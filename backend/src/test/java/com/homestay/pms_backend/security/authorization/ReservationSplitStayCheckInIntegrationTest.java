package com.homestay.pms_backend.security.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homestay.pms_backend.entity.*;
import com.homestay.pms_backend.enums.*;
import com.homestay.pms_backend.repository.*;
import com.homestay.pms_backend.security.AuthenticatedStaff;
import com.homestay.pms_backend.security.AuthenticatedStaffPrincipal;
import com.homestay.pms_backend.security.jwt.AccessTokenDto;
import com.homestay.pms_backend.security.jwt.AccessTokenService;
import com.homestay.pms_backend.service.GuestPropertyAssociationService;
import com.homestay.pms_backend.service.ReservationService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IsolatedDatabaseTest
@TestPropertySource(properties = {
        "auth.jwt.enforcement.enabled=true",
        "TEST_AUTH_JWT_ENFORCEMENT_ENABLED=true"
})
@DisplayName("PMS Phase 2 — Step 3.4.3.6F.2: Split-Stay Restriction and Checkout Metadata Foundation Integration Tests")
class ReservationSplitStayCheckInIntegrationTest {

    private static final String TEST_DOMAIN = "@splitstay-test.com";
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
    private GuestPropertyAssociationService guestPropertyAssociationService;

    @Autowired
    private Environment environment;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private MockMvc mockMvc;
    private JdbcTemplate adminJdbcTemplate;

    private UUID propertyIdA;
    private UUID propertyIdB;
    private UUID roomTypeIdA1;
    private UUID roomTypeIdA2;
    private UUID roomIdA1;
    private UUID roomIdA2;
    private UUID roomIdA3;
    private UUID guestId1;
    private UUID guestId2;
    private UUID bookingSourceDirectId;

    private String superAdminToken;
    private String propAdminTokenA;
    private String receptionistTokenA;
    private String propAdminTokenB;
    private String viewerTokenA;

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

        // 1. Seed Properties
        propertyIdA = getOrCreateProperty("SplitStay Property Alpha", "PROP_SPLIT_A", PROPERTY_TZ);
        propertyIdB = getOrCreateProperty("SplitStay Property Beta", "PROP_SPLIT_B", PROPERTY_TZ);

        // 2. Seed Room Types
        roomTypeIdA1 = getOrCreateRoomType(propertyIdA, "Deluxe Suite Split", "RT_SPLIT_A1");
        roomTypeIdA2 = getOrCreateRoomType(propertyIdA, "Standard Room Split", "RT_SPLIT_A2");

        // 3. Seed Physical Rooms
        roomIdA1 = getOrCreateRoom(propertyIdA, roomTypeIdA1, "SPLIT-101");
        roomIdA2 = getOrCreateRoom(propertyIdA, roomTypeIdA1, "SPLIT-102");
        roomIdA3 = getOrCreateRoom(propertyIdA, roomTypeIdA2, "SPLIT-103");

        // 4. Seed Guests
        guestId1 = getOrCreateGuest("Split Guest One", "guest1-split@test.com", "+919876543301");
        guestId2 = getOrCreateGuest("Split Guest Two", "guest2-split@test.com", "+919876543302");
        guestPropertyAssociationService.createRegistrationAssociation(guestId1, propertyIdA);
        guestPropertyAssociationService.createRegistrationAssociation(guestId2, propertyIdB);

        // 5. Booking Source
        bookingSourceDirectId = bookingSourceRepository.findByCode("DIRECT")
                .orElseThrow(() -> new IllegalStateException("DIRECT booking source not found"))
                .getId();

        // 6. Tokens
        superAdminToken = createStaffToken("superadmin" + TEST_DOMAIN, StaffRole.SUPER_ADMIN, null);
        UUID staffIdAdminA = createTestStaff("propadmin-a" + TEST_DOMAIN);
        assignStaffPropertyRole(staffIdAdminA, propertyIdA, StaffRole.PROPERTY_ADMIN);
        propAdminTokenA = generateTokenForStaff(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);

