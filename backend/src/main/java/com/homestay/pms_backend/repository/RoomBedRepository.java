package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.RoomBed;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface RoomBedRepository extends JpaRepository<RoomBed, UUID> {

    List<RoomBed> findByRoomId(UUID roomId);

    List<RoomBed> findByBedTypeId(UUID bedTypeId);

    boolean existsByRoomIdAndBedTypeIdAndExtraBed(
            UUID roomId,
            UUID bedTypeId,
            boolean extraBed
    );

    @Query("""
            SELECT COALESCE(SUM(rb.quantity), 0)
            FROM RoomBed rb
            WHERE rb.roomId = :roomId
              AND rb.extraBed = true
            """)
    int sumExtraBedQuantityByRoomId(UUID roomId);
}