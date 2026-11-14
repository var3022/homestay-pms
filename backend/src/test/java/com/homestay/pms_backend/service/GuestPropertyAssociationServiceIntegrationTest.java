package com.homestay.pms_backend.service;

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
import com.homestay.pms_backend.security.authorization.StaffRole;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IsolatedDatabaseTest
@org.springframework.test.context.TestPropertySource(properties = {
        "auth.jwt.enforcement.enabled=true",
        "TEST_AUTH_JWT_ENFORCEMENT_ENABLED=true"
})
@DisplayName("PMS Phase 2 — Step 3.4.3.3B.3: Guest Property Association Service Integration Tests")
class GuestPropertyAssociationServiceIntegrationTest {

    private static final String TEST_DOMAIN = "@test-assoc-service.com";

    @Autowired
    private GuestPropertyAssociationService associationService;

    @Autowired
    private GuestPropertyAssociationRepository associationRepository;

    @Autowired
    private GuestRepository guestRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Environment environment;

    private JdbcTemplate adminJdbcTemplate;

    private UUID propertyAId;
    private UUID propertyBId;
    private UUID guestId1;
    private UUID guestId2;

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
        propertyAId = getOrCreateProperty("Property Alpha", "SVC_PROP_A");
        propertyBId = getOrCreateProperty("Property Beta", "SVC_PROP_B");

        // 2. Create Guests
        Guest g1 = new Guest();
        g1.setName("Service Test Guest 1");
        guestId1 = guestRepository.save(g1).getId();

        Guest g2 = new Guest();
        g2.setName("Service Test Guest 2");
        guestId2 = guestRepository.save(g2).getId();
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
                    "  SELECT id FROM guests WHERE name LIKE 'Service Test Guest%'" +
                    "); " +
                    "DELETE FROM guests WHERE name LIKE 'Service Test Guest%'; " +
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
                    prop.setAddressLine1("123 Service St");
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
    // 1. Initial Registration Association & Idempotency
    // =========================================================================

    @Test
    @DisplayName("Registration Association: Creates association with REGISTRATION source idempotently")
    void shouldCreateRegistrationAssociationIdempotently() {
        GuestPropertyAssociation assoc1 = associationService.createRegistrationAssociation(guestId1, propertyAId);

        assertThat(assoc1).isNotNull();
        assertThat(assoc1.getGuestId()).isEqualTo(guestId1);
        assertThat(assoc1.getPropertyId()).isEqualTo(propertyAId);
        assertThat(assoc1.getAssociationSource()).isEqualTo(GuestAssociationSource.REGISTRATION);

        // Re-running registration insert must be completely idempotent (no exception, returns existing)
        GuestPropertyAssociation assoc2 = associationService.createRegistrationAssociation(guestId1, propertyAId);
        assertThat(assoc2.getId()).isEqualTo(assoc1.getId());
        assertThat(associationRepository.countByIdPropertyId(propertyAId)).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrency Safety: Concurrent registration attempts for the same guest and property succeed without duplicate key error")
    void shouldHandleConcurrentRegistrationAssociationsSafely() throws Exception {
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<GuestPropertyAssociation>> tasks = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            tasks.add(() -> associationService.createRegistrationAssociation(guestId1, propertyAId));
        }

        List<Future<GuestPropertyAssociation>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        for (Future<GuestPropertyAssociation> future : futures) {
            GuestPropertyAssociation result = future.get();
            assertThat(result).isNotNull();
            assertThat(result.getGuestId()).isEqualTo(guestId1);
        }

        assertThat(associationRepository.countByIdPropertyId(propertyAId)).isEqualTo(1);
    }

    // =========================================================================
    // 2. Historical Provenance Preservation
    // =========================================================================

