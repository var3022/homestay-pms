package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.entity.GuestPropertyAssociation;
import com.homestay.pms_backend.enums.GuestAssociationSource;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.repository.GuestPropertyAssociationRepository;
import com.homestay.pms_backend.repository.GuestRepository;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.security.authorization.GuestAuthorizationGuard;
import com.homestay.pms_backend.security.authorization.StaffAuthorizationService;
import com.homestay.pms_backend.security.authorization.StaffOperation;
import com.homestay.pms_backend.service.GuestPropertyAssociationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of GuestPropertyAssociationService.
 *
 * Provides transactional lifecycle operations for guest-property associations:
 * 1. Guarantees idempotent and race-free insertions via database-level conflict handling.
 * 2. Enforces database referential integrity with foreign key checks on guest and property entities.
 * 3. Prevents arbitrary existing-guest adoption across properties without explicit authorization.
 * 4. Preserves historical association source and first_associated_at metadata.
 */
@Service
@Transactional
public class GuestPropertyAssociationServiceImpl implements GuestPropertyAssociationService {

    private static final Logger log = LoggerFactory.getLogger(GuestPropertyAssociationServiceImpl.class);

    private final GuestPropertyAssociationRepository associationRepository;
    private final GuestRepository guestRepository;
    private final PropertyRepository propertyRepository;
    private final StaffAuthorizationService authorizationService;
    private final GuestAuthorizationGuard authorizationGuard;

    @Autowired
    public GuestPropertyAssociationServiceImpl(
            GuestPropertyAssociationRepository associationRepository,
            GuestRepository guestRepository,
            PropertyRepository propertyRepository,
            StaffAuthorizationService authorizationService,
            GuestAuthorizationGuard authorizationGuard
    ) {
        this.associationRepository = Objects.requireNonNull(associationRepository, "GuestPropertyAssociationRepository must not be null");
        this.guestRepository = Objects.requireNonNull(guestRepository, "GuestRepository must not be null");
        this.propertyRepository = Objects.requireNonNull(propertyRepository, "PropertyRepository must not be null");
        this.authorizationService = Objects.requireNonNull(authorizationService, "StaffAuthorizationService must not be null");
        this.authorizationGuard = Objects.requireNonNull(authorizationGuard, "GuestAuthorizationGuard must not be null");
    }

