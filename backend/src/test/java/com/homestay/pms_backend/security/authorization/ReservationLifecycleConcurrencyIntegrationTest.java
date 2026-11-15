package com.homestay.pms_backend.security.authorization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.homestay.pms_backend.dto.request.ReservationUpdateRequest;
import com.homestay.pms_backend.dto.request.RoomAssignmentCreateRequest;
import com.homestay.pms_backend.dto.response.ReservationResponse;
import com.homestay.pms_backend.dto.response.RoomAssignmentResponse;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
@DisplayName("PMS Phase 2 — Step 3.4.3.4C.1: Reservation Lifecycle and Room-Assignment Concurrency Integration Tests")
class ReservationLifecycleConcurrencyIntegrationTest {

    private static final String TEST_DOMAIN = "@lifecycle-concur-test.com";
    private static final String VALID_BCRYPT_HASH = "$2a$10$wT0l8kPqSsqGzC.q0Qe11eZ7t6jPqXn3yEwK6Tq5Z7hC0d1z8Kz6W";

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
    private RoomAssignmentService roomAssignmentService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private GuestPropertyAssociationService guestPropertyAssociationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Environment environment;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = createObjectMapper();
    private JdbcTemplate adminJdbcTemplate;

    private UUID propertyIdA;
    private UUID propertyIdB;
    private UUID roomTypeIdA1;
    private UUID roomIdA101;
    private UUID roomIdA102;

    private UUID guestIdA;
    private UUID bookingSourceDirectId;
    private UUID bookingSourceOtaId;

    private UUID staffIdAdminA;
    private UUID staffIdViewerA;
    private UUID staffIdAdminB;
    private String propAdminTokenA;
    private String propViewerTokenA;
    private String propAdminTokenB;

    private ExecutorService executor;

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
        propertyIdA = getOrCreateProperty("Property Alpha Lifecycle Concur", "PROP_A_LC_CONC");
        propertyIdB = getOrCreateProperty("Property Beta Lifecycle Concur", "PROP_B_LC_CONC");

        // 2. Seed Room Types
        roomTypeIdA1 = getOrCreateRoomType(propertyIdA, "Deluxe A1 Lifecycle", "RTA1_LC_CONC");

        // 3. Seed Physical Rooms
        roomIdA101 = getOrCreateRoom(propertyIdA, roomTypeIdA1, "A-101-LC");
        roomIdA102 = getOrCreateRoom(propertyIdA, roomTypeIdA1, "A-102-LC");

        // 4. Seed Guest
        guestIdA = getOrCreateGuest("Guest Lifecycle A", "guest-a" + TEST_DOMAIN, "+919876542101");
        guestPropertyAssociationService.createRegistrationAssociation(guestIdA, propertyIdA);

        // 5. Booking Sources
        bookingSourceDirectId = bookingSourceRepository.findByCode("DIRECT")
                .orElseThrow(() -> new IllegalStateException("DIRECT booking source not found"))
                .getId();

        bookingSourceOtaId = bookingSourceRepository.findByCode("BOOKING_COM")
                .orElseGet(() -> {
                    BookingSource bs = new BookingSource();
                    bs.setCode("BOOKING_COM");
                    bs.setName("Booking.com");
                    bs.setActive(true);
                    return bookingSourceRepository.saveAndFlush(bs);
                }).getId();

        // 6. Seed Staff and Tokens
        staffIdAdminA = createTestStaff("propadmin-a" + TEST_DOMAIN, true, false);
        assignStaffPropertyRole(staffIdAdminA, propertyIdA, StaffRole.PROPERTY_ADMIN);
        propAdminTokenA = generateTokenForStaff(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);

        staffIdViewerA = createTestStaff("propviewer-a" + TEST_DOMAIN, true, false);
        assignStaffPropertyRole(staffIdViewerA, propertyIdA, StaffRole.VIEWER);
        propViewerTokenA = generateTokenForStaff(staffIdViewerA, "propviewer-a" + TEST_DOMAIN);

