package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.ReservationUpdateRequest;
import com.homestay.pms_backend.dto.response.ReservationResponse;
import com.homestay.pms_backend.entity.BookingSource;
import com.homestay.pms_backend.entity.Reservation;
import com.homestay.pms_backend.entity.ReservationRoom;
import com.homestay.pms_backend.entity.RoomAssignment;
import com.homestay.pms_backend.entity.RoomType;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

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
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

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
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

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
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

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
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
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
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
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
    @DisplayName("updateReservation date change rejects when existing physical assignment would fall outside new stay dates")
    void updateReservation_dateChange_rejectsWhenAssignmentFallsOutsideNewStayDates() {
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(bookingSourceRepository.findById(bookingSourceId)).thenReturn(Optional.of(directSource));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setReservationId(reservationId);
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        rr.setCancelledQuantity(0);

        when(reservationRoomRepository.findByReservationId(reservationId)).thenReturn(List.of(rr));

        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setReservationRoomId(rr.getId());
        assignment.setRoomId(UUID.randomUUID());
        assignment.setAssignedFrom(LocalDate.of(2026, 11, 10));
        assignment.setAssignedUntil(LocalDate.of(2026, 11, 12));
        assignment.setStatus(RoomAssignmentStatus.ASSIGNED);

        when(roomAssignmentRepository.findByReservationRoomId(rr.getId())).thenReturn(List.of(assignment));

        // Shifting check-in from Nov 10 to Nov 11 would invalidate assignment starting Nov 10
        ReservationUpdateRequest request = new ReservationUpdateRequest();
        request.setCheckInDate(LocalDate.of(2026, 11, 11));

        assertThatThrownBy(() -> reservationService.updateReservation(reservationId, request))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot change reservation dates because existing room assignments would fall outside the new reservation dates");

        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("updateReservation date change rejects when room-type inventory is insufficient for changed dates")
    void updateReservation_dateChange_rejectsWhenInventoryIsInsufficientOnNewDates() {
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(bookingSourceRepository.findById(bookingSourceId)).thenReturn(Optional.of(directSource));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setReservationId(reservationId);
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(2);
        rr.setCancelledQuantity(0);

        when(reservationRoomRepository.findByReservationId(reservationId)).thenReturn(List.of(rr));
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
    @DisplayName("updateReservation date change succeeds when inventory is available and existing assignments remain within new dates")
    void updateReservation_dateChange_succeedsWhenInventoryIsAvailableAndAssignmentsValid() {
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(bookingSourceRepository.findById(bookingSourceId)).thenReturn(Optional.of(directSource));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setReservationId(reservationId);
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(1);
        rr.setCancelledQuantity(0);

        when(reservationRoomRepository.findByReservationId(reservationId)).thenReturn(List.of(rr));

        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setReservationRoomId(rr.getId());
        assignment.setRoomId(UUID.randomUUID());
        assignment.setAssignedFrom(LocalDate.of(2026, 11, 10));
        assignment.setAssignedUntil(LocalDate.of(2026, 11, 12));
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
    @DisplayName("updateReservation preserves reservation room quantities and pricing")
    void updateReservation_preservesReservationRoomQuantitiesAndPricing() {
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
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
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(bookingSourceRepository.findById(bookingSourceId)).thenReturn(Optional.of(directSource));

        ReservationRoom rr = new ReservationRoom();
        rr.setId(UUID.randomUUID());
        rr.setReservationId(reservationId);
        rr.setRoomTypeId(roomTypeId);
        rr.setQuantity(2);
        rr.setCancelledQuantity(0);
        rr.setUnitPrice(new BigDecimal("2500.00"));
        rr.setStatus(ReservationRoomStatus.CONFIRMED);

        when(reservationRoomRepository.findByReservationId(reservationId)).thenReturn(List.of(rr));

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
}

