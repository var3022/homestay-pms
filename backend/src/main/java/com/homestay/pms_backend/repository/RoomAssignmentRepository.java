package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.RoomAssignment;
import com.homestay.pms_backend.enums.RoomAssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomAssignmentRepository
        extends JpaRepository<RoomAssignment, UUID> {

    @Query("SELECT ra.status FROM RoomAssignment ra WHERE ra.id = :id")
    Optional<RoomAssignmentStatus> findStatusById(@Param("id") UUID id);

    @Query("SELECT ra.reservationRoomId FROM RoomAssignment ra WHERE ra.id = :id")
    Optional<UUID> findReservationRoomIdById(@Param("id") UUID id);

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

    @Query("""
        SELECT CASE WHEN count(ra) > 0 THEN true ELSE false END
        FROM RoomAssignment ra
        JOIN ReservationRoom rr ON ra.reservationRoomId = rr.id
        JOIN Reservation res ON rr.reservationId = res.id
        WHERE ra.roomId = :roomId
          AND ra.status = com.homestay.pms_backend.enums.RoomAssignmentStatus.ASSIGNED
          AND res.status = com.homestay.pms_backend.enums.ReservationStatus.CHECKED_IN
    """)
    boolean hasActiveCheckedInReservation(@Param("roomId") UUID roomId);

    @Query("""
        SELECT CASE WHEN count(ra) > 0 THEN true ELSE false END
        FROM RoomAssignment ra
        JOIN ReservationRoom rr ON ra.reservationRoomId = rr.id
        JOIN Reservation res ON rr.reservationId = res.id
        WHERE ra.roomId = :roomId
          AND ra.status = com.homestay.pms_backend.enums.RoomAssignmentStatus.ASSIGNED
          AND res.status = com.homestay.pms_backend.enums.ReservationStatus.CHECKED_IN
          AND res.id <> :excludeReservationId
    """)
    boolean hasOtherActiveCheckedInReservation(
            @Param("roomId") UUID roomId,
            @Param("excludeReservationId") UUID excludeReservationId
    );
}
