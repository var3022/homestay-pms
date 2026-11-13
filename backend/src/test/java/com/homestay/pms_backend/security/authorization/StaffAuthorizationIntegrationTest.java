package com.homestay.pms_backend.security.authorization;

import com.homestay.pms_backend.entity.Property;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.security.AuthenticatedStaffPrincipal;
import com.homestay.pms_backend.security.auth.StaffAuthenticationToken;
import com.homestay.pms_backend.security.jwt.StaffJwtClaims;
import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IsolatedDatabaseTest
@DisplayName("PMS Phase 2 — Step 3.4.3.1: Staff Authorization Integration Tests")
class StaffAuthorizationIntegrationTest {

    private static final String TEST_DOMAIN = "@test-authz.com";

    @Autowired
    private StaffAuthorizationService authorizationService;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private Environment environment;

    private JdbcTemplate adminJdbcTemplate;

    private UUID propertyLaVivante;
    private UUID propertyElHogar;
    private UUID propertySolaria;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

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

        propertyLaVivante = getOrCreateProperty("La Vivante Homestay", "LAVIV_TEST");
        propertyElHogar = getOrCreateProperty("El Hogar Boutique", "ELHOG_TEST");
        propertySolaria = getOrCreateProperty("Solaria Mountain Resort", "SOLAR_TEST");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanupTestData();
    }

    private void cleanupTestData() {
        if (adminJdbcTemplate != null) {
            adminJdbcTemplate.execute(
                    "ALTER TABLE staff_security_audit_events DISABLE TRIGGER trg_staff_security_audit_events_immutable; " +
                    "DELETE FROM staff_security_audit_events WHERE email LIKE '%" + TEST_DOMAIN + "'; " +
                    "DELETE FROM staff_refresh_tokens WHERE staff_user_id IN (SELECT id FROM staff_users WHERE email LIKE '%" + TEST_DOMAIN + "'); " +
                    "DELETE FROM staff_property_roles WHERE staff_user_id IN (SELECT id FROM staff_users WHERE email LIKE '%" + TEST_DOMAIN + "'); " +
                    "DELETE FROM staff_global_roles WHERE staff_user_id IN (SELECT id FROM staff_users WHERE email LIKE '%" + TEST_DOMAIN + "'); " +
                    "DELETE FROM staff_users WHERE email LIKE '%" + TEST_DOMAIN + "'; " +
                    "DELETE FROM staff_roles WHERE role_code = 'CUSTOM_EXPERIMENTAL'; " +
                    "ALTER TABLE staff_security_audit_events ENABLE TRIGGER trg_staff_security_audit_events_immutable;"
            );
        }
    }

    private UUID getOrCreateProperty(String name, String code) {
        return propertyRepository.findByCode(code)
                .map(Property::getId)
                .orElseGet(() -> {
                    Property prop = new Property();
                    prop.setName(name);
                    prop.setCode(code);
                    prop.setDescription("Integration test property for RBAC authorization verification");
                    prop.setAddressLine1("123 Test Street");
                    prop.setCity("Test City");
                    prop.setState("Test State");
                    prop.setCountry("Test Country");
                    prop.setTimezone("UTC");
                    prop.setActive(true);
                    return propertyRepository.save(prop).getId();
                });
    }

    private static final String VALID_BCRYPT_HASH = "$2a$12$e80yq7KjVpEw3W6xK9qXxeQjJcM1q4RkQ9aVbY2vK8n1o2p3q4r5s";

    private UUID createTestStaff(String email, boolean isActive, boolean isLocked) {
        UUID id = UUID.randomUUID();
        adminJdbcTemplate.update(
                """
                INSERT INTO staff_users (
                    id, email, password_hash, first_name, last_name, phone,
                    is_active, is_locked, failed_login_attempts, lockout_until,
                    last_login_at, password_changed_at, created_at, updated_at
                ) VALUES (?, ?, ?, 'Test', 'Staff', '+919876543210', ?, ?, 0, NULL, NULL, CURRENT_TIMESTAMP - INTERVAL '5 seconds', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id,
                email.trim().toLowerCase(),
                VALID_BCRYPT_HASH,
                isActive,
                isLocked
        );
        return id;
    }

    private void assignGlobalRole(UUID staffUserId, StaffRole role) {
        adminJdbcTemplate.update(
                "INSERT INTO staff_global_roles (staff_user_id, role_code, assigned_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                staffUserId,
                role.getCode()
        );
    }

    private void assignPropertyRole(UUID staffUserId, UUID propertyId, StaffRole role) {
        adminJdbcTemplate.update(
                "INSERT INTO staff_property_roles (staff_user_id, property_id, role_code, assigned_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                staffUserId,
                propertyId,
                role.getCode()
        );
    }

    private void revokePropertyRole(UUID staffUserId, UUID propertyId, StaffRole role) {
        adminJdbcTemplate.update(
                "DELETE FROM staff_property_roles WHERE staff_user_id = ? AND property_id = ? AND role_code = ?",
                staffUserId,
                propertyId,
                role.getCode()
        );
    }

    private void setStaffActiveStatus(UUID staffUserId, boolean isActive) {
        adminJdbcTemplate.update(
                "UPDATE staff_users SET is_active = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                isActive,
                staffUserId
        );
    }

    private void setStaffLockedStatus(UUID staffUserId, boolean isLocked) {
        adminJdbcTemplate.update(
                "UPDATE staff_users SET is_locked = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                isLocked,
                staffUserId
        );
    }

    private void authenticateStaff(UUID staffUserId, String email) {
        AuthenticatedStaffPrincipal principal = new AuthenticatedStaffPrincipal(staffUserId, email, "Test", "Staff");
        Instant now = Instant.now();
        StaffJwtClaims claims = new StaffJwtClaims(
                staffUserId,
                "homestay-pms",
                "pms-clients",
                now,
                now,
                now.plusSeconds(900),
                "test-jti-" + UUID.randomUUID(),
                "Bearer",
                "key-1"
        );
        StaffAuthenticationToken token = new StaffAuthenticationToken(principal, claims);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    // =========================================================================
    // 1. Global SUPER_ADMIN Access Tests
    // =========================================================================

    @Test
    @DisplayName("Global SUPER_ADMIN has authoritative access across all properties for all domain operations")
    void shouldAuthorizeGlobalSuperAdminAcrossAllProperties() {
        String email = "superadmin" + TEST_DOMAIN;
        UUID adminId = createTestStaff(email, true, false);
        assignGlobalRole(adminId, StaffRole.SUPER_ADMIN);

        authenticateStaff(adminId, email);

        assertThat(authorizationService.isGlobalSuperAdmin()).isTrue();
        assertThat(authorizationService.isGlobalSuperAdmin(adminId)).isTrue();

        // Universal property assignment check
        assertThat(authorizationService.isAssignedToProperty(propertyLaVivante)).isTrue();
        assertThat(authorizationService.isAssignedToProperty(propertyElHogar)).isTrue();
        assertThat(authorizationService.isAssignedToProperty(propertySolaria)).isTrue();

        // Access to La Vivante
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.PROPERTY_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.ROOM_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.RESERVATION_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.GUEST_MANAGE)).isTrue();

        // Access to El Hogar
        assertThat(authorizationService.hasPropertyAccess(propertyElHogar, StaffOperation.PROPERTY_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyElHogar, StaffOperation.ROOM_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyElHogar, StaffOperation.RESERVATION_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyElHogar, StaffOperation.GUEST_MANAGE)).isTrue();
    }

    // =========================================================================
    // 2. PROPERTY_ADMIN Scoped Access & Cross-Property Denial Tests
    // =========================================================================

    @Test
    @DisplayName("PROPERTY_ADMIN manages assigned property and is strictly DENIED cross-property access")
    void shouldAuthorizePropertyAdminForAssignedPropertyAndDenyCrossProperty() {
        String email = "padmin" + TEST_DOMAIN;
        UUID padminId = createTestStaff(email, true, false);
        assignPropertyRole(padminId, propertyLaVivante, StaffRole.PROPERTY_ADMIN);

        authenticateStaff(padminId, email);

        assertThat(authorizationService.isGlobalSuperAdmin()).isFalse();

        // Assigned property: La Vivante
        assertThat(authorizationService.isAssignedToProperty(propertyLaVivante)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.PROPERTY_READ)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.PROPERTY_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.ROOM_READ)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.ROOM_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.ROOM_STATUS_UPDATE)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.RESERVATION_READ)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.RESERVATION_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.GUEST_READ)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.GUEST_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyRole(propertyLaVivante, StaffRole.PROPERTY_ADMIN)).isTrue();

        // Cross-property: El Hogar (Strict Denial)
        assertThat(authorizationService.isAssignedToProperty(propertyElHogar)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyElHogar, StaffOperation.PROPERTY_READ)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyElHogar, StaffOperation.PROPERTY_MANAGE)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyElHogar, StaffOperation.ROOM_READ)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyElHogar, StaffOperation.ROOM_MANAGE)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyElHogar, StaffOperation.RESERVATION_MANAGE)).isFalse();
        assertThat(authorizationService.hasPropertyRole(propertyElHogar, StaffRole.PROPERTY_ADMIN)).isFalse();
    }

    // =========================================================================
    // 3. RECEPTIONIST Operational vs Administrative Permissions
    // =========================================================================

    @Test
    @DisplayName("RECEPTIONIST is authorized for front-desk operations but DENIED property/room administration")
    void shouldAuthorizeReceptionistOperationalActionsAndDenyAdministrativeActions() {
        String email = "receptionist" + TEST_DOMAIN;
        UUID recId = createTestStaff(email, true, false);
        assignPropertyRole(recId, propertyLaVivante, StaffRole.RECEPTIONIST);

        authenticateStaff(recId, email);

        // Operational actions allowed
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.PROPERTY_READ)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.ROOM_READ)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.ROOM_STATUS_UPDATE)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.RESERVATION_READ)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.RESERVATION_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.GUEST_READ)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.GUEST_MANAGE)).isTrue();

        // Administrative actions strictly DENIED
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.PROPERTY_MANAGE)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.ROOM_MANAGE)).isFalse();

        // Cross-property access denied
        assertThat(authorizationService.hasPropertyAccess(propertyElHogar, StaffOperation.RESERVATION_READ)).isFalse();
    }

    // =========================================================================
    // 4. ACCOUNTANT Operational Reconciliation vs Mutation Restrictions
    // =========================================================================

    @Test
    @DisplayName("ACCOUNTANT is authorized for financial reconciliation reads but DENIED mutations and guest data")
    void shouldAuthorizeAccountantReconciliationAndDenyMutations() {
        String email = "accountant" + TEST_DOMAIN;
        UUID accId = createTestStaff(email, true, false);
        assignPropertyRole(accId, propertyLaVivante, StaffRole.ACCOUNTANT);

        authenticateStaff(accId, email);

        // Allowed reconciliation reads
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.PROPERTY_READ)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.ROOM_READ)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.RESERVATION_READ)).isTrue();

        // Strictly DENIED mutations and guest identity document privileges
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.PROPERTY_MANAGE)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.ROOM_MANAGE)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.ROOM_STATUS_UPDATE)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.RESERVATION_MANAGE)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.GUEST_READ)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.GUEST_MANAGE)).isFalse();
    }

    // =========================================================================
    // 5. VIEWER Read-Only Enforcement
    // =========================================================================

    @Test
    @DisplayName("VIEWER is authorized for operational reads but DENIED full guest details and all state mutations")
    void shouldAuthorizeViewerReadOnlyAndDenyAllWrites() {
        String email = "viewer" + TEST_DOMAIN;
        UUID viewerId = createTestStaff(email, true, false);
        assignPropertyRole(viewerId, propertyLaVivante, StaffRole.VIEWER);

        authenticateStaff(viewerId, email);

        // Allowed operational reads
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.PROPERTY_READ)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.ROOM_READ)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.RESERVATION_READ)).isTrue();

        // Strictly DENIED full guest details (protects sensitive PII and ID documents)
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.GUEST_READ)).isFalse();

        // Denied ALL mutations
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.PROPERTY_MANAGE)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.ROOM_MANAGE)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.ROOM_STATUS_UPDATE)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.RESERVATION_MANAGE)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.GUEST_MANAGE)).isFalse();
    }

    @Test
    @DisplayName("Guest Privacy Enforcement: Neither VIEWER nor ACCOUNTANT can access GUEST_READ")
    void shouldDenyViewerAndAccountantAccessToGuestRead() {
        String viewerEmail = "v_privacy" + TEST_DOMAIN;
        UUID viewerId = createTestStaff(viewerEmail, true, false);
        assignPropertyRole(viewerId, propertyLaVivante, StaffRole.VIEWER);

        authenticateStaff(viewerId, viewerEmail);
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.GUEST_READ)).isFalse();

        String accEmail = "a_privacy" + TEST_DOMAIN;
        UUID accId = createTestStaff(accEmail, true, false);
        assignPropertyRole(accId, propertyLaVivante, StaffRole.ACCOUNTANT);

        authenticateStaff(accId, accEmail);
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.GUEST_READ)).isFalse();
    }

    // =========================================================================
    // 6. Multiple Property Assignments with Distinct Roles
    // =========================================================================

    @Test
    @DisplayName("Staff with assignments on multiple properties maintains strict role segregation per property")
    void shouldSupportMultiplePropertyAssignmentsWithDistinctRoles() {
        String email = "multi" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false);
        assignPropertyRole(staffId, propertyLaVivante, StaffRole.RECEPTIONIST);
        assignPropertyRole(staffId, propertyElHogar, StaffRole.PROPERTY_ADMIN);

        authenticateStaff(staffId, email);

        // La Vivante: RECEPTIONIST permissions only
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.ROOM_MANAGE)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.RESERVATION_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyRole(propertyLaVivante, StaffRole.RECEPTIONIST)).isTrue();
        assertThat(authorizationService.hasPropertyRole(propertyLaVivante, StaffRole.PROPERTY_ADMIN)).isFalse();

        // El Hogar: PROPERTY_ADMIN permissions
        assertThat(authorizationService.hasPropertyAccess(propertyElHogar, StaffOperation.ROOM_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyElHogar, StaffOperation.RESERVATION_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyRole(propertyElHogar, StaffRole.PROPERTY_ADMIN)).isTrue();
        assertThat(authorizationService.hasPropertyRole(propertyElHogar, StaffRole.RECEPTIONIST)).isFalse();

        // Solaria (unassigned): Denied
        assertThat(authorizationService.isAssignedToProperty(propertySolaria)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertySolaria, StaffOperation.PROPERTY_READ)).isFalse();

        // Distinct assigned property IDs
        Set<UUID> assignedIds = authorizationService.getAssignedPropertyIds();
        assertThat(assignedIds).containsExactlyInAnyOrder(propertyLaVivante, propertyElHogar);
    }

    // =========================================================================
    // 7. No Property Assignments Tests
    // =========================================================================

    @Test
    @DisplayName("Active staff with no role assignments is denied all access")
    void shouldDenyAccessWhenStaffHasNoRoleAssignments() {
        String email = "unassigned" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false);

        authenticateStaff(staffId, email);

        assertThat(authorizationService.isGlobalSuperAdmin()).isFalse();
        assertThat(authorizationService.isAssignedToProperty(propertyLaVivante)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.PROPERTY_READ)).isFalse();
        assertThat(authorizationService.getStaffAssignments()).isEmpty();
        assertThat(authorizationService.getAssignedPropertyIds()).isEmpty();
    }

    // =========================================================================
    // 8. Global vs Property Role Separation
    // =========================================================================

    @Test
    @DisplayName("Property roles do not elevate to global administrative privileges")
    void shouldEnforceGlobalVsPropertyRoleSeparation() {
        String email = "padmin_strict" + TEST_DOMAIN;
        UUID padminId = createTestStaff(email, true, false);
        assignPropertyRole(padminId, propertyLaVivante, StaffRole.PROPERTY_ADMIN);

        authenticateStaff(padminId, email);

        assertThat(authorizationService.isGlobalSuperAdmin()).isFalse();
        assertThat(authorizationService.isAssignedToProperty(propertyElHogar)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyElHogar, StaffOperation.PROPERTY_MANAGE)).isFalse();
    }

    // =========================================================================
    // 9. Real-Time Revocation & Deactivation Tests
    // =========================================================================

    @Test
    @DisplayName("Property role revocation takes effect immediately on the next authorization check")
    void shouldImmediatelyDenyAccessUponRoleRevocation() {
        String email = "revoked" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false);
        assignPropertyRole(staffId, propertyLaVivante, StaffRole.PROPERTY_ADMIN);

        authenticateStaff(staffId, email);

        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.PROPERTY_MANAGE)).isTrue();

        // Revoke the role in the database
        revokePropertyRole(staffId, propertyLaVivante, StaffRole.PROPERTY_ADMIN);

        // Immediate subsequent authorization check must fail closed
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.PROPERTY_MANAGE)).isFalse();
        assertThat(authorizationService.isAssignedToProperty(propertyLaVivante)).isFalse();
    }

    @Test
    @DisplayName("Staff account deactivation (is_active = FALSE) immediately revokes all authorization")
    void shouldImmediatelyDenyAccessUponStaffDeactivation() {
        String email = "deactivated" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false);
        assignPropertyRole(staffId, propertyLaVivante, StaffRole.PROPERTY_ADMIN);

        authenticateStaff(staffId, email);

        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.PROPERTY_MANAGE)).isTrue();

        // Deactivate staff account
        setStaffActiveStatus(staffId, false);

        // Immediate subsequent authorization check must fail closed
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.PROPERTY_MANAGE)).isFalse();
        assertThat(authorizationService.isAssignedToProperty(propertyLaVivante)).isFalse();
        assertThat(authorizationService.getStaffAssignments()).isEmpty();
    }

    @Test
    @DisplayName("Staff account lockout (is_locked = TRUE) immediately revokes all authorization")
    void shouldImmediatelyDenyAccessUponStaffLockout() {
        String email = "locked" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false);
        assignPropertyRole(staffId, propertyLaVivante, StaffRole.PROPERTY_ADMIN);

        authenticateStaff(staffId, email);

        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.PROPERTY_MANAGE)).isTrue();

        // Lock staff account
        setStaffLockedStatus(staffId, true);

        // Immediate subsequent authorization check must fail closed
        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.PROPERTY_MANAGE)).isFalse();
        assertThat(authorizationService.isAssignedToProperty(propertyLaVivante)).isFalse();
    }

    // =========================================================================
    // 10. Null Property ID & Unauthenticated Rejection
    // =========================================================================

    @Test
    @DisplayName("Null property ID is strictly rejected for scoped operations")
    void shouldRejectNullPropertyIdForScopedOperations() {
        String email = "null_prop" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false);
        assignPropertyRole(staffId, propertyLaVivante, StaffRole.PROPERTY_ADMIN);

        authenticateStaff(staffId, email);

        assertThat(authorizationService.hasPropertyAccess(null, StaffOperation.PROPERTY_MANAGE)).isFalse();
        assertThat(authorizationService.isAssignedToProperty(null)).isFalse();
        assertThat(authorizationService.hasPropertyRole((UUID) null, StaffRole.PROPERTY_ADMIN)).isFalse();
    }

    @Test
    @DisplayName("Authorization fails closed when SecurityContext is empty or unauthenticated")
    void shouldRejectAccessWhenSecurityContextIsMissingOrUnauthenticated() {
        SecurityContextHolder.clearContext();

        assertThat(authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.ROOM_READ)).isFalse();
        assertThat(authorizationService.isGlobalSuperAdmin()).isFalse();
        assertThat(authorizationService.isAssignedToProperty(propertyLaVivante)).isFalse();
        assertThat(authorizationService.getStaffAssignments()).isEmpty();
        assertThat(authorizationService.getAssignedPropertyIds()).isEmpty();
    }

    // =========================================================================
    // 11. Resistance to Client-Supplied Staff UUID Spoofing
    // =========================================================================

    @Test
    @DisplayName("Client-supplied staff UUID parameters cannot bypass authorization because identity is resolved from SecurityContext")
    void shouldPreventClientSuppliedStaffUuidSpoofingUsingSecurityContext() {
        // Staff A is Property Admin
        String adminEmail = "legit_admin" + TEST_DOMAIN;
        UUID adminId = createTestStaff(adminEmail, true, false);
        assignPropertyRole(adminId, propertyLaVivante, StaffRole.PROPERTY_ADMIN);

        // Staff B has zero permissions
        String attackerEmail = "attacker" + TEST_DOMAIN;
        UUID attackerId = createTestStaff(attackerEmail, true, false);

        // Attacker is authenticated in SecurityContext
        authenticateStaff(attackerId, attackerEmail);

        // Attacker attempts request-facing operation:
        // Even if an attacker supplies adminId in an HTTP parameter or body,
        // request-facing authorization extracts attackerId strictly from SecurityContext!
        boolean hasAccess = authorizationService.hasPropertyAccess(propertyLaVivante, StaffOperation.PROPERTY_MANAGE);
        assertThat(hasAccess).isFalse();
    }

    // =========================================================================
    // 12. Database Corrupted / Unknown Role Code Integrity Tests
    // =========================================================================

    @Test
    @DisplayName("Database check constraint chk_staff_roles_valid_code and foreign key prevent unknown roles from entering the database")
    void shouldEnforceDatabaseSchemaLevelRoleIntegrity() {
        String email = "corrupt" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false);

        // Attempting to insert an unknown role code is rejected by chk_staff_roles_valid_code
        assertThatThrownBy(() -> adminJdbcTemplate.update(
                "INSERT INTO staff_roles (role_code, name, description, is_property_scopable) VALUES ('CUSTOM_EXPERIMENTAL', 'Experimental', 'Test', TRUE)"
        )).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("chk_staff_roles_valid_code");

        // And inserting into staff_property_roles with unseeded role is rejected by foreign key
        assertThatThrownBy(() -> adminJdbcTemplate.update(
                "INSERT INTO staff_property_roles (staff_user_id, property_id, role_code) VALUES (?, ?, 'CUSTOM_EXPERIMENTAL')",
                staffId, propertyLaVivante
        )).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
