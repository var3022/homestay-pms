package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.ReservationRoomAddRequest;
import com.homestay.pms_backend.dto.response.ReservationRoomResponse;
import com.homestay.pms_backend.entity.Reservation;
import com.homestay.pms_backend.entity.ReservationRoom;
import com.homestay.pms_backend.entity.RoomType;
import com.homestay.pms_backend.enums.ReservationRoomStatus;
import com.homestay.pms_backend.enums.ReservationStatus;
import com.homestay.pms_backend.enums.RoomStatus;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.mapper.ReservationRoomMapper;
import com.homestay.pms_backend.repository.ReservationRepository;
import com.homestay.pms_backend.repository.ReservationRoomRepository;
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
class ReservationRoomServiceImplTest {

    @Mock
    private ReservationRoomRepository reservationRoomRepository;
    @Mock
    private ReservationRoomMapper reservationRoomMapper;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private RoomTypeRepository roomTypeRepository;
    @Mock
    private RoomRepository roomRepository;

    @InjectMocks
    private ReservationRoomServiceImpl reservationRoomService;

    private UUID reservationId;
    private UUID propertyId;
    private UUID roomTypeId;
    private Reservation reservation;
    private RoomType roomType;
    private ReservationRoomAddRequest addRequest;

    @BeforeEach
    void setUp() {
        reservationId = UUID.randomUUID();
        propertyId = UUID.randomUUID();
        roomTypeId = UUID.randomUUID();

        reservation = new Reservation();
        reservation.setId(reservationId);
        reservation.setPropertyId(propertyId);
        reservation.setCheckInDate(LocalDate.of(2026, 11, 10));
        reservation.setCheckOutDate(LocalDate.of(2026, 11, 12));
        reservation.setStatus(ReservationStatus.CONFIRMED);

        roomType = new RoomType();
        roomType.setId(roomTypeId);
        roomType.setPropertyId(propertyId);
        roomType.setName("Deluxe Room");
        roomType.setActive(true);

        addRequest = new ReservationRoomAddRequest();
        addRequest.setReservationId(reservationId);
        addRequest.setRoomTypeId(roomTypeId);
        addRequest.setQuantity(1);
        addRequest.setUnitPrice(new BigDecimal("2500.00"));
    }

    @Test
    @DisplayName("addReservationRoom throws ResourceNotFoundException when reservation does not exist")
    void addReservationRoom_rejectsNonExistentReservation() {
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationRoomService.addReservationRoom(addRequest))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Reservation not found with id");

        verify(reservationRoomRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("addReservationRoom rejects adding room to CANCELLED reservation")
    void addReservationRoom_rejectsCancelledReservation() {
        reservation.setStatus(ReservationStatus.CANCELLED);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationRoomService.addReservationRoom(addRequest))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot add rooms to a cancelled reservation");

        verify(reservationRoomRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("addReservationRoom rejects adding room to CHECKED_IN reservation")
    void addReservationRoom_rejectsCheckedInReservation() {
        reservation.setStatus(ReservationStatus.CHECKED_IN);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationRoomService.addReservationRoom(addRequest))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot add rooms to a checked-in reservation");

        verify(reservationRoomRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("addReservationRoom rejects adding room to CHECKED_OUT reservation")
    void addReservationRoom_rejectsCheckedOutReservation() {
        reservation.setStatus(ReservationStatus.CHECKED_OUT);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationRoomService.addReservationRoom(addRequest))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot add rooms to a checked-out reservation");

        verify(reservationRoomRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("addReservationRoom rejects adding room to NO_SHOW reservation")
    void addReservationRoom_rejectsNoShowReservation() {
        reservation.setStatus(ReservationStatus.NO_SHOW);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationRoomService.addReservationRoom(addRequest))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot add rooms to a no-show reservation");

        verify(reservationRoomRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("addReservationRoom rejects room type from different property")
    void addReservationRoom_rejectsRoomTypeFromDifferentProperty() {
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        roomType.setPropertyId(UUID.randomUUID()); // different property
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(roomType));

        assertThatThrownBy(() -> reservationRoomService.addReservationRoom(addRequest))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("The selected room type does not belong to the reservation property");

        verify(reservationRoomRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("addReservationRoom rejects inactive room type")
    void addReservationRoom_rejectsInactiveRoomType() {
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        roomType.setActive(false);
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(roomType));

        assertThatThrownBy(() -> reservationRoomService.addReservationRoom(addRequest))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("The selected room type is inactive");

        verify(reservationRoomRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("addReservationRoom rejects when requested quantity exceeds available inventory")
    void addReservationRoom_rejectsWhenQuantityExceedsAvailableInventory() {
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(roomType));

        // 3 active non-OOS rooms
        when(roomRepository.countByPropertyIdAndRoomTypeIdAndActiveTrueAndStatusNot(
                propertyId, roomTypeId, RoomStatus.OUT_OF_SERVICE)).thenReturn(3L);

        // 3 rooms already reserved on these stay dates
        when(reservationRoomRepository.sumReservedQuantityForOverlappingDates(
                eq(propertyId), eq(roomTypeId), eq(reservation.getCheckInDate()), eq(reservation.getCheckOutDate()), any()))
                .thenReturn(3L);

        // available = 3 - 3 = 0, requested = 1
        assertThatThrownBy(() -> reservationRoomService.addReservationRoom(addRequest))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Insufficient availability for room type 'Deluxe Room'");

        verify(reservationRoomRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("addReservationRoom succeeds when inventory is available")
    void addReservationRoom_succeedsWhenInventoryAvailable() {
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(roomType));

        when(roomRepository.countByPropertyIdAndRoomTypeIdAndActiveTrueAndStatusNot(
                propertyId, roomTypeId, RoomStatus.OUT_OF_SERVICE)).thenReturn(3L);
        when(reservationRoomRepository.sumReservedQuantityForOverlappingDates(
                eq(propertyId), eq(roomTypeId), eq(reservation.getCheckInDate()), eq(reservation.getCheckOutDate()), any()))
                .thenReturn(1L);

        ReservationRoom roomToSave = new ReservationRoom();
        roomToSave.setId(UUID.randomUUID());
        roomToSave.setReservationId(reservationId);
        roomToSave.setRoomTypeId(roomTypeId);
        roomToSave.setQuantity(1);
        roomToSave.setUnitPrice(new BigDecimal("2500.00"));
        roomToSave.setStatus(ReservationRoomStatus.CONFIRMED);

        when(reservationRoomMapper.toEntity(addRequest)).thenReturn(roomToSave);
        when(reservationRoomRepository.saveAndFlush(roomToSave)).thenReturn(roomToSave);

        ReservationRoomResponse mockResponse = new ReservationRoomResponse();
        mockResponse.setId(roomToSave.getId());
        when(reservationRoomMapper.toResponse(roomToSave)).thenReturn(mockResponse);

        ReservationRoomResponse result = reservationRoomService.addReservationRoom(addRequest);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(roomToSave.getId());
        verify(reservationRoomRepository).saveAndFlush(roomToSave);
    }
}

