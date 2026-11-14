package com.homestay.pms_backend.security.authorization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.homestay.pms_backend.config.SecurityEnforcementProperties;
import com.homestay.pms_backend.dto.request.RoomAssignmentCreateRequest;
import com.homestay.pms_backend.dto.response.RoomAssignmentResponse;
import com.homestay.pms_backend.entity.*;
import com.homestay.pms_backend.enums.ReservationStatus;
import com.homestay.pms_backend.enums.RoomAssignmentStatus;
import com.homestay.pms_backend.enums.RoomStatus;
import com.homestay.pms_backend.enums.RoomTypeCategory;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@IsolatedDatabaseTest
@TestPropertySource(properties = {
        "auth.jwt.enforcement.enabled=true",
        "TEST_AUTH_JWT_ENFORCEMENT_ENABLED=true"
})
@DisplayName("PMS Phase 2 — Step 3.4.3.4B: Room-Assignment Property Authorization Integration Tests")
class RoomAssignmentSecurityIntegrationTest {

    private static final String TEST_DOMAIN = "@room-asgn-auth.com";
    private static final String VALID_BCRYPT_HASH = "$2a$10$wT0l8kPqSsqGzC.q0Qe11eZ7t6jPqXn3yEwK6Tq5Z7hC0d1z8Kz6W";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private SecurityEnforcementProperties enforcementProperties;

    @Autowired
    private RoomAssignmentService roomAssignmentService;

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
    private GuestPropertyAssociationService guestPropertyAssociationService;

    @Autowired
    private Environment environment;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = createObjectMapper();
    private JdbcTemplate adminJdbcTemplate;

    private UUID propertyIdA;
    private UUID propertyIdB;
    private UUID roomTypeIdA1;
    private UUID roomTypeIdA2;
    private UUID roomTypeIdB1;

    private UUID roomIdA101;
    private UUID roomIdA102;
    private UUID roomIdA201;
    private UUID roomIdB101;

    private UUID guestIdA;
    private UUID guestIdB;
    private UUID bookingSourceDirectId;

    private UUID reservationIdA;
    private UUID reservationIdB;
    private UUID reservationRoomIdA1;
    private UUID reservationRoomIdB1;

    private UUID assignmentIdA1;
    private UUID assignmentIdB1;

    private UUID staffIdAdminA;
    private UUID staffIdReceptionistA;
    private UUID staffIdViewerA;
    private UUID staffIdAccountantA;
    private UUID staffIdAdminB;
    private UUID staffIdDual;
    private UUID staffIdUnassigned;

    private String superAdminToken;
    private String propAdminTokenA;
    private String receptionistTokenA;
    private String viewerTokenA;
    private String accountantTokenA;
    private String propAdminTokenB;
    private String dualAdminToken;
    private String unassignedStaffToken;

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
        propertyIdA = getOrCreateProperty("La Vivante Assign", "PROP_ALPHA_ASGN");
        propertyIdB = getOrCreateProperty("El Hogar Assign", "PROP_BETA_ASGN");

        // 2. Seed Room Types
        roomTypeIdA1 = getOrCreateRoomType(propertyIdA, "Deluxe Assign A1", "RTA1_ASGN");
        roomTypeIdA2 = getOrCreateRoomType(propertyIdA, "Executive Assign A2", "RTA2_ASGN");
        roomTypeIdB1 = getOrCreateRoomType(propertyIdB, "Standard Assign B1", "RTB1_ASGN");

        // 3. Seed Physical Rooms
        roomIdA101 = getOrCreateRoom(propertyIdA, roomTypeIdA1, "A-101-ASGN");
        roomIdA102 = getOrCreateRoom(propertyIdA, roomTypeIdA1, "A-102-ASGN");
        roomIdA201 = getOrCreateRoom(propertyIdA, roomTypeIdA2, "A-201-ASGN");
        roomIdB101 = getOrCreateRoom(propertyIdB, roomTypeIdB1, "B-101-ASGN");

