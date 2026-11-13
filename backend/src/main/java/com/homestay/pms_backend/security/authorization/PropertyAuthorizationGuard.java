package com.homestay.pms_backend.security.authorization;

import com.homestay.pms_backend.config.SecurityEnforcementProperties;
import com.homestay.pms_backend.dto.response.PropertyAmenityResponse;
import com.homestay.pms_backend.dto.response.PropertyResponse;
import com.homestay.pms_backend.dto.response.RoomAmenityResponse;
import com.homestay.pms_backend.dto.response.RoomBedResponse;
import com.homestay.pms_backend.entity.Room;
import com.homestay.pms_backend.entity.RoomBed;
import com.homestay.pms_backend.entity.RoomType;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Shared Property-Scope Authorization Guard.
 *
 * Centralizes property-scoped RBAC enforcement for Property, Room, Room-Type, Room-Bed,
 * Room-Amenity, and Property-Amenity resources:
 * 1. Derives authenticated staff identity exclusively from SecurityContext.
 * 2. Resolves permissions dynamically via database-backed StaffAuthorizationService.
 * 3. Resolves resource property ownership directly from the database (never trusts client parameters).
 * 4. Fails closed with 403 AccessDeniedException on unauthorized operations.
 * 5. Returns 404 ResourceNotFoundException when resources are non-existent OR outside the caller's authorized scope,
 *    preventing cross-property resource existence enumeration.
 * 6. Enforces global administrative checks (403) before property existence checks, preventing enumeration via admin endpoints.
 * 7. Preserves core business validation integrity regardless of security enforcement mode.
 */
@Component
public class PropertyAuthorizationGuard {

    private static final Logger log = LoggerFactory.getLogger(PropertyAuthorizationGuard.class);

    private final StaffAuthorizationService authorizationService;
    private final SecurityEnforcementProperties enforcementProperties;
    private final PropertyRepository propertyRepository;
    private final RoomRepository roomRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final RoomBedRepository roomBedRepository;
    private final BedTypeRepository bedTypeRepository;
    private final AmenityRepository amenityRepository;

    @Autowired
    public PropertyAuthorizationGuard(
            StaffAuthorizationService authorizationService,
            SecurityEnforcementProperties enforcementProperties,
            PropertyRepository propertyRepository,
            RoomRepository roomRepository,
            RoomTypeRepository roomTypeRepository,
            RoomBedRepository roomBedRepository,
            BedTypeRepository bedTypeRepository,
            AmenityRepository amenityRepository
    ) {
        this.authorizationService = Objects.requireNonNull(authorizationService, "StaffAuthorizationService must not be null");
        this.enforcementProperties = Objects.requireNonNull(enforcementProperties, "SecurityEnforcementProperties must not be null");
        this.propertyRepository = Objects.requireNonNull(propertyRepository, "PropertyRepository must not be null");
        this.roomRepository = Objects.requireNonNull(roomRepository, "RoomRepository must not be null");
        this.roomTypeRepository = Objects.requireNonNull(roomTypeRepository, "RoomTypeRepository must not be null");
        this.roomBedRepository = Objects.requireNonNull(roomBedRepository, "RoomBedRepository must not be null");
        this.bedTypeRepository = Objects.requireNonNull(bedTypeRepository, "BedTypeRepository must not be null");
        this.amenityRepository = Objects.requireNonNull(amenityRepository, "AmenityRepository must not be null");
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
     * Enforces that the current authenticated principal possesses global SUPER_ADMIN status.
     * Used for global property creation.
     */
    public void requireGlobalSuperAdmin() {
        if (!isEnforcementEnabled()) {
            return;
        }
        if (!authorizationService.isGlobalSuperAdmin()) {
            log.warn("Access denied: Global SUPER_ADMIN required but caller is not authorized.");
            throw new AccessDeniedException("Access is denied.");
        }
    }

    /**
     * Enforces global SUPER_ADMIN status before evaluating property existence.
     * Used for property activation and deactivation to prevent non-SUPER_ADMIN callers from probing property existence.
     */
    public void requirePropertyExistenceAndSuperAdmin(UUID propertyId) {
        if (!isEnforcementEnabled()) {
            return;
        }
        requireGlobalSuperAdmin();
        if (propertyId == null || !propertyRepository.existsById(propertyId)) {
            throw new ResourceNotFoundException("Property not found with id: " + propertyId);
        }
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
     * Resolves the authoritative propertyId for a given room.
     *
     * @throws ResourceNotFoundException if room does not exist
     */
    public UUID resolveRoomPropertyId(UUID roomId) {
        if (roomId == null) {
            throw new ResourceNotFoundException("Room not found with id: null");
        }
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + roomId));
        return room.getPropertyId();
    }

