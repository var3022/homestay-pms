package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.Guest;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GuestRepository extends JpaRepository<Guest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM Guest g WHERE g.id = :id")
    Optional<Guest> findByIdForUpdate(@Param("id") UUID id);

    List<Guest> findByNameIgnoreCase(String name);

    List<Guest> findByPhone(String phone);

    List<Guest> findByEmailIgnoreCase(String email);

    @Query("""
        SELECT DISTINCT g FROM Guest g
        JOIN GuestPropertyAssociation a ON a.id.guestId = g.id
        WHERE a.id.propertyId = :propertyId
        ORDER BY g.name ASC, g.id ASC
    """)
    List<Guest> findByPropertyId(@Param("propertyId") UUID propertyId, Pageable pageable);

    @Query("""
        SELECT DISTINCT g FROM Guest g
        JOIN GuestPropertyAssociation a ON a.id.guestId = g.id
        WHERE a.id.propertyId IN :propertyIds
        ORDER BY g.name ASC, g.id ASC
    """)
    List<Guest> findByPropertyIdIn(@Param("propertyIds") Collection<UUID> propertyIds, Pageable pageable);

    @Query("""
        SELECT g FROM Guest g
        ORDER BY g.name ASC, g.id ASC
    """)
    List<Guest> findAllPaged(Pageable pageable);
}