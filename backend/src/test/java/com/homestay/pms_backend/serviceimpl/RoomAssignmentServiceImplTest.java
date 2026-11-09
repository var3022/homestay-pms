package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.RoomAssignmentCreateRequest;
import com.homestay.pms_backend.dto.response.RoomAssignmentResponse;
import com.homestay.pms_backend.entity.Reservation;
import com.homestay.pms_backend.entity.ReservationRoom;
import com.homestay.pms_backend.entity.Room;
import com.homestay.pms_backend.entity.RoomAssignment;
import com.homestay.pms_backend.enums.ReservationStatus;
import com.homestay.pms_backend.enums.RoomAssignmentStatus;
import com.homestay.pms_backend.enums.RoomStatus;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.mapper.RoomAssignmentMapper;
import com.homestay.pms_backend.repository.ReservationRepository;
import com.homestay.pms_backend.repository.ReservationRoomRepository;
import com.homestay.pms_backend.repository.RoomAssignmentRepository;
import com.homestay.pms_backend.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomAssignmentServiceImplTest {

    @Mock
    private RoomAssignmentRepository roomAssignmentRepository;
    @Mock
    private RoomAssignmentMapper roomAssignmentMapper;
    @Mock
    private ReservationRoomRepository reservationRoomRepository;
    @Mock
    private RoomRepository roomRepository;
    @Mock
    private ReservationRepository reservationRepository;

    @InjectMocks
    private RoomAssignmentServiceImpl roomAssignmentService;

    private UUID reservationId;
    private UUID reservationRoomId;
    private UUID roomId;
    private UUID propertyId;
    private UUID roomTypeId;

    private Reservation reservation;
    private ReservationRoom reservationRoom;
    private Room room;
    private RoomAssignmentCreateRequest assignRequest;

    @BeforeEach
    void setUp() {
        reservationId = UUID.randomUUID();
        reservationRoomId = UUID.randomUUID();
        roomId = UUID.randomUUID();
        propertyId = UUID.randomUUID();
        roomTypeId = UUID.randomUUID();

        reservation = new Reservation();
        reservation.setId(reservationId);
        reservation.setPropertyId(propertyId);
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setCheckInDate(LocalDate.of(2026, 11, 10));
        reservation.setCheckOutDate(LocalDate.of(2026, 11, 12));

        reservationRoom = new ReservationRoom();
        reservationRoom.setId(reservationRoomId);
        reservationRoom.setReservationId(reservationId);
        reservationRoom.setRoomTypeId(roomTypeId);
        reservationRoom.setQuantity(1);
        reservationRoom.setCancelledQuantity(0);

        room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyId);
        room.setRoomTypeId(roomTypeId);
        room.setRoomNumber("101");
        room.setActive(true);
        room.setStatus(RoomStatus.AVAILABLE);

        assignRequest = new RoomAssignmentCreateRequest();
        assignRequest.setReservationRoomId(reservationRoomId);
        assignRequest.setRoomId(roomId);
        assignRequest.setAssignedFrom(LocalDate.of(2026, 11, 10));
        assignRequest.setAssignedUntil(LocalDate.of(2026, 11, 12));
    }

    @Test
    @DisplayName("assignRoom rejects assignments for CANCELLED reservations")
    void assignRoom_rejectsCancelledReservation() {
        reservation.setStatus(ReservationStatus.CANCELLED);
        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(reservationRoom));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> roomAssignmentService.assignRoom(assignRequest))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot assign a room to a cancelled reservation");

        verify(roomAssignmentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("assignRoom rejects assignments for CHECKED_OUT reservations")
    void assignRoom_rejectsCheckedOutReservation() {
        reservation.setStatus(ReservationStatus.CHECKED_OUT);
        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(reservationRoom));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> roomAssignmentService.assignRoom(assignRequest))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot assign a room to a checked-out reservation");

        verify(roomAssignmentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("assignRoom rejects assignments for NO_SHOW reservations")
    void assignRoom_rejectsNoShowReservation() {
        reservation.setStatus(ReservationStatus.NO_SHOW);
        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(reservationRoom));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> roomAssignmentService.assignRoom(assignRequest))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot assign a room to a no-show reservation");

        verify(roomAssignmentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("assignRoom rejects OUT_OF_SERVICE rooms")
    void assignRoom_rejectsOutOfServiceRoom() {
        room.setStatus(RoomStatus.OUT_OF_SERVICE);
        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(reservationRoom));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> roomAssignmentService.assignRoom(assignRequest))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Room is out of service and cannot be assigned");

        verify(roomAssignmentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("assignRoom rejects inactive rooms")
    void assignRoom_rejectsInactiveRoom() {
        room.setActive(false);
        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(reservationRoom));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> roomAssignmentService.assignRoom(assignRequest))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Room is inactive and cannot be assigned");

        verify(roomAssignmentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("assignRoom permits future assignments to rooms currently OCCUPIED, provided room is not out of service and has no overlap")
    void assignRoom_permitsFutureAssignmentWhenRoomIsOccupiedToday() {
        // Room status is OCCUPIED (e.g. guest currently staying)
        room.setStatus(RoomStatus.OCCUPIED);
        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(reservationRoom));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        // No overlapping assignment for the requested future dates Nov 10 - Nov 12
        when(roomAssignmentRepository
                .findByReservationRoomIdAndStatusAndAssignedFromLessThanAndAssignedUntilGreaterThan(
                        reservationRoomId, RoomAssignmentStatus.ASSIGNED, LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 10)))
                .thenReturn(List.of());

        when(roomAssignmentRepository
                .findByRoomIdAndStatusAndAssignedFromLessThanAndAssignedUntilGreaterThan(
                        roomId, RoomAssignmentStatus.ASSIGNED, LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 10)))
                .thenReturn(List.of());

        RoomAssignment assignmentToSave = new RoomAssignment();
        assignmentToSave.setId(UUID.randomUUID());
        assignmentToSave.setStatus(RoomAssignmentStatus.ASSIGNED);

        when(roomAssignmentMapper.toEntity(assignRequest)).thenReturn(assignmentToSave);
        when(roomAssignmentRepository.saveAndFlush(assignmentToSave)).thenReturn(assignmentToSave);

        RoomAssignmentResponse mockResponse = new RoomAssignmentResponse();
        mockResponse.setId(assignmentToSave.getId());
        when(roomAssignmentMapper.toResponse(assignmentToSave)).thenReturn(mockResponse);

        RoomAssignmentResponse response = roomAssignmentService.assignRoom(assignRequest);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(assignmentToSave.getId());
        verify(roomAssignmentRepository).saveAndFlush(assignmentToSave);
    }

    @Test
    @DisplayName("assignRoom rejects when room already has an overlapping ASSIGNED record for the requested dates")
    void assignRoom_rejectsOverlappingAssignment() {
        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(reservationRoom));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        when(roomAssignmentRepository
                .findByReservationRoomIdAndStatusAndAssignedFromLessThanAndAssignedUntilGreaterThan(
                        reservationRoomId, RoomAssignmentStatus.ASSIGNED, LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 10)))
                .thenReturn(List.of());

        // Existing overlapping assignment for room 101
        RoomAssignment existingOverlap = new RoomAssignment();
        existingOverlap.setId(UUID.randomUUID());
        existingOverlap.setRoomId(roomId);
        existingOverlap.setStatus(RoomAssignmentStatus.ASSIGNED);

        when(roomAssignmentRepository
                .findByRoomIdAndStatusAndAssignedFromLessThanAndAssignedUntilGreaterThan(
                        roomId, RoomAssignmentStatus.ASSIGNED, LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 10)))
                .thenReturn(List.of(existingOverlap));

        assertThatThrownBy(() -> roomAssignmentService.assignRoom(assignRequest))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Room is already assigned for the requested dates");

        verify(roomAssignmentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("releaseRoomAssignment rejects releasing an assignment belonging to a CHECKED_IN reservation")
    void releaseRoomAssignment_rejectsCheckedInReservation() {
        UUID assignmentId = UUID.randomUUID();
        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(assignmentId);
        assignment.setReservationRoomId(reservationRoomId);
        assignment.setStatus(RoomAssignmentStatus.ASSIGNED);

        reservation.setStatus(ReservationStatus.CHECKED_IN);

        when(roomAssignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(reservationRoom));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> roomAssignmentService.releaseRoomAssignment(assignmentId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot release room assignment for a checked-in reservation through the generic release endpoint");

        verify(roomAssignmentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("releaseRoomAssignment rejects an assignment that is already RELEASED")
    void releaseRoomAssignment_rejectsAlreadyReleasedAssignment() {
        UUID assignmentId = UUID.randomUUID();
        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(assignmentId);
        assignment.setStatus(RoomAssignmentStatus.RELEASED);

        when(roomAssignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));

        assertThatThrownBy(() -> roomAssignmentService.releaseRoomAssignment(assignmentId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Room assignment is already released");

        verify(roomAssignmentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("releaseRoomAssignment succeeds for PENDING or CONFIRMED reservations before check-in")
    void releaseRoomAssignment_succeedsForPendingOrConfirmedReservation() {
        UUID assignmentId = UUID.randomUUID();
        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(assignmentId);
        assignment.setReservationRoomId(reservationRoomId);
        assignment.setStatus(RoomAssignmentStatus.ASSIGNED);

        reservation.setStatus(ReservationStatus.CONFIRMED);

        when(roomAssignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(reservationRoom));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(roomAssignmentRepository.saveAndFlush(assignment)).thenReturn(assignment);

        RoomAssignmentResponse mockResponse = new RoomAssignmentResponse();
        mockResponse.setId(assignmentId);
        mockResponse.setStatus(RoomAssignmentStatus.RELEASED);
        when(roomAssignmentMapper.toResponse(assignment)).thenReturn(mockResponse);

        RoomAssignmentResponse response = roomAssignmentService.releaseRoomAssignment(assignmentId);

        assertThat(response).isNotNull();
        assertThat(assignment.getStatus()).isEqualTo(RoomAssignmentStatus.RELEASED);
        verify(roomAssignmentRepository).saveAndFlush(assignment);
    }
}

