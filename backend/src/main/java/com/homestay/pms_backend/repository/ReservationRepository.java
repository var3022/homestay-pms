package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.Reservation;
import com.homestay.pms_backend.enums.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") UUID id);

    List<Reservation> findByPropertyId(UUID propertyId);

    List<Reservation> findByPropertyIdIn(Collection<UUID> propertyIds);

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