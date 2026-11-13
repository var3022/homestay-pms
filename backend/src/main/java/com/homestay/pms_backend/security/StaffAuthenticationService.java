package com.homestay.pms_backend.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Enterprise Staff Credential Verification Service.
 *
 * Implements least-privilege staff authentication over the dedicated pms_auth DataSource:
 * 1. Constant-time dummy BCrypt verification for nonexistent accounts to mitigate timing attacks.
 * 2. Password matching via BCryptPasswordEncoder (work factor 12).
 * 3. Atomic database login failure attempt recording (enforcing the 5-attempt/15-minute lockout).
 * 4. Post-verification atomic database login success assertion via fn_record_staff_login_success.
 * 5. Deterministic concurrent password mutation protection under row-level serialization.
 * 6. Complete sanitization: credentials and hashes are never logged, leaked, or exposed.
 */
@Service
@ConditionalOnProperty(prefix = "auth.datasource", name = "enabled", havingValue = "true")
public class StaffAuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(StaffAuthenticationService.class);

    // Pre-computed syntactically valid BCrypt hash with cost 12 for constant-time dummy verification
    public static final String DUMMY_BCRYPT_HASH = "$2a$12$e80yq7KjVpEw3W6xK9qXxeQjJcM1q4RkQ9aVbY2vK8n1o2p3q4r5s";

    private final StaffIdentityLookup staffIdentityLookup;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate authJdbcTemplate;
    private final TransactionTemplate authTransactionTemplate;

    // Package-private test hook to verify race condition between BCrypt verification and login-success assertion
    private Consumer<UUID> postVerificationHookForTesting;

    public StaffAuthenticationService(
            StaffIdentityLookup staffIdentityLookup,
            PasswordEncoder passwordEncoder,
            @Qualifier("authJdbcTemplate") JdbcTemplate authJdbcTemplate,
            @Qualifier("authTransactionTemplate") TransactionTemplate authTransactionTemplate
    ) {
        this.staffIdentityLookup = staffIdentityLookup;
        this.passwordEncoder = passwordEncoder;
        this.authJdbcTemplate = authJdbcTemplate;
        this.authTransactionTemplate = authTransactionTemplate;
    }

    /**
     * Authenticates a staff user by email and raw password.
     *
     * @param email       Staff email address
     * @param rawPassword Plaintext password supplied by the caller
     * @param ipAddress   Caller IP address (for audit logging)
     * @param userAgent   Caller User-Agent (for audit logging)
     * @return Minimal AuthenticatedStaff identity upon success
     * @throws StaffAuthenticationException with uniform generic message if authentication fails
     */
    public AuthenticatedStaff authenticate(String email, String rawPassword, String ipAddress, String userAgent) {
        // Step 1: Input Validation
        if (email == null || email.isBlank() || email.indexOf('@') <= 0 || email.indexOf('@') == email.length() - 1
                || rawPassword == null || rawPassword.isBlank()) {
            // Mitigate timing discrepancies by performing dummy BCrypt comparison
            passwordEncoder.matches(
                    (rawPassword != null && !rawPassword.isBlank()) ? rawPassword : "invalid_candidate",
                    DUMMY_BCRYPT_HASH
            );
            throw new StaffAuthenticationException();
        }

        String normalizedEmail = StaffIdentityLookup.normalizeEmail(email);

        // Step 2: Staff Identity Lookup via pms_auth
        Optional<StaffAuthAccount> maybeAccount = staffIdentityLookup.findByEmail(normalizedEmail);

        if (maybeAccount.isEmpty()) {
            // Mitigate account enumeration via constant-time dummy BCrypt matching
            passwordEncoder.matches(rawPassword, DUMMY_BCRYPT_HASH);
            // Record failed attempt in database (triggers LOGIN_FAILED_UNKNOWN_USER audit event)
            recordFailedAttempt(normalizedEmail, ipAddress, userAgent);
            throw new StaffAuthenticationException();
        }

        StaffAuthAccount account = maybeAccount.get();

        // Step 3: Cryptographic Password Verification
        boolean passwordMatches = passwordEncoder.matches(rawPassword, account.passwordHash());

        if (!passwordMatches) {
            // Record failed attempt in database (increments failed attempts, triggers lockout if >= 5)
            recordFailedAttempt(normalizedEmail, ipAddress, userAgent);
            throw new StaffAuthenticationException();
        }

        // Test hook to simulate concurrent administrative mutations (deactivation/lockout/password change)
        if (postVerificationHookForTesting != null) {
            postVerificationHookForTesting.accept(account.id());
        }

        // Step 4: Atomic Database Login Success Assertion
        // Under row-level lock (FOR UPDATE), verifies that:
        // a) Credentials (password_hash & password_changed_at) have not mutated since Step 2 lookup
        // b) Account is active (is_active = TRUE)
        // c) Account is not under an active lockout (lockout_until <= CURRENT_TIMESTAMP)
        boolean dbSuccess = recordLoginSuccess(account, ipAddress, userAgent);

        if (!dbSuccess) {
            // Database rejected login success (e.g., account deactivated, under active lockout, or password mutated)
            throw new StaffAuthenticationException();
        }

        return new AuthenticatedStaff(
                account.id(),
                account.email(),
                account.firstName(),
                account.lastName(),
                account.phone()
        );
    }

    public AuthenticatedStaff authenticate(String email, String rawPassword) {
        return authenticate(email, rawPassword, null, null);
    }

    private boolean recordFailedAttempt(String email, String ipAddress, String userAgent) {
        Boolean result = authJdbcTemplate.queryForObject(
                "SELECT fn_record_staff_login_attempt(?, false, ?, ?)",
                Boolean.class,
                email,
                ipAddress,
                userAgent
        );
        return Boolean.TRUE.equals(result);
    }

    private boolean recordLoginSuccess(StaffAuthAccount initialAccount, String ipAddress, String userAgent) {
        return Boolean.TRUE.equals(authTransactionTemplate.execute(status -> {
            Map<String, Object> currentRecord;
            try {
                currentRecord = authJdbcTemplate.queryForMap(
                        "SELECT password_hash, password_changed_at FROM staff_users WHERE id = ? FOR UPDATE",
                        initialAccount.id()
                );
            } catch (EmptyResultDataAccessException e) {
                log.warn("Staff user ID [{}] no longer exists. Login aborted.", initialAccount.id());
                return false;
            }

            String currentHash = (String) currentRecord.get("password_hash");
            Timestamp currentChangedTs = (Timestamp) currentRecord.get("password_changed_at");
            Instant currentChangedAt = currentChangedTs != null ? currentChangedTs.toInstant() : null;

            // Invariant: The stored password hash and password_changed_at must strictly match
            // the snapshot verified by BCrypt in application memory.
            if (!Objects.equals(initialAccount.passwordHash(), currentHash)
                    || !Objects.equals(initialAccount.passwordChangedAt(), currentChangedAt)) {
                log.warn("Concurrent credential mutation detected for staff user ID [{}]. In-flight login aborted.", initialAccount.id());
                return false;
            }

            Boolean result = authJdbcTemplate.queryForObject(
                    "SELECT fn_record_staff_login_success(?, ?, ?)",
                    Boolean.class,
                    initialAccount.id(),
                    ipAddress,
                    userAgent
            );

            return Boolean.TRUE.equals(result);
        }));
    }

    void setPostVerificationHookForTesting(Consumer<UUID> hook) {
        this.postVerificationHookForTesting = hook;
    }
}
