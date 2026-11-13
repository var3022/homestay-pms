package com.homestay.pms_backend.security.jwt;

import com.homestay.pms_backend.security.AuthenticatedStaff;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IsolatedDatabaseTest
@DisplayName("Step 3.3C.3: JWT Token Generation, Validation & Database State Integration Tests")
class JwtTokenIntegrationTest {

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private StaffAuthenticationStateValidator stateValidator;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate; // Primary pms_app connection

    @Autowired
    @Qualifier("authJdbcTemplate")
    private JdbcTemplate authJdbcTemplate; // Dedicated pms_auth connection

    @Autowired
    private Environment environment;

    private JdbcTemplate adminJdbcTemplate;

    private static final String TEST_DOMAIN = "@test-jwt.com";
    private static final String DEFAULT_PASSWORD = "StrongSecurePassword#2026!";

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
                ) VALUES (?, ?, ?, 'JWT', 'Tester', '+919876543210', ?, ?, 0, ?, NULL, CURRENT_TIMESTAMP - INTERVAL '5 seconds', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
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
    // 1. End-to-End Token Generation and Real Database Validation
    // =========================================================================

    @Test
    @DisplayName("End-to-End: Generates valid access token for active staff member and validates claims")
    void shouldGenerateAndValidateAccessTokenForDatabaseUser() {
        String email = "alice" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        AuthenticatedStaff staff = new AuthenticatedStaff(
                staffId,
                email,
                "Alice",
                "Tester",
                "+919876543210"
        );

        AccessTokenDto dto = jwtTokenService.generateAccessToken(staff);
        assertThat(dto).isNotNull();
        assertThat(dto.token()).isNotBlank();
        assertThat(dto.tokenType()).isEqualTo("access");

        StaffJwtClaims claims = jwtTokenService.validateAccessToken(dto.token());
        assertThat(claims.staffUserId()).isEqualTo(staffId);
        assertThat(claims.tokenType()).isEqualTo("access");

        // Validate real-time account state in PostgreSQL
        boolean stateValid = stateValidator.isAccountStateValid(claims.staffUserId(), claims.issuedAt());
        assertThat(stateValid).isTrue();
    }

    // =========================================================================
    // 2. Real-Time Account State Validation (Active / Locked / Password Mutation)
    // =========================================================================

    @Test
    @DisplayName("Real-Time Revocation: Rejects token immediately when staff member is deactivated")
    void shouldRejectTokenWhenStaffIsDeactivated() {
        String email = "bob" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "Bob", "Tester", null);
        AccessTokenDto dto = jwtTokenService.generateAccessToken(staff);
        StaffJwtClaims claims = jwtTokenService.validateAccessToken(dto.token());

        // Token is initially valid
        assertThat(stateValidator.isAccountStateValid(staffId, claims.issuedAt())).isTrue();

        // Deactivate staff member in database
        adminJdbcTemplate.update("UPDATE staff_users SET is_active = FALSE WHERE id = ?", staffId);

        // Immediate revocation: Token must now be rejected
        assertThat(stateValidator.isAccountStateValid(staffId, claims.issuedAt())).isFalse();
        assertThatThrownBy(() -> stateValidator.validateAccountState(staffId, claims.issuedAt()))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Real-Time Revocation: Rejects token immediately when staff account is locked")
    void shouldRejectTokenWhenStaffIsLocked() {
        String email = "charlie" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "Charlie", "Tester", null);
        AccessTokenDto dto = jwtTokenService.generateAccessToken(staff);
        StaffJwtClaims claims = jwtTokenService.validateAccessToken(dto.token());

        // Token is initially valid
        assertThat(stateValidator.isAccountStateValid(staffId, claims.issuedAt())).isTrue();

        // Lock staff member in database (timed lockout: 15 minutes)
        adminJdbcTemplate.update(
                "UPDATE staff_users SET is_locked = TRUE, lockout_until = CURRENT_TIMESTAMP + INTERVAL '15 minutes' WHERE id = ?",
                staffId
        );

        // Immediate revocation
        assertThat(stateValidator.isAccountStateValid(staffId, claims.issuedAt())).isFalse();
        assertThatThrownBy(() -> stateValidator.validateAccountState(staffId, claims.issuedAt()))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Lockout Policy: Rejects token when staff has permanent administrative lockout (is_locked=true, lockout_until=null)")
    void shouldRejectTokenWhenStaffHasPermanentAdministrativeLockout() {
        String email = "frank" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "Frank", "Tester", null);
        AccessTokenDto dto = jwtTokenService.generateAccessToken(staff);
        StaffJwtClaims claims = jwtTokenService.validateAccessToken(dto.token());

        // Token is initially valid
        assertThat(stateValidator.isAccountStateValid(staffId, claims.issuedAt())).isTrue();

        // Administrator permanently locks staff account
        adminJdbcTemplate.update(
                "UPDATE staff_users SET is_locked = TRUE, lockout_until = NULL WHERE id = ?",
                staffId
        );

        // Immediate revocation
        assertThat(stateValidator.isAccountStateValid(staffId, claims.issuedAt())).isFalse();
        assertThatThrownBy(() -> stateValidator.validateAccountState(staffId, claims.issuedAt()))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Lockout Policy: Accepts token when temporary lockout duration has expired in database")
    void shouldAcceptTokenWhenTemporaryLockoutHasExpiredInDatabase() {
        String email = "grace" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "Grace", "Tester", null);
        AccessTokenDto dto = jwtTokenService.generateAccessToken(staff);
        StaffJwtClaims claims = jwtTokenService.validateAccessToken(dto.token());

        // Token is initially valid
        assertThat(stateValidator.isAccountStateValid(staffId, claims.issuedAt())).isTrue();

        // Expired temporary lockout (lockout ended 1 minute ago)
        adminJdbcTemplate.update(
                "UPDATE staff_users SET is_locked = TRUE, lockout_until = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?",
                staffId
        );

        // Expired lockout does not become a permanent lock: token is accepted
        assertThat(stateValidator.isAccountStateValid(staffId, claims.issuedAt())).isTrue();
    }

    @Test
    @DisplayName("Ambiguous Boundary: Rejects token when password_changed_at is exactly equal to token issuedAt in database")
    void shouldRejectTokenWhenPasswordChangedAtEqualsTokenIssuedAtInDatabase() {
        String email = "equality" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "Equal", "Tester", null);
        AccessTokenDto dto = jwtTokenService.generateAccessToken(staff);
        StaffJwtClaims claims = jwtTokenService.validateAccessToken(dto.token());

        // Set password_changed_at in database exactly equal to token issuedAt
        adminJdbcTemplate.update(
                "UPDATE staff_users SET password_changed_at = ? WHERE id = ?",
                Timestamp.from(claims.issuedAt()),
                staffId
        );

        // Fails closed on equality
        assertThat(stateValidator.isAccountStateValid(staffId, claims.issuedAt())).isFalse();
        assertThatThrownBy(() -> stateValidator.validateAccountState(staffId, claims.issuedAt()))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Same-Second Invalidation: Rejects token when password changed in same second after issuance in database")
    void shouldRejectTokenWhenPasswordChangedInSameSecondAfterIssuanceInDatabase() {
        String email = "heidi" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "Heidi", "Tester", null);
        AccessTokenDto dto = jwtTokenService.generateAccessToken(staff);
        StaffJwtClaims claims = jwtTokenService.validateAccessToken(dto.token());

        // Set password_changed_at in database to 300ms after token issuance (same second)
        Instant subSecondPasswordChange = claims.issuedAt().plusMillis(300);
        adminJdbcTemplate.update(
                "UPDATE staff_users SET password_changed_at = ? WHERE id = ?",
                Timestamp.from(subSecondPasswordChange),
                staffId
        );

        // Deterministic same-second invalidation without sleep
        assertThat(stateValidator.isAccountStateValid(staffId, claims.issuedAt())).isFalse();
        assertThatThrownBy(() -> stateValidator.validateAccountState(staffId, claims.issuedAt()))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Temporal Invalidation: Rejects token when password_changed_at is updated between issuance and validation")
    void shouldRejectTokenWhenPasswordChangedBetweenIssuanceAndValidation() {
        String email = "ivan" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "Ivan", "Tester", null);
        AccessTokenDto dto = jwtTokenService.generateAccessToken(staff);
        StaffJwtClaims claims = jwtTokenService.validateAccessToken(dto.token());

        // Update password_changed_at to 5 seconds after token issuance
        adminJdbcTemplate.update(
                "UPDATE staff_users SET password_changed_at = ? WHERE id = ?",
                Timestamp.from(claims.issuedAt().plusSeconds(5)),
                staffId
        );

        // Must reject token
        assertThat(stateValidator.isAccountStateValid(staffId, claims.issuedAt())).isFalse();
        assertThatThrownBy(() -> stateValidator.validateAccountState(staffId, claims.issuedAt()))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Real-Time Revocation: Rejects token immediately when staff password is changed")
    void shouldRejectTokenWhenPasswordIsChangedAfterIssuance() {
        String email = "david" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "David", "Tester", null);
        AccessTokenDto dto = jwtTokenService.generateAccessToken(staff);
        StaffJwtClaims claims = jwtTokenService.validateAccessToken(dto.token());

        // Token is initially valid
        assertThat(stateValidator.isAccountStateValid(staffId, claims.issuedAt())).isTrue();

        // Administrator changes password (updating password_changed_at to a future instant)
        adminJdbcTemplate.update(
                "UPDATE staff_users SET password_changed_at = CURRENT_TIMESTAMP + INTERVAL '2 seconds' WHERE id = ?",
                staffId
        );

        // Immediate revocation
        assertThat(stateValidator.isAccountStateValid(staffId, claims.issuedAt())).isFalse();
        assertThatThrownBy(() -> stateValidator.validateAccountState(staffId, claims.issuedAt()))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Real-Time Revocation: Rejects token if staff member is deleted from database")
    void shouldRejectTokenWhenStaffIsDeleted() {
        String email = "eve" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "Eve", "Tester", null);
        AccessTokenDto dto = jwtTokenService.generateAccessToken(staff);
        StaffJwtClaims claims = jwtTokenService.validateAccessToken(dto.token());

        // Delete user
        adminJdbcTemplate.update("DELETE FROM staff_users WHERE id = ?", staffId);

        // Immediate rejection
        assertThat(stateValidator.isAccountStateValid(staffId, claims.issuedAt())).isFalse();
        assertThatThrownBy(() -> stateValidator.validateAccountState(staffId, claims.issuedAt()))
                .isInstanceOf(JwtValidationException.class);
    }

    // =========================================================================
    // 3. Database Least-Privilege & Segregation Enforcement
    // =========================================================================

    @Test
    @DisplayName("Privilege Isolation: pms_app is denied access to staff_refresh_tokens and auth routines")
    void shouldVerifyPrivilegeSegregationBetweenPmsAppAndPmsAuth() {
        // 1. pms_app cannot access staff_refresh_tokens
        assertThatThrownBy(() -> jdbcTemplate.queryForList("SELECT * FROM staff_refresh_tokens"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_refresh_tokens");

        // 2. pms_app cannot execute fn_record_staff_login_success (EXECUTE privilege denied)
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                "SELECT fn_record_staff_login_success(?, '127.0.0.1', 'test')",
                Boolean.class,
                UUID.randomUUID()
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_record_staff_login_success");

        // 3. pms_auth can query staff_users account state
        String email = "privilege" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);
        Integer count = authJdbcTemplate.queryForObject(
                "SELECT count(*) FROM staff_users WHERE id = ?",
                Integer.class,
                staffId
        );
        assertThat(count).isEqualTo(1);
    }
}
