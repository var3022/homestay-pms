package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.ReservationRoom;
import com.homestay.pms_backend.enums.ReservationStatus;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRoomRepository
        extends JpaRepository<ReservationRoom, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rr FROM ReservationRoom rr WHERE rr.id = :id")
    Optional<ReservationRoom> findByIdForUpdate(@Param("id") UUID id);

    List<ReservationRoom> findByReservationId(UUID reservationId);

    List<ReservationRoom> findByRoomTypeId(UUID roomTypeId);

   @Query("""
            SELECT COALESCE(SUM(rr.quantity - rr.cancelledQuantity), 0)
            FROM ReservationRoom rr
            JOIN Reservation r ON r.id = rr.reservationId
            WHERE r.propertyId = :propertyId
              AND rr.roomTypeId = :roomTypeId
              AND r.status IN :statuses
              AND rr.status <> com.homestay.pms_backend.enums.ReservationRoomStatus.CANCELLED
              AND r.checkInDate < :checkOutDate
              AND r.checkOutDate > :checkInDate
            """)
    Long sumReservedQuantityForOverlappingDates(
            @Param("propertyId") UUID propertyId,
            @Param("roomTypeId") UUID roomTypeId,
            @Param("checkInDate") LocalDate checkInDate,
            @Param("checkOutDate") LocalDate checkOutDate,
            @Param("statuses") List<ReservationStatus> statuses
    );

    @Query("""
            SELECT COALESCE(SUM(rr.quantity - rr.cancelledQuantity), 0)
            FROM ReservationRoom rr
            JOIN Reservation r ON r.id = rr.reservationId
            WHERE r.propertyId = :propertyId
              AND rr.roomTypeId = :roomTypeId
              AND r.status IN :statuses
              AND rr.status <> com.homestay.pms_backend.enums.ReservationRoomStatus.CANCELLED
              AND r.checkInDate < :checkOutDate
              AND r.checkOutDate > :checkInDate
              AND r.id <> :excludeReservationId
            """)
    Long sumReservedQuantityForOverlappingDatesExcludingReservation(
            @Param("propertyId") UUID propertyId,
            @Param("roomTypeId") UUID roomTypeId,
            @Param("checkInDate") LocalDate checkInDate,
            @Param("checkOutDate") LocalDate checkOutDate,
            @Param("statuses") List<ReservationStatus> statuses,
            @Param("excludeReservationId") UUID excludeReservationId
    );
}

