package com.homestay.pms_backend.security.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homestay.pms_backend.dto.request.ReservationCreateRequest;
import com.homestay.pms_backend.dto.request.ReservationRoomCreateRequest;
import com.homestay.pms_backend.dto.request.RoomCreateRequest;
import com.homestay.pms_backend.dto.request.RoomHousekeepingUpdateRequest;
import com.homestay.pms_backend.dto.response.ReservationResponse;
import com.homestay.pms_backend.entity.*;
import com.homestay.pms_backend.enums.*;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.repository.*;
import com.homestay.pms_backend.security.AuthenticatedStaff;
import com.homestay.pms_backend.security.jwt.AccessTokenDto;
import com.homestay.pms_backend.security.jwt.AccessTokenService;
import com.homestay.pms_backend.service.GuestPropertyAssociationService;
import com.homestay.pms_backend.service.ReservationService;
import com.homestay.pms_backend.service.RoomService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IsolatedDatabaseTest
@TestPropertySource(properties = {
        "auth.jwt.enforcement.enabled=true",
        "TEST_AUTH_JWT_ENFORCEMENT_ENABLED=true"
})
@DisplayName("PMS Phase 2 — Step 3.4.3.6E: Housekeeping Foundation and Room Readiness Integration Tests")
class RoomHousekeepingIntegrationTest {

    private static final String TEST_DOMAIN = "@housekeeping-test.com";
    private static final String VALID_BCRYPT_HASH = "$2a$10$wT0l8kPqSsqGzC.q0Qe11eZ7t6jPqXn3yEwK6Tq5Z7hC0d1z8Kz6W";
    private static final String PROPERTY_TZ = "Asia/Kolkata";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private AccessTokenService accessTokenService;

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
    private RoomAssignmentRepository roomAssignmentRepository;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private GuestPropertyAssociationService guestPropertyAssociationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Environment environment;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private MockMvc mockMvc;
    private JdbcTemplate adminJdbcTemplate;

    private UUID propertyIdA;
    private UUID propertyIdB;
    private UUID roomTypeIdA;
    private UUID roomIdA1;
    private UUID roomIdA2;
    private UUID roomIdB;
    private UUID guestIdA;
    private UUID bookingSourceDirectId;

    private UUID staffIdAdminA;
    private String superAdminToken;
    private String propAdminTokenA;
    private String receptionistTokenA;
    private String accountantTokenA;
    private String viewerTokenA;
    private String propAdminTokenB;

    private LocalDate todayInPropZone;

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

        todayInPropZone = LocalDate.now(ZoneId.of(PROPERTY_TZ));

        propertyIdA = getOrCreateProperty("Housekeeping Property Alpha", "PROP_HK_A", PROPERTY_TZ);
        propertyIdB = getOrCreateProperty("Housekeeping Property Beta", "PROP_HK_B", PROPERTY_TZ);

        roomTypeIdA = getOrCreateRoomType(propertyIdA, "Housekeeping Room Type A", "RT_HK_A");
        roomIdA1 = getOrCreateRoom(propertyIdA, roomTypeIdA, "HK-101");
        roomIdA2 = getOrCreateRoom(propertyIdA, roomTypeIdA, "HK-102");
        roomIdB = getOrCreateRoom(propertyIdB, getOrCreateRoomType(propertyIdB, "Housekeeping Room Type B", "RT_HK_B"), "HK-201");

        guestIdA = getOrCreateGuest("Housekeeping Guest Alpha", "guest-hk@test.com", "+919988776655");
        guestPropertyAssociationService.createRegistrationAssociation(guestIdA, propertyIdA);

        bookingSourceDirectId = bookingSourceRepository.findByCode("DIRECT")
                .orElseThrow(() -> new IllegalStateException("DIRECT booking source not found"))
                .getId();

        superAdminToken = createStaffToken("hk-superadmin" + TEST_DOMAIN, StaffRole.SUPER_ADMIN, null);

        staffIdAdminA = createTestStaff("hk-propadmin-a" + TEST_DOMAIN);
        assignStaffPropertyRole(staffIdAdminA, propertyIdA, StaffRole.PROPERTY_ADMIN);
        propAdminTokenA = generateTokenForStaff(staffIdAdminA, "hk-propadmin-a" + TEST_DOMAIN);

