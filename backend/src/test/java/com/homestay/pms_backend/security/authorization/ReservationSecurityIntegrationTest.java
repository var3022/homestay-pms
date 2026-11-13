package com.homestay.pms_backend.security.authorization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.homestay.pms_backend.config.SecurityEnforcementProperties;
import com.homestay.pms_backend.controller.ReservationController;
import com.homestay.pms_backend.dto.request.ReservationCreateRequest;
import com.homestay.pms_backend.dto.request.ReservationRoomAddRequest;
import com.homestay.pms_backend.dto.request.ReservationRoomCreateRequest;
import com.homestay.pms_backend.dto.request.ReservationUpdateRequest;
import com.homestay.pms_backend.dto.response.ReservationResponse;
import com.homestay.pms_backend.entity.*;
import com.homestay.pms_backend.enums.*;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.repository.*;
import com.homestay.pms_backend.security.AuthenticatedStaff;
import com.homestay.pms_backend.security.jwt.AccessTokenDto;
import com.homestay.pms_backend.security.jwt.AccessTokenService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
@DisplayName("Step 3.4.3.3A: Reservation and Reservation-Room Security Integration Tests")
class ReservationSecurityIntegrationTest {

    private static final String TEST_DOMAIN = "@reservation-auth-test.com";
    private static final String VALID_BCRYPT_HASH = "$2a$12$7kP.j7z2Wk2R8oO2g0U.w.fB6jH1Fk8iG/1qZ3yM6n7Q8b9c0d1e2";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private final ObjectMapper objectMapper = createObjectMapper();

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

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private StaffAuthorizationService authorizationService;

    @Autowired
    private SecurityEnforcementProperties enforcementProperties;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationAuthorizationGuard reservationAuthorizationGuard;

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
    private Environment environment;

    private MockMvc mockMvc;
    private JdbcTemplate adminJdbcTemplate;

    private UUID propertyIdA;
    private UUID propertyIdB;
    private UUID roomTypeIdA;
    private UUID roomTypeIdB;
    private UUID roomIdA;
    private UUID roomIdB;
    private UUID guestId1;
    private UUID guestId2;
    private UUID bookingSourceDirectId;
    private UUID bookingSourceOtaId;

    private UUID reservationIdA;
    private UUID reservationIdB;
    private UUID reservationRoomIdA;
    private UUID reservationRoomIdB;

    private UUID staffIdAdminA;
    private String superAdminToken;
    private String propAdminTokenA;
    private String receptionistTokenA;
    private String viewerTokenA;
    private String accountantTokenA;
    private String elHogarStaffToken;
    private String unassignedStaffToken;

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

        // 1. Seed Properties
        propertyIdA = getOrCreateProperty("La Vivante Res", "PROP_ALPHA_RES");
        propertyIdB = getOrCreateProperty("El Hogar Res", "PROP_BETA_RES");

        // 2. Seed Room Types
        roomTypeIdA = getOrCreateRoomType(propertyIdA, "Suite Res A", "RTA_RES");
        roomTypeIdB = getOrCreateRoomType(propertyIdB, "Suite Res B", "RTB_RES");

        // 3. Seed Rooms
        roomIdA = getOrCreateRoom(propertyIdA, roomTypeIdA, "A-101-RES");
        roomIdB = getOrCreateRoom(propertyIdB, roomTypeIdB, "B-201-RES");

        // 4. Seed Guests
        guestId1 = getOrCreateGuest("Guest Alpha", "guest1-res@test.com", "+919876543211");
        guestId2 = getOrCreateGuest("Guest Beta", "guest2-res@test.com", "+919876543212");

        // 5. Lookup Booking Sources
        bookingSourceDirectId = bookingSourceRepository.findByCode("DIRECT")
                .orElseThrow(() -> new IllegalStateException("DIRECT booking source not found"))
                .getId();
        bookingSourceOtaId = bookingSourceRepository.findByCode("BOOKING_COM")
                .orElseThrow(() -> new IllegalStateException("BOOKING_COM booking source not found"))
                .getId();

        // 6. Seed Reservations and Reservation Rooms
        reservationIdA = createDirectReservation(propertyIdA, guestId1, bookingSourceDirectId, "Res-A-Initial");
        reservationRoomIdA = createReservationRoom(reservationIdA, roomTypeIdA, 1, new BigDecimal("1500.00"));

