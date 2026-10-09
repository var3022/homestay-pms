package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.RoomType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomTypeRepository extends JpaRepository<RoomType, UUID> {

    Optional<RoomType> findByPropertyIdAndCode(UUID propertyId, String code);

    boolean existsByPropertyIdAndCode(UUID propertyId, String code);

    List<RoomType> findByPropertyId(UUID propertyId);
}