        receptionistTokenA = createStaffToken("hk-receptionist-a" + TEST_DOMAIN, StaffRole.RECEPTIONIST, propertyIdA);
        accountantTokenA = createStaffToken("hk-accountant-a" + TEST_DOMAIN, StaffRole.ACCOUNTANT, propertyIdA);
        viewerTokenA = createStaffToken("hk-viewer-a" + TEST_DOMAIN, StaffRole.VIEWER, propertyIdA);
        propAdminTokenB = createStaffToken("hk-propadmin-b" + TEST_DOMAIN, StaffRole.PROPERTY_ADMIN, propertyIdB);
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
                    "DELETE FROM room_assignments WHERE room_id IN (SELECT id FROM rooms WHERE room_number IN ('HK-101', 'HK-102', 'HK-103', 'HK-201')); " +
                    "DELETE FROM reservation_rooms WHERE room_type_id IN (SELECT id FROM room_types WHERE code IN ('RT_HK_A', 'RT_HK_B')); " +
                    "DELETE FROM reservations WHERE property_id IN (SELECT id FROM properties WHERE code IN ('PROP_HK_A', 'PROP_HK_B')); " +
                    "DELETE FROM rooms WHERE room_number IN ('HK-101', 'HK-102', 'HK-103', 'HK-201'); " +
                    "DELETE FROM room_types WHERE code IN ('RT_HK_A', 'RT_HK_B'); " +
                    "DELETE FROM guest_property_associations WHERE guest_id IN (SELECT id FROM guests WHERE email = 'guest-hk@test.com'); " +
                    "DELETE FROM guests WHERE email = 'guest-hk@test.com'; " +
                    "DELETE FROM properties WHERE code IN ('PROP_HK_A', 'PROP_HK_B');"
            );
        }
    }

    private UUID getOrCreateProperty(String name, String code, String timezone) {
        return propertyRepository.findByCode(code)
                .map(Property::getId)
                .orElseGet(() -> {
                    Property prop = new Property();
                    prop.setName(name);
                    prop.setCode(code);
                    prop.setDescription("Housekeeping integration test property");
                    prop.setAddressLine1("123 Clean Street");
                    prop.setCity("Clean City");
                    prop.setState("Clean State");
                    prop.setCountry("India");
                    prop.setTimezone(timezone);
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
                    rt.setDescription("Housekeeping Room Type");
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
                    Room room = new Room();
                    room.setPropertyId(propertyId);
                    room.setRoomTypeId(roomTypeId);
                    room.setRoomNumber(roomNumber);
                    room.setName("Room " + roomNumber);
                    room.setFloor("1");
                    room.setBaseOccupancy(2);
                    room.setMaxOccupancy(4);
                    room.setMaxExtraBeds(1);
                    room.setBathroomCount(1);
                    room.setStatus(RoomStatus.AVAILABLE);
                    room.setHousekeepingStatus(HousekeepingStatus.CLEAN);
                    room.setActive(true);
                    return roomRepository.save(room).getId();
                });
    }

    private UUID getOrCreateGuest(String name, String email, String phone) {
        List<Guest> existing = guestRepository.findByEmailIgnoreCase(email);
        if (!existing.isEmpty()) {
            return existing.get(0).getId();
        }
        Guest g = new Guest();
        g.setName(name);
        g.setEmail(email);
        g.setPhone(phone);
        g.setIdType(IdentityDocumentType.PASSPORT);
        g.setIdNumber("HK12345678");
        g.setActive(true);
        return guestRepository.save(g).getId();
    }

    private UUID createTestStaff(String email) {
        UUID id = UUID.randomUUID();
        adminJdbcTemplate.update(
                """
                INSERT INTO staff_users (
                    id, email, password_hash, first_name, last_name, phone,
                    is_active, is_locked, failed_login_attempts, lockout_until,
                    last_login_at, password_changed_at, created_at, updated_at
                ) VALUES (?, ?, ?, 'Test', 'Staff', '+919876543210', true, false, 0, NULL, NULL, CURRENT_TIMESTAMP - INTERVAL '5 seconds', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id,
                email.trim().toLowerCase(),
                VALID_BCRYPT_HASH
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
        UUID staffId = createTestStaff(email);
        if (role == StaffRole.SUPER_ADMIN) {
            adminJdbcTemplate.update(
                    "INSERT INTO staff_global_roles (staff_user_id, role_code, assigned_at) VALUES (?, 'SUPER_ADMIN', CURRENT_TIMESTAMP)",
                    staffId
            );
        } else if (propertyId != null) {
            assignStaffPropertyRole(staffId, propertyId, role);
        }
        return generateTokenForStaff(staffId, email);
    }

    private UUID createConfirmedReservation(UUID propertyId, UUID guestId, UUID bookingSourceId,
                                            LocalDate checkIn, LocalDate checkOut) {
        Reservation res = new Reservation();
        res.setPropertyId(propertyId);
        res.setPrimaryGuestId(guestId);
        res.setBookingSourceId(bookingSourceId);
        res.setCheckInDate(checkIn);
        res.setCheckOutDate(checkOut);
        res.setAdults(2);
        res.setChildren(0);
        res.setInfants(0);
        res.setStatus(ReservationStatus.CONFIRMED);
        res.setCheckoutDue(false);
        return reservationRepository.saveAndFlush(res).getId();
    }

    private UUID createReservationRoom(UUID reservationId, UUID roomTypeId, int quantity) {
        ReservationRoom rr = new ReservationRoom();
        rr.setReservationId(reservationId);
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(quantity);
        rr.setCancelledQuantity(0);
        rr.setUnitPrice(new BigDecimal("1200.00"));
        rr.setSubtotal(new BigDecimal("1200.00").multiply(BigDecimal.valueOf(quantity)));
        rr.setStatus(ReservationRoomStatus.CONFIRMED);
        return reservationRoomRepository.saveAndFlush(rr).getId();
    }

    private UUID assignRoom(UUID reservationRoomId, UUID roomId, LocalDate start, LocalDate end) {
        RoomAssignment ra = new RoomAssignment();
        ra.setReservationRoomId(reservationRoomId);
        ra.setRoomId(roomId);
        ra.setAssignedFrom(start);
        ra.setAssignedUntil(end);
        ra.setStatus(RoomAssignmentStatus.ASSIGNED);
        return roomAssignmentRepository.saveAndFlush(ra).getId();
    }

    // =========================================================================
    // Test 1-2: Schema & Entity Initialization
    // =========================================================================

    @Test
    @DisplayName("Test 1: Newly created room defaults to CLEAN")
    void test01_newlyCreatedRoom_defaultsToClean() throws Exception {
        RoomCreateRequest req = new RoomCreateRequest();
        req.setPropertyId(propertyIdA);
        req.setRoomTypeId(roomTypeIdA);
        req.setRoomNumber("HK-103");
        req.setName("New Room 103");
        req.setFloor("1");
        req.setBaseOccupancy(2);
        req.setMaxOccupancy(4);
        req.setMaxExtraBeds(1);
        req.setBathroomCount(1);

        mockMvc.perform(post("/api/v1/rooms")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.housekeepingStatus").value("CLEAN"));

        Room created = roomRepository.findByPropertyIdAndRoomNumber(propertyIdA, "HK-103").orElseThrow();
        assertThat(created.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.CLEAN);
    }

    @Test
    @DisplayName("Test 2: Existing room schema migration initializes housekeeping state")
    void test02_existingRoomSchemaMigration_initializesHousekeepingState() {
        String dbStatus = adminJdbcTemplate.queryForObject(
                "SELECT housekeeping_status FROM rooms WHERE id = ?",
                String.class,
                roomIdA1
        );
        assertThat(dbStatus).isEqualTo("CLEAN");
    }

    // =========================================================================
    // Test 3-4: Housekeeping State Transitions & Validations
    // =========================================================================

    @Test
    @DisplayName("Test 3: Authorized housekeeping transitions succeed")
    void test03_authorizedHousekeepingTransitions_succeed() throws Exception {
        // CLEAN -> DIRTY
        RoomHousekeepingUpdateRequest dirtyReq = new RoomHousekeepingUpdateRequest();
        dirtyReq.setHousekeepingStatus(HousekeepingStatus.DIRTY);

        mockMvc.perform(patch("/api/v1/rooms/" + roomIdA1 + "/housekeeping-status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dirtyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.housekeepingStatus").value("DIRTY"));

        // DIRTY -> CLEANING_IN_PROGRESS
        RoomHousekeepingUpdateRequest inProgressReq = new RoomHousekeepingUpdateRequest();
        inProgressReq.setHousekeepingStatus(HousekeepingStatus.CLEANING_IN_PROGRESS);

        mockMvc.perform(patch("/api/v1/rooms/" + roomIdA1 + "/housekeeping-status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(inProgressReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.housekeepingStatus").value("CLEANING_IN_PROGRESS"));

        // CLEANING_IN_PROGRESS -> CLEAN
        RoomHousekeepingUpdateRequest cleanReq = new RoomHousekeepingUpdateRequest();
        cleanReq.setHousekeepingStatus(HousekeepingStatus.CLEAN);

        mockMvc.perform(patch("/api/v1/rooms/" + roomIdA1 + "/housekeeping-status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cleanReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.housekeepingStatus").value("CLEAN"));

        // Direct confirmation: CLEAN -> DIRTY -> CLEAN
        mockMvc.perform(patch("/api/v1/rooms/" + roomIdA1 + "/housekeeping-status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dirtyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.housekeepingStatus").value("DIRTY"));

        mockMvc.perform(patch("/api/v1/rooms/" + roomIdA1 + "/housekeeping-status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cleanReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.housekeepingStatus").value("CLEAN"));
    }

    @Test
    @DisplayName("Test 4: Duplicate status update fails 400 Bad Request")
    void test04_duplicateStatusUpdate_fails() throws Exception {
        RoomHousekeepingUpdateRequest cleanReq = new RoomHousekeepingUpdateRequest();
        cleanReq.setHousekeepingStatus(HousekeepingStatus.CLEAN);

        // Room is already CLEAN
        mockMvc.perform(patch("/api/v1/rooms/" + roomIdA1 + "/housekeeping-status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cleanReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Room is already in housekeeping status: CLEAN")));
    }

    // =========================================================================
    // Test 5-8: Authorization and Anti-Enumeration
    // =========================================================================

    @Test
    @DisplayName("Test 5: Unauthenticated request gets 401 Unauthorized")
    void test05_unauthenticatedRequest_gets401() throws Exception {
        RoomHousekeepingUpdateRequest req = new RoomHousekeepingUpdateRequest();
        req.setHousekeepingStatus(HousekeepingStatus.DIRTY);

        mockMvc.perform(patch("/api/v1/rooms/" + roomIdA1 + "/housekeeping-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Test 6: VIEWER and ACCOUNTANT get 403 Forbidden")
    void test06_viewerAndAccountant_get403() throws Exception {
        RoomHousekeepingUpdateRequest req = new RoomHousekeepingUpdateRequest();
        req.setHousekeepingStatus(HousekeepingStatus.DIRTY);

        // VIEWER
        mockMvc.perform(patch("/api/v1/rooms/" + roomIdA1 + "/housekeeping-status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        // ACCOUNTANT
        mockMvc.perform(patch("/api/v1/rooms/" + roomIdA1 + "/housekeeping-status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Test 7: Foreign-property staff get 404 Not Found")
    void test07_foreignPropertyStaff_get404() throws Exception {
        RoomHousekeepingUpdateRequest req = new RoomHousekeepingUpdateRequest();
        req.setHousekeepingStatus(HousekeepingStatus.DIRTY);

        // Staff on Property B targets Property A room
        mockMvc.perform(patch("/api/v1/rooms/" + roomIdA1 + "/housekeeping-status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Test 8: RECEPTIONIST can update housekeeping within assigned property")
    void test08_receptionist_canUpdateHousekeepingWithinAssignedProperty() throws Exception {
        RoomHousekeepingUpdateRequest req = new RoomHousekeepingUpdateRequest();
        req.setHousekeepingStatus(HousekeepingStatus.DIRTY);

        mockMvc.perform(patch("/api/v1/rooms/" + roomIdA1 + "/housekeeping-status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.housekeepingStatus").value("DIRTY"));
    }

    // =========================================================================
    // Test 9-12: Check-In Readiness Rules
    // =========================================================================

    @Test
    @DisplayName("Test 9: Check-in succeeds with CLEAN assigned rooms")
    void test09_checkInSucceeds_withCleanAssignedRooms() throws Exception {
        UUID resId = createConfirmedReservation(propertyIdA, guestIdA, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2));
        UUID rrId = createReservationRoom(resId, roomTypeIdA, 1);
        assignRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));

        Room room = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(room.getStatus()).isEqualTo(RoomStatus.OCCUPIED);
        assertThat(room.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.CLEAN);
    }

    @Test
    @DisplayName("Test 10: Check-in fails with DIRTY assigned room")
    void test10_checkInFails_withDirtyAssignedRoom() throws Exception {
        Room r = roomRepository.findById(roomIdA1).orElseThrow();
        r.setHousekeepingStatus(HousekeepingStatus.DIRTY);
        roomRepository.saveAndFlush(r);

        UUID resId = createConfirmedReservation(propertyIdA, guestIdA, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2));
        UUID rrId = createReservationRoom(resId, roomTypeIdA, 1);
        assignRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Cannot check in: assigned room requires housekeeping.")));

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Test 11: Check-in fails with CLEANING_IN_PROGRESS assigned room")
    void test11_checkInFails_withCleaningInProgressAssignedRoom() throws Exception {
        Room r = roomRepository.findById(roomIdA1).orElseThrow();
        r.setHousekeepingStatus(HousekeepingStatus.CLEANING_IN_PROGRESS);
        roomRepository.saveAndFlush(r);

        UUID resId = createConfirmedReservation(propertyIdA, guestIdA, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2));
        UUID rrId = createReservationRoom(resId, roomTypeIdA, 1);
        assignRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Cannot check in: assigned room requires housekeeping.")));
    }

    @Test
    @DisplayName("Test 12: Multi-room check-in fails if even one required room is dirty")
    void test12_multiRoomCheckInFails_ifEvenOneRequiredRoomIsDirty() throws Exception {
        // Room A1 is CLEAN
        Room r1 = roomRepository.findById(roomIdA1).orElseThrow();
        r1.setHousekeepingStatus(HousekeepingStatus.CLEAN);
        roomRepository.saveAndFlush(r1);

        // Room A2 is DIRTY
        Room r2 = roomRepository.findById(roomIdA2).orElseThrow();
        r2.setHousekeepingStatus(HousekeepingStatus.DIRTY);
        roomRepository.saveAndFlush(r2);

        UUID resId = createConfirmedReservation(propertyIdA, guestIdA, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2));
        UUID rrId = createReservationRoom(resId, roomTypeIdA, 2);
        assignRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));
        assignRoom(rrId, roomIdA2, todayInPropZone, todayInPropZone.plusDays(2));

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Cannot check in: assigned room requires housekeeping.")));

        // Neither room becomes OCCUPIED
        assertThat(roomRepository.findById(roomIdA1).orElseThrow().getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(roomRepository.findById(roomIdA2).orElseThrow().getStatus()).isEqualTo(RoomStatus.AVAILABLE);
    }

    // =========================================================================
    // Test 13-14: Concurrency & Rollback Safety
    // =========================================================================

    @Test
    @DisplayName("Test 13: Concurrent cleaning-status change and check-in are serialized")
    void test13_concurrentCleaningStatusChangeAndCheckIn_serialized() throws Exception {
        UUID resId = createConfirmedReservation(propertyIdA, guestIdA, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2));
        UUID rrId = createReservationRoom(resId, roomTypeIdA, 1);
        assignRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicReference<String> checkInResult = new AtomicReference<>("NONE");
        AtomicReference<String> markDirtyResult = new AtomicReference<>("NONE");

        // Thread 1: Check-in
        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.execute(status -> {
                    reservationService.checkInReservation(resId);
                    return null;
                });
                checkInResult.set("SUCCESS");
            } catch (Exception e) {
                checkInResult.set("FAILED: " + e.getMessage());
            }
        });

        // Thread 2: Mark room DIRTY
        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.execute(status -> {
                    RoomHousekeepingUpdateRequest req = new RoomHousekeepingUpdateRequest();
                    req.setHousekeepingStatus(HousekeepingStatus.DIRTY);
                    roomService.updateHousekeepingStatus(roomIdA1, req);
                    return null;
                });
                markDirtyResult.set("SUCCESS");
            } catch (Exception e) {
                markDirtyResult.set("FAILED: " + e.getMessage());
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        Room room = roomRepository.findById(roomIdA1).orElseThrow();

        if ("SUCCESS".equals(checkInResult.get())) {
            // Check-in won: room is OCCUPIED, res is CHECKED_IN; markDirty failed because room is actively checked-in
            assertThat(res.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
            assertThat(room.getStatus()).isEqualTo(RoomStatus.OCCUPIED);
            assertThat(markDirtyResult.get()).contains("Cannot mark room as DIRTY while it is occupied by an active checked-in reservation");
        } else {
            // Mark DIRTY won: room is DIRTY; check-in failed because room requires housekeeping
            assertThat(markDirtyResult.get()).isEqualTo("SUCCESS");
            assertThat(room.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);
            assertThat(checkInResult.get()).contains("Cannot check in: assigned room requires housekeeping.");
            assertThat(res.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        }
    }

    @Test
    @DisplayName("Test 14: No partial check-in state after failure")
    void test14_noPartialCheckInState_afterFailure() throws Exception {
        Room r = roomRepository.findById(roomIdA1).orElseThrow();
        r.setHousekeepingStatus(HousekeepingStatus.DIRTY);
        roomRepository.saveAndFlush(r);

        UUID resId = createConfirmedReservation(propertyIdA, guestIdA, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2));
        UUID rrId = createReservationRoom(resId, roomTypeIdA, 1);
        assignRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest());

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(res.isCheckoutDue()).isFalse();
        assertThat(res.getCheckoutDueAt()).isNull();

        Room room = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(room.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(room.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);
    }

    // =========================================================================
    // Test 15-16: Future Availability & Existing Protections
    // =========================================================================

    @Test
    @DisplayName("Test 15: Future room-type availability is unaffected by dirty status")
    void test15_futureRoomTypeAvailability_unaffectedByDirtyStatus() {
        // Mark room DIRTY
        Room r = roomRepository.findById(roomIdA1).orElseThrow();
        r.setHousekeepingStatus(HousekeepingStatus.DIRTY);
        roomRepository.saveAndFlush(r);

        // Reservation creation for future dates should succeed
        ReservationCreateRequest req = new ReservationCreateRequest();
        req.setPropertyId(propertyIdA);
        req.setPrimaryGuestId(guestIdA);
        req.setBookingSourceId(bookingSourceDirectId);
        req.setCheckInDate(todayInPropZone.plusDays(10));
        req.setCheckOutDate(todayInPropZone.plusDays(12));
        req.setAdults(2);

        ReservationRoomCreateRequest rrReq = new ReservationRoomCreateRequest();
        rrReq.setRoomTypeId(roomTypeIdA);
        rrReq.setQuantity(1);
        rrReq.setUnitPrice(new BigDecimal("2500.00"));
        req.setReservationRooms(List.of(rrReq));

        ReservationResponse response = reservationService.createReservation(req);
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    @DisplayName("Test 16: Existing room-status and deactivation protections remain intact")
    void test16_existingRoomStatusAndDeactivationProtections_remainIntact() {
        // Create checked-in reservation
        Room r = roomRepository.findById(roomIdA1).orElseThrow();
        r.setHousekeepingStatus(HousekeepingStatus.CLEAN);
        roomRepository.saveAndFlush(r);

        UUID resId = createConfirmedReservation(propertyIdA, guestIdA, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2));
        UUID rrId = createReservationRoom(resId, roomTypeIdA, 1);
        assignRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        reservationService.checkInReservation(resId);

        // 1. Marking room AVAILABLE while checked-in fails
        org.junit.jupiter.api.Assertions.assertThrows(BusinessValidationException.class, () -> {
            com.homestay.pms_backend.dto.request.RoomStatusUpdateRequest statusReq =
                    new com.homestay.pms_backend.dto.request.RoomStatusUpdateRequest();
            statusReq.setStatus(RoomStatus.AVAILABLE);
            roomService.updateRoomStatus(roomIdA1, statusReq);
        });

        // 2. Deactivating room while checked-in fails
        org.junit.jupiter.api.Assertions.assertThrows(BusinessValidationException.class, () -> {
            roomService.deactivateRoom(roomIdA1);
        });

        // 3. Marking room DIRTY while checked-in fails
        org.junit.jupiter.api.Assertions.assertThrows(BusinessValidationException.class, () -> {
            RoomHousekeepingUpdateRequest hkReq = new RoomHousekeepingUpdateRequest();
            hkReq.setHousekeepingStatus(HousekeepingStatus.DIRTY);
            roomService.updateHousekeepingStatus(roomIdA1, hkReq);
        });
    }
}
