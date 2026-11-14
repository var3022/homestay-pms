package com.homestay.pms_backend.security.authorization;

import com.homestay.pms_backend.entity.Guest;
import com.homestay.pms_backend.entity.GuestPropertyAssociation;
import com.homestay.pms_backend.entity.Property;
import com.homestay.pms_backend.enums.GuestAssociationSource;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.repository.GuestPropertyAssociationRepository;
import com.homestay.pms_backend.repository.GuestRepository;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IsolatedDatabaseTest
@org.springframework.test.context.TestPropertySource(properties = {
        "auth.jwt.enforcement.enabled=true",
        "TEST_AUTH_JWT_ENFORCEMENT_ENABLED=true"
})
@DisplayName("PMS Phase 2 — Step 3.4.3.3B.3: Guest Authorization Guard Integration Tests")
class GuestAuthorizationIntegrationTest {

    private static final String TEST_DOMAIN = "@test-guest-authz.com";

    @Autowired
    private GuestAuthorizationGuard guestAuthorizationGuard;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private GuestRepository guestRepository;

    @Autowired
    private GuestPropertyAssociationRepository associationRepository;

    @Autowired
    private Environment environment;

    private JdbcTemplate adminJdbcTemplate;

    private UUID propertyAId;
    private UUID propertyBId;

    private UUID guestAId;
    private UUID guestBId;
    private UUID guestSharedId;

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

        // 1. Create Properties
        propertyAId = getOrCreateProperty("Property Alpha", "GA_PROP_A");
        propertyBId = getOrCreateProperty("Property Beta", "GA_PROP_B");

        // 2. Create Guests
        Guest gA = new Guest();
        gA.setName("Guest Alpha Only");
        guestAId = guestRepository.save(gA).getId();

        Guest gB = new Guest();
        gB.setName("Guest Beta Only");
        guestBId = guestRepository.save(gB).getId();

        Guest gShared = new Guest();
        gShared.setName("Guest Shared Alpha Beta");
        guestSharedId = guestRepository.save(gShared).getId();

        // 3. Create Associations
        Guest guestA = guestRepository.findById(guestAId).orElseThrow();
        Guest guestB = guestRepository.findById(guestBId).orElseThrow();
        Guest guestShared = guestRepository.findById(guestSharedId).orElseThrow();
        Property propA = propertyRepository.findById(propertyAId).orElseThrow();
        Property propB = propertyRepository.findById(propertyBId).orElseThrow();

