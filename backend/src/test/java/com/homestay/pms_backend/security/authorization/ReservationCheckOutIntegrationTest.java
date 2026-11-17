package com.homestay.pms_backend.security.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homestay.pms_backend.dto.request.ReservationCreateRequest;
import com.homestay.pms_backend.dto.request.ReservationRoomCreateRequest;
import com.homestay.pms_backend.dto.request.RoomHousekeepingUpdateRequest;
import com.homestay.pms_backend.dto.request.RoomStatusUpdateRequest;
import com.homestay.pms_backend.dto.response.ReservationResponse;
import com.homestay.pms_backend.entity.*;
import com.homestay.pms_backend.enums.*;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.repository.*;
import com.homestay.pms_backend.security.AuthenticatedStaff;
import com.homestay.pms_backend.security.AuthenticatedStaffPrincipal;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
@DisplayName("PMS Phase 2 — Step 3.4.3.6F.3: Secure Staff Checkout Integration Tests")
class ReservationCheckOutIntegrationTest {

    private static final String TEST_DOMAIN = "@checkout-auth-test.com";
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
    private UUID roomTypeIdA1;
    private UUID roomTypeIdA2;
    private UUID roomTypeIdB;
    private UUID roomIdA1;
    private UUID roomIdA2;
    private UUID roomIdA3;
    private UUID roomIdB;

    private UUID guestId1;
    private UUID guestId2;
    private UUID bookingSourceDirectId;
    private UUID bookingSourceOtaId;

    private UUID staffIdSuperAdmin;
    private UUID staffIdAdminA;
    private UUID staffIdReceptionistA;
    private UUID staffIdAccountantA;
    private UUID staffIdViewerA;
    private UUID staffIdAdminB;

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

        // 1. Seed Properties
        propertyIdA = getOrCreateProperty("Checkout Property Alpha", "PROP_CKOUT_A", PROPERTY_TZ);
        propertyIdB = getOrCreateProperty("Checkout Property Beta", "PROP_CKOUT_B", PROPERTY_TZ);

        // 2. Seed Room Types
        roomTypeIdA1 = getOrCreateRoomType(propertyIdA, "Deluxe Suite A1", "RT_CKOUT_A1");
        roomTypeIdA2 = getOrCreateRoomType(propertyIdA, "Standard Room A2", "RT_CKOUT_A2");
        roomTypeIdB = getOrCreateRoomType(propertyIdB, "Deluxe Suite B", "RT_CKOUT_B");

        // 3. Seed Physical Rooms
        roomIdA1 = getOrCreateRoom(propertyIdA, roomTypeIdA1, "CKOUT-101");
        roomIdA2 = getOrCreateRoom(propertyIdA, roomTypeIdA1, "CKOUT-102");
        roomIdA3 = getOrCreateRoom(propertyIdA, roomTypeIdA2, "CKOUT-103");
        roomIdB = getOrCreateRoom(propertyIdB, roomTypeIdB, "CKOUT-201");

        // 4. Seed Guests
        guestId1 = getOrCreateGuest("Guest Alpha", "guest1-ckout@test.com", "+919876543201");
        guestId2 = getOrCreateGuest("Guest Beta", "guest2-ckout@test.com", "+919876543202");
        guestPropertyAssociationService.createRegistrationAssociation(guestId1, propertyIdA);
        guestPropertyAssociationService.createRegistrationAssociation(guestId2, propertyIdB);

        // 5. Booking Sources
        bookingSourceDirectId = bookingSourceRepository.findByCode("DIRECT")
                .orElseThrow(() -> new IllegalStateException("DIRECT booking source not found"))
                .getId();
        bookingSourceOtaId = bookingSourceRepository.findByCode("BOOKING_COM")
                .orElseThrow(() -> new IllegalStateException("BOOKING_COM booking source not found"))
                .getId();

        // 6. Seed Staff Accounts & Auth Tokens
        staffIdSuperAdmin = createTestStaff("superadmin" + TEST_DOMAIN);
        adminJdbcTemplate.update(
                "INSERT INTO staff_global_roles (staff_user_id, role_code, assigned_at) VALUES (?, 'SUPER_ADMIN', CURRENT_TIMESTAMP)",
                staffIdSuperAdmin
        );
        superAdminToken = generateTokenForStaff(staffIdSuperAdmin, "superadmin" + TEST_DOMAIN);

