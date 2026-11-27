package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.Room;
import com.homestay.pms_backend.enums.RoomStatus;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Room r WHERE r.id = :id")
    Optional<Room> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT r.roomTypeId FROM Room r WHERE r.id = :id")
    Optional<UUID> findRoomTypeIdById(@Param("id") UUID id);

    Optional<Room> findByPropertyIdAndRoomNumber(
            UUID propertyId,
            String roomNumber
    );

    boolean existsByPropertyIdAndRoomNumber(
            UUID propertyId,
            String roomNumber
    );

    List<Room> findByPropertyId(UUID propertyId);

    List<Room> findByRoomTypeId(UUID roomTypeId);

    long countByPropertyIdAndRoomTypeIdAndActiveTrueAndStatusNot(
            UUID propertyId,
            UUID roomTypeId,
            RoomStatus status
    );
}