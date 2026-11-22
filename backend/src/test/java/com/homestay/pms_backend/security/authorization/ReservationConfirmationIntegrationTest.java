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
@DisplayName("PMS Phase 2 — Step 3.4.3.9C: Secure Staff Reservation Confirmation Backend Integration Tests")
class ReservationConfirmationIntegrationTest {

    private static final String TEST_DOMAIN = "@confirm-auth-test.com";
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
        propertyIdA = getOrCreateProperty("Confirm Property Alpha", "PROP_CNFM_A", PROPERTY_TZ);
        propertyIdB = getOrCreateProperty("Confirm Property Beta", "PROP_CNFM_B", PROPERTY_TZ);

        // 2. Seed Room Types
        roomTypeIdA1 = getOrCreateRoomType(propertyIdA, "Deluxe Suite A", "RT_CNFM_A1");
        roomTypeIdA2 = getOrCreateRoomType(propertyIdA, "Standard Room A", "RT_CNFM_A2");
        roomTypeIdB = getOrCreateRoomType(propertyIdB, "Deluxe Suite B", "RT_CNFM_B");

        // 3. Seed Physical Rooms
        roomIdA1 = getOrCreateRoom(propertyIdA, roomTypeIdA1, "CNFM-101");
        roomIdA2 = getOrCreateRoom(propertyIdA, roomTypeIdA1, "CNFM-102");
        roomIdA3 = getOrCreateRoom(propertyIdA, roomTypeIdA2, "CNFM-103");
        roomIdB = getOrCreateRoom(propertyIdB, roomTypeIdB, "CNFM-201");

        // 4. Seed Guests
        guestId1 = getOrCreateGuest("Guest Alpha", "guest1-cnfm@test.com", "+919876543211");
        guestId2 = getOrCreateGuest("Guest Beta", "guest2-cnfm@test.com", "+919876543212");
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
                    "DELETE FROM room_assignments WHERE room_id IN (SELECT id FROM rooms WHERE room_number IN ('CNFM-101', 'CNFM-102', 'CNFM-103', 'CNFM-201')); " +
                    "DELETE FROM reservation_rooms WHERE room_type_id IN (SELECT id FROM room_types WHERE code IN ('RT_CNFM_A1', 'RT_CNFM_A2', 'RT_CNFM_B')); " +
                    "DELETE FROM reservations WHERE property_id IN (SELECT id FROM properties WHERE code IN ('PROP_CNFM_A', 'PROP_CNFM_B')); " +
                    "DELETE FROM rooms WHERE room_number IN ('CNFM-101', 'CNFM-102', 'CNFM-103', 'CNFM-201'); " +
                    "DELETE FROM room_types WHERE code IN ('RT_CNFM_A1', 'RT_CNFM_A2', 'RT_CNFM_B'); " +
                    "DELETE FROM guest_property_associations WHERE guest_id IN (SELECT id FROM guests WHERE email IN ('guest1-cnfm@test.com', 'guest2-cnfm@test.com')); " +
                    "DELETE FROM guests WHERE email IN ('guest1-cnfm@test.com', 'guest2-cnfm@test.com'); " +
                    "DELETE FROM properties WHERE code IN ('PROP_CNFM_A', 'PROP_CNFM_B');"
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
                    prop.setDescription("Integration test property for confirm workflow");
                    prop.setAddressLine1("123 Confirm Lane");
                    prop.setCity("Confirm City");
                    prop.setState("Confirm State");
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
                    rt.setDescription("Confirm Room Type");
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
        res.setSpecialRequests("Quiet corner room requested");
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
    // Part 1: Role Authorization Matrix
    // =========================================================================

