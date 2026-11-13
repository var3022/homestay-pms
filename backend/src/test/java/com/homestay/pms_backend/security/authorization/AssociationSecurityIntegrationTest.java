package com.homestay.pms_backend.security.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homestay.pms_backend.config.SecurityEnforcementProperties;
import com.homestay.pms_backend.dto.request.PropertyAmenityCreateRequest;
import com.homestay.pms_backend.dto.request.RoomAmenityCreateRequest;
import com.homestay.pms_backend.dto.request.RoomBedCreateRequest;
import com.homestay.pms_backend.dto.request.RoomBedUpdateRequest;
import com.homestay.pms_backend.entity.Amenity;
import com.homestay.pms_backend.entity.BedType;
import com.homestay.pms_backend.entity.Property;
import com.homestay.pms_backend.entity.PropertyAmenity;
import com.homestay.pms_backend.entity.PropertyAmenityId;
import com.homestay.pms_backend.entity.Room;
import com.homestay.pms_backend.entity.RoomAmenity;
import com.homestay.pms_backend.entity.RoomAmenityId;
import com.homestay.pms_backend.entity.RoomBed;
import com.homestay.pms_backend.entity.RoomType;
import com.homestay.pms_backend.enums.AmenityCategory;
import com.homestay.pms_backend.enums.RoomStatus;
import com.homestay.pms_backend.enums.RoomTypeCategory;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.repository.AmenityRepository;
import com.homestay.pms_backend.repository.BedTypeRepository;
import com.homestay.pms_backend.repository.PropertyAmenityRepository;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.repository.RoomAmenityRepository;
import com.homestay.pms_backend.repository.RoomBedRepository;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IsolatedDatabaseTest
@TestPropertySource(properties = {
        "auth.jwt.enforcement.enabled=true",
        "TEST_AUTH_JWT_ENFORCEMENT_ENABLED=true"
})
@DisplayName("PMS Phase 2 — Step 3.4.3.2B: Association Authorization Integration Tests")
public class AssociationSecurityIntegrationTest {

    private static final String TEST_DOMAIN = "@test-assoc-authz.com";
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
    private BedTypeRepository bedTypeRepository;

    @Autowired
    private AmenityRepository amenityRepository;

    @Autowired
    private RoomBedRepository roomBedRepository;

    @Autowired
    private RoomAmenityRepository roomAmenityRepository;

    @Autowired
    private PropertyAmenityRepository propertyAmenityRepository;

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
    private UUID bedTypeIdKing;
    private UUID bedTypeIdSingle;
    private UUID amenityIdWifi;
    private UUID amenityIdPool;
    private UUID roomBedIdA;
    private UUID roomBedIdB;

    private String superAdminToken;
    private String propAdminTokenA;
    private String receptionistTokenA;
    private String viewerTokenA;
    private String accountantTokenA;
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

        // 1. Seed Properties
        propertyIdA = getOrCreateProperty("Association Alpha", "PROP_ALPHA_ASSOC");
        propertyIdB = getOrCreateProperty("Association Beta", "PROP_BETA_ASSOC");

        // 2. Seed Room Types
        roomTypeIdA = getOrCreateRoomType(propertyIdA, "Deluxe Assoc A", "RTA_ASSOC");
        roomTypeIdB = getOrCreateRoomType(propertyIdB, "Standard Assoc B", "RTB_ASSOC");

        // 3. Seed Rooms
        roomIdA = getOrCreateRoom(propertyIdA, roomTypeIdA, "A-101-ASSOC");
        roomIdB = getOrCreateRoom(propertyIdB, roomTypeIdB, "B-201-ASSOC");

        // 4. Seed Global Bed Types and Amenities
        bedTypeIdKing = getOrCreateBedType("KING_ASSOC", "King Bed Assoc");
        bedTypeIdSingle = getOrCreateBedType("SINGLE_ASSOC", "Single Bed Assoc");
        amenityIdWifi = getOrCreateAmenity("WIFI_ASSOC", "WiFi Assoc", AmenityCategory.ROOM);
        amenityIdPool = getOrCreateAmenity("POOL_ASSOC", "Pool Assoc", AmenityCategory.PROPERTY);

        // 5. Seed Initial Associations
        roomBedIdA = createRoomBed(roomIdA, bedTypeIdKing, 1, false);
        roomBedIdB = createRoomBed(roomIdB, bedTypeIdKing, 1, false);
        createRoomAmenity(roomIdA, amenityIdWifi);
        createRoomAmenity(roomIdB, amenityIdWifi);
        createPropertyAmenity(propertyIdA, amenityIdPool);
        createPropertyAmenity(propertyIdB, amenityIdPool);

