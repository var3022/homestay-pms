package com.homestay.pms_backend.security.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
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
@DisplayName("PMS Phase 2 — Step 3.4.3.6C.3: Room Deactivation and Check-In Concurrency Safety Tests")
class RoomDeactivationCheckInConcurrencyIntegrationTest {

    private static final String TEST_DOMAIN = "@deact-audit.com";
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

        propertyIdA = getOrCreateProperty("Deact Property Alpha", "PROP_DEACT_A", PROPERTY_TZ);
        propertyIdB = getOrCreateProperty("Deact Property Beta", "PROP_DEACT_B", PROPERTY_TZ);

        roomTypeIdA = getOrCreateRoomType(propertyIdA, "Deact Room Type A", "RT_DEACT_A");
        roomIdA1 = getOrCreateRoom(propertyIdA, roomTypeIdA, "DEACT-101");
        roomIdA2 = getOrCreateRoom(propertyIdA, roomTypeIdA, "DEACT-102");
        roomIdB = getOrCreateRoom(propertyIdB, getOrCreateRoomType(propertyIdB, "Deact Room Type B", "RT_DEACT_B"), "DEACT-201");

        guestIdA = getOrCreateGuest("Deact Guest Alpha", "guest-deact@test.com", "+919911223344");
        guestPropertyAssociationService.createRegistrationAssociation(guestIdA, propertyIdA);

        bookingSourceDirectId = bookingSourceRepository.findByCode("DIRECT")
                .orElseThrow(() -> new IllegalStateException("DIRECT booking source not found"))
                .getId();

        staffIdAdminA = createTestStaff("deact-propadmin-a" + TEST_DOMAIN);
        assignStaffPropertyRole(staffIdAdminA, propertyIdA, StaffRole.PROPERTY_ADMIN);
        propAdminTokenA = generateTokenForStaff(staffIdAdminA, "deact-propadmin-a" + TEST_DOMAIN);

