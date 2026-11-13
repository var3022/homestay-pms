package com.homestay.pms_backend.security.refreshtoken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Production implementation of the RefreshTokenService.
 *
 * Implements strict security controls:
 * 1. Dual-DataSource isolation: bound exclusively to authJdbcTemplate and authTransactionTemplate (pms_auth).
 * 2. High-entropy CSPRNG raw token generation; database stores only SHA-256 hashes (never plaintext).
 * 3. Atomic rotation via PostgreSQL routine fn_rotate_refresh_token with 10-second concurrency grace window.
 * 4. Automatic token-family revocation on replay attack detection with committed security audit events.
 * 5. Replay and deactivation transactions commit before the service returns failure to callers.
 * 6. Authoritative row-locking state checks prevent session creation for locked or deactivated accounts.
 * 7. Cross-staff session manipulation prevention during logout.
 * 8. Redaction of all raw tokens and hashes in logs and toString() representations.
 */
@Service
@ConditionalOnProperty(prefix = "auth.datasource", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RefreshTokenProperties.class)
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenServiceImpl.class);

    private final RefreshTokenGenerator tokenGenerator;
    private final RefreshTokenProperties properties;
    private final JdbcTemplate authJdbcTemplate;
    private final TransactionTemplate authTransactionTemplate;
    private final Clock clock;

    @Autowired
    public RefreshTokenServiceImpl(
            RefreshTokenGenerator tokenGenerator,
            RefreshTokenProperties properties,
            @Qualifier("authJdbcTemplate") JdbcTemplate authJdbcTemplate,
            @Qualifier("authTransactionTemplate") TransactionTemplate authTransactionTemplate,
            Clock clock
    ) {
        this.tokenGenerator = tokenGenerator;
        this.properties = properties;
        this.authJdbcTemplate = authJdbcTemplate;
        this.authTransactionTemplate = authTransactionTemplate;
        this.clock = clock;
    }

    public RefreshTokenServiceImpl(
            RefreshTokenGenerator tokenGenerator,
            RefreshTokenProperties properties,
            @Qualifier("authJdbcTemplate") JdbcTemplate authJdbcTemplate,
            @Qualifier("authTransactionTemplate") TransactionTemplate authTransactionTemplate
    ) {
        this(tokenGenerator, properties, authJdbcTemplate, authTransactionTemplate, Clock.systemUTC());
    }

    @Override
    public IssuedRefreshToken issueInitialToken(UUID staffUserId, String clientIp, String userAgent) {
        if (staffUserId == null) {
            throw new IllegalArgumentException("Staff user ID must not be null");
        }

        String sanitizedIp = sanitizeIp(clientIp);
        String sanitizedUserAgent = sanitizeUserAgent(userAgent);

        String rawToken = tokenGenerator.generateToken();
        String tokenHash = tokenGenerator.hashToken(rawToken);
        UUID tokenFamily = UUID.randomUUID();

        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant expiresAt = now.plus(properties.getTtlDays(), ChronoUnit.DAYS);

        UUID tokenId = authTransactionTemplate.execute(status -> {
            Map<String, Object> staff;
            try {
                staff = authJdbcTemplate.queryForMap(
                        "SELECT id, email, is_active, is_locked, lockout_until FROM staff_users WHERE id = ? FOR UPDATE",
                        staffUserId
                );
            } catch (EmptyResultDataAccessException e) {
                log.warn("Attempted to issue refresh token for non-existent staff user [{}]", staffUserId);
                throw new InvalidRefreshTokenException("Staff account does not exist");
            }

            boolean isActive = Boolean.TRUE.equals(staff.get("is_active"));
            if (!isActive) {
                log.warn("Attempted to issue refresh token for deactivated staff user [{}]", staffUserId);
                throw new InvalidRefreshTokenException("Staff account is deactivated");
            }

            boolean isLocked = Boolean.TRUE.equals(staff.get("is_locked"));
            if (isLocked) {
                Timestamp lockoutUntil = (Timestamp) staff.get("lockout_until");
                if (lockoutUntil == null || lockoutUntil.toInstant().isAfter(clock.instant())) {
                    log.warn("Attempted to issue refresh token for locked staff user [{}]", staffUserId);
                    throw new InvalidRefreshTokenException("Staff account is locked");
                }
            }

            return authJdbcTemplate.queryForObject(
                    """
                    INSERT INTO staff_refresh_tokens (
                        staff_user_id, token_hash, token_family, sequence_number,
                        is_revoked, revoked_at, revoked_reason, expires_at, created_at,
                        last_used_at, user_agent, ip_address
                    ) VALUES (
                        ?, ?, ?, 1,
                        FALSE, NULL, NULL, ?, ?,
                        NULL, ?, ?
                    ) RETURNING id
                    """,
                    UUID.class,
                    staffUserId,
                    tokenHash,
                    tokenFamily,
                    Timestamp.from(expiresAt),
                    Timestamp.from(now),
                    sanitizedUserAgent,
                    sanitizedIp
            );
        });

        return new IssuedRefreshToken(
                rawToken,
                tokenId,
                tokenFamily,
                1,
                expiresAt,
                staffUserId
        );
    }

    @Override
    public RotatedRefreshToken rotateToken(String rawRefreshToken, String clientIp, String userAgent) {
        if (!tokenGenerator.isValidTokenFormat(rawRefreshToken)) {
            log.warn("Rejected rotation attempt: malformed or unreadable token format");
            throw new InvalidRefreshTokenException("Invalid refresh token format");
        }

        String oldTokenHash = tokenGenerator.hashToken(rawRefreshToken);
        String newRawToken = tokenGenerator.generateToken();
        String newTokenHash = tokenGenerator.hashToken(newRawToken);

        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant newExpiresAt = now.plus(properties.getTtlDays(), ChronoUnit.DAYS);

        String sanitizedIp = sanitizeIp(clientIp);
        String sanitizedUserAgent = sanitizeUserAgent(userAgent);

        record RotationDbResult(
                boolean success,
                UUID newId,
                UUID staffUserId,
                UUID tokenFamily,
                int sequenceNumber,
                Instant expiresAt,
                String failureReason
        ) {}

        RotationDbResult dbResult = authTransactionTemplate.execute(status -> {
            UUID newId;
            try {
                newId = authJdbcTemplate.queryForObject(
                        "SELECT fn_rotate_refresh_token(?, ?, ?, ?, ?)",
                        UUID.class,
                        oldTokenHash,
                        newTokenHash,
                        Timestamp.from(newExpiresAt),
                        sanitizedUserAgent,
                        sanitizedIp
                );
            } catch (DataAccessException e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("Presented refresh token does not exist")) {
                    log.warn("Rejected rotation attempt: presented refresh token not found in database");
                    return new RotationDbResult(false, null, null, null, 0, null, "Presented refresh token does not exist");
                }
                log.error("Internal database error during refresh token rotation: {}", e.getMessage());
                throw e;
            }

            if (newId == null) {
                log.warn("Token rotation rejected by database routine fn_rotate_refresh_token");
                return new RotationDbResult(false, null, null, null, 0, null, "Token rotation rejected by database policy");
            }

            return authJdbcTemplate.queryForObject(
                    "SELECT id, staff_user_id, token_family, sequence_number, expires_at FROM staff_refresh_tokens WHERE id = ?",
                    (rs, rowNum) -> new RotationDbResult(
                            true,
                            rs.getObject("id", UUID.class),
                            rs.getObject("staff_user_id", UUID.class),
                            rs.getObject("token_family", UUID.class),
                            rs.getInt("sequence_number"),
                            rs.getTimestamp("expires_at").toInstant(),
                            null
                    ),
                    newId
            );
        });

        if (dbResult == null || !dbResult.success()) {
            throw new InvalidRefreshTokenException(
                    (dbResult != null && dbResult.failureReason() != null)
                            ? dbResult.failureReason()
                            : "Token rotation rejected"
            );
        }

        UUID staffUserId = dbResult.staffUserId();
        try {
            Map<String, Object> staff = authJdbcTemplate.queryForMap(
                    "SELECT is_locked, lockout_until FROM staff_users WHERE id = ?",
                    staffUserId
            );
            boolean isLocked = Boolean.TRUE.equals(staff.get("is_locked"));
            if (isLocked) {
                Timestamp lockoutUntil = (Timestamp) staff.get("lockout_until");
                if (lockoutUntil == null || lockoutUntil.toInstant().isAfter(clock.instant())) {
                    log.warn("Refusing to return rotated token for locked staff user [{}]", staffUserId);
                    revokeSession(newRawToken, staffUserId, "system:auth");
                    throw new InvalidRefreshTokenException("Staff account is locked");
                }
            }
        } catch (DataAccessException e) {
            log.error("Failed to verify staff account lock state during rotation: {}", e.getMessage());
            throw e;
        }

        return new RotatedRefreshToken(
                newRawToken,
                dbResult.newId(),
                dbResult.tokenFamily(),
                dbResult.sequenceNumber(),
                dbResult.expiresAt(),
                dbResult.staffUserId()
        );
    }

    @Override
    public void revokeSession(String rawRefreshToken, UUID expectedStaffUserId, String actorId) {
        if (!tokenGenerator.isValidTokenFormat(rawRefreshToken)) {
            log.debug("revokeSession called with invalid token format; ignoring");
            return;
        }

        String tokenHash = tokenGenerator.hashToken(rawRefreshToken);
        String effectiveActor = (actorId != null && !actorId.isBlank()) ? actorId : "system:auth";

        authTransactionTemplate.executeWithoutResult(status -> {
            List<Map<String, Object>> rows = authJdbcTemplate.queryForList(
                    "SELECT token_family, staff_user_id, is_revoked FROM staff_refresh_tokens WHERE token_hash = ?",
                    tokenHash
            );

            if (rows.isEmpty()) {
                log.debug("revokeSession: token hash not found; no-op");
                return;
            }

            Map<String, Object> row = rows.get(0);
            UUID tokenFamily = (UUID) row.get("token_family");
            UUID actualStaffUserId = (UUID) row.get("staff_user_id");

            if (expectedStaffUserId != null && !expectedStaffUserId.equals(actualStaffUserId)) {
                log.warn("Cross-staff session revocation rejected: caller [{}] attempted to revoke session for [{}]",
                        expectedStaffUserId, actualStaffUserId);
                throw new InvalidRefreshTokenException("Cannot revoke session belonging to another staff member");
            }

            authJdbcTemplate.queryForObject(
                    "SELECT fn_revoke_refresh_token_family(?, ?, ?)",
                    Integer.class,
                    tokenFamily,
                    "LOGOUT",
                    effectiveActor
            );
        });
    }

    private String sanitizeIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "127.0.0.1";
        }
        String trimmed = ip.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return (trimmed.length() > 50) ? trimmed.substring(0, 50) : trimmed;
    }

    private String sanitizeUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown";
        }
        String clean = userAgent.replaceAll("[\\p{Cntrl}\\r\\n\\t]", "").trim();
        return (clean.length() > 255) ? clean.substring(0, 255) : clean;
    }
}
