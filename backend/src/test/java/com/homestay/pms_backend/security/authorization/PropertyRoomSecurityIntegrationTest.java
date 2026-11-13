package com.homestay.pms_backend.security.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homestay.pms_backend.config.SecurityEnforcementProperties;
import com.homestay.pms_backend.dto.request.*;
import com.homestay.pms_backend.entity.Property;
import com.homestay.pms_backend.entity.Room;
import com.homestay.pms_backend.entity.RoomType;
import com.homestay.pms_backend.enums.RoomStatus;
import com.homestay.pms_backend.enums.RoomTypeCategory;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.repository.RoomRepository;
import com.homestay.pms_backend.repository.RoomTypeRepository;
import com.homestay.pms_backend.security.AuthenticatedStaff;
import com.homestay.pms_backend.security.jwt.AccessTokenDto;
import com.homestay.pms_backend.security.jwt.AccessTokenService;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@IsolatedDatabaseTest
@TestPropertySource(properties = {
        "auth.jwt.enforcement.enabled=true",
        "TEST_AUTH_JWT_ENFORCEMENT_ENABLED=true"
})
@DisplayName("PMS Phase 2 — Step 3.4.3.2A: Property, Room, and Room-Type Authorization Integration Tests")
class PropertyRoomSecurityIntegrationTest {

    private static final String TEST_DOMAIN = "@test-prop-authz.com";
    private static final String VALID_BCRYPT_HASH = "$2a$12$e80yq7KjVpEw3W6xK9qXxeQjJcM1q4RkQ9aVbY2vK8n1o2p3q4r5s";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private StaffAuthorizationService authorizationService;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private RoomTypeRepository roomTypeRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private Environment environment;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private MockMvc mockMvc;
    private JdbcTemplate adminJdbcTemplate;

    private UUID propertyIdA;
    private UUID propertyIdB;
    private UUID roomTypeIdA;
    private UUID roomTypeIdB;
    private UUID roomIdA;
    private UUID roomIdB;

    private String superAdminToken;
    private String propAdminTokenA;
    private String receptionistTokenA;
    private String viewerTokenA;
    private String unassignedStaffToken;

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
        this.adminJdbcTemplate = new JdbcTemplate(adminDs);

        cleanupTestData();

        // 1. Seed Property A and Property B
        propertyIdA = getOrCreateProperty("Property Alpha", "PROP_ALPHA_AUTHZ");
        propertyIdB = getOrCreateProperty("Property Beta", "PROP_BETA_AUTHZ");

        // 2. Seed Room Types
        roomTypeIdA = getOrCreateRoomType(propertyIdA, "Deluxe Alpha", "RTA_DLX");
        roomTypeIdB = getOrCreateRoomType(propertyIdB, "Standard Beta", "RTB_STD");

        // 3. Seed Rooms
        roomIdA = getOrCreateRoom(propertyIdA, roomTypeIdA, "A-101");
        roomIdB = getOrCreateRoom(propertyIdB, roomTypeIdB, "B-201");

        // 4. Create Staff accounts and tokens
        superAdminToken = createStaffToken("superadmin" + TEST_DOMAIN, StaffRole.SUPER_ADMIN, null);
        propAdminTokenA = createStaffToken("propadmin-a" + TEST_DOMAIN, StaffRole.PROPERTY_ADMIN, propertyIdA);
        receptionistTokenA = createStaffToken("receptionist-a" + TEST_DOMAIN, StaffRole.RECEPTIONIST, propertyIdA);
        viewerTokenA = createStaffToken("viewer-a" + TEST_DOMAIN, StaffRole.VIEWER, propertyIdA);
        unassignedStaffToken = createStaffToken("unassigned" + TEST_DOMAIN, null, null);
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
                    "DELETE FROM rooms WHERE room_number NOT IN ('A-101', 'B-201'); " +
                    "DELETE FROM room_types WHERE code NOT IN ('RTA_DLX', 'RTB_STD'); " +
                    "UPDATE rooms SET status = 'AVAILABLE', is_active = true WHERE room_number IN ('A-101', 'B-201'); " +
                    "UPDATE properties SET name = 'Property Alpha', is_active = true WHERE code = 'PROP_ALPHA_AUTHZ'; " +
                    "UPDATE properties SET name = 'Property Beta', is_active = true WHERE code = 'PROP_BETA_AUTHZ';"
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

