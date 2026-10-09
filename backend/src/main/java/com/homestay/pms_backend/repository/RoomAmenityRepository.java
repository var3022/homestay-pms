package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.RoomAmenity;
import com.homestay.pms_backend.entity.RoomAmenityId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RoomAmenityRepository
        extends JpaRepository<RoomAmenity, RoomAmenityId> {

    List<RoomAmenity> findByIdRoomId(UUID roomId);

    List<RoomAmenity> findByIdAmenityId(UUID amenityId);

    boolean existsByIdRoomIdAndIdAmenityId(
            UUID roomId,
            UUID amenityId
    );
}