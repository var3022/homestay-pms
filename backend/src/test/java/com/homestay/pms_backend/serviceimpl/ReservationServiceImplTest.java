package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.config.SecurityEnforcementProperties;
import com.homestay.pms_backend.dto.request.ReservationUpdateRequest;
import com.homestay.pms_backend.dto.response.ReservationResponse;
import com.homestay.pms_backend.entity.BookingSource;
import com.homestay.pms_backend.entity.Property;
import com.homestay.pms_backend.entity.Reservation;
import com.homestay.pms_backend.entity.ReservationRoom;
import com.homestay.pms_backend.entity.Room;
import com.homestay.pms_backend.entity.RoomAssignment;
import com.homestay.pms_backend.entity.RoomType;
import com.homestay.pms_backend.enums.HousekeepingStatus;
import com.homestay.pms_backend.enums.ReservationRoomStatus;
import com.homestay.pms_backend.enums.ReservationStatus;
import com.homestay.pms_backend.enums.RoomAssignmentStatus;
import com.homestay.pms_backend.enums.RoomStatus;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.mapper.ReservationMapper;
import com.homestay.pms_backend.mapper.ReservationRoomMapper;
import com.homestay.pms_backend.repository.BookingSourceRepository;
import com.homestay.pms_backend.repository.GuestRepository;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.repository.ReservationRepository;
import com.homestay.pms_backend.repository.ReservationRoomRepository;
import com.homestay.pms_backend.repository.RoomAssignmentRepository;
import com.homestay.pms_backend.repository.RoomRepository;
import com.homestay.pms_backend.repository.RoomTypeRepository;
import com.homestay.pms_backend.security.AuthenticatedStaffPrincipal;
import com.homestay.pms_backend.security.authorization.StaffAuthorizationService;
import com.homestay.pms_backend.service.GuestPropertyAssociationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceImplTest {

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private PropertyRepository propertyRepository;
    @Mock
    private GuestRepository guestRepository;
    @Mock
    private BookingSourceRepository bookingSourceRepository;
    @Mock
    private ReservationRoomRepository reservationRoomRepository;
    @Mock
    private RoomAssignmentRepository roomAssignmentRepository;
    @Mock
    private RoomRepository roomRepository;
    @Mock
    private RoomTypeRepository roomTypeRepository;
    @Mock
    private ReservationRoomMapper reservationRoomMapper;
    @Mock
    private GuestPropertyAssociationService guestPropertyAssociationService;
    @Mock
    private EntityManager entityManager;
    @Mock
    private Clock clock;
    @Mock
    private StaffAuthorizationService staffAuthorizationService;
    @Mock
    private SecurityEnforcementProperties securityEnforcementProperties;

    @InjectMocks
    private ReservationServiceImpl reservationService;

    private UUID reservationId;
    private UUID propertyId;
    private UUID bookingSourceId;
    private UUID roomTypeId;
    private BookingSource directSource;
    private BookingSource otaSource;
    private Reservation reservation;
    private RoomType roomType;

    @BeforeEach
    void setUp() {
        reservationId = UUID.randomUUID();
        propertyId = UUID.randomUUID();
        bookingSourceId = UUID.randomUUID();
        roomTypeId = UUID.randomUUID();

        directSource = new BookingSource();
        directSource.setId(bookingSourceId);
        directSource.setCode("DIRECT");
        directSource.setName("Direct Booking");
        directSource.setActive(true);

        otaSource = new BookingSource();
        otaSource.setId(UUID.randomUUID());
        otaSource.setCode("BOOKING_COM");
        otaSource.setName("Booking.com");
        otaSource.setActive(true);

        reservation = new Reservation();
        reservation.setId(reservationId);
        reservation.setPropertyId(propertyId);
        reservation.setBookingSourceId(bookingSourceId);
        reservation.setCheckInDate(LocalDate.of(2026, 11, 10));
        reservation.setCheckOutDate(LocalDate.of(2026, 11, 12));
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setAdults(2);

        roomType = new RoomType();
        roomType.setId(roomTypeId);
        roomType.setPropertyId(propertyId);
        roomType.setName("Deluxe Room");
        roomType.setActive(true);
    }

    @Test
    @DisplayName("updateReservation rejects modification of CANCELLED reservations")
    void updateReservation_rejectsCancelledReservation() {
        reservation.setStatus(ReservationStatus.CANCELLED);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        ReservationUpdateRequest request = new ReservationUpdateRequest();
        request.setAdults(3);

        assertThatThrownBy(() -> reservationService.updateReservation(reservationId, request))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cancelled reservations cannot be modified");

        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("updateReservation rejects modification of CHECKED_IN reservations")
    void updateReservation_rejectsCheckedInReservation() {
        reservation.setStatus(ReservationStatus.CHECKED_IN);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        ReservationUpdateRequest request = new ReservationUpdateRequest();
        request.setCheckOutDate(LocalDate.of(2026, 11, 15));

        assertThatThrownBy(() -> reservationService.updateReservation(reservationId, request))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Checked-in reservations cannot be modified through the generic update endpoint");

        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("updateReservation rejects modification of CHECKED_OUT reservations")
    void updateReservation_rejectsCheckedOutReservation() {
        reservation.setStatus(ReservationStatus.CHECKED_OUT);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        ReservationUpdateRequest request = new ReservationUpdateRequest();
        assertThatThrownBy(() -> reservationService.updateReservation(reservationId, request))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Checked-out reservations cannot be modified");

        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("updateReservation rejects modification of NO_SHOW reservations")
    void updateReservation_rejectsNoShowReservation() {
        reservation.setStatus(ReservationStatus.NO_SHOW);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        ReservationUpdateRequest request = new ReservationUpdateRequest();
        assertThatThrownBy(() -> reservationService.updateReservation(reservationId, request))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("No-show reservations cannot be modified");

        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("updateReservation rejects OTA bookings via generic update endpoint")
    void updateReservation_rejectsOtaReservation() {
        reservation.setBookingSourceId(otaSource.getId());
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(bookingSourceRepository.findById(otaSource.getId())).thenReturn(Optional.of(otaSource));

        ReservationUpdateRequest request = new ReservationUpdateRequest();
        request.setAdults(3);

        assertThatThrownBy(() -> reservationService.updateReservation(reservationId, request))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("OTA reservations cannot be modified through the generic update endpoint");

        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("updateReservation rejects checkout date on or before checkin date")
    void updateReservation_rejectsInvalidDates_checkOutBeforeCheckIn() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(bookingSourceRepository.findById(bookingSourceId)).thenReturn(Optional.of(directSource));

        ReservationUpdateRequest request = new ReservationUpdateRequest();
        request.setCheckInDate(LocalDate.of(2026, 11, 15));
        request.setCheckOutDate(LocalDate.of(2026, 11, 14));

        assertThatThrownBy(() -> reservationService.updateReservation(reservationId, request))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Check-out date must be after check-in date");

        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("updateReservation extending checkout rejects when assignment still ends on the old checkout date")
    void updateReservation_extendingCheckout_rejectsWhenAssignmentEndsOnOldCheckoutDate() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(bookingSourceRepository.findById(bookingSourceId)).thenReturn(Optional.of(directSource));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setReservationId(reservationId);
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        rr.setCancelledQuantity(0);

        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setReservationRoomId(rr.getId());
        assignment.setRoomId(UUID.randomUUID());
        assignment.setAssignedFrom(LocalDate.of(2026, 11, 10));
        assignment.setAssignedUntil(LocalDate.of(2026, 11, 12));
        assignment.setStatus(RoomAssignmentStatus.ASSIGNED);

        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(assignment));

        // Extending checkout to Nov 15 while assignment still ends Nov 12
        ReservationUpdateRequest request = new ReservationUpdateRequest();
        request.setCheckOutDate(LocalDate.of(2026, 11, 15));

        assertThatThrownBy(() -> reservationService.updateReservation(reservationId, request))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("does not cover the complete new stay period (2026-11-10 to 2026-11-15)");

        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("updateReservation moving check-in earlier rejects when existing assignment starts after new check-in date")
    void updateReservation_movingCheckInEarlier_rejectsWhenAssignmentStartsAfterNewCheckInDate() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(bookingSourceRepository.findById(bookingSourceId)).thenReturn(Optional.of(directSource));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setReservationId(reservationId);
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        rr.setCancelledQuantity(0);

        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setReservationRoomId(rr.getId());
        assignment.setRoomId(UUID.randomUUID());
        assignment.setAssignedFrom(LocalDate.of(2026, 11, 10));
        assignment.setAssignedUntil(LocalDate.of(2026, 11, 12));
        assignment.setStatus(RoomAssignmentStatus.ASSIGNED);

        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(assignment));

        // Moving check-in earlier to Nov 08 while assignment starts on Nov 10
        ReservationUpdateRequest request = new ReservationUpdateRequest();
        request.setCheckInDate(LocalDate.of(2026, 11, 8));

        assertThatThrownBy(() -> reservationService.updateReservation(reservationId, request))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("does not cover the complete new stay period (2026-11-08 to 2026-11-12)");

        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("updateReservation shrinking dates rejects when existing assignment extends beyond new dates")
    void updateReservation_shrinkingDates_rejectsWhenAssignmentExtendsBeyondNewDates() {
        reservation.setCheckInDate(LocalDate.of(2026, 11, 10));
        reservation.setCheckOutDate(LocalDate.of(2026, 11, 15));

        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(bookingSourceRepository.findById(bookingSourceId)).thenReturn(Optional.of(directSource));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setReservationId(reservationId);
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        rr.setCancelledQuantity(0);

        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setReservationRoomId(rr.getId());
        assignment.setRoomId(UUID.randomUUID());
        assignment.setAssignedFrom(LocalDate.of(2026, 11, 10));
        assignment.setAssignedUntil(LocalDate.of(2026, 11, 15));
        assignment.setStatus(RoomAssignmentStatus.ASSIGNED);

        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(assignment));

        // Shrinking checkout to Nov 13 while assignment is for Nov 10 to Nov 15
        ReservationUpdateRequest request = new ReservationUpdateRequest();
        request.setCheckOutDate(LocalDate.of(2026, 11, 13));

        assertThatThrownBy(() -> reservationService.updateReservation(reservationId, request))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("does not cover the complete new stay period (2026-11-10 to 2026-11-13)");

        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("updateReservation date change succeeds when no active physical assignments exist and inventory is available")
    void updateReservation_dateChange_succeedsWithNoActivePhysicalAssignments() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(bookingSourceRepository.findById(bookingSourceId)).thenReturn(Optional.of(directSource));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setReservationId(reservationId);
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        rr.setCancelledQuantity(0);

        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));
        // No physical assignments exist
        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of());
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(roomType));

        when(roomRepository.countByPropertyIdAndRoomTypeIdAndActiveTrueAndStatusNot(
                propertyId, roomTypeId, RoomStatus.OUT_OF_SERVICE)).thenReturn(3L);
        when(reservationRoomRepository.sumReservedQuantityForOverlappingDatesExcludingReservation(
                eq(propertyId), eq(roomTypeId), eq(LocalDate.of(2026, 11, 10)), eq(LocalDate.of(2026, 11, 14)),
                any(), eq(reservationId))).thenReturn(1L);

        ReservationUpdateRequest request = new ReservationUpdateRequest();
        request.setCheckOutDate(LocalDate.of(2026, 11, 14));

        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(i -> i.getArgument(0));
        ReservationResponse mockResponse = new ReservationResponse();
        mockResponse.setId(reservationId);
        when(reservationMapper.toResponse(any())).thenReturn(mockResponse);

        ReservationResponse response = reservationService.updateReservation(reservationId, request);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(reservationId);
        verify(reservationMapper).updateEntity(reservation, request);
        verify(reservationRepository).saveAndFlush(reservation);
    }

    @Test
    @DisplayName("updateReservation date change rejects when room-type inventory is insufficient for changed dates")
    void updateReservation_dateChange_rejectsWhenInventoryIsInsufficientOnNewDates() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(bookingSourceRepository.findById(bookingSourceId)).thenReturn(Optional.of(directSource));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setReservationId(reservationId);
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(2);
        rr.setCancelledQuantity(0);

        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));
        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of());
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(roomType));

        // Total active non-out-of-service rooms = 3
        when(roomRepository.countByPropertyIdAndRoomTypeIdAndActiveTrueAndStatusNot(
                propertyId, roomTypeId, RoomStatus.OUT_OF_SERVICE)).thenReturn(3L);

        // Other reservations already reserved 2 rooms on the new dates Nov 10 - Nov 15
        when(reservationRoomRepository.sumReservedQuantityForOverlappingDatesExcludingReservation(
                eq(propertyId), eq(roomTypeId), eq(LocalDate.of(2026, 11, 10)), eq(LocalDate.of(2026, 11, 15)),
                any(), eq(reservationId))).thenReturn(2L);

        // Available = 3 - 2 = 1. But rr requires 2.
        ReservationUpdateRequest request = new ReservationUpdateRequest();
        request.setCheckOutDate(LocalDate.of(2026, 11, 15));

        assertThatThrownBy(() -> reservationService.updateReservation(reservationId, request))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Insufficient availability for room type 'Deluxe Room' for the requested dates");

        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("updateReservation date change succeeds when inventory is available and existing assignments cover complete new stay")
    void updateReservation_dateChange_succeedsWhenInventoryIsAvailableAndAssignmentsValid() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(bookingSourceRepository.findById(bookingSourceId)).thenReturn(Optional.of(directSource));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setReservationId(reservationId);
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        rr.setCancelledQuantity(0);

        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setReservationRoomId(rr.getId());
        assignment.setRoomId(UUID.randomUUID());
        assignment.setAssignedFrom(LocalDate.of(2026, 11, 10));
        assignment.setAssignedUntil(LocalDate.of(2026, 11, 14));
        assignment.setStatus(RoomAssignmentStatus.ASSIGNED);

        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(assignment));
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(roomType));

        // Total rooms = 3, other reserved = 1 -> Available = 2 (>= 1 required)
        when(roomRepository.countByPropertyIdAndRoomTypeIdAndActiveTrueAndStatusNot(
                propertyId, roomTypeId, RoomStatus.OUT_OF_SERVICE)).thenReturn(3L);
        when(reservationRoomRepository.sumReservedQuantityForOverlappingDatesExcludingReservation(
                eq(propertyId), eq(roomTypeId), eq(LocalDate.of(2026, 11, 10)), eq(LocalDate.of(2026, 11, 14)),
                any(), eq(reservationId))).thenReturn(1L);

        ReservationUpdateRequest request = new ReservationUpdateRequest();
        request.setCheckOutDate(LocalDate.of(2026, 11, 14));

        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(i -> i.getArgument(0));
        ReservationResponse mockResponse = new ReservationResponse();
        mockResponse.setId(reservationId);
        when(reservationMapper.toResponse(any())).thenReturn(mockResponse);

        ReservationResponse response = reservationService.updateReservation(reservationId, request);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(reservationId);
        verify(reservationMapper).updateEntity(reservation, request);
        verify(reservationRepository).saveAndFlush(reservation);
    }

    @Test
    @DisplayName("updateReservation date change ignores CANCELLED ReservationRooms when validating inventory and assignments")
    void updateReservation_dateChange_ignoresCancelledReservationRooms_whenValidatingInventoryAndAssignments() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(bookingSourceRepository.findById(bookingSourceId)).thenReturn(Optional.of(directSource));

        // 1. CANCELLED reservation room with remaining quantity = 2 and an active assignment ending on the old checkout date
        ReservationRoom cancelledRr = new ReservationRoom();
        cancelledRr.setId(UUID.randomUUID());
        cancelledRr.setReservationId(reservationId);
        cancelledRr.setRoomTypeId(roomTypeId);
        cancelledRr.setQuantity(2);
        cancelledRr.setCancelledQuantity(0);
        cancelledRr.setStatus(ReservationRoomStatus.CANCELLED);

        RoomAssignment cancelledAssignment = new RoomAssignment();
        cancelledAssignment.setId(UUID.randomUUID());
        cancelledAssignment.setReservationRoomId(cancelledRr.getId());
        cancelledAssignment.setRoomId(UUID.randomUUID());
        cancelledAssignment.setAssignedFrom(LocalDate.of(2026, 11, 10));
        cancelledAssignment.setAssignedUntil(LocalDate.of(2026, 11, 12)); // Old checkout date
        cancelledAssignment.setStatus(RoomAssignmentStatus.ASSIGNED);

        // 2. PARTIALLY_CANCELLED reservation room with quantity = 2, cancelled = 1 (active = 1) and assignment covering new stay
        ReservationRoom partiallyCancelledRr = new ReservationRoom();
        partiallyCancelledRr.setId(UUID.randomUUID());
        partiallyCancelledRr.setReservationId(reservationId);
        partiallyCancelledRr.setRoomTypeId(roomTypeId);
        partiallyCancelledRr.setQuantity(2);
        partiallyCancelledRr.setCancelledQuantity(1);
        partiallyCancelledRr.setStatus(ReservationRoomStatus.PARTIALLY_CANCELLED);

        RoomAssignment activeAssignment = new RoomAssignment();
        activeAssignment.setId(UUID.randomUUID());
        activeAssignment.setReservationRoomId(partiallyCancelledRr.getId());
        activeAssignment.setRoomId(UUID.randomUUID());
        activeAssignment.setAssignedFrom(LocalDate.of(2026, 11, 10));
        activeAssignment.setAssignedUntil(LocalDate.of(2026, 11, 15)); // New checkout date
        activeAssignment.setStatus(RoomAssignmentStatus.ASSIGNED);

        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId))
                .thenReturn(List.of(cancelledRr, partiallyCancelledRr));
        lenient().when(roomAssignmentRepository.findByReservationRoomId(cancelledRr.getId()))
                .thenReturn(List.of(cancelledAssignment));
        when(roomAssignmentRepository.findByReservationRoomId(partiallyCancelledRr.getId()))
                .thenReturn(List.of(activeAssignment));

        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(roomType));

        // Capacity: 2 total rooms, 1 booked by others -> 1 available
        when(roomRepository.countByPropertyIdAndRoomTypeIdAndActiveTrueAndStatusNot(
                propertyId, roomTypeId, RoomStatus.OUT_OF_SERVICE)).thenReturn(2L);
        when(reservationRoomRepository.sumReservedQuantityForOverlappingDatesExcludingReservation(
                eq(propertyId), eq(roomTypeId), eq(LocalDate.of(2026, 11, 10)), eq(LocalDate.of(2026, 11, 15)),
                any(), eq(reservationId))).thenReturn(1L);

        // Request extension to Nov 15
        ReservationUpdateRequest request = new ReservationUpdateRequest();
        request.setCheckOutDate(LocalDate.of(2026, 11, 15));

        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(i -> i.getArgument(0));
        ReservationResponse mockResponse = new ReservationResponse();
        mockResponse.setId(reservationId);
        when(reservationMapper.toResponse(any())).thenReturn(mockResponse);

        ReservationResponse response = reservationService.updateReservation(reservationId, request);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(reservationId);
        verify(reservationMapper).updateEntity(reservation, request);
        verify(reservationRepository).saveAndFlush(reservation);
        verify(roomAssignmentRepository, never()).findByReservationRoomId(cancelledRr.getId());
    }

    @Test
    @DisplayName("updateReservation preserves reservation room quantities and pricing")
    void updateReservation_preservesReservationRoomQuantitiesAndPricing() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(bookingSourceRepository.findById(bookingSourceId)).thenReturn(Optional.of(directSource));

        ReservationUpdateRequest request = new ReservationUpdateRequest();
        request.setAdults(3);
        request.setSpecialRequests("Quiet room please");

        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenReturn(reservation);
        when(reservationMapper.toResponse(any())).thenReturn(new ReservationResponse());

        reservationService.updateReservation(reservationId, request);

        verify(reservationMapper).updateEntity(reservation, request);
        verify(reservationRepository).saveAndFlush(reservation);
        // Reservation rooms are never modified by updateReservation
        verify(reservationRoomRepository, never()).save(any());
        verify(reservationRoomRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("cancelReservation preserves cancellation and availability-release behavior")
    void cancelReservation_preservesCancellationAndAvailabilityReleaseBehavior() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(bookingSourceRepository.findById(bookingSourceId)).thenReturn(Optional.of(directSource));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setReservationId(reservationId);
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(2);
        rr.setCancelledQuantity(0);
        rr.setUnitPrice(new BigDecimal("2500.00"));
        rr.setStatus(ReservationRoomStatus.CONFIRMED);

        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setReservationRoomId(rr.getId());
        assignment.setRoomId(UUID.randomUUID());
        assignment.setStatus(RoomAssignmentStatus.ASSIGNED);

        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(assignment));
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(i -> i.getArgument(0));
        when(reservationMapper.toResponse(any())).thenReturn(new ReservationResponse());

        reservationService.cancelReservation(reservationId);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(rr.getStatus()).isEqualTo(ReservationRoomStatus.CANCELLED);
        assertThat(rr.getCancelledQuantity()).isEqualTo(2);
        assertThat(assignment.getStatus()).isEqualTo(RoomAssignmentStatus.RELEASED);

        verify(reservationRoomRepository).saveAll(List.of(rr));
        verify(roomAssignmentRepository).saveAll(List.of(assignment));
        verify(reservationRepository).saveAndFlush(reservation);
    }

    // =========================================================================
    // Step 3.4.3.6B: checkInReservation Unit Tests
    // =========================================================================

    @Test
    @DisplayName("checkInReservation rejects if reservation is already CHECKED_IN")
    void checkInReservation_rejectsIfAlreadyCheckedIn() {
        reservation.setStatus(ReservationStatus.CHECKED_IN);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationService.checkInReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Reservation is already checked in");
    }

    @Test
    @DisplayName("checkInReservation rejects if reservation is PENDING")
    void checkInReservation_rejectsIfPending() {
        reservation.setStatus(ReservationStatus.PENDING);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationService.checkInReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot check in reservation from status: PENDING");
    }

    @Test
    @DisplayName("checkInReservation rejects if reservation is CANCELLED")
    void checkInReservation_rejectsIfCancelled() {
        reservation.setStatus(ReservationStatus.CANCELLED);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationService.checkInReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot check in reservation from status: CANCELLED");
    }

    @Test
    @DisplayName("checkInReservation rejects if reservation is CHECKED_OUT")
    void checkInReservation_rejectsIfCheckedOut() {
        reservation.setStatus(ReservationStatus.CHECKED_OUT);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationService.checkInReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot check in reservation from status: CHECKED_OUT");
    }

    @Test
    @DisplayName("checkInReservation rejects if reservation is NO_SHOW")
    void checkInReservation_rejectsIfNoShow() {
        reservation.setStatus(ReservationStatus.NO_SHOW);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationService.checkInReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot check in reservation from status: NO_SHOW");
    }

    @Test
    @DisplayName("checkInReservation rejects if property timezone is invalid")
    void checkInReservation_rejectsIfInvalidTimezone() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of());

        Property prop = new Property();
        prop.setId(propertyId);
        prop.setTimezone("Invalid/Timezone");
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(prop));

        assertThatThrownBy(() -> reservationService.checkInReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("has an invalid timezone");
    }

    @Test
    @DisplayName("checkInReservation rejects early arrival before check-in date")
    void checkInReservation_rejectsEarlyArrival() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of());

        Property prop = new Property();
        prop.setId(propertyId);
        prop.setTimezone("Asia/Kolkata");
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(prop));

        // Fixed clock on Nov 09 (checkin is Nov 10)
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-09T05:00:00Z"), ZoneId.of("Asia/Kolkata"));
        when(clock.withZone(any(ZoneId.class))).thenReturn(fixedClock);

        assertThatThrownBy(() -> reservationService.checkInReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot check in before reservation check-in date");
    }

    @Test
    @DisplayName("checkInReservation rejects check-in on or after check-out date")
    void checkInReservation_rejectsCheckOutDateOrLater() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of());

        Property prop = new Property();
        prop.setId(propertyId);
        prop.setTimezone("Asia/Kolkata");
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(prop));

        // Fixed clock on Nov 12 (checkout is Nov 12)
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-12T05:00:00Z"), ZoneId.of("Asia/Kolkata"));
        when(clock.withZone(any(ZoneId.class))).thenReturn(fixedClock);

        assertThatThrownBy(() -> reservationService.checkInReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot check in on or after reservation check-out date");
    }

    @Test
    @DisplayName("checkInReservation rejects if reservation has no active rooms")
    void checkInReservation_rejectsIfNoActiveRooms() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        ReservationRoom cancelledRoom = new ReservationRoom();
        cancelledRoom.setId(UUID.randomUUID());
        cancelledRoom.setQuantity(1);
        cancelledRoom.setCancelledQuantity(1);
        cancelledRoom.setStatus(ReservationRoomStatus.CANCELLED);
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(cancelledRoom));

        Property prop = new Property();
        prop.setId(propertyId);
        prop.setTimezone("Asia/Kolkata");
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(prop));

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-10T05:00:00Z"), ZoneId.of("Asia/Kolkata"));
        when(clock.withZone(any(ZoneId.class))).thenReturn(fixedClock);

        assertThatThrownBy(() -> reservationService.checkInReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Reservation has no active rooms");
    }

    @Test
    @DisplayName("checkInReservation rejects if room assignment coverage is incomplete")
    void checkInReservation_rejectsIncompleteRoomAssignment() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        rr.setCancelledQuantity(0);
        rr.setStatus(ReservationRoomStatus.CONFIRMED);
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        Property prop = new Property();
        prop.setId(propertyId);
        prop.setTimezone("Asia/Kolkata");
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(prop));

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-10T05:00:00Z"), ZoneId.of("Asia/Kolkata"));
        when(clock.withZone(any(ZoneId.class))).thenReturn(fixedClock);

        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of());
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(roomType));

        assertThatThrownBy(() -> reservationService.checkInReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Incomplete physical room assignment for room type 'Deluxe Room'");
    }

    @Test
    @DisplayName("checkInReservation rejects if assigned room is out of service")
    void checkInReservation_rejectsAssignedRoomOutOfService() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        rr.setCancelledQuantity(0);
        rr.setStatus(ReservationRoomStatus.CONFIRMED);
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        Property prop = new Property();
        prop.setId(propertyId);
        prop.setTimezone("Asia/Kolkata");
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(prop));

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-10T05:00:00Z"), ZoneId.of("Asia/Kolkata"));
        when(clock.withZone(any(ZoneId.class))).thenReturn(fixedClock);

        UUID roomId = UUID.randomUUID();
        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setReservationRoomId(rr.getId());
        assignment.setRoomId(roomId);
        assignment.setAssignedFrom(LocalDate.of(2026, 11, 10));
        assignment.setAssignedUntil(LocalDate.of(2026, 11, 12));
        assignment.setStatus(RoomAssignmentStatus.ASSIGNED);

        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(assignment));

        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyId);
        room.setRoomTypeId(roomTypeId);
        room.setRoomNumber("101");
        room.setActive(true);
        room.setStatus(RoomStatus.OUT_OF_SERVICE);

        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> reservationService.checkInReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("is out of service and cannot be checked in");
    }

    @Test
    @DisplayName("checkInReservation succeeds with full coverage, sets OCCUPIED and checkout deadline")
    void checkInReservation_succeedsWithFullCoverage() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        rr.setCancelledQuantity(0);
        rr.setStatus(ReservationRoomStatus.CONFIRMED);
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        Property prop = new Property();
        prop.setId(propertyId);
        prop.setTimezone("Asia/Kolkata");
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(prop));

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-10T05:00:00Z"), ZoneId.of("Asia/Kolkata"));
        when(clock.withZone(any(ZoneId.class))).thenReturn(fixedClock);

        UUID roomId = UUID.randomUUID();
        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setReservationRoomId(rr.getId());
        assignment.setRoomId(roomId);
        assignment.setAssignedFrom(LocalDate.of(2026, 11, 10));
        assignment.setAssignedUntil(LocalDate.of(2026, 11, 12));
        assignment.setStatus(RoomAssignmentStatus.ASSIGNED);

        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(assignment));

        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyId);
        room.setRoomTypeId(roomTypeId);
        room.setRoomNumber("101");
        room.setActive(true);
        room.setStatus(RoomStatus.AVAILABLE);

        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(roomRepository.findByIdForUpdate(roomId)).thenReturn(Optional.of(room));
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(i -> i.getArgument(0));
        when(reservationMapper.toResponse(any())).thenReturn(new ReservationResponse());

        reservationService.checkInReservation(reservationId);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
        assertThat(reservation.isCheckoutDue()).isFalse();
        assertThat(reservation.getCheckoutDueAt()).isEqualTo(
                LocalDate.of(2026, 11, 12).atTime(12, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant()
        );
        assertThat(room.getStatus()).isEqualTo(RoomStatus.OCCUPIED);

        verify(roomRepository).saveAllAndFlush(List.of(room));
        verify(reservationRepository).saveAndFlush(reservation);
    }

    @Test
    @DisplayName("checkInReservation rejects split-stay assignments across different physical rooms")
    void checkInReservation_rejectsSplitStayAssignments() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        Property prop = new Property();
        prop.setId(propertyId);
        prop.setTimezone("Asia/Kolkata");
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(prop));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-10T05:00:00Z"), ZoneId.of("Asia/Kolkata"));
        when(clock.withZone(any(ZoneId.class))).thenReturn(fixedClock);

        // Split stay: Room 101 for night 1, Room 102 for night 2
        UUID roomId1 = UUID.randomUUID();
        UUID roomId2 = UUID.randomUUID();

        RoomAssignment ra1 = new RoomAssignment();
        ra1.setId(UUID.randomUUID());
        ra1.setReservationRoomId(rr.getId());
        ra1.setRoomId(roomId1);
        ra1.setAssignedFrom(LocalDate.of(2026, 11, 10));
        ra1.setAssignedUntil(LocalDate.of(2026, 11, 11));
        ra1.setStatus(RoomAssignmentStatus.ASSIGNED);

        RoomAssignment ra2 = new RoomAssignment();
        ra2.setId(UUID.randomUUID());
        ra2.setReservationRoomId(rr.getId());
        ra2.setRoomId(roomId2);
        ra2.setAssignedFrom(LocalDate.of(2026, 11, 11));
        ra2.setAssignedUntil(LocalDate.of(2026, 11, 12));
        ra2.setStatus(RoomAssignmentStatus.ASSIGNED);

        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(ra1, ra2));

        assertThatThrownBy(() -> reservationService.checkInReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Split-stay reservations (different physical rooms across stay dates) are not supported");

        verify(roomRepository, never()).saveAllAndFlush(any());
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("checkInReservation rejects partial-date assignment that does not cover full stay")
    void checkInReservation_rejectsPartialDateAssignment() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        Property prop = new Property();
        prop.setId(propertyId);
        prop.setTimezone("Asia/Kolkata");
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(prop));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-10T05:00:00Z"), ZoneId.of("Asia/Kolkata"));
        when(clock.withZone(any(ZoneId.class))).thenReturn(fixedClock);

        UUID roomId = UUID.randomUUID();
        // Partial assignment: covers 2026-11-10 to 2026-11-11, stay is until 2026-11-12
        RoomAssignment ra = new RoomAssignment();
        ra.setId(UUID.randomUUID());
        ra.setReservationRoomId(rr.getId());
        ra.setRoomId(roomId);
        ra.setAssignedFrom(LocalDate.of(2026, 11, 10));
        ra.setAssignedUntil(LocalDate.of(2026, 11, 11));
        ra.setStatus(RoomAssignmentStatus.ASSIGNED);

        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(ra));

        assertThatThrownBy(() -> reservationService.checkInReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Split-stay reservations (different physical rooms across stay dates) are not supported");

        verify(roomRepository, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("checkInReservation rejects multiple partial assignments for the same physical room")
    void checkInReservation_rejectsMultiplePartialAssignmentsSameRoom() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        Property prop = new Property();
        prop.setId(propertyId);
        prop.setTimezone("Asia/Kolkata");
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(prop));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-10T05:00:00Z"), ZoneId.of("Asia/Kolkata"));
        when(clock.withZone(any(ZoneId.class))).thenReturn(fixedClock);

        UUID roomId = UUID.randomUUID();
        // Multiple partial assignments for the SAME room
        RoomAssignment ra1 = new RoomAssignment();
        ra1.setId(UUID.randomUUID());
        ra1.setReservationRoomId(rr.getId());
        ra1.setRoomId(roomId);
        ra1.setAssignedFrom(LocalDate.of(2026, 11, 10));
        ra1.setAssignedUntil(LocalDate.of(2026, 11, 11));
        ra1.setStatus(RoomAssignmentStatus.ASSIGNED);

        RoomAssignment ra2 = new RoomAssignment();
        ra2.setId(UUID.randomUUID());
        ra2.setReservationRoomId(rr.getId());
        ra2.setRoomId(roomId);
        ra2.setAssignedFrom(LocalDate.of(2026, 11, 11));
        ra2.setAssignedUntil(LocalDate.of(2026, 11, 12));
        ra2.setStatus(RoomAssignmentStatus.ASSIGNED);

        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(ra1, ra2));

        assertThatThrownBy(() -> reservationService.checkInReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Split-stay reservations (different physical rooms across stay dates) are not supported");

        verify(roomRepository, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("checkInReservation succeeds for simultaneous multi-room reservation")
    void checkInReservation_succeedsForSimultaneousMultiRoom() {
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        Property prop = new Property();
        prop.setId(propertyId);
        prop.setTimezone("Asia/Kolkata");
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(prop));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(2);
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-10T05:00:00Z"), ZoneId.of("Asia/Kolkata"));
        when(clock.withZone(any(ZoneId.class))).thenReturn(fixedClock);

        UUID roomId1 = UUID.randomUUID();
        UUID roomId2 = UUID.randomUUID();

        RoomAssignment ra1 = new RoomAssignment();
        ra1.setId(UUID.randomUUID());
        ra1.setReservationRoomId(rr.getId());
        ra1.setRoomId(roomId1);
        ra1.setAssignedFrom(LocalDate.of(2026, 11, 10));
        ra1.setAssignedUntil(LocalDate.of(2026, 11, 12));
        ra1.setStatus(RoomAssignmentStatus.ASSIGNED);

        RoomAssignment ra2 = new RoomAssignment();
        ra2.setId(UUID.randomUUID());
        ra2.setReservationRoomId(rr.getId());
        ra2.setRoomId(roomId2);
        ra2.setAssignedFrom(LocalDate.of(2026, 11, 10));
        ra2.setAssignedUntil(LocalDate.of(2026, 11, 12));
        ra2.setStatus(RoomAssignmentStatus.ASSIGNED);

        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(ra1, ra2));

        Room room1 = new Room();
        room1.setId(roomId1);
        room1.setPropertyId(propertyId);
        room1.setRoomTypeId(roomTypeId);
        room1.setRoomNumber("101");
        room1.setActive(true);
        room1.setStatus(RoomStatus.AVAILABLE);

        Room room2 = new Room();
        room2.setId(roomId2);
        room2.setPropertyId(propertyId);
        room2.setRoomTypeId(roomTypeId);
        room2.setRoomNumber("102");
        room2.setActive(true);
        room2.setStatus(RoomStatus.AVAILABLE);

        when(roomRepository.findById(roomId1)).thenReturn(Optional.of(room1));
        when(roomRepository.findById(roomId2)).thenReturn(Optional.of(room2));
        when(roomRepository.findByIdForUpdate(roomId1)).thenReturn(Optional.of(room1));
        when(roomRepository.findByIdForUpdate(roomId2)).thenReturn(Optional.of(room2));
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(i -> i.getArgument(0));
        when(reservationMapper.toResponse(any())).thenReturn(new ReservationResponse());

        reservationService.checkInReservation(reservationId);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
        assertThat(room1.getStatus()).isEqualTo(RoomStatus.OCCUPIED);
        assertThat(room2.getStatus()).isEqualTo(RoomStatus.OCCUPIED);

        verify(roomRepository).saveAllAndFlush(any());
        verify(reservationRepository).saveAndFlush(reservation);
    }

    @Test
    @DisplayName("checkOutReservation succeeds for single-room reservation")
    void checkOutReservation_succeedsForSingleRoom() {
        reservation.setStatus(ReservationStatus.CHECKED_IN);
        reservation.setCheckoutDue(true);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        UUID roomId = UUID.randomUUID();
        RoomAssignment ra = new RoomAssignment();
        ra.setId(UUID.randomUUID());
        ra.setReservationRoomId(rr.getId());
        ra.setRoomId(roomId);
        ra.setAssignedFrom(LocalDate.of(2026, 11, 10));
        ra.setAssignedUntil(LocalDate.of(2026, 11, 12));
        ra.setStatus(RoomAssignmentStatus.ASSIGNED);
        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(ra));

        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyId);
        room.setRoomTypeId(roomTypeId);
        room.setRoomNumber("101");
        room.setActive(true);
        room.setStatus(RoomStatus.OCCUPIED);
        room.setHousekeepingStatus(HousekeepingStatus.CLEAN);
        when(roomRepository.findByIdForUpdate(roomId)).thenReturn(Optional.of(room));

        when(roomAssignmentRepository.hasOtherActiveCheckedInReservation(roomId, reservationId)).thenReturn(false);

        UUID staffUserId = UUID.randomUUID();
        AuthenticatedStaffPrincipal principal = new AuthenticatedStaffPrincipal(staffUserId, "staff@test.com", "Staff", "User");
        when(staffAuthorizationService.getCurrentPrincipal()).thenReturn(Optional.of(principal));

        Instant checkoutInstant = Instant.parse("2026-11-12T11:00:00Z");
        when(clock.instant()).thenReturn(checkoutInstant);

        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(i -> i.getArgument(0));
        when(reservationMapper.toResponse(any())).thenReturn(new ReservationResponse());

        ReservationResponse response = reservationService.checkOutReservation(reservationId);

        assertThat(response).isNotNull();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_OUT);
        assertThat(reservation.isCheckoutDue()).isFalse();
        assertThat(reservation.getActualCheckedOutAt()).isEqualTo(checkoutInstant);
        assertThat(reservation.getCheckedOutByStaffUserId()).isEqualTo(staffUserId);

        assertThat(room.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(room.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);
        assertThat(ra.getStatus()).isEqualTo(RoomAssignmentStatus.RELEASED);

        verify(roomAssignmentRepository).saveAllAndFlush(List.of(ra));
        verify(roomRepository).saveAllAndFlush(List.of(room));
        verify(reservationRepository).saveAndFlush(reservation);
    }

    @Test
    @DisplayName("checkOutReservation succeeds for simultaneous multi-room reservation")
    void checkOutReservation_succeedsForSimultaneousMultiRoom() {
        reservation.setStatus(ReservationStatus.CHECKED_IN);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(2);
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        UUID roomId1 = UUID.randomUUID();
        UUID roomId2 = UUID.randomUUID();

        RoomAssignment ra1 = new RoomAssignment();
        ra1.setId(UUID.randomUUID());
        ra1.setReservationRoomId(rr.getId());
        ra1.setRoomId(roomId1);
        ra1.setAssignedFrom(LocalDate.of(2026, 11, 10));
        ra1.setAssignedUntil(LocalDate.of(2026, 11, 12));
        ra1.setStatus(RoomAssignmentStatus.ASSIGNED);

        RoomAssignment ra2 = new RoomAssignment();
        ra2.setId(UUID.randomUUID());
        ra2.setReservationRoomId(rr.getId());
        ra2.setRoomId(roomId2);
        ra2.setAssignedFrom(LocalDate.of(2026, 11, 10));
        ra2.setAssignedUntil(LocalDate.of(2026, 11, 12));
        ra2.setStatus(RoomAssignmentStatus.ASSIGNED);

        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(ra1, ra2));

        Room room1 = new Room();
        room1.setId(roomId1);
        room1.setPropertyId(propertyId);
        room1.setRoomTypeId(roomTypeId);
        room1.setRoomNumber("101");
        room1.setActive(true);
        room1.setStatus(RoomStatus.OCCUPIED);
        room1.setHousekeepingStatus(HousekeepingStatus.CLEAN);

        Room room2 = new Room();
        room2.setId(roomId2);
        room2.setPropertyId(propertyId);
        room2.setRoomTypeId(roomTypeId);
        room2.setRoomNumber("102");
        room2.setActive(true);
        room2.setStatus(RoomStatus.OCCUPIED);
        room2.setHousekeepingStatus(HousekeepingStatus.CLEAN);

        when(roomRepository.findByIdForUpdate(roomId1)).thenReturn(Optional.of(room1));
        when(roomRepository.findByIdForUpdate(roomId2)).thenReturn(Optional.of(room2));

        when(roomAssignmentRepository.hasOtherActiveCheckedInReservation(roomId1, reservationId)).thenReturn(false);
        when(roomAssignmentRepository.hasOtherActiveCheckedInReservation(roomId2, reservationId)).thenReturn(false);

        UUID staffUserId = UUID.randomUUID();
        AuthenticatedStaffPrincipal principal = new AuthenticatedStaffPrincipal(staffUserId, "staff@test.com", "Staff", "User");
        when(staffAuthorizationService.getCurrentPrincipal()).thenReturn(Optional.of(principal));
        when(clock.instant()).thenReturn(Instant.parse("2026-11-12T11:00:00Z"));

        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(i -> i.getArgument(0));
        when(reservationMapper.toResponse(any())).thenReturn(new ReservationResponse());

        reservationService.checkOutReservation(reservationId);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_OUT);
        assertThat(room1.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(room1.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);
        assertThat(room2.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(room2.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);
        assertThat(ra1.getStatus()).isEqualTo(RoomAssignmentStatus.RELEASED);
        assertThat(ra2.getStatus()).isEqualTo(RoomAssignmentStatus.RELEASED);

        verify(roomAssignmentRepository).saveAllAndFlush(any());
        verify(roomRepository).saveAllAndFlush(any());
        verify(reservationRepository).saveAndFlush(reservation);
    }

    @Test
    @DisplayName("checkOutReservation rejects repeated checkout")
    void checkOutReservation_rejectsRepeatedCheckout() {
        reservation.setStatus(ReservationStatus.CHECKED_OUT);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationService.checkOutReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Reservation is already checked out");

        verify(roomRepository, never()).saveAllAndFlush(any());
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("checkOutReservation rejects non CHECKED_IN reservation")
    void checkOutReservation_rejectsNonCheckedInReservation() {
        reservation.setStatus(ReservationStatus.CONFIRMED);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationService.checkOutReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Only CHECKED_IN reservations can be checked out");

        verify(roomRepository, never()).saveAllAndFlush(any());
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("checkOutReservation preserves OUT_OF_SERVICE status and marks DIRTY")
    void checkOutReservation_preservesOutOfServiceStatusAndMarksDirty() {
        reservation.setStatus(ReservationStatus.CHECKED_IN);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        UUID roomId = UUID.randomUUID();
        RoomAssignment ra = new RoomAssignment();
        ra.setId(UUID.randomUUID());
        ra.setReservationRoomId(rr.getId());
        ra.setRoomId(roomId);
        ra.setAssignedFrom(LocalDate.of(2026, 11, 10));
        ra.setAssignedUntil(LocalDate.of(2026, 11, 12));
        ra.setStatus(RoomAssignmentStatus.ASSIGNED);
        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(ra));

        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyId);
        room.setRoomTypeId(roomTypeId);
        room.setRoomNumber("101");
        room.setActive(true);
        room.setStatus(RoomStatus.OUT_OF_SERVICE);
        room.setHousekeepingStatus(HousekeepingStatus.CLEAN);
        when(roomRepository.findByIdForUpdate(roomId)).thenReturn(Optional.of(room));

        when(roomAssignmentRepository.hasOtherActiveCheckedInReservation(roomId, reservationId)).thenReturn(false);

        UUID staffUserId = UUID.randomUUID();
        AuthenticatedStaffPrincipal principal = new AuthenticatedStaffPrincipal(staffUserId, "staff@test.com", "Staff", "User");
        when(staffAuthorizationService.getCurrentPrincipal()).thenReturn(Optional.of(principal));
        when(clock.instant()).thenReturn(Instant.parse("2026-11-12T11:00:00Z"));

        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(i -> i.getArgument(0));
        when(reservationMapper.toResponse(any())).thenReturn(new ReservationResponse());

        reservationService.checkOutReservation(reservationId);

        assertThat(room.getStatus()).isEqualTo(RoomStatus.OUT_OF_SERVICE);
        assertThat(room.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);
        assertThat(ra.getStatus()).isEqualTo(RoomAssignmentStatus.RELEASED);
    }

    @Test
    @DisplayName("checkOutReservation preserves inactive room flag")
    void checkOutReservation_preservesInactiveRoomFlag() {
        reservation.setStatus(ReservationStatus.CHECKED_IN);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        UUID roomId = UUID.randomUUID();
        RoomAssignment ra = new RoomAssignment();
        ra.setId(UUID.randomUUID());
        ra.setReservationRoomId(rr.getId());
        ra.setRoomId(roomId);
        ra.setAssignedFrom(LocalDate.of(2026, 11, 10));
        ra.setAssignedUntil(LocalDate.of(2026, 11, 12));
        ra.setStatus(RoomAssignmentStatus.ASSIGNED);
        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(ra));

        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyId);
        room.setRoomTypeId(roomTypeId);
        room.setRoomNumber("101");
        room.setActive(false);
        room.setStatus(RoomStatus.OCCUPIED);
        room.setHousekeepingStatus(HousekeepingStatus.CLEAN);
        when(roomRepository.findByIdForUpdate(roomId)).thenReturn(Optional.of(room));

        when(roomAssignmentRepository.hasOtherActiveCheckedInReservation(roomId, reservationId)).thenReturn(false);

        UUID staffUserId = UUID.randomUUID();
        AuthenticatedStaffPrincipal principal = new AuthenticatedStaffPrincipal(staffUserId, "staff@test.com", "Staff", "User");
        when(staffAuthorizationService.getCurrentPrincipal()).thenReturn(Optional.of(principal));
        when(clock.instant()).thenReturn(Instant.parse("2026-11-12T11:00:00Z"));

        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(i -> i.getArgument(0));
        when(reservationMapper.toResponse(any())).thenReturn(new ReservationResponse());

        reservationService.checkOutReservation(reservationId);

        assertThat(room.isActive()).isFalse();
        assertThat(room.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(room.getHousekeepingStatus()).isEqualTo(HousekeepingStatus.DIRTY);
    }

    @Test
    @DisplayName("checkOutReservation rejects unexpected AVAILABLE room status")
    void checkOutReservation_rejectsUnexpectedAvailableRoomStatus() {
        reservation.setStatus(ReservationStatus.CHECKED_IN);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        UUID roomId = UUID.randomUUID();
        RoomAssignment ra = new RoomAssignment();
        ra.setId(UUID.randomUUID());
        ra.setReservationRoomId(rr.getId());
        ra.setRoomId(roomId);
        ra.setAssignedFrom(LocalDate.of(2026, 11, 10));
        ra.setAssignedUntil(LocalDate.of(2026, 11, 12));
        ra.setStatus(RoomAssignmentStatus.ASSIGNED);
        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(ra));

        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyId);
        room.setRoomTypeId(roomTypeId);
        room.setRoomNumber("101");
        room.setActive(true);
        room.setStatus(RoomStatus.AVAILABLE);
        when(roomRepository.findByIdForUpdate(roomId)).thenReturn(Optional.of(room));

        when(roomAssignmentRepository.hasOtherActiveCheckedInReservation(roomId, reservationId)).thenReturn(false);

        assertThatThrownBy(() -> reservationService.checkOutReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("is unexpectedly in AVAILABLE status");

        verify(roomAssignmentRepository, never()).saveAllAndFlush(any());
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("checkOutReservation rejects split-stay or incomplete assignments")
    void checkOutReservation_rejectsSplitStayAssignments() {
        reservation.setStatus(ReservationStatus.CHECKED_IN);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        RoomAssignment raPartial = new RoomAssignment();
        raPartial.setId(UUID.randomUUID());
        raPartial.setReservationRoomId(rr.getId());
        raPartial.setRoomId(UUID.randomUUID());
        raPartial.setAssignedFrom(LocalDate.of(2026, 11, 10));
        raPartial.setAssignedUntil(LocalDate.of(2026, 11, 11)); // only partial date!
        raPartial.setStatus(RoomAssignmentStatus.ASSIGNED);
        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(raPartial));

        assertThatThrownBy(() -> reservationService.checkOutReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot check out: Split-stay or partial-date room assignments detected");

        verify(roomRepository, never()).saveAllAndFlush(any());
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("checkOutReservation rejects when physical room is occupied by another checked-in reservation")
    void checkOutReservation_rejectsWhenRoomOccupiedByOtherGuest() {
        reservation.setStatus(ReservationStatus.CHECKED_IN);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        UUID roomId = UUID.randomUUID();
        RoomAssignment ra = new RoomAssignment();
        ra.setId(UUID.randomUUID());
        ra.setReservationRoomId(rr.getId());
        ra.setRoomId(roomId);
        ra.setAssignedFrom(LocalDate.of(2026, 11, 10));
        ra.setAssignedUntil(LocalDate.of(2026, 11, 12));
        ra.setStatus(RoomAssignmentStatus.ASSIGNED);
        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(ra));

        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyId);
        room.setRoomTypeId(roomTypeId);
        room.setRoomNumber("101");
        room.setActive(true);
        room.setStatus(RoomStatus.OCCUPIED);
        when(roomRepository.findByIdForUpdate(roomId)).thenReturn(Optional.of(room));

        when(roomAssignmentRepository.hasOtherActiveCheckedInReservation(roomId, reservationId)).thenReturn(true);

        assertThatThrownBy(() -> reservationService.checkOutReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("is currently referenced by another active checked-in reservation");

        verify(roomAssignmentRepository, never()).saveAllAndFlush(any());
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("checkOutReservation fails closed when staff principal missing under enforcement")
    void checkOutReservation_failsClosedWhenStaffPrincipalMissingUnderEnforcement() {
        reservation.setStatus(ReservationStatus.CHECKED_IN);
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        when(reservationRoomRepository.findByReservationIdForUpdate(reservationId)).thenReturn(List.of(rr));

        UUID roomId = UUID.randomUUID();
        RoomAssignment ra = new RoomAssignment();
        ra.setId(UUID.randomUUID());
        ra.setReservationRoomId(rr.getId());
        ra.setRoomId(roomId);
        ra.setAssignedFrom(LocalDate.of(2026, 11, 10));
        ra.setAssignedUntil(LocalDate.of(2026, 11, 12));
        ra.setStatus(RoomAssignmentStatus.ASSIGNED);
        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(ra));

        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyId);
        room.setRoomTypeId(roomTypeId);
        room.setRoomNumber("101");
        room.setActive(true);
        room.setStatus(RoomStatus.OCCUPIED);
        when(roomRepository.findByIdForUpdate(roomId)).thenReturn(Optional.of(room));

        when(roomAssignmentRepository.hasOtherActiveCheckedInReservation(roomId, reservationId)).thenReturn(false);

        when(staffAuthorizationService.getCurrentPrincipal()).thenReturn(Optional.empty());
        when(securityEnforcementProperties.isEnabled()).thenReturn(true);

        assertThatThrownBy(() -> reservationService.checkOutReservation(reservationId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Authenticated staff principal is required");

        verify(roomAssignmentRepository, never()).saveAllAndFlush(any());
        verify(reservationRepository, never()).saveAndFlush(any());
    }
}

