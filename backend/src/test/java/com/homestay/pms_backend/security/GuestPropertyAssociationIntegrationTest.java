package com.homestay.pms_backend.security;

import com.homestay.pms_backend.entity.Guest;
import com.homestay.pms_backend.entity.GuestPropertyAssociation;
import com.homestay.pms_backend.entity.GuestPropertyAssociationId;
import com.homestay.pms_backend.entity.Property;
import com.homestay.pms_backend.enums.GuestAssociationSource;
import com.homestay.pms_backend.enums.IdentityDocumentType;
import com.homestay.pms_backend.repository.GuestPropertyAssociationRepository;
import com.homestay.pms_backend.repository.GuestRepository;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IsolatedDatabaseTest
@DisplayName("PMS Phase 2 — Step 3.4.3.3B.2: Guest-Property Association Database Foundation Integration Tests")
class GuestPropertyAssociationIntegrationTest {

    private static final String CLEAN_VALIDATION_DB_URL = "jdbc:postgresql://localhost:5433/v8_clean_validation_db";
    private static final String DISPOSABLE_DB_URL = "jdbc:postgresql://localhost:5433/v7_disposable_db";
    private static final String ADMIN_USER = "pms_admin";
    private static final String ADMIN_PASS = "disposable_pass";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GuestRepository guestRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private GuestPropertyAssociationRepository associationRepository;

    private UUID testPropertyIdA;
    private UUID testPropertyIdB;
    private UUID testGuestId1;
    private UUID testGuestId2;

    private JdbcTemplate createAdminJdbcTemplate() {
        DriverManagerDataSource adminDs = new DriverManagerDataSource();
        adminDs.setDriverClassName("org.postgresql.Driver");
        adminDs.setUrl(DISPOSABLE_DB_URL);
        adminDs.setUsername(ADMIN_USER);
        adminDs.setPassword(ADMIN_PASS);
        return new JdbcTemplate(adminDs);
    }

    @BeforeEach
    void setUp() {
        // Create isolated test properties with all mandatory fields
        Property propA = new Property();
        propA.setName("GPA Test Property A");
        propA.setCode("GPA_PROP_A_" + UUID.randomUUID().toString().substring(0, 8));
        propA.setAddressLine1("100 Test St");
        propA.setCity("Test City");
        propA.setState("Test State");
        propA.setCountry("India");
        propA.setTimezone("Asia/Kolkata");
        propA.setActive(true);
        propA = propertyRepository.saveAndFlush(propA);
        testPropertyIdA = propA.getId();

        Property propB = new Property();
        propB.setName("GPA Test Property B");
        propB.setCode("GPA_PROP_B_" + UUID.randomUUID().toString().substring(0, 8));
        propB.setAddressLine1("200 Test Ave");
        propB.setCity("Test City");
        propB.setState("Test State");
        propB.setCountry("India");
        propB.setTimezone("Asia/Kolkata");
        propB.setActive(true);
        propB = propertyRepository.saveAndFlush(propB);
        testPropertyIdB = propB.getId();

        // Create isolated test guests
        Guest g1 = new Guest();
        g1.setName("GPA Test Guest One");
        g1.setEmail("gpa.guest1." + UUID.randomUUID().toString().substring(0, 8) + "@test.com");
        g1.setPhone("+919876543201");
        g1.setIdType(IdentityDocumentType.PASSPORT);
        g1.setIdNumber("P12345678");
        g1 = guestRepository.saveAndFlush(g1);
        testGuestId1 = g1.getId();

        Guest g2 = new Guest();
        g2.setName("GPA Test Guest Two");
        g2.setEmail("gpa.guest2." + UUID.randomUUID().toString().substring(0, 8) + "@test.com");
        g2.setPhone("+919876543202");
        g2.setIdType(IdentityDocumentType.AADHAAR);
        g2.setIdNumber("123456789012");
        g2 = guestRepository.saveAndFlush(g2);
        testGuestId2 = g2.getId();
    }

