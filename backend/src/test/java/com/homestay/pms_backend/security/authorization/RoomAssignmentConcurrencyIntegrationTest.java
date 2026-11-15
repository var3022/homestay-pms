package com.homestay.pms_backend.security.authorization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.homestay.pms_backend.dto.request.RoomAssignmentCreateRequest;
import com.homestay.pms_backend.dto.response.RoomAssignmentResponse;
import com.homestay.pms_backend.entity.*;
import com.homestay.pms_backend.enums.*;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.repository.*;
import com.homestay.pms_backend.security.AuthenticatedStaff;
import com.homestay.pms_backend.security.AuthenticatedStaffPrincipal;
import com.homestay.pms_backend.security.jwt.AccessTokenDto;
import com.homestay.pms_backend.security.jwt.AccessTokenService;
import com.homestay.pms_backend.service.GuestPropertyAssociationService;
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
@DisplayName("PMS Phase 2 — Step 3.4.3.4C: Reservation-Room Capacity Concurrency Integration Tests")
class RoomAssignmentConcurrencyIntegrationTest {

    private static final String TEST_DOMAIN = "@assign-concur-test.com";
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
    private UUID roomTypeIdB1;

    private UUID roomIdA101;
    private UUID roomIdA102;
    private UUID roomIdA103;
    private UUID roomIdB101;

    private UUID guestIdA;
    private UUID bookingSourceDirectId;

    private UUID staffIdAdminA;
    private UUID staffIdAdminB;
    private String propAdminTokenA;
    private String propAdminTokenB;
    private String superAdminToken;

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
        propertyIdA = getOrCreateProperty("Property Alpha Assign Concur", "PROP_A_ASGN_CONC");
        propertyIdB = getOrCreateProperty("Property Beta Assign Concur", "PROP_B_ASGN_CONC");

        // 2. Seed Room Types
        roomTypeIdA1 = getOrCreateRoomType(propertyIdA, "Deluxe A1 Concur", "RTA1_ASGN_CONC");
        roomTypeIdB1 = getOrCreateRoomType(propertyIdB, "Standard B1 Concur", "RTB1_ASGN_CONC");

        // 3. Seed Physical Rooms
        roomIdA101 = getOrCreateRoom(propertyIdA, roomTypeIdA1, "A-101-CONC");
        roomIdA102 = getOrCreateRoom(propertyIdA, roomTypeIdA1, "A-102-CONC");
        roomIdA103 = getOrCreateRoom(propertyIdA, roomTypeIdA1, "A-103-CONC");
        roomIdB101 = getOrCreateRoom(propertyIdB, roomTypeIdB1, "B-101-CONC");

        // 4. Seed Guest
        guestIdA = getOrCreateGuest("Guest Concur A", "guest-a" + TEST_DOMAIN, "+919876542101");
        guestPropertyAssociationService.createRegistrationAssociation(guestIdA, propertyIdA);

        // 5. Booking Source
        bookingSourceDirectId = bookingSourceRepository.findByCode("DIRECT")
                .orElseThrow(() -> new IllegalStateException("DIRECT booking source not found"))
                .getId();

        // 6. Seed Staff and Tokens
        superAdminToken = createStaffToken("superadmin" + TEST_DOMAIN, StaffRole.SUPER_ADMIN, null);

