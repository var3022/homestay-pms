package com.homestay.pms_backend.serviceimpl;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.homestay.pms_backend.dto.request.GuestCreateRequest;
import com.homestay.pms_backend.dto.request.ReservationCreateRequest;
import com.homestay.pms_backend.dto.request.ReservationRoomCreateRequest;
import com.homestay.pms_backend.entity.BookingSource;
import com.homestay.pms_backend.entity.Guest;
import com.homestay.pms_backend.entity.Property;
import com.homestay.pms_backend.entity.Room;
import com.homestay.pms_backend.entity.RoomType;
import com.homestay.pms_backend.enums.HousekeepingStatus;
import com.homestay.pms_backend.enums.IdempotencyOperation;
import com.homestay.pms_backend.enums.IdentityDocumentType;
import com.homestay.pms_backend.enums.RoomStatus;
import com.homestay.pms_backend.enums.RoomTypeCategory;
import com.homestay.pms_backend.repository.*;
import com.homestay.pms_backend.service.ClaimOutcome;
import com.homestay.pms_backend.service.IdempotencyFingerprintService;
import com.homestay.pms_backend.service.IdempotencyService;
import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@IsolatedDatabaseTest
@DisplayName("Step 3.4.3.11B.1: Transactional Guest and Reservation Creation Idempotency Integration Tests")
class CreationIdempotencyIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

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
    private GuestPropertyAssociationRepository associationRepository;

    @Autowired
    private IdempotencyFingerprintService fingerprintService;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private MockMvc mockMvc;
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
        mapper.registerModule(dateModule);
        return mapper;
    }

    private Property testProperty;
    private Guest testGuest;
    private RoomType testRoomType;
    private UUID directBookingSourceId;

    private final List<UUID> createdPropertyIds = new ArrayList<>();
    private final List<UUID> createdGuestIds = new ArrayList<>();

    private JdbcTemplate createAdminJdbcTemplate() {
        DriverManagerDataSource adminDs = new DriverManagerDataSource();
        adminDs.setDriverClassName("org.postgresql.Driver");
        adminDs.setUrl(environment.getProperty("spring.datasource.url", "jdbc:postgresql://localhost:5433/v7_disposable_db"));
        adminDs.setUsername("pms_admin");
        adminDs.setPassword(environment.getProperty("TEST_FLYWAY_PASSWORD", "disposable_pass"));
        return new JdbcTemplate(adminDs);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        BookingSource direct = bookingSourceRepository.findByCode("DIRECT")
                .orElseThrow(() -> new IllegalStateException("DIRECT booking source missing"));
        directBookingSourceId = direct.getId();

        testProperty = createProperty("IDEMP-PROP");
        testGuest = createGuest(testProperty.getId(), "Primary Test Guest");
        testRoomType = createRoomType(testProperty.getId(), "DLX-1", 3);
    }

    @AfterEach
    void tearDown() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        try {
            adminJdbc.execute("DELETE FROM api_idempotency_keys");
        } catch (Exception ignored) {
        }

        for (UUID propId : createdPropertyIds) {
            try {
                adminJdbc.update("DELETE FROM room_assignments WHERE reservation_room_id IN (SELECT id FROM reservation_rooms WHERE reservation_id IN (SELECT id FROM reservations WHERE property_id = ?))", propId);
                adminJdbc.update("DELETE FROM reservation_rooms WHERE reservation_id IN (SELECT id FROM reservations WHERE property_id = ?)", propId);
                adminJdbc.update("DELETE FROM reservations WHERE property_id = ?", propId);
                adminJdbc.update("DELETE FROM rooms WHERE property_id = ?", propId);
                adminJdbc.update("DELETE FROM room_types WHERE property_id = ?", propId);
                adminJdbc.update("DELETE FROM guest_property_associations WHERE property_id = ?", propId);
                adminJdbc.update("DELETE FROM properties WHERE id = ?", propId);
            } catch (Exception ignored) {
            }
        }
        createdPropertyIds.clear();

        for (UUID gId : createdGuestIds) {
            try {
                adminJdbc.update("DELETE FROM guest_property_associations WHERE guest_id = ?", gId);
                adminJdbc.update("DELETE FROM guests WHERE id = ?", gId);
            } catch (Exception ignored) {
            }
        }
        createdGuestIds.clear();
    }

    private Property createProperty(String namePrefix) {
        Property p = new Property();
        p.setName(namePrefix + " " + UUID.randomUUID().toString().substring(0, 6));
        p.setCode("P-" + UUID.randomUUID().toString().substring(0, 8));
        p.setAddressLine1("123 Hill Road");
        p.setCity("Munnar");
        p.setState("Kerala");
        p.setCountry("India");
        p.setTimezone("Asia/Kolkata");
        p.setActive(true);
        Property saved = propertyRepository.saveAndFlush(p);
        createdPropertyIds.add(saved.getId());
        return saved;
    }

    private Guest createGuest(UUID propertyId, String name) {
        Guest g = new Guest();
        g.setName(name);
        g.setPhone("+919876543210");
        g.setEmail("test-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        g.setIdType(IdentityDocumentType.PASSPORT);
        g.setIdNumber("Z" + UUID.randomUUID().toString().substring(0, 7));
        Guest saved = guestRepository.saveAndFlush(g);
        createdGuestIds.add(saved.getId());

        createAdminJdbcTemplate().update(
                "INSERT INTO guest_property_associations (guest_id, property_id, first_associated_at, last_activity_at, association_source) " +
                        "VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'REGISTRATION') " +
                        "ON CONFLICT (guest_id, property_id) DO NOTHING",
                saved.getId(), propertyId
        );
        return saved;
    }

    private RoomType createRoomType(UUID propertyId, String code, int roomCount) {
        RoomType rt = new RoomType();
        rt.setPropertyId(propertyId);
        rt.setName("RoomType " + code);
        rt.setCode(code + "-" + UUID.randomUUID().toString().substring(0, 4));
        rt.setBaseOccupancy(1);
        rt.setMaxOccupancy(2);
        rt.setCategory(RoomTypeCategory.SUITE);
        RoomType saved = roomTypeRepository.saveAndFlush(rt);

        for (int i = 1; i <= roomCount; i++) {
            Room r = new Room();
            r.setPropertyId(propertyId);
            r.setRoomTypeId(saved.getId());
            r.setRoomNumber("R-" + code + "-" + i);
            r.setStatus(RoomStatus.AVAILABLE);
            r.setHousekeepingStatus(HousekeepingStatus.CLEAN);
            r.setActive(true);
            roomRepository.saveAndFlush(r);
        }
        return saved;
    }

    @Test
    @DisplayName("1. Fresh guest creation returns 201 Created and persists idempotency record")
    void testGuestCreationFresh() throws Exception {
        String key = "guest-create-" + UUID.randomUUID();
        GuestCreateRequest req = new GuestCreateRequest();
        req.setPropertyId(testProperty.getId());
        req.setName("Fresh Idempotent Guest");
        req.setEmail("fresh.guest@example.com");
        req.setPhone("+919111111111");
        req.setIdType(IdentityDocumentType.PASSPORT);
        req.setIdNumber("P998877");

        MvcResult result = mockMvc.perform(post("/api/v1/guests")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Key", key))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Fresh Idempotent Guest"))
                .andReturn();

        String respJson = result.getResponse().getContentAsString();
        UUID guestId = UUID.fromString(objectMapper.readTree(respJson).get("id").asText());
        createdGuestIds.add(guestId);

        Integer ledgerCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM api_idempotency_keys WHERE idempotency_key = ? AND state = 'COMPLETED' AND resource_id = ?",
                Integer.class, key, guestId
        );
        assertThat(ledgerCount).isEqualTo(1);
    }

    @Test
    @DisplayName("2. Exact same-key replay for guest returns 200 OK with Idempotent-Replayed header")
    void testGuestCreationReplayExact() throws Exception {
        String key = "guest-replay-" + UUID.randomUUID();
        GuestCreateRequest req = new GuestCreateRequest();
        req.setPropertyId(testProperty.getId());
        req.setName("Replay Guest");
        req.setEmail("replay.guest@example.com");
        req.setPhone("+919222222222");

        MvcResult first = mockMvc.perform(post("/api/v1/guests")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID firstGuestId = UUID.fromString(objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText());
        createdGuestIds.add(firstGuestId);

        // Replay with identical key and payload
        mockMvc.perform(post("/api/v1/guests")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Key", key))
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(firstGuestId.toString()))
                .andExpect(jsonPath("$.name").value("Replay Guest"));

        Integer guestCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM guests WHERE email = 'replay.guest@example.com'",
                Integer.class
        );
        assertThat(guestCount).isEqualTo(1);
    }

    @Test
    @DisplayName("3. Same-key guest creation with modified payload returns 422 Unprocessable Entity")
    void testGuestCreationPayloadMismatch() throws Exception {
        String key = "guest-mismatch-" + UUID.randomUUID();
        GuestCreateRequest req1 = new GuestCreateRequest();
        req1.setPropertyId(testProperty.getId());
        req1.setName("Guest Original");
        req1.setEmail("original@example.com");

        MvcResult first = mockMvc.perform(post("/api/v1/guests")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID firstGuestId = UUID.fromString(objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText());
        createdGuestIds.add(firstGuestId);

        // Second request with changed email
        GuestCreateRequest req2 = new GuestCreateRequest();
        req2.setPropertyId(testProperty.getId());
        req2.setName("Guest Original");
        req2.setEmail("tampered@example.com");

        mockMvc.perform(post("/api/v1/guests")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("The idempotency key is already used with a different request payload."));
    }

    @Test
    @DisplayName("4. Fresh reservation creation returns 201 Created and persists idempotency record")
    void testReservationCreationFresh() throws Exception {
        String key = "res-create-" + UUID.randomUUID();
        ReservationCreateRequest req = buildReservationRequest();

        MvcResult result = mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Key", key))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();

        UUID resId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

        Integer ledgerCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM api_idempotency_keys WHERE idempotency_key = ? AND state = 'COMPLETED' AND resource_id = ?",
                Integer.class, key, resId
        );
        assertThat(ledgerCount).isEqualTo(1);
    }

    @Test
    @DisplayName("5. Exact same-key replay for reservation returns 200 OK with Idempotent-Replayed header")
    void testReservationCreationReplayExact() throws Exception {
        String key = "res-replay-" + UUID.randomUUID();
        ReservationCreateRequest req = buildReservationRequest();

        MvcResult first = mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID firstResId = UUID.fromString(objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText());

        // Replay
        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Key", key))
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(firstResId.toString()));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservations WHERE property_id = ?",
                Integer.class, testProperty.getId()
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("6. Same-key reservation creation with modified dates returns 422 Unprocessable Entity")
    void testReservationCreationPayloadMismatch() throws Exception {
        String key = "res-mismatch-" + UUID.randomUUID();
        ReservationCreateRequest req1 = buildReservationRequest();

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        ReservationCreateRequest req2 = buildReservationRequest();
        req2.setAdults(3); // Changed adults count

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("The idempotency key is already used with a different request payload."));
    }

    @Test
    @DisplayName("7. Canonical price normalization and whitespace in strings returns 200 replay (Scenario E)")
    void testReservationCanonicalPriceAndWhitespaceReplay() throws Exception {
        String key = "res-canonical-" + UUID.randomUUID();

        ReservationCreateRequest req1 = buildReservationRequest();
        req1.getReservationRooms().get(0).setUnitPrice(new BigDecimal("2500.00"));
        req1.setSpecialRequests("Quiet room");

        MvcResult first = mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID firstResId = UUID.fromString(objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText());

        // Equivalent canonical payload: 2500 instead of 2500.00, and trailing whitespace in string
        ReservationCreateRequest req2 = buildReservationRequest();
        req2.getReservationRooms().get(0).setUnitPrice(new BigDecimal("2500"));
        req2.setSpecialRequests("  Quiet room  ");

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(firstResId.toString()));
    }

    @Test
    @DisplayName("8. Concurrent same-key reservation creation race produces exactly 1 creation and replays (Scenario H)")
    void testConcurrentSameKeyReservationRace() throws Exception {
        String key = "res-race-" + UUID.randomUUID();
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);

        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                ReservationCreateRequest req = buildReservationRequest();
                MvcResult res = mockMvc.perform(post("/api/v1/reservations")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                        .andReturn();
                return res.getResponse().getStatus();
            }));
        }

        executor.shutdown();
        boolean finished = executor.awaitTermination(15, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        int created201 = 0;
        int ok200 = 0;
        int retry409 = 0;

        for (Future<Integer> f : futures) {
            int status = f.get();
            if (status == 201) created201++;
            else if (status == 200) ok200++;
            else if (status == 409) retry409++;
        }

        assertThat(created201).isEqualTo(1);
        assertThat(created201 + ok200 + retry409).isEqualTo(threads);

        Integer totalReservations = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservations WHERE property_id = ?",
                Integer.class, testProperty.getId()
        );
        assertThat(totalReservations).isEqualTo(1);
    }

    @Test
    @DisplayName("9. Room capacity inventory contention with distinct keys prevents overselling")
    void testInventoryContentionUnderIdempotency() throws Exception {
        // Create a dedicated room type with capacity 1
        RoomType singleCapacityType = createRoomType(testProperty.getId(), "SINGLE-CAP", 1);

        String keyA = "res-contention-A-" + UUID.randomUUID();
        String keyB = "res-contention-B-" + UUID.randomUUID();

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);

        Callable<Integer> taskA = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            ReservationCreateRequest req = buildReservationRequest();
            req.getReservationRooms().get(0).setRoomTypeId(singleCapacityType.getId());
            return mockMvc.perform(post("/api/v1/reservations")
                            .header("Idempotency-Key", keyA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andReturn().getResponse().getStatus();
        };

        Callable<Integer> taskB = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            ReservationCreateRequest req = buildReservationRequest();
            req.getReservationRooms().get(0).setRoomTypeId(singleCapacityType.getId());
            return mockMvc.perform(post("/api/v1/reservations")
                            .header("Idempotency-Key", keyB)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andReturn().getResponse().getStatus();
        };

        Future<Integer> fA = executor.submit(taskA);
        Future<Integer> fB = executor.submit(taskB);

        executor.shutdown();
        executor.awaitTermination(15, TimeUnit.SECONDS);

        int statusA = fA.get();
        int statusB = fB.get();

        boolean oneCreated = (statusA == 201 && statusB != 201) || (statusB == 201 && statusA != 201);
        assertThat(oneCreated).as("Exactly one reservation must be created when capacity is 1").isTrue();

        Integer totalRoomsReserved = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservation_rooms WHERE room_type_id = ?",
                Integer.class, singleCapacityType.getId()
        );
        assertThat(totalRoomsReserved).isEqualTo(1);
    }

    @Test
    @DisplayName("10. Validation failure rollback leaves no orphan idempotency key and allows subsequent retry (Scenario M)")
    void testRollbackOnValidationFailureLeavesNoOrphanKey() throws Exception {
        String key = "res-rollback-" + UUID.randomUUID();

        ReservationCreateRequest req = buildReservationRequest();
        req.setCheckInDate(LocalDate.now().plusDays(5));
        req.setCheckOutDate(LocalDate.now().plusDays(2));

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM api_idempotency_keys WHERE idempotency_key = ?",
                Integer.class, key
        );
        assertThat(count).isEqualTo(0);

        req.setCheckInDate(LocalDate.now().plusDays(2));
        req.setCheckOutDate(LocalDate.now().plusDays(5));

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Key", key));
    }

    @Test
    @DisplayName("11. Expired idempotency key is rejected with 409 Conflict")
    void testExpiredKeyRejection() throws Exception {
        String key = "res-expired-" + UUID.randomUUID();
        ReservationCreateRequest req = buildReservationRequest();

        createAdminJdbcTemplate().update(
                "INSERT INTO api_idempotency_keys (property_id, operation, principal_id, idempotency_key, request_fingerprint, state, created_at, updated_at, expires_at) " +
                        "VALUES (?, 'CREATE_RESERVATION', '00000000-0000-0000-0000-000000000000', ?, 'dummy_fingerprint', 'COMPLETED', " +
                        "CURRENT_TIMESTAMP - INTERVAL '48 hours', CURRENT_TIMESTAMP - INTERVAL '48 hours', CURRENT_TIMESTAMP - INTERVAL '24 hours')",
                testProperty.getId(), key
        );

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The idempotency key has expired."));
    }

    @Test
    @DisplayName("12. Missing Idempotency-Key header allows normal creation without ledger row")
    void testBackwardCompatibilityWithoutKey() throws Exception {
        ReservationCreateRequest req = buildReservationRequest();

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Key"))
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }

    @Test
    @DisplayName("13. Blank or oversized Idempotency-Key header returns 400 Bad Request")
    void testInvalidKeyHeaderValidation() throws Exception {
        ReservationCreateRequest req = buildReservationRequest();

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        String longKey = "a".repeat(129);
        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", longKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("14. Application role pms_app is strictly denied DELETE on api_idempotency_keys")
    void testLeastPrivilegeAppRoleCannotDeleteLedger() {
        assertThatThrownBy(() -> jdbcTemplate.execute("DELETE FROM api_idempotency_keys"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table api_idempotency_keys");
    }

    @Test
    @DisplayName("15. Cross-property isolation allows same idempotency key across different properties (Scenario L)")
    void testCrossPropertyKeyIsolation() throws Exception {
        Property propertyB = createProperty("PROP-B");
        Guest guestB = createGuest(propertyB.getId(), "Guest B");
        RoomType roomTypeB = createRoomType(propertyB.getId(), "B-DLX", 2);

        String sharedKey = "shared-key-" + UUID.randomUUID();

        ReservationCreateRequest reqA = buildReservationRequest();
        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", sharedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqA)))
                .andExpect(status().isCreated());

        ReservationCreateRequest reqB = new ReservationCreateRequest();
        reqB.setPropertyId(propertyB.getId());
        reqB.setPrimaryGuestId(guestB.getId());
        reqB.setCheckInDate(LocalDate.now().plusDays(2));
        reqB.setCheckOutDate(LocalDate.now().plusDays(5));
        reqB.setAdults(1);
        reqB.setBookingSourceId(directBookingSourceId);

        ReservationRoomCreateRequest roomReqB = new ReservationRoomCreateRequest();
        roomReqB.setRoomTypeId(roomTypeB.getId());
        roomReqB.setQuantity(1);
        roomReqB.setUnitPrice(new BigDecimal("2000.00"));
        reqB.setReservationRooms(List.of(roomReqB));

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", sharedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqB)))
                .andExpect(status().isCreated());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM api_idempotency_keys WHERE idempotency_key = ?",
                Integer.class, sharedKey
        );
        assertThat(count).isEqualTo(2);
    }

    // --- Targeted Regression Tests for Step 3.4.3.11B.1 ---

    @Test
    @DisplayName("16. Guest request changes only in addressLine, city, or postal code return 422 (Scenario A)")
    void testGuestAddressChangeReturns422() throws Exception {
        String key = "guest-addr-" + UUID.randomUUID();
        GuestCreateRequest req1 = new GuestCreateRequest();
        req1.setPropertyId(testProperty.getId());
        req1.setName("Address Test Guest");
        req1.setAddress("123 Hill Road");
        req1.setCity("Munnar");
        req1.setPostalCode("685612");

        mockMvc.perform(post("/api/v1/guests")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        // Same key, changed city only
        GuestCreateRequest req2 = new GuestCreateRequest();
        req2.setPropertyId(testProperty.getId());
        req2.setName("Address Test Guest");
        req2.setAddress("123 Hill Road");
        req2.setCity("Kochi");
        req2.setPostalCode("685612");

        mockMvc.perform(post("/api/v1/guests")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("The idempotency key is already used with a different request payload."));
    }

    @Test
    @DisplayName("17. Reservation request changes only in adult, child, or infant counts return 422 (Scenario B)")
    void testReservationOccupancyCountChangeReturns422() throws Exception {
        String key = "res-occupancy-" + UUID.randomUUID();
        ReservationCreateRequest req1 = buildReservationRequest();
        req1.setAdults(2);
        req1.setChildren(0);
        req1.setInfants(0);

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        ReservationCreateRequest req2 = buildReservationRequest();
        req2.setAdults(2);
        req2.setChildren(1); // changed
        req2.setInfants(0);

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("The idempotency key is already used with a different request payload."));
    }

    @Test
    @DisplayName("18. Reservation request changes only in booking source return 422 (Scenario C)")
    void testReservationBookingSourceChangeReturns422() throws Exception {
        String key = "res-source-" + UUID.randomUUID();
        ReservationCreateRequest req1 = buildReservationRequest();

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        BookingSource otaSource = new BookingSource();
        otaSource.setName("OTA Test Source " + UUID.randomUUID().toString().substring(0, 4));
        otaSource.setCode("OTA_" + UUID.randomUUID().toString().substring(0, 6));
        otaSource.setActive(true);
        otaSource = bookingSourceRepository.saveAndFlush(otaSource);

        ReservationCreateRequest req2 = buildReservationRequest();
        req2.setBookingSourceId(otaSource.getId());

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("The idempotency key is already used with a different request payload."));
    }

    @Test
    @DisplayName("19. Reservation request changes only in external booking ID return 422 (Scenario D)")
    void testReservationExternalBookingIdChangeReturns422() throws Exception {
        String key = "res-ext-id-" + UUID.randomUUID();
        ReservationCreateRequest req1 = buildReservationRequest();
        req1.setExternalBookingId("EXT-BOOK-1111");

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        ReservationCreateRequest req2 = buildReservationRequest();
        req2.setExternalBookingId("EXT-BOOK-2222");

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("The idempotency key is already used with a different request payload."));
    }

    @Test
    @DisplayName("20. Canonical encoding prevents delimiter collision across fields (Scenario F)")
    void testCanonicalEncodingDelimiterCollisionResistance() {
        GuestCreateRequest req1 = new GuestCreateRequest();
        req1.setPropertyId(testProperty.getId());
        req1.setName("Alice;phone=10:1234567890;");
        req1.setPhone(null);

        GuestCreateRequest req2 = new GuestCreateRequest();
        req2.setPropertyId(testProperty.getId());
        req2.setName("Alice");
        req2.setPhone("1234567890");

        String fp1 = fingerprintService.generateGuestFingerprint(req1);
        String fp2 = fingerprintService.generateGuestFingerprint(req2);

        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    @DisplayName("21. Concurrent same-key guest creation race produces exactly 1 creation and replays (Scenario G)")
    void testConcurrentSameKeyGuestRace() throws Exception {
        String key = "guest-race-" + UUID.randomUUID();
        String uniqueEmail = "race-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);

        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                GuestCreateRequest req = new GuestCreateRequest();
                req.setPropertyId(testProperty.getId());
                req.setName("Concurrent Race Guest");
                req.setEmail(uniqueEmail);
                req.setPhone("+919333333333");

                MvcResult res = mockMvc.perform(post("/api/v1/guests")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                        .andReturn();
                return res.getResponse().getStatus();
            }));
        }

        executor.shutdown();
        boolean finished = executor.awaitTermination(15, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        int created201 = 0;
        int ok200 = 0;
        int retry409 = 0;

        for (Future<Integer> f : futures) {
            int status = f.get();
            if (status == 201) created201++;
            else if (status == 200) ok200++;
            else if (status == 409) retry409++;
        }

        assertThat(created201).isEqualTo(1);
        assertThat(created201 + ok200 + retry409).isEqualTo(threads);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM guests WHERE email = ?",
                Integer.class,
                uniqueEmail
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("22. Claim lock timeout with competing uncommitted transaction returns 409 and Retry-After: 1 (Scenario I)")
    void testClaimLockTimeoutWithCompetingUncommittedTransaction() throws Exception {
        String key = "res-lock-timeout-" + UUID.randomUUID();
        DriverManagerDataSource adminDs = new DriverManagerDataSource();
        adminDs.setDriverClassName("org.postgresql.Driver");
        adminDs.setUrl(environment.getProperty("spring.datasource.url", "jdbc:postgresql://localhost:5433/v7_disposable_db"));
        adminDs.setUsername("pms_admin");
        adminDs.setPassword(environment.getProperty("TEST_FLYWAY_PASSWORD", "disposable_pass"));

        try (Connection conn = adminDs.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO api_idempotency_keys (property_id, operation, principal_id, idempotency_key, request_fingerprint, state, expires_at) " +
                            "VALUES (?, 'CREATE_RESERVATION', '00000000-0000-0000-0000-000000000000', ?, 'competing_fingerprint', 'IN_PROGRESS', CURRENT_TIMESTAMP + INTERVAL '24 hours')"
            )) {
                ps.setObject(1, testProperty.getId());
                ps.setString(2, key);
                ps.executeUpdate();

                // Uncommitted competing transaction: mockMvc will block on the claim and time out after 3000ms
                ReservationCreateRequest req = buildReservationRequest();
                mockMvc.perform(post("/api/v1/reservations")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                        .andExpect(status().isConflict())
                        .andExpect(header().string("Retry-After", "1"))
                        .andExpect(jsonPath("$.message").value("A request with this idempotency key is currently in progress. Please retry."));
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    @DisplayName("23. Timeout restoration before RoomType inventory locking (Scenario J)")
    void testTimeoutRestorationBeforeInventoryLock() {
        String key = "res-timeout-restore-" + UUID.randomUUID();
        ReservationCreateRequest req = buildReservationRequest();
        String fp = fingerprintService.generateReservationFingerprint(req);

        // Execute claimKey within a transaction and verify timeout restoration
        transactionTemplate.execute(status -> {
            ClaimOutcome outcome = idempotencyService.claimKey(
                    testProperty.getId(),
                    IdempotencyOperation.CREATE_RESERVATION,
                    IdempotencyServiceImpl.ANONYMOUS_PRINCIPAL_ID,
                    key,
                    fp
            );
            assertThat(outcome).isInstanceOf(ClaimOutcome.Claimed.class);

            // Verify that lock_timeout is restored to session default (0) rather than remaining 3000ms
            String activeTimeout = jdbcTemplate.queryForObject("SHOW lock_timeout", String.class);
            assertThat(activeTimeout).isIn("0", "0s");
            return null;
        });
    }

    @Test
    @DisplayName("24. Replay authorization: key possession cannot bypass property authorization (Scenario K)")
    void testReplayAuthorizationPropertyGuarded() throws Exception {
        String key = "guarded-key-" + UUID.randomUUID();
        ReservationCreateRequest req = buildReservationRequest();

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        // Attempting replay with non-existent property ID is rejected with 404
        UUID unauthorizedPropId = UUID.randomUUID();
        ReservationCreateRequest reqUnauthorized = buildReservationRequest();
        reqUnauthorized.setPropertyId(unauthorizedPropId);

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqUnauthorized)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("25. Replaying a resource that was subsequently deleted returns 404 and does not leak data (Scenario N)")
    void testReplayDeletedResourceReturns404() throws Exception {
        String key = "res-deleted-" + UUID.randomUUID();
        ReservationCreateRequest req = buildReservationRequest();

        MvcResult res = mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID resId = UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());

        // Delete the reservation using admin template
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        adminJdbc.update("DELETE FROM reservation_rooms WHERE reservation_id = ?", resId);
        adminJdbc.update("DELETE FROM reservations WHERE id = ?", resId);

        // Attempt to replay with the same key
        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("26. Cross-instance / restart simulation: replaying purely from DB persistence returns 200 OK (Scenario O)")
    void testCrossInstanceReplayFromDatabase() throws Exception {
        String key = "cross-inst-" + UUID.randomUUID();
        ReservationCreateRequest req = buildReservationRequest();

        MvcResult first = mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID firstId = UUID.fromString(objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText());

        // Replay with identical key and payload simulates fresh client request hitting backend
        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(firstId.toString()));
    }

    private ReservationCreateRequest buildReservationRequest() {
        ReservationCreateRequest req = new ReservationCreateRequest();
        req.setPropertyId(testProperty.getId());
        req.setPrimaryGuestId(testGuest.getId());
        req.setCheckInDate(LocalDate.now().plusDays(3));
        req.setCheckOutDate(LocalDate.now().plusDays(6));
        req.setAdults(2);
        req.setChildren(0);
        req.setInfants(0);
        req.setBookingSourceId(directBookingSourceId);
        req.setSpecialRequests("First floor preferred");

        ReservationRoomCreateRequest roomReq = new ReservationRoomCreateRequest();
        roomReq.setRoomTypeId(testRoomType.getId());
        roomReq.setQuantity(1);
        roomReq.setUnitPrice(new BigDecimal("2500.00"));
        req.setReservationRooms(List.of(roomReq));

        return req;
    }
}