    /**
     * Enforces that the room exists, belongs to a property within caller's scope (returns 404 if room does not exist
     * or belongs to an out-of-scope property), and verifies caller has operation access (returns 403 if in scope but lacks permission).
     */
    public void requireRoomAccess(UUID roomId, StaffOperation operation) {
        if (!isEnforcementEnabled()) {
            return;
        }
        if (roomId == null) {
            throw new ResourceNotFoundException("Room not found with id: null");
        }
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + roomId));
        if (!isPropertyInScope(room.getPropertyId())) {
            log.warn("Room out of scope: Room [{}] belongs to property [{}] outside staff scope. Returning 404.",
                    roomId, room.getPropertyId());
            throw new ResourceNotFoundException("Room not found with id: " + roomId);
        }
        if (!authorizationService.hasPropertyAccess(room.getPropertyId(), operation)) {
            log.warn("Access denied: Staff principal lacks {} on property {} for room {}",
                    operation, room.getPropertyId(), roomId);
            throw new AccessDeniedException("Access is denied.");
        }
    }

    /**
     * Resolves the authoritative propertyId for a given room type.
     *
     * @throws ResourceNotFoundException if room type does not exist
     */
    public UUID resolveRoomTypePropertyId(UUID roomTypeId) {
        if (roomTypeId == null) {
            throw new ResourceNotFoundException("Room type not found with id: null");
        }
        RoomType roomType = roomTypeRepository.findById(roomTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found with id: " + roomTypeId));
        return roomType.getPropertyId();
    }

    /**
     * Enforces that the room type exists, belongs to a property within caller's scope (returns 404 if room type does not exist
     * or belongs to an out-of-scope property), and verifies caller has operation access (returns 403 if in scope but lacks permission).
     */
    public void requireRoomTypeAccess(UUID roomTypeId, StaffOperation operation) {
        if (!isEnforcementEnabled()) {
            return;
        }
        if (roomTypeId == null) {
            throw new ResourceNotFoundException("Room type not found with id: null");
        }
        RoomType roomType = roomTypeRepository.findById(roomTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found with id: " + roomTypeId));
        if (!isPropertyInScope(roomType.getPropertyId())) {
            log.warn("Room type out of scope: Room type [{}] belongs to property [{}] outside staff scope. Returning 404.",
                    roomTypeId, roomType.getPropertyId());
            throw new ResourceNotFoundException("Room type not found with id: " + roomTypeId);
        }
        if (!authorizationService.hasPropertyAccess(roomType.getPropertyId(), operation)) {
            log.warn("Access denied: Staff principal lacks {} on property {} for room type {}",
                    operation, roomType.getPropertyId(), roomTypeId);
            throw new AccessDeniedException("Access is denied.");
        }
    }

    /**
     * Validates room creation invariants:
     * 1. Target property exists (404).
     * 2. Target property is within caller's scope if enforcement enabled (404).
     * 3. Room type exists (404).
     * 4. Room type belongs to target property (400 BusinessValidationException) - ALWAYS RUNS regardless of enforcement flag.
     * 5. Caller has ROOM_MANAGE on target property if enforcement enabled (403).
     */
    public void validateRoomCreation(UUID propertyId, UUID roomTypeId) {
        if (propertyId == null) {
            throw new BusinessValidationException("propertyId must be provided");
        }
        if (roomTypeId == null) {
            throw new BusinessValidationException("roomTypeId must be provided");
        }
        if (!propertyRepository.existsById(propertyId)) {
            throw new ResourceNotFoundException("Property not found with id: " + propertyId);
        }
        if (isEnforcementEnabled() && !isPropertyInScope(propertyId)) {
            throw new ResourceNotFoundException("Property not found with id: " + propertyId);
        }

        RoomType roomType = roomTypeRepository.findById(roomTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found with id: " + roomTypeId));

        if (!propertyId.equals(roomType.getPropertyId())) {
            log.warn("Cross-property room type binding rejected: propertyId [{}] does not match roomType property [{}]",
                    propertyId, roomType.getPropertyId());
            throw new BusinessValidationException("Room type does not belong to the specified property");
        }

        if (isEnforcementEnabled()) {
            requirePropertyAccess(propertyId, StaffOperation.ROOM_MANAGE);
        }
    }

    /**
     * Validates room query invariants when both propertyId and roomTypeId are supplied:
     * 1. Target property exists (404).
     * 2. Target property is within caller's scope if enforcement enabled (404).
     * 3. Room type exists (404).
     * 4. Room type belongs to target property (400 BusinessValidationException) - ALWAYS RUNS regardless of enforcement flag.
     * 5. Caller has ROOM_READ on target property if enforcement enabled (403).
     */
    public void validateRoomQuery(UUID propertyId, UUID roomTypeId) {
        if (propertyId == null || roomTypeId == null) {
            return;
        }
        if (!propertyRepository.existsById(propertyId)) {
            throw new ResourceNotFoundException("Property not found with id: " + propertyId);
        }
        if (isEnforcementEnabled() && !isPropertyInScope(propertyId)) {
            throw new ResourceNotFoundException("Property not found with id: " + propertyId);
        }

        RoomType roomType = roomTypeRepository.findById(roomTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found with id: " + roomTypeId));

        if (!propertyId.equals(roomType.getPropertyId())) {
            log.warn("Cross-property room query rejected: propertyId [{}] does not match roomType property [{}]",
                    propertyId, roomType.getPropertyId());
            throw new BusinessValidationException("Room type does not belong to the specified property");
        }

        if (isEnforcementEnabled()) {
            requirePropertyAccess(propertyId, StaffOperation.ROOM_READ);
        }
    }

    /**
     * Validates room bed creation invariants:
     * 1. Target room exists (404).
     * 2. Target room's property is within caller's scope if enforcement enabled (404).
     * 3. Caller has ROOM_MANAGE on target property if enforcement enabled (403).
     */
    public void validateRoomBedCreation(UUID roomId) {
        if (roomId == null) {
            throw new BusinessValidationException("roomId must be provided");
        }
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + roomId));
        if (isEnforcementEnabled() && !isPropertyInScope(room.getPropertyId())) {
            log.warn("Room bed creation out of scope: Room [{}] belongs to property [{}] outside staff scope. Returning 404.",
                    roomId, room.getPropertyId());
            throw new ResourceNotFoundException("Room not found with id: " + roomId);
        }
        if (isEnforcementEnabled()) {
            requirePropertyAccess(room.getPropertyId(), StaffOperation.ROOM_MANAGE);
        }
    }

    /**
     * Enforces that the room bed exists, its room belongs to a property within caller's scope (returns 404 if room bed
     * does not exist or belongs to an out-of-scope property), and verifies caller has operation access (returns 403 if in scope but lacks permission).
     */
    public void requireRoomBedAccess(UUID roomBedId, StaffOperation operation) {
        if (!isEnforcementEnabled()) {
            return;
        }
        if (roomBedId == null) {
            throw new ResourceNotFoundException("Room bed configuration not found with id: null");
        }
        RoomBed roomBed = roomBedRepository.findById(roomBedId)
                .orElseThrow(() -> new ResourceNotFoundException("Room bed configuration not found with id: " + roomBedId));
        Room room = roomRepository.findById(roomBed.getRoomId())
                .orElseThrow(() -> new ResourceNotFoundException("Room bed configuration not found with id: " + roomBedId));
        if (!isPropertyInScope(room.getPropertyId())) {
            log.warn("Room bed out of scope: Room bed [{}] belongs to property [{}] outside staff scope. Returning 404.",
                    roomBedId, room.getPropertyId());
            throw new ResourceNotFoundException("Room bed configuration not found with id: " + roomBedId);
        }
        if (!authorizationService.hasPropertyAccess(room.getPropertyId(), operation)) {
            log.warn("Access denied: Staff principal lacks {} on property {} for room bed {}",
                    operation, room.getPropertyId(), roomBedId);
            throw new AccessDeniedException("Access is denied.");
        }
    }

    /**
     * Validates room amenity assignment invariants:
     * 1. Target room exists (404).
     * 2. Target room's property is within caller's scope if enforcement enabled (404).
     * 3. Caller has ROOM_MANAGE on target property if enforcement enabled (403).
     */
    public void validateRoomAmenityAssignment(UUID roomId) {
        if (roomId == null) {
            throw new BusinessValidationException("roomId must be provided");
        }
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + roomId));
        if (isEnforcementEnabled() && !isPropertyInScope(room.getPropertyId())) {
            log.warn("Room amenity assignment out of scope: Room [{}] belongs to property [{}] outside staff scope. Returning 404.",
                    roomId, room.getPropertyId());
            throw new ResourceNotFoundException("Room not found with id: " + roomId);
        }
        if (isEnforcementEnabled()) {
            requirePropertyAccess(room.getPropertyId(), StaffOperation.ROOM_MANAGE);
        }
    }

    /**
     * Validates that the global bed type exists (404 if not found).
     */
    public void validateBedTypeExists(UUID bedTypeId) {
        if (bedTypeId == null || !bedTypeRepository.existsById(bedTypeId)) {
            throw new ResourceNotFoundException("Bed type not found with id: " + bedTypeId);
        }
    }

    /**
     * Validates that the global amenity exists (404 if not found).
     */
    public void validateAmenityExists(UUID amenityId) {
        if (amenityId == null || !amenityRepository.existsById(amenityId)) {
            throw new ResourceNotFoundException("Amenity not found with id: " + amenityId);
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
     * Filters property list to only properties the caller has PROPERTY_READ access to.
     * Global SUPER_ADMIN receives all properties.
     * Property-scoped staff receive only their assigned properties.
     * Unassigned staff receive an empty list.
     */
    public List<PropertyResponse> filterAuthorizedProperties(List<PropertyResponse> properties) {
        if (!isEnforcementEnabled()) {
            return properties;
        }
        if (authorizationService.isGlobalSuperAdmin()) {
            return properties;
        }

        Set<UUID> assignedIds = authorizationService.getAssignedPropertyIds();
        if (assignedIds.isEmpty()) {
            return Collections.emptyList();
        }

        return properties.stream()
                .filter(p -> assignedIds.contains(p.getId()) && authorizationService.hasPropertyAccess(p.getId(), StaffOperation.PROPERTY_READ))
                .toList();
    }

    /**
     * Filters room-bed list to only records belonging to properties the caller has ROOM_READ access to.
     */
    public List<RoomBedResponse> filterAuthorizedRoomBeds(List<RoomBedResponse> roomBeds) {
        if (!isEnforcementEnabled() || authorizationService.isGlobalSuperAdmin()) {
            return roomBeds;
        }
        Set<UUID> assignedIds = authorizationService.getAssignedPropertyIds();
        if (assignedIds.isEmpty() || roomBeds.isEmpty()) {
            return Collections.emptyList();
        }
        Set<UUID> roomIds = roomBeds.stream()
                .map(RoomBedResponse::getRoomId)
                .collect(Collectors.toSet());
        Map<UUID, UUID> roomToPropertyMap = roomRepository.findAllById(roomIds).stream()
                .collect(Collectors.toMap(Room::getId, Room::getPropertyId));

        return roomBeds.stream()
                .filter(rb -> {
                    UUID propertyId = roomToPropertyMap.get(rb.getRoomId());
                    return propertyId != null
                            && assignedIds.contains(propertyId)
                            && authorizationService.hasPropertyAccess(propertyId, StaffOperation.ROOM_READ);
                })
                .toList();
    }

    /**
     * Filters room-amenity list to only records belonging to properties the caller has ROOM_READ access to.
     */
    public List<RoomAmenityResponse> filterAuthorizedRoomAmenities(List<RoomAmenityResponse> roomAmenities) {
        if (!isEnforcementEnabled() || authorizationService.isGlobalSuperAdmin()) {
            return roomAmenities;
        }
        Set<UUID> assignedIds = authorizationService.getAssignedPropertyIds();
        if (assignedIds.isEmpty() || roomAmenities.isEmpty()) {
            return Collections.emptyList();
        }
        Set<UUID> roomIds = roomAmenities.stream()
                .map(RoomAmenityResponse::getRoomId)
                .collect(Collectors.toSet());
        Map<UUID, UUID> roomToPropertyMap = roomRepository.findAllById(roomIds).stream()
                .collect(Collectors.toMap(Room::getId, Room::getPropertyId));

        return roomAmenities.stream()
                .filter(ra -> {
                    UUID propertyId = roomToPropertyMap.get(ra.getRoomId());
                    return propertyId != null
                            && assignedIds.contains(propertyId)
                            && authorizationService.hasPropertyAccess(propertyId, StaffOperation.ROOM_READ);
                })
                .toList();
    }

    /**
     * Filters property-amenity list to only records belonging to properties the caller has PROPERTY_READ access to.
     */
    public List<PropertyAmenityResponse> filterAuthorizedPropertyAmenities(List<PropertyAmenityResponse> propertyAmenities) {
        if (!isEnforcementEnabled() || authorizationService.isGlobalSuperAdmin()) {
            return propertyAmenities;
        }
        Set<UUID> assignedIds = authorizationService.getAssignedPropertyIds();
        if (assignedIds.isEmpty() || propertyAmenities.isEmpty()) {
            return Collections.emptyList();
        }
        return propertyAmenities.stream()
                .filter(pa -> assignedIds.contains(pa.getPropertyId())
                        && authorizationService.hasPropertyAccess(pa.getPropertyId(), StaffOperation.PROPERTY_READ))
                .toList();
    }
}
