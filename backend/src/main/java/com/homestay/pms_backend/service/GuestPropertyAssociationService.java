package com.homestay.pms_backend.service;

import com.homestay.pms_backend.entity.GuestPropertyAssociation;
import com.homestay.pms_backend.enums.GuestAssociationSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing transactional guest-property associations.
 *
 * Responsibilities:
 * 1. Establishes atomic guest-property associations during new guest registration.
 * 2. Provides idempotent and concurrency-safe insert operations over composite key (guest_id, property_id).
 * 3. Preserves original association provenance (first_associated_at, association_source) while recording activity.
 * 4. Enforces strict authorization boundaries against arbitrary existing-guest adoption across properties.
 */
public interface GuestPropertyAssociationService {

    /**
     * Creates an initial property association for a newly registered guest.
     * Uses atomic ON CONFLICT DO NOTHING to guarantee idempotency and concurrency safety.
     * Validates that guest and property exist.
     *
     * @param guestId the newly created guest's UUID
     * @param propertyId the registering property's UUID
     * @return the created or existing GuestPropertyAssociation
     */
    GuestPropertyAssociation createRegistrationAssociation(UUID guestId, UUID propertyId);

    /**
     * Checks whether an association already exists between the guest and property.
     *
     * @param guestId the guest UUID
     * @param propertyId the property UUID
     * @return true if associated, false otherwise
     */
    boolean isGuestAssociatedWithProperty(UUID guestId, UUID propertyId);

    /**
     * Retrieves all property UUIDs associated with the specified guest.
     *
     * @param guestId the guest UUID
     * @return list of property UUIDs
     */
    List<UUID> getAssociatedPropertyIds(UUID guestId);

    /**
     * Retrieves all guest UUIDs associated with the specified property.
     *
     * @param propertyId the property UUID
     * @return list of guest UUIDs
     */
    List<UUID> getAssociatedGuestIds(UUID propertyId);

    /**
     * Retrieves the association record, if present.
     *
     * @param guestId the guest UUID
     * @param propertyId the property UUID
     * @return Optional containing the association if present
     */
    Optional<GuestPropertyAssociation> getAssociation(UUID guestId, UUID propertyId);

    /**
     * Touches or records guest activity for an authorized reservation event.
     * If association exists, updates last_activity_at to CURRENT_TIMESTAMP while preserving
     * original first_associated_at and association_source (especially HISTORICAL_BACKFILL).
     *
     * @param guestId the guest UUID
     * @param propertyId the property UUID
     */
    void recordReservationActivity(UUID guestId, UUID propertyId);

    /**
     * Validates whether an existing guest may be used at the target property (e.g. for reservation creation).
     * Prevents arbitrary guest adoption across properties:
     * - If enforcement disabled: allowed if guest exists.
     * - If global SUPER_ADMIN: allowed if guest exists.
     * - If scoped staff: allowed only if guest already has an established association with target property.
     *   Throws ResourceNotFoundException (uniform 404) if not associated.
     *
     * @param guestId the guest UUID
     * @param propertyId the target property UUID
     */
    void validateExistingGuestUsableAtProperty(UUID guestId, UUID propertyId);

    /**
     * Explicitly associates an existing guest with a new property under authorized workflows.
     * Requires:
     * - Caller is global SUPER_ADMIN, OR
     * - Caller holds GUEST_MANAGE on an existing property associated with the guest AND GUEST_MANAGE on target property.
     * Throws AccessDeniedException if caller lacks cross-property authorization.
     *
     * @param guestId the existing guest UUID
     * @param propertyId the target property UUID
     * @param source the association source (REGISTRATION or RESERVATION)
     * @return the created or existing association
     */
    GuestPropertyAssociation attachExistingGuestWithAuthorization(UUID guestId, UUID propertyId, GuestAssociationSource source);
}