    @Test
    @DisplayName("Test 1: SUPER_ADMIN reservation confirmation succeeds")
    void test1_superAdmin_confirmSucceeds() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone.plusDays(1), todayInPropZone.plusDays(3), ReservationStatus.PENDING);
        createReservationRoom(resId, roomTypeIdA1, 1);

        mockMvc.perform(patch("/api/v1/reservations/" + resId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(resId.toString()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.propertyId").value(propertyIdA.toString()));

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Test 2: Authorized PROPERTY_ADMIN reservation confirmation succeeds")
    void test2_propertyAdmin_confirmSucceeds() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone.plusDays(2), todayInPropZone.plusDays(4), ReservationStatus.PENDING);
        createReservationRoom(resId, roomTypeIdA1, 1);

        mockMvc.perform(patch("/api/v1/reservations/" + resId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Test 3: Authorized RECEPTIONIST reservation confirmation succeeds")
    void test3_receptionist_confirmSucceeds() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone.plusDays(3), todayInPropZone.plusDays(5), ReservationStatus.PENDING);
        createReservationRoom(resId, roomTypeIdA1, 1);

        mockMvc.perform(patch("/api/v1/reservations/" + resId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Test 4: ACCOUNTANT lacks RESERVATION_MANAGE, denied with 403 Forbidden")
    void test4_accountant_confirmForbidden() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone.plusDays(1), todayInPropZone.plusDays(3), ReservationStatus.PENDING);
        createReservationRoom(resId, roomTypeIdA1, 1);

        mockMvc.perform(patch("/api/v1/reservations/" + resId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA))
                .andExpect(status().isForbidden());

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    @DisplayName("Test 5: VIEWER lacks RESERVATION_MANAGE, denied with 403 Forbidden")
    void test5_viewer_confirmForbidden() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone.plusDays(1), todayInPropZone.plusDays(3), ReservationStatus.PENDING);
        createReservationRoom(resId, roomTypeIdA1, 1);

        mockMvc.perform(patch("/api/v1/reservations/" + resId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isForbidden());

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    @DisplayName("Test 6: Staff from foreign property returns 404 Not Found (scoping guard)")
    void test6_foreignPropertyStaff_confirmNotFound() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone.plusDays(1), todayInPropZone.plusDays(3), ReservationStatus.PENDING);
        createReservationRoom(resId, roomTypeIdA1, 1);

        mockMvc.perform(patch("/api/v1/reservations/" + resId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenB))
                .andExpect(status().isNotFound());

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    @DisplayName("Test 7: Unauthenticated confirmation returns 401 Unauthorized")
    void test7_unauthenticated_confirmUnauthorized() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone.plusDays(1), todayInPropZone.plusDays(3), ReservationStatus.PENDING);
        createReservationRoom(resId, roomTypeIdA1, 1);

        mockMvc.perform(patch("/api/v1/reservations/" + resId + "/confirm"))
                .andExpect(status().isUnauthorized());

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    @DisplayName("Test 8: Revoking staff property role immediately turns confirmation into 404 Not Found")
    void test8_revokedRole_confirmNotFound() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone.plusDays(1), todayInPropZone.plusDays(3), ReservationStatus.PENDING);
        createReservationRoom(resId, roomTypeIdA1, 1);

        // Revoke role
        adminJdbcTemplate.update("DELETE FROM staff_property_roles WHERE staff_user_id = ? AND property_id = ?",
                staffIdAdminA, propertyIdA);

        mockMvc.perform(patch("/api/v1/reservations/" + resId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound());

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    @DisplayName("Test 9: Non-existent reservation returns 404 Not Found")
    void test9_nonExistentReservation_confirmNotFound() throws Exception {
        UUID nonExistentId = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/reservations/" + nonExistentId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // Part 2: Lifecycle & Status Transition Invariants
    // =========================================================================

    @Test
    @DisplayName("Test 10: OTA booking confirmation attempt rejected with 400 Bad Request")
    void test10_otaReservation_confirmRejected() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceOtaId,
                todayInPropZone.plusDays(1), todayInPropZone.plusDays(3), ReservationStatus.PENDING);
        createReservationRoom(resId, roomTypeIdA1, 1);

        mockMvc.perform(patch("/api/v1/reservations/" + resId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("OTA reservations cannot be confirmed locally")));

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    @DisplayName("Test 11: Already CONFIRMED reservation rejected with 400 Bad Request")
    void test11_alreadyConfirmed_confirmRejected() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone.plusDays(1), todayInPropZone.plusDays(3), ReservationStatus.CONFIRMED);
        createReservationRoom(resId, roomTypeIdA1, 1);

        mockMvc.perform(patch("/api/v1/reservations/" + resId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Reservation is already confirmed")));
    }

    @Test
    @DisplayName("Test 12: CHECKED_IN reservation rejected with 400 Bad Request")
    void test12_checkedInReservation_confirmRejected() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.CHECKED_IN);
        createReservationRoom(resId, roomTypeIdA1, 1);

        mockMvc.perform(patch("/api/v1/reservations/" + resId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Cannot confirm reservation from status: CHECKED_IN")));
    }

    @Test
    @DisplayName("Test 13: CHECKED_OUT reservation rejected with 400 Bad Request")
    void test13_checkedOutReservation_confirmRejected() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone.minusDays(3), todayInPropZone.minusDays(1), ReservationStatus.CHECKED_OUT);
        createReservationRoom(resId, roomTypeIdA1, 1);

        mockMvc.perform(patch("/api/v1/reservations/" + resId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Cannot confirm reservation from status: CHECKED_OUT")));
    }

    @Test
    @DisplayName("Test 14: CANCELLED reservation rejected with 400 Bad Request")
    void test14_cancelledReservation_confirmRejected() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone.plusDays(1), todayInPropZone.plusDays(3), ReservationStatus.CANCELLED);
        createReservationRoom(resId, roomTypeIdA1, 1);

        mockMvc.perform(patch("/api/v1/reservations/" + resId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Cannot confirm reservation from status: CANCELLED")));
    }

    @Test
    @DisplayName("Test 15: NO_SHOW reservation rejected with 400 Bad Request")
    void test15_noShowReservation_confirmRejected() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone.minusDays(2), todayInPropZone, ReservationStatus.NO_SHOW);
        createReservationRoom(resId, roomTypeIdA1, 1);

        mockMvc.perform(patch("/api/v1/reservations/" + resId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Cannot confirm reservation from status: NO_SHOW")));
    }

    // =========================================================================
    // Part 3: Data Integrity & Non-Side-Effect Invariants
    // =========================================================================

    @Test
    @DisplayName("Test 16: Data integrity — all reservation and room details preserved unchanged")
    void test16_dataIntegrity_allFieldsPreserved() throws Exception {
        LocalDate checkIn = todayInPropZone.plusDays(5);
        LocalDate checkOut = todayInPropZone.plusDays(8);
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                checkIn, checkOut, ReservationStatus.PENDING);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);

        mockMvc.perform(patch("/api/v1/reservations/" + resId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(resId.toString()))
                .andExpect(jsonPath("$.propertyId").value(propertyIdA.toString()))
                .andExpect(jsonPath("$.primaryGuestId").value(guestId1.toString()))
                .andExpect(jsonPath("$.bookingSourceId").value(bookingSourceDirectId.toString()))
                .andExpect(jsonPath("$.checkInDate").value(checkIn.toString()))
                .andExpect(jsonPath("$.checkOutDate").value(checkOut.toString()))
                .andExpect(jsonPath("$.adults").value(2))
                .andExpect(jsonPath("$.children").value(0))
                .andExpect(jsonPath("$.infants").value(0))
                .andExpect(jsonPath("$.specialRequests").value("Quiet corner room requested"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(res.getCheckInDate()).isEqualTo(checkIn);
        assertThat(res.getCheckOutDate()).isEqualTo(checkOut);
        assertThat(res.getAdults()).isEqualTo(2);
        assertThat(res.getSpecialRequests()).isEqualTo("Quiet corner room requested");

        ReservationRoom rr = reservationRoomRepository.findById(rrId).orElseThrow();
        assertThat(rr.getQuantity()).isEqualTo(1);
        assertThat(rr.getCancelledQuantity()).isEqualTo(0);
        assertThat(rr.getUnitPrice()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(rr.getSubtotal()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(rr.getStatus()).isEqualTo(ReservationRoomStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Test 17: Non-side-effects — no physical room assignments created and rooms remain AVAILABLE")
    void test17_nonSideEffects_noPhysicalRoomAssignedOrOccupied() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone.plusDays(1), todayInPropZone.plusDays(3), ReservationStatus.PENDING);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);

        mockMvc.perform(patch("/api/v1/reservations/" + resId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        // Verify NO room assignments exist for this reservation room
        List<RoomAssignment> assignments = roomAssignmentRepository.findByReservationRoomId(rrId);
        assertThat(assignments).isEmpty();

        // Verify rooms remain AVAILABLE
        Room room1 = roomRepository.findById(roomIdA1).orElseThrow();
        Room room2 = roomRepository.findById(roomIdA2).orElseThrow();
        assertThat(room1.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(room2.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
    }

    @Test
    @DisplayName("Test 18: Lifecycle continuity — complete full-stay lifecycle PENDING -> CONFIRMED -> ASSIGNED -> CHECKED_IN -> CHECKED_OUT")
    void test18_lifecycleContinuity_fullStayLifecycleFromPendingToCheckedOut() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone, todayInPropZone.plusDays(2), ReservationStatus.PENDING);
        UUID rrId = createReservationRoom(resId, roomTypeIdA1, 1);

        // Ensure room is CLEAN initially
        Room initRoom = roomRepository.findById(roomIdA1).orElseThrow();
        initRoom.setHousekeepingStatus(HousekeepingStatus.CLEAN);
        roomRepository.saveAndFlush(initRoom);

        // 1. Confirm reservation (PENDING -> CONFIRMED)
        mockMvc.perform(patch("/api/v1/reservations/" + resId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 2. Assign physical room
        setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
        RoomAssignmentCreateRequest assignReq = new RoomAssignmentCreateRequest();
        assignReq.setReservationRoomId(rrId);
        assignReq.setRoomId(roomIdA1);
        assignReq.setAssignedFrom(todayInPropZone);
        assignReq.setAssignedUntil(todayInPropZone.plusDays(2));
        roomAssignmentService.assignRoom(assignReq);

        // 3. Check-in (CONFIRMED -> CHECKED_IN)
        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));

        Reservation checkedInRes = reservationRepository.findById(resId).orElseThrow();
        assertThat(checkedInRes.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
        Room occupiedRoom = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(occupiedRoom.getStatus()).isEqualTo(RoomStatus.OCCUPIED);

        // 4. Check-out (CHECKED_IN -> CHECKED_OUT)
        mockMvc.perform(post("/api/v1/reservations/" + resId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_OUT"));

        Reservation checkedOutRes = reservationRepository.findById(resId).orElseThrow();
        assertThat(checkedOutRes.getStatus()).isEqualTo(ReservationStatus.CHECKED_OUT);
        Room vacatedRoom = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(vacatedRoom.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(vacatedRoom.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);

        List<RoomAssignment> releasedAssignments = roomAssignmentRepository.findByRoomIdAndStatus(roomIdA1, RoomAssignmentStatus.RELEASED);
        assertThat(releasedAssignments).hasSize(1);
    }

    // =========================================================================
    // Part 4: Inventory & Availability Re-validation
    // =========================================================================

    @Test
    @DisplayName("Test 19: Inventory revalidation — overbooked pending reservation fails confirmation with 400")
    void test19_inventoryRevalidation_overbookedPendingFailsConfirmation() throws Exception {
        // Room type A1 has 2 physical rooms (roomIdA1, roomIdA2)
        // Create confirmed reservation 1 taking both 2 rooms
        UUID res1 = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone.plusDays(10), todayInPropZone.plusDays(12), ReservationStatus.CONFIRMED);
        createReservationRoom(res1, roomTypeIdA1, 2);

        // Create pending reservation 2 requesting 1 room for the same dates
        UUID res2 = createTestReservation(propertyIdA, guestId2, bookingSourceDirectId,
                todayInPropZone.plusDays(10), todayInPropZone.plusDays(12), ReservationStatus.PENDING);
        createReservationRoom(res2, roomTypeIdA1, 1);

        // Attempt to confirm res2 -> must fail because res1 took all available rooms
        mockMvc.perform(patch("/api/v1/reservations/" + res2 + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Insufficient availability")));

        Reservation r2 = reservationRepository.findById(res2).orElseThrow();
        assertThat(r2.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    @DisplayName("Test 20: Exclusion-safe inventory check — reservation does not double count against itself")
    void test20_inventoryRevalidation_exclusionSafeSelfNotDoubleCounted() throws Exception {
        // Room type A1 has 2 rooms.
        // Reservation requests exactly 2 rooms.
        // During confirmation, exclusion check ensures its own pending rooms are not double-counted.
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone.plusDays(15), todayInPropZone.plusDays(17), ReservationStatus.PENDING);
        createReservationRoom(resId, roomTypeIdA1, 2);

        mockMvc.perform(patch("/api/v1/reservations/" + resId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    // =========================================================================
    // Part 5: Concurrency Safety
    // =========================================================================

    @Test
    @DisplayName("Test 21: Concurrent confirmation — multi-threaded confirmation results in exactly 1 success")
    void test21_concurrentConfirmation_singleWinner() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone.plusDays(20), todayInPropZone.plusDays(22), ReservationStatus.PENDING);
        createReservationRoom(resId, roomTypeIdA1, 1);

        int threadCount = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }

                TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
                txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                try {
                    txTemplate.execute(status -> {
                        reservationService.confirmReservation(resId);
                        return null;
                    });
                    successCount.incrementAndGet();
                } catch (BusinessValidationException e) {
                    if (e.getMessage().contains("already confirmed")) {
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
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Test 22: Concurrent confirmation and cancellation: single valid terminal ordering persists")
    void test22_concurrentConfirmationAndCancellation_singleWinner() throws Exception {
        UUID resId = createTestReservation(propertyIdA, guestId1, bookingSourceDirectId,
                todayInPropZone.plusDays(25), todayInPropZone.plusDays(27), ReservationStatus.PENDING);
        createReservationRoom(resId, roomTypeIdA1, 1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicReference<String> confirmResult = new AtomicReference<>("NONE");
        AtomicReference<String> cancelResult = new AtomicReference<>("NONE");

        // Thread 1: Confirm
        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.execute(status -> {
                    reservationService.confirmReservation(resId);
                    return null;
                });
                confirmResult.set("SUCCESS");
            } catch (Exception e) {
                confirmResult.set("FAILED: " + e.getMessage());
            }
        });

        // Thread 2: Cancel
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
        // If confirm won first: res became CONFIRMED, then cancel won on CONFIRMED reservation (which is also allowed to cancel) OR
        // If cancel won first: res became CANCELLED, and confirm threw "Cannot confirm reservation from status: CANCELLED"
        if ("SUCCESS".equals(confirmResult.get()) && "SUCCESS".equals(cancelResult.get())) {
            // Confirm succeeded (PENDING -> CONFIRMED), and Cancel succeeded (CONFIRMED -> CANCELLED)
            assertThat(res.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        } else if ("SUCCESS".equals(cancelResult.get())) {
            assertThat(confirmResult.get()).contains("Cannot confirm reservation from status: CANCELLED");
            assertThat(res.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        } else if ("SUCCESS".equals(confirmResult.get())) {
            assertThat(res.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        }
    }
}
