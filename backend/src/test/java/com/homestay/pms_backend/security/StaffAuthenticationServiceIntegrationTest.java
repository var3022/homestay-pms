package com.homestay.pms_backend.security;

import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IsolatedDatabaseTest
@DisplayName("Step 3.3C.2: Staff Identity Lookup & Secure Password Verification Integration Tests")
class StaffAuthenticationServiceIntegrationTest {

    @Autowired
    private StaffAuthenticationService authService;

    @Autowired
    private StaffIdentityLookup identityLookup;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    @Qualifier("authJdbcTemplate")
    private JdbcTemplate authJdbcTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate; // pms_app

    @Autowired
    private Environment environment;

    private JdbcTemplate adminJdbcTemplate;

    private static final String TEST_EMAIL_DOMAIN = "@test-auth.com";
    private static final String DEFAULT_RAW_PASSWORD = "ValidSecurePass#2026!";

    @BeforeEach
    void setUp() {
        String adminPass = environment.getProperty("TEST_FLYWAY_PASSWORD");
        if (adminPass == null || adminPass.isBlank()) {
            adminPass = environment.getProperty("spring.flyway.password", "disposable_pass");
        }
        String dbUrl = environment.getProperty("auth.datasource.url", "jdbc:postgresql://localhost:5433/v7_disposable_db");

        DriverManagerDataSource adminDs = new DriverManagerDataSource();
        adminDs.setDriverClassName("org.postgresql.Driver");
        adminDs.setUrl(dbUrl);
        adminDs.setUsername("pms_admin");
        adminDs.setPassword(adminPass);
        adminJdbcTemplate = new JdbcTemplate(adminDs);

        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        authService.setPostVerificationHookForTesting(null);
        cleanupTestData();
    }

    private void cleanupTestData() {
        if (adminJdbcTemplate != null) {
            adminJdbcTemplate.execute(
                    "ALTER TABLE staff_security_audit_events DISABLE TRIGGER trg_staff_security_audit_events_immutable; " +
                    "DELETE FROM staff_security_audit_events WHERE email LIKE '%" + TEST_EMAIL_DOMAIN + "'; " +
                    "DELETE FROM staff_users WHERE email LIKE '%" + TEST_EMAIL_DOMAIN + "'; " +
                    "ALTER TABLE staff_security_audit_events ENABLE TRIGGER trg_staff_security_audit_events_immutable;"
            );
        }
    }

