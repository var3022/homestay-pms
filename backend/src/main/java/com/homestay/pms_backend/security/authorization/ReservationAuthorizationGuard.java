package com.homestay.pms_backend.security.authorization;

import com.homestay.pms_backend.config.SecurityEnforcementProperties;
import com.homestay.pms_backend.entity.Reservation;
import com.homestay.pms_backend.entity.ReservationRoom;
import com.homestay.pms_backend.entity.RoomType;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.repository.ReservationRepository;
import com.homestay.pms_backend.repository.ReservationRoomRepository;
import com.homestay.pms_backend.repository.RoomTypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Authoritative Reservation and Reservation-Room Authorization Guard.
 *
 * Centralizes property-scoped RBAC enforcement for Reservation and Reservation-Room resources:
 * 1. Derives authenticated staff identity exclusively from SecurityContext.
 * 2. Resolves permissions dynamically via database-backed StaffAuthorizationService.
 * 3. Resolves resource property ownership directly from persisted records (never trusts client parameters).
 * 4. Fails closed with 403 AccessDeniedException on unauthorized operations.
 * 5. Returns 404 ResourceNotFoundException when resources are non-existent OR outside the caller's authorized scope,
 *    preventing cross-property resource existence enumeration.
 * 6. Preserves core business validation integrity (cross-property room type binding rejection) regardless of security enforcement mode.
 * 7. Enforces sensitive reservation response privacy: full reservation and reservation-room reads expose financial and
 *    sensitive booking details (unit price, subtotal, external booking IDs, special requests) and are restricted via
 *    RESERVATION_READ to SUPER_ADMIN, PROPERTY_ADMIN, RECEPTIONIST, and ACCOUNTANT. VIEWER is strictly denied full responses
 *    until sanitized booking-calendar DTOs are introduced in a future step.
 */
@Component
public class ReservationAuthorizationGuard {

    private static final Logger log = LoggerFactory.getLogger(ReservationAuthorizationGuard.class);

    private final StaffAuthorizationService authorizationService;
    private final SecurityEnforcementProperties enforcementProperties;
    private final PropertyRepository propertyRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationRoomRepository reservationRoomRepository;
    private final RoomTypeRepository roomTypeRepository;