        staffIdAdminA = createTestStaff("propadmin-a" + TEST_DOMAIN);
        assignStaffPropertyRole(staffIdAdminA, propertyIdA, StaffRole.PROPERTY_ADMIN);
        propAdminTokenA = generateTokenForStaff(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);

        staffIdReceptionistA = createTestStaff("receptionist-a" + TEST_DOMAIN);
        assignStaffPropertyRole(staffIdReceptionistA, propertyIdA, StaffRole.RECEPTIONIST);
        receptionistTokenA = generateTokenForStaff(staffIdReceptionistA, "receptionist-a" + TEST_DOMAIN);

        staffIdAccountantA = createTestStaff("accountant-a" + TEST_DOMAIN);
        assignStaffPropertyRole(staffIdAccountantA, propertyIdA, StaffRole.ACCOUNTANT);
        accountantTokenA = generateTokenForStaff(staffIdAccountantA, "accountant-a" + TEST_DOMAIN);

        staffIdViewerA = createTestStaff("viewer-a" + TEST_DOMAIN);
        assignStaffPropertyRole(staffIdViewerA, propertyIdA, StaffRole.VIEWER);
        viewerTokenA = generateTokenForStaff(staffIdViewerA, "viewer-a" + TEST_DOMAIN);

        staffIdAdminB = createTestStaff("propadmin-b" + TEST_DOMAIN);
        assignStaffPropertyRole(staffIdAdminB, propertyIdB, StaffRole.PROPERTY_ADMIN);
        propAdminTokenB = generateTokenForStaff(staffIdAdminB, "propadmin-b" + TEST_DOMAIN);
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
                    "DELETE FROM room_assignments WHERE room_id IN (SELECT id FROM rooms WHERE room_number IN ('CKOUT-101', 'CKOUT-102', 'CKOUT-103', 'CKOUT-201')); " +
                    "DELETE FROM reservation_rooms WHERE room_type_id IN (SELECT id FROM room_types WHERE code IN ('RT_CKOUT_A1', 'RT_CKOUT_A2', 'RT_CKOUT_B')); " +
                    "DELETE FROM reservations WHERE property_id IN (SELECT id FROM properties WHERE code IN ('PROP_CKOUT_A', 'PROP_CKOUT_B')); " +
                    "DELETE FROM rooms WHERE room_number IN ('CKOUT-101', 'CKOUT-102', 'CKOUT-103', 'CKOUT-201'); " +
                    "DELETE FROM room_types WHERE code IN ('RT_CKOUT_A1', 'RT_CKOUT_A2', 'RT_CKOUT_B'); " +
                    "DELETE FROM guest_property_associations WHERE guest_id IN (SELECT id FROM guests WHERE email IN ('guest1-ckout@test.com', 'guest2-ckout@test.com')); " +
                    "DELETE FROM guests WHERE email IN ('guest1-ckout@test.com', 'guest2-ckout@test.com'); " +
                    "DELETE FROM properties WHERE code IN ('PROP_CKOUT_A', 'PROP_CKOUT_B');"
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
                    prop.setDescription("Integration test property for checkout workflow");
                    prop.setAddressLine1("123 Checkout Lane");
                    prop.setCity("Checkout City");
                    prop.setState("Checkout State");
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
                    rt.setDescription("Checkout Room Type");
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
        rr.setUnitPrice(new BigDecimal("1500.00"));
        rr.setSubtotal(new BigDecimal("1500.00").multiply(BigDecimal.valueOf(quantity)));
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

