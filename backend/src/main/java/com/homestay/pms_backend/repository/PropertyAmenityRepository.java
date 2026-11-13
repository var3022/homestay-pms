package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.PropertyAmenity;
import com.homestay.pms_backend.entity.PropertyAmenityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PropertyAmenityRepository
        extends JpaRepository<PropertyAmenity, PropertyAmenityId> {

    List<PropertyAmenity> findByIdPropertyId(UUID propertyId);

    List<PropertyAmenity> findByIdAmenityId(UUID amenityId);

    @Query("""
            SELECT pa FROM PropertyAmenity pa
            WHERE pa.id.amenityId = :amenityId
              AND pa.id.propertyId IN :propertyIds
            """)
    List<PropertyAmenity> findByAmenityIdAndPropertyIdIn(
            @Param("amenityId") UUID amenityId,
            @Param("propertyIds") Collection<UUID> propertyIds
    );

    boolean existsByIdPropertyIdAndIdAmenityId(
            UUID propertyId,
            UUID amenityId
    );
}
