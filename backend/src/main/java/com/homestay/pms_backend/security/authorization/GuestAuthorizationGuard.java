package com.homestay.pms_backend.security.authorization;

import com.homestay.pms_backend.config.SecurityEnforcementProperties;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.repository.GuestPropertyAssociationRepository;
import com.homestay.pms_backend.repository.GuestRepository;
import com.homestay.pms_backend.repository.PropertyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Authoritative Guest Authorization Guard.
 *
 * Enforces property-scoped, database-backed RBAC for Guest resources:
 * 1. Derives authenticated staff identity exclusively from SecurityContext via StaffAuthorizationService.
 * 2. Revalidates staff role and property permissions on every request without cross-request caching.
 * 3. Never trusts client-supplied staff IDs, property assignments, or guest ownership claims.
 * 4. Resolves guest access via persisted guest_property_associations.
 * 5. Returns uniform 404 ResourceNotFoundException when a guest is non-existent OR outside the caller's authorized scope,
 *    preventing cross-property resource existence enumeration.
 * 6. Returns 403 AccessDeniedException when the caller has property scope but lacks required operation permission.
 * 7. Strictly denies VIEWER and ACCOUNTANT access to guest details (protecting sensitive PII and ID documents).
 * 8. Shared multi-property guest protection: staff assigned to a single property cannot modify global shared identity fields
 *    for a guest associated with multiple properties (requires global SUPER_ADMIN).
 * 9. Never discloses guest identifiers, phone numbers, email addresses, or document numbers in authorization errors.
 */
@Component
public class GuestAuthorizationGuard {

    private static final Logger log = LoggerFactory.getLogger(GuestAuthorizationGuard.class);

    private final StaffAuthorizationService authorizationService;
    private final SecurityEnforcementProperties enforcementProperties;
    private final PropertyRepository propertyRepository;
    private final GuestRepository guestRepository;
    private final GuestPropertyAssociationRepository associationRepository;

    @Autowired
    public GuestAuthorizationGuard(
            StaffAuthorizationService authorizationService,
            SecurityEnforcementProperties enforcementProperties,
            PropertyRepository propertyRepository,
            GuestRepository guestRepository,
            GuestPropertyAssociationRepository associationRepository
    ) {
        this.authorizationService = Objects.requireNonNull(authorizationService, "StaffAuthorizationService must not be null");
        this.enforcementProperties = Objects.requireNonNull(enforcementProperties, "SecurityEnforcementProperties must not be null");
        this.propertyRepository = Objects.requireNonNull(propertyRepository, "PropertyRepository must not be null");
        this.guestRepository = Objects.requireNonNull(guestRepository, "GuestRepository must not be null");
        this.associationRepository = Objects.requireNonNull(associationRepository, "GuestPropertyAssociationRepository must not be null");
    }

    /**
     * Checks if JWT/RBAC security enforcement is actively enabled.
     */
    public boolean isEnforcementEnabled() {
        return enforcementProperties.isEnabled();
    }

    /**
     * Determines whether the currently authenticated staff member has global SUPER_ADMIN status.
     */
    public boolean isGlobalSuperAdmin() {
        return authorizationService.isGlobalSuperAdmin();
    }

    /**
     * Checks if a property is within the authorized scope of the authenticated staff member.
     * When enforcement is disabled, all properties are considered in scope.
     * When enabled, returns true for global SUPER_ADMIN or staff members assigned to the property.
     */
    public boolean isPropertyInScope(UUID propertyId) {
        if (!isEnforcementEnabled()) {
            return true;
        }
        if (propertyId == null) {
            return false;
        }
        return authorizationService.isGlobalSuperAdmin() || authorizationService.isAssignedToProperty(propertyId);
    }

