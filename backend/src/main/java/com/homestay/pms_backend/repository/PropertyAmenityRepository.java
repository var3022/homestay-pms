package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.PropertyAmenity;
import com.homestay.pms_backend.entity.PropertyAmenityId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PropertyAmenityRepository
        extends JpaRepository<PropertyAmenity, PropertyAmenityId> {

    List<PropertyAmenity> findByIdPropertyId(UUID propertyId);

    List<PropertyAmenity> findByIdAmenityId(UUID amenityId);

    boolean existsByIdPropertyIdAndIdAmenityId(
            UUID propertyId,
            UUID amenityId
    );
}