    private UUID getOrCreateRoomType(UUID propertyId, String name, String code) {
        return roomTypeRepository.findByPropertyIdAndCode(propertyId, code)
                .map(RoomType::getId)
                .orElseGet(() -> {
                    RoomType rt = new RoomType();
                    rt.setPropertyId(propertyId);
                    rt.setName(name);
                    rt.setCode(code);
                    rt.setDescription("Test Room Type");
                    rt.setCategory(RoomTypeCategory.SUITE);
                    rt.setBaseOccupancy(2);
                    rt.setMaxOccupancy(4);
                    rt.setMaxExtraBeds(1);
                    rt.setActive(true);
                    return roomTypeRepository.save(rt).getId();
                });
    }

    private UUID getOrCreateRoom(UUID propertyId, UUID roomTypeId, String roomNumber) {
        return roomRepository.findByPropertyIdAndRoomNumber(propertyId, roomNumber)
                .map(r -> {
                    r.setStatus(RoomStatus.AVAILABLE);
                    r.setActive(true);
                    return roomRepository.save(r).getId();
                })
                .orElseGet(() -> {
                    Room r = new Room();
                    r.setPropertyId(propertyId);
                    r.setRoomTypeId(roomTypeId);
                    r.setRoomNumber(roomNumber);
                    r.setName("Room " + roomNumber);
                    r.setFloor("1");
                    r.setBaseOccupancy(2);
                    r.setMaxOccupancy(4);
                    r.setMaxExtraBeds(1);
                    r.setBathroomCount(1);
                    r.setStatus(RoomStatus.AVAILABLE);
                    r.setActive(true);
                    return roomRepository.save(r).getId();
                });
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

    private String createStaffToken(String email, StaffRole role, UUID propertyId) {
        UUID staffId = createTestStaff(email, true, false);
        if (role == StaffRole.SUPER_ADMIN) {
            adminJdbcTemplate.update(
                    "INSERT INTO staff_global_roles (staff_user_id, role_code, assigned_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                    staffId,
                    role.getCode()
            );
        } else if (role != null && propertyId != null) {
            adminJdbcTemplate.update(
                    "INSERT INTO staff_property_roles (staff_user_id, property_id, role_code, assigned_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                    staffId,
                    propertyId,
                    role.getCode()
            );
        }
        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "Test", "Staff", "+919876543210");
        AccessTokenDto dto = accessTokenService.generateAccessToken(staff);
        return dto.token();
    }

    // =========================================================================
    // SECTION 1: SUPER_ADMIN Global Operations
    // =========================================================================

    @Test
    @DisplayName("SUPER_ADMIN can create property (POST /api/v1/properties)")
    void superAdmin_canCreateProperty() throws Exception {
        PropertyCreateRequest req = new PropertyCreateRequest();
        req.setName("Global Super Homestay");
        req.setCode("GBL_PROP_" + UUID.randomUUID().toString().substring(0, 8));
        req.setAddressLine1("100 Super Blvd");
        req.setCity("Metropolis");
        req.setState("NY");
        req.setCountry("USA");
        req.setTimezone("America/New_York");

        mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Global Super Homestay"));
    }

    @Test
    @DisplayName("SUPER_ADMIN can view any property and list all properties")
    void superAdmin_canViewAnyPropertyAndListAll() throws Exception {
        // Can view Property A
        mockMvc.perform(get("/api/v1/properties/{id}", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(propertyIdA.toString()));

        // Can view Property B
        mockMvc.perform(get("/api/v1/properties/{id}", propertyIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(propertyIdB.toString()));

        // Can list all properties (both returned)
        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItems(propertyIdA.toString(), propertyIdB.toString())));
    }