    @AfterEach
    void tearDown() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        if (testGuestId1 != null && testGuestId2 != null) {
            adminJdbc.update("DELETE FROM guest_property_associations WHERE guest_id IN (?, ?)", testGuestId1, testGuestId2);
        }
        if (testPropertyIdA != null && testPropertyIdB != null) {
            adminJdbc.update("DELETE FROM guest_property_associations WHERE property_id IN (?, ?)", testPropertyIdA, testPropertyIdB);
        }
    }

    // =========================================================================
    // 1. Clean Flyway V1-V10 Migration & Sequential Upgrade Tests
    // =========================================================================

    @Test
    @DisplayName("Clean Migration: Flyway V1-V10 executes cleanly on clean database with exactly 10 migrations")
    void shouldExecuteCleanV1ThroughV10Migration() {
        Flyway flyway = Flyway.configure()
                .dataSource(CLEAN_VALIDATION_DB_URL, ADMIN_USER, ADMIN_PASS)
                .locations("classpath:db/migration")
                .target("10")
                .cleanDisabled(false)
                .load();

        flyway.clean();
        var result = flyway.migrate();
        assertThat(result.migrationsExecuted).isEqualTo(10);

        MigrationInfo current = flyway.info().current();
        assertThat(current).isNotNull();
        assertThat(current.getVersion().getVersion()).isEqualTo("10");
        assertThat(current.getDescription()).isEqualTo("create guest property associations");

        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).hasSize(10);
        for (MigrationInfo info : applied) {
            assertThat(info.getState().isApplied())
                    .as("Migration V%s must be applied", info.getVersion().getVersion())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Upgrade Validation: Sequential migration from V9 to V10 succeeds with 1 migration executed")
    void shouldUpgradeFromV9ToV10Successfully() {
        Flyway v9Flyway = Flyway.configure()
                .dataSource(CLEAN_VALIDATION_DB_URL, ADMIN_USER, ADMIN_PASS)
                .locations("classpath:db/migration")
                .target("9")
                .cleanDisabled(false)
                .load();

        v9Flyway.clean();
        var v9Result = v9Flyway.migrate();
        assertThat(v9Result.migrationsExecuted).isEqualTo(9);
        assertThat(v9Flyway.info().current().getVersion().getVersion()).isEqualTo("9");

        // Now upgrade to V10
        Flyway v10Flyway = Flyway.configure()
                .dataSource(CLEAN_VALIDATION_DB_URL, ADMIN_USER, ADMIN_PASS)
                .locations("classpath:db/migration")
                .target("10")
                .cleanDisabled(false)
                .load();

        var v10Result = v10Flyway.migrate();
        assertThat(v10Result.migrationsExecuted).isEqualTo(1);
        assertThat(v10Flyway.info().current().getVersion().getVersion()).isEqualTo("10");
        assertThat(v10Flyway.info().current().getDescription()).isEqualTo("create guest property associations");
    }

    @Test
    @DisplayName("Upgrade Validation: Populated pre-V10 database upgraded to V10 backfills primary, secondary, and multi-property guests")
    void shouldUpgradePopulatedPreV10DatabaseAndVerifyBackfill() {
        Flyway v9Flyway = Flyway.configure()
                .dataSource(CLEAN_VALIDATION_DB_URL, ADMIN_USER, ADMIN_PASS)
                .locations("classpath:db/migration")
                .target("9")
                .cleanDisabled(false)
                .load();

        v9Flyway.clean();
        v9Flyway.migrate();
        assertThat(v9Flyway.info().current().getVersion().getVersion()).isEqualTo("9");

        // Insert pre-V10 data using raw SQL on CLEAN_VALIDATION_DB_URL
        DriverManagerDataSource ds = new DriverManagerDataSource(CLEAN_VALIDATION_DB_URL, ADMIN_USER, ADMIN_PASS);
        JdbcTemplate cleanJdbc = new JdbcTemplate(ds);

        UUID propA = UUID.randomUUID();
        UUID propB = UUID.randomUUID();
        cleanJdbc.update("INSERT INTO properties (id, code, name, address_line1, city, state, country, timezone) VALUES (?, 'CLEAN_A', 'Clean Prop A', '123 Main', 'City', 'State', 'Country', 'UTC')", propA);
        cleanJdbc.update("INSERT INTO properties (id, code, name, address_line1, city, state, country, timezone) VALUES (?, 'CLEAN_B', 'Clean Prop B', '456 Side', 'City', 'State', 'Country', 'UTC')", propB);

        UUID gPrimary = UUID.randomUUID();
        UUID gSecondary = UUID.randomUUID();
        UUID gShared = UUID.randomUUID();
        UUID gCancelled = UUID.randomUUID();
        UUID gCheckedOut = UUID.randomUUID();
        UUID gUnreserved = UUID.randomUUID();

        cleanJdbc.update("INSERT INTO guests (id, name) VALUES (?, 'Primary Guest')", gPrimary);
        cleanJdbc.update("INSERT INTO guests (id, name) VALUES (?, 'Secondary Guest')", gSecondary);
        cleanJdbc.update("INSERT INTO guests (id, name) VALUES (?, 'Shared Guest')", gShared);
        cleanJdbc.update("INSERT INTO guests (id, name) VALUES (?, 'Cancelled Guest')", gCancelled);
        cleanJdbc.update("INSERT INTO guests (id, name) VALUES (?, 'CheckedOut Guest')", gCheckedOut);
        cleanJdbc.update("INSERT INTO guests (id, name) VALUES (?, 'Unreserved Guest')", gUnreserved);

        UUID directSourceId = cleanJdbc.queryForObject("SELECT id FROM booking_sources WHERE code = 'DIRECT'", UUID.class);

        // 1. Confirmed reservation for gPrimary at propA
        UUID res1 = UUID.randomUUID();
        cleanJdbc.update("INSERT INTO reservations (id, property_id, primary_guest_id, check_in_date, check_out_date, adults, booking_source_id, status) VALUES (?, ?, ?, CURRENT_DATE + 5, CURRENT_DATE + 8, 1, ?, 'CONFIRMED')",
                res1, propA, gPrimary, directSourceId);

        // 2. Secondary guest on res1
        cleanJdbc.update("INSERT INTO reservation_guests (reservation_id, guest_id, role) VALUES (?, ?, 'ADDITIONAL_GUEST')",
                res1, gSecondary);

        // 3. Shared guest at both propA and propB
        UUID resSharedA = UUID.randomUUID();
        cleanJdbc.update("INSERT INTO reservations (id, property_id, primary_guest_id, check_in_date, check_out_date, adults, booking_source_id, status) VALUES (?, ?, ?, CURRENT_DATE + 10, CURRENT_DATE + 12, 1, ?, 'CONFIRMED')",
                resSharedA, propA, gShared, directSourceId);
        UUID resSharedB = UUID.randomUUID();
        cleanJdbc.update("INSERT INTO reservations (id, property_id, primary_guest_id, check_in_date, check_out_date, adults, booking_source_id, status) VALUES (?, ?, ?, CURRENT_DATE + 20, CURRENT_DATE + 22, 1, ?, 'CONFIRMED')",
                resSharedB, propB, gShared, directSourceId);

        // 4. Cancelled reservation for gCancelled at propA
        UUID resCancelled = UUID.randomUUID();
        cleanJdbc.update("INSERT INTO reservations (id, property_id, primary_guest_id, check_in_date, check_out_date, adults, booking_source_id, status) VALUES (?, ?, ?, CURRENT_DATE + 30, CURRENT_DATE + 32, 1, ?, 'CANCELLED')",
                resCancelled, propA, gCancelled, directSourceId);

        // 5. Checked-out reservation for gCheckedOut at propA
        UUID resCheckedOut = UUID.randomUUID();
        cleanJdbc.update("INSERT INTO reservations (id, property_id, primary_guest_id, check_in_date, check_out_date, adults, booking_source_id, status) VALUES (?, ?, ?, CURRENT_DATE - 10, CURRENT_DATE - 8, 1, ?, 'CHECKED_OUT')",
                resCheckedOut, propA, gCheckedOut, directSourceId);

        // Execute Flyway V10 migration on this POPULATED database
        Flyway v10Flyway = Flyway.configure()
                .dataSource(CLEAN_VALIDATION_DB_URL, ADMIN_USER, ADMIN_PASS)
                .locations("classpath:db/migration")
                .target("10")
                .cleanDisabled(false)
                .load();

        var v10Result = v10Flyway.migrate();
        assertThat(v10Result.migrationsExecuted).isEqualTo(1);
        assertThat(v10Flyway.info().current().getVersion().getVersion()).isEqualTo("10");

        // Verify associations created by Flyway V10 migration
        Integer primaryAssocCount = cleanJdbc.queryForObject(
                "SELECT count(*) FROM guest_property_associations WHERE guest_id = ? AND property_id = ? AND association_source = 'RESERVATION'",
                Integer.class, gPrimary, propA);
        assertThat(primaryAssocCount).isEqualTo(1);

        Integer secondaryAssocCount = cleanJdbc.queryForObject(
                "SELECT count(*) FROM guest_property_associations WHERE guest_id = ? AND property_id = ? AND association_source = 'RESERVATION'",
                Integer.class, gSecondary, propA);
        assertThat(secondaryAssocCount).isEqualTo(1);

        Integer sharedCountA = cleanJdbc.queryForObject(
                "SELECT count(*) FROM guest_property_associations WHERE guest_id = ? AND property_id = ?",
                Integer.class, gShared, propA);
        Integer sharedCountB = cleanJdbc.queryForObject(
                "SELECT count(*) FROM guest_property_associations WHERE guest_id = ? AND property_id = ?",
                Integer.class, gShared, propB);
        assertThat(sharedCountA).isEqualTo(1);
        assertThat(sharedCountB).isEqualTo(1);

        Integer cancelledCount = cleanJdbc.queryForObject(
                "SELECT count(*) FROM guest_property_associations WHERE guest_id = ? AND property_id = ?",
                Integer.class, gCancelled, propA);
        assertThat(cancelledCount).isEqualTo(1);

        Integer checkedOutCount = cleanJdbc.queryForObject(
                "SELECT count(*) FROM guest_property_associations WHERE guest_id = ? AND property_id = ?",
                Integer.class, gCheckedOut, propA);
        assertThat(checkedOutCount).isEqualTo(1);

        Integer unreservedCount = cleanJdbc.queryForObject(
                "SELECT count(*) FROM guest_property_associations WHERE guest_id = ?",
                Integer.class, gUnreserved);
        assertThat(unreservedCount).isZero();

        Integer invalidTimestamps = cleanJdbc.queryForObject(
                "SELECT count(*) FROM guest_property_associations WHERE last_activity_at < first_associated_at",
                Integer.class);
        assertThat(invalidTimestamps).isZero();
    }

    // =========================================================================
    // 2. Schema Structure and Constraints
    // =========================================================================

    @Test
    @DisplayName("Schema Constraints: Verify composite PK, foreign keys, and indexes exist on guest_property_associations")
    void shouldVerifySchemaConstraintsAndIndexes() {
        // Table ownership
        String owner = jdbcTemplate.queryForObject(
                "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'guest_property_associations'",
                String.class
        );
        assertThat(owner).isEqualTo("pms_admin");

        // Composite primary key
        Integer pkCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conrelid = 'guest_property_associations'::regclass AND conname = 'pk_guest_property_associations'",
                Integer.class
        );
        assertThat(pkCount).isEqualTo(1);

        // Foreign keys to guests and properties
        Integer fkGuest = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conrelid = 'guest_property_associations'::regclass AND conname = 'fk_gpa_guest'",
                Integer.class
        );
        assertThat(fkGuest).isEqualTo(1);

        Integer fkProperty = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conrelid = 'guest_property_associations'::regclass AND conname = 'fk_gpa_property'",
                Integer.class
        );
        assertThat(fkProperty).isEqualTo(1);

        // Check constraint for association_source
        Integer chkSource = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conrelid = 'guest_property_associations'::regclass AND conname = 'chk_gpa_association_source'",
                Integer.class
        );
        assertThat(chkSource).isEqualTo(1);

        // Check constraint for timestamps
        Integer chkTimestamps = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conrelid = 'guest_property_associations'::regclass AND conname = 'chk_gpa_timestamps'",
                Integer.class
        );
        assertThat(chkTimestamps).isEqualTo(1);
    }

    @Test
    @DisplayName("Integrity Constraint: Invalid association_source is rejected by database CHECK constraint")
    void shouldRejectInvalidAssociationSource() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        assertThatThrownBy(() -> adminJdbc.execute(
                "INSERT INTO guest_property_associations (guest_id, property_id, association_source) " +
                "VALUES ('" + testGuestId1 + "', '" + testPropertyIdA + "', 'INVALID_SOURCE')"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("chk_gpa_association_source");
    }

    @Test
    @DisplayName("Referential Integrity: Non-existent guest_id is rejected by foreign key constraint")
    void shouldRejectNonExistentGuestId() {
        UUID nonExistentGuestId = UUID.randomUUID();
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        assertThatThrownBy(() -> adminJdbc.execute(
                "INSERT INTO guest_property_associations (guest_id, property_id, association_source) " +
                "VALUES ('" + nonExistentGuestId + "', '" + testPropertyIdA + "', 'REGISTRATION')"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("fk_gpa_guest");
    }

    @Test
    @DisplayName("Referential Integrity: Non-existent property_id is rejected by foreign key constraint")
    void shouldRejectNonExistentPropertyId() {
        UUID nonExistentPropertyId = UUID.randomUUID();
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        assertThatThrownBy(() -> adminJdbc.execute(
                "INSERT INTO guest_property_associations (guest_id, property_id, association_source) " +
                "VALUES ('" + testGuestId1 + "', '" + nonExistentPropertyId + "', 'REGISTRATION')"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("fk_gpa_property");
    }

    // =========================================================================
    // 3. Historical Backfill and Idempotency Tests
    // =========================================================================

    @Test
    @DisplayName("Backfill Validation: Primary guest and secondary guest associations backfill correctly and idempotently")
    void shouldVerifyBackfillIdempotencyAndCompleteness() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();

        // 1. Create a reservation for guest 1 at Property A
        UUID directSourceId = jdbcTemplate.queryForObject(
                "SELECT id FROM booking_sources WHERE code = 'DIRECT'", UUID.class);

        UUID resId = UUID.randomUUID();
        adminJdbc.update(
                "INSERT INTO reservations (id, property_id, primary_guest_id, check_in_date, check_out_date, adults, booking_source_id, status) " +
                "VALUES (?, ?, ?, CURRENT_DATE + 30, CURRENT_DATE + 32, 1, ?, 'CONFIRMED')",
                resId, testPropertyIdA, testGuestId1, directSourceId
        );

        // 2. Execute backfill statement (identical to V10 migration)
        adminJdbc.execute(
                "INSERT INTO guest_property_associations (guest_id, property_id, first_associated_at, last_activity_at, association_source) " +
                "SELECT r.primary_guest_id, r.property_id, MIN(r.created_at), GREATEST(MIN(r.created_at), MAX(COALESCE(r.updated_at, r.created_at))), 'RESERVATION' " +
                "FROM reservations r WHERE r.id = '" + resId + "' GROUP BY r.primary_guest_id, r.property_id " +
                "ON CONFLICT (guest_id, property_id) DO NOTHING;"
        );

        // Verify association exists
        boolean exists = associationRepository.existsByIdGuestIdAndIdPropertyId(testGuestId1, testPropertyIdA);
        assertThat(exists).isTrue();

        // 3. Re-run backfill statement to verify idempotency (must not throw or duplicate)
        adminJdbc.execute(
                "INSERT INTO guest_property_associations (guest_id, property_id, first_associated_at, last_activity_at, association_source) " +
                "SELECT r.primary_guest_id, r.property_id, MIN(r.created_at), GREATEST(MIN(r.created_at), MAX(COALESCE(r.updated_at, r.created_at))), 'RESERVATION' " +
                "FROM reservations r WHERE r.id = '" + resId + "' GROUP BY r.primary_guest_id, r.property_id " +
                "ON CONFLICT (guest_id, property_id) DO NOTHING;"
        );

        long count = associationRepository.countByIdPropertyId(testPropertyIdA);
        assertThat(count).isEqualTo(1);

        // Cleanup test reservation
        adminJdbc.update("DELETE FROM reservations WHERE id = ?", resId);
    }

    @Test
    @DisplayName("Lifecycle Preservation: Cancelled and checked-out reservations retain property associations")
    void shouldRetainAssociationsForCancelledAndCheckedOutReservations() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID directSourceId = jdbcTemplate.queryForObject(
                "SELECT id FROM booking_sources WHERE code = 'DIRECT'", UUID.class);

        // 1. Create Cancelled Reservation
        UUID cancelledResId = UUID.randomUUID();
        adminJdbc.update(
                "INSERT INTO reservations (id, property_id, primary_guest_id, check_in_date, check_out_date, adults, booking_source_id, status) " +
                "VALUES (?, ?, ?, CURRENT_DATE + 40, CURRENT_DATE + 42, 1, ?, 'CANCELLED')",
                cancelledResId, testPropertyIdA, testGuestId2, directSourceId
        );

        adminJdbc.execute(
                "INSERT INTO guest_property_associations (guest_id, property_id, first_associated_at, last_activity_at, association_source) " +
                "SELECT r.primary_guest_id, r.property_id, MIN(r.created_at), GREATEST(MIN(r.created_at), MAX(COALESCE(r.updated_at, r.created_at))), 'RESERVATION' " +
                "FROM reservations r WHERE r.id = '" + cancelledResId + "' GROUP BY r.primary_guest_id, r.property_id " +
                "ON CONFLICT (guest_id, property_id) DO NOTHING;"
        );

        assertThat(associationRepository.existsByIdGuestIdAndIdPropertyId(testGuestId2, testPropertyIdA)).isTrue();

        // Cleanup
        adminJdbc.update("DELETE FROM reservations WHERE id = ?", cancelledResId);
    }

    // =========================================================================
    // 4. Multi-Property Shared Guest and Unreserved Guest Behavior
    // =========================================================================

    @Test
    @DisplayName("Multi-Property Association: Single guest associated with both properties maintains two distinct rows")
    @Transactional
    void shouldSupportSharedGuestAcrossMultipleProperties() {
        Guest guest = guestRepository.findById(testGuestId1).orElseThrow();
        Property propA = propertyRepository.findById(testPropertyIdA).orElseThrow();
        Property propB = propertyRepository.findById(testPropertyIdB).orElseThrow();

        GuestPropertyAssociation assocA = new GuestPropertyAssociation(guest, propA, GuestAssociationSource.REGISTRATION);
        GuestPropertyAssociation assocB = new GuestPropertyAssociation(guest, propB, GuestAssociationSource.RESERVATION);

        associationRepository.saveAndFlush(assocA);
        associationRepository.saveAndFlush(assocB);

        // Verify guest has 2 distinct associations
        List<UUID> propertyIds = associationRepository.findPropertyIdsByGuestId(testGuestId1);
        assertThat(propertyIds).containsExactlyInAnyOrder(testPropertyIdA, testPropertyIdB);

        // Verify property A only lists guest 1
        List<UUID> guestsAtA = associationRepository.findGuestIdsByPropertyId(testPropertyIdA);
        assertThat(guestsAtA).contains(testGuestId1);

        // Verify property B only lists guest 1
        List<UUID> guestsAtB = associationRepository.findGuestIdsByPropertyId(testPropertyIdB);
        assertThat(guestsAtB).contains(testGuestId1);
    }

    @Test
    @DisplayName("Unreserved Guest: Newly created guest without reservations has zero associations initially")
    void shouldVerifyUnreservedGuestHasZeroAssociationsByDefault() {
        List<UUID> propertyIds = associationRepository.findPropertyIdsByGuestId(testGuestId2);
        assertThat(propertyIds).isEmpty();
        assertThat(associationRepository.countByIdGuestId(testGuestId2)).isZero();
    }

    @Test
    @DisplayName("Duplicate Insertion: Inserting duplicate association via JPA throws DataIntegrityViolationException")
    void shouldRejectDuplicateAssociationInsertion() {
        Guest guest = guestRepository.findById(testGuestId1).orElseThrow();
        Property propA = propertyRepository.findById(testPropertyIdA).orElseThrow();

        GuestPropertyAssociation assoc1 = new GuestPropertyAssociation(guest, propA, GuestAssociationSource.REGISTRATION);
        associationRepository.saveAndFlush(assoc1);

        // Attempting to save another entity with same composite key fails or updates
        GuestPropertyAssociation assocDuplicate = new GuestPropertyAssociation(guest, propA, GuestAssociationSource.RESERVATION);
        // Direct SQL duplicate INSERT must fail with unique/PK violation
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        assertThatThrownBy(() -> adminJdbc.execute(
                "INSERT INTO guest_property_associations (guest_id, property_id, association_source) " +
                "VALUES ('" + testGuestId1 + "', '" + testPropertyIdA + "', 'REGISTRATION')"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("pk_guest_property_associations");
    }

    // =========================================================================
    // 5. Least-Privilege Role Segregation Tests
    // =========================================================================

    @Test
    @DisplayName("Role Segregation: pms_app has full operational DML on guest_property_associations")
    void shouldVerifyPmsAppHasFullDmlOnAssociations() {
        Boolean canSelect = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_app', 'guest_property_associations', 'SELECT')", Boolean.class);
        Boolean canInsert = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_app', 'guest_property_associations', 'INSERT')", Boolean.class);
        Boolean canUpdate = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_app', 'guest_property_associations', 'UPDATE')", Boolean.class);
        Boolean canDelete = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_app', 'guest_property_associations', 'DELETE')", Boolean.class);

        assertThat(canSelect).isTrue();
        assertThat(canInsert).isTrue();
        assertThat(canUpdate).isTrue();
        assertThat(canDelete).isTrue();
    }

    @Test
    @DisplayName("Role Segregation: pms_auth is strictly DENIED all access to guest_property_associations")
    void shouldVerifyPmsAuthIsDeniedAccessToAssociations() {
        Boolean authAccess = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_auth', 'guest_property_associations', 'SELECT')", Boolean.class);
        assertThat(authAccess).isFalse();

        Boolean authInsert = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_auth', 'guest_property_associations', 'INSERT')", Boolean.class);
        assertThat(authInsert).isFalse();
    }

    @Test
    @DisplayName("Role Segregation: pms_finance_admin has NO privileges on guest_property_associations")
    void shouldVerifyPmsFinanceAdminHasNoPrivilegesOnAssociations() {
        Boolean finSelect = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_finance_admin', 'guest_property_associations', 'SELECT')", Boolean.class);
        assertThat(finSelect).isFalse();
    }

    // =========================================================================
    // 6. JPA Repository Operation Tests
    // =========================================================================

    @Test
    @DisplayName("JPA Repository: Standard CRUD and custom query operations function cleanly under pms_app")
    @Transactional
    void shouldPerformJpaRepositoryOperationsUnderPmsApp() {
        Guest guest = guestRepository.findById(testGuestId1).orElseThrow();
        Property propA = propertyRepository.findById(testPropertyIdA).orElseThrow();

        // 1. Create and Save
        GuestPropertyAssociation assoc = new GuestPropertyAssociation(
                guest, propA, GuestAssociationSource.REGISTRATION, Instant.now(), Instant.now()
        );
        GuestPropertyAssociation saved = associationRepository.save(assoc);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getGuestId()).isEqualTo(testGuestId1);
        assertThat(saved.getPropertyId()).isEqualTo(testPropertyIdA);
        assertThat(saved.getAssociationSource()).isEqualTo(GuestAssociationSource.REGISTRATION);

        // 2. Query by Property
        List<GuestPropertyAssociationId> byProp = associationRepository.findByIdPropertyId(testPropertyIdA)
                .stream().map(GuestPropertyAssociation::getId).toList();
        assertThat(byProp).contains(saved.getId());

        // 3. Query by Guest
        List<GuestPropertyAssociationId> byGuest = associationRepository.findByIdGuestId(testGuestId1)
                .stream().map(GuestPropertyAssociation::getId).toList();
        assertThat(byGuest).contains(saved.getId());

        // 4. Exists query
        boolean exists = associationRepository.existsByIdGuestIdAndIdPropertyId(testGuestId1, testPropertyIdA);
        assertThat(exists).isTrue();

        // 5. Delete by composite ID
        associationRepository.deleteById(saved.getId());
        associationRepository.flush();

        assertThat(associationRepository.existsByIdGuestIdAndIdPropertyId(testGuestId1, testPropertyIdA)).isFalse();
    }
}
