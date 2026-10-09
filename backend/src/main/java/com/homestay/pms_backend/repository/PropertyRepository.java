package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.Property;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PropertyRepository extends JpaRepository<Property, UUID> {

    Optional<Property> findByCode(String code);

    boolean existsByCode(String code);
}