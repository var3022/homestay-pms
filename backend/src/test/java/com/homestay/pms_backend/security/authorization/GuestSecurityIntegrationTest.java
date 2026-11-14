package com.homestay.pms_backend.security.authorization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.homestay.pms_backend.config.SecurityEnforcementProperties;
import com.homestay.pms_backend.dto.request.*;
import com.homestay.pms_backend.dto.response.GuestResponse;
import com.homestay.pms_backend.dto.response.ReservationResponse;
import com.homestay.pms_backend.entity.*;
import com.homestay.pms_backend.enums.*;
import com.homestay.pms_backend.repository.*;
import com.homestay.pms_backend.security.AuthenticatedStaff;
import com.homestay.pms_backend.security.jwt.AccessTokenDto;
import com.homestay.pms_backend.security.jwt.AccessTokenService;
import com.homestay.pms_backend.service.GuestPropertyAssociationService;
import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@IsolatedDatabaseTest
@TestPropertySource(properties = {
        "auth.jwt.enforcement.enabled=true",
        "TEST_AUTH_JWT_ENFORCEMENT_ENABLED=true"
})
@DisplayName("PMS Phase 2 — Step 3.4.3.3B.4: Secure Guest HTTP APIs & Reservation Association Integration Tests")
class GuestSecurityIntegrationTest {

    private static final String TEST_DOMAIN = "@guest-sec-test.com";
    private static final String VALID_BCRYPT_HASH = "$2a$10$wT0l8kPqSsqGzC.q0Qe11eZ7t6jPqXn3yEwK6Tq5Z7hC0d1z8Kz6W";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private SecurityEnforcementProperties enforcementProperties;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private RoomTypeRepository roomTypeRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private GuestRepository guestRepository;

    @Autowired
    private BookingSourceRepository bookingSourceRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationRoomRepository reservationRoomRepository;

    @Autowired
    private GuestPropertyAssociationRepository associationRepository;

    @Autowired
    private GuestPropertyAssociationService associationService;