    @Override
    public GuestPropertyAssociation createRegistrationAssociation(UUID guestId, UUID propertyId) {
        if (guestId == null) {
            throw new BusinessValidationException("guestId must not be null");
        }
        if (propertyId == null) {
            throw new BusinessValidationException("propertyId must not be null");
        }
        if (!guestRepository.existsById(guestId)) {
            throw new ResourceNotFoundException("Guest not found with id: " + guestId);
        }
        if (!propertyRepository.existsById(propertyId)) {
            throw new ResourceNotFoundException("Property not found with id: " + propertyId);
        }

        associationRepository.insertAssociationIdempotent(guestId, propertyId, GuestAssociationSource.REGISTRATION.name());

        return associationRepository.findByIdGuestIdAndIdPropertyId(guestId, propertyId)
                .orElseThrow(() -> new IllegalStateException("Failed to load created guest property association"));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isGuestAssociatedWithProperty(UUID guestId, UUID propertyId) {
        if (guestId == null || propertyId == null) {
            return false;
        }
        return associationRepository.existsByIdGuestIdAndIdPropertyId(guestId, propertyId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> getAssociatedPropertyIds(UUID guestId) {
        if (guestId == null) {
            return List.of();
        }
        return associationRepository.findPropertyIdsByGuestId(guestId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> getAssociatedGuestIds(UUID propertyId) {
        if (propertyId == null) {
            return List.of();
        }
        return associationRepository.findGuestIdsByPropertyId(propertyId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GuestPropertyAssociation> getAssociation(UUID guestId, UUID propertyId) {
        if (guestId == null || propertyId == null) {
            return Optional.empty();
        }
        return associationRepository.findByIdGuestIdAndIdPropertyId(guestId, propertyId);
    }

    @Override
    public void recordReservationActivity(UUID guestId, UUID propertyId) {
        if (guestId == null || propertyId == null) {
            throw new BusinessValidationException("guestId and propertyId must not be null");
        }

        validateExistingGuestUsableAtProperty(guestId, propertyId);

        int updated = associationRepository.touchLastActivity(guestId, propertyId);
        if (updated == 0) {
            if (!authorizationGuard.isEnforcementEnabled()) {
                log.info("Development mode: skipping reservation activity touch for unassociated guest [{}] at property [{}]",
                        guestId, propertyId);
                return;
            }
            log.warn("Recording reservation activity failed: No existing association found for guest [{}] at property [{}]",
                    guestId, propertyId);
            throw new ResourceNotFoundException("Guest is not associated with property: " + propertyId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void validateExistingGuestUsableAtProperty(UUID guestId, UUID propertyId) {
        if (guestId == null) {
            throw new ResourceNotFoundException("Guest not found with id: null");
        }
        if (propertyId == null) {
            throw new ResourceNotFoundException("Property not found with id: null");
        }

        if (!guestRepository.existsById(guestId)) {
            throw new ResourceNotFoundException("Guest not found with id: " + guestId);
        }
        if (!propertyRepository.existsById(propertyId)) {
            throw new ResourceNotFoundException("Property not found with id: " + propertyId);
        }

        if (!authorizationGuard.isEnforcementEnabled() || authorizationService.isGlobalSuperAdmin()) {
            return;
        }

        if (!associationRepository.existsByIdGuestIdAndIdPropertyId(guestId, propertyId)) {
            log.warn("Guest adoption rejected: Guest [{}] is not associated with property [{}]. Returning uniform 404.",
                    guestId, propertyId);
            throw new ResourceNotFoundException("Guest not found with id: " + guestId);
        }
    }

    @Override
    public GuestPropertyAssociation attachExistingGuestWithAuthorization(UUID guestId, UUID propertyId, GuestAssociationSource source) {
        if (guestId == null) {
            throw new BusinessValidationException("guestId must not be null");
        }
        if (propertyId == null) {
            throw new BusinessValidationException("propertyId must not be null");
        }
        if (source == null) {
            throw new BusinessValidationException("association source must not be null");
        }

        if (!guestRepository.existsById(guestId)) {
            throw new ResourceNotFoundException("Guest not found with id: " + guestId);
        }
        if (!propertyRepository.existsById(propertyId)) {
            throw new ResourceNotFoundException("Property not found with id: " + propertyId);
        }

        if (authorizationGuard.isEnforcementEnabled() && !authorizationService.isGlobalSuperAdmin()) {
            if (!authorizationService.hasPropertyAccess(propertyId, StaffOperation.GUEST_MANAGE)) {
                log.warn("Access denied: Caller lacks GUEST_MANAGE on target property [{}]", propertyId);
                throw new AccessDeniedException("Access is denied.");
            }

            List<UUID> existingAssocProps = associationRepository.findPropertyIdsByGuestId(guestId);
            boolean authorizedForExistingGuest = existingAssocProps.stream()
                    .anyMatch(existingPropId -> authorizationService.hasPropertyAccess(existingPropId, StaffOperation.GUEST_MANAGE));

            if (!authorizedForExistingGuest) {
                log.warn("Cross-property adoption rejected: Caller lacks GUEST_MANAGE on any existing associated property for guest [{}]",
                        guestId);
                throw new AccessDeniedException("Explicit cross-property authorization required to associate existing guest.");
            }
        }

        associationRepository.insertAssociationIdempotent(guestId, propertyId, source.name());

        return associationRepository.findByIdGuestIdAndIdPropertyId(guestId, propertyId)
                .orElseThrow(() -> new IllegalStateException("Failed to load attached guest property association"));
    }
}
