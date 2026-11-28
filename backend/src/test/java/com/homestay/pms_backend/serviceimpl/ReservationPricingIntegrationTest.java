package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.ReservationCreateRequest;
import com.homestay.pms_backend.dto.request.ReservationRoomAddRequest;
import com.homestay.pms_backend.dto.request.ReservationRoomCreateRequest;
import com.homestay.pms_backend.dto.request.ReservationUpdateRequest;
import com.homestay.pms_backend.dto.response.ReservationResponse;
import com.homestay.pms_backend.dto.response.ReservationRoomResponse;
import com.homestay.pms_backend.entity.BookingSource;
import com.homestay.pms_backend.entity.Guest;
import com.homestay.pms_backend.entity.Property;
import com.homestay.pms_backend.entity.Reservation;
import com.homestay.pms_backend.entity.ReservationRoom;
import com.homestay.pms_backend.entity.Room;
import com.homestay.pms_backend.entity.RoomType;
import com.homestay.pms_backend.enums.HousekeepingStatus;
import com.homestay.pms_backend.enums.IdentityDocumentType;
import com.homestay.pms_backend.enums.ReservationStatus;
import com.homestay.pms_backend.enums.RoomStatus;
import com.homestay.pms_backend.enums.RoomTypeCategory;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.IdempotencyConflictException;
import com.homestay.pms_backend.repository.BookingSourceRepository;
import com.homestay.pms_backend.repository.GuestPropertyAssociationRepository;
import com.homestay.pms_backend.repository.GuestRepository;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.repository.ReservationRepository;
import com.homestay.pms_backend.repository.ReservationRoomRepository;
import com.homestay.pms_backend.repository.RoomRepository;
import com.homestay.pms_backend.repository.RoomTypeRepository;
import com.homestay.pms_backend.service.ReservationRoomService;
import com.homestay.pms_backend.service.ReservationService;
import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IsolatedDatabaseTest
@DisplayName("Step 3.4.3.13B.2: Reservation Accommodation Subtotal and Price Integrity Integration Tests")
class ReservationPricingIntegrationTest {

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
    private ReservationService reservationService;

    @Autowired
    private ReservationRoomService reservationRoomService;

    private Property testProperty;
    private Guest testGuest;
    private RoomType roomTypeA;
    private RoomType roomTypeB;
    private UUID directBookingSourceId;
    private final List<UUID> createdPropertyIds = new ArrayList<>();
    private final List<UUID> createdGuestIds = new ArrayList<>();

    private JdbcTemplate createAdminJdbcTemplate() {
        DriverManagerDataSource adminDs = new DriverManagerDataSource();
        adminDs.setDriverClassName("org.postgresql.Driver");
        adminDs.setUrl(environment.getProperty("TEST_DATASOURCE_URL", "jdbc:postgresql://localhost:5433/v7_disposable_db"));
        adminDs.setUsername(environment.getProperty("TEST_FLYWAY_USER", "pms_admin"));
        adminDs.setPassword(environment.getProperty("TEST_FLYWAY_PASSWORD", "disposable_pass"));
        return new JdbcTemplate(adminDs);
    }