        reservationIdB = createDirectReservation(propertyIdB, guestId2, bookingSourceDirectId, "Res-B-Initial");
        reservationRoomIdB = createReservationRoom(reservationIdB, roomTypeIdB, 1, new BigDecimal("2000.00"));

        // 7. Create Staff Accounts and Tokens
        superAdminToken = createStaffToken("superadmin" + TEST_DOMAIN, StaffRole.SUPER_ADMIN, null);
        staffIdAdminA = createTestStaff("propadmin-a" + TEST_DOMAIN, true, false);
        assignStaffPropertyRole(staffIdAdminA, propertyIdA, StaffRole.PROPERTY_ADMIN);
        propAdminTokenA = generateTokenForStaff(staffIdAdminA, "propadmin-a" + TEST_DOMAIN);

        receptionistTokenA = createStaffToken("receptionist-a" + TEST_DOMAIN, StaffRole.RECEPTIONIST, propertyIdA);
        viewerTokenA = createStaffToken("viewer-a" + TEST_DOMAIN, StaffRole.VIEWER, propertyIdA);
        accountantTokenA = createStaffToken("accountant-a" + TEST_DOMAIN, StaffRole.ACCOUNTANT, propertyIdA);
        elHogarStaffToken = createStaffToken("elhogar-admin" + TEST_DOMAIN, StaffRole.PROPERTY_ADMIN, propertyIdB);
        unassignedStaffToken = createStaffToken("unassigned" + TEST_DOMAIN, null, null);
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
                    "DELETE FROM room_assignments WHERE reservation_room_id IN (SELECT id FROM reservation_rooms WHERE room_type_id IN (SELECT id FROM room_types WHERE code IN ('RTA_RES', 'RTB_RES'))); " +
                    "DELETE FROM reservation_rooms WHERE room_type_id IN (SELECT id FROM room_types WHERE code IN ('RTA_RES', 'RTB_RES')); " +
                    "DELETE FROM reservations WHERE property_id IN (SELECT id FROM properties WHERE code IN ('PROP_ALPHA_RES', 'PROP_BETA_RES')); " +
                    "DELETE FROM rooms WHERE room_number IN ('A-101-RES', 'B-201-RES'); " +
                    "DELETE FROM room_types WHERE code IN ('RTA_RES', 'RTB_RES'); " +
                    "DELETE FROM guests WHERE email IN ('guest1-res@test.com', 'guest2-res@test.com'); " +
                    "DELETE FROM properties WHERE code IN ('PROP_ALPHA_RES', 'PROP_BETA_RES');"
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
                    prop.setDescription("Integration test property for reservation authorization");
                    prop.setAddressLine1("100 Res Blvd");
                    prop.setCity("Res City");
                    prop.setState("Res State");
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
                    rt.setDescription("Res Room Type");
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
                .map(r -> {
                    r.setStatus(RoomStatus.AVAILABLE);
                    r.setActive(true);
                    return roomRepository.save(r).getId();
                })
                .orElseGet(() -> {
                    Room r = new Room();
                    r.setPropertyId(propertyId);
                    r.setRoomTypeId(roomTypeId);
                    r.setRoomNumber(roomNumber);
                    r.setName("Room " + roomNumber);
                    r.setFloor("1");
                    r.setBaseOccupancy(2);
                    r.setMaxOccupancy(4);
                    r.setMaxExtraBeds(2);
                    r.setBathroomCount(1);
                    r.setStatus(RoomStatus.AVAILABLE);
                    r.setActive(true);
                    return roomRepository.save(r).getId();
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
        return guestRepository.save(g).getId();
    }

    private UUID createDirectReservation(UUID propertyId, UUID guestId, UUID bookingSourceId, String specialRequests) {
        Reservation res = new Reservation();
        res.setPropertyId(propertyId);
        res.setPrimaryGuestId(guestId);
        res.setBookingSourceId(bookingSourceId);
        res.setCheckInDate(LocalDate.now().plusDays(10));
        res.setCheckOutDate(LocalDate.now().plusDays(12));
        res.setAdults(2);
        res.setChildren(0);
        res.setInfants(0);
        res.setStatus(ReservationStatus.CONFIRMED);
        res.setSpecialRequests(specialRequests);
        return reservationRepository.save(res).getId();
    }

    private UUID createReservationRoom(UUID reservationId, UUID roomTypeId, int quantity, BigDecimal unitPrice) {
        ReservationRoom rr = new ReservationRoom();
        rr.setReservationId(reservationId);
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(quantity);
        rr.setCancelledQuantity(0);
        rr.setUnitPrice(unitPrice);
        rr.setSubtotal(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        rr.setStatus(ReservationRoomStatus.CONFIRMED);
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

    // =========================================================================
    // SECTION 1: SUPER_ADMIN Role Verification (Test 1)
    // =========================================================================

    @Test
    @DisplayName("1. SUPER_ADMIN can create, read, update, and cancel authorized reservations")
    void superAdmin_canPerformAllReservationOperations() throws Exception {
        ReservationRoomCreateRequest roomReq = new ReservationRoomCreateRequest();
        roomReq.setRoomTypeId(roomTypeIdA);
        roomReq.setQuantity(1);
        roomReq.setUnitPrice(new BigDecimal("1200.00"));

        ReservationCreateRequest createReq = new ReservationCreateRequest();
        createReq.setPropertyId(propertyIdA);
        createReq.setPrimaryGuestId(guestId1);
        createReq.setBookingSourceId(bookingSourceDirectId);
        createReq.setCheckInDate(LocalDate.now().plusDays(20));
        createReq.setCheckOutDate(LocalDate.now().plusDays(23));
        createReq.setAdults(2);
        createReq.setChildren(0);
        createReq.setInfants(0);
        createReq.setSpecialRequests("Super admin booking");
        createReq.setReservationRooms(List.of(roomReq));

        // Create
        String responseJson = mockMvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.propertyId").value(propertyIdA.toString()))
                .andReturn().getResponse().getContentAsString();

        ReservationResponse created = objectMapper.readValue(responseJson, ReservationResponse.class);
        UUID newResId = created.getId();

        // Read
        mockMvc.perform(get("/api/v1/reservations/" + newResId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(newResId.toString()));

        // Update
        ReservationUpdateRequest updateReq = new ReservationUpdateRequest();
        updateReq.setSpecialRequests("Updated by super admin");
        mockMvc.perform(put("/api/v1/reservations/" + newResId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specialRequests").value("Updated by super admin"));

        // Cancel
        mockMvc.perform(patch("/api/v1/reservations/" + newResId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // =========================================================================
    // SECTION 2: PROPERTY_ADMIN Scoped Authority (Test 2)
    // =========================================================================

    @Test
    @DisplayName("2. PROPERTY_ADMIN manages only assigned-property reservations")
    void propertyAdmin_managesOnlyAssignedProperty() throws Exception {
        ReservationRoomCreateRequest roomReq = new ReservationRoomCreateRequest();
        roomReq.setRoomTypeId(roomTypeIdA);
        roomReq.setQuantity(1);
        roomReq.setUnitPrice(new BigDecimal("1300.00"));

        ReservationCreateRequest createReqA = new ReservationCreateRequest();
        createReqA.setPropertyId(propertyIdA);
        createReqA.setPrimaryGuestId(guestId1);
        createReqA.setBookingSourceId(bookingSourceDirectId);
        createReqA.setCheckInDate(LocalDate.now().plusDays(30));
        createReqA.setCheckOutDate(LocalDate.now().plusDays(32));
        createReqA.setAdults(1);
        createReqA.setSpecialRequests("PropAdmin A booking");
        createReqA.setReservationRooms(List.of(roomReq));

        // Create on Property A succeeds
        String responseJson = mockMvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReqA)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        ReservationResponse created = objectMapper.readValue(responseJson, ReservationResponse.class);

        // Read on Property A succeeds
        mockMvc.perform(get("/api/v1/reservations/" + created.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        // Update on Property A succeeds
        ReservationUpdateRequest updateReq = new ReservationUpdateRequest();
        updateReq.setSpecialRequests("PropAdmin updated notes");
        mockMvc.perform(put("/api/v1/reservations/" + created.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk());

        // Cancel on Property A succeeds
        mockMvc.perform(patch("/api/v1/reservations/" + created.getId() + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Create on Property B fails with 404 (out of scope)
        ReservationCreateRequest createReqB = new ReservationCreateRequest();
        createReqB.setPropertyId(propertyIdB);
        createReqB.setPrimaryGuestId(guestId2);
        createReqB.setBookingSourceId(bookingSourceDirectId);
        createReqB.setCheckInDate(LocalDate.now().plusDays(30));
        createReqB.setCheckOutDate(LocalDate.now().plusDays(32));
        createReqB.setAdults(1);
        createReqB.setReservationRooms(List.of(roomReq));

        mockMvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReqB)))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // SECTION 3: RECEPTIONIST Operational Management (Test 3)
    // =========================================================================

    @Test
    @DisplayName("3. RECEPTIONIST can manage assigned-property reservations")
    void receptionist_canManageAssignedPropertyReservations() throws Exception {
        ReservationRoomCreateRequest roomReq = new ReservationRoomCreateRequest();
        roomReq.setRoomTypeId(roomTypeIdA);
        roomReq.setQuantity(1);
        roomReq.setUnitPrice(new BigDecimal("1100.00"));

        ReservationCreateRequest createReq = new ReservationCreateRequest();
        createReq.setPropertyId(propertyIdA);
        createReq.setPrimaryGuestId(guestId1);
        createReq.setBookingSourceId(bookingSourceDirectId);
        createReq.setCheckInDate(LocalDate.now().plusDays(40));
        createReq.setCheckOutDate(LocalDate.now().plusDays(42));
        createReq.setAdults(2);
        createReq.setSpecialRequests("Receptionist booking");
        createReq.setReservationRooms(List.of(roomReq));

        // Create succeeds
        String resJson = mockMvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        ReservationResponse created = objectMapper.readValue(resJson, ReservationResponse.class);

        // Read succeeds
        mockMvc.perform(get("/api/v1/reservations/" + created.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk());

        // Update succeeds
        ReservationUpdateRequest updateReq = new ReservationUpdateRequest();
        updateReq.setSpecialRequests("Receptionist updated");
        mockMvc.perform(put("/api/v1/reservations/" + created.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk());

        // Cancel succeeds
        mockMvc.perform(patch("/api/v1/reservations/" + created.getId() + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // =========================================================================
    // SECTION 4: ACCOUNTANT Role Segregation (Test 4)
    // =========================================================================

    @Test
    @DisplayName("4. ACCOUNTANT cannot create, update, or cancel reservations (denied with 403)")
    void accountant_cannotMutateReservations() throws Exception {
        // Read is allowed
        mockMvc.perform(get("/api/v1/reservations/" + reservationIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA))
                .andExpect(status().isOk());

        // Create is forbidden
        ReservationRoomCreateRequest roomReq = new ReservationRoomCreateRequest();
        roomReq.setRoomTypeId(roomTypeIdA);
        roomReq.setQuantity(1);
        roomReq.setUnitPrice(new BigDecimal("1000.00"));

        ReservationCreateRequest createReq = new ReservationCreateRequest();
        createReq.setPropertyId(propertyIdA);
        createReq.setPrimaryGuestId(guestId1);
        createReq.setBookingSourceId(bookingSourceDirectId);
        createReq.setCheckInDate(LocalDate.now().plusDays(50));
        createReq.setCheckOutDate(LocalDate.now().plusDays(52));
        createReq.setAdults(1);
        createReq.setReservationRooms(List.of(roomReq));

        mockMvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isForbidden());

        // Update is forbidden
        ReservationUpdateRequest updateReq = new ReservationUpdateRequest();
        updateReq.setSpecialRequests("Accountant note");
        mockMvc.perform(put("/api/v1/reservations/" + reservationIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isForbidden());

        // Cancel is forbidden
        mockMvc.perform(patch("/api/v1/reservations/" + reservationIdA + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // SECTION 5: VIEWER Read-Only Segregation (Test 5)
    // =========================================================================

    @Test
    @DisplayName("5. VIEWER cannot read or mutate reservations (denied with 403 for in-scope reads and mutations)")
    void viewer_cannotReadOrMutateReservations() throws Exception {
        // Full reservation read is strictly forbidden (403) to protect financial details, nightly rates, and special requests
        mockMvc.perform(get("/api/v1/reservations/" + reservationIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isForbidden());

        // In-scope property filter read is strictly forbidden (403)
        mockMvc.perform(get("/api/v1/reservations?propertyId=" + propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isForbidden());

        // Reservation-room by ID read is strictly forbidden (403)
        mockMvc.perform(get("/api/v1/reservation-rooms/" + reservationRoomIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isForbidden());

        // Reservation-room list by reservationId read is strictly forbidden (403)
        mockMvc.perform(get("/api/v1/reservation-rooms?reservationId=" + reservationIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isForbidden());

        // Reservation list without parameters returns empty list [] (no reservations disclosed)
        mockMvc.perform(get("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // Create is forbidden
        ReservationRoomCreateRequest roomReq = new ReservationRoomCreateRequest();
        roomReq.setRoomTypeId(roomTypeIdA);
        roomReq.setQuantity(1);
        roomReq.setUnitPrice(new BigDecimal("1000.00"));

        ReservationCreateRequest createReq = new ReservationCreateRequest();
        createReq.setPropertyId(propertyIdA);
        createReq.setPrimaryGuestId(guestId1);
        createReq.setBookingSourceId(bookingSourceDirectId);
        createReq.setCheckInDate(LocalDate.now().plusDays(60));
        createReq.setCheckOutDate(LocalDate.now().plusDays(62));
        createReq.setAdults(1);
        createReq.setReservationRooms(List.of(roomReq));

        mockMvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isForbidden());

        // Update is forbidden
        ReservationUpdateRequest updateReq = new ReservationUpdateRequest();
        updateReq.setSpecialRequests("Viewer note");
        mockMvc.perform(put("/api/v1/reservations/" + reservationIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isForbidden());

        // Cancel is forbidden
        mockMvc.perform(patch("/api/v1/reservations/" + reservationIdA + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // SECTION 6: Cross-Property Non-Enumeration (Test 6)
    // =========================================================================

    @Test
    @DisplayName("6. Cross-property reservation ID access denied without existence disclosure (404)")
    void crossPropertyReservationId_returnsIndistinguishable404() throws Exception {
        UUID nonexistentId = UUID.randomUUID();

        // Nonexistent reservation returns 404
        mockMvc.perform(get("/api/v1/reservations/" + nonexistentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Reservation not found with id: " + nonexistentId));

        // Existing reservation on Property B returns identical 404
        mockMvc.perform(get("/api/v1/reservations/" + reservationIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Reservation not found with id: " + reservationIdB));
    }

    // =========================================================================
    // SECTION 7: List Filter by Property ID (Tests 7, 8, 9)
    // =========================================================================

    @Test
    @DisplayName("7. GET /reservations?propertyId=A returns only A reservations")
    void getReservations_withAuthorizedPropertyId_returnsOnlyScopedReservations() throws Exception {
        mockMvc.perform(get("/api/v1/reservations?propertyId=" + propertyIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[*].propertyId", everyItem(is(propertyIdA.toString()))));
    }

    @Test
    @DisplayName("8. GET /reservations?propertyId=B denied for Property A staff (404)")
    void getReservations_withUnauthorizedPropertyId_deniedWith404() throws Exception {
        mockMvc.perform(get("/api/v1/reservations?propertyId=" + propertyIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Property not found with id: " + propertyIdB));
    }

    @Test
    @DisplayName("9. GET /reservations without propertyId never leaks across properties when enforcement is enabled")
    void getReservations_withoutPropertyId_scopesToAssignedProperties() throws Exception {
        // Property Admin A receives only Property A bookings
        mockMvc.perform(get("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].propertyId", everyItem(is(propertyIdA.toString()))))
                .andExpect(jsonPath("$[*].propertyId", not(hasItem(propertyIdB.toString()))));

        // El Hogar Staff receives only Property B bookings
        mockMvc.perform(get("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + elHogarStaffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].propertyId", everyItem(is(propertyIdB.toString()))))
                .andExpect(jsonPath("$[*].propertyId", not(hasItem(propertyIdA.toString()))));
    }

    // =========================================================================
    // SECTION 8: Development Compatibility (Test 10)
    // =========================================================================

    @Test
    @DisplayName("10. Development compatibility preserves legacy no-parameter list")
    void developmentCompatibility_preservesLegacyList() {
        SecurityEnforcementProperties disabledProps = new SecurityEnforcementProperties();
        disabledProps.setEnabled(false);

        ReservationAuthorizationGuard devGuard = new ReservationAuthorizationGuard(
                authorizationService,
                disabledProps,
                propertyRepository,
                reservationRepository,
                reservationRoomRepository,
                roomTypeRepository
        );

        ReservationController devController = new ReservationController(reservationService, devGuard);
        ResponseEntity<List<ReservationResponse>> response = devController.getReservations(null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        // Cross-property results are returned in un-enforced dev mode
        List<UUID> returnedPropertyIds = response.getBody().stream().map(ReservationResponse::getPropertyId).toList();
        assertThat(returnedPropertyIds).contains(propertyIdA, propertyIdB);
    }

    // =========================================================================
    // SECTION 9: Unauthorized Writes Fail Without Database Mutation (Tests 11, 12)
    // =========================================================================

    @Test
    @DisplayName("11. Reservation creation for unauthorized property fails without writes")
    void reservationCreate_unauthorizedProperty_failsWithoutWrites() throws Exception {
        long countBefore = reservationRepository.count();

        ReservationRoomCreateRequest roomReq = new ReservationRoomCreateRequest();
        roomReq.setRoomTypeId(roomTypeIdB);
        roomReq.setQuantity(1);
        roomReq.setUnitPrice(new BigDecimal("1000.00"));

        ReservationCreateRequest createReq = new ReservationCreateRequest();
        createReq.setPropertyId(propertyIdB);
        createReq.setPrimaryGuestId(guestId2);
        createReq.setBookingSourceId(bookingSourceDirectId);
        createReq.setCheckInDate(LocalDate.now().plusDays(70));
        createReq.setCheckOutDate(LocalDate.now().plusDays(72));
        createReq.setAdults(1);
        createReq.setReservationRooms(List.of(roomReq));

        mockMvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isNotFound());

        long countAfter = reservationRepository.count();
        assertThat(countAfter).isEqualTo(countBefore);
    }

    @Test
    @DisplayName("12. Reservation update and cancellation on unauthorized property fail without writes")
    void reservationUpdateAndCancel_unauthorizedProperty_failsWithoutWrites() throws Exception {
        Reservation resB = reservationRepository.findById(reservationIdB).orElseThrow();
        String originalRequests = resB.getSpecialRequests();
        ReservationStatus originalStatus = resB.getStatus();

        // Update
        ReservationUpdateRequest updateReq = new ReservationUpdateRequest();
        updateReq.setSpecialRequests("Attempted cross-property update");
        mockMvc.perform(put("/api/v1/reservations/" + reservationIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isNotFound());

        // Cancel
        mockMvc.perform(patch("/api/v1/reservations/" + reservationIdB + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound());

        Reservation resBAfter = reservationRepository.findById(reservationIdB).orElseThrow();
        assertThat(resBAfter.getSpecialRequests()).isEqualTo(originalRequests);
        assertThat(resBAfter.getStatus()).isEqualTo(originalStatus);
    }

    // =========================================================================
    // SECTION 10: Reservation-Room Authorization (Tests 13, 14, 15, 16)
    // =========================================================================

    @Test
    @DisplayName("13. Reservation-room create requires access to parent reservation")
    void reservationRoomCreate_requiresParentReservationAccess() throws Exception {
        ReservationRoomAddRequest addReq = new ReservationRoomAddRequest();
        addReq.setReservationId(reservationIdB); // Out of scope for Staff A
        addReq.setRoomTypeId(roomTypeIdB);
        addReq.setQuantity(1);
        addReq.setUnitPrice(new BigDecimal("1800.00"));

        // Out-of-scope parent reservation returns 404
        mockMvc.perform(post("/api/v1/reservation-rooms")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addReq)))
                .andExpect(status().isNotFound());

        // In-scope parent reservation with unauthorized roles returns 403
        addReq.setReservationId(reservationIdA);
        addReq.setRoomTypeId(roomTypeIdA);

        mockMvc.perform(post("/api/v1/reservation-rooms")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addReq)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/reservation-rooms")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addReq)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("14. Reservation-room GET by ID is property scoped")
    void reservationRoomGetById_isPropertyScoped() throws Exception {
        // Staff A can access reservation-room A
        mockMvc.perform(get("/api/v1/reservation-rooms/" + reservationRoomIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reservationRoomIdA.toString()));

        // Staff A cannot access reservation-room B (indistinguishable 404)
        mockMvc.perform(get("/api/v1/reservation-rooms/" + reservationRoomIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Reservation room not found with id: " + reservationRoomIdB));
    }

    @Test
    @DisplayName("15. Reservation-room list by reservationId is property scoped")
    void reservationRoomListByReservationId_isPropertyScoped() throws Exception {
        // Staff A accesses reservation rooms for reservation A
        mockMvc.perform(get("/api/v1/reservation-rooms?reservationId=" + reservationIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].reservationId").value(reservationIdA.toString()));

        // Staff A accesses reservation rooms for reservation B -> 404
        mockMvc.perform(get("/api/v1/reservation-rooms?reservationId=" + reservationIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Reservation not found with id: " + reservationIdB));
    }

    @Test
    @DisplayName("16. Cross-property roomTypeId on reservation-room creation is rejected (400)")
    void reservationRoomCreate_crossPropertyRoomType_rejected() throws Exception {
        ReservationRoomAddRequest addReq = new ReservationRoomAddRequest();
        addReq.setReservationId(reservationIdA);
        addReq.setRoomTypeId(roomTypeIdB); // Room type from Property B!
        addReq.setQuantity(1);
        addReq.setUnitPrice(new BigDecimal("1500.00"));

        mockMvc.perform(post("/api/v1/reservation-rooms")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The selected room type does not belong to the reservation property"));
    }

    // =========================================================================
    // SECTION 11: Business Rules and Lifecycle Integrity (Tests 17, 18, 19, 20)
    // =========================================================================

    @Test
    @DisplayName("17. OTA update and cancellation restrictions remain enforced")
    void otaRestrictions_remainEnforced() throws Exception {
        UUID otaReservationId = createDirectReservation(propertyIdA, guestId1, bookingSourceOtaId, "OTA booking");

        // Attempting to update OTA reservation returns 400
        ReservationUpdateRequest updateReq = new ReservationUpdateRequest();
        updateReq.setSpecialRequests("Try to update OTA");
        mockMvc.perform(put("/api/v1/reservations/" + otaReservationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("OTA reservations cannot be modified")));

        // Attempting to cancel OTA reservation returns 400
        mockMvc.perform(patch("/api/v1/reservations/" + otaReservationId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("OTA bookings cannot be cancelled locally")));
    }

    @Test
    @DisplayName("18. Terminal-status modification guards remain enforced")
    void terminalStatusGuards_remainEnforced() throws Exception {
        // Cancel reservation
        mockMvc.perform(patch("/api/v1/reservations/" + reservationIdA + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        // Further modifications are rejected
        ReservationUpdateRequest updateReq = new ReservationUpdateRequest();
        updateReq.setSpecialRequests("Try update after cancel");
        mockMvc.perform(put("/api/v1/reservations/" + reservationIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cancelled reservations cannot be modified"));

        // Second cancel attempt rejected
        mockMvc.perform(patch("/api/v1/reservations/" + reservationIdA + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Reservation is already cancelled"));

        // Adding room to cancelled reservation rejected
        ReservationRoomAddRequest addReq = new ReservationRoomAddRequest();
        addReq.setReservationId(reservationIdA);
        addReq.setRoomTypeId(roomTypeIdA);
        addReq.setQuantity(1);
        addReq.setUnitPrice(new BigDecimal("1000.00"));
        mockMvc.perform(post("/api/v1/reservation-rooms")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cannot add rooms to a cancelled reservation"));
    }

    @Test
    @DisplayName("19. Cancelled inventory remains available as previously tested")
    void cancelledInventory_isReleased() throws Exception {
        // Cancel reservation
        mockMvc.perform(patch("/api/v1/reservations/" + reservationIdA + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        ReservationRoom rr = reservationRoomRepository.findById(reservationRoomIdA).orElseThrow();
        assertThat(rr.getStatus()).isEqualTo(ReservationRoomStatus.CANCELLED);
        assertThat(rr.getCancelledQuantity()).isEqualTo(rr.getQuantity());
    }

    @Test
    @DisplayName("20. Date-change assignment safety remains enforced")
    void dateChangeAssignmentSafety_remainsEnforced() throws Exception {
        // Create an active room assignment covering the original reservation dates
        RoomAssignment assignment = new RoomAssignment();
        assignment.setReservationRoomId(reservationRoomIdA);
        assignment.setRoomId(roomIdA);
        assignment.setAssignedFrom(LocalDate.now().plusDays(10));
        assignment.setAssignedUntil(LocalDate.now().plusDays(12));
        assignment.setStatus(RoomAssignmentStatus.ASSIGNED);
        roomAssignmentRepository.save(assignment);

        // Update dates such that the room assignment does NOT cover the new range
        ReservationUpdateRequest updateReq = new ReservationUpdateRequest();
        updateReq.setCheckInDate(LocalDate.now().plusDays(8));
        updateReq.setCheckOutDate(LocalDate.now().plusDays(14));

        mockMvc.perform(put("/api/v1/reservations/" + reservationIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Cannot change reservation dates because active room assignment")));
    }

    // =========================================================================
    // SECTION 12: Dynamic Security State (Tests 21, 22, 23, 24)
    // =========================================================================

    @Test
    @DisplayName("21. Role revocation takes effect on subsequent requests")
    void roleRevocation_takesEffectImmediately() throws Exception {
        // Initially authorized
        mockMvc.perform(get("/api/v1/reservations/" + reservationIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        // Revoke role in database
        adminJdbcTemplate.update("DELETE FROM staff_property_roles WHERE staff_user_id = ?", staffIdAdminA);

        // Subsequent request returns 404 (out of scope now)
        mockMvc.perform(get("/api/v1/reservations/" + reservationIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("22. Staff deactivation prevents authorization")
    void staffDeactivation_preventsAuthorization() throws Exception {
        // Initially authorized
        mockMvc.perform(get("/api/v1/reservations/" + reservationIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isOk());

        // Deactivate staff in database
        adminJdbcTemplate.update("UPDATE staff_users SET is_active = false WHERE id = ?", staffIdAdminA);

        // Subsequent request returns 401 Unauthorized (via JwtAuthenticationFilter state validation)
        mockMvc.perform(get("/api/v1/reservations/" + reservationIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("23. Missing JWT returns 401 under enforcement")
    void missingJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/reservations"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(get("/api/v1/reservations/" + reservationIdA))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(get("/api/v1/reservation-rooms/" + reservationRoomIdA))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("24. Unassigned staff cannot read cross-property bookings")
    void unassignedStaff_cannotReadCrossPropertyBookings() throws Exception {
        // Listing reservations returns empty list
        mockMvc.perform(get("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unassignedStaffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // Get by ID returns 404
        mockMvc.perform(get("/api/v1/reservations/" + reservationIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unassignedStaffToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/reservations/" + reservationIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unassignedStaffToken))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // SECTION 13: Sensitive Response Policy (Test 25)
    // =========================================================================

    @Test
    @DisplayName("25. Sensitive reservation response fields obey the chosen least-privilege policy")
    void sensitiveResponseFields_obeyLeastPrivilegePolicy() throws Exception {
        // ReservationResponse returns primaryGuestId as UUID only (no guest name, phone, or identity document) for authorized staff (e.g. RECEPTIONIST)
        mockMvc.perform(get("/api/v1/reservations/" + reservationIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryGuestId").value(guestId1.toString()))
                .andExpect(jsonPath("$.guestName").doesNotExist())
                .andExpect(jsonPath("$.guestPhone").doesNotExist())
                .andExpect(jsonPath("$.guestEmail").doesNotExist())
                .andExpect(jsonPath("$.identificationNumber").doesNotExist());

        // ReservationRoomResponse returns pricing to authorized staff (e.g. ACCOUNTANT)
        mockMvc.perform(get("/api/v1/reservation-rooms/" + reservationRoomIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitPrice").value(1500.00))
                .andExpect(jsonPath("$.subtotal").value(1500.00));

        // VIEWER is strictly denied access to full reservation responses (403 Forbidden)
        mockMvc.perform(get("/api/v1/reservations/" + reservationIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isForbidden());

        // VIEWER is strictly denied access to full reservation-room responses (403 Forbidden)
        mockMvc.perform(get("/api/v1/reservation-rooms/" + reservationRoomIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA))
                .andExpect(status().isForbidden());
    }
}
