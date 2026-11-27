package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.enums.IdempotencyOperation;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.IdempotencyConflictException;
import com.homestay.pms_backend.exception.IdempotencyExpiredException;
import com.homestay.pms_backend.exception.IdempotencyInProgressException;
import com.homestay.pms_backend.security.AuthenticatedStaffPrincipal;
import com.homestay.pms_backend.security.authorization.StaffAuthorizationService;
import com.homestay.pms_backend.service.ClaimOutcome;
import com.homestay.pms_backend.service.IdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyServiceImpl implements IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyServiceImpl.class);

    public static final UUID ANONYMOUS_PRINCIPAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private static final String SQL_CLAIM_KEY = """
            INSERT INTO api_idempotency_keys (
                property_id, operation, principal_id, idempotency_key,
                request_fingerprint, state, expires_at
            ) VALUES (
                ?, ?, ?, ?,
                ?, 'IN_PROGRESS', CURRENT_TIMESTAMP + INTERVAL '24 hours'
            )
            ON CONFLICT (property_id, operation, principal_id, idempotency_key)
            DO NOTHING
            RETURNING id;
            """;

    private static final String SQL_FIND_EXISTING = """
            SELECT id, request_fingerprint, state, resource_id, expires_at
            FROM api_idempotency_keys
            WHERE property_id = ? AND operation = ? AND principal_id = ? AND idempotency_key = ?;
            """;

    private static final String SQL_COMPLETE_CLAIM = """
            UPDATE api_idempotency_keys
            SET state = 'COMPLETED',
                resource_id = ?,
                response_status = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?;
            """;

    private final JdbcTemplate jdbcTemplate;
    private final StaffAuthorizationService staffAuthorizationService;

    @Autowired
    public IdempotencyServiceImpl(
            JdbcTemplate jdbcTemplate,
            @Autowired(required = false) StaffAuthorizationService staffAuthorizationService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.staffAuthorizationService = staffAuthorizationService;
    }

    @Override
    public void validateKeyHeader(String idempotencyKey) {
        if (idempotencyKey == null) {
            return;
        }
        String trimmed = idempotencyKey.trim();
        if (trimmed.isEmpty() || trimmed.length() > 128) {
            throw new BusinessValidationException(
                    "Invalid Idempotency-Key header: must be non-empty and at most 128 characters.");
        }
    }

    @Override
    public UUID resolvePrincipalId() {
        if (staffAuthorizationService != null) {
            Optional<AuthenticatedStaffPrincipal> principalOpt = staffAuthorizationService.getCurrentPrincipal();
            if (principalOpt.isPresent()) {
                UUID staffId = principalOpt.get().staffUserId();
                if (staffId != null) {
                    return staffId;
                }
            }
        }
        return ANONYMOUS_PRINCIPAL_ID;
    }

    @Override
    @Transactional
    public ClaimOutcome claimKey(
            UUID propertyId,
            IdempotencyOperation operation,
            UUID principalId,
            String idempotencyKey,
            String fingerprint
    ) {
        if (propertyId == null) {
            throw new BusinessValidationException("Target propertyId is required when Idempotency-Key is provided.");
        }

        String originalTimeout = null;
        try {
            // Read session default timeout to allow restoration before inventory locking
            originalTimeout = jdbcTemplate.queryForObject("SHOW lock_timeout", String.class);
            jdbcTemplate.execute("SET LOCAL lock_timeout = '3000ms'");

            List<UUID> claimedIds = jdbcTemplate.query(
                    SQL_CLAIM_KEY,
                    (rs, rowNum) -> rs.getObject("id", UUID.class),
                    propertyId, operation.name(), principalId, idempotencyKey, fingerprint
            );

            if (!claimedIds.isEmpty()) {
                UUID claimId = claimedIds.get(0);
                restoreTimeout(originalTimeout);
                log.debug("Claimed idempotency key [{}] for op [{}] with claimId [{}]", idempotencyKey, operation, claimId);
                return new ClaimOutcome.Claimed(claimId);
            }

            // Conflict encountered: retrieve existing committed key
            List<ExistingKeyRecord> records = jdbcTemplate.query(
                    SQL_FIND_EXISTING,
                    (rs, rowNum) -> {
                        UUID id = rs.getObject("id", UUID.class);
                        String storedFingerprint = rs.getString("request_fingerprint");
                        String state = rs.getString("state");
                        UUID resourceId = rs.getObject("resource_id", UUID.class);
                        Timestamp expiresTs = rs.getTimestamp("expires_at");
                        Instant expiresAt = expiresTs != null ? expiresTs.toInstant() : Instant.EPOCH;
                        return new ExistingKeyRecord(id, storedFingerprint, state, resourceId, expiresAt);
                    },
                    propertyId, operation.name(), principalId, idempotencyKey
            );

            restoreTimeout(originalTimeout);

            if (records.isEmpty()) {
                throw new IdempotencyInProgressException("A request with this idempotency key is currently in progress. Please retry.");
            }

            ExistingKeyRecord existing = records.get(0);

            if (existing.expiresAt().isBefore(Instant.now())) {
                log.warn("Idempotency key [{}] expired at {}", idempotencyKey, existing.expiresAt());
                throw new IdempotencyExpiredException("The idempotency key has expired.");
            }

            if (!existing.fingerprint().equals(fingerprint)) {
                log.warn("Payload mismatch for idempotency key [{}]: expected [{}], got [{}]",
                        idempotencyKey, existing.fingerprint(), fingerprint);
                throw new IdempotencyConflictException("The idempotency key is already used with a different request payload.");
            }

            if (!"COMPLETED".equals(existing.state()) || existing.resourceId() == null) {
                log.info("Concurrent in-progress request detected for key [{}]", idempotencyKey);
                throw new IdempotencyInProgressException("A request with this idempotency key is currently in progress. Please retry.");
            }

            log.debug("Replaying idempotency key [{}] with resourceId [{}]", idempotencyKey, existing.resourceId());
            return new ClaimOutcome.Replay(existing.resourceId());
        } catch (DataAccessException dae) {
            SQLException sqlEx = findSqlException(dae);
            if (sqlEx != null && "55P03".equals(sqlEx.getSQLState())) {
                log.warn("Claim lock timeout (SQLSTATE 55P03) for key [{}]: {}", idempotencyKey, sqlEx.getMessage());
                throw new IdempotencyInProgressException("A request with this idempotency key is currently in progress. Please retry.");
            }
            throw dae;
        }
    }

    private void restoreTimeout(String originalTimeout) {
        try {
            if (originalTimeout != null && !originalTimeout.isBlank()) {
                jdbcTemplate.execute("SET LOCAL lock_timeout = '" + originalTimeout + "'");
            } else {
                jdbcTemplate.execute("RESET lock_timeout");
            }
        } catch (Exception e) {
            log.warn("Failed to restore lock_timeout to [{}]: {}", originalTimeout, e.getMessage());
        }
    }

    private static SQLException findSqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlEx) {
                return sqlEx;
            }
            current = current.getCause();
        }
        return null;
    }

    @Override
    @Transactional
    public void completeClaim(UUID claimId, UUID resourceId, int responseStatus) {
        int updated = jdbcTemplate.update(
                SQL_COMPLETE_CLAIM,
                resourceId, responseStatus, claimId
        );
        if (updated == 0) {
            log.warn("Failed to complete idempotency claim [{}] with resource [{}]", claimId, resourceId);
        }
    }

    private record ExistingKeyRecord(
            UUID id,
            String fingerprint,
            String state,
            UUID resourceId,
            Instant expiresAt
    ) {
    }
}
