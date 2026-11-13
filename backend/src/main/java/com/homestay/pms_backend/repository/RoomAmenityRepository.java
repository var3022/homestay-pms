package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.RoomAmenity;
import com.homestay.pms_backend.entity.RoomAmenityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RoomAmenityRepository
        extends JpaRepository<RoomAmenity, RoomAmenityId> {

    List<RoomAmenity> findByIdRoomId(UUID roomId);

    List<RoomAmenity> findByIdAmenityId(UUID amenityId);

    @Query("""
            SELECT ra FROM RoomAmenity ra, Room r
            WHERE ra.id.roomId = r.id
              AND ra.id.amenityId = :amenityId
              AND r.propertyId IN :propertyIds
            """)
    List<RoomAmenity> findByAmenityIdAndPropertyIdIn(
            @Param("amenityId") UUID amenityId,
            @Param("propertyIds") Collection<UUID> propertyIds
    );

    boolean existsByIdRoomIdAndIdAmenityId(
            UUID roomId,
            UUID amenityId
    );
}
