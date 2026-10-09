package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.BedType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BedTypeRepository extends JpaRepository<BedType, UUID> {

    Optional<BedType> findByCode(String code);

    boolean existsByCode(String code);
}