    @Autowired
    public ReservationAuthorizationGuard(
            StaffAuthorizationService authorizationService,
            SecurityEnforcementProperties enforcementProperties,
            PropertyRepository propertyRepository,
            ReservationRepository reservationRepository,
            ReservationRoomRepository reservationRoomRepository,
            RoomTypeRepository roomTypeRepository
    ) {
        this.authorizationService = Objects.requireNonNull(authorizationService, "StaffAuthorizationService must not be null");
        this.enforcementProperties = Objects.requireNonNull(enforcementProperties, "SecurityEnforcementProperties must not be null");
        this.propertyRepository = Objects.requireNonNull(propertyRepository, "PropertyRepository must not be null");
        this.reservationRepository = Objects.requireNonNull(reservationRepository, "ReservationRepository must not be null");
        this.reservationRoomRepository = Objects.requireNonNull(reservationRoomRepository, "ReservationRoomRepository must not be null");
        this.roomTypeRepository = Objects.requireNonNull(roomTypeRepository, "RoomTypeRepository must not be null");
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
     * Validates reservation creation invariants:
     * 1. Target propertyId is provided (400).
     * 2. Target property exists and is within caller's scope (404).
     * 3. Caller possesses RESERVATION_MANAGE on target property (403).
     */
    public void validateReservationCreation(UUID propertyId) {
        if (propertyId == null) {
            throw new BusinessValidationException("propertyId must be provided");
        }
        requirePropertyExistenceAndAccess(propertyId, StaffOperation.RESERVATION_MANAGE);
    }

    /**
     * Authoritatively resolves the owning property ID for a persisted reservation.
     *
     * @throws ResourceNotFoundException if reservation does not exist
     */
    public UUID resolveReservationPropertyId(UUID reservationId) {
        if (reservationId == null) {
            throw new ResourceNotFoundException("Reservation not found with id: null");
        }
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found with id: " + reservationId));
        return reservation.getPropertyId();
    }

    /**
     * Enforces that the reservation exists, belongs to a property within caller's scope (returns 404 if reservation
     * does not exist or belongs to an out-of-scope property), and verifies caller has operation access (returns 403
     * if in scope but lacks permission).
     */
    public void requireReservationAccess(UUID reservationId, StaffOperation operation) {
        if (!isEnforcementEnabled()) {
            return;
        }
        if (reservationId == null) {
            throw new ResourceNotFoundException("Reservation not found with id: null");
        }
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found with id: " + reservationId));
        if (!isPropertyInScope(reservation.getPropertyId())) {
            log.warn("Reservation out of scope: Reservation [{}] belongs to property [{}] outside staff scope. Returning 404.",
                    reservationId, reservation.getPropertyId());
            throw new ResourceNotFoundException("Reservation not found with id: " + reservationId);
        }
        if (!authorizationService.hasPropertyAccess(reservation.getPropertyId(), operation)) {
            log.warn("Access denied: Staff principal lacks {} on property {} for reservation {}",
                    operation, reservation.getPropertyId(), reservationId);
            throw new AccessDeniedException("Access is denied.");
        }
    }

    /**
     * Validates reservation room addition invariants:
     * 1. Target reservation exists (404).
     * 2. Target reservation's property is within caller's scope (404).
     * 3. Caller possesses RESERVATION_MANAGE on target property (403).
     * 4. Target room type exists (404).
     * 5. Target room type belongs to target reservation's property (400 BusinessValidationException) - ALWAYS RUNS.
     */
    public void validateReservationRoomCreation(UUID reservationId, UUID roomTypeId) {
        if (reservationId == null) {
            throw new BusinessValidationException("reservationId must be provided");
        }
        if (roomTypeId == null) {
            throw new BusinessValidationException("roomTypeId must be provided");
        }
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found with id: " + reservationId));

        if (isEnforcementEnabled()) {
            if (!isPropertyInScope(reservation.getPropertyId())) {
                log.warn("Reservation room creation out of scope: Reservation [{}] belongs to property [{}] outside staff scope. Returning 404.",
                        reservationId, reservation.getPropertyId());
                throw new ResourceNotFoundException("Reservation not found with id: " + reservationId);
            }
            if (!authorizationService.hasPropertyAccess(reservation.getPropertyId(), StaffOperation.RESERVATION_MANAGE)) {
                log.warn("Access denied: Staff principal lacks RESERVATION_MANAGE on property {} for reservation room creation",
                        reservation.getPropertyId());
                throw new AccessDeniedException("Access is denied.");
            }
        }

        RoomType roomType = roomTypeRepository.findById(roomTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found with id: " + roomTypeId));

        if (!reservation.getPropertyId().equals(roomType.getPropertyId())) {
            log.warn("Cross-property reservation room type binding rejected: reservation property [{}] does not match roomType property [{}]",
                    reservation.getPropertyId(), roomType.getPropertyId());
            throw new BusinessValidationException("The selected room type does not belong to the reservation property");
        }
    }

    /**
     * Enforces that the reservation room exists, its parent reservation belongs to a property within caller's scope
     * (returns 404 if reservation room does not exist, parent reservation does not exist, or belongs to an out-of-scope
     * property), and verifies caller has operation access (returns 403 if in scope but lacks permission).
     */
    public void requireReservationRoomAccess(UUID reservationRoomId, StaffOperation operation) {
        if (!isEnforcementEnabled()) {
            return;
        }
        if (reservationRoomId == null) {
            throw new ResourceNotFoundException("Reservation room not found with id: null");
        }
        ReservationRoom reservationRoom = reservationRoomRepository.findById(reservationRoomId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation room not found with id: " + reservationRoomId));
        Reservation reservation = reservationRepository.findById(reservationRoom.getReservationId())
                .orElseThrow(() -> new ResourceNotFoundException("Reservation room not found with id: " + reservationRoomId));
        if (!isPropertyInScope(reservation.getPropertyId())) {
            log.warn("Reservation room out of scope: Reservation room [{}] belongs to property [{}] outside staff scope. Returning 404.",
                    reservationRoomId, reservation.getPropertyId());
            throw new ResourceNotFoundException("Reservation room not found with id: " + reservationRoomId);
        }
        if (!authorizationService.hasPropertyAccess(reservation.getPropertyId(), operation)) {
            log.warn("Access denied: Staff principal lacks {} on property {} for reservation room {}",
                    operation, reservation.getPropertyId(), reservationRoomId);
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
}