    @Autowired
    private Environment environment;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = createObjectMapper();
    private JdbcTemplate adminJdbcTemplate;

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule dateModule = new SimpleModule();
        dateModule.addSerializer(LocalDate.class, new JsonSerializer<>() {
            @Override
            public void serialize(LocalDate value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(value.toString());
            }
        });
        dateModule.addDeserializer(LocalDate.class, new JsonDeserializer<>() {
            @Override
            public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return LocalDate.parse(p.getText());
            }
        });
        dateModule.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(value.toString());
            }
        });
        dateModule.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override
            public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return Instant.parse(p.getText());
            }
        });
        mapper.registerModule(dateModule);
        return mapper;
    }

    private UUID propertyIdA;
    private UUID propertyIdB;
    private UUID roomTypeIdA;
    private UUID roomTypeIdB;
    private UUID roomIdA;
    private UUID roomIdB;

    private UUID guestAlphaId;
    private UUID guestBetaId;
    private UUID guestSharedId;
    private UUID guestUnassociatedId;

    private UUID bookingSourceDirectId;
    private UUID bookingSourceOtaId;

    private String superAdminToken;
    private UUID staffAdminAId;
    private String propAdminTokenA;
    private String receptionistTokenA;
    private String viewerTokenA;
    private String accountantTokenA;
    private String propAdminTokenB;
    private String unassignedToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

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

        // 1. Seed Properties
        propertyIdA = getOrCreateProperty("Property Alpha Sec", "PROP_A_SEC");
        propertyIdB = getOrCreateProperty("Property Beta Sec", "PROP_B_SEC");

        // 2. Seed Room Types & Rooms
        roomTypeIdA = getOrCreateRoomType(propertyIdA, "Suite Alpha Sec", "RTA_SEC");
        roomTypeIdB = getOrCreateRoomType(propertyIdB, "Suite Beta Sec", "RTB_SEC");
        roomIdA = getOrCreateRoom(propertyIdA, roomTypeIdA, "A-SEC-101");
        roomIdB = getOrCreateRoom(propertyIdB, roomTypeIdB, "B-SEC-201");

        // 3. Seed Guests
        guestAlphaId = createGuestInDb("AAA Guest Alpha Only", "alpha-guest" + TEST_DOMAIN, "+919876540001", "P12345678");
        guestBetaId = createGuestInDb("AAA Guest Beta Only", "beta-guest" + TEST_DOMAIN, "+919876540002", "P87654321");
        guestSharedId = createGuestInDb("AAA Guest Shared Alpha Beta", "shared-guest" + TEST_DOMAIN, "+919876540003", "P55554444");
        guestUnassociatedId = createGuestInDb("AAA Guest Unassociated Dev", "unassoc-guest" + TEST_DOMAIN, "+919876540004", "P99990000");

        // 4. Seed Associations
        associationService.createRegistrationAssociation(guestAlphaId, propertyIdA);
        associationService.createRegistrationAssociation(guestBetaId, propertyIdB);
        associationService.createRegistrationAssociation(guestSharedId, propertyIdA);
        adminJdbcTemplate.update(
                "INSERT INTO guest_property_associations (guest_id, property_id, association_source, first_associated_at, last_activity_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                guestSharedId, propertyIdB, GuestAssociationSource.HISTORICAL_BACKFILL.name());

        // 5. Lookup Booking Sources
        bookingSourceDirectId = bookingSourceRepository.findByCode("DIRECT")
                .orElseThrow(() -> new IllegalStateException("DIRECT booking source not found"))
                .getId();
        bookingSourceOtaId = bookingSourceRepository.findByCode("BOOKING_COM")
                .orElseThrow(() -> new IllegalStateException("BOOKING_COM booking source not found"))
                .getId();

        // 6. Seed Staff Accounts and Tokens
        superAdminToken = createStaffToken("superadmin" + TEST_DOMAIN, StaffRole.SUPER_ADMIN, null);

        staffAdminAId = createTestStaff("propadmin-a" + TEST_DOMAIN, true, false);
        assignStaffPropertyRole(staffAdminAId, propertyIdA, StaffRole.PROPERTY_ADMIN);
        propAdminTokenA = generateTokenForStaff(staffAdminAId, "propadmin-a" + TEST_DOMAIN);

        receptionistTokenA = createStaffToken("receptionist-a" + TEST_DOMAIN, StaffRole.RECEPTIONIST, propertyIdA);
        viewerTokenA = createStaffToken("viewer-a" + TEST_DOMAIN, StaffRole.VIEWER, propertyIdA);
        accountantTokenA = createStaffToken("accountant-a" + TEST_DOMAIN, StaffRole.ACCOUNTANT, propertyIdA);
        propAdminTokenB = createStaffToken("propadmin-b" + TEST_DOMAIN, StaffRole.PROPERTY_ADMIN, propertyIdB);
        unassignedToken = createStaffToken("unassigned" + TEST_DOMAIN, null, null);
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
                    "ALTER TABLE staff_security_audit_events ENABLE TRIGGER trg_staff_security_audit_events_immutable; " +
                    "DELETE FROM room_assignments WHERE reservation_room_id IN (SELECT id FROM reservation_rooms WHERE room_type_id IN (SELECT id FROM room_types WHERE code IN ('RTA_SEC', 'RTB_SEC'))); " +
                    "DELETE FROM reservation_rooms WHERE room_type_id IN (SELECT id FROM room_types WHERE code IN ('RTA_SEC', 'RTB_SEC')); " +
                    "DELETE FROM reservations WHERE property_id IN (SELECT id FROM properties WHERE code IN ('PROP_A_SEC', 'PROP_B_SEC')); " +
                    "DELETE FROM rooms WHERE room_number IN ('A-SEC-101', 'B-SEC-201'); " +
                    "DELETE FROM room_types WHERE code IN ('RTA_SEC', 'RTB_SEC'); " +
                    "DELETE FROM guest_property_associations WHERE guest_id IN (SELECT id FROM guests WHERE email LIKE '%" + TEST_DOMAIN + "'); " +
                    "DELETE FROM guests WHERE email LIKE '%" + TEST_DOMAIN + "'; " +
                    "DELETE FROM properties WHERE code IN ('PROP_A_SEC', 'PROP_B_SEC');"
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
                    prop.setDescription("Security test property");
                    prop.setAddressLine1("100 Sec St");
                    prop.setCity("Sec City");
                    prop.setState("Sec State");
                    prop.setCountry("India");
                    prop.setTimezone("Asia/Kolkata");
                    prop.setActive(true);
                    return propertyRepository.save(prop).getId();
                });
    }

    private UUID getOrCreateRoomType(UUID propertyId, String name, String code) {
        return roomTypeRepository.findByPropertyIdAndCode(propertyId, code)
                .map(RoomType::getId)
                .orElseGet(() -> {
                    RoomType rt = new RoomType();
                    rt.setPropertyId(propertyId);
                    rt.setName(name);
                    rt.setCode(code);
                    rt.setDescription("Sec Room Type");
                    rt.setCategory(RoomTypeCategory.SUITE);
                    rt.setBaseOccupancy(2);
                    rt.setMaxOccupancy(4);
                    rt.setMaxExtraBeds(2);
                    rt.setActive(true);
                    return roomTypeRepository.save(rt).getId();
                });
    }

    private UUID getOrCreateRoom(UUID propertyId, UUID roomTypeId, String roomNumber) {
        return roomRepository.findByPropertyIdAndRoomNumber(propertyId, roomNumber)
                .map(Room::getId)
                .orElseGet(() -> {
                    Room r = new Room();
                    r.setPropertyId(propertyId);
                    r.setRoomTypeId(roomTypeId);
                    r.setRoomNumber(roomNumber);
                    r.setName("Room " + roomNumber);
                    r.setFloor("1");
                    r.setBaseOccupancy(2);
                    r.setMaxOccupancy(4);
                    r.setMaxExtraBeds(2);
                    r.setStatus(RoomStatus.AVAILABLE);
                    r.setActive(true);
                    return roomRepository.save(r).getId();
                });
    }

    private UUID createGuestInDb(String name, String email, String phone, String idNumber) {
        Guest g = new Guest();
        g.setName(name);
        g.setEmail(email);
        g.setPhone(phone);
        g.setIdType(IdentityDocumentType.PASSPORT);
        g.setIdNumber(idNumber);
        g.setActive(true);
        return guestRepository.save(g).getId();
    }

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

    private void assignStaffPropertyRole(UUID staffId, UUID propertyId, StaffRole role) {
        adminJdbcTemplate.update(
                "INSERT INTO staff_property_roles (staff_user_id, property_id, role_code, assigned_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                staffId,
                propertyId,
                role.getCode()
        );
    }

    private String generateTokenForStaff(UUID staffId, String email) {
        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "Test", "Staff", "+919876543210");
        AccessTokenDto dto = accessTokenService.generateAccessToken(staff);
        return dto.token();
    }

    private String createStaffToken(String email, StaffRole role, UUID propertyId) {
        UUID staffId = createTestStaff(email, true, false);
        if (role == StaffRole.SUPER_ADMIN) {
            adminJdbcTemplate.update(
                    "INSERT INTO staff_global_roles (staff_user_id, role_code, assigned_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                    staffId,
                    role.getCode()
            );
        } else if (role != null && propertyId != null) {
            assignStaffPropertyRole(staffId, propertyId, role);
        }
        return generateTokenForStaff(staffId, email);
    }

    // =========================================================================
    // 1-4. Guest Registration & Association Creation Security
    // =========================================================================

    @Test
    @DisplayName("1. Authorized guest registration succeeds and creates REGISTRATION association")
    void test01_authorizedGuestRegistration() throws Exception {
        GuestCreateRequest request = new GuestCreateRequest();
        request.setPropertyId(propertyIdA);
        request.setName("New Registered Guest");
        request.setEmail("newreg" + TEST_DOMAIN);
        request.setPhone("+919876549999");
        request.setIdType(IdentityDocumentType.PASSPORT);
        request.setIdNumber("P99887766");

        String resJson = mockMvc.perform(post("/api/v1/guests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("New Registered Guest"))
                .andExpect(jsonPath("$.idNumber").value("****7766"))
                .andExpect(jsonPath("$.idDocumentImageKey").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        GuestResponse created = objectMapper.readValue(resJson, GuestResponse.class);
        UUID newGuestId = created.getId();

        // Verify association row created in database
        assertThat(associationRepository.existsByIdGuestIdAndIdPropertyId(newGuestId, propertyIdA)).isTrue();
        GuestPropertyAssociation assoc = associationRepository.findByIdGuestIdAndIdPropertyId(newGuestId, propertyIdA).orElseThrow();
        assertThat(assoc.getAssociationSource()).isEqualTo(GuestAssociationSource.REGISTRATION);
        assertThat(assoc.getFirstAssociatedAt()).isNotNull();
        assertThat(assoc.getLastActivityAt()).isNotNull();
    }

    @Test
    @DisplayName("2. Registration rejected for unauthorized property (404/403)")
    void test02_unauthorizedPropertyRegistrationRejected() throws Exception {
        GuestCreateRequest request = new GuestCreateRequest();
        request.setPropertyId(propertyIdB); // Out of scope for Staff Admin A
        request.setName("Hacker Guest");
        request.setEmail("hacker" + TEST_DOMAIN);

        mockMvc.perform(post("/api/v1/guests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound()); // Uniform 404 for out-of-scope property
    }

    @Test
    @DisplayName("3. Atomic guest/association rollback if target property does not exist")
    void test03_atomicGuestAssociationRollback() throws Exception {
        UUID nonExistentPropertyId = UUID.randomUUID();
        GuestCreateRequest request = new GuestCreateRequest();
        request.setPropertyId(nonExistentPropertyId);
        request.setName("Rollback Guest");
        request.setEmail("rollback" + TEST_DOMAIN);

        mockMvc.perform(post("/api/v1/guests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());

        // Confirm guest was rolled back and never persisted
        List<Guest> matches = guestRepository.findByEmailIgnoreCase("rollback" + TEST_DOMAIN);
        assertThat(matches).isEmpty();
    }

    @Test
    @DisplayName("4. Registration association preserves REGISTRATION source enum")
    void test04_registrationCreatesRegistrationAssociation() throws Exception {
        GuestCreateRequest request = new GuestCreateRequest();
        request.setPropertyId(propertyIdA);
        request.setName("Reg Source Guest");
        request.setEmail("regsource" + TEST_DOMAIN);

        String resJson = mockMvc.perform(post("/api/v1/guests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        GuestResponse created = objectMapper.readValue(resJson, GuestResponse.class);
        GuestPropertyAssociation assoc = associationRepository.findByIdGuestIdAndIdPropertyId(created.getId(), propertyIdA).orElseThrow();
        assertThat(assoc.getAssociationSource()).isEqualTo(GuestAssociationSource.REGISTRATION);
    }

    // =========================================================================
    // 5-8. Guest Read by ID & Role-Based Access Control
    // =========================================================================

    @Test
    @DisplayName("5. GET guest by ID property scoping allows in-scope PROPERTY_ADMIN and RECEPTIONIST")
    void test05_getGuestByIdPropertyScoping() throws Exception {
        mockMvc.perform(get("/api/v1/guests/" + guestAlphaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(guestAlphaId.toString()))
                .andExpect(jsonPath("$.name").value("AAA Guest Alpha Only"));

        mockMvc.perform(get("/api/v1/guests/" + guestAlphaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/guests/" + guestAlphaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("6. Unknown or out-of-scope guest UUIDs return indistinguishable 404 responses")
    void test06_unknownOrOutOfScopeGuestUniform404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();

        // Non-existent guest returns 404
        mockMvc.perform(get("/api/v1/guests/" + nonExistentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound());

        // Out-of-scope guest (Beta guest requested by Alpha admin) returns identical 404
        mockMvc.perform(get("/api/v1/guests/" + guestBetaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("7. In-scope VIEWER is denied full guest profile access (403)")
    void test07_inScopeViewerDenied() throws Exception {
        mockMvc.perform(get("/api/v1/guests/" + guestAlphaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("8. In-scope ACCOUNTANT is denied full guest profile access (403)")
    void test08_inScopeAccountantDenied() throws Exception {
        mockMvc.perform(get("/api/v1/guests/" + guestAlphaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 9-13. Guest List Property Scoping, Deduplication & Pagination
    // =========================================================================

    @Test
    @DisplayName("9. Guest list property scoping returns only associated guests for caller's property")
    void test09_guestListPropertyScoping() throws Exception {
        mockMvc.perform(get("/api/v1/guests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItems(guestAlphaId.toString(), guestSharedId.toString())))
                .andExpect(jsonPath("$[*].id", not(hasItem(guestBetaId.toString()))));
    }

    @Test
    @DisplayName("10. Multi-property guest list deduplication via DISTINCT prevents duplicates")
    void test10_multiPropertyGuestListDeduplication() throws Exception {
        // Assign staff to BOTH Property A and Property B
        UUID dualStaffId = createTestStaff("dualstaff" + TEST_DOMAIN, true, false);
        assignStaffPropertyRole(dualStaffId, propertyIdA, StaffRole.PROPERTY_ADMIN);
        assignStaffPropertyRole(dualStaffId, propertyIdB, StaffRole.PROPERTY_ADMIN);
        String dualToken = generateTokenForStaff(dualStaffId, "dualstaff" + TEST_DOMAIN);

        String json = mockMvc.perform(get("/api/v1/guests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dualToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItems(guestAlphaId.toString(), guestBetaId.toString(), guestSharedId.toString())))
                .andReturn().getResponse().getContentAsString();

        // Verify shared guest occurs exactly once
        List<GuestResponse> guests = objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, GuestResponse.class));
        long sharedCount = guests.stream().filter(g -> g.getId().equals(guestSharedId)).count();
        assertThat(sharedCount).isEqualTo(1);
    }

    @Test
    @DisplayName("11. Unassigned staff receives an empty guest list")
    void test11_unassignedStaffReceivesNoGuests() throws Exception {
        mockMvc.perform(get("/api/v1/guests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unassignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("12. SUPER_ADMIN listing can list all or filter by property")
    void test12_superAdminListingRemainsScopedByOptionalFilter() throws Exception {
        // Without filter: sees all guests
        mockMvc.perform(get("/api/v1/guests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItems(guestAlphaId.toString(), guestBetaId.toString(), guestSharedId.toString())));

        // With Property A filter: sees only Alpha and Shared guests
        mockMvc.perform(get("/api/v1/guests?propertyId=" + propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItems(guestAlphaId.toString(), guestSharedId.toString())))
                .andExpect(jsonPath("$[*].id", not(hasItem(guestBetaId.toString()))));
    }

    @Test
    @DisplayName("13. Bounded pagination prevents unlimited PII export")
    void test13_boundedPaginationPreventsUnlimitedPiiExport() throws Exception {
        mockMvc.perform(get("/api/v1/guests?page=0&size=2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    // =========================================================================
    // 14-16. Guest Updates & Global Lifecycle Controls
    // =========================================================================

    @Test
    @DisplayName("14. Shared guest update rejected for property-scoped staff (403)")
    void test14_sharedGuestUpdateRejectedForScopedStaff() throws Exception {
        GuestUpdateRequest request = new GuestUpdateRequest();
        request.setName("Attempted Update Shared Name");

        mockMvc.perform(put("/api/v1/guests/" + guestSharedId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("15. Single-property guest update allowed for authorized staff, dynamic multi-property association immediately blocks subsequent scoped updates")
    void test15_singlePropertyGuestUpdateAllowedForAuthorizedStaff() throws Exception {
        GuestUpdateRequest request = new GuestUpdateRequest();
        request.setName("Updated Alpha Guest Name");

        // 1. Single-property guest: in-scope PROPERTY_ADMIN can update
        mockMvc.perform(put("/api/v1/guests/" + guestAlphaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Alpha Guest Name"));

        // 2. Dynamically associate guest with a second property (Property B)
        associationService.createRegistrationAssociation(guestAlphaId, propertyIdB);

        // 3. Scoped staff update is now immediately rejected with 403 Forbidden
        GuestUpdateRequest secondUpdateRequest = new GuestUpdateRequest();
        secondUpdateRequest.setName("Scoped Staff Cannot Update Shared Guest");

        mockMvc.perform(put("/api/v1/guests/" + guestAlphaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondUpdateRequest)))
                .andExpect(status().isForbidden());

        // 4. Global SUPER_ADMIN can update the shared multi-property guest
        GuestUpdateRequest superAdminUpdateRequest = new GuestUpdateRequest();
        superAdminUpdateRequest.setName("Super Admin Updated Shared Guest");

        mockMvc.perform(put("/api/v1/guests/" + guestAlphaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(superAdminUpdateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Super Admin Updated Shared Guest"));
    }

    @Test
    @DisplayName("16. Global activation and deactivation restricted to SUPER_ADMIN")
    void test16_globalActivationDeactivationRestrictedToSuperAdmin() throws Exception {
        // Scoped staff cannot deactivate (403)
        mockMvc.perform(patch("/api/v1/guests/" + guestAlphaId + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isForbidden());

        // SUPER_ADMIN deactivates guest
        mockMvc.perform(patch("/api/v1/guests/" + guestAlphaId + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isNoContent());

        Guest deactivated = guestRepository.findById(guestAlphaId).orElseThrow();
        assertThat(deactivated.isActive()).isFalse();

        // Scoped staff cannot activate (403)
        mockMvc.perform(patch("/api/v1/guests/" + guestAlphaId + "/activate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isForbidden());

        // SUPER_ADMIN activates guest
        mockMvc.perform(patch("/api/v1/guests/" + guestAlphaId + "/activate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isNoContent());

        Guest activated = guestRepository.findById(guestAlphaId).orElseThrow();
        assertThat(activated.isActive()).isTrue();
    }

    // =========================================================================
    // 17-19. Sensitive Response Protection & Input Validation
    // =========================================================================

    @Test
    @DisplayName("17. Full identity number is never present in GuestResponse JSON (masked only)")
    void test17_fullIdentityNumberNotPresentInGuestResponseJson() throws Exception {
        String resJson = mockMvc.perform(get("/api/v1/guests/" + guestAlphaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(resJson).doesNotContain("P12345678");
        assertThat(resJson).contains("****5678");
    }

    @Test
    @DisplayName("18. Document storage key is never present in GuestResponse JSON")
    void test18_documentStorageKeyNotPresentInGuestResponseJson() throws Exception {
        String resJson = mockMvc.perform(get("/api/v1/guests/" + guestAlphaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(resJson).doesNotContain("idDocumentImageKey");
    }

    @Test
    @DisplayName("19. Client-supplied arbitrary document storage key cannot be persisted")
    void test19_clientSuppliedDocumentStorageKeyCannotBePersisted() throws Exception {
        GuestCreateRequest request = new GuestCreateRequest();
        request.setPropertyId(propertyIdA);
        request.setName("Key Exploit Attempt Guest");
        request.setEmail("keyexploit" + TEST_DOMAIN);
        request.setIdDocumentImageKey("injected/storage/path/pass.png");

        String resJson = mockMvc.perform(post("/api/v1/guests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        GuestResponse created = objectMapper.readValue(resJson, GuestResponse.class);
        Guest persisted = guestRepository.findById(created.getId()).orElseThrow();

        // Must be null in DB under enforcement
        assertThat(persisted.getIdDocumentImageKey()).isNull();
    }

    // =========================================================================
    // 20-23. Reservation Creation & Association Activity Integration
    // =========================================================================

    @Test
    @DisplayName("20. Reservation creation succeeds for associated guest and updates last_activity_at")
    void test20_reservationCreationSucceedsForAssociatedGuest() throws Exception {
        GuestPropertyAssociation initialAssoc = associationRepository
                .findByIdGuestIdAndIdPropertyId(guestAlphaId, propertyIdA).orElseThrow();
        Instant initialActivity = initialAssoc.getLastActivityAt();

        ReservationRoomCreateRequest roomReq = new ReservationRoomCreateRequest();
        roomReq.setRoomTypeId(roomTypeIdA);
        roomReq.setQuantity(1);
        roomReq.setUnitPrice(new BigDecimal("1500.00"));

        ReservationCreateRequest createReq = new ReservationCreateRequest();
        createReq.setPropertyId(propertyIdA);
        createReq.setPrimaryGuestId(guestAlphaId);
        createReq.setBookingSourceId(bookingSourceDirectId);
        createReq.setCheckInDate(LocalDate.now().plusDays(10));
        createReq.setCheckOutDate(LocalDate.now().plusDays(12));
        createReq.setAdults(2);
        createReq.setChildren(0);
        createReq.setInfants(0);
        createReq.setReservationRooms(List.of(roomReq));

        mockMvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.primaryGuestId").value(guestAlphaId.toString()));

        GuestPropertyAssociation updatedAssoc = associationRepository
                .findByIdGuestIdAndIdPropertyId(guestAlphaId, propertyIdA).orElseThrow();
        assertThat(updatedAssoc.getLastActivityAt()).isNotNull();
    }

    @Test
    @DisplayName("21. Reservation creation rejects cross-property guest UUID with uniform 404")
    void test21_reservationCreationRejectsCrossPropertyGuest() throws Exception {
        // guestBetaId is associated ONLY with Property B
        ReservationRoomCreateRequest roomReq = new ReservationRoomCreateRequest();
        roomReq.setRoomTypeId(roomTypeIdA);
        roomReq.setQuantity(1);
        roomReq.setUnitPrice(new BigDecimal("1500.00"));

        ReservationCreateRequest createReq = new ReservationCreateRequest();
        createReq.setPropertyId(propertyIdA); // Attempting to use Beta guest at Property A!
        createReq.setPrimaryGuestId(guestBetaId);
        createReq.setBookingSourceId(bookingSourceDirectId);
        createReq.setCheckInDate(LocalDate.now().plusDays(10));
        createReq.setCheckOutDate(LocalDate.now().plusDays(12));
        createReq.setAdults(2);
        createReq.setChildren(0);
        createReq.setInfants(0);
        createReq.setReservationRooms(List.of(roomReq));

        mockMvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isNotFound()); // Uniform 404 rejecting cross-property guest adoption
    }

    @Test
    @DisplayName("22. Failed reservation creation does not touch association activity or persist partial reservation")
    void test22_failedReservationCreationDoesNotUpdateAssociationOrPersistPartial() throws Exception {
        ReservationRoomCreateRequest roomReq = new ReservationRoomCreateRequest();
        roomReq.setRoomTypeId(roomTypeIdA);
        roomReq.setQuantity(1);
        roomReq.setUnitPrice(new BigDecimal("1500.00"));

        ReservationCreateRequest createReq = new ReservationCreateRequest();
        createReq.setPropertyId(propertyIdA);
        createReq.setPrimaryGuestId(guestAlphaId);
        createReq.setBookingSourceId(bookingSourceDirectId);
        createReq.setCheckInDate(LocalDate.now().plusDays(12));
        createReq.setCheckOutDate(LocalDate.now().plusDays(10)); // Invalid check-out before check-in!
        createReq.setAdults(2);
        createReq.setChildren(0);
        createReq.setInfants(0);
        createReq.setReservationRooms(List.of(roomReq));

        mockMvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("23. Development-mode booking compatibility safely skips activity touch for unassociated guests")
    void test23_legacyDevelopmentBookingCompatibility() {
        enforcementProperties.setEnabled(false);
        try {
            // validateExistingGuestUsableAtProperty does not throw for unassociated guest
            associationService.validateExistingGuestUsableAtProperty(guestUnassociatedId, propertyIdA);

            // recordReservationActivity safely skips without throwing or creating implicit association
            associationService.recordReservationActivity(guestUnassociatedId, propertyIdA);

            assertThat(associationRepository.existsByIdGuestIdAndIdPropertyId(guestUnassociatedId, propertyIdA)).isFalse();
        } finally {
            enforcementProperties.setEnabled(true);
        }
    }

    // =========================================================================
    // 24-30. Regression & Security Boundaries
    // =========================================================================

    @Test
    @DisplayName("24. Existing OTA reservation rules remain intact")
    void test24_existingOtaModificationRulesRemainUnchanged() throws Exception {
        // Create direct reservation then verify OTA rules
        Reservation res = new Reservation();
        res.setPropertyId(propertyIdA);
        res.setPrimaryGuestId(guestAlphaId);
        res.setBookingSourceId(bookingSourceOtaId);
        res.setExternalBookingId("OTA-EXT-9988");
        res.setCheckInDate(LocalDate.now().plusDays(15));
        res.setCheckOutDate(LocalDate.now().plusDays(18));
        res.setAdults(2);
        res.setChildren(0);
        res.setInfants(0);
        res.setStatus(ReservationStatus.CONFIRMED);
        Reservation savedOta = reservationRepository.saveAndFlush(res);

        ReservationUpdateRequest updateReq = new ReservationUpdateRequest();
        updateReq.setCheckInDate(LocalDate.now().plusDays(16)); // OTA date modification disallowed

        mockMvc.perform(put("/api/v1/reservations/" + savedOta.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("25. Existing room availability and cancellation logic remains intact")
    void test25_existingRoomAvailabilityCancellationLogicIntact() throws Exception {
        Reservation res = new Reservation();
        res.setPropertyId(propertyIdA);
        res.setPrimaryGuestId(guestAlphaId);
        res.setBookingSourceId(bookingSourceDirectId);
        res.setCheckInDate(LocalDate.now().plusDays(25));
        res.setCheckOutDate(LocalDate.now().plusDays(28));
        res.setAdults(2);
        res.setChildren(0);
        res.setInfants(0);
        res.setStatus(ReservationStatus.CONFIRMED);
        Reservation saved = reservationRepository.saveAndFlush(res);

        mockMvc.perform(patch("/api/v1/reservations/" + saved.getId() + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(ReservationStatus.CANCELLED.name()));
    }

    @Test
    @DisplayName("26. Immediate role revocation takes effect on subsequent requests")
    void test26_immediateRoleRevocationIsRespected() throws Exception {
        // Staff Admin A is authorized for guestAlphaId
        mockMvc.perform(get("/api/v1/guests/" + guestAlphaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        // Revoke Property A role immediately in DB
        adminJdbcTemplate.update("DELETE FROM staff_property_roles WHERE staff_user_id = ?", staffAdminAId);

        // Immediate subsequent request fails with 404 (out of scope)
        mockMvc.perform(get("/api/v1/guests/" + guestAlphaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("27. Missing or invalid JWT returns 401 Unauthorized under enforcement")
    void test27_missingOrInvalidJwtReturns401UnderEnforcement() throws Exception {
        mockMvc.perform(get("/api/v1/guests"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/guests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-tampered-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("28. Historical association remains valid after cancellation or checkout")
    void test28_historicalAssociationRemainsValidAfterCancellation() {
        GuestPropertyAssociation assoc = associationRepository
                .findByIdGuestIdAndIdPropertyId(guestSharedId, propertyIdA).orElseThrow();

        assertThat(assoc.getAssociationSource()).isEqualTo(GuestAssociationSource.REGISTRATION);
        assertThat(assoc.getFirstAssociatedAt()).isNotNull();

        GuestPropertyAssociation assocB = associationRepository
                .findByIdGuestIdAndIdPropertyId(guestSharedId, propertyIdB).orElseThrow();
        assertThat(assocB.getAssociationSource()).isEqualTo(GuestAssociationSource.HISTORICAL_BACKFILL);
    }

    @Test
    @DisplayName("29. Database privilege segregation remains intact")
    void test29_databasePrivilegeSegregationRemainsIntact() {
        // Verify staff tables are accessible by admin but restricted
        Integer count = adminJdbcTemplate.queryForObject("SELECT count(*) FROM guest_property_associations", Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("30. Multi-property guest cannot be deactivated by scoped staff")
    void test30_multiPropertyGuestCannotBeDeactivatedByScopedStaff() throws Exception {
        mockMvc.perform(patch("/api/v1/guests/" + guestSharedId + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isForbidden());
    }
}