    @Test
    @DisplayName("SUPER_ADMIN can update, activate, and deactivate any property")
    void superAdmin_canUpdateActivateDeactivateProperty() throws Exception {
        PropertyUpdateRequest updateReq = new PropertyUpdateRequest();
        updateReq.setName("Updated Alpha Name");

        mockMvc.perform(put("/api/v1/properties/{id}", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Alpha Name"));

        // Deactivate Property A
        mockMvc.perform(patch("/api/v1/properties/{id}/deactivate", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isNoContent());

        // Activate Property A
        mockMvc.perform(patch("/api/v1/properties/{id}/activate", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("SUPER_ADMIN can create room type, room, and update room status in any property")
    void superAdmin_canManageRoomsAndRoomTypesGlobally() throws Exception {
        // Create Room Type in Property B
        RoomTypeCreateRequest rtReq = new RoomTypeCreateRequest();
        rtReq.setPropertyId(propertyIdB);
        rtReq.setName("Super Admin Suite");
        rtReq.setCode("SAS_" + UUID.randomUUID().toString().substring(0, 6));
        rtReq.setCategory(RoomTypeCategory.SUITE);
        rtReq.setBaseOccupancy(2);
        rtReq.setMaxOccupancy(4);
        rtReq.setMaxExtraBeds(1);

        String rtResponseStr = mockMvc.perform(post("/api/v1/room-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rtReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID createdRtId = UUID.fromString(objectMapper.readTree(rtResponseStr).get("id").asText());

        // Create Room in Property B
        RoomCreateRequest rReq = new RoomCreateRequest();
        rReq.setPropertyId(propertyIdB);
        rReq.setRoomTypeId(createdRtId);
        rReq.setRoomNumber("B-SUP-" + UUID.randomUUID().toString().substring(0, 6));
        rReq.setBaseOccupancy(2);
        rReq.setMaxOccupancy(4);
        rReq.setMaxExtraBeds(1);
        rReq.setBathroomCount(1);

        String roomResponseStr = mockMvc.perform(post("/api/v1/rooms")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID createdRoomId = UUID.fromString(objectMapper.readTree(roomResponseStr).get("id").asText());

        // Update Room Status in Property B
        RoomStatusUpdateRequest statusReq = new RoomStatusUpdateRequest();
        statusReq.setStatus(RoomStatus.OUT_OF_SERVICE);

        mockMvc.perform(patch("/api/v1/rooms/{id}/status", createdRoomId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statusReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"));
    }

    @Test
    @DisplayName("SUPER_ADMIN can list rooms and room types across all properties and filter combinations")
    void superAdmin_canListRoomsAndRoomTypesAcrossProperties() throws Exception {
        // Rooms by Property A
        mockMvc.perform(get("/api/v1/rooms")
                        .param("propertyId", propertyIdA.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(roomIdA.toString())));

        // Rooms by Property B
        mockMvc.perform(get("/api/v1/rooms")
                        .param("propertyId", propertyIdB.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(roomIdB.toString())));

        // Rooms by RoomType A
        mockMvc.perform(get("/api/v1/rooms")
                        .param("roomTypeId", roomTypeIdA.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(roomIdA.toString())));

        // Rooms by RoomType B
        mockMvc.perform(get("/api/v1/rooms")
                        .param("roomTypeId", roomTypeIdB.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(roomIdB.toString())));

        // Rooms by Property A + RoomType A
        mockMvc.perform(get("/api/v1/rooms")
                        .param("propertyId", propertyIdA.toString())
                        .param("roomTypeId", roomTypeIdA.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(roomIdA.toString())));

        // Room types by Property A
        mockMvc.perform(get("/api/v1/room-types")
                        .param("propertyId", propertyIdA.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(roomTypeIdA.toString())));

        // Room types by Property B
        mockMvc.perform(get("/api/v1/room-types")
                        .param("propertyId", propertyIdB.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(roomTypeIdB.toString())));
    }

    @Test
    @DisplayName("List routes enforce parameter contracts, scoping, and rejection of unauthorized combinations")
    void listRoutes_missingParameterContractsAndScoping() throws Exception {
        // GET /api/v1/rooms without parameters -> 400 Bad Request
        mockMvc.perform(get("/api/v1/rooms")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Either propertyId or roomTypeId must be provided"));

        // GET /api/v1/room-types without parameters -> 400 Bad Request (missing required propertyId)
        mockMvc.perform(get("/api/v1/room-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest());

        // GET /api/v1/rooms with out-of-scope propertyId AND matching roomTypeIdB -> 404 Not Found (property out of scope)
        mockMvc.perform(get("/api/v1/rooms")
                        .param("propertyId", propertyIdB.toString())
                        .param("roomTypeId", roomTypeIdB.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Property not found with id: " + propertyIdB));

        // GET /api/v1/properties for Property Admin A -> returns only Property A
        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(propertyIdA.toString()));

        // GET /api/v1/properties for Receptionist A -> returns only Property A
        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(propertyIdA.toString()));

        // GET /api/v1/properties for Viewer A -> returns only Property A
        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(propertyIdA.toString()));
    }

    // =========================================================================
    // SECTION 2: PROPERTY_ADMIN Scoped to Property A
    // =========================================================================

    @Test
    @DisplayName("PROPERTY_ADMIN can view and update Property A, but CANNOT create properties or activate/deactivate (403)")
    void propAdminA_propertyRestrictions() throws Exception {
        // Can view Property A
        mockMvc.perform(get("/api/v1/properties/{id}", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        // Can update Property A
        PropertyUpdateRequest updateReq = new PropertyUpdateRequest();
        updateReq.setName("Alpha Updated By PropAdmin");
        mockMvc.perform(put("/api/v1/properties/{id}", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk());

        // CANNOT create property -> 403 Forbidden
        PropertyCreateRequest createReq = new PropertyCreateRequest();
        createReq.setName("Unauthorized Homestay");
        createReq.setCode("UNAUTH_PROP");
        createReq.setAddressLine1("456 Rogue Rd");
        createReq.setCity("Roguetown");
        createReq.setState("NY");
        createReq.setCountry("USA");

        mockMvc.perform(post("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.message").value("Access is denied."));

        // CANNOT deactivate Property A -> 403 Forbidden (requireGlobalSuperAdmin checked first)
        mockMvc.perform(patch("/api/v1/properties/{id}/deactivate", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access is denied."));

        // CANNOT activate Property A -> 403 Forbidden (requireGlobalSuperAdmin checked first)
        mockMvc.perform(patch("/api/v1/properties/{id}/activate", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access is denied."));

        // CANNOT deactivate Property B -> 403 Forbidden (denial does NOT disclose whether property exists)
        mockMvc.perform(patch("/api/v1/properties/{id}/deactivate", propertyIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access is denied."));
    }

    @Test
    @DisplayName("PROPERTY_ADMIN on Property A receives 404 for Property B resources (preventing enumeration)")
    void propAdminA_crossPropertyAccessReturns404() throws Exception {
        // Cannot view Property B -> 404 Not Found (indistinguishable from nonexistent)
        mockMvc.perform(get("/api/v1/properties/{id}", propertyIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Property not found with id: " + propertyIdB));

        // Cannot update Property B -> 404 Not Found
        PropertyUpdateRequest updateReq = new PropertyUpdateRequest();
        updateReq.setName("Hacked Beta");
        mockMvc.perform(put("/api/v1/properties/{id}", propertyIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Property not found with id: " + propertyIdB));

        // Cannot list rooms in Property B -> 404 Not Found
        mockMvc.perform(get("/api/v1/rooms")
                        .param("propertyId", propertyIdB.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Property not found with id: " + propertyIdB));

        // Cannot view room B-201 -> 404 Not Found (indistinguishable from nonexistent)
        mockMvc.perform(get("/api/v1/rooms/{id}", roomIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Room not found with id: " + roomIdB));

        // Cannot update room status in Property B -> 404 Not Found
        RoomStatusUpdateRequest statusReq = new RoomStatusUpdateRequest();
        statusReq.setStatus(RoomStatus.OCCUPIED);
        mockMvc.perform(patch("/api/v1/rooms/{id}/status", roomIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statusReq)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Room not found with id: " + roomIdB));

        // Cannot create room in Property B -> 404 Not Found
        RoomCreateRequest rReq = new RoomCreateRequest();
        rReq.setPropertyId(propertyIdB);
        rReq.setRoomTypeId(roomTypeIdB);
        rReq.setRoomNumber("B-ROGUE-999");
        rReq.setBaseOccupancy(2);
        rReq.setMaxOccupancy(4);
        rReq.setMaxExtraBeds(1);
        rReq.setBathroomCount(1);

        mockMvc.perform(post("/api/v1/rooms")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rReq)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Property not found with id: " + propertyIdB));

        // Cannot view room type B -> 404 Not Found
        mockMvc.perform(get("/api/v1/room-types/{id}", roomTypeIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Room type not found with id: " + roomTypeIdB));

        // Cannot query rooms by roomTypeIdB -> 404 Not Found (prevents nested existence oracle)
        mockMvc.perform(get("/api/v1/rooms")
                        .param("roomTypeId", roomTypeIdB.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Room type not found with id: " + roomTypeIdB));

        // Cannot list room types in Property B -> 404 Not Found
        mockMvc.perform(get("/api/v1/room-types")
                        .param("propertyId", propertyIdB.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Property not found with id: " + propertyIdB));

        // Cannot create room type in Property B -> 404 Not Found
        RoomTypeCreateRequest rtReq = new RoomTypeCreateRequest();
        rtReq.setPropertyId(propertyIdB);
        rtReq.setName("Rogue RT");
        rtReq.setCode("RRT_ROGUE");
        rtReq.setCategory(RoomTypeCategory.SUITE);
        rtReq.setBaseOccupancy(2);
        rtReq.setMaxOccupancy(4);
        rtReq.setMaxExtraBeds(1);

        mockMvc.perform(post("/api/v1/room-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rtReq)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Property not found with id: " + propertyIdB));
    }

    @Test
    @DisplayName("PROPERTY_ADMIN can create room and room type in Property A and update room status")
    void propAdminA_canManageRoomsInPropertyA() throws Exception {
        // Create Room Type in Property A
        RoomTypeCreateRequest rtReq = new RoomTypeCreateRequest();
        rtReq.setPropertyId(propertyIdA);
        rtReq.setName("Alpha Executive Suite");
        rtReq.setCode("AES_" + UUID.randomUUID().toString().substring(0, 6));
        rtReq.setCategory(RoomTypeCategory.SUITE);
        rtReq.setBaseOccupancy(2);
        rtReq.setMaxOccupancy(4);
        rtReq.setMaxExtraBeds(1);

        String rtResponseStr = mockMvc.perform(post("/api/v1/room-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rtReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID createdRtId = UUID.fromString(objectMapper.readTree(rtResponseStr).get("id").asText());

        // Create Room in Property A
        RoomCreateRequest rReq = new RoomCreateRequest();
        rReq.setPropertyId(propertyIdA);
        rReq.setRoomTypeId(createdRtId);
        rReq.setRoomNumber("A-ADM-" + UUID.randomUUID().toString().substring(0, 6));
        rReq.setBaseOccupancy(2);
        rReq.setMaxOccupancy(4);
        rReq.setMaxExtraBeds(1);
        rReq.setBathroomCount(1);

        String roomResponseStr = mockMvc.perform(post("/api/v1/rooms")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID createdRoomId = UUID.fromString(objectMapper.readTree(roomResponseStr).get("id").asText());

        // Update Room Status in Property A
        RoomStatusUpdateRequest statusReq = new RoomStatusUpdateRequest();
        statusReq.setStatus(RoomStatus.OUT_OF_SERVICE);

        mockMvc.perform(patch("/api/v1/rooms/{id}/status", createdRoomId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statusReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"));
    }

    @Test
    @DisplayName("Cross-property room creation and query parameter mismatches are rejected with HTTP 400")
    void propAdminA_crossPropertyBindingMismatchesRejected() throws Exception {
        // Create Room with propertyId = Property A, but roomTypeId = RoomType B (belongs to Property B)
        RoomCreateRequest crossRoomReq = new RoomCreateRequest();
        crossRoomReq.setPropertyId(propertyIdA);
        crossRoomReq.setRoomTypeId(roomTypeIdB);
        crossRoomReq.setRoomNumber("CROSS-FAIL-100");
        crossRoomReq.setBaseOccupancy(2);
        crossRoomReq.setMaxOccupancy(4);
        crossRoomReq.setMaxExtraBeds(1);
        crossRoomReq.setBathroomCount(1);

        mockMvc.perform(post("/api/v1/rooms")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crossRoomReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Room type does not belong to the specified property"));

        // Query rooms with propertyId = Property A and roomTypeId = RoomType B
        mockMvc.perform(get("/api/v1/rooms")
                        .param("propertyId", propertyIdA.toString())
                        .param("roomTypeId", roomTypeIdB.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Room type does not belong to the specified property"));
    }

    // =========================================================================
    // SECTION 3: RECEPTIONIST on Property A
    // =========================================================================

    @Test
    @DisplayName("RECEPTIONIST can view Property A, its rooms, and update room status")
    void receptionistA_permittedOperations() throws Exception {
        // Can view Property A
        mockMvc.perform(get("/api/v1/properties/{id}", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk());

        // Can view Room A-101
        mockMvc.perform(get("/api/v1/rooms/{id}", roomIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk());

        // Can view Room Type A
        mockMvc.perform(get("/api/v1/room-types/{id}", roomTypeIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk());

        // Can update room status in Property A
        RoomStatusUpdateRequest statusReq = new RoomStatusUpdateRequest();
        statusReq.setStatus(RoomStatus.OCCUPIED);

        mockMvc.perform(patch("/api/v1/rooms/{id}/status", roomIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statusReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OCCUPIED"));
    }

    @Test
    @DisplayName("RECEPTIONIST receives 403 on Property A mutations, and 404 for Property B resources")
    void receptionistA_deniedOperations() throws Exception {
        // CANNOT update Property A -> 403 Forbidden (in scope, but lacks PROPERTY_MANAGE)
        PropertyUpdateRequest updateReq = new PropertyUpdateRequest();
        updateReq.setName("Receptionist Property Update");
        mockMvc.perform(put("/api/v1/properties/{id}", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access is denied."));

        // CANNOT create room in Property A -> 403 Forbidden (in scope, but lacks ROOM_MANAGE)
        RoomCreateRequest rReq = new RoomCreateRequest();
        rReq.setPropertyId(propertyIdA);
        rReq.setRoomTypeId(roomTypeIdA);
        rReq.setRoomNumber("A-RECEP-FAIL");
        rReq.setBaseOccupancy(2);
        rReq.setMaxOccupancy(4);
        rReq.setMaxExtraBeds(1);
        rReq.setBathroomCount(1);

        mockMvc.perform(post("/api/v1/rooms")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access is denied."));

        // CANNOT create room type in Property A -> 403 Forbidden (in scope, but lacks ROOM_MANAGE)
        RoomTypeCreateRequest rtReq = new RoomTypeCreateRequest();
        rtReq.setPropertyId(propertyIdA);
        rtReq.setName("Recep Room Type");
        rtReq.setCode("RRT_FAIL");
        rtReq.setCategory(RoomTypeCategory.PRIVATE_ROOM);
        rtReq.setBaseOccupancy(2);
        rtReq.setMaxOccupancy(4);
        rtReq.setMaxExtraBeds(0);

        mockMvc.perform(post("/api/v1/room-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rtReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access is denied."));

        // CANNOT update room in Property A -> 403 Forbidden (in scope, but lacks ROOM_MANAGE)
        RoomUpdateRequest roomUpdateReq = new RoomUpdateRequest();
        roomUpdateReq.setName("Recep Room Name Update");
        mockMvc.perform(put("/api/v1/rooms/{id}", roomIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(roomUpdateReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access is denied."));

        // Access to Property B -> 404 Not Found (out of scope, does NOT disclose existence)
        mockMvc.perform(get("/api/v1/properties/{id}", propertyIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Property not found with id: " + propertyIdB));

        mockMvc.perform(get("/api/v1/rooms/{id}", roomIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Room not found with id: " + roomIdB));
    }

    // =========================================================================
    // SECTION 4: VIEWER on Property A
    // =========================================================================

    @Test
    @DisplayName("VIEWER can view Property A, but receives 403 on Property A mutations and 404 for Property B")
    void viewerA_permissionsAndDenials() throws Exception {
        // Can view Property A
        mockMvc.perform(get("/api/v1/properties/{id}", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isOk());

        // Can view rooms in Property A
        mockMvc.perform(get("/api/v1/rooms/{id}", roomIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isOk());

        // CANNOT update room status -> 403 Forbidden (in scope, but lacks ROOM_STATUS_UPDATE)
        RoomStatusUpdateRequest statusReq = new RoomStatusUpdateRequest();
        statusReq.setStatus(RoomStatus.OUT_OF_SERVICE);
        mockMvc.perform(patch("/api/v1/rooms/{id}/status", roomIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statusReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access is denied."));

        // CANNOT mutate property -> 403 Forbidden (in scope, but lacks PROPERTY_MANAGE)
        PropertyUpdateRequest updateReq = new PropertyUpdateRequest();
        updateReq.setName("Viewer Hacked");
        mockMvc.perform(put("/api/v1/properties/{id}", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access is denied."));

        // CANNOT access Property B -> 404 Not Found (out of scope, does NOT disclose existence)
        mockMvc.perform(get("/api/v1/properties/{id}", propertyIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Property not found with id: " + propertyIdB));
    }

    // =========================================================================
    // SECTION 5: Unassigned Staff
    // =========================================================================

    @Test
    @DisplayName("Unassigned staff receives empty list on GET /api/v1/properties and 404 on specific properties")
    void unassignedStaff_restrictions() throws Exception {
        // Listing properties returns 200 OK with empty array []
        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unassignedStaffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // Access to Property A -> 404 Not Found (out of scope, indistinguishable from nonexistent)
        mockMvc.perform(get("/api/v1/properties/{id}", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unassignedStaffToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Property not found with id: " + propertyIdA));

        // Access to Property B -> 404 Not Found (out of scope, indistinguishable from nonexistent)
        mockMvc.perform(get("/api/v1/properties/{id}", propertyIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unassignedStaffToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Property not found with id: " + propertyIdB));
    }

    // =========================================================================
    // SECTION 6: Non-Existent Resources Return 404 (Indistinguishable from Out-of-Scope)
    // =========================================================================

    @Test
    @DisplayName("Non-existent resources return HTTP 404 identical to out-of-scope resources")
    void nonExistentResources_return404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();

        // Non-existent property -> 404
        mockMvc.perform(get("/api/v1/properties/{id}", nonExistentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Property not found with id: " + nonExistentId));

        // Non-existent room -> 404
        mockMvc.perform(get("/api/v1/rooms/{id}", nonExistentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Room not found with id: " + nonExistentId));

        // Non-existent room type -> 404
        mockMvc.perform(get("/api/v1/room-types/{id}", nonExistentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Room type not found with id: " + nonExistentId));

        // Non-existent room type query -> 404
        mockMvc.perform(get("/api/v1/rooms")
                        .param("roomTypeId", nonExistentId.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Room type not found with id: " + nonExistentId));

        // Non-super-admin deactivating non-existent property -> 403 (checked FIRST, no existence leakage)
        mockMvc.perform(patch("/api/v1/properties/{id}/deactivate", nonExistentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access is denied."));

        // SUPER_ADMIN deactivating non-existent property -> 404
        mockMvc.perform(patch("/api/v1/properties/{id}/deactivate", nonExistentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Property not found with id: " + nonExistentId));
    }

    // =========================================================================
    // SECTION 7: Business Integrity Validation in Development Mode
    // =========================================================================

    @Test
    @DisplayName("Business validation (cross-property mismatch) is enforced even when enforcement is disabled")
    void developmentMode_preservesBusinessValidation() {
        SecurityEnforcementProperties disabledProps = new SecurityEnforcementProperties();
        disabledProps.setEnabled(false);

        PropertyAuthorizationGuard devGuard = new PropertyAuthorizationGuard(
                authorizationService,
                disabledProps,
                propertyRepository,
                roomRepository,
                roomTypeRepository
        );

        // Cross-property room creation must throw 400 BusinessValidationException even in dev mode
        assertThatThrownBy(() -> devGuard.validateRoomCreation(propertyIdA, roomTypeIdB))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessage("Room type does not belong to the specified property");

        // Cross-property room query must throw 400 BusinessValidationException even in dev mode
        assertThatThrownBy(() -> devGuard.validateRoomQuery(propertyIdA, roomTypeIdB))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessage("Room type does not belong to the specified property");

        // Valid room creation passes cleanly without authorization requirements in dev mode
        devGuard.validateRoomCreation(propertyIdA, roomTypeIdA);
        devGuard.validateRoomQuery(propertyIdA, roomTypeIdA);
    }
}
