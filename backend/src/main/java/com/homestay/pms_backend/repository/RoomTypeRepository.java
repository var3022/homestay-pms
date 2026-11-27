package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.RoomType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomTypeRepository extends JpaRepository<RoomType, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rt FROM RoomType rt WHERE rt.id = :id")
    Optional<RoomType> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rt FROM RoomType rt WHERE rt.id IN :ids ORDER BY rt.id")
    List<RoomType> findAllByIdInForUpdate(@Param("ids") Collection<UUID> ids);

    Optional<RoomType> findByPropertyIdAndCode(UUID propertyId, String code);

    boolean existsByPropertyIdAndCode(UUID propertyId, String code);

    List<RoomType> findByPropertyId(UUID propertyId);
}