package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.Amenity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AmenityRepository extends JpaRepository<Amenity, UUID> {

    Optional<Amenity> findByCode(String code);

    boolean existsByCode(String code);
}