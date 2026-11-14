package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.GuestPropertyAssociation;
import com.homestay.pms_backend.entity.GuestPropertyAssociationId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GuestPropertyAssociationRepository extends JpaRepository<GuestPropertyAssociation, GuestPropertyAssociationId> {

    List<GuestPropertyAssociation> findByIdPropertyId(UUID propertyId);

    List<GuestPropertyAssociation> findByIdGuestId(UUID guestId);

    boolean existsByIdGuestIdAndIdPropertyId(UUID guestId, UUID propertyId);

    Optional<GuestPropertyAssociation> findByIdGuestIdAndIdPropertyId(UUID guestId, UUID propertyId);

    @Query("SELECT a.id.guestId FROM GuestPropertyAssociation a WHERE a.id.propertyId = :propertyId")
    List<UUID> findGuestIdsByPropertyId(@Param("propertyId") UUID propertyId);

    @Query("SELECT a.id.guestId FROM GuestPropertyAssociation a WHERE a.id.propertyId IN :propertyIds")
    List<UUID> findGuestIdsByPropertyIdIn(@Param("propertyIds") Collection<UUID> propertyIds);

    @Query("SELECT a.id.propertyId FROM GuestPropertyAssociation a WHERE a.id.guestId = :guestId")
    List<UUID> findPropertyIdsByGuestId(@Param("guestId") UUID guestId);

    long countByIdPropertyId(UUID propertyId);

    long countByIdGuestId(UUID guestId);

    void deleteByIdGuestIdAndIdPropertyId(UUID guestId, UUID propertyId);
}
