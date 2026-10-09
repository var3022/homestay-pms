package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.Reservation;
import com.homestay.pms_backend.enums.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    Optional<Reservation> findByBookingSourceIdAndExternalBookingId(
            UUID bookingSourceId,
            String externalBookingId
    );

    @Query("""
            SELECT r
            FROM Reservation r
            WHERE r.propertyId = :propertyId
              AND r.status IN :statuses
              AND r.checkInDate < :checkOutDate
              AND r.checkOutDate > :checkInDate
            """)
    List<Reservation> findOverlappingReservations(
            @Param("propertyId") UUID propertyId,
            @Param("checkInDate") LocalDate checkInDate,
            @Param("checkOutDate") LocalDate checkOutDate,
            @Param("statuses") List<ReservationStatus> statuses
    );
}