        // 4. Seed Guests
        guestIdA = getOrCreateGuest("Guest Alpha Assign", "guest-a" + TEST_DOMAIN, "+919876543101");
        guestIdB = getOrCreateGuest("Guest Beta Assign", "guest-b" + TEST_DOMAIN, "+919876543102");
        guestPropertyAssociationService.createRegistrationAssociation(guestIdA, propertyIdA);
        guestPropertyAssociationService.createRegistrationAssociation(guestIdB, propertyIdB);

        // 5. Booking Source
        bookingSourceDirectId = bookingSourceRepository.findByCode("DIRECT")
                .orElseThrow(() -> new IllegalStateException("DIRECT booking source not found"))
                .getId();

        // 6. Seed Reservations and Reservation Rooms
        // Reservation A: 2 rooms requested of RoomType A1
        reservationIdA = createReservation(propertyIdA, guestIdA, bookingSourceDirectId, "RES-A-ASGN",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        reservationRoomIdA1 = createReservationRoom(reservationIdA, roomTypeIdA1, 2, 0, new BigDecimal("1200.00"));

        // Reservation B: 1 room requested of RoomType B1
        reservationIdB = createReservation(propertyIdB, guestIdB, bookingSourceDirectId, "RES-B-ASGN",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), ReservationStatus.CONFIRMED);
        reservationRoomIdB1 = createReservationRoom(reservationIdB, roomTypeIdB1, 1, 0, new BigDecimal("1800.00"));