    @Test
    @DisplayName("Historical Preservation: recordReservationActivity touches last_activity_at while preserving HISTORICAL_BACKFILL source")
    void shouldPreserveHistoricalBackfillSourceAndFirstAssociatedAt() {
        // 1. Directly insert a HISTORICAL_BACKFILL row with an older timestamp
        Instant historicalTime = Instant.now().minusSeconds(86400 * 30).truncatedTo(java.time.temporal.ChronoUnit.MICROS); // 30 days ago
        adminJdbcTemplate.update(
                "INSERT INTO guest_property_associations (guest_id, property_id, first_associated_at, last_activity_at, association_source) " +
                "VALUES (?, ?, ?, ?, 'HISTORICAL_BACKFILL')",
                guestId1, propertyAId, java.sql.Timestamp.from(historicalTime), java.sql.Timestamp.from(historicalTime)
        );

        // Authenticate staff assigned to propertyAId
        UUID staffId = createTestStaff("staff_hist" + TEST_DOMAIN);
        assignPropertyRole(staffId, propertyAId, StaffRole.RECEPTIONIST);
        authenticateStaff(staffId, "staff_hist" + TEST_DOMAIN);

        // 2. Record reservation activity
        associationService.recordReservationActivity(guestId1, propertyAId);

        // 3. Verify association source and first_associated_at are preserved
        Optional<GuestPropertyAssociation> updatedOpt = associationService.getAssociation(guestId1, propertyAId);
        assertThat(updatedOpt).isPresent();
        GuestPropertyAssociation updated = updatedOpt.get();

        assertThat(updated.getAssociationSource()).isEqualTo(GuestAssociationSource.HISTORICAL_BACKFILL);
        assertThat(updated.getFirstAssociatedAt()).isEqualTo(historicalTime);
        assertThat(updated.getLastActivityAt()).isAfter(historicalTime);
    }