        // 6. Create Staff Accounts and Tokens
        superAdminToken = createStaffToken("superadmin" + TEST_DOMAIN, StaffRole.SUPER_ADMIN, null);
        propAdminTokenA = createStaffToken("propadmin-a" + TEST_DOMAIN, StaffRole.PROPERTY_ADMIN, propertyIdA);
        receptionistTokenA = createStaffToken("receptionist-a" + TEST_DOMAIN, StaffRole.RECEPTIONIST, propertyIdA);
        viewerTokenA = createStaffToken("viewer-a" + TEST_DOMAIN, StaffRole.VIEWER, propertyIdA);
        accountantTokenA = createStaffToken("accountant-a" + TEST_DOMAIN, StaffRole.ACCOUNTANT, propertyIdA);
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
                    "DELETE FROM room_beds WHERE room_id IN (SELECT id FROM rooms WHERE room_number IN ('A-101-ASSOC', 'B-201-ASSOC')); " +
                    "DELETE FROM room_amenities WHERE room_id IN (SELECT id FROM rooms WHERE room_number IN ('A-101-ASSOC', 'B-201-ASSOC')); " +
                    "DELETE FROM property_amenities WHERE property_id IN (SELECT id FROM properties WHERE code IN ('PROP_ALPHA_ASSOC', 'PROP_BETA_ASSOC')); " +
                    "DELETE FROM rooms WHERE room_number IN ('A-101-ASSOC', 'B-201-ASSOC'); " +
                    "DELETE FROM room_types WHERE code IN ('RTA_ASSOC', 'RTB_ASSOC'); " +
                    "DELETE FROM bed_types WHERE code IN ('KING_ASSOC', 'SINGLE_ASSOC', 'EXTRA_ASSOC'); " +
                    "DELETE FROM amenities WHERE code IN ('WIFI_ASSOC', 'POOL_ASSOC', 'AC_ASSOC', 'TV_ASSOC'); " +
                    "DELETE FROM properties WHERE code IN ('PROP_ALPHA_ASSOC', 'PROP_BETA_ASSOC');"
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
                    prop.setDescription("Integration test property for association authorization");
                    prop.setAddressLine1("100 Assoc Blvd");
                    prop.setCity("Assoc City");
                    prop.setState("Assoc State");
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
                    rt.setDescription("Assoc Room Type");
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
                    r.setMaxExtraBeds(2);
                    r.setBathroomCount(1);
                    r.setStatus(RoomStatus.AVAILABLE);
                    r.setActive(true);
                    return roomRepository.save(r).getId();
                });
    }

    private UUID getOrCreateBedType(String code, String name) {
        return bedTypeRepository.findByCode(code)
                .map(BedType::getId)
                .orElseGet(() -> {
                    BedType bt = new BedType();
                    bt.setCode(code);
                    bt.setName(name);
                    return bedTypeRepository.save(bt).getId();
                });
    }

    private UUID getOrCreateAmenity(String code, String name, AmenityCategory category) {
        return amenityRepository.findByCode(code)
                .map(Amenity::getId)
                .orElseGet(() -> {
                    Amenity a = new Amenity();
                    a.setCode(code);
                    a.setName(name);
                    a.setCategory(category);
                    a.setDescription("Test association amenity");
                    a.setActive(true);
                    return amenityRepository.save(a).getId();
                });
    }

    private UUID createRoomBed(UUID roomId, UUID bedTypeId, int quantity, boolean extraBed) {
        RoomBed rb = new RoomBed();
        rb.setRoomId(roomId);
        rb.setBedTypeId(bedTypeId);
        rb.setQuantity(quantity);
        rb.setExtraBed(extraBed);
        return roomBedRepository.save(rb).getId();
    }

    private void createRoomAmenity(UUID roomId, UUID amenityId) {
        RoomAmenity ra = new RoomAmenity();
        ra.setId(new RoomAmenityId(roomId, amenityId));
        roomAmenityRepository.save(ra);
    }

    private void createPropertyAmenity(UUID propertyId, UUID amenityId) {
        PropertyAmenity pa = new PropertyAmenity();
        pa.setId(new PropertyAmenityId(propertyId, amenityId));
        propertyAmenityRepository.save(pa);
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
    // SECTION 1: SUPER_ADMIN Global Operations (Tests 1, 21)
    // =========================================================================

    @Test
    @DisplayName("1. SUPER_ADMIN can manage room beds across all properties")
    void superAdmin_canManageRoomBedsAcrossProperties() throws Exception {
        UUID extraBedType = getOrCreateBedType("EXTRA_ASSOC", "Extra Bed Assoc");

        // Create RoomBed on Room A
        RoomBedCreateRequest reqA = new RoomBedCreateRequest();
        reqA.setRoomId(roomIdA);
        reqA.setBedTypeId(extraBedType);
        reqA.setQuantity(1);
        reqA.setExtraBed(true);

        String responseAStr = mockMvc.perform(post("/api/v1/room-beds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").value(roomIdA.toString()))
                .andExpect(jsonPath("$.bedTypeId").value(extraBedType.toString()))
                .andReturn().getResponse().getContentAsString();

        UUID createdBedIdA = UUID.fromString(objectMapper.readTree(responseAStr).get("id").asText());

        // Create RoomBed on Room B
        RoomBedCreateRequest reqB = new RoomBedCreateRequest();
        reqB.setRoomId(roomIdB);
        reqB.setBedTypeId(extraBedType);
        reqB.setQuantity(2);
        reqB.setExtraBed(true);

        mockMvc.perform(post("/api/v1/room-beds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqB)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").value(roomIdB.toString()))
                .andExpect(jsonPath("$.quantity").value(2));

        // Read RoomBed on Room A
        mockMvc.perform(get("/api/v1/room-beds/{id}", createdBedIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdBedIdA.toString()));

        // Update RoomBed on Room A
        RoomBedUpdateRequest updateReq = new RoomBedUpdateRequest();
        updateReq.setQuantity(2);
        mockMvc.perform(put("/api/v1/room-beds/{id}", createdBedIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(2));

        // Delete RoomBed on Room A
        mockMvc.perform(delete("/api/v1/room-beds/{id}", createdBedIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("2. SUPER_ADMIN can manage room amenities across all properties")
    void superAdmin_canManageRoomAmenitiesAcrossProperties() throws Exception {
        UUID acAmenity = getOrCreateAmenity("AC_ASSOC", "AC Assoc", AmenityCategory.ROOM);

        // Add to Room A
        RoomAmenityCreateRequest reqA = new RoomAmenityCreateRequest();
        reqA.setRoomId(roomIdA);
        reqA.setAmenityId(acAmenity);

        mockMvc.perform(post("/api/v1/room-amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").value(roomIdA.toString()))
                .andExpect(jsonPath("$.amenityId").value(acAmenity.toString()));

        // Add to Room B
        RoomAmenityCreateRequest reqB = new RoomAmenityCreateRequest();
        reqB.setRoomId(roomIdB);
        reqB.setAmenityId(acAmenity);

        mockMvc.perform(post("/api/v1/room-amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqB)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").value(roomIdB.toString()));

        // Read from Room A
        mockMvc.perform(get("/api/v1/room-amenities/room/{roomId}", roomIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2))); // Wifi + AC

        // Delete from Room A
        mockMvc.perform(delete("/api/v1/room-amenities/{roomId}/{amenityId}", roomIdA, acAmenity)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("3. SUPER_ADMIN can manage property amenities across all properties")
    void superAdmin_canManagePropertyAmenitiesAcrossProperties() throws Exception {
        UUID tvAmenity = getOrCreateAmenity("TV_ASSOC", "TV Assoc", AmenityCategory.PROPERTY);

        // Add to Property A
        PropertyAmenityCreateRequest reqA = new PropertyAmenityCreateRequest();
        reqA.setPropertyId(propertyIdA);
        reqA.setAmenityId(tvAmenity);

        mockMvc.perform(post("/api/v1/property-amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.propertyId").value(propertyIdA.toString()))
                .andExpect(jsonPath("$.amenityId").value(tvAmenity.toString()));

        // Add to Property B
        PropertyAmenityCreateRequest reqB = new PropertyAmenityCreateRequest();
        reqB.setPropertyId(propertyIdB);
        reqB.setAmenityId(tvAmenity);

        mockMvc.perform(post("/api/v1/property-amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqB)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.propertyId").value(propertyIdB.toString()));

        // Read from Property A
        mockMvc.perform(get("/api/v1/property-amenities/property/{propertyId}", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2))); // Pool + TV

        // Delete from Property A
        mockMvc.perform(delete("/api/v1/property-amenities/{propertyId}/{amenityId}", propertyIdA, tvAmenity)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("4. SUPER_ADMIN reverse lookups return associations across all properties")
    void superAdmin_reverseLookupsReturnAssociationsAcrossAllProperties() throws Exception {
        // Room Beds by BedType (both Room A and Room B have King bed)
        mockMvc.perform(get("/api/v1/room-beds")
                        .param("bedTypeId", bedTypeIdKing.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // Room Amenities by AmenityId (both Room A and Room B have Wifi)
        mockMvc.perform(get("/api/v1/room-amenities/amenity/{amenityId}", amenityIdWifi)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // Property Amenities by AmenityId (both Property A and Property B have Pool)
        mockMvc.perform(get("/api/v1/property-amenities/amenity/{amenityId}", amenityIdPool)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    // =========================================================================
    // SECTION 2: PROPERTY_ADMIN Scoped Full Authority (Test 2)
    // =========================================================================

    @Test
    @DisplayName("5. PROPERTY_ADMIN on Property A can manage room beds for Property A")
    void propertyAdminA_canManageRoomBedsForPropertyA() throws Exception {
        UUID extraBedType = getOrCreateBedType("EXTRA_ASSOC", "Extra Bed Assoc");

        // Create RoomBed on Room A
        RoomBedCreateRequest req = new RoomBedCreateRequest();
        req.setRoomId(roomIdA);
        req.setBedTypeId(extraBedType);
        req.setQuantity(1);
        req.setExtraBed(true);

        String responseStr = mockMvc.perform(post("/api/v1/room-beds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").value(roomIdA.toString()))
                .andReturn().getResponse().getContentAsString();

        UUID createdBedId = UUID.fromString(objectMapper.readTree(responseStr).get("id").asText());

        // Read RoomBed on Room A
        mockMvc.perform(get("/api/v1/room-beds/{id}", createdBedId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdBedId.toString()));

        // Update RoomBed on Room A
        RoomBedUpdateRequest updateReq = new RoomBedUpdateRequest();
        updateReq.setQuantity(2);
        mockMvc.perform(put("/api/v1/room-beds/{id}", createdBedId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(2));

        // Delete RoomBed on Room A
        mockMvc.perform(delete("/api/v1/room-beds/{id}", createdBedId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("6. PROPERTY_ADMIN on Property A can manage room amenities for Property A")
    void propertyAdminA_canManageRoomAmenitiesForPropertyA() throws Exception {
        UUID acAmenity = getOrCreateAmenity("AC_ASSOC", "AC Assoc", AmenityCategory.ROOM);

        // Add to Room A
        RoomAmenityCreateRequest req = new RoomAmenityCreateRequest();
        req.setRoomId(roomIdA);
        req.setAmenityId(acAmenity);

        mockMvc.perform(post("/api/v1/room-amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").value(roomIdA.toString()));

        // Read from Room A
        mockMvc.perform(get("/api/v1/room-amenities/room/{roomId}", roomIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // Remove from Room A
        mockMvc.perform(delete("/api/v1/room-amenities/{roomId}/{amenityId}", roomIdA, acAmenity)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("7. PROPERTY_ADMIN on Property A can manage property amenities for Property A")
    void propertyAdminA_canManagePropertyAmenitiesForPropertyA() throws Exception {
        UUID tvAmenity = getOrCreateAmenity("TV_ASSOC", "TV Assoc", AmenityCategory.PROPERTY);

        // Add to Property A
        PropertyAmenityCreateRequest req = new PropertyAmenityCreateRequest();
        req.setPropertyId(propertyIdA);
        req.setAmenityId(tvAmenity);

        mockMvc.perform(post("/api/v1/property-amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.propertyId").value(propertyIdA.toString()));

        // Read from Property A
        mockMvc.perform(get("/api/v1/property-amenities/property/{propertyId}", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // Remove from Property A
        mockMvc.perform(delete("/api/v1/property-amenities/{propertyId}/{amenityId}", propertyIdA, tvAmenity)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNoContent());
    }

    // =========================================================================
    // SECTION 3: RECEPTIONIST Role Restrictions (Tests 3, 11, 14, 15, 18)
    // =========================================================================

    @Test
    @DisplayName("8. RECEPTIONIST on Property A can read associations but cannot mutate (403)")
    void receptionistA_canReadRoomBedsAndAmenities_cannotMutate() throws Exception {
        UUID extraBedType = getOrCreateBedType("EXTRA_ASSOC", "Extra Bed Assoc");
        UUID acAmenity = getOrCreateAmenity("AC_ASSOC", "AC Assoc", AmenityCategory.ROOM);
        UUID tvAmenity = getOrCreateAmenity("TV_ASSOC", "TV Assoc", AmenityCategory.PROPERTY);

        // Reads succeed (200)
        mockMvc.perform(get("/api/v1/room-beds/{id}", roomBedIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/room-amenities/room/{roomId}", roomIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/property-amenities/property/{propertyId}", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk());

        // RoomBed mutations fail (403)
        RoomBedCreateRequest bedReq = new RoomBedCreateRequest();
        bedReq.setRoomId(roomIdA);
        bedReq.setBedTypeId(extraBedType);
        bedReq.setQuantity(1);
        mockMvc.perform(post("/api/v1/room-beds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bedReq)))
                .andExpect(status().isForbidden());

        RoomBedUpdateRequest bedUpdateReq = new RoomBedUpdateRequest();
        bedUpdateReq.setQuantity(2);
        mockMvc.perform(put("/api/v1/room-beds/{id}", roomBedIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bedUpdateReq)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/room-beds/{id}", roomBedIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isForbidden());

        // RoomAmenity mutations fail (403)
        RoomAmenityCreateRequest roomAmenityReq = new RoomAmenityCreateRequest();
        roomAmenityReq.setRoomId(roomIdA);
        roomAmenityReq.setAmenityId(acAmenity);
        mockMvc.perform(post("/api/v1/room-amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(roomAmenityReq)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/room-amenities/{roomId}/{amenityId}", roomIdA, amenityIdWifi)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isForbidden());

        // PropertyAmenity mutations fail (403)
        PropertyAmenityCreateRequest propAmenityReq = new PropertyAmenityCreateRequest();
        propAmenityReq.setPropertyId(propertyIdA);
        propAmenityReq.setAmenityId(tvAmenity);
        mockMvc.perform(post("/api/v1/property-amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(propAmenityReq)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/property-amenities/{propertyId}/{amenityId}", propertyIdA, amenityIdPool)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // SECTION 4: VIEWER Role Restrictions (Tests 4, 11, 14, 15, 18)
    // =========================================================================

    @Test
    @DisplayName("9. VIEWER on Property A can read associations but cannot mutate (403)")
    void viewerA_canReadRoomBedsAndAmenities_cannotMutate() throws Exception {
        UUID extraBedType = getOrCreateBedType("EXTRA_ASSOC", "Extra Bed Assoc");
        UUID acAmenity = getOrCreateAmenity("AC_ASSOC", "AC Assoc", AmenityCategory.ROOM);
        UUID tvAmenity = getOrCreateAmenity("TV_ASSOC", "TV Assoc", AmenityCategory.PROPERTY);

        // Reads succeed (200)
        mockMvc.perform(get("/api/v1/room-beds/{id}", roomBedIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/room-amenities/room/{roomId}", roomIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/property-amenities/property/{propertyId}", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isOk());

        // Mutations fail (403)
        RoomBedCreateRequest bedReq = new RoomBedCreateRequest();
        bedReq.setRoomId(roomIdA);
        bedReq.setBedTypeId(extraBedType);
        bedReq.setQuantity(1);
        mockMvc.perform(post("/api/v1/room-beds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bedReq)))
                .andExpect(status().isForbidden());

        RoomAmenityCreateRequest roomAmenityReq = new RoomAmenityCreateRequest();
        roomAmenityReq.setRoomId(roomIdA);
        roomAmenityReq.setAmenityId(acAmenity);
        mockMvc.perform(post("/api/v1/room-amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(roomAmenityReq)))
                .andExpect(status().isForbidden());

        PropertyAmenityCreateRequest propAmenityReq = new PropertyAmenityCreateRequest();
        propAmenityReq.setPropertyId(propertyIdA);
        propAmenityReq.setAmenityId(tvAmenity);
        mockMvc.perform(post("/api/v1/property-amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(propAmenityReq)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // SECTION 5: ACCOUNTANT Role Restrictions (Test 5)
    // =========================================================================

    @Test
    @DisplayName("10. ACCOUNTANT on Property A can read associations but cannot mutate (403)")
    void accountantA_cannotReadOrMutateAssociations() throws Exception {
        UUID extraBedType = getOrCreateBedType("EXTRA_ASSOC", "Extra Bed Assoc");
        UUID acAmenity = getOrCreateAmenity("AC_ASSOC", "AC Assoc", AmenityCategory.ROOM);
        UUID tvAmenity = getOrCreateAmenity("TV_ASSOC", "TV Assoc", AmenityCategory.PROPERTY);

        // Reads succeed (200 OK) because ACCOUNTANT possesses ROOM_READ and PROPERTY_READ
        mockMvc.perform(get("/api/v1/room-beds/{id}", roomBedIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/room-amenities/room/{roomId}", roomIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/property-amenities/property/{propertyId}", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA))
                .andExpect(status().isOk());

        // Mutations fail with 403 Forbidden (ACCOUNTANT lacks ROOM_MANAGE and PROPERTY_MANAGE)
        RoomBedCreateRequest bedReq = new RoomBedCreateRequest();
        bedReq.setRoomId(roomIdA);
        bedReq.setBedTypeId(extraBedType);
        bedReq.setQuantity(1);
        mockMvc.perform(post("/api/v1/room-beds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bedReq)))
                .andExpect(status().isForbidden());

        RoomBedUpdateRequest bedUpdateReq = new RoomBedUpdateRequest();
        bedUpdateReq.setQuantity(2);
        mockMvc.perform(put("/api/v1/room-beds/{id}", roomBedIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bedUpdateReq)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/room-beds/{id}", roomBedIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA))
                .andExpect(status().isForbidden());

        RoomAmenityCreateRequest roomAmenityReq = new RoomAmenityCreateRequest();
        roomAmenityReq.setRoomId(roomIdA);
        roomAmenityReq.setAmenityId(acAmenity);
        mockMvc.perform(post("/api/v1/room-amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(roomAmenityReq)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/room-amenities/{roomId}/{amenityId}", roomIdA, amenityIdWifi)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA))
                .andExpect(status().isForbidden());

        PropertyAmenityCreateRequest propAmenityReq = new PropertyAmenityCreateRequest();
        propAmenityReq.setPropertyId(propertyIdA);
        propAmenityReq.setAmenityId(tvAmenity);
        mockMvc.perform(post("/api/v1/property-amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(propAmenityReq)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/property-amenities/{propertyId}/{amenityId}", propertyIdA, amenityIdPool)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // SECTION 6: Cross-Property Isolation & Anti-Enumeration (Tests 6, 7, 8, 11, 12, 14, 15, 16, 18)
    // =========================================================================

    @Test
    @DisplayName("11. Cross-property room bed access returns 404 indistinguishable from nonexistent")
    void propertyAdminA_crossPropertyRoomBedAccess_returns404Not403() throws Exception {
        UUID nonExistentId = UUID.randomUUID();

        // Cross-property GET -> 404
        mockMvc.perform(get("/api/v1/room-beds/{id}", roomBedIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Room bed configuration not found with id: " + roomBedIdB));

        // Non-existent GET -> 404 (exact same shape and structure)
        mockMvc.perform(get("/api/v1/room-beds/{id}", nonExistentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Room bed configuration not found with id: " + nonExistentId));

        // Cross-property PUT -> 404
        RoomBedUpdateRequest updateReq = new RoomBedUpdateRequest();
        updateReq.setQuantity(5);
        mockMvc.perform(put("/api/v1/room-beds/{id}", roomBedIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        // Cross-property DELETE -> 404
        mockMvc.perform(delete("/api/v1/room-beds/{id}", roomBedIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("12. Cross-property room bed creation returns 404 (room out of scope)")
    void propertyAdminA_crossPropertyRoomBedCreation_returns404() throws Exception {
        RoomBedCreateRequest req = new RoomBedCreateRequest();
        req.setRoomId(roomIdB);
        req.setBedTypeId(bedTypeIdSingle);
        req.setQuantity(1);

        mockMvc.perform(post("/api/v1/room-beds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Room not found with id: " + roomIdB));
    }

    @Test
    @DisplayName("13. Room bed list by roomId is property scoped (404 for out of scope room)")
    void propertyAdminA_roomBedListByRoomId_crossPropertyReturns404() throws Exception {
        // Query Room A -> 200 OK
        mockMvc.perform(get("/api/v1/room-beds")
                        .param("roomId", roomIdA.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].roomId").value(roomIdA.toString()));

        // Query Room B -> 404 Not Found
        mockMvc.perform(get("/api/v1/room-beds")
                        .param("roomId", roomIdB.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Room not found with id: " + roomIdB));
    }

    @Test
    @DisplayName("14. Cross-property room amenity operations return 404 (out of scope room)")
    void propertyAdminA_crossPropertyRoomAmenityAccess_returns404() throws Exception {
        UUID acAmenity = getOrCreateAmenity("AC_ASSOC", "AC Assoc", AmenityCategory.ROOM);

        // POST on Room B -> 404
        RoomAmenityCreateRequest req = new RoomAmenityCreateRequest();
        req.setRoomId(roomIdB);
        req.setAmenityId(acAmenity);

        mockMvc.perform(post("/api/v1/room-amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Room not found with id: " + roomIdB));

        // GET Room B -> 404
        mockMvc.perform(get("/api/v1/room-amenities/room/{roomId}", roomIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Room not found with id: " + roomIdB));

        // DELETE Room B -> 404
        mockMvc.perform(delete("/api/v1/room-amenities/{roomId}/{amenityId}", roomIdB, amenityIdWifi)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Room not found with id: " + roomIdB));
    }

    @Test
    @DisplayName("15. Cross-property property amenity operations return 404 (out of scope property)")
    void propertyAdminA_crossPropertyPropertyAmenityAccess_returns404() throws Exception {
        UUID tvAmenity = getOrCreateAmenity("TV_ASSOC", "TV Assoc", AmenityCategory.PROPERTY);

        // POST on Property B -> 404
        PropertyAmenityCreateRequest req = new PropertyAmenityCreateRequest();
        req.setPropertyId(propertyIdB);
        req.setAmenityId(tvAmenity);

        mockMvc.perform(post("/api/v1/property-amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Property not found with id: " + propertyIdB));

        // GET Property B -> 404
        mockMvc.perform(get("/api/v1/property-amenities/property/{propertyId}", propertyIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Property not found with id: " + propertyIdB));

        // DELETE Property B -> 404
        mockMvc.perform(delete("/api/v1/property-amenities/{propertyId}/{amenityId}", propertyIdB, amenityIdPool)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Property not found with id: " + propertyIdB));
    }

    // =========================================================================
    // SECTION 7: Reverse Lookups Scoping & Dual Filter (Tests 9, 10, 13, 17)
    // =========================================================================

    @Test
    @DisplayName("16. Reverse lookup for room beds by bedTypeId filters strictly by authorized properties")
    void propertyAdminA_roomBedsByBedTypeId_doesNotLeakPropertyBBeds() throws Exception {
        // Both Room A and Room B have King bed, but PropAdmin A must only see Room A
        mockMvc.perform(get("/api/v1/room-beds")
                        .param("bedTypeId", bedTypeIdKing.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].roomId").value(roomIdA.toString()))
                .andExpect(jsonPath("$[0].id").value(roomBedIdA.toString()));
    }

    @Test
    @DisplayName("17. Dual filter (roomId + bedTypeId) enforces both conditions and prevents cross-property leakage")
    void propertyAdminA_roomBedsDualFilters_roomIdAndBedTypeId_respected() throws Exception {
        // 1. Valid room + matching bedType -> returns match
        mockMvc.perform(get("/api/v1/room-beds")
                        .param("roomId", roomIdA.toString())
                        .param("bedTypeId", bedTypeIdKing.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].bedTypeId").value(bedTypeIdKing.toString()));

        // 2. Valid room + non-matching bedType -> returns empty list
        mockMvc.perform(get("/api/v1/room-beds")
                        .param("roomId", roomIdA.toString())
                        .param("bedTypeId", bedTypeIdSingle.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));

        // 3. Out-of-scope room + valid bedType -> returns 404 (not empty list!)
        mockMvc.perform(get("/api/v1/room-beds")
                        .param("roomId", roomIdB.toString())
                        .param("bedTypeId", bedTypeIdKing.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Room not found with id: " + roomIdB));

        // 4. Valid room + non-existent bedType -> returns 404 (bed type not found)
        UUID nonExistentBedTypeId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/room-beds")
                        .param("roomId", roomIdA.toString())
                        .param("bedTypeId", nonExistentBedTypeId.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Bed type not found with id: " + nonExistentBedTypeId));
    }

    @Test
    @DisplayName("18. Reverse lookup for room amenities by amenityId does not leak Property B associations")
    void propertyAdminA_roomAmenitiesByAmenityId_doesNotLeakPropertyBAmenities() throws Exception {
        // Both Room A and Room B have Wifi amenity, but PropAdmin A must only see Room A
        mockMvc.perform(get("/api/v1/room-amenities/amenity/{amenityId}", amenityIdWifi)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].roomId").value(roomIdA.toString()));
    }

    @Test
    @DisplayName("19. Reverse lookup for property amenities by amenityId does not leak Property B associations")
    void propertyAdminA_propertyAmenitiesByAmenityId_doesNotLeakPropertyBAmenities() throws Exception {
        // Both Property A and Property B have Pool amenity, but PropAdmin A must only see Property A
        mockMvc.perform(get("/api/v1/property-amenities/amenity/{amenityId}", amenityIdPool)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].propertyId").value(propertyIdA.toString()));
    }

    // =========================================================================
    // SECTION 8: Unassigned Staff Behavior (Test 19)
    // =========================================================================

    @Test
    @DisplayName("20. Unassigned staff receives empty list on reverse lookups and 404 on entities")
    void unassignedStaff_receivesEmptyListOnReverseLookups_and404OnEntities() throws Exception {
        // Reverse lookups return empty list
        mockMvc.perform(get("/api/v1/room-beds")
                        .param("bedTypeId", bedTypeIdKing.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unassignedStaffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));

        mockMvc.perform(get("/api/v1/room-amenities/amenity/{amenityId}", amenityIdWifi)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unassignedStaffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));

        mockMvc.perform(get("/api/v1/property-amenities/amenity/{amenityId}", amenityIdPool)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unassignedStaffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));

        // Entity lookups return 404 Not Found
        mockMvc.perform(get("/api/v1/room-beds/{id}", roomBedIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unassignedStaffToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/room-amenities/room/{roomId}", roomIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unassignedStaffToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/property-amenities/property/{propertyId}", propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unassignedStaffToken))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // SECTION 9: Dynamic Role Revocation (Test 20)
    // =========================================================================

    @Test
    @DisplayName("21. Dynamic role revocation takes effect immediately on subsequent requests")
    void dynamicRoleRevocation_takesEffectImmediatelyOnSubsequentRequest() throws Exception {
        String testEmail = "dynamic-revocation" + TEST_DOMAIN;
        UUID dynamicStaffId = createTestStaff(testEmail, true, false);

        adminJdbcTemplate.update(
                "INSERT INTO staff_property_roles (staff_user_id, property_id, role_code, assigned_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                dynamicStaffId,
                propertyIdA,
                StaffRole.PROPERTY_ADMIN.getCode()
        );

        AuthenticatedStaff staff = new AuthenticatedStaff(dynamicStaffId, testEmail, "Dynamic", "Staff", "+919876543210");
        String dynamicToken = accessTokenService.generateAccessToken(staff).token();

        // 1. Initial request succeeds (200 OK)
        mockMvc.perform(get("/api/v1/room-beds/{id}", roomBedIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dynamicToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(roomBedIdA.toString()));

        // 2. Revoke role in the database directly
        adminJdbcTemplate.update("DELETE FROM staff_property_roles WHERE staff_user_id = ?", dynamicStaffId);

        // 3. Subsequent request with the exact same token immediately fails with 404 (property no longer in scope)
        mockMvc.perform(get("/api/v1/room-beds/{id}", roomBedIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dynamicToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // =========================================================================
    // SECTION 10: Development Mode & Parameter Integrity (Test 22)
    // =========================================================================

    @Test
    @DisplayName("22. Business integrity checks remain enforced even when security enforcement is disabled")
    void developmentMode_preservesBusinessIntegrityValidation() {
        SecurityEnforcementProperties disabledProps = new SecurityEnforcementProperties();
        disabledProps.setEnabled(false);

        PropertyAuthorizationGuard devGuard = new PropertyAuthorizationGuard(
                authorizationService,
                disabledProps,
                propertyRepository,
                roomRepository,
                roomTypeRepository,
                roomBedRepository,
                bedTypeRepository,
                amenityRepository
        );

        UUID nonExistentId = UUID.randomUUID();

        // validateRoomBedCreation throws 404 for non-existent room
        assertThatThrownBy(() -> devGuard.validateRoomBedCreation(nonExistentId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Room not found with id: " + nonExistentId);

        // validateBedTypeExists throws 404 for non-existent bed type
        assertThatThrownBy(() -> devGuard.validateBedTypeExists(nonExistentId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Bed type not found with id: " + nonExistentId);

        // validateAmenityExists throws 404 for non-existent amenity
        assertThatThrownBy(() -> devGuard.validateAmenityExists(nonExistentId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Amenity not found with id: " + nonExistentId);

        // Valid entities pass without error
        devGuard.validateRoomBedCreation(roomIdA);
        devGuard.validateBedTypeExists(bedTypeIdKing);
        devGuard.validateAmenityExists(amenityIdWifi);
    }

    @Test
    @DisplayName("23. GET /api/v1/room-beds without roomId or bedTypeId returns 400 Bad Request")
    void roomBeds_withoutParameters_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/room-beds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Either roomId or bedTypeId must be provided"));
    }

    // =========================================================================
    // SECTION 11: Authentication Enforcement (Test 23)
    // =========================================================================

    @Test
    @DisplayName("24. Missing or invalid JWT is rejected with 401 Unauthorized")
    void missingOrInvalidJwt_isRejected() throws Exception {
        // Missing token on RoomBed GET
        mockMvc.perform(get("/api/v1/room-beds/{id}", roomBedIdA))
                .andExpect(status().isUnauthorized());

        // Missing token on RoomAmenity POST
        RoomAmenityCreateRequest roomAmenityReq = new RoomAmenityCreateRequest();
        roomAmenityReq.setRoomId(roomIdA);
        roomAmenityReq.setAmenityId(amenityIdWifi);
        mockMvc.perform(post("/api/v1/room-amenities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(roomAmenityReq)))
                .andExpect(status().isUnauthorized());

        // Missing token on PropertyAmenity GET
        mockMvc.perform(get("/api/v1/property-amenities/property/{propertyId}", propertyIdA))
                .andExpect(status().isUnauthorized());

        // Malformed token
        mockMvc.perform(get("/api/v1/room-beds/{id}", roomBedIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // SECTION 12: Workflows Regression Checks (Test 25)
    // =========================================================================

    @Test
    @DisplayName("25. Existing reservation and guest workflows remain unaffected")
    void existingGuestAndReservationWorkflows_remainUnaffected() throws Exception {
        // Guests list is accessible to authenticated staff
        mockMvc.perform(get("/api/v1/guests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        // Reservations list is accessible to authenticated staff
        mockMvc.perform(get("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());
    }
}