    /**
     * Enforces that the current authenticated principal is authorized for the given operation on the property.
     */
    public void requirePropertyAccess(UUID propertyId, StaffOperation operation) {
        if (!isEnforcementEnabled()) {
            return;
        }
        if (propertyId == null) {
            log.warn("Access denied: Target propertyId is null for operation {}", operation);
            throw new AccessDeniedException("Access is denied.");
        }
        if (!authorizationService.hasPropertyAccess(propertyId, operation)) {
            log.warn("Access denied: Staff principal lacks {} on property {}", operation, propertyId);
            throw new AccessDeniedException("Access is denied.");
        }
    }

    /**
     * Enforces that the specified property exists, is within caller's scope (returns 404 if out of scope),
     * and verifies caller has operation access on it (returns 403 if in scope but lacks permission).
     */
    public void requirePropertyExistenceAndAccess(UUID propertyId, StaffOperation operation) {
        if (!isEnforcementEnabled()) {
            return;
        }
        if (propertyId == null || !propertyRepository.existsById(propertyId)) {
            throw new ResourceNotFoundException("Property not found with id: " + propertyId);
        }
        if (!isPropertyInScope(propertyId)) {
            log.warn("Property out of scope: Property [{}] is outside staff scope. Returning 404.", propertyId);
            throw new ResourceNotFoundException("Property not found with id: " + propertyId);
        }
        requirePropertyAccess(propertyId, operation);
    }

    /**
     * Validates guest registration permissions for a target property:
     * 1. Target propertyId must be provided (BusinessValidationException).
     * 2. Target property exists and is within caller's scope (404 ResourceNotFoundException).
     * 3. Caller possesses GUEST_MANAGE on target property (403 AccessDeniedException).
     */
    public void validateGuestRegistration(UUID propertyId) {
        if (propertyId == null) {
            throw new BusinessValidationException("propertyId must be provided");
        }
        requirePropertyExistenceAndAccess(propertyId, StaffOperation.GUEST_MANAGE);
    }

    /**
     * Enforces guest access by persisted guestId:
     * 1. Returns 404 if guestId is null.
     * 2. When enforcement is disabled: verifies guest existence in guestRepository (404 if absent).
     * 3. When enforcement is enabled:
     *    - Global SUPER_ADMIN: verifies guest existence (404 if absent).
     *    - Scoped staff:
     *      - Finds associated properties via associationRepository.findPropertyIdsByGuestId(guestId).
     *      - If empty, or if none of the associated properties are in caller's assigned scope:
     *        returns 404 ResourceNotFoundException (preventing cross-property guest enumeration).
     *      - If at least one associated property is in caller's assigned scope:
     *        evaluates whether caller has operation on any assigned associated property.
     *        - If no assigned associated property grants operation: returns 403 AccessDeniedException.
     *        - If at least one grants operation: allows.
     */
    public void requireGuestAccess(UUID guestId, StaffOperation operation) {
        if (guestId == null) {
            throw new ResourceNotFoundException("Guest not found with id: null");
        }
        if (!isEnforcementEnabled()) {
            if (!guestRepository.existsById(guestId)) {
                throw new ResourceNotFoundException("Guest not found with id: " + guestId);
            }
            return;
        }

        if (authorizationService.isGlobalSuperAdmin()) {
            if (!guestRepository.existsById(guestId)) {
                throw new ResourceNotFoundException("Guest not found with id: " + guestId);
            }
            return;
        }

        List<UUID> associatedPropertyIds = associationRepository.findPropertyIdsByGuestId(guestId);
        if (associatedPropertyIds.isEmpty()) {
            throw new ResourceNotFoundException("Guest not found with id: " + guestId);
        }

        List<UUID> inScopeAssociatedProperties = associatedPropertyIds.stream()
                .filter(authorizationService::isAssignedToProperty)
                .toList();

        if (inScopeAssociatedProperties.isEmpty()) {
            log.warn("Guest [{}] exists but is outside caller property scope. Returning uniform 404.", guestId);
            throw new ResourceNotFoundException("Guest not found with id: " + guestId);
        }

        boolean hasPermission = inScopeAssociatedProperties.stream()
                .anyMatch(propId -> authorizationService.hasPropertyAccess(propId, operation));

        if (!hasPermission) {
            log.warn("Access denied: Caller is assigned to property for guest [{}] but lacks {} (e.g. VIEWER/ACCOUNTANT).",
                    guestId, operation);
            throw new AccessDeniedException("Access is denied.");
        }
    }

