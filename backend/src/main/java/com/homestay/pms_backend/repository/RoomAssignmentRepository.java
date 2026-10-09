package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.RoomAssignment;
import com.homestay.pms_backend.enums.RoomAssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface RoomAssignmentRepository
        extends JpaRepository<RoomAssignment, UUID> {

    List<RoomAssignment> findByReservationRoomId(UUID reservationRoomId);

    List<RoomAssignment> findByRoomIdAndStatus(
            UUID roomId,
            RoomAssignmentStatus status
    );

    List<RoomAssignment> findByRoomIdAndStatusAndAssignedFromLessThanAndAssignedUntilGreaterThan(
            UUID roomId,
            RoomAssignmentStatus status,
            LocalDate assignedUntil,
            LocalDate assignedFrom
    );

    List<RoomAssignment> findByReservationRoomIdAndStatusAndAssignedFromLessThanAndAssignedUntilGreaterThan(
            UUID reservationRoomId,
            RoomAssignmentStatus status,
            LocalDate assignedUntil,
            LocalDate assignedFrom
    );
}
