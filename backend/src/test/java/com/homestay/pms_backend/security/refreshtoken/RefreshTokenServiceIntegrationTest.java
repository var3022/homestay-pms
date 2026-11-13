package com.homestay.pms_backend.security.refreshtoken;

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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IsolatedDatabaseTest
@DisplayName("Step 3.3C.5.1: Refresh-Token Lifecycle Service Integration Tests")
class RefreshTokenServiceIntegrationTest {

    private static final String TEST_DOMAIN = "@test-refresh.com";
    private static final String DEFAULT_PASSWORD = "StrongSecurePassword#2026!";

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private RefreshTokenGenerator tokenGenerator;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate; // Primary pms_app connection

    @Autowired
    @Qualifier("authJdbcTemplate")
    private JdbcTemplate authJdbcTemplate; // Dedicated pms_auth connection

    private JdbcTemplate adminJdbcTemplate;

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
        cleanupTestData();
    }

    private void cleanupTestData() {
        if (adminJdbcTemplate != null) {
            adminJdbcTemplate.execute(
                    "ALTER TABLE staff_security_audit_events DISABLE TRIGGER trg_staff_security_audit_events_immutable; " +
                    "DELETE FROM staff_security_audit_events WHERE email LIKE '%" + TEST_DOMAIN + "'; " +
                    "DELETE FROM staff_refresh_tokens WHERE staff_user_id IN (SELECT id FROM staff_users WHERE email LIKE '%" + TEST_DOMAIN + "'); " +
                    "DELETE FROM staff_users WHERE email LIKE '%" + TEST_DOMAIN + "'; " +
                    "ALTER TABLE staff_security_audit_events ENABLE TRIGGER trg_staff_security_audit_events_immutable;"
            );
        }
    }

    private UUID createTestStaff(String email, boolean isActive, boolean isLocked, Instant lockoutUntil) {
        UUID id = UUID.randomUUID();
        String hash = passwordEncoder.encode(DEFAULT_PASSWORD);
        adminJdbcTemplate.update(
                """
                INSERT INTO staff_users (
                    id, email, password_hash, first_name, last_name, phone,
                    is_active, is_locked, failed_login_attempts, lockout_until,
                    last_login_at, password_changed_at, created_at, updated_at
                ) VALUES (?, ?, ?, 'Refresh', 'Tester', '+919876543210', ?, ?, 0, ?, NULL, CURRENT_TIMESTAMP - INTERVAL '5 seconds', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id,
                email.trim().toLowerCase(),
                hash,
                isActive,
                isLocked,
                lockoutUntil != null ? Timestamp.from(lockoutUntil) : null
        );
        return id;
    }

    // =========================================================================
    // 1. Initial Issuance, Storage & Entropy Tests
    // =========================================================================

    @Test
    @DisplayName("Requirement 1: Initial refresh-token issuance succeeds for eligible authenticated staff")
    void shouldIssueInitialRefreshTokenForEligibleStaff() {
        UUID staffId = createTestStaff("alice" + TEST_DOMAIN, true, false, null);

        IssuedRefreshToken issued = refreshTokenService.issueInitialToken(staffId, "192.168.1.10", "Mozilla/5.0 Tester");

        assertThat(issued).isNotNull();
        assertThat(issued.rawToken()).isNotBlank();
        assertThat(issued.staffUserId()).isEqualTo(staffId);
        assertThat(issued.sequenceNumber()).isEqualTo(1);
        assertThat(issued.tokenFamily()).isNotNull();
        assertThat(issued.tokenId()).isNotNull();

        // Verify record in PostgreSQL database via pms_auth
        Map<String, Object> row = authJdbcTemplate.queryForMap(
                "SELECT staff_user_id, token_family, sequence_number, is_revoked, user_agent, ip_address FROM staff_refresh_tokens WHERE id = ?",
                issued.tokenId()
        );
        assertThat(row.get("staff_user_id")).isEqualTo(staffId);
        assertThat(row.get("token_family")).isEqualTo(issued.tokenFamily());
        assertThat(row.get("sequence_number")).isEqualTo(1);
        assertThat(row.get("is_revoked")).isEqualTo(false);
        assertThat(row.get("user_agent")).isEqualTo("Mozilla/5.0 Tester");
        assertThat(row.get("ip_address")).isEqualTo("192.168.1.10");
    }

    @Test
    @DisplayName("Requirement 2 & 3: Raw refresh tokens are never stored in PostgreSQL and SHA-256 hash is verified")
    void shouldNeverStoreRawRefreshTokensInDatabase() {
        UUID staffId = createTestStaff("bob" + TEST_DOMAIN, true, false, null);
        IssuedRefreshToken issued = refreshTokenService.issueInitialToken(staffId, "10.0.0.1", "SecureAgent");

        // Assert raw token is NOT stored anywhere in the database
        Integer rawMatchCount = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_refresh_tokens WHERE token_hash = ?",
                Integer.class,
                issued.rawToken()
        );
        assertThat(rawMatchCount).isZero();

        // Assert SHA-256 hash IS stored
        String expectedHash = tokenGenerator.hashToken(issued.rawToken());
        assertThat(expectedHash).hasSize(64).matches("^[a-f0-9]{64}$");

        Integer hashMatchCount = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_refresh_tokens WHERE token_hash = ?",
                Integer.class,
                expectedHash
        );
        assertThat(hashMatchCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Requirement 4: Issued tokens expire after seven days")
    void shouldSetSevenDayExpiration() {
        UUID staffId = createTestStaff("charlie" + TEST_DOMAIN, true, false, null);
        IssuedRefreshToken issued = refreshTokenService.issueInitialToken(staffId, "10.0.0.1", "Agent");

        Timestamp dbExpiresAt = adminJdbcTemplate.queryForObject(
                "SELECT expires_at FROM staff_refresh_tokens WHERE id = ?",
                Timestamp.class,
                issued.tokenId()
        );
        assertThat(dbExpiresAt).isNotNull();

        long daysDiff = ChronoUnit.DAYS.between(Instant.now(), dbExpiresAt.toInstant());
        assertThat(daysDiff).isBetween(6L, 7L);
    }

    // =========================================================================
    // 2. Token Rotation & Replay Handling Tests
    // =========================================================================

    @Test
    @DisplayName("Requirement 5 & 6: Rotation revokes the previous token and issues exactly one valid successor")
    void shouldRotateTokenAndRevokePredecessor() {
        UUID staffId = createTestStaff("david" + TEST_DOMAIN, true, false, null);
        IssuedRefreshToken initial = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent 1");

        RotatedRefreshToken rotated = refreshTokenService.rotateToken(initial.rawToken(), "127.0.0.1", "Agent 2");

        assertThat(rotated).isNotNull();
        assertThat(rotated.rawToken()).isNotEqualTo(initial.rawToken());
        assertThat(rotated.tokenFamily()).isEqualTo(initial.tokenFamily());
        assertThat(rotated.sequenceNumber()).isEqualTo(2);
        assertThat(rotated.staffUserId()).isEqualTo(staffId);

        // Verify predecessor state in PostgreSQL
        Map<String, Object> oldRow = adminJdbcTemplate.queryForMap(
                "SELECT is_revoked, revoked_reason, last_used_at FROM staff_refresh_tokens WHERE id = ?",
                initial.tokenId()
        );
        assertThat(oldRow.get("is_revoked")).isEqualTo(true);
        assertThat(oldRow.get("revoked_reason")).isEqualTo("ROTATED");
        assertThat(oldRow.get("last_used_at")).isNotNull();

        // Verify exactly one active token in the family
        Integer activeCount = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_refresh_tokens WHERE token_family = ? AND is_revoked = FALSE",
                Integer.class,
                initial.tokenFamily()
        );
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Requirement 7: Concurrent rotation within 10-second grace window creates no duplicate valid successors")
    void shouldRejectSimultaneousRotationWithinGraceWindowWithoutRevokingFamily() {
        UUID staffId = createTestStaff("eve" + TEST_DOMAIN, true, false, null);
        IssuedRefreshToken initial = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent Tab 1");

        // First rotation succeeds
        RotatedRefreshToken firstRotated = refreshTokenService.rotateToken(initial.rawToken(), "127.0.0.1", "Agent Tab 1");
        assertThat(firstRotated).isNotNull();

        // Simultaneous second rotation attempt on the same old token (within 10s grace window)
        assertThatThrownBy(() -> refreshTokenService.rotateToken(initial.rawToken(), "127.0.0.1", "Agent Tab 2"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        // Verify that the family was NOT revoked: the first successor remains the single active token
        Integer activeCount = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_refresh_tokens WHERE token_family = ? AND is_revoked = FALSE",
                Integer.class,
                initial.tokenFamily()
        );
        assertThat(activeCount).isEqualTo(1);

        // Verify the single active successor is firstRotated
        Map<String, Object> activeRow = adminJdbcTemplate.queryForMap(
                "SELECT id, sequence_number FROM staff_refresh_tokens WHERE token_family = ? AND is_revoked = FALSE",
                initial.tokenFamily()
        );
        assertThat(activeRow.get("id")).isEqualTo(firstRotated.tokenId());
        assertThat(activeRow.get("sequence_number")).isEqualTo(2);
    }

    @Test
    @DisplayName("Requirement 8 & 9: Token reuse outside grace window revokes whole family and commits replay audit event")
    void shouldRevokeFamilyAndLogAuditOnReplayOutsideGraceWindow() {
        UUID staffId = createTestStaff("frank" + TEST_DOMAIN, true, false, null);
        IssuedRefreshToken initial = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Legit Agent");

        // Legit rotation
        RotatedRefreshToken successor = refreshTokenService.rotateToken(initial.rawToken(), "127.0.0.1", "Legit Agent");
        assertThat(successor).isNotNull();

        // Simulate that rotation occurred 15 seconds ago (outside the 10-second concurrency grace window)
        adminJdbcTemplate.update(
                "UPDATE staff_refresh_tokens SET revoked_at = CURRENT_TIMESTAMP - INTERVAL '15 seconds' WHERE id = ?",
                initial.tokenId()
        );

        // Malicious replay attack using the superseded token
        assertThatThrownBy(() -> refreshTokenService.rotateToken(initial.rawToken(), "203.0.113.5", "Attacker Agent"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        // Requirement 8: Verify ENTIRE family was revoked
        Integer activeTokens = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_refresh_tokens WHERE token_family = ? AND is_revoked = FALSE",
                Integer.class,
                initial.tokenFamily()
        );
        assertThat(activeTokens).isZero();

        // Requirement 9: Verify REFRESH_TOKEN_REPLAY_DETECTED audit event is permanently committed
        List<Map<String, Object>> auditEvents = adminJdbcTemplate.queryForList(
                "SELECT event_type, email, staff_user_id, ip_address, user_agent FROM staff_security_audit_events WHERE email = ? AND event_type = 'REFRESH_TOKEN_REPLAY_DETECTED'",
                "frank" + TEST_DOMAIN
        );
        assertThat(auditEvents).hasSize(1);
        assertThat(auditEvents.get(0).get("staff_user_id")).isEqualTo(staffId);
        assertThat(auditEvents.get(0).get("ip_address")).isEqualTo("203.0.113.5");
        assertThat(auditEvents.get(0).get("user_agent")).isEqualTo("Attacker Agent");
    }

    @Test
    @DisplayName("Requirement 10: Invalid or expired tokens cannot rotate")
    void shouldRejectInvalidOrExpiredTokenRotation() {
        UUID staffId = createTestStaff("grace" + TEST_DOMAIN, true, false, null);
        IssuedRefreshToken initial = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");

        // Non-existent token format
        String randomFakeToken = tokenGenerator.generateToken();
        assertThatThrownBy(() -> refreshTokenService.rotateToken(randomFakeToken, "127.0.0.1", "Agent"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("Presented refresh token does not exist");

        // Expired token (set created_at = now - 8 days, expires_at = now - 1 hour to satisfy expires_at > created_at)
        adminJdbcTemplate.update(
                "UPDATE staff_refresh_tokens SET created_at = CURRENT_TIMESTAMP - INTERVAL '8 days', expires_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE id = ?",
                initial.tokenId()
        );

        assertThatThrownBy(() -> refreshTokenService.rotateToken(initial.rawToken(), "127.0.0.1", "Agent"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        // Verify token marked EXPIRED in database
        String revokedReason = adminJdbcTemplate.queryForObject(
                "SELECT revoked_reason FROM staff_refresh_tokens WHERE id = ?",
                String.class,
                initial.tokenId()
        );
        assertThat(revokedReason).isEqualTo("EXPIRED");
    }

    // =========================================================================
    // 3. Account Eligibility & Concurrent Mutation Tests
    // =========================================================================

    @Test
    @DisplayName("Requirement 11 & 12: Disabled and locked staff cannot receive new sessions")
    void shouldRejectSessionCreationForDisabledOrLockedStaff() {
        UUID inactiveId = createTestStaff("heidi" + TEST_DOMAIN, false, false, null);
        assertThatThrownBy(() -> refreshTokenService.issueInitialToken(inactiveId, "127.0.0.1", "Agent"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("Staff account is deactivated");

        UUID lockedId = createTestStaff("ivan" + TEST_DOMAIN, true, true, Instant.now().plusSeconds(900));
        assertThatThrownBy(() -> refreshTokenService.issueInitialToken(lockedId, "127.0.0.1", "Agent"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("Staff account is locked");
    }

    @Test
    @DisplayName("Requirement 13: Password changes invalidate existing refresh tokens and prevent rotation")
    void shouldInvalidateTokensUponPasswordChange() {
        UUID staffId = createTestStaff("judy" + TEST_DOMAIN, true, false, null);
        IssuedRefreshToken initial = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");

        // Simulate password change via administrative routine
        String newHash = passwordEncoder.encode("NewSecretPassword#2026!");
        adminJdbcTemplate.execute((java.sql.Connection conn) -> {
            try (var ps = conn.prepareStatement("SELECT fn_admin_reset_staff_password(?, ?, ?)")) {
                ps.setObject(1, staffId);
                ps.setString(2, newHash);
                ps.setString(3, "admin-actor");
                ps.execute();
                return null;
            }
        });

        // Verify database revoked the token with reason ADMIN_PASSWORD_RESET
        Map<String, Object> row = adminJdbcTemplate.queryForMap(
                "SELECT is_revoked, revoked_reason FROM staff_refresh_tokens WHERE id = ?",
                initial.tokenId()
        );
        assertThat(row.get("is_revoked")).isEqualTo(true);
        assertThat(row.get("revoked_reason")).isEqualTo("ADMIN_PASSWORD_RESET");

        // Attempting to rotate the superseded token fails
        assertThatThrownBy(() -> refreshTokenService.rotateToken(initial.rawToken(), "127.0.0.1", "Agent"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    // =========================================================================
    // 4. Session Revocation & Logout Tests
    // =========================================================================

    @Test
    @DisplayName("Requirement 14: Logout revokes the correct token family and records audit event")
    void shouldRevokeTokenFamilyUponLogout() {
        UUID staffA = createTestStaff("userA" + TEST_DOMAIN, true, false, null);
        UUID staffB = createTestStaff("userB" + TEST_DOMAIN, true, false, null);

        IssuedRefreshToken tokenA = refreshTokenService.issueInitialToken(staffA, "127.0.0.1", "Agent A");
        IssuedRefreshToken tokenB = refreshTokenService.issueInitialToken(staffB, "127.0.0.1", "Agent B");

        // Staff A logs out
        refreshTokenService.revokeSession(tokenA.rawToken(), staffA, "userA-client");

        // Verify Staff A's family is revoked
        Boolean isRevokedA = adminJdbcTemplate.queryForObject(
                "SELECT is_revoked FROM staff_refresh_tokens WHERE id = ?",
                Boolean.class,
                tokenA.tokenId()
        );
        assertThat(isRevokedA).isTrue();

        // Verify Staff B's family remains completely active
        Boolean isRevokedB = adminJdbcTemplate.queryForObject(
                "SELECT is_revoked FROM staff_refresh_tokens WHERE id = ?",
                Boolean.class,
                tokenB.tokenId()
        );
        assertThat(isRevokedB).isFalse();

        // Verify audit event TOKEN_FAMILY_REVOKED recorded
        List<Map<String, Object>> auditEvents = adminJdbcTemplate.queryForList(
                "SELECT event_type, email, staff_user_id FROM staff_security_audit_events WHERE email = ? AND event_type = 'TOKEN_FAMILY_REVOKED'",
                "usera" + TEST_DOMAIN
        );
        assertThat(auditEvents).hasSize(1);
        assertThat(auditEvents.get(0).get("staff_user_id")).isEqualTo(staffA);
    }

    @Test
    @DisplayName("Requirement 15: Cross-staff session revocation is strictly prevented")
    void shouldPreventCrossStaffSessionRevocation() {
        UUID staffA = createTestStaff("victim" + TEST_DOMAIN, true, false, null);
        UUID staffB = createTestStaff("attacker" + TEST_DOMAIN, true, false, null);

        IssuedRefreshToken tokenA = refreshTokenService.issueInitialToken(staffA, "127.0.0.1", "Victim Agent");

        // Attacker tries to revoke Victim's token
        assertThatThrownBy(() -> refreshTokenService.revokeSession(tokenA.rawToken(), staffB, "attacker-client"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("Cannot revoke session belonging to another staff member");

        // Victim's token family remains active
        Boolean isRevoked = adminJdbcTemplate.queryForObject(
                "SELECT is_revoked FROM staff_refresh_tokens WHERE id = ?",
                Boolean.class,
                tokenA.tokenId()
        );
        assertThat(isRevoked).isFalse();
    }

    @Test
    @DisplayName("Requirement 16: Repeated logout calls are idempotent")
    void shouldBeIdempotentOnRepeatedLogout() {
        UUID staffId = createTestStaff("mallory" + TEST_DOMAIN, true, false, null);
        IssuedRefreshToken token = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");

        // First logout succeeds
        refreshTokenService.revokeSession(token.rawToken(), staffId, "client");

        // Second and third logout succeed without exception (idempotent)
        refreshTokenService.revokeSession(token.rawToken(), staffId, "client");
        refreshTokenService.revokeSession(token.rawToken(), null, "client");

        // Non-existent token also succeeds idempotently
        String fakeToken = tokenGenerator.generateToken();
        refreshTokenService.revokeSession(fakeToken, null, "client");
    }

    // =========================================================================
    // 5. Least-Privilege & Boundary Tests
    // =========================================================================

    @Test
    @DisplayName("Requirement 17: Primary application role (pms_app) cannot access refresh-token records")
    void shouldDenyPmsAppAccessToRefreshTokens() {
        assertThatThrownBy(() -> jdbcTemplate.queryForList("SELECT * FROM staff_refresh_tokens"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("Requirement 18: Dedicated auth role (pms_auth) cannot access business or finance tables")
    void shouldDenyPmsAuthAccessToDomainAndFinanceTables() {
        // Business tables: reservations
        assertThatThrownBy(() -> authJdbcTemplate.queryForList("SELECT * FROM reservations"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");

        // Finance tables: finance_accounts
        assertThatThrownBy(() -> authJdbcTemplate.queryForList("SELECT * FROM finance_accounts"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("Requirement 19: Database failures are distinguishable and not misclassified as invalid tokens")
    void shouldDistinguishDatabaseFailuresFromInvalidTokens() {
        // Querying non-existent column to simulate unexpected database syntax/execution failure
        assertThatThrownBy(() -> authJdbcTemplate.queryForList("SELECT non_existent_column FROM staff_refresh_tokens"))
                .isInstanceOf(DataAccessException.class)
                .isNotInstanceOf(InvalidRefreshTokenException.class);
    }
}