    /**
     * Enforces guest access specifically in the context of a target property:
     * 1. Target property must exist and be in caller's scope (404), and caller must have operation (403).
     * 2. Guest must exist and have an established association with that property (404).
     */
    public void requireGuestAccessForProperty(UUID guestId, UUID propertyId, StaffOperation operation) {
        if (guestId == null) {
            throw new ResourceNotFoundException("Guest not found with id: null");
        }
        if (propertyId == null) {
            throw new ResourceNotFoundException("Property not found with id: null");
        }
        requirePropertyExistenceAndAccess(propertyId, operation);

        if (!isEnforcementEnabled()) {
            if (!guestRepository.existsById(guestId)) {
                throw new ResourceNotFoundException("Guest not found with id: " + guestId);
            }
            return;
        }

        if (!associationRepository.existsByIdGuestIdAndIdPropertyId(guestId, propertyId)) {
            log.warn("Guest [{}] is not associated with property [{}]. Returning 404.", guestId, propertyId);
            throw new ResourceNotFoundException("Guest not found with id: " + guestId);
        }
    }

    /**
     * Validates permission to update guest records:
     * 1. Requires GUEST_MANAGE access for the guest.
     * 2. Shared Guest Protection: If the guest is associated with multiple properties,
     *    modifying global identity fields cannot be performed by a property-scoped staff member.
     *    Only global SUPER_ADMIN can modify shared multi-property guest records.
     */
    public void requireGuestUpdateAccess(UUID guestId) {
        requireGuestAccess(guestId, StaffOperation.GUEST_MANAGE);

        if (!isEnforcementEnabled() || authorizationService.isGlobalSuperAdmin()) {
            return;
        }

        List<UUID> associatedPropertyIds = associationRepository.findPropertyIdsByGuestId(guestId);
        if (associatedPropertyIds.size() > 1) {
            log.warn("Access denied: Guest [{}] is associated with {} properties. Property-scoped staff cannot modify shared multi-property guest identity.",
                    guestId, associatedPropertyIds.size());
            throw new AccessDeniedException("Shared multi-property guest identity cannot be modified by property-scoped staff.");
        }
    }

    /**
     * Enforces global super administrator authority for global lifecycle changes (e.g. guest activation/deactivation).
     */
    public void requireGlobalSuperAdmin(String actionDescription) {
        if (!isEnforcementEnabled()) {
            return;
        }
        if (!authorizationService.isGlobalSuperAdmin()) {
            log.warn("Access denied: Operation [{}] requires global super administrator status.", actionDescription);
            throw new AccessDeniedException("Access is denied.");
        }
    }

    /**
     * Resolves the set of property IDs the current staff member has the given operation permission on.
     */
    public Set<UUID> getAuthorizedPropertyIds(StaffOperation operation) {
        if (!isEnforcementEnabled() || authorizationService.isGlobalSuperAdmin()) {
            return Collections.emptySet();
        }
        return authorizationService.getAssignedPropertyIds().stream()
                .filter(propId -> authorizationService.hasPropertyAccess(propId, operation))
                .collect(Collectors.toSet());
    }

    /**
     * Resolves all guest IDs associated with the caller's authorized properties for the given operation.
     */
    public Set<UUID> getAuthorizedGuestIds(StaffOperation operation) {
        if (!isEnforcementEnabled() || authorizationService.isGlobalSuperAdmin()) {
            return Collections.emptySet();
        }
        Set<UUID> authorizedProps = getAuthorizedPropertyIds(operation);
        if (authorizedProps.isEmpty()) {
            return Collections.emptySet();
        }
        return Set.copyOf(associationRepository.findGuestIdsByPropertyIdIn(authorizedProps));
    }
}