    private UUID createTestStaff(String email, String rawPassword, boolean isActive, boolean isLocked,
                                 int failedAttempts, Instant lockoutUntil) {
        UUID id = UUID.randomUUID();
        String hash = passwordEncoder.encode(rawPassword);
        adminJdbcTemplate.update(
                """
                INSERT INTO staff_users (
                    id, email, password_hash, first_name, last_name, phone,
                    is_active, is_locked, failed_login_attempts, lockout_until,
                    last_login_at, password_changed_at, created_at, updated_at
                ) VALUES (?, ?, ?, 'Test', 'Staff', '+919999999999', ?, ?, ?, ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id,
                email.trim().toLowerCase(),
                hash,
                isActive,
                isLocked,
                failedAttempts,
                lockoutUntil != null ? Timestamp.from(lockoutUntil) : null
        );
        return id;
    }

    // =========================================================================
    // 1. Valid BCrypt password is accepted
    // =========================================================================

    @Test
    @DisplayName("1. Valid BCrypt password is accepted and returns minimal authenticated identity")
    void shouldAcceptValidBcryptPassword() {
        String email = "alice.valid." + UUID.randomUUID() + TEST_EMAIL_DOMAIN;
        UUID userId = createTestStaff(email, DEFAULT_RAW_PASSWORD, true, false, 0, null);

        AuthenticatedStaff staff = authService.authenticate(email, DEFAULT_RAW_PASSWORD, "127.0.0.1", "JUnit-Test");

        assertThat(staff).isNotNull();
        assertThat(staff.id()).isEqualTo(userId);
        assertThat(staff.email()).isEqualTo(email);
        assertThat(staff.firstName()).isEqualTo("Test");
        assertThat(staff.lastName()).isEqualTo("Staff");
        assertThat(staff.phone()).isEqualTo("+919999999999");

        // Verify database audit log
        List<Map<String, Object>> audits = adminJdbcTemplate.queryForList(
                "SELECT event_type, email FROM staff_security_audit_events WHERE email = ? ORDER BY performed_at DESC",
                email
        );
        assertThat(audits).isNotEmpty();
        assertThat(audits.getFirst().get("event_type")).isEqualTo("LOGIN_SUCCESS");
    }

    // =========================================================================
    // 2. Incorrect password is rejected
    // =========================================================================

    @Test
    @DisplayName("2. Incorrect password is rejected, incrementing failed attempts and returning generic failure")
    void shouldRejectIncorrectPassword() {
        String email = "bob.wrongpass." + UUID.randomUUID() + TEST_EMAIL_DOMAIN;
        createTestStaff(email, DEFAULT_RAW_PASSWORD, true, false, 0, null);

        assertThatThrownBy(() -> authService.authenticate(email, "WrongPassword#999", "127.0.0.1", "JUnit-Test"))
                .isInstanceOf(StaffAuthenticationException.class)
                .hasMessage("Invalid email or password.");

        Map<String, Object> user = adminJdbcTemplate.queryForMap(
                "SELECT failed_login_attempts, is_locked FROM staff_users WHERE email = ?",
                email
        );
        assertThat(user.get("failed_login_attempts")).isEqualTo(1);
        assertThat(user.get("is_locked")).isEqualTo(false);

        List<Map<String, Object>> audits = adminJdbcTemplate.queryForList(
                "SELECT event_type FROM staff_security_audit_events WHERE email = ? ORDER BY performed_at DESC",
                email
        );
        assertThat(audits).isNotEmpty();
        assertThat(audits.getFirst().get("event_type")).isEqualTo("LOGIN_FAILED");
    }

    // =========================================================================
    // 3. Nonexistent email is rejected without exposing account existence
    // =========================================================================

    @Test
    @DisplayName("3. Nonexistent email is rejected with identical generic message and constant-time dummy match")
    void shouldRejectNonexistentEmailWithoutOracle() {
        String nonexistentEmail = "ghost.nonexistent." + UUID.randomUUID() + TEST_EMAIL_DOMAIN;

        assertThatThrownBy(() -> authService.authenticate(nonexistentEmail, "AnyPassword#123", "127.0.0.1", "JUnit-Test"))
                .isInstanceOf(StaffAuthenticationException.class)
                .hasMessage("Invalid email or password.");

        List<Map<String, Object>> audits = adminJdbcTemplate.queryForList(
                "SELECT event_type FROM staff_security_audit_events WHERE email = ? ORDER BY performed_at DESC",
                nonexistentEmail
        );
        assertThat(audits).isNotEmpty();
        assertThat(audits.getFirst().get("event_type")).isEqualTo("LOGIN_FAILED_UNKNOWN_USER");
    }

    // =========================================================================
    // 4. Email normalization works
    // =========================================================================

    @Test
    @DisplayName("4. Email normalization strips whitespace and converts uppercase consistently")
    void shouldNormalizeEmailAddress() {
        String rawEmail = "charlie.norm." + UUID.randomUUID() + TEST_EMAIL_DOMAIN;
        createTestStaff(rawEmail, DEFAULT_RAW_PASSWORD, true, false, 0, null);

        String messyEmailInput = "   " + rawEmail.toUpperCase() + "   ";
        AuthenticatedStaff staff = authService.authenticate(messyEmailInput, DEFAULT_RAW_PASSWORD, "127.0.0.1", "JUnit-Test");

        assertThat(staff).isNotNull();
        assertThat(staff.email()).isEqualTo(rawEmail);
    }

    // =========================================================================
    // 5. Disabled staff cannot authenticate
    // =========================================================================

    @Test
    @DisplayName("5. Deactivated staff cannot authenticate even with valid credentials")
    void shouldRejectDeactivatedStaff() {
        String email = "david.disabled." + UUID.randomUUID() + TEST_EMAIL_DOMAIN;
        createTestStaff(email, DEFAULT_RAW_PASSWORD, false, false, 0, null);

        assertThatThrownBy(() -> authService.authenticate(email, DEFAULT_RAW_PASSWORD, "127.0.0.1", "JUnit-Test"))
                .isInstanceOf(StaffAuthenticationException.class)
                .hasMessage("Invalid email or password.");

        List<Map<String, Object>> audits = adminJdbcTemplate.queryForList(
                "SELECT event_type FROM staff_security_audit_events WHERE email = ? ORDER BY performed_at DESC",
                email
        );
        assertThat(audits).isNotEmpty();
        assertThat(audits.getFirst().get("event_type")).isEqualTo("LOGIN_FAILED_DEACTIVATED_USER");
    }

    // =========================================================================
    // 6. Locked staff cannot authenticate, including with correct password
    // =========================================================================

    @Test
    @DisplayName("6. Locked staff cannot authenticate, even with correct password")
    void shouldRejectLockedStaffWithCorrectPassword() {
        String email = "eve.locked." + UUID.randomUUID() + TEST_EMAIL_DOMAIN;
        Instant lockoutUntil = Instant.now().plus(Duration.ofMinutes(15));
        createTestStaff(email, DEFAULT_RAW_PASSWORD, true, true, 5, lockoutUntil);

        assertThatThrownBy(() -> authService.authenticate(email, DEFAULT_RAW_PASSWORD, "127.0.0.1", "JUnit-Test"))
                .isInstanceOf(StaffAuthenticationException.class)
                .hasMessage("Invalid email or password.");

        Map<String, Object> user = adminJdbcTemplate.queryForMap(
                "SELECT is_locked, lockout_until FROM staff_users WHERE email = ?",
                email
        );
        assertThat(user.get("is_locked")).isEqualTo(true);
        assertThat(user.get("lockout_until")).isNotNull();

        List<Map<String, Object>> audits = adminJdbcTemplate.queryForList(
                "SELECT event_type FROM staff_security_audit_events WHERE email = ? ORDER BY performed_at DESC",
                email
        );
        assertThat(audits).isNotEmpty();
        assertThat(audits.getFirst().get("event_type")).isEqualTo("LOGIN_REJECTED_LOCKED_ACCOUNT");
    }

    // =========================================================================
    // 7. Five failed attempts trigger the V9 lockout policy
    // =========================================================================

    @Test
    @DisplayName("7. Five failed attempts trigger account lockout for 15 minutes")
    void shouldTriggerLockoutOnFifthFailedAttempt() {
        String email = "frank.bruteforce." + UUID.randomUUID() + TEST_EMAIL_DOMAIN;
        createTestStaff(email, DEFAULT_RAW_PASSWORD, true, false, 0, null);

        for (int i = 1; i <= 4; i++) {
            final int attempt = i;
            assertThatThrownBy(() -> authService.authenticate(email, "Wrong#" + attempt, "127.0.0.1", "JUnit-Test"))
                    .isInstanceOf(StaffAuthenticationException.class);

            Map<String, Object> user = adminJdbcTemplate.queryForMap(
                    "SELECT failed_login_attempts, is_locked, lockout_until FROM staff_users WHERE email = ?",
                    email
            );
            assertThat(user.get("failed_login_attempts")).isEqualTo(attempt);
            assertThat(user.get("is_locked")).isEqualTo(false);
            assertThat(user.get("lockout_until")).isNull();
        }

        // 5th attempt locks the account
        assertThatThrownBy(() -> authService.authenticate(email, "Wrong#5", "127.0.0.1", "JUnit-Test"))
                .isInstanceOf(StaffAuthenticationException.class);

        Map<String, Object> lockedUser = adminJdbcTemplate.queryForMap(
                "SELECT failed_login_attempts, is_locked, lockout_until FROM staff_users WHERE email = ?",
                email
        );
        assertThat(lockedUser.get("failed_login_attempts")).isEqualTo(5);
        assertThat(lockedUser.get("is_locked")).isEqualTo(true);
        assertThat(lockedUser.get("lockout_until")).isNotNull();

        List<Map<String, Object>> audits = adminJdbcTemplate.queryForList(
                "SELECT event_type FROM staff_security_audit_events WHERE email = ? ORDER BY performed_at DESC",
                email
        );
        assertThat(audits).isNotEmpty();
        assertThat(audits.getFirst().get("event_type")).isEqualTo("ACCOUNT_LOCKED");
    }

    // =========================================================================
    // 8. Correct password during active lockout does not clear lockout
    // =========================================================================

    @Test
    @DisplayName("8. Correct password during active lockout does not clear the lockout")
    void shouldNotClearLockoutWithCorrectPassword() {
        String email = "grace.lockedstate." + UUID.randomUUID() + TEST_EMAIL_DOMAIN;
        Instant lockoutUntil = Instant.now().plus(Duration.ofMinutes(14));
        createTestStaff(email, DEFAULT_RAW_PASSWORD, true, true, 5, lockoutUntil);

        // Attempt login with correct password during active lockout
        assertThatThrownBy(() -> authService.authenticate(email, DEFAULT_RAW_PASSWORD, "127.0.0.1", "JUnit-Test"))
                .isInstanceOf(StaffAuthenticationException.class);

        Map<String, Object> user = adminJdbcTemplate.queryForMap(
                "SELECT failed_login_attempts, is_locked, lockout_until FROM staff_users WHERE email = ?",
                email
        );
        assertThat(user.get("is_locked")).isEqualTo(true);
        assertThat(user.get("failed_login_attempts")).isEqualTo(5);
        assertThat(user.get("lockout_until")).isNotNull();

        List<Map<String, Object>> audits = adminJdbcTemplate.queryForList(
                "SELECT event_type FROM staff_security_audit_events WHERE email = ? ORDER BY performed_at DESC",
                email
        );
        assertThat(audits).isNotEmpty();
        assertThat(audits.getFirst().get("event_type")).isEqualTo("LOGIN_REJECTED_LOCKED_ACCOUNT");
    }

    // =========================================================================
    // 9. Expired lockouts follow the database-defined recovery policy
    // =========================================================================

    @Test
    @DisplayName("9. Expired lockout allows successful authentication and clears lockout state")
    void shouldRecoverFromExpiredLockout() {
        String email = "helen.expiredlock." + UUID.randomUUID() + TEST_EMAIL_DOMAIN;
        Instant expiredLockout = Instant.now().minus(Duration.ofMinutes(2));
        createTestStaff(email, DEFAULT_RAW_PASSWORD, true, true, 5, expiredLockout);

        AuthenticatedStaff staff = authService.authenticate(email, DEFAULT_RAW_PASSWORD, "127.0.0.1", "JUnit-Test");

        assertThat(staff).isNotNull();
        assertThat(staff.email()).isEqualTo(email);

        Map<String, Object> user = adminJdbcTemplate.queryForMap(
                "SELECT failed_login_attempts, is_locked, lockout_until, last_login_at FROM staff_users WHERE email = ?",
                email
        );
        assertThat(user.get("is_locked")).isEqualTo(false);
        assertThat(user.get("failed_login_attempts")).isEqualTo(0);
        assertThat(user.get("lockout_until")).isNull();
        assertThat(user.get("last_login_at")).isNotNull();
    }

    // =========================================================================
    // 10. Successful authentication resets failed attempts
    // =========================================================================

    @Test
    @DisplayName("10. Successful authentication resets accumulated failed attempts to zero")
    void shouldResetFailedAttemptsOnSuccess() {
        String email = "ian.resetattempts." + UUID.randomUUID() + TEST_EMAIL_DOMAIN;
        createTestStaff(email, DEFAULT_RAW_PASSWORD, true, false, 3, null);

        AuthenticatedStaff staff = authService.authenticate(email, DEFAULT_RAW_PASSWORD, "127.0.0.1", "JUnit-Test");

        assertThat(staff).isNotNull();
        Map<String, Object> user = adminJdbcTemplate.queryForMap(
                "SELECT failed_login_attempts, is_locked FROM staff_users WHERE email = ?",
                email
        );
        assertThat(user.get("failed_login_attempts")).isEqualTo(0);
        assertThat(user.get("is_locked")).isEqualTo(false);
    }

    // =========================================================================
    // 11. Concurrent failed attempts cannot bypass lockout
    // =========================================================================

    @Test
    @DisplayName("11. Concurrent failed attempts cannot bypass lockout due to database row serialization")
    void shouldSerializeConcurrentFailedAttemptsAndLockAccount() throws InterruptedException {
        String email = "judy.concurrent." + UUID.randomUUID() + TEST_EMAIL_DOMAIN;
        createTestStaff(email, DEFAULT_RAW_PASSWORD, true, false, 0, null);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    authService.authenticate(email, "WrongPassword#Concur", "127.0.0.1", "JUnit-Test");
                } catch (StaffAuthenticationException expected) {
                    failures.incrementAndGet();
                } catch (Exception e) {
                    // unexpected
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = finishLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(failures.get()).isEqualTo(threadCount);

        Map<String, Object> user = adminJdbcTemplate.queryForMap(
                "SELECT failed_login_attempts, is_locked, lockout_until FROM staff_users WHERE email = ?",
                email
        );
        assertThat(user.get("is_locked")).isEqualTo(true);
        assertThat(user.get("lockout_until")).isNotNull();
        assertThat(((Number) user.get("failed_login_attempts")).intValue()).isGreaterThanOrEqualTo(5);

        // Attempting with correct password now must fail
        assertThatThrownBy(() -> authService.authenticate(email, DEFAULT_RAW_PASSWORD, "127.0.0.1", "JUnit-Test"))
                .isInstanceOf(StaffAuthenticationException.class);
    }

    // =========================================================================
    // 12. User disabled between credential verification & login-success is rejected
    // =========================================================================

    @Test
    @DisplayName("12. User disabled between BCrypt verification and login-success recording is rejected")
    void shouldRejectUserDisabledDuringVerificationRace() {
        String email = "kevin.race." + UUID.randomUUID() + TEST_EMAIL_DOMAIN;
        UUID userId = createTestStaff(email, DEFAULT_RAW_PASSWORD, true, false, 0, null);

        // Simulate concurrent admin deactivating user right after BCrypt verification
        authService.setPostVerificationHookForTesting(id -> {
            adminJdbcTemplate.update("UPDATE staff_users SET is_active = FALSE WHERE id = ?", id);
        });

        assertThatThrownBy(() -> authService.authenticate(email, DEFAULT_RAW_PASSWORD, "127.0.0.1", "JUnit-Test"))
                .isInstanceOf(StaffAuthenticationException.class)
                .hasMessage("Invalid email or password.");

        List<Map<String, Object>> audits = adminJdbcTemplate.queryForList(
                "SELECT event_type FROM staff_security_audit_events WHERE staff_user_id = ? ORDER BY performed_at DESC",
                userId
        );
        assertThat(audits).isNotEmpty();
        assertThat(audits.getFirst().get("event_type")).isEqualTo("LOGIN_FAILED_DEACTIVATED_USER");
    }

    // =========================================================================
    // 13. pms_app cannot read password hashes or execute login-success routines
    // =========================================================================

    @Test
    @DisplayName("13. Least privilege: pms_app is strictly denied reading password_hash or executing fn_record_staff_login_success")
    void shouldDenyPmsAppPrivilegedAuthAccess() {
        // Direct read on password_hash is denied
        assertThatThrownBy(() -> jdbcTemplate.queryForList("SELECT password_hash FROM staff_users"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_users");

        // Direct execution of fn_record_staff_login_success is denied
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                "SELECT fn_record_staff_login_success(?, ?, ?)",
                Boolean.class,
                UUID.randomUUID(),
                null,
                null
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_record_staff_login_success");
    }

    // =========================================================================
    // 14. No raw passwords or hashes appear in logs or exception messages
    // =========================================================================

    @Test
    @DisplayName("14. Sensitive credentials hygiene: toString() redacts hashes and exceptions contain no raw secrets")
    void shouldEnsureNoSecretsInToStringOrExceptions() {
        String rawSecret = "SuperConfidentialPass#2026!";
        String fakeHash = "$2a$12$e80yq7KjVpEw3W6xK9qXxeQjJcM1q4RkQ9aVbY2vK8n1o2p3q4r5s";

        StaffAuthAccount account = new StaffAuthAccount(
                UUID.randomUUID(),
                "test@test-auth.com",
                fakeHash,
                "First",
                "Last",
                "+1234567890",
                true,
                false,
                0,
                null,
                null,
                null,
                null,
                null
        );

        String accountStr = account.toString();
        assertThat(accountStr).contains("passwordHash=[REDACTED]");
        assertThat(accountStr).doesNotContain(fakeHash);
        assertThat(accountStr).doesNotContain(rawSecret);

        // Exception message verification
        StaffAuthenticationException ex = new StaffAuthenticationException();
        assertThat(ex.getMessage()).isEqualTo("Invalid email or password.");
        assertThat(ex.getMessage()).doesNotContain(rawSecret);
        assertThat(ex.getMessage()).doesNotContain(fakeHash);
    }

    // =========================================================================
    // 15. Task 1: Concurrent password change race condition hardening
    // =========================================================================

    @Test
    @DisplayName("15. Concurrent password change during verification aborts in-flight login and denies old password")
    void shouldRejectLoginWhenPasswordChangedConcurrentlyDuringVerification() {
        String email = "concur.passchange." + UUID.randomUUID() + TEST_EMAIL_DOMAIN;
        String oldPassword = "OldValidPassword#123";
        String newPassword = "NewAdminSetPassword#456";
        UUID userId = createTestStaff(email, oldPassword, true, false, 0, null);

        // Simulate concurrent admin changing password while BCrypt is in flight
        authService.setPostVerificationHookForTesting(id -> {
            String newHash = passwordEncoder.encode(newPassword);
            adminJdbcTemplate.update(
                    "UPDATE staff_users SET password_hash = ?, password_changed_at = CURRENT_TIMESTAMP WHERE id = ?",
                    newHash,
                    id
            );
        });

        // Login with old password must be aborted by the final authoritative database credential check
        assertThatThrownBy(() -> authService.authenticate(email, oldPassword, "127.0.0.1", "JUnit-Test"))
                .isInstanceOf(StaffAuthenticationException.class)
                .hasMessage("Invalid email or password.");

        // Clear test hook
        authService.setPostVerificationHookForTesting(null);

        // New password succeeds
        AuthenticatedStaff staff = authService.authenticate(email, newPassword, "127.0.0.1", "JUnit-Test");
        assertThat(staff).isNotNull();
        assertThat(staff.id()).isEqualTo(userId);

        // Old password rejected permanently
        assertThatThrownBy(() -> authService.authenticate(email, oldPassword, "127.0.0.1", "JUnit-Test"))
                .isInstanceOf(StaffAuthenticationException.class)
                .hasMessage("Invalid email or password.");
    }
}
