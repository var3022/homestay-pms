package com.homestay.pms_backend.security.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homestay.pms_backend.dto.request.RoomAssignmentCreateRequest;
import com.homestay.pms_backend.dto.request.RoomStatusUpdateRequest;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IsolatedDatabaseTest
@TestPropertySource(properties = {
        "auth.jwt.enforcement.enabled=true",
        "TEST_AUTH_JWT_ENFORCEMENT_ENABLED=true"
})
@DisplayName("PMS Phase 2 — Step 3.4.3.6C.1: Room Status and Check-In Concurrency Safety Tests")
class RoomStatusCheckInConcurrencyIntegrationTest {

    private static final String TEST_DOMAIN = "@room-status-audit.com";
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
    private UUID roomTypeIdA;
    private UUID roomIdA;
    private UUID roomIdA2;
    private UUID roomIdB;
    private UUID guestIdA;
    private UUID bookingSourceDirectId;

    private UUID staffIdAdminA;
    private String propAdminTokenA;
    private String receptionistTokenA;
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

        propertyIdA = getOrCreateProperty("Audit Property Alpha", "PROP_AUD_A", PROPERTY_TZ);
        propertyIdB = getOrCreateProperty("Audit Property Beta", "PROP_AUD_B", PROPERTY_TZ);

        roomTypeIdA = getOrCreateRoomType(propertyIdA, "Deluxe Room A", "RT_AUD_A");
        roomIdA = getOrCreateRoom(propertyIdA, roomTypeIdA, "AUD-101");
        roomIdA2 = getOrCreateRoom(propertyIdA, roomTypeIdA, "AUD-102");
        roomIdB = getOrCreateRoom(propertyIdB, getOrCreateRoomType(propertyIdB, "Deluxe Room B", "RT_AUD_B"), "AUD-201");

        guestIdA = getOrCreateGuest("Audit Guest Alpha", "guest-aud@test.com", "+919988776655");
        guestPropertyAssociationService.createRegistrationAssociation(guestIdA, propertyIdA);

        bookingSourceDirectId = bookingSourceRepository.findByCode("DIRECT")
                .orElseThrow(() -> new IllegalStateException("DIRECT booking source not found"))
                .getId();

        staffIdAdminA = createTestStaff("aud-propadmin-a" + TEST_DOMAIN);
        assignStaffPropertyRole(staffIdAdminA, propertyIdA, StaffRole.PROPERTY_ADMIN);
        propAdminTokenA = generateTokenForStaff(staffIdAdminA, "aud-propadmin-a" + TEST_DOMAIN);