        receptionistTokenA = createStaffToken("deact-receptionist-a" + TEST_DOMAIN, StaffRole.RECEPTIONIST, propertyIdA);
        viewerTokenA = createStaffToken("deact-viewer-a" + TEST_DOMAIN, StaffRole.VIEWER, propertyIdA);
        propAdminTokenB = createStaffToken("deact-propadmin-b" + TEST_DOMAIN, StaffRole.PROPERTY_ADMIN, propertyIdB);
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
                    "DELETE FROM room_assignments WHERE room_id IN (SELECT id FROM rooms WHERE room_number IN ('DEACT-101', 'DEACT-102', 'DEACT-201')); " +
                    "DELETE FROM reservation_rooms WHERE room_type_id IN (SELECT id FROM room_types WHERE code IN ('RT_DEACT_A', 'RT_DEACT_B')); " +
                    "DELETE FROM reservations WHERE property_id IN (SELECT id FROM properties WHERE code IN ('PROP_DEACT_A', 'PROP_DEACT_B')); " +
                    "DELETE FROM rooms WHERE room_number IN ('DEACT-101', 'DEACT-102', 'DEACT-201'); " +
                    "DELETE FROM room_types WHERE code IN ('RT_DEACT_A', 'RT_DEACT_B'); " +
                    "DELETE FROM guest_property_associations WHERE guest_id IN (SELECT id FROM guests WHERE email = 'guest-deact@test.com'); " +
                    "DELETE FROM guests WHERE email = 'guest-deact@test.com'; " +
                    "DELETE FROM properties WHERE code IN ('PROP_DEACT_A', 'PROP_DEACT_B');"
            );
        }
    }

    // =========================================================================
    // Test 1: Deactivation blocked for actively checked-in room
    // =========================================================================

    @Test
    @DisplayName("Test 1: Deactivation is rejected with HTTP 400 when room is occupied by a CHECKED_IN reservation")
    void test01_deactivationBlocked_forActivelyCheckedInRoom() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, roomIdA1, roomTypeIdA);

        Room roomBefore = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(roomBefore.isActive()).isTrue();
        assertThat(roomBefore.getStatus()).isEqualTo(RoomStatus.OCCUPIED);

        // Attempt PATCH /api/v1/rooms/{id}/deactivate via MockMvc
        mockMvc.perform(patch("/api/v1/rooms/{id}/deactivate", roomIdA1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cannot deactivate room while it is occupied by an active checked-in reservation"));

        // Verify service layer also rejects directly
        setupSecurityContext(staffIdAdminA, "deact-propadmin-a" + TEST_DOMAIN);
        assertThatThrownBy(() -> roomService.deactivateRoom(roomIdA1))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot deactivate room while it is occupied by an active checked-in reservation");

        // Verify room remains active in DB
        Room roomAfter = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(roomAfter.isActive()).isTrue();
        assertThat(roomAfter.getStatus()).isEqualTo(RoomStatus.OCCUPIED);

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    // =========================================================================
    // Test 2: Deactivation permitted when no checked-in reservation occupies the room
    // =========================================================================

    @Test
    @DisplayName("Test 2: Deactivation succeeds with HTTP 204 when no checked-in reservation occupies the room")
    void test02_deactivationPermitted_whenNoCheckedInReservation() throws Exception {
        // Room DEACT-102 has no reservations and is active
        Room roomBefore = roomRepository.findById(roomIdA2).orElseThrow();
        assertThat(roomBefore.isActive()).isTrue();

        mockMvc.perform(patch("/api/v1/rooms/{id}/deactivate", roomIdA2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNoContent());

        Room roomAfter = roomRepository.findById(roomIdA2).orElseThrow();
        assertThat(roomAfter.isActive()).isFalse();
    }

    // =========================================================================
    // Test 3: Concurrent check-in and deactivation safety
    // =========================================================================

    @Test
    @DisplayName("Test 3: Concurrent check-in and deactivation serialize safely under row-level locks")
    void test03_concurrentCheckInAndDeactivation_safety() throws Exception {
        // Create CONFIRMED reservation assigned to DEACT-102
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
        AtomicReference<String> deactResult = new AtomicReference<>("NONE");

        // Thread 1: Check-in
        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                setupSecurityContext(staffIdAdminA, "deact-propadmin-a" + TEST_DOMAIN);
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

        // Thread 2: Deactivate
        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                setupSecurityContext(staffIdAdminA, "deact-propadmin-a" + TEST_DOMAIN);
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.execute(status -> {
                    roomService.deactivateRoom(roomIdA2);
                    return null;
                });
                deactResult.set("SUCCESS");
            } catch (Exception e) {
                deactResult.set("FAILED: " + e.getMessage());
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Verify post-commit state
        Room finalRoom = roomRepository.findById(roomIdA2).orElseThrow();
        Reservation finalRes = reservationRepository.findById(resId).orElseThrow();

        if ("SUCCESS".equals(checkInResult.get())) {
            // Check-in won the race: deactivation must have been rejected
            assertThat(deactResult.get()).contains("Cannot deactivate room while it is occupied by an active checked-in reservation");
            assertThat(finalRoom.isActive()).isTrue();
            assertThat(finalRoom.getStatus()).isEqualTo(RoomStatus.OCCUPIED);
            assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
        } else {
            // Deactivation won the race: check-in must have been safely rejected
            assertThat(deactResult.get()).isEqualTo("SUCCESS");
            assertThat(checkInResult.get()).contains("is inactive and cannot be checked in");
            assertThat(finalRoom.isActive()).isFalse();
            assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        }
    }

    // =========================================================================
    // Test 4: Failed deactivation leaves persisted state unchanged
    // =========================================================================

    @Test
    @DisplayName("Test 4: Failed deactivation on checked-in room leaves persisted room and reservation intact")
    void test04_failedDeactivation_leavesPersistedStateUnchanged() throws Exception {
        UUID resId = createCheckedInReservation(propertyIdA, roomIdA1, roomTypeIdA);

        setupSecurityContext(staffIdAdminA, "deact-propadmin-a" + TEST_DOMAIN);
        assertThatThrownBy(() -> roomService.deactivateRoom(roomIdA1))
                .isInstanceOf(BusinessValidationException.class);

        Room room = roomRepository.findById(roomIdA1).orElseThrow();
        assertThat(room.isActive()).isTrue();
        assertThat(room.getStatus()).isEqualTo(RoomStatus.OCCUPIED);

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    // =========================================================================
    // Test 5: Authorization - RECEPTIONIST cannot deactivate room (403)
    // =========================================================================

    @Test
    @DisplayName("Test 5: RECEPTIONIST lacks ROOM_MANAGE and receives HTTP 403 on deactivation")
    void test05_authorization_receptionistCannotDeactivateRoom() throws Exception {
        mockMvc.perform(patch("/api/v1/rooms/{id}/deactivate", roomIdA2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access is denied."));

        Room room = roomRepository.findById(roomIdA2).orElseThrow();
        assertThat(room.isActive()).isTrue();
    }

    // =========================================================================
    // Test 6: Authorization - VIEWER cannot deactivate room (403)
    // =========================================================================

    @Test
    @DisplayName("Test 6: VIEWER lacks ROOM_MANAGE and receives HTTP 403 on deactivation")
    void test06_authorization_viewerCannotDeactivateRoom() throws Exception {
        mockMvc.perform(patch("/api/v1/rooms/{id}/deactivate", roomIdA2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access is denied."));

        Room room = roomRepository.findById(roomIdA2).orElseThrow();
        assertThat(room.isActive()).isTrue();
    }

    // =========================================================================
    // Test 7: Authorization - Cross-property staff receives HTTP 404
    // =========================================================================

    @Test
    @DisplayName("Test 7: Cross-property PROPERTY_ADMIN receives anti-enumeration HTTP 404 on deactivation")
    void test07_authorization_crossPropertyStaffReceives404() throws Exception {
        // Staff assigned to Property B attempts to deactivate room in Property A
        mockMvc.perform(patch("/api/v1/rooms/{id}/deactivate", roomIdA2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Room not found with id: " + roomIdA2));

        Room room = roomRepository.findById(roomIdA2).orElseThrow();
        assertThat(room.isActive()).isTrue();
    }

    // =========================================================================
    // Test 8: Room status handling unaffected by deactivation rules
    // =========================================================================

    @Test
    @DisplayName("Test 8: Legitimate room status transitions (AVAILABLE <-> OUT_OF_SERVICE) remain unaffected")
    void test08_roomStatusHandling_unaffectedByDeactivationRules() throws Exception {
        setupSecurityContext(staffIdAdminA, "deact-propadmin-a" + TEST_DOMAIN);

        // Transition DEACT-102 to OUT_OF_SERVICE
        RoomStatusUpdateRequest oosReq = new RoomStatusUpdateRequest();
        oosReq.setStatus(RoomStatus.OUT_OF_SERVICE);
        roomService.updateRoomStatus(roomIdA2, oosReq);

        Room roomOos = roomRepository.findById(roomIdA2).orElseThrow();
        assertThat(roomOos.getStatus()).isEqualTo(RoomStatus.OUT_OF_SERVICE);
        assertThat(roomOos.isActive()).isTrue();

        // Transition back to AVAILABLE
        RoomStatusUpdateRequest availReq = new RoomStatusUpdateRequest();
        availReq.setStatus(RoomStatus.AVAILABLE);
        roomService.updateRoomStatus(roomIdA2, availReq);

        Room roomAvail = roomRepository.findById(roomIdA2).orElseThrow();
        assertThat(roomAvail.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(roomAvail.isActive()).isTrue();
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

        setupSecurityContext(staffIdAdminA, "deact-propadmin-a" + TEST_DOMAIN);
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
                    prop.setDescription("Integration test property for deactivation workflow");
                    prop.setAddressLine1("123 Deact Lane");
                    prop.setCity("Deact City");
                    prop.setState("Deact State");
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
                    rt.setDescription("Deact Room Type");
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