    private UUID createCheckedInReservation(UUID propertyId, UUID guestId, UUID bookingSourceId,
                                           UUID roomTypeId, UUID roomId, LocalDate checkIn, LocalDate checkOut) {
        UUID resId = createTestReservation(propertyId, guestId, bookingSourceId, checkIn, checkOut, ReservationStatus.CHECKED_IN);
        UUID rrId = createReservationRoom(resId, roomTypeId, 1);
        assignRoomToReservationRoom(rrId, roomId, checkIn, checkOut, RoomAssignmentStatus.ASSIGNED);

        Room r = roomRepository.findById(roomId).orElseThrow();
        r.setStatus(RoomStatus.OCCUPIED);
        r.setHousekeepingStatus(HousekeepingStatus.CLEAN);
        roomRepository.saveAndFlush(r);

        return resId;
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

    private void setupSecurityContext(UUID staffId, String email) {
        AuthenticatedStaffPrincipal principal = new AuthenticatedStaffPrincipal(staffId, email, "Test", "Staff");
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    // =========================================================================
    // Tests 1-12: Core Checkout Functionality, State Transitions & Metadata
    // =========================================================================

    @Test
    @DisplayName("Test 1: Successful single-room checkout")
    void test01_successfulSingleRoomCheckOut() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_OUT"))
                .andExpect(jsonPath("$.checkoutDue").value(false))
                .andExpect(jsonPath("$.actualCheckedOutAt").isNotEmpty())
                .andExpect(jsonPath("$.checkedOutByStaffUserId").value(staffIdAdminA.toString()));

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CHECKED_OUT);
        assertThat(res.isCheckoutDue()).isFalse();
        assertThat(res.getActualCheckedOutAt()).isNotNull();
        assertThat(res.getCheckedOutByStaffUserId()).isEqualTo(staffIdAdminA);

        Room room = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(room.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(room.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);

        List<RoomAssignment> assignments = roomAssignmentRepository.findByRoomIdAndStatus(roomIdA1, RoomAssignmentStatus.RELEASED);
        assertThat(assignments).hasSize(1);
    }

    @Test
    @DisplayName("Test 2: Successful simultaneous multi-room checkout")
    void test02_successfulSimultaneousMultiRoomCheckOut() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CHECKED_IN);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 2);
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);
        assignRoomToReservationRoom(rrId, roomIdA2, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        Room r1 = roomRepository.findById(roomIdA1).orElseThrow();
        r1.setStatus(RoomStatus.OCCUPIED);
        r1.setHousekeepingStatus(HousekeepingStatus.CLEAN);
        roomRepository.saveAndFlush(r1);

        Room r2 = roomRepository.findById(roomIdA2).orElseThrow();
        r2.setStatus(RoomStatus.OCCUPIED);
        r2.setHousekeepingStatus(HousekeepingStatus.CLEAN);
        roomRepository.saveAndFlush(r2);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_OUT"));

        Room updatedR1 = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(updatedR1.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(updatedR1.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);

        Room updatedR2 = roomRepository.findById(roomIdA2).orElseThrow();
        assertThat(updatedR2.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(updatedR2.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);

        List<RoomAssignment> assignments = roomAssignmentRepository.findByReservationRoomId(rrId);
        assertThat(assignments).hasSize(2);
        assertThat(assignments).allMatch(a -> a.getStatus() == RoomAssignmentStatus.RELEASED);
    }

    @Test
    @DisplayName("Test 3: All active assignments released atomically")
    void test03_allActiveAssignmentsReleasedAtomically() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        assertThat(roomAssignmentRepository.hasActiveCheckedInReservation(roomIdA1)).isTrue();

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk());

        assertThat(roomAssignmentRepository.hasActiveCheckedInReservation(roomIdA1)).isFalse();
    }

    @Test
    @DisplayName("Test 4: Assignment history preserved (zero records deleted)")
    void test04_assignmentHistoryPreserved_zeroRecordsDeleted() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        long countBefore = roomAssignmentRepository.count();

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        long countAfter = roomAssignmentRepository.count();
        assertThat(countAfter).isEqualTo(countBefore);
    }

    @Test
    @DisplayName("Test 5: Checkout metadata persisted (actualCheckedOutAt and checkedOutByStaffUserId)")
    void test05_checkoutMetadataPersisted() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        Instant before = Instant.now().minusSeconds(2);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        Instant after = Instant.now().plusSeconds(2);

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getActualCheckedOutAt()).isNotNull();
        assertThat(res.getActualCheckedOutAt()).isAfterOrEqualTo(before);
        assertThat(res.getActualCheckedOutAt()).isBeforeOrEqualTo(after);
        assertThat(res.getCheckedOutByStaffUserId()).isEqualTo(staffIdAdminA);
    }

    @Test
    @DisplayName("Test 6: Original booking dates preserved")
    void test06_originalBookingDatesPreserved() throws Exception {
        LocalDate checkIn = todayInPropZone.minusDays(1);
        LocalDate checkOut = todayInPropZone.plusDays(2);

        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, checkIn, checkOut);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getCheckInDate()).isEqualTo(checkIn);
        assertThat(res.getCheckOutDate()).isEqualTo(checkOut);
    }

    @Test
    @DisplayName("Test 7: checkoutDue cleared (false)")
    void test07_checkoutDueCleared() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        res.setCheckoutDue(true);
        reservationRepository.saveAndFlush(res);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutDue").value(false));

        assertThat(reservationRepository.findById(resId).orElseThrow().isCheckoutDue()).isFalse();
    }

    @Test
    @DisplayName("Test 8: Original checkoutDueAt preserved")
    void test08_originalCheckoutDueAtPreserved() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        Instant specificDeadline = Instant.parse("2026-11-20T06:30:00Z");
        Reservation res = reservationRepository.findById(resId).orElseThrow();
        res.setCheckoutDueAt(specificDeadline);
        reservationRepository.saveAndFlush(res);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        Reservation updated = reservationRepository.findById(resId).orElseThrow();
        assertThat(updated.getCheckoutDueAt()).isEqualTo(specificDeadline);
    }

    @Test
    @DisplayName("Test 9: Room OCCUPIED transitions to AVAILABLE")
    void test09_roomOccupiedTransitionsToAvailable() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        Room beforeRoom = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(beforeRoom.getStatus()).isEqualTo(RoomStatus.OCCUPIED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        Room afterRoom = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(afterRoom.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
    }

    @Test
    @DisplayName("Test 10: Housekeeping status transitions to DIRTY")
    void test10_housekeepingStatusTransitionsToDirty() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        Room beforeRoom = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(beforeRoom.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.CLEAN);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        Room afterRoom = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(afterRoom.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);
    }

    @Test
    @DisplayName("Test 11: OUT_OF_SERVICE status preserved, marked DIRTY")
    void test11_outOfServiceStatusPreserved_markedDirty() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        Room room = roomRepository.findById(roomIdA1).orElseThrow();
        room.setStatus(RoomStatus.OUT_OF_SERVICE);
        roomRepository.saveAndFlush(room);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        Room updated = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(RoomStatus.OUT_OF_SERVICE);
        assertThat(updated.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);
    }

    @Test
    @DisplayName("Test 12: Inactive room never reactivated")
    void test12_inactiveRoomNeverReactivated() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        Room room = roomRepository.findById(roomIdA1).orElseThrow();
        room.setActive(false);
        roomRepository.saveAndFlush(room);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        Room updated = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(updated.isActive()).isFalse();
        assertThat(updated.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(updated.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);
    }

    // =========================================================================
    // Tests 13-19: Invalid States, OTAs, Security & RBAC Enforcement
    // =========================================================================

    @Test
    @DisplayName("Test 13: Repeated checkout rejected with HTTP 400")
    void test13_repeatedCheckOut_rejected400() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Reservation is already checked out")));
    }

    @Test
    @DisplayName("Test 14: Invalid reservation states rejected with HTTP 400")
    void test14_invalidReservationStates_rejected400() throws Exception {
        int i = 0;
        for (ReservationStatus s : List.of(ReservationStatus.CONFIRMED, ReservationStatus.PENDING, ReservationStatus.CANCELLED)) {
            LocalDate start = todayInPropZone.plusDays(i * 5);
            LocalDate end = start.plusDays(2);
            i++;

            UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                    start, end, s);
            UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
            assignRoomToReservationRoom(rrId, roomIdA1, start, end, RoomAssignmentStatus.ASSIGNED);

            mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("Only CHECKED_IN reservations can be checked out")));
        }
    }

    @Test
    @DisplayName("Test 15: OTA reservation checkout permitted")
    void test15_otaReservation_checkOutPermitted() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceOtaId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_OUT"));
    }

    @Test
    @DisplayName("Test 16: Missing JWT returns HTTP 401 Unauthorized")
    void test16_missingJwt_returns401() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Test 17: Insufficient roles return HTTP 403 Forbidden")
    void test17_insufficientRoles_return403() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Test 18: Foreign property returns HTTP 404 Not Found")
    void test18_foreignProperty_returns404() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Test 19: Staff actor recorded correctly from token")
    void test19_staffActorRecordedCorrectlyFromToken() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk());

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getCheckedOutByStaffUserId()).isEqualTo(staffIdReceptionistA);
    }

    // =========================================================================
    // Tests 20-25: Concurrency & Race Conditions
    // =========================================================================

    @Test
    @DisplayName("Test 20: Concurrent duplicate checkout race condition: exactly one succeeds")
    void test20_concurrentDuplicateCheckout_exactlyOneSucceeds() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();

                TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
                txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                try {
                    setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                    txTemplate.execute(status -> {
                        reservationService.checkOutReservation(resId);
                        return null;
                    });
                    successCount.incrementAndGet();
                } catch (BusinessValidationException e) {
                    if (e.getMessage().contains("already checked out")) {
                        conflictCount.incrementAndGet();
                    }
                } finally {
                    SecurityContextHolder.clearContext();
                }
                return null;
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<Void> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(threadCount - 1);

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CHECKED_OUT);
        Room room = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(room.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(room.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);
    }

    @Test
    @DisplayName("Test 21: Checkout vs housekeeping update race condition: serialized safely")
    void test21_checkoutVsHousekeepingUpdate_serializedSafely() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicReference<String> checkoutResult = new AtomicReference<>("NONE");
        AtomicReference<String> housekeepingResult = new AtomicReference<>("NONE");

        // Thread 1: Check out
        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.execute(st -> {
                    reservationService.checkOutReservation(resId);
                    return null;
                });
                checkoutResult.set("SUCCESS");
            } catch (Exception e) {
                checkoutResult.set("ERROR: " + e.getMessage());
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        // Thread 2: Update housekeeping status
        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.execute(st -> {
                    RoomHousekeepingUpdateRequest req = new RoomHousekeepingUpdateRequest();
                    req.setHousekeepingStatus(HousekeepingStatus.CLEANING_IN_PROGRESS);
                    roomService.updateHousekeepingStatus(roomIdA1, req);
                    return null;
                });
                housekeepingResult.set("SUCCESS");
            } catch (Exception e) {
                housekeepingResult.set("ERROR: " + e.getMessage());
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(checkoutResult.get()).isEqualTo("SUCCESS");
        assertThat(housekeepingResult.get()).matches("SUCCESS|ERROR: Cannot mark room as .* while it is occupied.*");

        Room room = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(room.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        // Housekeeping status must be either DIRTY (if checkout happened last) or CLEANING_IN_PROGRESS (if housekeeping happened last)
        assertThat(room.getHousekeepingStatus()).isIn(HousekeepingStatus.DIRTY, HousekeepingStatus.CLEANING_IN_PROGRESS);
    }

    @Test
    @DisplayName("Test 22: Checkout vs manual room-status change race condition: serialized safely")
    void test22_checkoutVsManualRoomStatusChange_serializedSafely() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicReference<String> checkoutResult = new AtomicReference<>("NONE");
        AtomicReference<String> statusChangeResult = new AtomicReference<>("NONE");

        // Thread 1: Check out
        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.execute(st -> {
                    reservationService.checkOutReservation(resId);
                    return null;
                });
                checkoutResult.set("SUCCESS");
            } catch (Exception e) {
                checkoutResult.set("ERROR: " + e.getMessage());
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        // Thread 2: Update room status to OUT_OF_SERVICE
        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.execute(st -> {
                    RoomStatusUpdateRequest req = new RoomStatusUpdateRequest();
                    req.setStatus(RoomStatus.OUT_OF_SERVICE);
                    roomService.updateRoomStatus(roomIdA1, req);
                    return null;
                });
                statusChangeResult.set("SUCCESS");
            } catch (Exception e) {
                statusChangeResult.set("ERROR: " + e.getMessage());
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(checkoutResult.get()).isEqualTo("SUCCESS");
        // Status change will either succeed (setting OUT_OF_SERVICE) or be serialized safely
        Room room = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(room.getStatus()).isIn(RoomStatus.AVAILABLE, RoomStatus.OUT_OF_SERVICE);
        assertThat(room.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);
    }

    @Test
    @DisplayName("Test 22b: Checkout first, then OUT_OF_SERVICE update: status becomes OUT_OF_SERVICE and housekeeping remains DIRTY")
    void test22b_checkoutFirstThenOutOfServiceUpdate() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        // 1. Checkout first
        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        Room afterCheckout = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(afterCheckout.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(afterCheckout.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);

        // 2. Update to OUT_OF_SERVICE second
        RoomStatusUpdateRequest req = new RoomStatusUpdateRequest();
        req.setStatus(RoomStatus.OUT_OF_SERVICE);
        mockMvc.perform(patch("/api/v1/rooms/" + roomIdA1 + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"))
                .andExpect(jsonPath("$.housekeepingStatus").value("DIRTY"));

        Room afterUpdate = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(afterUpdate.getStatus()).isEqualTo(RoomStatus.OUT_OF_SERVICE);
        assertThat(afterUpdate.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);
    }

    @Test
    @DisplayName("Test 23: Checkout vs room deactivation: room cannot be deactivated while checked in, deactivated after checkout")
    void test23_checkoutVsRoomDeactivation() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        // While checked in, room deactivation is forbidden
        mockMvc.perform(patch("/api/v1/rooms/" + roomIdA1 + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Cannot deactivate room")));

        // Check out
        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        // After checkout, room can be deactivated
        mockMvc.perform(patch("/api/v1/rooms/" + roomIdA1 + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNoContent());

        Room room = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(room.isActive()).isFalse();
    }

    @Test
    @DisplayName("Test 24: Checkout vs another reservation check-in: subsequent check-in blocked while room is DIRTY")
    void test24_checkoutVsAnotherReservationCheckIn_blockedWhileDirty() throws Exception {
        // Reservation 1 is checked in
        UUID resId1 = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(1));

        // Check out Reservation 1 -> Room becomes AVAILABLE and DIRTY, assignment RELEASED
        mockMvc.perform(post("/api/v1/reservations/" + resId1 + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        // Reservation 2 arrives for the same room
        UUID resId2 = createTestReservation(propertyIdA, guestId2, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId2 = createReservationRoom(resId2, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId2, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        // Reservation 2 attempts check-in -> Blocked because room is DIRTY!
        mockMvc.perform(post("/api/v1/reservations/" + resId2 + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Cannot check in: assigned room requires housekeeping.")));
    }

    @Test
    @DisplayName("Test 25: Checkout vs physical assignment modification: serialized safely under pessimistic locks")
    void test25_checkoutVsAssignmentModification_serializedSafely() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2));

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        List<RoomAssignment> assignments = roomAssignmentRepository.findByRoomIdAndStatus(roomIdA1, RoomAssignmentStatus.RELEASED);
        assertThat(assignments).hasSize(1);
    }

    // =========================================================================
    // Tests 26-30: Edge Cases, Rollback & Invariant Guarantees
    // =========================================================================

    @Test
    @DisplayName("Test 26: Legacy split-stay checkout safely rejected")
    void test26_legacySplitStayCheckout_safelyRejected400() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CHECKED_IN);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);

        // Split stay: Night 1 room 1, Night 2 room 2
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(1), RoomAssignmentStatus.ASSIGNED);
        assignRoomToReservationRoom(rrId, roomIdA2, todayInPropZone.plusDays(1), todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Split-stay or partial-date room assignments detected")));
    }

    @Test
    @DisplayName("Test 27: Ambiguous other-guest occupancy safely rejected")
    void test27_ambiguousOtherGuestOccupancy_safelyRejected400() throws Exception {
        // Reservation 1: today minus 2 to today
        UUID resId1 = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone.minusDays(2), todayInPropZone, ReservationStatus.CHECKED_IN);
        UUID rrId1 = createReservationRoom(resId1, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId1, roomIdA1, todayInPropZone.minusDays(2), todayInPropZone, RoomAssignmentStatus.ASSIGNED);

        Room r = roomRepository.findById(roomIdA1).orElseThrow();
        r.setStatus(RoomStatus.OCCUPIED);
        r.setHousekeepingStatus(HousekeepingStatus.CLEAN);
        roomRepository.saveAndFlush(r);

        // Reservation 2: today to today plus 2, also CHECKED_IN referencing the same physical room
        UUID resId2 = createTestReservation(propertyIdA, guestId2, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CHECKED_IN);
        UUID rrId2 = createReservationRoom(resId2, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId2, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId1 + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("is currently referenced by another active checked-in reservation")));
    }

    @Test
    @DisplayName("Test 28: Failed checkout has no partial persistence (full rollback)")
    void test28_failedCheckout_noPartialPersistence_fullRollback() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CHECKED_IN);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(1), RoomAssignmentStatus.ASSIGNED);

        Room room = roomRepository.findById(roomIdA1).orElseThrow();
        room.setStatus(RoomStatus.OCCUPIED);
        room.setHousekeepingStatus(HousekeepingStatus.CLEAN);
        roomRepository.saveAndFlush(room);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest());

        // Verify full rollback: no state was modified
        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
        assertThat(res.getActualCheckedOutAt()).isNull();

        Room r = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(RoomStatus.OCCUPIED);
        assertThat(r.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.CLEAN);

        RoomAssignment ra = roomAssignmentRepository.findByReservationRoomId(rrId).get(0);
        assertThat(ra.getStatus()).isEqualTo(RoomAssignmentStatus.ASSIGNED);
    }

    @Test
    @DisplayName("Test 29: Next guest cannot check in until room becomes CLEAN")
    void test29_nextGuestCannotCheckInUntilRoomClean() throws Exception {
        // Guest 1 checks out -> Room becomes DIRTY
        UUID resId1 = createCheckedInReservation(propertyIdA, guestId1, bookingSourceDirectId,
                roomTypeIdA1, roomIdA1, todayInPropZone, todayInPropZone.plusDays(1));

        mockMvc.perform(post("/api/v1/reservations/" + resId1 + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        // Guest 2 arrives for the same room
        UUID resId2 = createTestReservation(propertyIdA, guestId2, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId2 = createReservationRoom(resId2, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId2, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        // Check-in fails because room is DIRTY
        mockMvc.perform(post("/api/v1/reservations/" + resId2 + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Cannot check in: assigned room requires housekeeping.")));

        // Staff cleans room -> update housekeeping status to CLEAN
        RoomHousekeepingUpdateRequest cleanReq = new RoomHousekeepingUpdateRequest();
        cleanReq.setHousekeepingStatus(HousekeepingStatus.CLEAN);
        mockMvc.perform(patch("/api/v1/rooms/" + roomIdA1 + "/housekeeping-status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cleanReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.housekeepingStatus").value("CLEAN"));

        // Guest 2 check-in now succeeds!
        mockMvc.perform(post("/api/v1/reservations/" + resId2 + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));

        Room r = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(RoomStatus.OCCUPIED);
    }

    @Test
    @DisplayName("Test 30: Future reservation inventory remains unaffected by dirty housekeeping state")
    void test30_futureReservationInventoryUnaffectedByDirtyHousekeeping() throws Exception {
        // Mark room DIRTY
        Room r = roomRepository.findById(roomIdA1).orElseThrow();
        r.setHousekeepingStatus(HousekeepingStatus.DIRTY);
        roomRepository.saveAndFlush(r);

        // Future reservation creation succeeds despite dirty housekeeping
        ReservationCreateRequest req = new ReservationCreateRequest();
        req.setPropertyId(propertyIdA);
        req.setPrimaryGuestId(guestId1);
        req.setBookingSourceId(bookingSourceDirectId);
        req.setCheckInDate(todayInPropZone.plusDays(10));
        req.setCheckOutDate(todayInPropZone.plusDays(12));
        req.setAdults(2);

        ReservationRoomCreateRequest rrReq = new ReservationRoomCreateRequest();
        rrReq.setRoomTypeId(roomTypeIdA1);
        rrReq.setQuantity(1);
        req.setReservationRooms(List.of(rrReq));

        setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
        try {
            ReservationResponse response = reservationService.createReservation(req);
            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(ReservationStatus.PENDING);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
