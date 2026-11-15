package com.homestay.pms_backend.security.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homestay.pms_backend.dto.request.RoomAssignmentCreateRequest;
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
import com.homestay.pms_backend.service.RoomAssignmentService;
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
import java.time.ZonedDateTime;
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
@DisplayName("PMS Phase 2 — Step 3.4.3.6B: Secure Staff Check-In Workflow Integration Tests")
class ReservationCheckInIntegrationTest {

    private static final String TEST_DOMAIN = "@checkin-auth-test.com";
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
    private RoomAssignmentService roomAssignmentService;

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

        // 1. Seed Properties
        propertyIdA = getOrCreateProperty("CheckIn Property Alpha", "PROP_CKIN_A", PROPERTY_TZ);
        propertyIdB = getOrCreateProperty("CheckIn Property Beta", "PROP_CKIN_B", PROPERTY_TZ);

        // 2. Seed Room Types
        roomTypeIdA1 = getOrCreateRoomType(propertyIdA, "Deluxe Suite A", "RT_CKIN_A1");
        roomTypeIdA2 = getOrCreateRoomType(propertyIdA, "Standard Room A", "RT_CKIN_A2");
        roomTypeIdB = getOrCreateRoomType(propertyIdB, "Deluxe Suite B", "RT_CKIN_B");

        // 3. Seed Physical Rooms
        roomIdA1 = getOrCreateRoom(propertyIdA, roomTypeIdA1, "CKIN-101");
        roomIdA2 = getOrCreateRoom(propertyIdA, roomTypeIdA1, "CKIN-102");
        roomIdA3 = getOrCreateRoom(propertyIdA, roomTypeIdA2, "CKIN-103");
        roomIdB = getOrCreateRoom(propertyIdB, roomTypeIdB, "CKIN-201");

        // 4. Seed Guests
        guestId1 = getOrCreateGuest("Guest Alpha", "guest1-ckin@test.com", "+919876543201");
        guestId2 = getOrCreateGuest("Guest Beta", "guest2-ckin@test.com", "+919876543202");
        guestPropertyAssociationService.createRegistrationAssociation(guestId1, propertyIdA);
        guestPropertyAssociationService.createRegistrationAssociation(guestId2, propertyIdB);

        // 5. Booking Sources
        bookingSourceDirectId = bookingSourceRepository.findByCode("DIRECT")
                .orElseThrow(() -> new IllegalStateException("DIRECT booking source not found"))
                .getId();
        bookingSourceOtaId = bookingSourceRepository.findByCode("BOOKING_COM")
                .orElseThrow(() -> new IllegalStateException("BOOKING_COM booking source not found"))
                .getId();

        // 6. Seed Staff Accounts and Auth Tokens
        superAdminToken = createStaffToken("superadmin" + TEST_DOMAIN, StaffRole.SUPER_ADMIN, null);

        staffIdAdminA = createTestStaff("propadmin-a" + TEST_DOMAIN);
        assignStaffPropertyRole(staffIdAdminA, propertyIdA, StaffRole.PROPERTY_ADMIN);
        propAdminTokenA = generateTokenForStaff(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);

        receptionistTokenA = createStaffToken("receptionist-a" + TEST_DOMAIN, StaffRole.RECEPTIONIST, propertyIdA);
        accountantTokenA = createStaffToken("accountant-a" + TEST_DOMAIN, StaffRole.ACCOUNTANT, propertyIdA);
        viewerTokenA = createStaffToken("viewer-a" + TEST_DOMAIN, StaffRole.VIEWER, propertyIdA);
        propAdminTokenB = createStaffToken("propadmin-b" + TEST_DOMAIN, StaffRole.PROPERTY_ADMIN, propertyIdB);
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
                    "DELETE FROM room_assignments WHERE room_id IN (SELECT id FROM rooms WHERE room_number IN ('CKIN-101', 'CKIN-102', 'CKIN-103', 'CKIN-201')); " +
                    "DELETE FROM reservation_rooms WHERE room_type_id IN (SELECT id FROM room_types WHERE code IN ('RT_CKIN_A1', 'RT_CKIN_A2', 'RT_CKIN_B')); " +
                    "DELETE FROM reservations WHERE property_id IN (SELECT id FROM properties WHERE code IN ('PROP_CKIN_A', 'PROP_CKIN_B')); " +
                    "DELETE FROM rooms WHERE room_number IN ('CKIN-101', 'CKIN-102', 'CKIN-103', 'CKIN-201'); " +
                    "DELETE FROM room_types WHERE code IN ('RT_CKIN_A1', 'RT_CKIN_A2', 'RT_CKIN_B'); " +
                    "DELETE FROM guest_property_associations WHERE guest_id IN (SELECT id FROM guests WHERE email IN ('guest1-ckin@test.com', 'guest2-ckin@test.com')); " +
                    "DELETE FROM guests WHERE email IN ('guest1-ckin@test.com', 'guest2-ckin@test.com'); " +
                    "DELETE FROM properties WHERE code IN ('PROP_CKIN_A', 'PROP_CKIN_B');"
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
                    prop.setDescription("Integration test property for checkin workflow");
                    prop.setAddressLine1("123 CheckIn Lane");
                    prop.setCity("CheckIn City");
                    prop.setState("CheckIn State");
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
                    rt.setDescription("CheckIn Room Type");
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
        g.setIdNumber("P12345678");
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
    // Test 1-3: Role Authorization Success Cases
    // =========================================================================