        associationRepository.saveAndFlush(new GuestPropertyAssociation(guestA, propA, GuestAssociationSource.REGISTRATION));
        associationRepository.saveAndFlush(new GuestPropertyAssociation(guestB, propB, GuestAssociationSource.REGISTRATION));
        associationRepository.saveAndFlush(new GuestPropertyAssociation(guestShared, propA, GuestAssociationSource.REGISTRATION));
        associationRepository.saveAndFlush(new GuestPropertyAssociation(guestShared, propB, GuestAssociationSource.RESERVATION));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanupTestData();
    }

    private void cleanupTestData() {
        if (adminJdbcTemplate != null) {
            adminJdbcTemplate.execute(
                    "DELETE FROM guest_property_associations WHERE guest_id IN (" +
                    "  SELECT id FROM guests WHERE name LIKE 'Guest Alpha%' OR name LIKE 'Guest Beta%' OR name LIKE 'Guest Shared%'" +
                    "); " +
                    "DELETE FROM guests WHERE name LIKE 'Guest Alpha%' OR name LIKE 'Guest Beta%' OR name LIKE 'Guest Shared%'; " +
                    "ALTER TABLE staff_security_audit_events DISABLE TRIGGER trg_staff_security_audit_events_immutable; " +
                    "DELETE FROM staff_security_audit_events WHERE email LIKE '%" + TEST_DOMAIN + "'; " +
                    "DELETE FROM staff_refresh_tokens WHERE staff_user_id IN (SELECT id FROM staff_users WHERE email LIKE '%" + TEST_DOMAIN + "'); " +
                    "DELETE FROM staff_property_roles WHERE staff_user_id IN (SELECT id FROM staff_users WHERE email LIKE '%" + TEST_DOMAIN + "'); " +
                    "DELETE FROM staff_global_roles WHERE staff_user_id IN (SELECT id FROM staff_users WHERE email LIKE '%" + TEST_DOMAIN + "'); " +
                    "DELETE FROM staff_users WHERE email LIKE '%" + TEST_DOMAIN + "'; " +
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
                    prop.setAddressLine1("123 Test St");
                    prop.setCity("City");
                    prop.setState("State");
                    prop.setCountry("Country");
                    prop.setTimezone("UTC");
                    prop.setActive(true);
                    return propertyRepository.save(prop).getId();
                });
    }

    private static final String BCRYPT_HASH = "$2a$12$e80yq7KjVpEw3W6xK9qXxeQjJcM1q4RkQ9aVbY2vK8n1o2p3q4r5s";

    private UUID createTestStaff(String email) {
        UUID id = UUID.randomUUID();
        adminJdbcTemplate.update(
                """
                INSERT INTO staff_users (
                    id, email, password_hash, first_name, last_name, phone,
                    is_active, is_locked, failed_login_attempts, lockout_until,
                    last_login_at, password_changed_at, created_at, updated_at
                ) VALUES (?, ?, ?, 'Test', 'Staff', '+919876543210', TRUE, FALSE, 0, NULL, NULL, CURRENT_TIMESTAMP - INTERVAL '5 seconds', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id,
                email.trim().toLowerCase(),
                BCRYPT_HASH
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
    // 1. SUPER_ADMIN Authorizations
    // =========================================================================

    @Test
    @DisplayName("SUPER_ADMIN has full guest read, manage, update, and global lifecycle authority")
    void shouldAuthorizeSuperAdminForAllGuestOperations() {
        UUID adminId = createTestStaff("superadmin" + TEST_DOMAIN);
        assignGlobalRole(adminId, StaffRole.SUPER_ADMIN);
        authenticateStaff(adminId, "superadmin" + TEST_DOMAIN);

        // Read and Manage across properties
        guestAuthorizationGuard.requireGuestAccess(guestAId, StaffOperation.GUEST_READ);
        guestAuthorizationGuard.requireGuestAccess(guestBId, StaffOperation.GUEST_READ);
        guestAuthorizationGuard.requireGuestAccess(guestAId, StaffOperation.GUEST_MANAGE);
        guestAuthorizationGuard.requireGuestAccess(guestBId, StaffOperation.GUEST_MANAGE);

        // Multi-property shared guest update allowed for super admin
        guestAuthorizationGuard.requireGuestUpdateAccess(guestSharedId);

        // Global activation/deactivation allowed
        guestAuthorizationGuard.requireGlobalSuperAdmin("deactivate-guest");
    }

    // =========================================================================
    // 2. PROPERTY_ADMIN & RECEPTIONIST Scoped Authorizations
    // =========================================================================

    @Test
    @DisplayName("PROPERTY_ADMIN at Property A is authorized for Property A guests but DENIED Property B-only guest with 404")
    void shouldAuthorizePropertyAdminForAssignedPropertyGuests() {
        UUID propAdminId = createTestStaff("propadmin" + TEST_DOMAIN);
        assignPropertyRole(propAdminId, propertyAId, StaffRole.PROPERTY_ADMIN);
        authenticateStaff(propAdminId, "propadmin" + TEST_DOMAIN);

        // Authorized on Property A guest
        guestAuthorizationGuard.requireGuestAccess(guestAId, StaffOperation.GUEST_READ);
        guestAuthorizationGuard.requireGuestAccess(guestAId, StaffOperation.GUEST_MANAGE);
        guestAuthorizationGuard.requireGuestUpdateAccess(guestAId);

        // Cross-property isolation: Property B guest throws 404
        assertThatThrownBy(() -> guestAuthorizationGuard.requireGuestAccess(guestBId, StaffOperation.GUEST_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Guest not found with id: " + guestBId);
    }

    @Test
    @DisplayName("RECEPTIONIST at Property A is authorized for Property A guests but DENIED Property B-only guest with 404")
    void shouldAuthorizeReceptionistForAssignedPropertyGuests() {
        UUID recepId = createTestStaff("recep" + TEST_DOMAIN);
        assignPropertyRole(recepId, propertyAId, StaffRole.RECEPTIONIST);
        authenticateStaff(recepId, "recep" + TEST_DOMAIN);

        // Authorized on Property A guest
        guestAuthorizationGuard.requireGuestAccess(guestAId, StaffOperation.GUEST_READ);
        guestAuthorizationGuard.requireGuestAccess(guestAId, StaffOperation.GUEST_MANAGE);

        // Cross-property isolation: Property B guest throws 404
        assertThatThrownBy(() -> guestAuthorizationGuard.requireGuestAccess(guestBId, StaffOperation.GUEST_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Guest not found with id: " + guestBId);
    }

    // =========================================================================
    // 3. Strict Denial of Guest Access for VIEWER and ACCOUNTANT (403)
    // =========================================================================

    @Test
    @DisplayName("VIEWER at Property A is strictly DENIED guest access with 403 AccessDeniedException")
    void shouldDenyViewerGuestAccessWith403() {
        UUID viewerId = createTestStaff("viewer" + TEST_DOMAIN);
        assignPropertyRole(viewerId, propertyAId, StaffRole.VIEWER);
        authenticateStaff(viewerId, "viewer" + TEST_DOMAIN);

        assertThatThrownBy(() -> guestAuthorizationGuard.requireGuestAccess(guestAId, StaffOperation.GUEST_READ))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access is denied.");

        assertThatThrownBy(() -> guestAuthorizationGuard.requireGuestAccess(guestAId, StaffOperation.GUEST_MANAGE))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access is denied.");
    }

    @Test
    @DisplayName("ACCOUNTANT at Property A is strictly DENIED guest access with 403 AccessDeniedException")
    void shouldDenyAccountantGuestAccessWith403() {
        UUID acctId = createTestStaff("acct" + TEST_DOMAIN);
        assignPropertyRole(acctId, propertyAId, StaffRole.ACCOUNTANT);
        authenticateStaff(acctId, "acct" + TEST_DOMAIN);

        assertThatThrownBy(() -> guestAuthorizationGuard.requireGuestAccess(guestAId, StaffOperation.GUEST_READ))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access is denied.");

        assertThatThrownBy(() -> guestAuthorizationGuard.requireGuestAccess(guestAId, StaffOperation.GUEST_MANAGE))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access is denied.");
    }

    // =========================================================================
    // 4. Cross-Property Resource Enumeration Prevention (Uniform 404)
    // =========================================================================

    @Test
    @DisplayName("Enumeration Prevention: Nonexistent guest and out-of-scope guest return identical 404 responses")
    void shouldReturnIndistinguishable404ForNonexistentAndOutOfScopeGuests() {
        UUID staffId = createTestStaff("staff_enum" + TEST_DOMAIN);
        assignPropertyRole(staffId, propertyAId, StaffRole.RECEPTIONIST);
        authenticateStaff(staffId, "staff_enum" + TEST_DOMAIN);

        UUID nonExistentId = UUID.randomUUID();

        // 1. Nonexistent guest
        assertThatThrownBy(() -> guestAuthorizationGuard.requireGuestAccess(nonExistentId, StaffOperation.GUEST_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Guest not found with id: " + nonExistentId);

        // 2. Real guest at Property B (out of caller scope)
        assertThatThrownBy(() -> guestAuthorizationGuard.requireGuestAccess(guestBId, StaffOperation.GUEST_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Guest not found with id: " + guestBId);
    }

    // =========================================================================
    // 5. Shared Multi-Property Guest Read & Update Protection
    // =========================================================================

    @Test
    @DisplayName("Shared Guest: Authorized staff at Property A and Property B can each read shared guest")
    void shouldAuthorizeSharedGuestReadForBothProperties() {
        UUID staffA = createTestStaff("staffA" + TEST_DOMAIN);
        assignPropertyRole(staffA, propertyAId, StaffRole.RECEPTIONIST);

        UUID staffB = createTestStaff("staffB" + TEST_DOMAIN);
        assignPropertyRole(staffB, propertyBId, StaffRole.RECEPTIONIST);

        // Staff A reads shared guest
        authenticateStaff(staffA, "staffA" + TEST_DOMAIN);
        guestAuthorizationGuard.requireGuestAccess(guestSharedId, StaffOperation.GUEST_READ);

        // Staff B reads shared guest
        authenticateStaff(staffB, "staffB" + TEST_DOMAIN);
        guestAuthorizationGuard.requireGuestAccess(guestSharedId, StaffOperation.GUEST_READ);
    }

    @Test
    @DisplayName("Shared Guest Update Protection: Property-scoped staff cannot modify multi-property shared guest")
    void shouldDenySharedGuestUpdateForPropertyScopedStaff() {
        UUID staffA = createTestStaff("staffA_update" + TEST_DOMAIN);
        assignPropertyRole(staffA, propertyAId, StaffRole.PROPERTY_ADMIN);
        authenticateStaff(staffA, "staffA_update" + TEST_DOMAIN);

        assertThatThrownBy(() -> guestAuthorizationGuard.requireGuestUpdateAccess(guestSharedId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Shared multi-property guest identity cannot be modified by property-scoped staff.");
    }

    @Test
    @DisplayName("Global Guest Lifecycle Protection: Receptionist cannot deactivate guest globally (403)")
    void shouldDenyReceptionistGlobalGuestDeactivation() {
        UUID recepId = createTestStaff("recep_deact" + TEST_DOMAIN);
        assignPropertyRole(recepId, propertyAId, StaffRole.RECEPTIONIST);
        authenticateStaff(recepId, "recep_deact" + TEST_DOMAIN);

        assertThatThrownBy(() -> guestAuthorizationGuard.requireGlobalSuperAdmin("deactivate-guest"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access is denied.");
    }

    // =========================================================================
    // 6. Immediate Dynamic Role Revocation
    // =========================================================================

    @Test
    @DisplayName("Dynamic Revalidation: Role revocation in database immediately takes effect without caching")
    void shouldReflectRoleRevocationImmediatelyOnNextRequest() {
        UUID staffId = createTestStaff("staff_revoke" + TEST_DOMAIN);
        assignPropertyRole(staffId, propertyAId, StaffRole.RECEPTIONIST);
        authenticateStaff(staffId, "staff_revoke" + TEST_DOMAIN);

        // Initially authorized
        guestAuthorizationGuard.requireGuestAccess(guestAId, StaffOperation.GUEST_READ);

        // Revoke role in database
        adminJdbcTemplate.update("DELETE FROM staff_property_roles WHERE staff_user_id = ?", staffId);

        // Subsequent check immediately fails with 404 (now out of scope)
        assertThatThrownBy(() -> guestAuthorizationGuard.requireGuestAccess(guestAId, StaffOperation.GUEST_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Guest not found with id: " + guestAId);
    }
}