        receptionistTokenA = createStaffToken("receptionist-a" + TEST_DOMAIN, StaffRole.RECEPTIONIST, propertyIdA);
        propAdminTokenB = createStaffToken("propadmin-b" + TEST_DOMAIN, StaffRole.PROPERTY_ADMIN, propertyIdB);
        viewerTokenA = createStaffToken("viewer-a" + TEST_DOMAIN, StaffRole.VIEWER, propertyIdA);
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
                    "DELETE FROM room_assignments WHERE room_id IN (SELECT id FROM rooms WHERE room_number IN ('SPLIT-101', 'SPLIT-102', 'SPLIT-103')); " +
                    "DELETE FROM reservation_rooms WHERE room_type_id IN (SELECT id FROM room_types WHERE code IN ('RT_SPLIT_A1', 'RT_SPLIT_A2')); " +
                    "DELETE FROM reservations WHERE property_id IN (SELECT id FROM properties WHERE code IN ('PROP_SPLIT_A', 'PROP_SPLIT_B')); " +
                    "DELETE FROM rooms WHERE room_number IN ('SPLIT-101', 'SPLIT-102', 'SPLIT-103'); " +
                    "DELETE FROM room_types WHERE code IN ('RT_SPLIT_A1', 'RT_SPLIT_A2'); " +
                    "DELETE FROM guest_property_associations WHERE guest_id IN (SELECT id FROM guests WHERE email IN ('guest1-split@test.com', 'guest2-split@test.com')); " +
                    "DELETE FROM guests WHERE email IN ('guest1-split@test.com', 'guest2-split@test.com'); " +
                    "DELETE FROM properties WHERE code IN ('PROP_SPLIT_A', 'PROP_SPLIT_B');"
            );
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID getOrCreateProperty(String name, String code, String timezone) {
        return propertyRepository.findByCode(code)
                .map(Property::getId)
                .orElseGet(() -> {
                    Property prop = new Property();
                    prop.setName(name);
                    prop.setCode(code);
                    prop.setDescription("Integration test property for split-stay workflow");
                    prop.setAddressLine1("123 SplitStay Lane");
                    prop.setCity("Test City");
                    prop.setState("Test State");
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
                    rt.setDescription("SplitStay Room Type");
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
        g.setIdNumber("P98765432");
        g.setActive(true);
        return guestRepository.save(g).getId();
    }

    private UUID createTestReservation(UUID propertyId, UUID guestId, UUID bookingSourceId,
                                       LocalDate checkIn, LocalDate checkOut, ReservationStatus status) {
        Reservation res = new Reservation();
        res.setPropertyId(propertyId);
        res.setPrimaryGuestId(guestId);
        res.setBookingSourceId(bookingSourceId);
        res.setCheckInDate(checkIn);
        res.setCheckOutDate(checkOut);
        res.setAdults(2);
        res.setChildren(0);
        res.setInfants(0);
        res.setStatus(status);
        res.setCheckoutDue(false);
        return reservationRepository.saveAndFlush(res).getId();
    }

    private UUID createReservationRoom(UUID reservationId, UUID roomTypeId, int quantity) {
        ReservationRoom rr = new ReservationRoom();
        rr.setReservationId(reservationId);
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(quantity);
        rr.setCancelledQuantity(0);
        rr.setUnitPrice(new BigDecimal("2000.00"));
        rr.setSubtotal(new BigDecimal("2000.00").multiply(BigDecimal.valueOf(quantity)));
        rr.setStatus(ReservationRoomStatus.CONFIRMED);
        return reservationRoomRepository.saveAndFlush(rr).getId();
    }

    private UUID assignRoomToReservationRoom(UUID reservationRoomId, UUID roomId,
                                             LocalDate start, LocalDate end, RoomAssignmentStatus status) {
        RoomAssignment ra = new RoomAssignment();
        ra.setReservationRoomId(reservationRoomId);
        ra.setRoomId(roomId);
        ra.setAssignedFrom(start);
        ra.setAssignedUntil(end);
        ra.setStatus(status);
        return roomAssignmentRepository.saveAndFlush(ra).getId();
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

    // =========================================================================
    // Test 1: Full-Stay Single-Room Check-In Succeeds
    // =========================================================================
    @Test
    @DisplayName("Scenario 1: Full-stay single-room check-in succeeds with 200 OK and marks room OCCUPIED")
    void test01_fullStaySingleRoomCheckIn_succeeds() throws Exception {
        LocalDate checkIn = todayInPropZone;
        LocalDate checkOut = todayInPropZone.plusDays(2);

        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId, checkIn, checkOut, ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, checkIn, checkOut, RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(resId.toString()))
                .andExpect(jsonPath("$.status").value("CHECKED_IN"))
                .andExpect(jsonPath("$.checkoutDue").value(false))
                .andExpect(jsonPath("$.checkoutDueAt").isNotEmpty())
                .andExpect(jsonPath("$.actualCheckedOutAt").isEmpty())
                .andExpect(jsonPath("$.checkedOutByStaffUserId").isEmpty());

        Room updatedRoom = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(updatedRoom.getStatus()).isEqualTo(RoomStatus.OCCUPIED);
        assertThat(updatedRoom.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.CLEAN);

        Reservation updatedRes = reservationRepository.findById(resId).orElseThrow();
        assertThat(updatedRes.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    // =========================================================================
    // Test 2: Full-Stay Simultaneous Multi-Room Check-In Succeeds
    // =========================================================================
    @Test
    @DisplayName("Scenario 2: Full-stay simultaneous multi-room check-in succeeds with both rooms marked OCCUPIED")
    void test02_fullStayMultiRoomCheckIn_succeeds() throws Exception {
        LocalDate checkIn = todayInPropZone;
        LocalDate checkOut = todayInPropZone.plusDays(3);

        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId, checkIn, checkOut, ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 2);
        assignRoomToReservationRoom(rrId, roomIdA1, checkIn, checkOut, RoomAssignmentStatus.ASSIGNED);
        assignRoomToReservationRoom(rrId, roomIdA2, checkIn, checkOut, RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(resId.toString()))
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));

        Room room1 = roomRepository.findById(roomIdA1).orElseThrow();
        Room room2 = roomRepository.findById(roomIdA2).orElseThrow();
        assertThat(room1.getStatus()).isEqualTo(RoomStatus.OCCUPIED);
        assertThat(room2.getStatus()).isEqualTo(RoomStatus.OCCUPIED);
    }

    // =========================================================================
    // Test 3: Partial-Date Split-Stay Check-In Rejection
    // =========================================================================
    @Test
    @DisplayName("Scenario 3: Split-stay check-in across different physical rooms rejected with 400 Bad Request")
    void test03_splitStayCheckInRejection_differentRoomsAcrossNights() throws Exception {
        LocalDate checkIn = todayInPropZone;
        LocalDate checkOut = todayInPropZone.plusDays(3);

        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId, checkIn, checkOut, ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);

        // Split stay: Room 101 for nights 1-2, Room 102 for night 3
        assignRoomToReservationRoom(rrId, roomIdA1, checkIn, checkIn.plusDays(2), RoomAssignmentStatus.ASSIGNED);
        assignRoomToReservationRoom(rrId, roomIdA2, checkIn.plusDays(2), checkOut, RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Split-stay reservations (different physical rooms across stay dates) are not supported")))
                .andExpect(jsonPath("$.message").value(containsString("require consecutive reservations or a future room-transfer workflow")));

        // Invariant: Neither room is mutated
        Room room1 = roomRepository.findById(roomIdA1).orElseThrow();
        Room room2 = roomRepository.findById(roomIdA2).orElseThrow();
        assertThat(room1.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(room2.getStatus()).isEqualTo(RoomStatus.AVAILABLE);

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    // =========================================================================
    // Test 4: Partial-Date Assignment Single Room Rejection
    // =========================================================================
    @Test
    @DisplayName("Scenario 4: Partial-date assignment leaving subsequent nights unassigned rejected with 400 Bad Request")
    void test04_partialDateAssignment_singleRoomRejection() throws Exception {
        LocalDate checkIn = todayInPropZone;
        LocalDate checkOut = todayInPropZone.plusDays(2);

        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId, checkIn, checkOut, ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);

        // Partial assignment: only night 1 assigned, night 2 unassigned
        assignRoomToReservationRoom(rrId, roomIdA1, checkIn, checkIn.plusDays(1), RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Split-stay reservations (different physical rooms across stay dates) are not supported")));

        Room room1 = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(room1.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    // =========================================================================
    // Test 5: Multiple Partial Assignment Records for Same Room Rejected
    // =========================================================================
    @Test
    @DisplayName("Scenario 5: Multiple partial assignment records for the same physical room rejected with 400 Bad Request")
    void test05_multiplePartialAssignmentsSamePhysicalRoomRejection() throws Exception {
        LocalDate checkIn = todayInPropZone;
        LocalDate checkOut = todayInPropZone.plusDays(2);

        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId, checkIn, checkOut, ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);

        // Two partial assignments for the SAME room (Day 1 and Day 2 separate records)
        assignRoomToReservationRoom(rrId, roomIdA1, checkIn, checkIn.plusDays(1), RoomAssignmentStatus.ASSIGNED);
        assignRoomToReservationRoom(rrId, roomIdA1, checkIn.plusDays(1), checkOut, RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Split-stay reservations (different physical rooms across stay dates) are not supported")));

        Room room1 = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(room1.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
    }

    // =========================================================================
    // Test 6: Fully Cancelled ReservationRoom Quantities Excluded
    // =========================================================================
    @Test
    @DisplayName("Scenario 6: Fully cancelled ReservationRoom quantities are excluded from check-in requirement")
    void test06_fullyCancelledReservationRoom_excludedFromRequirement() throws Exception {
        LocalDate checkIn = todayInPropZone;
        LocalDate checkOut = todayInPropZone.plusDays(2);

        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId, checkIn, checkOut, ReservationStatus.CONFIRMED);

        // Active RR1: qty 1, confirmed
        UUID rrIdActive = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrIdActive, roomIdA1, checkIn, checkOut, RoomAssignmentStatus.ASSIGNED);

        // Cancelled RR2: qty 1, cancelled
        ReservationRoom rrCancelled = new ReservationRoom();
        rrCancelled.setReservationId(resId);
        rrCancelled.setRoomTypeId(roomTypeIdA2);
        rrCancelled.setQuantity(1);
        rrCancelled.setCancelledQuantity(1);
        rrCancelled.setUnitPrice(new BigDecimal("1000.00"));
        rrCancelled.setSubtotal(BigDecimal.ZERO);
        rrCancelled.setStatus(ReservationRoomStatus.CANCELLED);
        reservationRoomRepository.saveAndFlush(rrCancelled);

        // Check-in succeeds because only rrIdActive requires physical room
        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));

        assertThat(roomRepository.findById(roomIdA1).orElseThrow().getStatus()).isEqualTo(RoomStatus.OCCUPIED);
    }

    // =========================================================================
    // Test 7: No Room-Status or Reservation Mutation After Rejection
    // =========================================================================
    @Test
    @DisplayName("Scenario 7: Database transaction rolls back completely on split-stay rejection; zero side effects")
    void test07_rejectionPreservesAllDatabaseState_noMutations() throws Exception {
        LocalDate checkIn = todayInPropZone;
        LocalDate checkOut = todayInPropZone.plusDays(2);

        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId, checkIn, checkOut, ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, checkIn, checkIn.plusDays(1), RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isBadRequest());

        // Direct SQL assertions
        Map<String, Object> resRow = adminJdbcTemplate.queryForMap(
                "SELECT status, checkout_due, checkout_due_at, actual_checked_out_at, checked_out_by_staff_user_id FROM reservations WHERE id = ?",
                resId
        );
        assertThat(resRow.get("status")).isEqualTo("CONFIRMED");
        assertThat(resRow.get("checkout_due")).isEqualTo(false);
        assertThat(resRow.get("checkout_due_at")).isNull();
        assertThat(resRow.get("actual_checked_out_at")).isNull();
        assertThat(resRow.get("checked_out_by_staff_user_id")).isNull();

        Map<String, Object> roomRow = adminJdbcTemplate.queryForMap(
                "SELECT status, housekeeping_status FROM rooms WHERE id = ?",
                roomIdA1
        );
        assertThat(roomRow.get("status")).isEqualTo("AVAILABLE");
        assertThat(roomRow.get("housekeeping_status")).isEqualTo("CLEAN");
    }

    // =========================================================================
    // Test 8: Existing Clean-Room Enforcement Preserved
    // =========================================================================
    @Test
    @DisplayName("Scenario 8: Existing clean-room check-in guard preserved; DIRTY room rejects check-in")
    void test08_existingCleanRoomEnforcementPreserved() throws Exception {
        LocalDate checkIn = todayInPropZone;
        LocalDate checkOut = todayInPropZone.plusDays(2);

        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId, checkIn, checkOut, ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, checkIn, checkOut, RoomAssignmentStatus.ASSIGNED);

        // Mark room DIRTY
        Room room = roomRepository.findById(roomIdA1).orElseThrow();
        room.setHousekeepingStatus(HousekeepingStatus.DIRTY);
        roomRepository.saveAndFlush(room);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Cannot check in: assigned room requires housekeeping.")));

        // Room remains DIRTY and AVAILABLE
        Room roomAfter = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(roomAfter.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(roomAfter.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);
    }

    // =========================================================================
    // Test 9: Existing Room Authorization Preserved
    // =========================================================================
    @Test
    @DisplayName("Scenario 9: Multi-tenant RBAC preserved: foreign property 404, unauthenticated 401, viewer 403")
    void test09_existingRoomAuthorizationPreserved() throws Exception {
        LocalDate checkIn = todayInPropZone;
        LocalDate checkOut = todayInPropZone.plusDays(2);

        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId, checkIn, checkOut, ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, checkIn, checkOut, RoomAssignmentStatus.ASSIGNED);

        // 1. Unauthenticated -> 401
        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        // 2. Foreign Property Staff -> 404 (Anti-enumeration defense)
        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenB)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        // 3. Viewer Role -> 403 Forbidden
        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Test 10: V12 Migration and Schema Integrity
    // =========================================================================
    @Test
    @DisplayName("Scenario 10: V12 migration schema integrity verified on disposable PostgreSQL 16")
    void test10_v12MigrationAndSchemaIntegrity() {
        // Verify actual_checked_out_at column
        Map<String, Object> col1 = adminJdbcTemplate.queryForMap(
                """
                SELECT data_type, is_nullable
                FROM information_schema.columns
                WHERE table_name = 'reservations' AND column_name = 'actual_checked_out_at'
                """
        );
        assertThat(col1.get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(col1.get("is_nullable")).isEqualTo("YES");

        // Verify checked_out_by_staff_user_id column
        Map<String, Object> col2 = adminJdbcTemplate.queryForMap(
                """
                SELECT data_type, is_nullable
                FROM information_schema.columns
                WHERE table_name = 'reservations' AND column_name = 'checked_out_by_staff_user_id'
                """
        );
        assertThat(col2.get("data_type")).isEqualTo("uuid");
        assertThat(col2.get("is_nullable")).isEqualTo("YES");

        // Verify foreign key constraint
        List<Map<String, Object>> fk = adminJdbcTemplate.queryForList(
                """
                SELECT tc.constraint_name, rc.delete_rule
                FROM information_schema.table_constraints tc
                JOIN information_schema.referential_constraints rc
                  ON tc.constraint_name = rc.constraint_name
                WHERE tc.table_name = 'reservations'
                  AND tc.constraint_name = 'fk_reservations_checked_out_by'
                """
        );
        assertThat(fk).hasSize(1);
        assertThat(fk.get(0).get("delete_rule")).isEqualTo("SET NULL");
    }

    // =========================================================================
    // Test 11: Existing Reservations Have Nullable Metadata and Map Correctly
    // =========================================================================
    @Test
    @DisplayName("Scenario 11: Reservation response maps nullable checkout metadata correctly")
    void test11_existingReservationsHaveNullableMetadataAndMapCorrectly() throws Exception {
        LocalDate checkIn = todayInPropZone.plusDays(1);
        LocalDate checkOut = todayInPropZone.plusDays(3);

        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId, checkIn, checkOut, ReservationStatus.CONFIRMED);

        mockMvc.perform(get("/api/v1/reservations/" + resId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(resId.toString()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.actualCheckedOutAt").isEmpty())
                .andExpect(jsonPath("$.checkedOutByStaffUserId").isEmpty());
    }

    // =========================================================================
    // Test 12: Concurrent Check-In Race Condition Safety
    // =========================================================================
    @Test
    @DisplayName("Scenario 12: Concurrent check-in race conditions serialize cleanly under pessimistic lock")
    void test12_concurrentCheckInRaceConditionSafety() throws Exception {
        LocalDate checkIn = todayInPropZone;
        LocalDate checkOut = todayInPropZone.plusDays(2);

        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId, checkIn, checkOut, ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, checkIn, checkOut, RoomAssignmentStatus.ASSIGNED);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                                    .contentType(MediaType.APPLICATION_JSON))
                            .andDo(result -> {
                                int status = result.getResponse().getStatus();
                                if (status == 200) {
                                    successCount.incrementAndGet();
                                } else if (status == 400) {
                                    conflictCount.incrementAndGet();
                                }
                            });
                } catch (Exception e) {
                    // unexpected
                }
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
        Room room = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(room.getStatus()).isEqualTo(RoomStatus.OCCUPIED);
    }

    // =========================================================================
    // Test 13: Simultaneous Multi-Room With Different Room Types
    // =========================================================================
    @Test
    @DisplayName("Scenario 13: Simultaneous multi-room check-in with distinct room types succeeds")
    void test13_simultaneousMultiRoomWithDifferentRoomTypes() throws Exception {
        LocalDate checkIn = todayInPropZone;
        LocalDate checkOut = todayInPropZone.plusDays(2);

        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId, checkIn, checkOut, ReservationStatus.CONFIRMED);

        UUID rrId1 = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId1, roomIdA1, checkIn, checkOut, RoomAssignmentStatus.ASSIGNED);

        UUID rrId2 = createReservationRoom(resId, roomTypeIdA2, 1);
        assignRoomToReservationRoom(rrId2, roomIdA3, checkIn, checkOut, RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));

        Room r1 = roomRepository.findById(roomIdA1).orElseThrow();
        Room r3 = roomRepository.findById(roomIdA3).orElseThrow();
        assertThat(r1.getStatus()).isEqualTo(RoomStatus.OCCUPIED);
        assertThat(r3.getStatus()).isEqualTo(RoomStatus.OCCUPIED);
    }

    // =========================================================================
    // Test 14: Incomplete Physical Room Assignment Message Preserved
    // =========================================================================
    @Test
    @DisplayName("Scenario 14: Incomplete physical room assignment (0 assigned) rejects with clear error message")
    void test14_incompletePhysicalRoomAssignment_messagePreserved() throws Exception {
        LocalDate checkIn = todayInPropZone;
        LocalDate checkOut = todayInPropZone.plusDays(2);

        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId, checkIn, checkOut, ReservationStatus.CONFIRMED);
        createReservationRoom(resId, roomTypeIdA1, 1); // 0 room assignments

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Incomplete physical room assignment for room type 'Deluxe Suite Split'")));
    }
}