    @Test
    @DisplayName("Reservation Activity Safety: recordReservationActivity cannot silently create association for unassociated guest")
    void shouldRejectReservationActivityForUnassociatedGuest() {
        UUID staffId = createTestStaff("staff_no_sil_assoc" + TEST_DOMAIN);
        assignPropertyRole(staffId, propertyAId, StaffRole.RECEPTIONIST);
        authenticateStaff(staffId, "staff_no_sil_assoc" + TEST_DOMAIN);

        assertThatThrownBy(() -> associationService.recordReservationActivity(guestId2, propertyAId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Guest not found with id: " + guestId2);

        assertThat(associationRepository.existsByIdGuestIdAndIdPropertyId(guestId2, propertyAId)).isFalse();
    }

    @Test
    @DisplayName("Reservation Activity Regression: Repeated reservation activity monotonically updates last_activity_at without mutating first_associated_at or source")
    void shouldMonotonicallyUpdateLastActivityOnRepeatedCalls() throws InterruptedException {
        associationService.createRegistrationAssociation(guestId1, propertyAId);

        UUID staffId = createTestStaff("staff_repeat_act" + TEST_DOMAIN);
        assignPropertyRole(staffId, propertyAId, StaffRole.RECEPTIONIST);
        authenticateStaff(staffId, "staff_repeat_act" + TEST_DOMAIN);

        GuestPropertyAssociation initial = associationService.getAssociation(guestId1, propertyAId).orElseThrow();
        Instant firstAssoc = initial.getFirstAssociatedAt();
        Instant activity1 = initial.getLastActivityAt();

        Thread.sleep(15);
        associationService.recordReservationActivity(guestId1, propertyAId);

        GuestPropertyAssociation afterFirst = associationService.getAssociation(guestId1, propertyAId).orElseThrow();
        assertThat(afterFirst.getFirstAssociatedAt()).isEqualTo(firstAssoc);
        assertThat(afterFirst.getLastActivityAt()).isAfter(activity1);
        assertThat(afterFirst.getAssociationSource()).isEqualTo(GuestAssociationSource.REGISTRATION);

        Thread.sleep(15);
        associationService.recordReservationActivity(guestId1, propertyAId);

        GuestPropertyAssociation afterSecond = associationService.getAssociation(guestId1, propertyAId).orElseThrow();
        assertThat(afterSecond.getFirstAssociatedAt()).isEqualTo(firstAssoc);
        assertThat(afterSecond.getLastActivityAt()).isAfter(afterFirst.getLastActivityAt());
        assertThat(afterSecond.getAssociationSource()).isEqualTo(GuestAssociationSource.REGISTRATION);
    }

    // =========================================================================
    // 3. Existing Guest Adoption Policy (validateExistingGuestUsableAtProperty)
    // =========================================================================

    @Test
    @DisplayName("Adoption Policy: Guest associated with Property A can be used for Property A reservations")
    void shouldAllowExistingGuestUsableAtAssociatedProperty() {
        associationService.createRegistrationAssociation(guestId1, propertyAId);

        UUID staffId = createTestStaff("staff_usable" + TEST_DOMAIN);
        assignPropertyRole(staffId, propertyAId, StaffRole.RECEPTIONIST);
        authenticateStaff(staffId, "staff_usable" + TEST_DOMAIN);

        // Must succeed without throwing
        associationService.validateExistingGuestUsableAtProperty(guestId1, propertyAId);
    }

    @Test
    @DisplayName("Adoption Policy: Arbitrary existing guest associated only with Property B cannot be adopted at Property A (404)")
    void shouldRejectAdoptionOfGuestAssociatedOnlyWithOtherProperty() {
        // Associate guest 2 exclusively with Property B
        associationService.createRegistrationAssociation(guestId2, propertyBId);

        UUID staffId = createTestStaff("staff_no_adopt" + TEST_DOMAIN);
        assignPropertyRole(staffId, propertyAId, StaffRole.RECEPTIONIST);
        authenticateStaff(staffId, "staff_no_adopt" + TEST_DOMAIN);

        // Staff at Property A cannot use guest 2 for Property A
        assertThatThrownBy(() -> associationService.validateExistingGuestUsableAtProperty(guestId2, propertyAId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Guest not found with id: " + guestId2);
    }

    @Test
    @DisplayName("Adoption Policy: Global SUPER_ADMIN can use any existing guest across any property")
    void shouldAllowSuperAdminToUseAnyExistingGuest() {
        associationService.createRegistrationAssociation(guestId2, propertyBId);

        UUID adminId = createTestStaff("admin_adopt" + TEST_DOMAIN);
        assignGlobalRole(adminId, StaffRole.SUPER_ADMIN);
        authenticateStaff(adminId, "admin_adopt" + TEST_DOMAIN);

        // Super admin can use guest 2 at property A
        associationService.validateExistingGuestUsableAtProperty(guestId2, propertyAId);
    }

    // =========================================================================
    // 4. Authorized Cross-Property Existing Guest Attachment
    // =========================================================================

    @Test
    @DisplayName("Explicit Attachment: Staff with GUEST_MANAGE on both properties can attach existing guest to target property")
    void shouldAllowCrossPropertyAttachmentForAuthorizedStaff() {
        associationService.createRegistrationAssociation(guestId1, propertyAId);

        // Staff member assigned to both Property A and Property B with PROPERTY_ADMIN
        UUID multiStaffId = createTestStaff("multi_staff" + TEST_DOMAIN);
        assignPropertyRole(multiStaffId, propertyAId, StaffRole.PROPERTY_ADMIN);
        assignPropertyRole(multiStaffId, propertyBId, StaffRole.PROPERTY_ADMIN);
        authenticateStaff(multiStaffId, "multi_staff" + TEST_DOMAIN);

        // Attaches guest 1 to Property B
        GuestPropertyAssociation attached = associationService.attachExistingGuestWithAuthorization(
                guestId1, propertyBId, GuestAssociationSource.RESERVATION
        );
        assertThat(attached).isNotNull();
        assertThat(attached.getGuestId()).isEqualTo(guestId1);
        assertThat(attached.getPropertyId()).isEqualTo(propertyBId);

        // Now guest 1 is associated with both properties
        List<UUID> props = associationService.getAssociatedPropertyIds(guestId1);
        assertThat(props).containsExactlyInAnyOrder(propertyAId, propertyBId);
    }

    @Test
    @DisplayName("Explicit Attachment: Staff lacking GUEST_MANAGE on source property is DENIED cross-property attachment (403)")
    void shouldDenyCrossPropertyAttachmentWhenLackingAuthorityOnSourceProperty() {
        associationService.createRegistrationAssociation(guestId1, propertyAId);

        // Staff member assigned to Property B only
        UUID staffB = createTestStaff("staff_b_only" + TEST_DOMAIN);
        assignPropertyRole(staffB, propertyBId, StaffRole.PROPERTY_ADMIN);
        authenticateStaff(staffB, "staff_b_only" + TEST_DOMAIN);

        assertThatThrownBy(() -> associationService.attachExistingGuestWithAuthorization(
                guestId1, propertyBId, GuestAssociationSource.RESERVATION))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Explicit cross-property authorization required to associate existing guest.");
    }

    // =========================================================================
    // 5. Atomic Registration Transaction Rollback
    // =========================================================================

    @Test
    @DisplayName("Atomic Transaction: Guest creation rolls back if association insertion fails")
    void shouldRollBackGuestCreationIfAssociationFails() {
        UUID nonExistentPropertyId = UUID.randomUUID();
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            Guest guest = new Guest();
            guest.setName("Rollback Test Guest");
            Guest saved = guestRepository.saveAndFlush(guest);

            // Attempt association with non-existent property (must throw ResourceNotFoundException and roll back)
            associationService.createRegistrationAssociation(saved.getId(), nonExistentPropertyId);
            return null;
        })).isInstanceOf(ResourceNotFoundException.class);

        // Verify guest was rolled back and does not exist in the database
        List<Guest> remaining = guestRepository.findAll().stream()
                .filter(g -> "Rollback Test Guest".equals(g.getName()))
                .toList();
        assertThat(remaining).isEmpty();
    }
}