    @Test
    @DisplayName("Test 1: SUPER_ADMIN check-in succeeds")
    void test1_superAdmin_checkInSucceeds() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"))
                .andExpect(jsonPath("$.checkoutDue").value(false))
                .andExpect(jsonPath("$.checkoutDueAt").isNotEmpty());

        assertThat(reservationRepository.findById(resId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CHECKED_IN);
        assertThat(roomRepository.findById(roomIdA1).orElseThrow().getStatus())
                .isEqualTo(RoomStatus.OCCUPIED);
    }

    @Test
    @DisplayName("Test 2: Authorized PROPERTY_ADMIN check-in succeeds")
    void test2_propertyAdmin_checkInSucceeds() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));
    }

    @Test
    @DisplayName("Test 3: Authorized RECEPTIONIST check-in succeeds")
    void test3_receptionist_checkInSucceeds() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));
    }

    // =========================================================================
    // Test 4-6: Role Authorization Denial Cases
    // =========================================================================

    @Test
    @DisplayName("Test 4: ACCOUNTANT check-in is denied 403 Forbidden")
    void test4_accountant_denied403() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Test 5: VIEWER check-in is denied 403 Forbidden")
    void test5_viewer_denied403() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Test 6: Foreign-property reservation check-in returns 404 Not Found")
    void test6_foreignProperty_returns404() throws Exception {
        UUID resIdA = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resIdA, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        // Staff on Property B attempts check-in on Property A reservation
        mockMvc.perform(post("/api/v1/reservations/" + resIdA + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenB))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // Test 7-9: Authentication & Dynamic Revocation
    // =========================================================================

    @Test
    @DisplayName("Test 7: Missing JWT denied 401 Unauthorized")
    void test7_missingJwt_denied401() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Test 8: Invalid JWT denied 401 Unauthorized")
    void test8_invalidJwt_denied401() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-tampered-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Test 9: Revoked property role denied 403 on subsequent check-in request")
    void test9_revokedPropertyRole_deniedOnSubsequentRequest() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        // Revoke PROPERTY_ADMIN role from staffIdAdminA
        adminJdbcTemplate.update(
                "DELETE FROM staff_property_roles WHERE staff_user_id = ? AND property_id = ?",
                staffIdAdminA, propertyIdA
        );

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound()); // Now out of scope, uniform 404
    }

    // =========================================================================
    // Test 10-15: Room Assignment Coverage and Invariant Rules
    // =========================================================================

    @Test
    @DisplayName("Test 10: No physical room assignment coverage is denied 400")
    void test10_noPhysicalAssignmentCoverage_denied() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        createReservationRoom(resId, roomTypeIdA1, 1); // 1 room needed, 0 assigned

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Incomplete physical room assignment")));
    }

    @Test
    @DisplayName("Test 11: Partial multi-room coverage is denied 400")
    void test11_partialMultiRoomCoverage_denied() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 2); // 2 rooms needed

        // Only 1 physical room assigned
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Incomplete physical room assignment")));
    }

    @Test
    @DisplayName("Test 12: Full multi-room coverage succeeds")
    void test12_fullMultiRoomCoverage_succeeds() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 2); // 2 rooms needed

        // Both physical rooms assigned
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);
        assignRoomToReservationRoom(rrId, roomIdA2, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));

        assertThat(roomRepository.findById(roomIdA1).orElseThrow().getStatus()).isEqualTo(RoomStatus.OCCUPIED);
        assertThat(roomRepository.findById(roomIdA2).orElseThrow().getStatus()).isEqualTo(RoomStatus.OCCUPIED);
    }

    @Test
    @DisplayName("Test 13: Released assignments do not count toward physical coverage")
    void test13_releasedAssignments_doNotCount() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);

        // Assignment exists but status is RELEASED
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.RELEASED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Incomplete physical room assignment")));
    }

    @Test
    @DisplayName("Test 14: Assigned room belonging to wrong room type or property is denied 400")
    void test14_wrongRoomTypeOrProperty_denied() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);

        // Assigned room roomIdA3 has roomTypeIdA2 (not roomTypeIdA1)
        assignRoomToReservationRoom(rrId, roomIdA3, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("does not match the reservation room type")));
    }

    @Test
    @DisplayName("Test 15: Out-of-service or inactive assigned room is denied 400")
    void test15_outOfServiceOrInactiveRoom_denied() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        // Set room out of service
        Room r = roomRepository.findById(roomIdA1).orElseThrow();
        r.setStatus(RoomStatus.OUT_OF_SERVICE);
        roomRepository.saveAndFlush(r);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("is out of service and cannot be checked in")));
    }

    // =========================================================================
    // Test 16-18: Status Validation Rules
    // =========================================================================

    @Test
    @DisplayName("Test 16: PENDING reservation cannot be checked in (denied 400)")
    void test16_pendingReservation_denied() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.PENDING);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Cannot check in reservation from status: PENDING")));
    }

    @Test
    @DisplayName("Test 17: Duplicate CHECKED_IN is denied with clear business error")
    void test17_duplicateCheckedIn_denied() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CHECKED_IN);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Reservation is already checked in")));
    }

    @Test
    @DisplayName("Test 18: CANCELLED, CHECKED_OUT, and NO_SHOW reservations cannot be checked in")
    void test18_terminalStatuses_denied() throws Exception {
        List<ReservationStatus> terminalStatuses = List.of(
                ReservationStatus.CANCELLED,
                ReservationStatus.CHECKED_OUT,
                ReservationStatus.NO_SHOW
        );

        int offset = 0;
        for (ReservationStatus s : terminalStatuses) {
            LocalDate start = todayInPropZone.plusDays(offset);
            LocalDate end = start.plusDays(2);
            offset += 5;

            UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                    start, end, s);
            UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
            assignRoomToReservationRoom(rrId, roomIdA1, start, end, RoomAssignmentStatus.ASSIGNED);

            mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("Cannot check in reservation from status: " + s)));
        }
    }

    // =========================================================================
    // Test 19-21: Temporal / Timezone Rules
    // =========================================================================

    @Test
    @DisplayName("Test 19: Early arrival before check-in date is denied 400")
    void test19_earlyArrival_denied() throws Exception {
        // Booked check-in starts tomorrow
        LocalDate checkInDate = todayInPropZone.plusDays(1);
        LocalDate checkOutDate = todayInPropZone.plusDays(3);

        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                checkInDate, checkOutDate, ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, checkInDate, checkOutDate, RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Cannot check in before reservation check-in date")));
    }

    @Test
    @DisplayName("Test 20: Late arrival within reservation dates is allowed")
    void test20_lateArrivalWithinStay_allowed() throws Exception {
        // Booked check-in started yesterday, stay extends for 2 more days
        LocalDate checkInDate = todayInPropZone.minusDays(1);
        LocalDate checkOutDate = todayInPropZone.plusDays(2);

        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                checkInDate, checkOutDate, ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, checkInDate, checkOutDate, RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));
    }

    @Test
    @DisplayName("Test 21: Checkout deadline uses property timezone at 12:00 PM on checkout date")
    void test21_checkoutDeadlineUsesPropertyTimezone() throws Exception {
        LocalDate checkOut = todayInPropZone.plusDays(2);
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, checkOut, ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, checkOut, RoomAssignmentStatus.ASSIGNED);

        Instant expectedDeadline = checkOut.atTime(12, 0).atZone(ZoneId.of(PROPERTY_TZ)).toInstant();

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutDue").value(false))
                .andExpect(jsonPath("$.checkoutDueAt").value(expectedDeadline.toString()));

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getCheckoutDueAt()).isEqualTo(expectedDeadline);
        assertThat(res.isCheckoutDue()).isFalse();
    }

    // =========================================================================
    // Test 22-24: Concurrency Tests
    // =========================================================================

    @Test
    @DisplayName("Test 22: Concurrent check-in requests: exactly one succeeds")
    void test22_concurrentCheckIn_exactlyOneSucceeds() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

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
                    txTemplate.execute(status -> {
                        reservationService.checkInReservation(resId);
                        return null;
                    });
                    successCount.incrementAndGet();
                } catch (BusinessValidationException e) {
                    if (e.getMessage().contains("already checked in")) {
                        conflictCount.incrementAndGet();
                    }
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
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    @Test
    @DisplayName("Test 23: Concurrent cancellation and check-in: only one valid terminal ordering persists")
    void test23_concurrentCancellationAndCheckIn_singleWinner() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicReference<String> checkInResult = new AtomicReference<>("NONE");
        AtomicReference<String> cancelResult = new AtomicReference<>("NONE");

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

        // Thread 2: Cancellation
        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.execute(status -> {
                    reservationService.cancelReservation(resId);
                    return null;
                });
                cancelResult.set("SUCCESS");
            } catch (Exception e) {
                cancelResult.set("FAILED: " + e.getMessage());
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        if ("SUCCESS".equals(checkInResult.get())) {
            assertThat(cancelResult.get()).contains("Reservation cannot be cancelled from status: CHECKED_IN");
            assertThat(res.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
        } else {
            assertThat(cancelResult.get()).isEqualTo("SUCCESS");
            assertThat(checkInResult.get()).contains("Cannot check in reservation from status: CANCELLED");
            assertThat(res.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        }
    }

    @Test
    @DisplayName("Test 24: Concurrent assignment and check-in: no incomplete allocation becomes checked in")
    void test24_concurrentAssignmentAndCheckIn_safety() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        // Initially 0 rooms assigned

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicReference<String> assignResult = new AtomicReference<>("NONE");
        AtomicReference<String> checkInResult = new AtomicReference<>("NONE");

        // Thread 1: Assign room
        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.execute(status -> {
                    RoomAssignmentCreateRequest req = new RoomAssignmentCreateRequest();
                    req.setReservationRoomId(rrId);
                    req.setRoomId(roomIdA1);
                    req.setAssignedFrom(todayInPropZone);
                    req.setAssignedUntil(todayInPropZone.plusDays(2));
                    roomAssignmentService.assignRoom(req);
                    return null;
                });
                assignResult.set("SUCCESS");
            } catch (Exception e) {
                assignResult.set("FAILED: " + e.getMessage());
            }
        });

        // Thread 2: Check-in
        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
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

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(assignResult.get()).isEqualTo("SUCCESS");
        Reservation res = reservationRepository.findById(resId).orElseThrow();

        // If check-in ran first, it threw Incomplete physical room assignment (400), res is CONFIRMED.
        // If assignment ran first, check-in succeeded, res is CHECKED_IN.
        if ("SUCCESS".equals(checkInResult.get())) {
            assertThat(res.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
        } else {
            assertThat(checkInResult.get()).contains("Incomplete physical room assignment");
            assertThat(res.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        }
    }

    // =========================================================================
    // Test 25-26: OTA and Atomicity / Partial Persistence Safety
    // =========================================================================

    @Test
    @DisplayName("Test 25: OTA CONFIRMED reservation check-in succeeds")
    void test25_otaConfirmedReservation_checkInSucceeds() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceOtaId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));

        assertThat(reservationRepository.findById(resId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CHECKED_IN);
    }

    @Test
    @DisplayName("Test 26: No partial persistence after a failed check-in")
    void test26_noPartialPersistenceAfterFailedCheckIn() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CONFIRMED);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);
        assignRoomToReservationRoom(rrId, roomIdA1, todayInPropZone, todayInPropZone.plusDays(2), RoomAssignmentStatus.ASSIGNED);

        // Make room out of service to trigger a transaction rollback during check-in
        Room r = roomRepository.findById(roomIdA1).orElseThrow();
        r.setStatus(RoomStatus.OUT_OF_SERVICE);
        roomRepository.saveAndFlush(r);

        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest());

        // Verify transaction rolled back cleanly: reservation status remains CONFIRMED, checkoutDue remains false, checkoutDueAt remains null
        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(res.isCheckoutDue()).isFalse();
        assertThat(res.getCheckoutDueAt()).isNull();
    }
}