        // 7. Seed Initial Room Assignments
        assignmentIdA1 = createRoomAssignment(reservationRoomIdA1, roomIdA101,
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), RoomAssignmentStatus.ASSIGNED);
        assignmentIdB1 = createRoomAssignment(reservationRoomIdB1, roomIdB101,
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), RoomAssignmentStatus.ASSIGNED);

        // 8. Seed Staff and Tokens
        superAdminToken = createStaffToken("superadmin" + TEST_DOMAIN, StaffRole.SUPER_ADMIN, null);

        staffIdAdminA = createTestStaff("propadmin-a" + TEST_DOMAIN, true, false);
        assignStaffPropertyRole(staffIdAdminA, propertyIdA, StaffRole.PROPERTY_ADMIN);
        propAdminTokenA = generateTokenForStaff(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);

        staffIdReceptionistA = createTestStaff("receptionist-a" + TEST_DOMAIN, true, false);
        assignStaffPropertyRole(staffIdReceptionistA, propertyIdA, StaffRole.RECEPTIONIST);
        receptionistTokenA = generateTokenForStaff(staffIdReceptionistA, "receptionist-a" + TEST_DOMAIN);

        staffIdViewerA = createTestStaff("viewer-a" + TEST_DOMAIN, true, false);
        assignStaffPropertyRole(staffIdViewerA, propertyIdA, StaffRole.VIEWER);
        viewerTokenA = generateTokenForStaff(staffIdViewerA, "viewer-a" + TEST_DOMAIN);

        staffIdAccountantA = createTestStaff("accountant-a" + TEST_DOMAIN, true, false);
        assignStaffPropertyRole(staffIdAccountantA, propertyIdA, StaffRole.ACCOUNTANT);
        accountantTokenA = generateTokenForStaff(staffIdAccountantA, "accountant-a" + TEST_DOMAIN);

        staffIdAdminB = createTestStaff("propadmin-b" + TEST_DOMAIN, true, false);
        assignStaffPropertyRole(staffIdAdminB, propertyIdB, StaffRole.PROPERTY_ADMIN);
        propAdminTokenB = generateTokenForStaff(staffIdAdminB, "propadmin-b" + TEST_DOMAIN);

        staffIdDual = createTestStaff("dualadmin" + TEST_DOMAIN, true, false);
        assignStaffPropertyRole(staffIdDual, propertyIdA, StaffRole.PROPERTY_ADMIN);
        assignStaffPropertyRole(staffIdDual, propertyIdB, StaffRole.PROPERTY_ADMIN);
        dualAdminToken = generateTokenForStaff(staffIdDual, "dualadmin" + TEST_DOMAIN);

        staffIdUnassigned = createTestStaff("unassigned" + TEST_DOMAIN, true, false);
        unassignedStaffToken = generateTokenForStaff(staffIdUnassigned, "unassigned" + TEST_DOMAIN);
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
                    "DELETE FROM room_assignments WHERE reservation_room_id IN (SELECT id FROM reservation_rooms WHERE reservation_id IN (SELECT id FROM reservations WHERE external_booking_id LIKE '%ASGN%')); " +
                    "DELETE FROM reservation_rooms WHERE reservation_id IN (SELECT id FROM reservations WHERE external_booking_id LIKE '%ASGN%'); " +
                    "DELETE FROM reservations WHERE external_booking_id LIKE '%ASGN%'; " +
                    "DELETE FROM guest_property_associations WHERE guest_id IN (SELECT id FROM guests WHERE email LIKE '%" + TEST_DOMAIN + "'); " +
                    "DELETE FROM guests WHERE email LIKE '%" + TEST_DOMAIN + "'; " +
                    "DELETE FROM rooms WHERE room_number LIKE '%-ASGN'; " +
                    "DELETE FROM room_types WHERE code IN ('RTA1_ASGN', 'RTA2_ASGN', 'RTB1_ASGN'); " +
                    "DELETE FROM properties WHERE code IN ('PROP_ALPHA_ASGN', 'PROP_BETA_ASGN');"
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
                    prop.setDescription("Room assignment test property");
                    prop.setAddressLine1("100 Assignment Road");
                    prop.setCity("Assignment City");
                    prop.setState("Assignment State");
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
                    return roomTypeRepository.save(rt).getId();
                });
    }

    private UUID getOrCreateRoom(UUID propertyId, UUID roomTypeId, String roomNumber) {
        return roomRepository.findByPropertyIdAndRoomNumber(propertyId, roomNumber)
                .map(Room::getId)
                .orElseGet(() -> {
                    Room r = new Room();
                    r.setPropertyId(propertyId);
                    r.setRoomTypeId(roomTypeId);
                    r.setRoomNumber(roomNumber);
                    r.setName("Room " + roomNumber);
                    r.setFloor("1");
                    r.setActive(true);
                    r.setStatus(RoomStatus.AVAILABLE);
                    return roomRepository.save(r).getId();
                });
    }

    private UUID getOrCreateGuest(String name, String email, String phone) {
        return guestRepository.findByEmailIgnoreCase(email).stream().findFirst()
                .map(Guest::getId)
                .orElseGet(() -> {
                    Guest g = new Guest();
                    g.setName(name);
                    g.setEmail(email);
                    g.setPhone(phone);
                    g.setIdNumber("P99887711");
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
        rr.setStatus(com.homestay.pms_backend.enums.ReservationRoomStatus.CONFIRMED);
        return reservationRoomRepository.save(rr).getId();
    }

    private UUID createRoomAssignment(UUID reservationRoomId, UUID roomId, LocalDate from, LocalDate until, RoomAssignmentStatus status) {
        RoomAssignment ra = new RoomAssignment();
        ra.setReservationRoomId(reservationRoomId);
        ra.setRoomId(roomId);
        ra.setAssignedFrom(from);
        ra.setAssignedUntil(until);
        ra.setStatus(status);
        return roomAssignmentRepository.save(ra).getId();
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

    // =========================================================================
    // 1-4. Room Assignment Creation & Cross-Property Anti-Enumeration
    // =========================================================================

    @Test
    @DisplayName("1. Authorized receptionist room assignment succeeds (201 Created)")
    void test01_authorizedReceptionistAssignmentSucceeds() throws Exception {
        RoomAssignmentCreateRequest request = new RoomAssignmentCreateRequest();
        request.setReservationRoomId(reservationRoomIdA1);
        request.setRoomId(roomIdA102); // 2nd room for 2-quantity reservation room
        request.setAssignedFrom(LocalDate.of(2026, 12, 1));
        request.setAssignedUntil(LocalDate.of(2026, 12, 5));

        mockMvc.perform(post("/api/v1/room-assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.reservationRoomId").value(reservationRoomIdA1.toString()))
                .andExpect(jsonPath("$.roomId").value(roomIdA102.toString()))
                .andExpect(jsonPath("$.status").value("ASSIGNED"));
    }

    @Test
    @DisplayName("2. Foreign reservationRoomId assignment attempt returns uniform 404")
    void test02_foreignReservationRoomIdAssignmentReturns404() throws Exception {
        RoomAssignmentCreateRequest request = new RoomAssignmentCreateRequest();
        request.setReservationRoomId(reservationRoomIdB1); // Foreign to Property A
        request.setRoomId(roomIdA101);
        request.setAssignedFrom(LocalDate.of(2026, 12, 1));
        request.setAssignedUntil(LocalDate.of(2026, 12, 5));

        mockMvc.perform(post("/api/v1/room-assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("Reservation room not found with id: " + reservationRoomIdB1)));
    }

    @Test
    @DisplayName("3. Foreign roomId assignment attempt returns uniform 404 (anti-enumeration defense)")
    void test03_foreignRoomIdAssignmentReturns404() throws Exception {
        RoomAssignmentCreateRequest request = new RoomAssignmentCreateRequest();
        request.setReservationRoomId(reservationRoomIdA1);
        request.setRoomId(roomIdB101); // Foreign to Property A
        request.setAssignedFrom(LocalDate.of(2026, 12, 1));
        request.setAssignedUntil(LocalDate.of(2026, 12, 5));

        // Must return 404 for foreign room, NEVER an informative 400 mismatch error
        mockMvc.perform(post("/api/v1/room-assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("Room not found with id: " + roomIdB101)));
    }

    @Test
    @DisplayName("4. Two in-scope but mismatched properties retain business validation rejection (400 Bad Request)")
    void test04_twoInScopeMismatchedPropertiesReturns400BusinessValidation() throws Exception {
        RoomAssignmentCreateRequest request = new RoomAssignmentCreateRequest();
        request.setReservationRoomId(reservationRoomIdA1); // In scope via dualAdminToken
        request.setRoomId(roomIdB101); // In scope via dualAdminToken
        request.setAssignedFrom(LocalDate.of(2026, 12, 1));
        request.setAssignedUntil(LocalDate.of(2026, 12, 5));

        mockMvc.perform(post("/api/v1/room-assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dualAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Room does not belong to the reservation property")));
    }

    // =========================================================================
    // 5-8. Direct Read and Release Authorization
    // =========================================================================

    @Test
    @DisplayName("5. Foreign room assignment GET returns uniform 404")
    void test05_foreignAssignmentGetReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/room-assignments/" + assignmentIdB1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("Room assignment not found with id: " + assignmentIdB1)));
    }

    @Test
    @DisplayName("6. Nonexistent room assignment GET returns indistinguishable 404")
    void test06_nonexistentAssignmentGetReturnsIndistinguishable404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/room-assignments/" + nonExistentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("Room assignment not found with id: " + nonExistentId)));
    }

    @Test
    @DisplayName("7. Foreign room assignment release returns uniform 404")
    void test07_foreignAssignmentReleaseReturns404() throws Exception {
        mockMvc.perform(patch("/api/v1/room-assignments/" + assignmentIdB1 + "/release")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("Room assignment not found with id: " + assignmentIdB1)));
    }

    @Test
    @DisplayName("8. Authorized release obeys existing lifecycle and coverage rules")
    void test08_authorizedReleaseObeysLifecycleAndCoverageRules() throws Exception {
        // A. Attempting to release when remaining coverage is insufficient throws 400
        // Currently reservationRoomIdA1 has requiredQuantity = 2, but only 1 room (A101) is assigned
        mockMvc.perform(patch("/api/v1/room-assignments/" + assignmentIdA1 + "/release")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("insufficient room coverage")));

        // B. Assign second room so quantity coverage condition can be tested
        UUID assignmentA2 = createRoomAssignment(reservationRoomIdA1, roomIdA102,
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), RoomAssignmentStatus.ASSIGNED);

        // Update reservation room quantity to 1 so releasing A2 leaves A1 active (fulfilling required = 1)
        adminJdbcTemplate.update("UPDATE reservation_rooms SET quantity = 1 WHERE id = ?", reservationRoomIdA1);

        // Now release succeeds
        mockMvc.perform(patch("/api/v1/room-assignments/" + assignmentA2 + "/release")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(assignmentA2.toString()))
                .andExpect(jsonPath("$.status").value("RELEASED"));

        // C. Releasing already released assignment throws 400
        mockMvc.perform(patch("/api/v1/room-assignments/" + assignmentA2 + "/release")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Room assignment is already released")));
    }

    // =========================================================================
    // 9-10. List Queries and Foreign Scope
    // =========================================================================

    @Test
    @DisplayName("9. Foreign reservationRoomId query returns uniform 404")
    void test09_foreignReservationRoomIdListReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/room-assignments?reservationRoomId=" + reservationRoomIdB1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("Reservation room not found with id: " + reservationRoomIdB1)));
    }

    @Test
    @DisplayName("10. Foreign roomId query returns uniform 404")
    void test10_foreignRoomIdListReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/room-assignments?roomId=" + roomIdB101)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("Room not found with id: " + roomIdB101)));
    }

    // =========================================================================
    // 11-13. Role-Based Access Control (ACCOUNTANT and VIEWER)
    // =========================================================================

    @Test
    @DisplayName("11. In-scope ACCOUNTANT read succeeds on all read endpoints (200 OK)")
    void test11_inScopeAccountantReadSucceeds() throws Exception {
        // GET by ID
        mockMvc.perform(get("/api/v1/room-assignments/" + assignmentIdA1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(assignmentIdA1.toString()));

        // GET by reservationRoomId
        mockMvc.perform(get("/api/v1/room-assignments?reservationRoomId=" + reservationRoomIdA1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))));

        // GET by roomId
        mockMvc.perform(get("/api/v1/room-assignments?roomId=" + roomIdA101)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("12. In-scope ACCOUNTANT mutation attempts return 403 Forbidden")
    void test12_inScopeAccountantMutationReturns403() throws Exception {
        RoomAssignmentCreateRequest request = new RoomAssignmentCreateRequest();
        request.setReservationRoomId(reservationRoomIdA1);
        request.setRoomId(roomIdA102);
        request.setAssignedFrom(LocalDate.of(2026, 12, 1));
        request.setAssignedUntil(LocalDate.of(2026, 12, 5));

        // Assignment creation fails with 403
        mockMvc.perform(post("/api/v1/room-assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // Release fails with 403
        mockMvc.perform(patch("/api/v1/room-assignments/" + assignmentIdA1 + "/release")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("13. In-scope VIEWER read returns 403 Forbidden on all assignment reads")
    void test13_inScopeViewerReadReturns403() throws Exception {
        // GET by ID
        mockMvc.perform(get("/api/v1/room-assignments/" + assignmentIdA1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isForbidden());

        // GET by reservationRoomId
        mockMvc.perform(get("/api/v1/room-assignments?reservationRoomId=" + reservationRoomIdA1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isForbidden());

        // GET by roomId
        mockMvc.perform(get("/api/v1/room-assignments?roomId=" + roomIdA101)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 14-16. Administrative and Unassigned Access
    // =========================================================================

    @Test
    @DisplayName("14. PROPERTY_ADMIN read and manage succeeds for assigned property")
    void test14_propertyAdminReadAndManageSucceeds() throws Exception {
        // GET
        mockMvc.perform(get("/api/v1/room-assignments/" + assignmentIdA1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        // POST
        RoomAssignmentCreateRequest request = new RoomAssignmentCreateRequest();
        request.setReservationRoomId(reservationRoomIdA1);
        request.setRoomId(roomIdA102);
        request.setAssignedFrom(LocalDate.of(2026, 12, 1));
        request.setAssignedUntil(LocalDate.of(2026, 12, 5));

        mockMvc.perform(post("/api/v1/room-assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("15. SUPER_ADMIN cross-property legitimate operation succeeds")
    void test15_superAdminCrossPropertyOperationSucceeds() throws Exception {
        // Reads across properties
        mockMvc.perform(get("/api/v1/room-assignments/" + assignmentIdA1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/room-assignments/" + assignmentIdB1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("16. Staff without an assigned property cannot access foreign records (returns 404)")
    void test16_unassignedStaffCannotAccessForeignRecords() throws Exception {
        mockMvc.perform(get("/api/v1/room-assignments/" + assignmentIdA1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unassignedStaffToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/room-assignments?reservationRoomId=" + reservationRoomIdA1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unassignedStaffToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/room-assignments?roomId=" + roomIdA101)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unassignedStaffToken))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // 17-19. Authentication & Dynamic Revocation
    // =========================================================================

    @Test
    @DisplayName("17. Missing Bearer JWT returns 401 Unauthorized")
    void test17_missingJwtReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/room-assignments/" + assignmentIdA1))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("18. Invalid Bearer JWT returns 401 Unauthorized")
    void test18_invalidJwtReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/room-assignments/" + assignmentIdA1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("19. Dynamic role/property revocation is immediately enforced without token reissue")
    void test19_dynamicRoleRevocationEnforced() throws Exception {
        // Initial request succeeds
        mockMvc.perform(get("/api/v1/room-assignments/" + assignmentIdA1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk());

        // Revoke role directly in database
        adminJdbcTemplate.update("DELETE FROM staff_property_roles WHERE staff_user_id = ?", staffIdReceptionistA);

        // Next request immediately fails with 404 (anti-enumeration: property is now out of scope)
        mockMvc.perform(get("/api/v1/room-assignments/" + assignmentIdA1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // 20-23. Query Parameter Ambiguity and List Semantics
    // =========================================================================

    @Test
    @DisplayName("20. Both reservationRoomId and roomId supplied returns 400 Bad Request")
    void test20_bothQueryFiltersSuppliedReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/room-assignments?reservationRoomId=" + reservationRoomIdA1 + "&roomId=" + roomIdA101)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Cannot provide both reservationRoomId and roomId")));
    }

    @Test
    @DisplayName("21. Neither reservationRoomId nor roomId supplied returns 400 Bad Request")
    void test21_neitherQueryFilterSuppliedReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/room-assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Either reservationRoomId or roomId must be provided")));
    }

    @Test
    @DisplayName("22. Query by reservationRoomId includes both ASSIGNED and RELEASED history")
    void test22_reservationRoomListPreservesReleasedHistory() throws Exception {
        // Create second assignment and mark it RELEASED
        UUID releasedAssignmentId = createRoomAssignment(reservationRoomIdA1, roomIdA102,
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), RoomAssignmentStatus.RELEASED);

        mockMvc.perform(get("/api/v1/room-assignments?reservationRoomId=" + reservationRoomIdA1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItems(assignmentIdA1.toString(), releasedAssignmentId.toString())))
                .andExpect(jsonPath("$[*].status", hasItems("ASSIGNED", "RELEASED")));
    }

    @Test
    @DisplayName("23. Query by roomId returns ASSIGNED records only, omitting RELEASED records")
    void test23_roomListPreservesAssignedOnlySemantics() throws Exception {
        // Create released assignment on roomIdA101 for earlier dates
        UUID releasedAssignmentId = createRoomAssignment(reservationRoomIdA1, roomIdA101,
                LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 25), RoomAssignmentStatus.RELEASED);

        mockMvc.perform(get("/api/v1/room-assignments?roomId=" + roomIdA101)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(assignmentIdA1.toString())))
                .andExpect(jsonPath("$[*].id", not(hasItem(releasedAssignmentId.toString()))))
                .andExpect(jsonPath("$[*].status", everyItem(equalTo("ASSIGNED"))));
    }

    // =========================================================================
    // 24-27. Service Boundary, Development Mode & Domain Invariants
    // =========================================================================

    @Test
    @DisplayName("24. Direct service invocation cannot bypass security enforcement")
    void test24_directServiceInvocationCannotBypassSecurityEnforcement() {
        // A. Unassigned staff principal calling service directly throws ResourceNotFoundException
        AuthenticatedStaffPrincipal unassignedPrincipal = new AuthenticatedStaffPrincipal(
                staffIdUnassigned, "unassigned" + TEST_DOMAIN, "Test", "Staff"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(unassignedPrincipal, null, List.of())
        );

        assertThatThrownBy(() -> roomAssignmentService.getRoomAssignmentById(assignmentIdA1))
                .isInstanceOf(ResourceNotFoundException.class);

        // B. Accountant principal calling assignRoom directly throws AccessDeniedException
        AuthenticatedStaffPrincipal accountantPrincipal = new AuthenticatedStaffPrincipal(
                staffIdAccountantA, "accountant-a" + TEST_DOMAIN, "Test", "Staff"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(accountantPrincipal, null, List.of())
        );

        RoomAssignmentCreateRequest request = new RoomAssignmentCreateRequest();
        request.setReservationRoomId(reservationRoomIdA1);
        request.setRoomId(roomIdA102);
        request.setAssignedFrom(LocalDate.of(2026, 12, 1));
        request.setAssignedUntil(LocalDate.of(2026, 12, 5));

        assertThatThrownBy(() -> roomAssignmentService.assignRoom(request))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("25. Development mode compatibility when enforcement is disabled")
    void test25_developmentEnforcementDisabledCompatible() throws Exception {
        enforcementProperties.setEnabled(false);
        try {
            // 1. Direct service call without property role succeeds in dev mode
            AuthenticatedStaffPrincipal unassignedPrincipal = new AuthenticatedStaffPrincipal(
                    staffIdUnassigned, "unassigned" + TEST_DOMAIN, "Test", "Staff"
            );
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(unassignedPrincipal, null, List.of())
            );

            RoomAssignmentResponse serviceRes = roomAssignmentService.getRoomAssignmentById(assignmentIdA1);
            assertThat(serviceRes).isNotNull();
            assertThat(serviceRes.getId()).isEqualTo(assignmentIdA1);

            // 2. MockMvc call with unassigned token succeeds across properties when guard enforcement is disabled
            mockMvc.perform(get("/api/v1/room-assignments/" + assignmentIdB1)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + unassignedStaffToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(assignmentIdB1.toString()));
        } finally {
            enforcementProperties.setEnabled(true);
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("26. Existing overlap and lifecycle rules preserved")
    void test26_existingLifecycleAndOverlapRulesPreserved() throws Exception {
        // Attempting to assign already assigned physical room for overlapping dates throws 400
        RoomAssignmentCreateRequest request = new RoomAssignmentCreateRequest();
        request.setReservationRoomId(reservationRoomIdA1);
        request.setRoomId(roomIdA101); // Already assigned to assignmentIdA1
        request.setAssignedFrom(LocalDate.of(2026, 12, 1));
        request.setAssignedUntil(LocalDate.of(2026, 12, 5));

        mockMvc.perform(post("/api/v1/room-assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("already assigned for the requested dates")));
    }

    @Test
    @DisplayName("27. Error responses do not leak room numbers, property codes, or internal keys")
    void test27_errorResponseDoesNotLeakSensitiveKeys() throws Exception {
        mockMvc.perform(get("/api/v1/room-assignments/" + assignmentIdB1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(not(containsString("El Hogar"))))
                .andExpect(jsonPath("$.message").value(not(containsString("PROP_BETA"))))
                .andExpect(jsonPath("$.message").value(not(containsString("B-101"))));
    }

    @Test
    @DisplayName("28. Inconsistent historical assignment with mismatched physical room property returns uniform 404")
    void test28_malformedHistoricalAssignmentWithMismatchedRoomPropertyReturns404() throws Exception {
        UUID malformedAssignmentId = UUID.randomUUID();
        adminJdbcTemplate.update(
                "INSERT INTO room_assignments (id, reservation_room_id, room_id, assigned_from, assigned_until, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, '2026-12-10', '2026-12-15', 'ASSIGNED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                malformedAssignmentId,
                reservationRoomIdA1, // Property A
                roomIdB101           // Property B
        );

        // GET must return 404
        mockMvc.perform(get("/api/v1/room-assignments/" + malformedAssignmentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("Room assignment not found with id: " + malformedAssignmentId)));

        // PATCH release must return 404
        mockMvc.perform(patch("/api/v1/room-assignments/" + malformedAssignmentId + "/release")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("Room assignment not found with id: " + malformedAssignmentId)));
    }
}
