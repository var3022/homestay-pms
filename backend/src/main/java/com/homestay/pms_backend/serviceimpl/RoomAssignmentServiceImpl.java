package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.RoomAssignmentCreateRequest;
import com.homestay.pms_backend.dto.response.RoomAssignmentResponse;
import com.homestay.pms_backend.entity.Reservation;
import com.homestay.pms_backend.entity.ReservationRoom;
import com.homestay.pms_backend.entity.Room;
import com.homestay.pms_backend.entity.RoomAssignment;
import com.homestay.pms_backend.enums.RoomAssignmentStatus;
import com.homestay.pms_backend.enums.RoomStatus;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.mapper.RoomAssignmentMapper;
import com.homestay.pms_backend.repository.ReservationRepository;
import com.homestay.pms_backend.repository.ReservationRoomRepository;
import com.homestay.pms_backend.repository.RoomAssignmentRepository;
import com.homestay.pms_backend.repository.RoomRepository;
import com.homestay.pms_backend.service.RoomAssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class RoomAssignmentServiceImpl implements RoomAssignmentService {

    private final RoomAssignmentRepository roomAssignmentRepository;
    private final RoomAssignmentMapper roomAssignmentMapper;
    private final ReservationRoomRepository reservationRoomRepository;
    private final RoomRepository roomRepository;
    private final ReservationRepository reservationRepository;

    @Override
    public RoomAssignmentResponse assignRoom(
            RoomAssignmentCreateRequest request) {

        ReservationRoom reservationRoom =
                reservationRoomRepository.findById(
                        request.getReservationRoomId()
                ).orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Reservation room not found with id: "
                                        + request.getReservationRoomId()
                        ));

        Reservation reservation =
                reservationRepository.findById(
                        reservationRoom.getReservationId()
                ).orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Reservation not found with id: "
                                        + reservationRoom.getReservationId()
                        ));

        Room room =
                roomRepository.findById(request.getRoomId())
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Room not found with id: "
                                                + request.getRoomId()
                                ));

        if (!room.isActive()) {
            throw new BusinessValidationException(
                    "Room is inactive and cannot be assigned"
            );
        }

        if (room.getStatus() != RoomStatus.AVAILABLE) {
            throw new BusinessValidationException(
                    "Room is not available for assignment"
            );
        }

        if (!room.getPropertyId().equals(
                reservation.getPropertyId())) {

            throw new BusinessValidationException(
                    "Room does not belong to the reservation property"
            );
        }

        if (!room.getRoomTypeId().equals(
                reservationRoom.getRoomTypeId())) {

            throw new BusinessValidationException(
                    "Room does not belong to the reservation room type"
            );
        }

        LocalDate assignedFrom = request.getAssignedFrom();
        LocalDate assignedUntil = request.getAssignedUntil();

        if (!assignedUntil.isAfter(assignedFrom)) {
            throw new BusinessValidationException(
                    "Assigned until date must be after assigned from date"
            );
        }

        if (assignedFrom.isBefore(reservation.getCheckInDate())
                || assignedUntil.isAfter(reservation.getCheckOutDate())) {

            throw new BusinessValidationException(
                    "Room assignment dates must be within the reservation dates"
            );
        }

        int activeQuantity =
                reservationRoom.getQuantity()
                        - reservationRoom.getCancelledQuantity();

        if (activeQuantity <= 0) {
            throw new BusinessValidationException(
                    "Cannot assign a room to a fully cancelled reservation room"
            );
        }

        List<RoomAssignment> overlappingReservationAssignments =
                roomAssignmentRepository
                        .findByReservationRoomIdAndStatusAndAssignedFromLessThanAndAssignedUntilGreaterThan(
                                request.getReservationRoomId(),
                                RoomAssignmentStatus.ASSIGNED,
                                assignedUntil,
                                assignedFrom
                        );

        if (overlappingReservationAssignments.size()
                >= activeQuantity) {

            throw new BusinessValidationException(
                    "Reservation room already has the maximum number of room assignments for the requested dates"
            );
        }

        List<RoomAssignment> overlappingRoomAssignments =
                roomAssignmentRepository
                        .findByRoomIdAndStatusAndAssignedFromLessThanAndAssignedUntilGreaterThan(
                                request.getRoomId(),
                                RoomAssignmentStatus.ASSIGNED,
                                assignedUntil,
                                assignedFrom
                        );

        if (!overlappingRoomAssignments.isEmpty()) {
            throw new BusinessValidationException(
                    "Room is already assigned for the requested dates"
            );
        }

        RoomAssignment assignment =
                roomAssignmentMapper.toEntity(request);

        RoomAssignment savedAssignment =
                roomAssignmentRepository.saveAndFlush(assignment);

        return roomAssignmentMapper.toResponse(savedAssignment);
    }

    @Override
    @Transactional(readOnly = true)
    public RoomAssignmentResponse getRoomAssignmentById(UUID id) {

        RoomAssignment assignment =
                roomAssignmentRepository.findById(id)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Room assignment not found with id: "
                                                + id
                                ));

        return roomAssignmentMapper.toResponse(assignment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomAssignmentResponse>
    getAssignmentsByReservationRoomId(UUID reservationRoomId) {

        if (!reservationRoomRepository.existsById(reservationRoomId)) {
            throw new ResourceNotFoundException(
                    "Reservation room not found with id: "
                            + reservationRoomId
            );
        }

        return roomAssignmentRepository
                .findByReservationRoomId(reservationRoomId)
                .stream()
                .map(roomAssignmentMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomAssignmentResponse>
    getAssignmentsByRoomId(UUID roomId) {

        if (!roomRepository.existsById(roomId)) {
            throw new ResourceNotFoundException(
                    "Room not found with id: " + roomId
            );
        }

        return roomAssignmentRepository
                .findByRoomIdAndStatus(
                        roomId,
                        RoomAssignmentStatus.ASSIGNED
                )
                .stream()
                .map(roomAssignmentMapper::toResponse)
                .toList();
    }

    @Override
    public RoomAssignmentResponse releaseRoomAssignment(UUID id) {

        RoomAssignment assignment =
                roomAssignmentRepository.findById(id)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Room assignment not found with id: " + id
                                ));

        if (assignment.getStatus() == RoomAssignmentStatus.RELEASED) {
                throw new BusinessValidationException(
                        "Room assignment is already released"
                );
        }

        assignment.setStatus(RoomAssignmentStatus.RELEASED);

        RoomAssignment savedAssignment =
                roomAssignmentRepository.saveAndFlush(assignment);

        return roomAssignmentMapper.toResponse(savedAssignment);
    }

}