        staffIdAdminA = createTestStaff("propadmin-a" + TEST_DOMAIN, true, false);
        assignStaffPropertyRole(staffIdAdminA, propertyIdA, StaffRole.PROPERTY_ADMIN);
        propAdminTokenA = generateTokenForStaff(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);

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
                    "DELETE FROM room_assignments WHERE reservation_room_id IN (SELECT id FROM reservation_rooms WHERE reservation_id IN (SELECT id FROM reservations WHERE external_booking_id LIKE '%CONC%')); " +
                    "DELETE FROM reservation_rooms WHERE reservation_id IN (SELECT id FROM reservations WHERE external_booking_id LIKE '%CONC%'); " +
                    "DELETE FROM reservations WHERE external_booking_id LIKE '%CONC%'; " +
                    "DELETE FROM guest_property_associations WHERE guest_id IN (SELECT id FROM guests WHERE email LIKE '%" + TEST_DOMAIN + "'); " +
                    "DELETE FROM guests WHERE email LIKE '%" + TEST_DOMAIN + "'; " +
                    "DELETE FROM rooms WHERE room_number LIKE '%-CONC'; " +
                    "DELETE FROM room_types WHERE code IN ('RTA1_ASGN_CONC', 'RTB1_ASGN_CONC'); " +
                    "DELETE FROM properties WHERE code IN ('PROP_A_ASGN_CONC', 'PROP_B_ASGN_CONC');"
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
                    prop.setDescription("Concurrency test property");
                    prop.setAddressLine1("100 Concurrency Road");
                    prop.setCity("Concurrency City");
                    prop.setState("Concurrency State");
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

    private String createStaffToken(String email, StaffRole role, UUID propertyId) {
        UUID staffId = createTestStaff(email, true, false);
        if (role == StaffRole.SUPER_ADMIN) {
            adminJdbcTemplate.update(
                    "INSERT INTO staff_global_roles (staff_user_id, role_code, assigned_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                    staffId,
                    role.getCode()
            );
        } else if (role != null && propertyId != null) {
            assignStaffPropertyRole(staffId, propertyId, role);
        }
        return generateTokenForStaff(staffId, email);
    }