        receptionistTokenA = createStaffToken("aud-receptionist-a" + TEST_DOMAIN, StaffRole.RECEPTIONIST, propertyIdA);
        viewerTokenA = createStaffToken("aud-viewer-a" + TEST_DOMAIN, StaffRole.VIEWER, propertyIdA);
        propAdminTokenB = createStaffToken("aud-propadmin-b" + TEST_DOMAIN, StaffRole.PROPERTY_ADMIN, propertyIdB);
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
                    "DELETE FROM room_assignments WHERE room_id IN (SELECT id FROM rooms WHERE room_number IN ('AUD-101', 'AUD-102', 'AUD-201')); " +
                    "DELETE FROM reservation_rooms WHERE room_type_id IN (SELECT id FROM room_types WHERE code IN ('RT_AUD_A', 'RT_AUD_B')); " +
                    "DELETE FROM reservations WHERE property_id IN (SELECT id FROM properties WHERE code IN ('PROP_AUD_A', 'PROP_AUD_B')); " +
                    "DELETE FROM rooms WHERE room_number IN ('AUD-101', 'AUD-102', 'AUD-201'); " +
                    "DELETE FROM room_types WHERE code IN ('RT_AUD_A', 'RT_AUD_B'); " +
                    "DELETE FROM guest_property_associations WHERE guest_id IN (SELECT id FROM guests WHERE email = 'guest-aud@test.com'); " +
                    "DELETE FROM guests WHERE email = 'guest-aud@test.com'; " +
                    "DELETE FROM properties WHERE code IN ('PROP_AUD_A', 'PROP_AUD_B');"
            );
        }
    }

    // =========================================================================
    // Test 1: Manual status update behavior on occupied checked-in room
    // =========================================================================

    @Test
    @DisplayName("Case B: Manual update OCCUPIED -> AVAILABLE is rejected while reservation is CHECKED_IN")
    void test01_occupiedRoom_withCheckedInReservation_cannotBeMarkedAvailable() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, roomIdA, roomTypeIdA);

        Room roomBefore = roomRepository.findById(roomIdA).orElseThrow();
        assertThat(roomBefore.getStatus()).isEqualTo(RoomStatus.OCCUPIED);

        setupSecurityContext(staffIdAdminA, "aud-propadmin-a" + TEST_DOMAIN);

        RoomStatusUpdateRequest updateReq = new RoomStatusUpdateRequest();
        updateReq.setStatus(RoomStatus.AVAILABLE);

        // Attempting to mark room as AVAILABLE must be rejected
        assertThatThrownBy(() -> roomService.updateRoomStatus(roomIdA, updateReq))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot mark room as AVAILABLE while it is occupied by an active checked-in reservation");

        // Verify status in DB remains OCCUPIED
        Room roomAfter = roomRepository.findById(roomIdA).orElseThrow();
        assertThat(roomAfter.getStatus()).isEqualTo(RoomStatus.OCCUPIED);

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    @Test
    @DisplayName("Legitimate maintenance: Room can be marked AVAILABLE when not occupied by checked-in reservation")
    void test02_roomStatus_canBeMarkedAvailable_whenNoCheckedInReservation() throws Exception {
        // Initially AUD-102 is AVAILABLE -> change to OUT_OF_SERVICE
        setupSecurityContext(staffIdAdminA, "aud-propadmin-a" + TEST_DOMAIN);

        RoomStatusUpdateRequest reqOos = new RoomStatusUpdateRequest();
        reqOos.setStatus(RoomStatus.OUT_OF_SERVICE);
        roomService.updateRoomStatus(roomIdA2, reqOos);

        Room roomOos = roomRepository.findById(roomIdA2).orElseThrow();
        assertThat(roomOos.getStatus()).isEqualTo(RoomStatus.OUT_OF_SERVICE);

        // Now change back to AVAILABLE
        RoomStatusUpdateRequest reqAvail = new RoomStatusUpdateRequest();
        reqAvail.setStatus(RoomStatus.AVAILABLE);
        roomService.updateRoomStatus(roomIdA2, reqAvail);

        Room roomAvail = roomRepository.findById(roomIdA2).orElseThrow();
        assertThat(roomAvail.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
    }

    @Test
    @DisplayName("Legitimate maintenance: Room can be marked OUT_OF_SERVICE when not occupied")
    void test03_roomStatus_canBeMarkedOutOfService_forMaintenance() throws Exception {
        setupSecurityContext(staffIdAdminA, "aud-propadmin-a" + TEST_DOMAIN);

        RoomStatusUpdateRequest reqOos = new RoomStatusUpdateRequest();
        reqOos.setStatus(RoomStatus.OUT_OF_SERVICE);
        roomService.updateRoomStatus(roomIdA2, reqOos);

        Room room = roomRepository.findById(roomIdA2).orElseThrow();
        assertThat(room.getStatus()).isEqualTo(RoomStatus.OUT_OF_SERVICE);
    }

    @Test
    @DisplayName("Case A & F: Concurrency between check-in and OUT_OF_SERVICE maintains consistency")
    void test04_concurrentCheckInAndOutOfService_safety() throws Exception {
        // Setup CONFIRMED reservation assigned to AUD-102
        Reservation res = new Reservation();
        res.setPropertyId(propertyIdA);
        res.setPrimaryGuestId(guestIdA);
        res.setBookingSourceId(bookingSourceDirectId);
        res.setCheckInDate(todayInPropZone);
        res.setCheckOutDate(todayInPropZone.plusDays(2));
        res.setAdults(1);
        res.setChildren(0);
        res.setInfants(0);
        res.setStatus(ReservationStatus.CONFIRMED);
        res.setCheckoutDue(false);
        res = reservationRepository.saveAndFlush(res);

        ReservationRoom rr = new ReservationRoom();
        rr.setReservationId(res.getId());
        rr.setRoomTypeId(roomTypeIdA);
        rr.setQuantity(1);
        rr.setCancelledQuantity(0);
        rr.setStatus(ReservationRoomStatus.CONFIRMED);
        rr.setUnitPrice(new BigDecimal("1000.00"));
        rr.setSubtotal(new BigDecimal("2000.00"));
        rr = reservationRoomRepository.saveAndFlush(rr);

        RoomAssignment ra = new RoomAssignment();
        ra.setReservationRoomId(rr.getId());
        ra.setRoomId(roomIdA2);
        ra.setAssignedFrom(todayInPropZone);
        ra.setAssignedUntil(todayInPropZone.plusDays(2));
        ra.setStatus(RoomAssignmentStatus.ASSIGNED);
        roomAssignmentRepository.saveAndFlush(ra);

        final UUID resId = res.getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicReference<String> checkInResult = new AtomicReference<>("NONE");
        AtomicReference<String> statusUpdateResult = new AtomicReference<>("NONE");

        // Thread 1: Check-in
        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                setupSecurityContext(staffIdAdminA, "aud-propadmin-a" + TEST_DOMAIN);
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

        // Thread 2: Update room status to OUT_OF_SERVICE
        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                setupSecurityContext(staffIdAdminA, "aud-propadmin-a" + TEST_DOMAIN);
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.execute(status -> {
                    RoomStatusUpdateRequest req = new RoomStatusUpdateRequest();
                    req.setStatus(RoomStatus.OUT_OF_SERVICE);
                    roomService.updateRoomStatus(roomIdA2, req);
                    return null;
                });
                statusUpdateResult.set("SUCCESS");
            } catch (Exception e) {
                statusUpdateResult.set("FAILED: " + e.getMessage());
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Verify database state is completely consistent
        Room finalRoom = roomRepository.findById(roomIdA2).orElseThrow();
        Reservation finalRes = reservationRepository.findById(resId).orElseThrow();

        if ("SUCCESS".equals(checkInResult.get())) {
            // Check-in committed first: reservation is CHECKED_IN
            assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
        } else {
            // OUT_OF_SERVICE committed first: check-in was safely rejected
            assertThat(checkInResult.get()).contains("is out of service and cannot be checked in");
            assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(finalRoom.getStatus()).isEqualTo(RoomStatus.OUT_OF_SERVICE);
        }
    }

    @Test
    @DisplayName("Failed check-in rolls back all partial changes atomically")
    void test05_failedCheckIn_rollsBackAllChanges() throws Exception {
        // Setup room AUD-102 as OUT_OF_SERVICE
        setupSecurityContext(staffIdAdminA, "aud-propadmin-a" + TEST_DOMAIN);
        RoomStatusUpdateRequest reqOos = new RoomStatusUpdateRequest();
        reqOos.setStatus(RoomStatus.OUT_OF_SERVICE);
        roomService.updateRoomStatus(roomIdA2, reqOos);

        Reservation res = new Reservation();
        res.setPropertyId(propertyIdA);
        res.setPrimaryGuestId(guestIdA);
        res.setBookingSourceId(bookingSourceDirectId);
        res.setCheckInDate(todayInPropZone);
        res.setCheckOutDate(todayInPropZone.plusDays(2));
        res.setAdults(1);
        res.setChildren(0);
        res.setInfants(0);
        res.setStatus(ReservationStatus.CONFIRMED);
        res.setCheckoutDue(false);
        res = reservationRepository.saveAndFlush(res);

        ReservationRoom rr = new ReservationRoom();
        rr.setReservationId(res.getId());
        rr.setRoomTypeId(roomTypeIdA);
        rr.setQuantity(1);
        rr.setCancelledQuantity(0);
        rr.setStatus(ReservationRoomStatus.CONFIRMED);
        rr.setUnitPrice(new BigDecimal("1000.00"));
        rr.setSubtotal(new BigDecimal("2000.00"));
        rr = reservationRoomRepository.saveAndFlush(rr);

        RoomAssignment ra = new RoomAssignment();
        ra.setReservationRoomId(rr.getId());
        ra.setRoomId(roomIdA2);
        ra.setAssignedFrom(todayInPropZone);
        ra.setAssignedUntil(todayInPropZone.plusDays(2));
        ra.setStatus(RoomAssignmentStatus.ASSIGNED);
        roomAssignmentRepository.saveAndFlush(ra);

        final UUID resId = res.getId();

        // Check-in must fail because room is OUT_OF_SERVICE
        assertThatThrownBy(() -> reservationService.checkInReservation(resId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("is out of service and cannot be checked in");

        // Verify complete rollback
        Reservation postRes = reservationRepository.findById(resId).orElseThrow();
        assertThat(postRes.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(postRes.getCheckoutDueAt()).isNull();

        Room postRoom = roomRepository.findById(roomIdA2).orElseThrow();
        assertThat(postRoom.getStatus()).isEqualTo(RoomStatus.OUT_OF_SERVICE);
    }

    @Test
    @DisplayName("Authorization: VIEWER is denied room status updates with HTTP 403")
    void test06_authorization_viewerCannotUpdateRoomStatus() throws Exception {
        RoomStatusUpdateRequest updateReq = new RoomStatusUpdateRequest();
        updateReq.setStatus(RoomStatus.OUT_OF_SERVICE);

        mockMvc.perform(patch("/api/v1/rooms/{id}/status", roomIdA2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access is denied."));
    }

    @Test
    @DisplayName("Authorization: Cross-property staff receives HTTP 404 for room on another property")
    void test07_authorization_crossPropertyStaffCannotUpdateRoomStatus() throws Exception {
        RoomStatusUpdateRequest updateReq = new RoomStatusUpdateRequest();
        updateReq.setStatus(RoomStatus.OUT_OF_SERVICE);

        // Staff assigned only to Property B tries to mutate room on Property A
        mockMvc.perform(patch("/api/v1/rooms/{id}/status", roomIdA2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Room not found with id: " + roomIdA2));
    }

    @Test
    @DisplayName("Authorization: RECEPTIONIST on assigned property can update room status")
    void test08_authorization_receptionistCanUpdateRoomStatusOnOwnProperty() throws Exception {
        RoomStatusUpdateRequest updateReq = new RoomStatusUpdateRequest();
        updateReq.setStatus(RoomStatus.OUT_OF_SERVICE);

        mockMvc.perform(patch("/api/v1/rooms/{id}/status", roomIdA2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID createCheckedInReservation(UUID propId, UUID roomId, UUID roomTypeId) {
        Reservation res = new Reservation();
        res.setPropertyId(propId);
        res.setPrimaryGuestId(guestIdA);
        res.setBookingSourceId(bookingSourceDirectId);
        res.setCheckInDate(todayInPropZone);
        res.setCheckOutDate(todayInPropZone.plusDays(2));
        res.setAdults(1);
        res.setChildren(0);
        res.setInfants(0);
        res.setStatus(ReservationStatus.CONFIRMED);
        res.setCheckoutDue(false);
        res = reservationRepository.saveAndFlush(res);

        ReservationRoom rr = new ReservationRoom();
        rr.setReservationId(res.getId());
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        rr.setCancelledQuantity(0);
        rr.setStatus(ReservationRoomStatus.CONFIRMED);
        rr.setUnitPrice(new BigDecimal("1000.00"));
        rr.setSubtotal(new BigDecimal("2000.00"));
        rr = reservationRoomRepository.saveAndFlush(rr);

        RoomAssignment ra = new RoomAssignment();
        ra.setReservationRoomId(rr.getId());
        ra.setRoomId(roomId);
        ra.setAssignedFrom(todayInPropZone);
        ra.setAssignedUntil(todayInPropZone.plusDays(2));
        ra.setStatus(RoomAssignmentStatus.ASSIGNED);
        roomAssignmentRepository.saveAndFlush(ra);

        setupSecurityContext(staffIdAdminA, "aud-propadmin-a" + TEST_DOMAIN);
        reservationService.checkInReservation(res.getId());

        Reservation refreshedRes = reservationRepository.findById(res.getId()).orElseThrow();
        assertThat(refreshedRes.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);

        return res.getId();
    }

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
                    return propertyRepository.saveAndFlush(prop).getId();
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
                    return roomTypeRepository.saveAndFlush(rt).getId();
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
                    return roomRepository.saveAndFlush(room).getId();
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
        return guestRepository.saveAndFlush(g).getId();
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
}
