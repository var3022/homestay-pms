package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

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
}