        staffIdAdminB = createTestStaff("propadmin-b" + TEST_DOMAIN, true, false);
        assignStaffPropertyRole(staffIdAdminB, propertyIdB, StaffRole.PROPERTY_ADMIN);
        propAdminTokenB = generateTokenForStaff(staffIdAdminB, "propadmin-b" + TEST_DOMAIN);

        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
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
                    "DELETE FROM room_assignments WHERE reservation_room_id IN (" +
                    "  SELECT rr.id FROM reservation_rooms rr JOIN reservations r ON r.id = rr.reservation_id WHERE r.external_booking_id LIKE 'RES-LC-%'); " +
                    "DELETE FROM reservation_rooms WHERE reservation_id IN (SELECT id FROM reservations WHERE external_booking_id LIKE 'RES-LC-%'); " +
                    "DELETE FROM reservations WHERE external_booking_id LIKE 'RES-LC-%';"
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
                    prop.setDescription("Lifecycle concurrency test property");
                    prop.setAddressLine1("100 Lifecycle Road");
                    prop.setCity("Lifecycle City");
                    prop.setState("Lifecycle State");
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
                    rt.setBaseOccupancy(2);
                    rt.setMaxOccupancy(4);
                    rt.setCategory(RoomTypeCategory.SUITE);
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
                    room.setFloor("1");
                    room.setStatus(RoomStatus.AVAILABLE);
                    room.setActive(true);
                    return roomRepository.save(room).getId();
                });
    }

    private UUID getOrCreateGuest(String name, String email, String phone) {
        return guestRepository.findByEmailIgnoreCase(email)
                .stream().findFirst()
                .map(Guest::getId)
                .orElseGet(() -> {
                    Guest g = new Guest();
                    g.setName(name);
                    g.setEmail(email);
                    g.setPhone(phone);
                    g.setActive(true);
                    return guestRepository.save(g).getId();
                });
    }

    private UUID createReservation(UUID propertyId, UUID guestId, UUID bookingSourceId, String externalId,
                                    LocalDate checkIn, LocalDate checkOut, ReservationStatus status) {
        Reservation r = new Reservation();
        r.setPropertyId(propertyId);
        r.setPrimaryGuestId(guestId);
        r.setBookingSourceId(bookingSourceId);
        r.setExternalBookingId(externalId);
        r.setCheckInDate(checkIn);
        r.setCheckOutDate(checkOut);
        r.setStatus(status);
        r.setAdults(2);
        r.setChildren(0);
        r.setInfants(0);
        return reservationRepository.save(r).getId();
    }

    private UUID createReservationRoom(UUID reservationId, UUID roomTypeId, int quantity, int cancelledQuantity, BigDecimal unitPrice) {
        ReservationRoom rr = new ReservationRoom();
        rr.setReservationId(reservationId);
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(quantity);
        rr.setCancelledQuantity(cancelledQuantity);
        rr.setUnitPrice(unitPrice);
        rr.setSubtotal(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        rr.setStatus(cancelledQuantity >= quantity ? ReservationRoomStatus.CANCELLED : ReservationRoomStatus.CONFIRMED);
        return reservationRoomRepository.save(rr).getId();
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

    private void setupSecurityContext(UUID staffId, String email) {
        AuthenticatedStaffPrincipal principal = new AuthenticatedStaffPrincipal(staffId, email, "Test", "Staff");
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    // =========================================================================
    // Concurrency Lifecycle Tests (Items A, B, C, D, G, H, I, J)
    // =========================================================================

    @Test
    @DisplayName("A. Assignment commits first, cancellation executes second -> reservation cancelled and assignments released")
    void testA_assignmentCommitsFirst_cancellationExecutesSecond() throws Exception {
        UUID resId = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-LC-A",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        UUID resRoomId = createReservationRoom(resId, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        CountDownLatch assignmentCommittedLatch = new CountDownLatch(1);
        AtomicReference<RoomAssignmentResponse> assignResp = new AtomicReference<>();
        AtomicReference<ReservationResponse> cancelResp = new AtomicReference<>();

        Future<?> futureAssign = executor.submit(() -> {
            try {
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                RoomAssignmentCreateRequest req = new RoomAssignmentCreateRequest();
                req.setReservationRoomId(resRoomId);
                req.setRoomId(roomIdA101);
                req.setAssignedFrom(LocalDate.of(2026, 12, 1));
                req.setAssignedUntil(LocalDate.of(2026, 12, 5));

                assignResp.set(roomAssignmentService.assignRoom(req));
                assignmentCommittedLatch.countDown();
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        Future<?> futureCancel = executor.submit(() -> {
            try {
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                boolean assigned = assignmentCommittedLatch.await(5, TimeUnit.SECONDS);
                assertThat(assigned).isTrue();

                cancelResp.set(reservationService.cancelReservation(resId));
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        futureAssign.get(10, TimeUnit.SECONDS);
        futureCancel.get(10, TimeUnit.SECONDS);

        assertThat(assignResp.get()).isNotNull();
        assertThat(cancelResp.get()).isNotNull();
        assertThat(cancelResp.get().getStatus()).isEqualTo(ReservationStatus.CANCELLED);

        // Verification of committed database state:
        Reservation persistedRes = reservationRepository.findById(resId).orElseThrow();
        assertThat(persistedRes.getStatus()).isEqualTo(ReservationStatus.CANCELLED);

        List<ReservationRoom> persistedRooms = reservationRoomRepository.findByReservationId(resId);
        assertThat(persistedRooms).hasSize(1);
        assertThat(persistedRooms.get(0).getStatus()).isEqualTo(ReservationRoomStatus.CANCELLED);
        assertThat(persistedRooms.get(0).getCancelledQuantity()).isEqualTo(1);

        // All assignments belonging to the cancelled reservation must be RELEASED
        List<RoomAssignment> persistedAssignments = roomAssignmentRepository.findByReservationRoomId(resRoomId);
        assertThat(persistedAssignments).hasSize(1);
        assertThat(persistedAssignments.get(0).getStatus()).isEqualTo(RoomAssignmentStatus.RELEASED);

        // Physical room A-101 has zero active assignments
        List<RoomAssignment> activeRoomAssignments = roomAssignmentRepository.findByRoomIdAndStatus(roomIdA101, RoomAssignmentStatus.ASSIGNED);
        assertThat(activeRoomAssignments).isEmpty();
    }

    @Test
    @DisplayName("B. Cancellation commits first, assignment executes second -> assignment rejected")
    void testB_cancellationCommitsFirst_assignmentExecutesSecond() throws Exception {
        UUID resId = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-LC-B",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        UUID resRoomId = createReservationRoom(resId, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        CountDownLatch cancellationCommittedLatch = new CountDownLatch(1);
        AtomicReference<Exception> assignException = new AtomicReference<>();

        Future<?> futureCancel = executor.submit(() -> {
            try {
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                reservationService.cancelReservation(resId);
                cancellationCommittedLatch.countDown();
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        Future<?> futureAssign = executor.submit(() -> {
            try {
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                boolean cancelled = cancellationCommittedLatch.await(5, TimeUnit.SECONDS);
                assertThat(cancelled).isTrue();

                RoomAssignmentCreateRequest req = new RoomAssignmentCreateRequest();
                req.setReservationRoomId(resRoomId);
                req.setRoomId(roomIdA101);
                req.setAssignedFrom(LocalDate.of(2026, 12, 1));
                req.setAssignedUntil(LocalDate.of(2026, 12, 5));

                roomAssignmentService.assignRoom(req);
            } catch (Exception e) {
                assignException.set(e);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        futureCancel.get(10, TimeUnit.SECONDS);
        futureAssign.get(10, TimeUnit.SECONDS);

        assertThat(assignException.get()).isNotNull();
        assertThat(assignException.get()).isInstanceOf(BusinessValidationException.class);
        assertThat(assignException.get().getMessage()).contains("Cannot assign a room to a cancelled reservation");

        // Zero assignments persisted
        List<RoomAssignment> assignments = roomAssignmentRepository.findByReservationRoomId(resRoomId);
        assertThat(assignments).isEmpty();
    }

    @Test
    @DisplayName("C. Assignment commits first, date update executes second -> conflicting date update rejected")
    void testC_assignmentCommitsFirst_dateUpdateExecutesSecond() throws Exception {
        UUID resId = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-LC-C",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        UUID resRoomId = createReservationRoom(resId, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        CountDownLatch assignmentCommittedLatch = new CountDownLatch(1);
        AtomicReference<Exception> updateException = new AtomicReference<>();

        // 1. Thread 1 assigns Room A-101 for Dec 1 to Dec 5
        Future<?> futureAssign = executor.submit(() -> {
            try {
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                RoomAssignmentCreateRequest req = new RoomAssignmentCreateRequest();
                req.setReservationRoomId(resRoomId);
                req.setRoomId(roomIdA101);
                req.setAssignedFrom(LocalDate.of(2026, 12, 1));
                req.setAssignedUntil(LocalDate.of(2026, 12, 5));

                roomAssignmentService.assignRoom(req);
                assignmentCommittedLatch.countDown();
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        // 2. Thread 2 attempts to change dates to Dec 1 to Dec 3 (shortened checkout conflicts with existing assignment ending Dec 5)
        Future<?> futureUpdate = executor.submit(() -> {
            try {
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                boolean assigned = assignmentCommittedLatch.await(5, TimeUnit.SECONDS);
                assertThat(assigned).isTrue();

                ReservationUpdateRequest updateReq = new ReservationUpdateRequest();
                updateReq.setCheckInDate(LocalDate.of(2026, 12, 1));
                updateReq.setCheckOutDate(LocalDate.of(2026, 12, 3));

                reservationService.updateReservation(resId, updateReq);
            } catch (Exception e) {
                updateException.set(e);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        futureAssign.get(10, TimeUnit.SECONDS);
        futureUpdate.get(10, TimeUnit.SECONDS);

        assertThat(updateException.get()).isNotNull();
        assertThat(updateException.get()).isInstanceOf(BusinessValidationException.class);
        assertThat(updateException.get().getMessage()).contains("does not cover the complete new stay period");

        // Reservation dates remain unchanged
        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getCheckInDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(res.getCheckOutDate()).isEqualTo(LocalDate.of(2026, 12, 5));
    }

    @Test
    @DisplayName("D. Date update commits first, assignment executes second -> out-of-range assignment rejected")
    void testD_dateUpdateCommitsFirst_assignmentExecutesSecond() throws Exception {
        UUID resId = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-LC-D",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        UUID resRoomId = createReservationRoom(resId, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        CountDownLatch dateUpdateCommittedLatch = new CountDownLatch(1);
        AtomicReference<Exception> assignException = new AtomicReference<>();

        // 1. Thread 1 updates reservation checkout to Dec 3
        Future<?> futureUpdate = executor.submit(() -> {
            try {
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                ReservationUpdateRequest updateReq = new ReservationUpdateRequest();
                updateReq.setCheckInDate(LocalDate.of(2026, 12, 1));
                updateReq.setCheckOutDate(LocalDate.of(2026, 12, 3));

                reservationService.updateReservation(resId, updateReq);
                dateUpdateCommittedLatch.countDown();
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        // 2. Thread 2 attempts to assign room for OLD dates: Dec 1 to Dec 5 (exceeds new checkout Dec 3)
        Future<?> futureAssign = executor.submit(() -> {
            try {
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                boolean updated = dateUpdateCommittedLatch.await(5, TimeUnit.SECONDS);
                assertThat(updated).isTrue();

                RoomAssignmentCreateRequest req = new RoomAssignmentCreateRequest();
                req.setReservationRoomId(resRoomId);
                req.setRoomId(roomIdA101);
                req.setAssignedFrom(LocalDate.of(2026, 12, 1));
                req.setAssignedUntil(LocalDate.of(2026, 12, 5));

                roomAssignmentService.assignRoom(req);
            } catch (Exception e) {
                assignException.set(e);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        futureUpdate.get(10, TimeUnit.SECONDS);
        futureAssign.get(10, TimeUnit.SECONDS);

        assertThat(assignException.get()).isNotNull();
        assertThat(assignException.get()).isInstanceOf(BusinessValidationException.class);
        assertThat(assignException.get().getMessage()).contains("Room assignment dates must be within the reservation dates");

        // Zero assignments created
        List<RoomAssignment> assignments = roomAssignmentRepository.findByReservationRoomId(resRoomId);
        assertThat(assignments).isEmpty();
    }

    @Test
    @DisplayName("G. Concurrent release and cancellation converge on consistent terminal state")
    void testG_concurrentReleaseAndCancellation() throws Exception {
        UUID resId = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-LC-G",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        UUID resRoomId = createReservationRoom(resId, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
        RoomAssignmentResponse assignResp;
        try {
            RoomAssignmentCreateRequest req = new RoomAssignmentCreateRequest();
            req.setReservationRoomId(resRoomId);
            req.setRoomId(roomIdA101);
            req.setAssignedFrom(LocalDate.of(2026, 12, 1));
            req.setAssignedUntil(LocalDate.of(2026, 12, 5));
            assignResp = roomAssignmentService.assignRoom(req);
        } finally {
            SecurityContextHolder.clearContext();
        }

        // Set cancelledQuantity = 1 so release coverage check allows releasing assignment
        adminJdbcTemplate.update("UPDATE reservation_rooms SET cancelled_quantity = 1 WHERE id = ?", resRoomId);

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        // Thread 1: release assignment
        futures.add(executor.submit(() -> {
            try {
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                barrier.await(3, TimeUnit.SECONDS);
                roomAssignmentService.releaseRoomAssignment(assignResp.getId());
                successCount.incrementAndGet();
            } catch (BusinessValidationException ignored) {
                // If cancellation committed first, assignment is already released
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }));

        // Thread 2: cancel reservation
        futures.add(executor.submit(() -> {
            try {
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                barrier.await(3, TimeUnit.SECONDS);
                reservationService.cancelReservation(resId);
                successCount.incrementAndGet();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }));

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }

        // Final state verification:
        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CANCELLED);

        RoomAssignment assignment = roomAssignmentRepository.findById(assignResp.getId()).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(RoomAssignmentStatus.RELEASED);
    }

    @Test
    @DisplayName("H. Cross-property authorization regression fails fast before locking (HTTP 404 anti-enumeration and 403 forbidden)")
    void testH_crossPropertyAuthorizationRegression() throws Exception {
        UUID resId = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-LC-H",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        UUID resRoomId = createReservationRoom(resId, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        RoomAssignmentCreateRequest request = new RoomAssignmentCreateRequest();
        request.setReservationRoomId(resRoomId);
        request.setRoomId(roomIdA101);
        request.setAssignedFrom(LocalDate.of(2026, 12, 1));
        request.setAssignedUntil(LocalDate.of(2026, 12, 5));

        // 1. Staff from Property B receives 404 (anti-enumeration)
        mockMvc.perform(post("/api/v1/room-assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("Reservation room not found with id: " + resRoomId)));

        // 2. Staff with VIEWER role on Property A receives 403 (lacks RESERVATION_MANAGE)
        mockMvc.perform(post("/api/v1/room-assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propViewerTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // Zero assignments created
        List<RoomAssignment> assignments = roomAssignmentRepository.findByReservationRoomId(resRoomId);
        assertThat(assignments).isEmpty();
    }

    @Test
    @DisplayName("I. Existing OTA cancellation and update guards remain preserved (HTTP 400 rejection)")
    void testI_otaCancellationAndUpdateGuardsPreserved() {
        UUID resId = createReservation(propertyIdA, guestIdA, bookingSourceOtaId, "RES-LC-I",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        createReservationRoom(resId, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
        try {
            // OTA cancellation rejected
            assertThatThrownBy(() -> reservationService.cancelReservation(resId))
                    .isInstanceOf(BusinessValidationException.class)
                    .hasMessageContaining("OTA bookings cannot be cancelled locally");

            // OTA update rejected
            ReservationUpdateRequest updateReq = new ReservationUpdateRequest();
            updateReq.setAdults(3);
            assertThatThrownBy(() -> reservationService.updateReservation(resId, updateReq))
                    .isInstanceOf(BusinessValidationException.class)
                    .hasMessageContaining("OTA reservations cannot be modified through the generic update endpoint");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("J. Deterministic interleaving: Cancellation holds lock first, concurrent assignRoom blocks on reservation lock and fails with 400 upon unblocking")
    void testJ_deterministicInterleavingCancellationHoldsLock_assignmentBlocksAndFails() throws Exception {
        UUID resId = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-LC-J",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        UUID resRoomId = createReservationRoom(resId, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        TransactionTemplate txTemplateCancel = new TransactionTemplate(transactionManager);
        txTemplateCancel.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        CountDownLatch txCancelHoldingLockLatch = new CountDownLatch(1);
        CountDownLatch txAssignAttemptedLatch = new CountDownLatch(1);

        AtomicReference<ReservationResponse> cancelResp = new AtomicReference<>();
        AtomicReference<Exception> assignException = new AtomicReference<>();

        // Thread 1: Starts cancel transaction, acquires lock on reservation & reservation rooms, signals latch, waits for assign to attempt
        Future<?> futureCancel = executor.submit(() -> {
            try {
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                txTemplateCancel.execute(status -> {
                    // Step 1: Acquire Level 1 lock on Reservation
                    reservationRepository.findByIdForUpdate(resId).orElseThrow();
                    // Step 2: Acquire Level 2 lock on child ReservationRooms
                    reservationRoomRepository.findByReservationIdForUpdate(resId);

                    // Signal that Tx Cancel holds the locks
                    txCancelHoldingLockLatch.countDown();

                    // Wait until assign attempt is blocked
                    try {
                        boolean signaled = txAssignAttemptedLatch.await(3, TimeUnit.SECONDS);
                        assertThat(signaled).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }

                    // Complete cancellation
                    ReservationResponse resp = reservationService.cancelReservation(resId);
                    cancelResp.set(resp);
                    return resp;
                });
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        // Thread 2: Waits until cancel holds lock, then attempts assignRoom (which blocks on reservationRepository.findByIdForUpdate)
        Future<?> futureAssign = executor.submit(() -> {
            try {
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);

                boolean lockHeld = txCancelHoldingLockLatch.await(3, TimeUnit.SECONDS);
                assertThat(lockHeld).isTrue();

                txAssignAttemptedLatch.countDown();

                RoomAssignmentCreateRequest req = new RoomAssignmentCreateRequest();
                req.setReservationRoomId(resRoomId);
                req.setRoomId(roomIdA101);
                req.setAssignedFrom(LocalDate.of(2026, 12, 1));
                req.setAssignedUntil(LocalDate.of(2026, 12, 5));

                // Blocks until Tx Cancel commits, then observes CANCELLED status and throws 400
                roomAssignmentService.assignRoom(req);
            } catch (Exception e) {
                assignException.set(e);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        futureCancel.get(10, TimeUnit.SECONDS);
        futureAssign.get(10, TimeUnit.SECONDS);

        assertThat(cancelResp.get()).isNotNull();
        assertThat(cancelResp.get().getStatus()).isEqualTo(ReservationStatus.CANCELLED);

        assertThat(assignException.get()).isNotNull();
        assertThat(assignException.get()).isInstanceOf(BusinessValidationException.class);
        assertThat(assignException.get().getMessage()).contains("Cannot assign a room to a cancelled reservation");

        // Database assertion: Zero assignments created, reservation is CANCELLED
        List<RoomAssignment> assignments = roomAssignmentRepository.findByReservationRoomId(resRoomId);
        assertThat(assignments).isEmpty();

        Reservation res = reservationRepository.findById(resId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }
}