    private void setupSecurityContext(UUID staffId, String email) {
        AuthenticatedStaffPrincipal principal = new AuthenticatedStaffPrincipal(staffId, email, "Test", "Staff");
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    // =========================================================================
    // Core Concurrency Tests (Requirements 1-10)
    // =========================================================================

    @Test
    @DisplayName("1. Deterministic lock serialization: Two simultaneous requests for quantity=1; Tx A acquires lock first, Tx B blocks, exactly one succeeds")
    void test01_deterministicLockSerializationExceedingActiveQuantityBlocked() throws Exception {
        UUID resId = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-CONC-01",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        UUID resRoomId = createReservationRoom(resId, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        TransactionTemplate txTemplateA = new TransactionTemplate(transactionManager);
        txTemplateA.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        CountDownLatch txAHoldingLockLatch = new CountDownLatch(1);
        CountDownLatch txBAttemptedLatch = new CountDownLatch(1);

        AtomicReference<RoomAssignmentResponse> responseA = new AtomicReference<>();
        AtomicReference<Exception> exceptionB = new AtomicReference<>();

        // Thread A: Acquires PESSIMISTIC_WRITE lock on reservation room, signals, waits for B to attempt, assigns Room A-101, commits
        Future<?> futureA = executor.submit(() -> {
            try {
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                txTemplateA.execute(status -> {
                    // Step 2: Acquire PESSIMISTIC_WRITE lock
                    reservationRoomRepository.findByIdForUpdate(resRoomId).orElseThrow();

                    // Signal that Tx A now holds the lock on the parent reservation room
                    txAHoldingLockLatch.countDown();

                    // Wait until Tx B has attempted its request and blocked
                    try {
                        boolean signaled = txBAttemptedLatch.await(3, TimeUnit.SECONDS);
                        assertThat(signaled).as("Tx B should attempt assignment within timeout").isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }

                    // Complete assignment of Room A-101 within Tx A
                    RoomAssignmentCreateRequest reqA = new RoomAssignmentCreateRequest();
                    reqA.setReservationRoomId(resRoomId);
                    reqA.setRoomId(roomIdA101);
                    reqA.setAssignedFrom(LocalDate.of(2026, 12, 1));
                    reqA.setAssignedUntil(LocalDate.of(2026, 12, 5));

                    RoomAssignmentResponse resp = roomAssignmentService.assignRoom(reqA);
                    responseA.set(resp);
                    return resp;
                });
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        // Thread B: Waits until Tx A holds the lock, then attempts to assign Room A-102 on the same resRoomId
        Future<?> futureB = executor.submit(() -> {
            try {
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);

                // Wait until Tx A has acquired the lock
                boolean lockHeld = txAHoldingLockLatch.await(3, TimeUnit.SECONDS);
                assertThat(lockHeld).as("Tx A should hold lock before Tx B starts").isTrue();

                // Signal that Tx B is now proceeding to call assignRoom (which will block on findByIdForUpdate)
                txBAttemptedLatch.countDown();

                RoomAssignmentCreateRequest reqB = new RoomAssignmentCreateRequest();
                reqB.setReservationRoomId(resRoomId);
                reqB.setRoomId(roomIdA102);
                reqB.setAssignedFrom(LocalDate.of(2026, 12, 1));
                reqB.setAssignedUntil(LocalDate.of(2026, 12, 5));

                roomAssignmentService.assignRoom(reqB);
            } catch (Exception e) {
                exceptionB.set(e);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        futureA.get(10, TimeUnit.SECONDS);
        futureB.get(10, TimeUnit.SECONDS);

        // Verification of deterministic outcomes:
        // 1. Exactly one assignment succeeded (Tx A)
        assertThat(responseA.get()).isNotNull();
        assertThat(responseA.get().getRoomId()).isEqualTo(roomIdA101);
        assertThat(responseA.get().getStatus()).isEqualTo(RoomAssignmentStatus.ASSIGNED);

        // 2. Tx B failed with the expected domain validation error after unblocking and observing A's committed assignment
        assertThat(exceptionB.get()).isNotNull();
        assertThat(exceptionB.get()).isInstanceOf(BusinessValidationException.class);
        assertThat(exceptionB.get().getMessage()).contains("Reservation room already has the maximum number of room assignments");

        // 3. Database contains exactly one active assignment for that reservation room/date range
        List<RoomAssignment> persistedAssignments = roomAssignmentRepository.findByReservationRoomId(resRoomId);
        assertThat(persistedAssignments).hasSize(1);
        assertThat(persistedAssignments.get(0).getRoomId()).isEqualTo(roomIdA101);
        assertThat(persistedAssignments.get(0).getStatus()).isEqualTo(RoomAssignmentStatus.ASSIGNED);
    }

    @Test
    @DisplayName("2. Simultaneous race stress test: 4 concurrent assignment requests for activeQuantity=1; exactly 1 succeeds, 3 fail")
    void test02_simultaneousRaceStressTest() throws Exception {
        UUID resId = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-CONC-02",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        UUID resRoomId = createReservationRoom(resId, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        // Create 4 physical rooms of roomTypeIdA1
        UUID r1 = roomIdA101;
        UUID r2 = roomIdA102;
        UUID r3 = roomIdA103;
        UUID r4 = getOrCreateRoom(propertyIdA, roomTypeIdA1, "A-104-CONC");

        List<UUID> roomList = List.of(r1, r2, r3, r4);
        int threadCount = 4;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger capacityExceededCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final UUID assignedRoomId = roomList.get(i);
            futures.add(executor.submit(() -> {
                try {
                    setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                    barrier.await(5, TimeUnit.SECONDS);

                    RoomAssignmentCreateRequest req = new RoomAssignmentCreateRequest();
                    req.setReservationRoomId(resRoomId);
                    req.setRoomId(assignedRoomId);
                    req.setAssignedFrom(LocalDate.of(2026, 12, 1));
                    req.setAssignedUntil(LocalDate.of(2026, 12, 5));

                    roomAssignmentService.assignRoom(req);
                    successCount.incrementAndGet();
                } catch (BusinessValidationException e) {
                    if (e.getMessage().contains("maximum number of room assignments")) {
                        capacityExceededCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }

        // Exactly 1 succeeded, exactly 3 failed with capacity exceeded
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(capacityExceededCount.get()).isEqualTo(3);

        // Database must have exactly 1 active assignment
        List<RoomAssignment> assignments = roomAssignmentRepository.findByReservationRoomId(resRoomId);
        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).getStatus()).isEqualTo(RoomAssignmentStatus.ASSIGNED);
    }

    // =========================================================================
    // Independent Reservation Rooms & Different Rooms (Requirements 11 & 13)
    // =========================================================================

    @Test
    @DisplayName("3. Different reservation-room IDs proceed independently and succeed without blocking each other")
    void test03_independentReservationRoomsProceedConcurrently() throws Exception {
        UUID resId1 = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-CONC-03A",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        UUID resRoomId1 = createReservationRoom(resId1, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        UUID resId2 = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-CONC-03B",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        UUID resRoomId2 = createReservationRoom(resId2, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<RoomAssignmentResponse> resp1 = new AtomicReference<>();
        AtomicReference<RoomAssignmentResponse> resp2 = new AtomicReference<>();

        Future<?> f1 = executor.submit(() -> {
            try {
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                barrier.await(3, TimeUnit.SECONDS);

                RoomAssignmentCreateRequest req = new RoomAssignmentCreateRequest();
                req.setReservationRoomId(resRoomId1);
                req.setRoomId(roomIdA101);
                req.setAssignedFrom(LocalDate.of(2026, 12, 1));
                req.setAssignedUntil(LocalDate.of(2026, 12, 5));

                resp1.set(roomAssignmentService.assignRoom(req));
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        Future<?> f2 = executor.submit(() -> {
            try {
                setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                barrier.await(3, TimeUnit.SECONDS);

                RoomAssignmentCreateRequest req = new RoomAssignmentCreateRequest();
                req.setReservationRoomId(resRoomId2);
                req.setRoomId(roomIdA102);
                req.setAssignedFrom(LocalDate.of(2026, 12, 1));
                req.setAssignedUntil(LocalDate.of(2026, 12, 5));

                resp2.set(roomAssignmentService.assignRoom(req));
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);

        assertThat(resp1.get()).isNotNull();
        assertThat(resp1.get().getRoomId()).isEqualTo(roomIdA101);
        assertThat(resp2.get()).isNotNull();
        assertThat(resp2.get().getRoomId()).isEqualTo(roomIdA102);

        assertThat(roomAssignmentRepository.findByReservationRoomId(resRoomId1)).hasSize(1);
        assertThat(roomAssignmentRepository.findByReservationRoomId(resRoomId2)).hasSize(1);
    }

    // =========================================================================
    // Non-Overlapping Intervals on Same Reservation Room (Requirement 12)
    // =========================================================================

    @Test
    @DisplayName("4. Non-overlapping assignment intervals preserve existing semantics and both succeed")
    void test04_nonOverlappingAssignmentIntervalsPreserved() throws Exception {
        UUID resId = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-CONC-04",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 6), ReservationStatus.CONFIRMED);
        UUID resRoomId = createReservationRoom(resId, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        // Interval 1: Dec 1 to Dec 3 (Room A-101)
        RoomAssignmentCreateRequest req1 = new RoomAssignmentCreateRequest();
        req1.setReservationRoomId(resRoomId);
        req1.setRoomId(roomIdA101);
        req1.setAssignedFrom(LocalDate.of(2026, 12, 1));
        req1.setAssignedUntil(LocalDate.of(2026, 12, 3));

        // Interval 2: Dec 3 to Dec 6 (Room A-102) -> [Dec 1, Dec 3) and [Dec 3, Dec 6) do NOT overlap
        RoomAssignmentCreateRequest req2 = new RoomAssignmentCreateRequest();
        req2.setReservationRoomId(resRoomId);
        req2.setRoomId(roomIdA102);
        req2.setAssignedFrom(LocalDate.of(2026, 12, 3));
        req2.setAssignedUntil(LocalDate.of(2026, 12, 6));

        setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
        try {
            RoomAssignmentResponse resp1 = roomAssignmentService.assignRoom(req1);
            RoomAssignmentResponse resp2 = roomAssignmentService.assignRoom(req2);

            assertThat(resp1).isNotNull();
            assertThat(resp2).isNotNull();

            List<RoomAssignment> assignments = roomAssignmentRepository.findByReservationRoomId(resRoomId);
            assertThat(assignments).hasSize(2);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // =========================================================================
    // GiST Exclusion Constraint on Physical Room Overlap (Requirement 14)
    // =========================================================================

    @Test
    @DisplayName("5. Concurrent requests for the same physical room respect the GiST exclusion constraint and application check")
    void test05_concurrentRequestsSamePhysicalRoomRespectGiSTExclusionConstraint() throws Exception {
        UUID resId1 = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-CONC-05A",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        UUID resRoomId1 = createReservationRoom(resId1, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        UUID resId2 = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-CONC-05B",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        UUID resRoomId2 = createReservationRoom(resId2, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger roomConflictCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        List<UUID> targetRRs = List.of(resRoomId1, resRoomId2);

        for (int i = 0; i < 2; i++) {
            final UUID targetRR = targetRRs.get(i);
            futures.add(executor.submit(() -> {
                try {
                    setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                    barrier.await(3, TimeUnit.SECONDS);

                    RoomAssignmentCreateRequest req = new RoomAssignmentCreateRequest();
                    req.setReservationRoomId(targetRR);
                    req.setRoomId(roomIdA101); // BOTH target the same physical Room A-101
                    req.setAssignedFrom(LocalDate.of(2026, 12, 1));
                    req.setAssignedUntil(LocalDate.of(2026, 12, 5));

                    roomAssignmentService.assignRoom(req);
                    successCount.incrementAndGet();
                } catch (BusinessValidationException e) {
                    if (e.getMessage().contains("already assigned for the requested dates")) {
                        roomConflictCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Could also be DataIntegrityViolationException from PostgreSQL GiST constraint
                    if (e.getMessage() != null && e.getMessage().contains("ex_room_assignments_no_overlap")) {
                        roomConflictCount.incrementAndGet();
                    }
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }

        // Exactly one physical room assignment succeeds
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(roomConflictCount.get()).isEqualTo(1);

        // Physical room A-101 has exactly 1 active assignment
        List<RoomAssignment> roomAssignments = roomAssignmentRepository.findByRoomIdAndStatus(roomIdA101, RoomAssignmentStatus.ASSIGNED);
        assertThat(roomAssignments).hasSize(1);
    }

    // =========================================================================
    // Pre-Lock Authorization & Anti-Enumeration (Requirement 15)
    // =========================================================================

    @Test
    @DisplayName("6. Failed authorization occurs before row locking and leaves no mutation (HTTP 404)")
    void test06_failedAuthorizationOccursBeforeMutationOrLocking() throws Exception {
        UUID resId = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-CONC-06",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        UUID resRoomId = createReservationRoom(resId, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        RoomAssignmentCreateRequest request = new RoomAssignmentCreateRequest();
        request.setReservationRoomId(resRoomId);
        request.setRoomId(roomIdA101);
        request.setAssignedFrom(LocalDate.of(2026, 12, 1));
        request.setAssignedUntil(LocalDate.of(2026, 12, 5));

        // Staff from Property B attempts to assign room on Property A -> 404 anti-enumeration
        mockMvc.perform(post("/api/v1/room-assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("Reservation room not found with id: " + resRoomId)));

        // No lock was retained and no assignment created
        List<RoomAssignment> assignments = roomAssignmentRepository.findByReservationRoomId(resRoomId);
        assertThat(assignments).isEmpty();
    }

    // =========================================================================
    // Rollback On Failed Validation (Requirement 16)
    // =========================================================================

    @Test
    @DisplayName("7. Failed transactions leave no partial assignments")
    void test07_failedTransactionsLeaveNoPartialAssignments() {
        UUID resId = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-CONC-07",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        UUID resRoomId = createReservationRoom(resId, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        // Dates outside reservation dates (check-out is Dec 5, assigned until Dec 10)
        RoomAssignmentCreateRequest request = new RoomAssignmentCreateRequest();
        request.setReservationRoomId(resRoomId);
        request.setRoomId(roomIdA101);
        request.setAssignedFrom(LocalDate.of(2026, 12, 1));
        request.setAssignedUntil(LocalDate.of(2026, 12, 10));

        setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
        try {
            assertThatThrownBy(() -> roomAssignmentService.assignRoom(request))
                    .isInstanceOf(BusinessValidationException.class)
                    .hasMessageContaining("Room assignment dates must be within the reservation dates");

            // Database contains no partial or dangling assignment
            List<RoomAssignment> assignments = roomAssignmentRepository.findByReservationRoomId(resRoomId);
            assertThat(assignments).isEmpty();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // =========================================================================
    // CHECKED_IN Release Restrictions (Requirement 17)
    // =========================================================================

    @Test
    @DisplayName("8. Existing CHECKED_IN release restrictions remain effective")
    void test08_existingCheckedInReleaseRestrictionsRemainEffective() throws Exception {
        UUID resId = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-CONC-08",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        UUID resRoomId = createReservationRoom(resId, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
        RoomAssignmentResponse assignmentResponse;
        try {
            RoomAssignmentCreateRequest req = new RoomAssignmentCreateRequest();
            req.setReservationRoomId(resRoomId);
            req.setRoomId(roomIdA101);
            req.setAssignedFrom(LocalDate.of(2026, 12, 1));
            req.setAssignedUntil(LocalDate.of(2026, 12, 5));
            assignmentResponse = roomAssignmentService.assignRoom(req);
        } finally {
            SecurityContextHolder.clearContext();
        }

        // Transition reservation status to CHECKED_IN
        adminJdbcTemplate.update("UPDATE reservations SET status = 'CHECKED_IN' WHERE id = ?", resId);

        // Attempting to release assignment via HTTP generic release endpoint must fail with 400
        mockMvc.perform(patch("/api/v1/room-assignments/" + assignmentResponse.getId() + "/release")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Cannot release room assignment for a checked-in reservation")));

        // Status remains ASSIGNED
        RoomAssignment assignment = roomAssignmentRepository.findById(assignmentResponse.getId()).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(RoomAssignmentStatus.ASSIGNED);
    }

    @Test
    @DisplayName("9. Concurrent release requests on same assignment: serialized on parent lock and exactly one succeeds")
    void test09_concurrentReleaseRequestsOnSameAssignmentSerializedAndExactlyOneSucceeds() throws Exception {
        UUID resId = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-CONC-09",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        // Create reservation room with quantity 1
        UUID resRoomId = createReservationRoom(resId, roomTypeIdA1, 1, 0, new BigDecimal("1200.00"));

        setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
        RoomAssignmentResponse assignmentResponse;
        try {
            RoomAssignmentCreateRequest req = new RoomAssignmentCreateRequest();
            req.setReservationRoomId(resRoomId);
            req.setRoomId(roomIdA101);
            req.setAssignedFrom(LocalDate.of(2026, 12, 1));
            req.setAssignedUntil(LocalDate.of(2026, 12, 5));
            assignmentResponse = roomAssignmentService.assignRoom(req);
        } finally {
            SecurityContextHolder.clearContext();
        }

        // Set cancelledQuantity = 1 so requiredQuantity = 0, allowing release without replacement room
        adminJdbcTemplate.update("UPDATE reservation_rooms SET cancelled_quantity = 1 WHERE id = ?", resRoomId);

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger alreadyReleasedCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> {
                try {
                    setupSecurityContext(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);
                    barrier.await(3, TimeUnit.SECONDS);

                    roomAssignmentService.releaseRoomAssignment(assignmentResponse.getId());
                    successCount.incrementAndGet();
                } catch (BusinessValidationException e) {
                    if (e.getMessage().contains("Room assignment is already released")) {
                        alreadyReleasedCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }

        // Exactly one release succeeds, the second unblocks and observes currentStatus == RELEASED post-lock
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(alreadyReleasedCount.get()).isEqualTo(1);

        // Final persisted assignment status is RELEASED
        RoomAssignment assignment = roomAssignmentRepository.findById(assignmentResponse.getId()).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(RoomAssignmentStatus.RELEASED);
    }
}