    @BeforeEach
    void setUp() {
        BookingSource directSource = bookingSourceRepository.findByCode("DIRECT")
                .orElseGet(() -> {
                    BookingSource s = new BookingSource();
                    s.setCode("DIRECT");
                    s.setName("Direct Front Desk");
                    s.setActive(true);
                    return bookingSourceRepository.saveAndFlush(s);
                });
        directBookingSourceId = directSource.getId();

        testProperty = new Property();
        testProperty.setName("La Vivante Homestay " + UUID.randomUUID().toString().substring(0, 6));
        testProperty.setCode("LV-" + UUID.randomUUID().toString().substring(0, 8));
        testProperty.setAddressLine1("Bhowali-Ramgarh Road");
        testProperty.setCity("Nainital");
        testProperty.setState("Uttarakhand");
        testProperty.setCountry("India");
        testProperty.setTimezone("Asia/Kolkata");
        testProperty.setActive(true);
        testProperty = propertyRepository.saveAndFlush(testProperty);
        createdPropertyIds.add(testProperty.getId());

        testGuest = new Guest();
        testGuest.setName("Vikram Malhotra");
        testGuest.setPhone("+919876543210");
        testGuest.setEmail("vikram-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com");
        testGuest.setIdType(IdentityDocumentType.PASSPORT);
        testGuest.setIdNumber("Z" + UUID.randomUUID().toString().substring(0, 7));
        testGuest.setActive(true);
        testGuest = guestRepository.saveAndFlush(testGuest);
        createdGuestIds.add(testGuest.getId());

        createAdminJdbcTemplate().update(
                "INSERT INTO guest_property_associations (guest_id, property_id, first_associated_at, last_activity_at, association_source) " +
                        "VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'REGISTRATION') " +
                        "ON CONFLICT (guest_id, property_id) DO NOTHING",
                testGuest.getId(), testProperty.getId()
        );

        roomTypeA = new RoomType();
        roomTypeA.setPropertyId(testProperty.getId());
        roomTypeA.setName("Standard Room");
        roomTypeA.setCode("STD-" + UUID.randomUUID().toString().substring(0, 4));
        roomTypeA.setCategory(RoomTypeCategory.PRIVATE_ROOM);
        roomTypeA.setBaseOccupancy(2);
        roomTypeA.setMaxOccupancy(3);
        roomTypeA.setActive(true);
        roomTypeA = roomTypeRepository.saveAndFlush(roomTypeA);

        roomTypeB = new RoomType();
        roomTypeB.setPropertyId(testProperty.getId());
        roomTypeB.setName("Deluxe Lakeview Suite");
        roomTypeB.setCode("DLX-" + UUID.randomUUID().toString().substring(0, 4));
        roomTypeB.setCategory(RoomTypeCategory.SUITE);
        roomTypeB.setBaseOccupancy(2);
        roomTypeB.setMaxOccupancy(4);
        roomTypeB.setActive(true);
        roomTypeB = roomTypeRepository.saveAndFlush(roomTypeB);

        createPhysicalRoom(testProperty.getId(), roomTypeA.getId(), "101");
        createPhysicalRoom(testProperty.getId(), roomTypeA.getId(), "102");
        createPhysicalRoom(testProperty.getId(), roomTypeB.getId(), "201");
        createPhysicalRoom(testProperty.getId(), roomTypeB.getId(), "202");
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

    private Room createPhysicalRoom(UUID propertyId, UUID roomTypeId, String roomNumber) {
        Room r = new Room();
        r.setPropertyId(propertyId);
        r.setRoomTypeId(roomTypeId);
        r.setRoomNumber(roomNumber);
        r.setStatus(RoomStatus.AVAILABLE);
        r.setHousekeepingStatus(HousekeepingStatus.CLEAN);
        r.setActive(true);
        return roomRepository.saveAndFlush(r);
    }

    @Test
    @DisplayName("Test D: Multiple reservation room types with accurate subtotals persisted in DB")
    void multipleReservationRoomTypes_accurateSubtotalsPersisted() {
        ReservationCreateRequest request = new ReservationCreateRequest();
        request.setPropertyId(testProperty.getId());
        request.setPrimaryGuestId(testGuest.getId());
        request.setBookingSourceId(directBookingSourceId);
        request.setCheckInDate(LocalDate.of(2026, 12, 10));
        request.setCheckOutDate(LocalDate.of(2026, 12, 12)); // 2 nights
        request.setAdults(3);

        ReservationRoomCreateRequest roomA = new ReservationRoomCreateRequest();
        roomA.setRoomTypeId(roomTypeA.getId());
        roomA.setQuantity(1);
        roomA.setUnitPrice(new BigDecimal("2000.00")); // 1 room * 2 nights * 2000.00 = 4000.00

        ReservationRoomCreateRequest roomB = new ReservationRoomCreateRequest();
        roomB.setRoomTypeId(roomTypeB.getId());
        roomB.setQuantity(2);
        roomB.setUnitPrice(new BigDecimal("3500.00")); // 2 rooms * 2 nights * 3500.00 = 14000.00

        request.setReservationRooms(List.of(roomA, roomB));

        ReservationResponse response = reservationService.createReservation(request);
        assertThat(response).isNotNull();

        List<ReservationRoom> savedRooms = reservationRoomRepository.findByReservationId(response.getId());
        assertThat(savedRooms).hasSize(2);

        ReservationRoom savedA = savedRooms.stream()
                .filter(r -> r.getRoomTypeId().equals(roomTypeA.getId()))
                .findFirst().orElseThrow();
        assertThat(savedA.getQuantity()).isEqualTo(1);
        assertThat(savedA.getCancelledQuantity()).isEqualTo(0);
        assertThat(savedA.getUnitPrice()).isEqualByComparingTo("2000.00");
        assertThat(savedA.getSubtotal()).isEqualByComparingTo("4000.00");

        ReservationRoom savedB = savedRooms.stream()
                .filter(r -> r.getRoomTypeId().equals(roomTypeB.getId()))
                .findFirst().orElseThrow();
        assertThat(savedB.getQuantity()).isEqualTo(2);
        assertThat(savedB.getCancelledQuantity()).isEqualTo(0);
        assertThat(savedB.getUnitPrice()).isEqualByComparingTo("3500.00");
        assertThat(savedB.getSubtotal()).isEqualByComparingTo("14000.00");
    }

    @Test
    @DisplayName("Test K: Reservation date amendment recalculates accommodation subtotal")
    void dateAmendment_recalculatesAccommodationSubtotal() {
        ReservationCreateRequest request = new ReservationCreateRequest();
        request.setPropertyId(testProperty.getId());
        request.setPrimaryGuestId(testGuest.getId());
        request.setBookingSourceId(directBookingSourceId);
        request.setCheckInDate(LocalDate.of(2026, 12, 10));
        request.setCheckOutDate(LocalDate.of(2026, 12, 12)); // 2 nights
        request.setAdults(2);

        ReservationRoomCreateRequest roomA = new ReservationRoomCreateRequest();
        roomA.setRoomTypeId(roomTypeA.getId());
        roomA.setQuantity(1);
        roomA.setUnitPrice(new BigDecimal("2500.00")); // 1 room * 2 nights * 2500.00 = 5000.00
        request.setReservationRooms(List.of(roomA));

        ReservationResponse created = reservationService.createReservation(request);

        List<ReservationRoom> initialRooms = reservationRoomRepository.findByReservationId(created.getId());
        assertThat(initialRooms.get(0).getSubtotal()).isEqualByComparingTo("5000.00");

        // Extend stay by 1 night: Dec 10 to Dec 13 (3 nights)
        ReservationUpdateRequest updateRequest = new ReservationUpdateRequest();
        updateRequest.setCheckOutDate(LocalDate.of(2026, 12, 13));

        reservationService.updateReservation(created.getId(), updateRequest);

        List<ReservationRoom> updatedRooms = reservationRoomRepository.findByReservationId(created.getId());
        // 1 room * 3 nights * 2500.00 = 7500.00
        assertThat(updatedRooms.get(0).getSubtotal()).isEqualByComparingTo("7500.00");
        assertThat(updatedRooms.get(0).getUnitPrice()).isEqualByComparingTo("2500.00");
    }

    @Test
    @DisplayName("Test L: Disallowed checked-in date amendment is strictly rejected")
    void checkedInReservation_dateAmendmentRejected() {
        ReservationCreateRequest request = new ReservationCreateRequest();
        request.setPropertyId(testProperty.getId());
        request.setPrimaryGuestId(testGuest.getId());
        request.setBookingSourceId(directBookingSourceId);
        request.setCheckInDate(LocalDate.of(2026, 12, 10));
        request.setCheckOutDate(LocalDate.of(2026, 12, 12));
        request.setAdults(2);

        ReservationRoomCreateRequest roomA = new ReservationRoomCreateRequest();
        roomA.setRoomTypeId(roomTypeA.getId());
        roomA.setQuantity(1);
        roomA.setUnitPrice(new BigDecimal("2500.00"));
        request.setReservationRooms(List.of(roomA));

        ReservationResponse created = reservationService.createReservation(request);

        // Transition status to CHECKED_IN
        Reservation res = reservationRepository.findById(created.getId()).orElseThrow();
        res.setStatus(ReservationStatus.CHECKED_IN);
        reservationRepository.saveAndFlush(res);

        ReservationUpdateRequest updateRequest = new ReservationUpdateRequest();
        updateRequest.setCheckOutDate(LocalDate.of(2026, 12, 14));

        assertThatThrownBy(() -> reservationService.updateReservation(created.getId(), updateRequest))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Checked-in reservations cannot be modified");
    }

    @Test
    @DisplayName("Test M: Added reservation room line computes and stores subtotal")
    void addReservationRoomLine_computesAndStoresSubtotal() {
        ReservationCreateRequest request = new ReservationCreateRequest();
        request.setPropertyId(testProperty.getId());
        request.setPrimaryGuestId(testGuest.getId());
        request.setBookingSourceId(directBookingSourceId);
        request.setCheckInDate(LocalDate.of(2026, 12, 10));
        request.setCheckOutDate(LocalDate.of(2026, 12, 13)); // 3 nights
        request.setAdults(2);

        ReservationRoomCreateRequest roomA = new ReservationRoomCreateRequest();
        roomA.setRoomTypeId(roomTypeA.getId());
        roomA.setQuantity(1);
        roomA.setUnitPrice(new BigDecimal("2000.00"));
        request.setReservationRooms(List.of(roomA));

        ReservationResponse created = reservationService.createReservation(request);

        // Add a second room line (Suite B: 1 room @ 4000.00 for 3 nights = 12000.00)
        ReservationRoomAddRequest addRequest = new ReservationRoomAddRequest();
        addRequest.setReservationId(created.getId());
        addRequest.setRoomTypeId(roomTypeB.getId());
        addRequest.setQuantity(1);
        addRequest.setUnitPrice(new BigDecimal("4000.00"));

        ReservationRoomResponse addedRoom = reservationRoomService.addReservationRoom(addRequest);

        assertThat(addedRoom.getSubtotal()).isEqualByComparingTo("12000.00");
        assertThat(addedRoom.getUnitPrice()).isEqualByComparingTo("4000.00");
        assertThat(addedRoom.getQuantity()).isEqualTo(1);
    }

    @Test
    @DisplayName("Test N: Existing V13 idempotent creation replay returns identical subtotal without recalculation")
    void idempotentCreationReplay_preservesSubtotalAndDoesNotDuplicate() {
        String idempotencyKey = UUID.randomUUID().toString();

        ReservationCreateRequest request = new ReservationCreateRequest();
        request.setPropertyId(testProperty.getId());
        request.setPrimaryGuestId(testGuest.getId());
        request.setBookingSourceId(directBookingSourceId);
        request.setCheckInDate(LocalDate.of(2026, 12, 10));
        request.setCheckOutDate(LocalDate.of(2026, 12, 12)); // 2 nights
        request.setAdults(2);

        ReservationRoomCreateRequest roomA = new ReservationRoomCreateRequest();
        roomA.setRoomTypeId(roomTypeA.getId());
        roomA.setQuantity(1);
        roomA.setUnitPrice(new BigDecimal("2500.00")); // 1 room * 2 nights * 2500 = 5000.00
        request.setReservationRooms(List.of(roomA));

        var initialResult = reservationService.createReservationIdempotent(request, idempotencyKey);
        assertThat(initialResult.replayed()).isFalse();

        List<ReservationRoom> savedRooms = reservationRoomRepository.findByReservationId(initialResult.data().getId());
        assertThat(savedRooms).hasSize(1);
        assertThat(savedRooms.get(0).getSubtotal()).isEqualByComparingTo("5000.00");

        // Exact replay with same key
        var replayResult = reservationService.createReservationIdempotent(request, idempotencyKey);
        assertThat(replayResult.replayed()).isTrue();
        assertThat(replayResult.data().getId()).isEqualTo(initialResult.data().getId());

        List<ReservationRoom> afterReplayRooms = reservationRoomRepository.findByReservationId(initialResult.data().getId());
        assertThat(afterReplayRooms).hasSize(1);
        assertThat(afterReplayRooms.get(0).getSubtotal()).isEqualByComparingTo("5000.00");
        assertThat(afterReplayRooms.get(0).getUnitPrice()).isEqualByComparingTo("2500.00");
    }

    @Test
    @DisplayName("Test O: Existing V13 payload mismatch produces IdempotencyConflictException (HTTP 422)")
    void idempotentPayloadMismatch_producesConflictException() {
        String idempotencyKey = UUID.randomUUID().toString();

        ReservationCreateRequest request1 = new ReservationCreateRequest();
        request1.setPropertyId(testProperty.getId());
        request1.setPrimaryGuestId(testGuest.getId());
        request1.setBookingSourceId(directBookingSourceId);
        request1.setCheckInDate(LocalDate.of(2026, 12, 10));
        request1.setCheckOutDate(LocalDate.of(2026, 12, 12));
        request1.setAdults(2);

        ReservationRoomCreateRequest roomA = new ReservationRoomCreateRequest();
        roomA.setRoomTypeId(roomTypeA.getId());
        roomA.setQuantity(1);
        roomA.setUnitPrice(new BigDecimal("2500.00"));
        request1.setReservationRooms(List.of(roomA));

        reservationService.createReservationIdempotent(request1, idempotencyKey);

        // Attempt replay with changed unitPrice (payload mismatch)
        ReservationCreateRequest request2 = new ReservationCreateRequest();
        request2.setPropertyId(testProperty.getId());
        request2.setPrimaryGuestId(testGuest.getId());
        request2.setBookingSourceId(directBookingSourceId);
        request2.setCheckInDate(LocalDate.of(2026, 12, 10));
        request2.setCheckOutDate(LocalDate.of(2026, 12, 12));
        request2.setAdults(2);

        ReservationRoomCreateRequest roomModified = new ReservationRoomCreateRequest();
        roomModified.setRoomTypeId(roomTypeA.getId());
        roomModified.setQuantity(1);
        roomModified.setUnitPrice(new BigDecimal("3000.00")); // Different price!
        request2.setReservationRooms(List.of(roomModified));

        assertThatThrownBy(() -> reservationService.createReservationIdempotent(request2, idempotencyKey))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("Test P: Catalog price changes do not alter agreed unitPrice or subtotal of existing bookings")
    void catalogPriceChange_doesNotAlterBookedUnitPriceOrSubtotal() {
        ReservationCreateRequest request = new ReservationCreateRequest();
        request.setPropertyId(testProperty.getId());
        request.setPrimaryGuestId(testGuest.getId());
        request.setBookingSourceId(directBookingSourceId);
        request.setCheckInDate(LocalDate.of(2026, 12, 10));
        request.setCheckOutDate(LocalDate.of(2026, 12, 13)); // 3 nights
        request.setAdults(2);

        ReservationRoomCreateRequest roomA = new ReservationRoomCreateRequest();
        roomA.setRoomTypeId(roomTypeA.getId());
        roomA.setQuantity(1);
        roomA.setUnitPrice(new BigDecimal("2000.00")); // 1 * 3 * 2000 = 6000.00
        request.setReservationRooms(List.of(roomA));

        ReservationResponse created = reservationService.createReservation(request);

        // Now suppose catalog or room type name/attributes are modified
        roomTypeA.setName("Standard Room - Seasonal Rate Increase");
        roomTypeRepository.saveAndFlush(roomTypeA);

        // Fetch reservation rooms again
        List<ReservationRoom> savedRooms = reservationRoomRepository.findByReservationId(created.getId());
        assertThat(savedRooms.get(0).getUnitPrice()).isEqualByComparingTo("2000.00");
        assertThat(savedRooms.get(0).getSubtotal()).isEqualByComparingTo("6000.00");
    }

    @Test
    @DisplayName("Test R: Cancellation sets active subtotal to 0.00 while preserving unitPrice and quantity")
    void cancellation_setsSubtotalToZeroWhilePreservingAgreedUnitPrice() {
        ReservationCreateRequest request = new ReservationCreateRequest();
        request.setPropertyId(testProperty.getId());
        request.setPrimaryGuestId(testGuest.getId());
        request.setBookingSourceId(directBookingSourceId);
        request.setCheckInDate(LocalDate.of(2026, 12, 10));
        request.setCheckOutDate(LocalDate.of(2026, 12, 12)); // 2 nights
        request.setAdults(2);

        ReservationRoomCreateRequest roomA = new ReservationRoomCreateRequest();
        roomA.setRoomTypeId(roomTypeA.getId());
        roomA.setQuantity(1);
        roomA.setUnitPrice(new BigDecimal("3000.00")); // 1 * 2 * 3000 = 6000.00
        request.setReservationRooms(List.of(roomA));

        ReservationResponse created = reservationService.createReservation(request);

        // Cancel reservation
        reservationService.cancelReservation(created.getId());

        List<ReservationRoom> cancelledRooms = reservationRoomRepository.findByReservationId(created.getId());
        ReservationRoom rr = cancelledRooms.get(0);
        assertThat(rr.getQuantity()).isEqualTo(1);
        assertThat(rr.getCancelledQuantity()).isEqualTo(1);
        assertThat(rr.getUnitPrice()).isEqualByComparingTo("3000.00"); // Preserved!
        assertThat(rr.getSubtotal()).isEqualByComparingTo("0.00");     // Active subtotal is 0.00
    }

    @Test
    @DisplayName("Direct service call: rejects null unitPrice on creation and rolls back completely")
    void testDirectServiceCall_rejectsNullUnitPrice_andPersistsNoReservationOrRoomLines() {
        long resCountBefore = reservationRepository.count();
        long roomCountBefore = reservationRoomRepository.count();

        ReservationCreateRequest request = new ReservationCreateRequest();
        request.setPropertyId(testProperty.getId());
        request.setPrimaryGuestId(testGuest.getId());
        request.setBookingSourceId(directBookingSourceId);
        request.setCheckInDate(LocalDate.of(2026, 12, 20));
        request.setCheckOutDate(LocalDate.of(2026, 12, 22));
        request.setAdults(2);

        ReservationRoomCreateRequest roomA = new ReservationRoomCreateRequest();
        roomA.setRoomTypeId(roomTypeA.getId());
        roomA.setQuantity(1);
        roomA.setUnitPrice(null); // Explicit null price!
        request.setReservationRooms(List.of(roomA));

        assertThatThrownBy(() -> reservationService.createReservation(request))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Unit price must not be null");

        // Assert zero entities persisted (transactional rollback & pre-save validation)
        assertThat(reservationRepository.count()).isEqualTo(resCountBefore);
        assertThat(reservationRoomRepository.count()).isEqualTo(roomCountBefore);
    }

    @Test
    @DisplayName("Direct service call: rejects negative unitPrice on creation and rolls back")
    void testDirectServiceCall_rejectsNegativeUnitPrice_andPersistsNoReservationOrRoomLines() {
        long resCountBefore = reservationRepository.count();
        long roomCountBefore = reservationRoomRepository.count();

        ReservationCreateRequest request = new ReservationCreateRequest();
        request.setPropertyId(testProperty.getId());
        request.setPrimaryGuestId(testGuest.getId());
        request.setBookingSourceId(directBookingSourceId);
        request.setCheckInDate(LocalDate.of(2026, 12, 20));
        request.setCheckOutDate(LocalDate.of(2026, 12, 22));
        request.setAdults(2);

        ReservationRoomCreateRequest roomA = new ReservationRoomCreateRequest();
        roomA.setRoomTypeId(roomTypeA.getId());
        roomA.setQuantity(1);
        roomA.setUnitPrice(new BigDecimal("-50.00"));
        request.setReservationRooms(List.of(roomA));

        assertThatThrownBy(() -> reservationService.createReservation(request))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Unit price cannot be negative");

        assertThat(reservationRepository.count()).isEqualTo(resCountBefore);
        assertThat(reservationRoomRepository.count()).isEqualTo(roomCountBefore);
    }

    @Test
    @DisplayName("Direct service call: addReservationRoom rejects null unitPrice and persists no room line")
    void testDirectServiceCall_addReservationRoom_rejectsNullUnitPrice() {
        // Create a valid reservation first
        ReservationCreateRequest request = new ReservationCreateRequest();
        request.setPropertyId(testProperty.getId());
        request.setPrimaryGuestId(testGuest.getId());
        request.setBookingSourceId(directBookingSourceId);
        request.setCheckInDate(LocalDate.of(2026, 12, 25));
        request.setCheckOutDate(LocalDate.of(2026, 12, 27));
        request.setAdults(2);

        ReservationRoomCreateRequest roomA = new ReservationRoomCreateRequest();
        roomA.setRoomTypeId(roomTypeA.getId());
        roomA.setQuantity(1);
        roomA.setUnitPrice(new BigDecimal("2500.00"));
        request.setReservationRooms(List.of(roomA));

        ReservationResponse res = reservationService.createReservation(request);
        long roomCountBefore = reservationRoomRepository.count();

        ReservationRoomAddRequest addReq = new ReservationRoomAddRequest();
        addReq.setReservationId(res.getId());
        addReq.setRoomTypeId(roomTypeB.getId());
        addReq.setQuantity(1);
        addReq.setUnitPrice(null); // Null unitPrice!

        assertThatThrownBy(() -> reservationRoomService.addReservationRoom(addReq))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Unit price must not be null");

        assertThat(reservationRoomRepository.count()).isEqualTo(roomCountBefore);
    }

    @Test
    @DisplayName("Explicit zero price complimentary booking succeeds and persists 0.00 subtotal")
    void testExplicitZeroPrice_complimentaryBookingSucceeds() {
        ReservationCreateRequest request = new ReservationCreateRequest();
        request.setPropertyId(testProperty.getId());
        request.setPrimaryGuestId(testGuest.getId());
        request.setBookingSourceId(directBookingSourceId);
        request.setCheckInDate(LocalDate.of(2026, 12, 28));
        request.setCheckOutDate(LocalDate.of(2026, 12, 30)); // 2 nights
        request.setAdults(1);

        ReservationRoomCreateRequest roomA = new ReservationRoomCreateRequest();
        roomA.setRoomTypeId(roomTypeA.getId());
        roomA.setQuantity(1);
        roomA.setUnitPrice(BigDecimal.ZERO); // Explicit complimentary!
        request.setReservationRooms(List.of(roomA));

        ReservationResponse res = reservationService.createReservation(request);
        assertThat(res).isNotNull();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.PENDING);

        List<ReservationRoom> rooms = reservationRoomRepository.findByReservationId(res.getId());
        assertThat(rooms).hasSize(1);
        assertThat(rooms.get(0).getUnitPrice()).isEqualByComparingTo("0.00");
        assertThat(rooms.get(0).getSubtotal()).isEqualByComparingTo("0.00");
    }
}
