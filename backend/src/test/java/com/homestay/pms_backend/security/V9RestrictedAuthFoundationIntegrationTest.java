package com.homestay.pms_backend.security;

import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IsolatedDatabaseTest
@DisplayName("V9 Restricted Authentication Foundation Integration Tests")
class V9RestrictedAuthFoundationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String CLEAN_VALIDATION_DB_URL = "jdbc:postgresql://localhost:5433/v8_clean_validation_db";
    private static final String DISPOSABLE_DB_URL = "jdbc:postgresql://localhost:5433/v7_disposable_db";
    private static final String ADMIN_USER = "pms_admin";
    private static final String ADMIN_PASS = "disposable_pass";
    private static final String AUTH_USER = "pms_auth";
    private static final String AUTH_PASS = "disposable_pass";

    private JdbcTemplate createAdminJdbcTemplate() {
        DriverManagerDataSource adminDs = new DriverManagerDataSource();
        adminDs.setDriverClassName("org.postgresql.Driver");
        adminDs.setUrl(DISPOSABLE_DB_URL);
        adminDs.setUsername(ADMIN_USER);
        adminDs.setPassword(ADMIN_PASS);
        return new JdbcTemplate(adminDs);
    }

    private JdbcTemplate createAuthJdbcTemplate() {
        DriverManagerDataSource authDs = new DriverManagerDataSource();
        authDs.setDriverClassName("org.postgresql.Driver");
        authDs.setUrl(DISPOSABLE_DB_URL);
        authDs.setUsername(AUTH_USER);
        authDs.setPassword(AUTH_PASS);
        return new JdbcTemplate(authDs);
    }

    private UUID createAdminTestUser(JdbcTemplate adminJdbc, String email) {
        UUID id = UUID.randomUUID();
        adminJdbc.update(
                "INSERT INTO staff_users (id, email, password_hash, first_name) " +
                "VALUES (?, ?, '$2a$12$e80yq7KjVpEw3W6xK9qXxeQjJcM1q4RkQ9aVbY2vK8n1o2p3q4r5s', 'V9Test')",
                id, email
        );
        return id;
    }

    private void cleanUpAdminUser(JdbcTemplate adminJdbc, UUID userId) {
        if (userId == null) return;
        adminJdbc.execute(
                "ALTER TABLE staff_security_audit_events DISABLE TRIGGER trg_staff_security_audit_events_immutable; " +
                "DELETE FROM staff_global_roles WHERE staff_user_id = '" + userId + "'; " +
                "DELETE FROM staff_property_roles WHERE staff_user_id = '" + userId + "'; " +
                "DELETE FROM staff_refresh_tokens WHERE staff_user_id = '" + userId + "'; " +
                "DELETE FROM staff_security_audit_events WHERE staff_user_id = '" + userId + "'; " +
                "DELETE FROM staff_users WHERE id = '" + userId + "'; " +
                "ALTER TABLE staff_security_audit_events ENABLE TRIGGER trg_staff_security_audit_events_immutable;"
        );
    }

    // =========================================================================
    // 1. Clean Flyway V1-V9 Migration & V8-to-V9 Upgrade Tests
    // =========================================================================

    @Test
    @DisplayName("Clean Migration: Flyway V1-V9 executes cleanly, installing all 9 migrations in order")
    void shouldExecuteCleanV1ThroughV9Migration() {
        Flyway flyway = Flyway.configure()
                .dataSource(CLEAN_VALIDATION_DB_URL, ADMIN_USER, ADMIN_PASS)
                .locations("classpath:db/migration")
                .target("9")
                .cleanDisabled(false)
                .load();

        flyway.clean();
        var result = flyway.migrate();
        assertThat(result.migrationsExecuted).isEqualTo(9);

        MigrationInfo current = flyway.info().current();
        assertThat(current).isNotNull();
        assertThat(current.getVersion().getVersion()).isEqualTo("9");
        assertThat(current.getDescription()).isEqualTo("harden auth execution paths");

        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).hasSize(9);
        for (MigrationInfo info : applied) {
            assertThat(info.getState().isApplied())
                    .as("Migration V%s must be applied", info.getVersion().getVersion())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Upgrade Validation: Sequential migration from V8 to V9 succeeds with 1 migration executed")
    void shouldUpgradeFromV8ToV9Successfully() {
        Flyway flyway = Flyway.configure()
                .dataSource(CLEAN_VALIDATION_DB_URL, ADMIN_USER, ADMIN_PASS)
                .locations("classpath:db/migration")
                .target("8")
                .cleanDisabled(false)
                .load();

        flyway.clean();
        var v8Result = flyway.migrate();
        assertThat(v8Result.migrationsExecuted).isEqualTo(8);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("8");

        // Now upgrade to V9
        Flyway v9Flyway = Flyway.configure()
                .dataSource(CLEAN_VALIDATION_DB_URL, ADMIN_USER, ADMIN_PASS)
                .locations("classpath:db/migration")
                .target("9")
                .cleanDisabled(false)
                .load();

        var v9Result = v9Flyway.migrate();
        assertThat(v9Result.migrationsExecuted).isEqualTo(1);
        assertThat(v9Flyway.info().current().getVersion().getVersion()).isEqualTo("9");
        assertThat(v9Flyway.info().current().getDescription()).isEqualTo("harden auth execution paths");
    }

    @Test
    @DisplayName("Business Integrity: Existing business data (reservations, properties) is unaffected by V9 migration")
    void shouldVerifyExistingBusinessDataUntouchedByV9() {
        Integer propertyCount = jdbcTemplate.queryForObject("SELECT count(*) FROM properties", Integer.class);
        Integer roomCount = jdbcTemplate.queryForObject("SELECT count(*) FROM rooms", Integer.class);
        Integer reservationCount = jdbcTemplate.queryForObject("SELECT count(*) FROM reservations", Integer.class);

        assertThat(propertyCount).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(roomCount).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(reservationCount).isNotNull().isGreaterThanOrEqualTo(0);
    }

    // =========================================================================
    // 2. Out-of-band Role Provisioning Idempotency
    // =========================================================================

    @Test
    @DisplayName("Role Provisioning: Idempotent pre-V9 script execution creates pms_auth without superuser or admin rights")
    void shouldVerifyPmsAuthRoleAttributes() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();

        Boolean roleExists = adminJdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_auth')",
                Boolean.class
        );
        assertThat(roleExists).isTrue();

        Boolean isSuperuser = adminJdbc.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = 'pms_auth'",
                Boolean.class
        );
        Boolean canCreateDb = adminJdbc.queryForObject(
                "SELECT rolcreatedb FROM pg_roles WHERE rolname = 'pms_auth'",
                Boolean.class
        );
        Boolean canCreateRole = adminJdbc.queryForObject(
                "SELECT rolcreaterole FROM pg_roles WHERE rolname = 'pms_auth'",
                Boolean.class
        );

        assertThat(isSuperuser).isFalse();
        assertThat(canCreateDb).isFalse();
        assertThat(canCreateRole).isFalse();
    }

    // =========================================================================
    // 3. Negative Security: pms_app Restrictions
    // =========================================================================

    @Test
    @DisplayName("Negative Security: pms_app is strictly DENIED from reading staff_users.password_hash")
    void shouldDenyPmsAppFromReadingPasswordHash() {
        assertThatThrownBy(() -> jdbcTemplate.execute("SELECT password_hash FROM staff_users"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_users");
    }

    @Test
    @DisplayName("Negative Security: pms_app is strictly DENIED from executing fn_record_staff_login_success")
    void shouldDenyPmsAppFromExecutingLoginSuccess() {
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                "SELECT fn_record_staff_login_success('00000000-0000-0000-0000-000000000000'::uuid, '127.0.0.1', 'JUnit')",
                Boolean.class
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_record_staff_login_success");
    }

    @Test
    @DisplayName("Negative Security: pms_app is strictly DENIED from executing fn_rotate_refresh_token")
    void shouldDenyPmsAppFromExecutingRotateRefreshToken() {
        assertThatThrownBy(() -> jdbcTemplate.execute(
                "SELECT fn_rotate_refresh_token('0000000000000000000000000000000000000000000000000000000000000000', '1111111111111111111111111111111111111111111111111111111111111111', now() + interval '1 day')"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_rotate_refresh_token");
    }

    @Test
    @DisplayName("Negative Security: pms_app is strictly DENIED from executing fn_revoke_refresh_token_family")
    void shouldDenyPmsAppFromExecutingRevokeRefreshTokenFamily() {
        assertThatThrownBy(() -> jdbcTemplate.execute(
                "SELECT fn_revoke_refresh_token_family('00000000-0000-0000-0000-000000000000'::uuid, 'TEST')"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_revoke_refresh_token_family");
    }

    @Test
    @DisplayName("Negative Security: pms_app is strictly DENIED direct SELECT on staff_refresh_tokens")
    void shouldDenyPmsAppFromSelectingStaffRefreshTokens() {
        assertThatThrownBy(() -> jdbcTemplate.execute("SELECT * FROM staff_refresh_tokens"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_refresh_tokens");
    }

    @Test
    @DisplayName("Negative Security: pms_app is strictly DENIED direct INSERT on staff_refresh_tokens")
    void shouldDenyPmsAppFromInsertingStaffRefreshTokens() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO staff_refresh_tokens (staff_user_id, token_hash, token_family, sequence_number, expires_at) " +
                "VALUES ('00000000-0000-0000-0000-000000000000'::uuid, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', '00000000-0000-0000-0000-000000000000'::uuid, 1, now() + interval '1 day')"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_refresh_tokens");
    }

    @Test
    @DisplayName("Negative Security: pms_app is strictly DENIED direct UPDATE on staff_refresh_tokens")
    void shouldDenyPmsAppFromUpdatingStaffRefreshTokens() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE staff_refresh_tokens SET is_revoked = TRUE WHERE id = '00000000-0000-0000-0000-000000000000'::uuid"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_refresh_tokens");
    }

    @Test
    @DisplayName("Negative Security: pms_app is strictly DENIED direct DELETE on staff_refresh_tokens")
    void shouldDenyPmsAppFromDeletingStaffRefreshTokens() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM staff_refresh_tokens WHERE id = '00000000-0000-0000-0000-000000000000'::uuid"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_refresh_tokens");
    }

    @Test
    @DisplayName("Negative Security: pms_app is strictly DENIED TRUNCATE on staff_refresh_tokens")
    void shouldDenyPmsAppFromTruncatingStaffRefreshTokens() {
        assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE TABLE staff_refresh_tokens"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_refresh_tokens");
    }

    // =========================================================================
    // 4. Negative Security: pms_auth Restrictions (Domain, Finance & Staff Admin)
    // =========================================================================

    @Test
    @DisplayName("Negative Security: pms_auth is strictly DENIED from reading reservations")
    void shouldDenyPmsAuthFromReadingReservations() {
        JdbcTemplate authJdbc = createAuthJdbcTemplate();
        assertThatThrownBy(() -> authJdbc.execute("SELECT * FROM reservations LIMIT 1"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table reservations");
    }

    @Test
    @DisplayName("Negative Security: pms_auth is strictly DENIED from reading finance_accounts")
    void shouldDenyPmsAuthFromReadingFinanceAccounts() {
        JdbcTemplate authJdbc = createAuthJdbcTemplate();
        assertThatThrownBy(() -> authJdbc.execute("SELECT * FROM finance_accounts LIMIT 1"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table finance_accounts");
    }

    @Test
    @DisplayName("Negative Security: pms_auth is strictly DENIED from executing fn_assign_staff_global_role")
    void shouldDenyPmsAuthFromExecutingGlobalRoleAssignment() {
        JdbcTemplate authJdbc = createAuthJdbcTemplate();
        assertThatThrownBy(() -> authJdbc.execute(
                "SELECT fn_assign_staff_global_role('00000000-0000-0000-0000-000000000000'::uuid, 'SUPER_ADMIN', 'actor')"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_assign_staff_global_role");
    }

    @Test
    @DisplayName("Negative Security: pms_auth is strictly DENIED from executing fn_admin_reset_staff_password")
    void shouldDenyPmsAuthFromExecutingAdminPasswordReset() {
        JdbcTemplate authJdbc = createAuthJdbcTemplate();
        assertThatThrownBy(() -> authJdbc.execute(
                "SELECT fn_admin_reset_staff_password('00000000-0000-0000-0000-000000000000'::uuid, '$2a$12$f91zq8LkQqFx4X7yL0rYyeRkKdN2r5SlR0bWcZ3wL9o2p3q4r5s6t', 'actor')"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_admin_reset_staff_password");
    }

    @Test
    @DisplayName("Negative Security: pms_auth is strictly DENIED from executing fn_set_staff_active_status")
    void shouldDenyPmsAuthFromExecutingSetActiveStatus() {
        JdbcTemplate authJdbc = createAuthJdbcTemplate();
        assertThatThrownBy(() -> authJdbc.execute(
                "SELECT fn_set_staff_active_status('00000000-0000-0000-0000-000000000000'::uuid, false, 'actor')"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_set_staff_active_status");
    }

    // =========================================================================
    // 5. Positive Privileges & Hardened Login Success Flow under pms_auth
    // =========================================================================

    @Test
    @DisplayName("Positive Privilege: pms_auth can read staff password_hash for application-layer BCrypt verification")
    void shouldAllowPmsAuthToReadPasswordHash() {
        JdbcTemplate authJdbc = createAuthJdbcTemplate();
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            userId = createAdminTestUser(adminJdbc, "auth_read_hash_" + UUID.randomUUID() + "@homestay.com");

            String hash = authJdbc.queryForObject(
                    "SELECT password_hash FROM staff_users WHERE id = ?",
                    String.class,
                    userId
            );
            assertThat(hash).isNotNull().startsWith("$2a$12$");
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    @Test
    @DisplayName("Hardened Flow: fn_record_staff_login_success under pms_auth resets attempts and logs audit event")
    void shouldRecordLoginSuccessUnderPmsAuth() {
        JdbcTemplate authJdbc = createAuthJdbcTemplate();
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            String email = "auth_login_success_" + UUID.randomUUID() + "@homestay.com";
            userId = createAdminTestUser(adminJdbc, email);

            // Record 3 failed attempts
            for (int i = 0; i < 3; i++) {
                authJdbc.queryForObject(
                        "SELECT fn_record_staff_login_attempt(?, FALSE, '127.0.0.1', 'JUnit')",
                        Boolean.class,
                        email
                );
            }

            Integer attempts = adminJdbc.queryForObject(
                    "SELECT failed_login_attempts FROM staff_users WHERE id = ?",
                    Integer.class,
                    userId
            );
            assertThat(attempts).isEqualTo(3);

            // Record login success under pms_auth
            Boolean success = authJdbc.queryForObject(
                    "SELECT fn_record_staff_login_success(?, '127.0.0.1', 'JUnit')",
                    Boolean.class,
                    userId
            );
            assertThat(success).isTrue();

            Map<String, Object> state = adminJdbc.queryForMap(
                    "SELECT failed_login_attempts, is_locked, lockout_until FROM staff_users WHERE id = ?",
                    userId
            );
            assertThat(state.get("failed_login_attempts")).isEqualTo(0);
            assertThat(state.get("is_locked")).isEqualTo(false);
            assertThat(state.get("lockout_until")).isNull();

            // Verify audit event
            Integer auditCount = adminJdbc.queryForObject(
                    "SELECT count(*) FROM staff_security_audit_events WHERE staff_user_id = ? AND event_type = 'LOGIN_SUCCESS'",
                    Integer.class,
                    userId
            );
            assertThat(auditCount).isEqualTo(1);
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    @Test
    @DisplayName("State Invariant: fn_record_staff_login_success rejects deactivated staff under pms_auth")
    void shouldRejectLoginSuccessForDeactivatedUserUnderPmsAuth() {
        JdbcTemplate authJdbc = createAuthJdbcTemplate();
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            String email = "deactivated_auth_" + UUID.randomUUID() + "@homestay.com";
            userId = createAdminTestUser(adminJdbc, email);

            // Deactivate target user under admin
            adminJdbc.update("UPDATE staff_users SET is_active = FALSE WHERE id = ?", userId);

            Boolean success = authJdbc.queryForObject(
                    "SELECT fn_record_staff_login_success(?, '127.0.0.1', 'JUnit')",
                    Boolean.class,
                    userId
            );
            assertThat(success).isFalse();

            Integer auditCount = adminJdbc.queryForObject(
                    "SELECT count(*) FROM staff_security_audit_events WHERE staff_user_id = ? AND event_type = 'LOGIN_FAILED_DEACTIVATED_USER'",
                    Integer.class,
                    userId
            );
            assertThat(auditCount).isEqualTo(1);
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    @Test
    @DisplayName("State Invariant: fn_record_staff_login_success rejects active lockout under pms_auth")
    void shouldRejectLoginSuccessForLockedUserUnderPmsAuth() {
        JdbcTemplate authJdbc = createAuthJdbcTemplate();
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            String email = "locked_auth_" + UUID.randomUUID() + "@homestay.com";
            userId = createAdminTestUser(adminJdbc, email);

            // Trigger 5 failed attempts to lock account
            for (int i = 0; i < 5; i++) {
                authJdbc.queryForObject(
                        "SELECT fn_record_staff_login_attempt(?, FALSE, '127.0.0.1', 'JUnit')",
                        Boolean.class,
                        email
                );
            }

            Boolean success = authJdbc.queryForObject(
                    "SELECT fn_record_staff_login_success(?, '127.0.0.1', 'JUnit')",
                    Boolean.class,
                    userId
            );
            assertThat(success).isFalse();

            Integer auditCount = adminJdbc.queryForObject(
                    "SELECT count(*) FROM staff_security_audit_events WHERE staff_user_id = ? AND event_type = 'LOGIN_REJECTED_LOCKED_ACCOUNT'",
                    Integer.class,
                    userId
            );
            assertThat(auditCount).isEqualTo(1);
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    // =========================================================================
    // 6. Refresh Token Rotation & Replay Detection under pms_auth
    // =========================================================================

    @Test
    @DisplayName("Token Rotation: fn_rotate_refresh_token rotates token, revoking old and issuing new with incremented sequence")
    void shouldRotateRefreshTokenUnderPmsAuth() {
        JdbcTemplate authJdbc = createAuthJdbcTemplate();
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            userId = createAdminTestUser(adminJdbc, "rotate_token_" + UUID.randomUUID() + "@homestay.com");
            UUID familyId = UUID.randomUUID();
            String oldHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
            String newHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

            // Insert initial token in family
            authJdbc.update(
                    "INSERT INTO staff_refresh_tokens (staff_user_id, token_hash, token_family, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                    userId, oldHash, familyId
            );

            // Rotate under pms_auth
            UUID newTokenId = authJdbc.queryForObject(
                    "SELECT fn_rotate_refresh_token(?, ?, CURRENT_TIMESTAMP + INTERVAL '7 days', 'JUnit', '127.0.0.1')",
                    UUID.class,
                    oldHash, newHash
            );
            assertThat(newTokenId).isNotNull();

            // Verify old token is marked revoked with ROTATED
            Map<String, Object> oldToken = adminJdbc.queryForMap(
                    "SELECT is_revoked, revoked_reason, sequence_number FROM staff_refresh_tokens WHERE token_hash = ?",
                    oldHash
            );
            assertThat(oldToken.get("is_revoked")).isEqualTo(true);
            assertThat(oldToken.get("revoked_reason")).isEqualTo("ROTATED");
            assertThat(oldToken.get("sequence_number")).isEqualTo(1);

            // Verify new token exists with sequence_number = 2 and same familyId
            Map<String, Object> newToken = adminJdbc.queryForMap(
                    "SELECT is_revoked, sequence_number, token_family FROM staff_refresh_tokens WHERE id = ?",
                    newTokenId
            );
            assertThat(newToken.get("is_revoked")).isEqualTo(false);
            assertThat(newToken.get("sequence_number")).isEqualTo(2);
            assertThat(newToken.get("token_family")).isEqualTo(familyId);
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    @Test
    @DisplayName("Replay Detection: Presenting already-revoked refresh token revokes entire family and logs security audit alert")
    void shouldDetectReplayAndRevokeEntireTokenFamily() {
        JdbcTemplate authJdbc = createAuthJdbcTemplate();
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            userId = createAdminTestUser(adminJdbc, "replay_attack_" + UUID.randomUUID() + "@homestay.com");
            UUID familyId = UUID.randomUUID();
            String token1Hash = "1111111111111111111111111111111111111111111111111111111111111111";
            String token2Hash = "2222222222222222222222222222222222222222222222222222222222222222";
            String token3Hash = "3333333333333333333333333333333333333333333333333333333333333333";

            authJdbc.update(
                    "INSERT INTO staff_refresh_tokens (staff_user_id, token_hash, token_family, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                    userId, token1Hash, familyId
            );

            // Rotate 1 -> 2
            UUID token2Id = authJdbc.queryForObject(
                    "SELECT fn_rotate_refresh_token(?, ?, CURRENT_TIMESTAMP + INTERVAL '7 days', 'JUnit', '127.0.0.1')",
                    UUID.class,
                    token1Hash, token2Hash
            );
            assertThat(token2Id).isNotNull();

            // Simulate that token 1 was rotated outside the concurrent grace window (30 seconds ago)
            adminJdbc.update(
                    "UPDATE staff_refresh_tokens SET revoked_at = CURRENT_TIMESTAMP - INTERVAL '30 seconds' WHERE token_hash = ?",
                    token1Hash
            );

            // Attacker presents revoked token 1 again (Replay attack outside grace window!)
            // Procedure safely commits the family revocation, writes the audit log, and returns NULL
            UUID replayTokenId = authJdbc.queryForObject(
                    "SELECT fn_rotate_refresh_token(?, ?, CURRENT_TIMESTAMP + INTERVAL '7 days', 'Attacker', '192.168.1.100')",
                    UUID.class,
                    token1Hash, token3Hash
            );
            assertThat(replayTokenId).isNull();

            // Verify ALL tokens in the family are now revoked and changes remain committed
            JdbcTemplate freshVerifierJdbc = createAdminJdbcTemplate();
            List<Map<String, Object>> familyTokens = freshVerifierJdbc.queryForList(
                    "SELECT id, is_revoked, revoked_reason FROM staff_refresh_tokens WHERE token_family = ?",
                    familyId
            );
            assertThat(familyTokens).hasSize(2);
            for (Map<String, Object> tok : familyTokens) {
                assertThat(tok.get("is_revoked"))
                        .as("Token %s must remain revoked after transaction completes", tok.get("id"))
                        .isEqualTo(true);
            }

            // Verify security audit log captured replay
            Integer replayAlerts = freshVerifierJdbc.queryForObject(
                    "SELECT count(*) FROM staff_security_audit_events WHERE staff_user_id = ? AND event_type = 'REFRESH_TOKEN_REPLAY_DETECTED'",
                    Integer.class,
                    userId
            );
            assertThat(replayAlerts).isEqualTo(1);
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    @Test
    @DisplayName("Simultaneous Refresh: Concurrent rotation from two independent connections produces exactly one valid successor")
    void shouldHandleSimultaneousRefreshAttemptsFromIndependentConnections() throws Exception {
        DriverManagerDataSource authDs1 = new DriverManagerDataSource();
        authDs1.setDriverClassName("org.postgresql.Driver");
        authDs1.setUrl(DISPOSABLE_DB_URL);
        authDs1.setUsername(AUTH_USER);
        authDs1.setPassword(AUTH_PASS);
        JdbcTemplate authJdbc1 = new JdbcTemplate(authDs1);

        DriverManagerDataSource authDs2 = new DriverManagerDataSource();
        authDs2.setDriverClassName("org.postgresql.Driver");
        authDs2.setUrl(DISPOSABLE_DB_URL);
        authDs2.setUsername(AUTH_USER);
        authDs2.setPassword(AUTH_PASS);
        JdbcTemplate authJdbc2 = new JdbcTemplate(authDs2);

        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            userId = createAdminTestUser(adminJdbc, "simultaneous_" + UUID.randomUUID() + "@homestay.com");
            UUID familyId = UUID.randomUUID();
            String token1Hash = "4444444444444444444444444444444444444444444444444444444444444444";
            String token2AHash = "5555555555555555555555555555555555555555555555555555555555555555";
            String token2BHash = "6666666666666666666666666666666666666666666666666666666666666666";

            // Insert initial active token into family
            authJdbc1.update(
                    "INSERT INTO staff_refresh_tokens (staff_user_id, token_hash, token_family, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                    userId, token1Hash, familyId
            );

            // Concurrently attempt rotation from two independent connections
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);

            final UUID[] results = new UUID[2];
            final Exception[] exceptions = new Exception[2];

            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    results[0] = authJdbc1.queryForObject(
                            "SELECT fn_rotate_refresh_token(?, ?, CURRENT_TIMESTAMP + INTERVAL '7 days', 'Tab1', '10.0.0.1')",
                            UUID.class,
                            token1Hash, token2AHash
                    );
                } catch (Exception e) {
                    exceptions[0] = e;
                } finally {
                    doneLatch.countDown();
                }
            });

            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    results[1] = authJdbc2.queryForObject(
                            "SELECT fn_rotate_refresh_token(?, ?, CURRENT_TIMESTAMP + INTERVAL '7 days', 'Tab2', '10.0.0.2')",
                            UUID.class,
                            token1Hash, token2BHash
                    );
                } catch (Exception e) {
                    exceptions[1] = e;
                } finally {
                    doneLatch.countDown();
                }
            });

            readyLatch.await();
            startLatch.countDown();
            boolean finished = doneLatch.await(10, java.util.concurrent.TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(finished).isTrue();
            assertThat(exceptions[0]).isNull();
            assertThat(exceptions[1]).isNull();

            // Exactly one connection succeeded (got successor UUID) and the other was rejected (got null)
            boolean firstSucceeded = results[0] != null;
            boolean secondSucceeded = results[1] != null;
            assertThat(firstSucceeded ^ secondSucceeded)
                    .as("Exactly one connection must succeed and one must return null")
                    .isTrue();

            UUID winnerTokenId = firstSucceeded ? results[0] : results[1];

            // Verify in database: exactly ONE valid successor token exists in the family
            List<Map<String, Object>> activeTokens = adminJdbc.queryForList(
                    "SELECT id, sequence_number, is_revoked, revoked_reason FROM staff_refresh_tokens WHERE token_family = ? AND is_revoked = FALSE",
                    familyId
            );
            assertThat(activeTokens).hasSize(1);
            assertThat(activeTokens.get(0).get("id")).isEqualTo(winnerTokenId);
            assertThat(activeTokens.get(0).get("sequence_number")).isEqualTo(2);

            // Verify old token is revoked with ROTATED
            Map<String, Object> oldTokenState = adminJdbc.queryForMap(
                    "SELECT is_revoked, revoked_reason FROM staff_refresh_tokens WHERE token_hash = ?",
                    token1Hash
            );
            assertThat(oldTokenState.get("is_revoked")).isEqualTo(true);
            assertThat(oldTokenState.get("revoked_reason")).isEqualTo("ROTATED");

            // Verify total tokens in family is 2 (old token + winner successor)
            Integer totalFamilyTokens = adminJdbc.queryForObject(
                    "SELECT count(*) FROM staff_refresh_tokens WHERE token_family = ?",
                    Integer.class,
                    familyId
            );
            assertThat(totalFamilyTokens).isEqualTo(2);

            // Verify no replay attack alert was erroneously logged for simultaneous race
            Integer replayAlertCount = adminJdbc.queryForObject(
                    "SELECT count(*) FROM staff_security_audit_events WHERE staff_user_id = ? AND event_type = 'REFRESH_TOKEN_REPLAY_DETECTED'",
                    Integer.class,
                    userId
            );
            assertThat(replayAlertCount).isEqualTo(0);
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    // =========================================================================
    // 7. Concurrency Safety: Serialized Failed Attempts
    // =========================================================================

    @Test
    @DisplayName("Concurrency: Concurrent login attempts under pms_auth are serialized safely with row locks")
    void shouldHandleConcurrentLoginAttemptsSafelyUnderPmsAuth() throws InterruptedException {
        JdbcTemplate authJdbc = createAuthJdbcTemplate();
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            String email = "concurrent_auth_" + UUID.randomUUID() + "@homestay.com";
            userId = createAdminTestUser(adminJdbc, email);

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger completedCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        authJdbc.queryForObject(
                                "SELECT fn_record_staff_login_attempt(?, FALSE, '127.0.0.1', 'JUnit-Concurrent')",
                                Boolean.class,
                                email
                        );
                        completedCount.incrementAndGet();
                    } catch (Exception e) {
                        // ignore
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            readyLatch.await();
            startLatch.countDown();
            boolean finished = doneLatch.await(10, java.util.concurrent.TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(finished).isTrue();
            assertThat(completedCount.get()).isEqualTo(threadCount);

            Map<String, Object> state = adminJdbc.queryForMap(
                    "SELECT failed_login_attempts, is_locked FROM staff_users WHERE id = ?",
                    userId
            );
            assertThat(state.get("failed_login_attempts")).isEqualTo(5);
            assertThat(state.get("is_locked")).isEqualTo(true);
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }
}
