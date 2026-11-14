package com.homestay.pms_backend.security.authorization;

import com.homestay.pms_backend.config.SecurityEnforcementProperties;
import com.homestay.pms_backend.entity.Reservation;
import com.homestay.pms_backend.entity.ReservationRoom;
import com.homestay.pms_backend.entity.Room;
import com.homestay.pms_backend.entity.RoomAssignment;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.repository.ReservationRepository;
import com.homestay.pms_backend.repository.ReservationRoomRepository;
import com.homestay.pms_backend.repository.RoomAssignmentRepository;
import com.homestay.pms_backend.repository.RoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Authoritative Room-Assignment Authorization Guard.
 *
 * Centralizes property-scoped RBAC enforcement for Room-Assignment resources:
 * 1. Derives authenticated staff identity exclusively from SecurityContext via StaffAuthorizationService.
 * 2. Resolves permissions dynamically via database-backed StaffAuthorizationService without cross-request caching.
 * 3. Never trusts client-supplied staff identity, roles, or property ownership claims.
 * 4. Resolves authoritative property ownership directly from persisted records:
 *    - RoomAssignment -> ReservationRoom -> Reservation -> propertyId
 *    - Room -> propertyId
 * 5. Enforces fail-closed access:
 *    - Returns 404 ResourceNotFoundException when resources are non-existent OR outside caller's authorized scope,
 *      preventing cross-property resource existence enumeration.
 *    - Returns 403 AccessDeniedException when caller has property scope but lacks required operation permission.
 * 6. Preserves domain validation: when a caller is authorized for both properties but combines mismatched resources,
 *    business validation cleanly rejects with 400 Bad Request.
 * 7. Enforces operation permissions:
 *    - RESERVATION_MANAGE for room assignment creation and release (SUPER_ADMIN, PROPERTY_ADMIN, RECEPTIONIST).
 *    - RESERVATION_READ for room assignment reads (SUPER_ADMIN, PROPERTY_ADMIN, RECEPTIONIST, ACCOUNTANT).
 *    - VIEWER is strictly excluded from room assignment reads (403) until sanitized calendar DTOs are available.
 */
@Component
public class RoomAssignmentAuthorizationGuard {

    private static final Logger log = LoggerFactory.getLogger(RoomAssignmentAuthorizationGuard.class);

    private final StaffAuthorizationService authorizationService;
    private final SecurityEnforcementProperties enforcementProperties;
    private final RoomAssignmentRepository roomAssignmentRepository;
    private final ReservationRoomRepository reservationRoomRepository;
    private final ReservationRepository reservationRepository;
    private final RoomRepository roomRepository;
    private final PropertyRepository propertyRepository;

    @Autowired
    public RoomAssignmentAuthorizationGuard(
            StaffAuthorizationService authorizationService,
            SecurityEnforcementProperties enforcementProperties,
            RoomAssignmentRepository roomAssignmentRepository,
            ReservationRoomRepository reservationRoomRepository,
            ReservationRepository reservationRepository,
            RoomRepository roomRepository,
            PropertyRepository propertyRepository
    ) {
        this.authorizationService = Objects.requireNonNull(authorizationService, "StaffAuthorizationService must not be null");
        this.enforcementProperties = Objects.requireNonNull(enforcementProperties, "SecurityEnforcementProperties must not be null");
        this.roomAssignmentRepository = Objects.requireNonNull(roomAssignmentRepository, "RoomAssignmentRepository must not be null");
        this.reservationRoomRepository = Objects.requireNonNull(reservationRoomRepository, "ReservationRoomRepository must not be null");
        this.reservationRepository = Objects.requireNonNull(reservationRepository, "ReservationRepository must not be null");
        this.roomRepository = Objects.requireNonNull(roomRepository, "RoomRepository must not be null");
        this.propertyRepository = Objects.requireNonNull(propertyRepository, "PropertyRepository must not be null");
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
     * Resolves the authoritative propertyId for a given room assignment.
     *
     * @throws ResourceNotFoundException if assignment, reservation room, or reservation does not exist
     */
    public UUID resolveAssignmentPropertyId(UUID assignmentId) {
        if (assignmentId == null) {
            throw new ResourceNotFoundException("Room assignment not found with id: null");
        }
        RoomAssignment assignment = roomAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Room assignment not found with id: " + assignmentId));

        ReservationRoom reservationRoom = reservationRoomRepository.findById(assignment.getReservationRoomId())
                .orElseThrow(() -> new ResourceNotFoundException("Reservation room not found with id: " + assignment.getReservationRoomId()));

        Reservation reservation = reservationRepository.findById(reservationRoom.getReservationId())
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found with id: " + reservationRoom.getReservationId()));

        if (assignment.getRoomId() == null) {
            throw new ResourceNotFoundException("Room assignment not found with id: " + assignmentId);
        }

        Room room = roomRepository.findById(assignment.getRoomId())
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + assignment.getRoomId()));

        if (!room.getPropertyId().equals(reservation.getPropertyId())) {
            log.warn("Inconsistent historical assignment [{}]: reservation property [{}] does not match room property [{}]",
                    assignmentId, reservation.getPropertyId(), room.getPropertyId());
            throw new ResourceNotFoundException("Room assignment not found with id: " + assignmentId);
        }

        return reservation.getPropertyId();
    }

    /**
     * Resolves the authoritative propertyId for a given reservation room.
     *
     * @throws ResourceNotFoundException if reservation room or reservation does not exist
     */
    public UUID resolveReservationRoomPropertyId(UUID reservationRoomId) {
        if (reservationRoomId == null) {
            throw new ResourceNotFoundException("Reservation room not found with id: null");
        }
        ReservationRoom reservationRoom = reservationRoomRepository.findById(reservationRoomId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation room not found with id: " + reservationRoomId));

        Reservation reservation = reservationRepository.findById(reservationRoom.getReservationId())
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found with id: " + reservationRoom.getReservationId()));

        return reservation.getPropertyId();
    }

    /**
     * Resolves the authoritative propertyId for a given physical room.
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
     * Enforces access on an existing RoomAssignment:
     * 1. Returns 404 if assignment does not exist.
     * 2. In development mode (enforcement disabled): passes after existence check.
     * 3. Under enforcement:
     *    - Resolves property from ReservationRoom -> Reservation -> propertyId.
     *    - Returns 404 ResourceNotFoundException if property is outside caller's scope (anti-enumeration).
     *    - Returns 403 AccessDeniedException if caller has property scope but lacks required operation.
     */
    public void requireAssignmentAccess(UUID assignmentId, StaffOperation operation) {
        if (assignmentId == null) {
            throw new ResourceNotFoundException("Room assignment not found with id: null");
        }
        RoomAssignment assignment = roomAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Room assignment not found with id: " + assignmentId));

        if (!isEnforcementEnabled()) {
            return;
        }

        ReservationRoom reservationRoom = reservationRoomRepository.findById(assignment.getReservationRoomId())
                .orElseThrow(() -> new ResourceNotFoundException("Reservation room not found with id: " + assignment.getReservationRoomId()));

        Reservation reservation = reservationRepository.findById(reservationRoom.getReservationId())
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found with id: " + reservationRoom.getReservationId()));

        if (assignment.getRoomId() == null) {
            throw new ResourceNotFoundException("Room assignment not found with id: " + assignmentId);
        }

        Room room = roomRepository.findById(assignment.getRoomId())
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + assignment.getRoomId()));

        if (!room.getPropertyId().equals(reservation.getPropertyId())) {
            log.warn("Inconsistent historical assignment [{}]: reservation property [{}] does not match room property [{}]. Returning 404.",
                    assignmentId, reservation.getPropertyId(), room.getPropertyId());
            throw new ResourceNotFoundException("Room assignment not found with id: " + assignmentId);
        }

        if (!isPropertyInScope(reservation.getPropertyId())) {
            log.warn("Room assignment out of scope: Assignment [{}] belongs to property [{}] outside staff scope. Returning 404.",
                    assignmentId, reservation.getPropertyId());
            throw new ResourceNotFoundException("Room assignment not found with id: " + assignmentId);
        }

        if (!authorizationService.hasPropertyAccess(reservation.getPropertyId(), operation)) {
            log.warn("Access denied: Staff principal lacks {} on property {} for room assignment {}",
                    operation, reservation.getPropertyId(), assignmentId);
            throw new AccessDeniedException("Access is denied.");
        }
    }

    /**
     * Enforces access on a ReservationRoom (used for querying assignments by reservationRoomId):
     * 1. Returns 404 if reservationRoom does not exist.
     * 2. In development mode: passes after existence check.
     * 3. Under enforcement:
     *    - Resolves property from Reservation -> propertyId.
     *    - Returns 404 if property is outside caller's scope (anti-enumeration).
     *    - Returns 403 if caller lacks required operation.
     */
    public void requireReservationRoomAccess(UUID reservationRoomId, StaffOperation operation) {
        if (reservationRoomId == null) {
            throw new ResourceNotFoundException("Reservation room not found with id: null");
        }
        ReservationRoom reservationRoom = reservationRoomRepository.findById(reservationRoomId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation room not found with id: " + reservationRoomId));

        if (!isEnforcementEnabled()) {
            return;
        }

        Reservation reservation = reservationRepository.findById(reservationRoom.getReservationId())
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found with id: " + reservationRoom.getReservationId()));

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
     * Enforces access on a Room (used for querying assignments by roomId):
     * 1. Returns 404 if room does not exist.
     * 2. In development mode: passes after existence check.
     * 3. Under enforcement:
     *    - Returns 404 if room's property is outside caller's scope (anti-enumeration).
     *    - Returns 403 if caller lacks required operation.
     */
    public void requireRoomAccess(UUID roomId, StaffOperation operation) {
        if (roomId == null) {
            throw new ResourceNotFoundException("Room not found with id: null");
        }
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + roomId));

        if (!isEnforcementEnabled()) {
            return;
        }

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
     * Validates room assignment creation invariants across both referenced resources:
     * 1. Validates non-null parameter requirements (400 BusinessValidationException).
     * 2. Validates ReservationRoom and parent Reservation existence (404 ResourceNotFoundException).
     * 3. Under enforcement:
     *    - Verifies reservation property is in caller scope (404 ResourceNotFoundException).
     *    - Verifies caller holds RESERVATION_MANAGE on reservation property (403 AccessDeniedException).
     * 4. Validates Room existence (404 ResourceNotFoundException).
     * 5. Under enforcement:
     *    - Verifies room property is in caller scope (404 ResourceNotFoundException).
     *      Crucial anti-enumeration defense: a caller authorized for the reservation property but NOT the
     *      physical room's property receives 404 for the foreign room, never an informative 400 error.
     */
    public void validateAssignmentCreation(UUID reservationRoomId, UUID roomId) {
        if (reservationRoomId == null) {
            throw new BusinessValidationException("reservationRoomId must be provided");
        }
        if (roomId == null) {
            throw new BusinessValidationException("roomId must be provided");
        }

        ReservationRoom reservationRoom = reservationRoomRepository.findById(reservationRoomId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation room not found with id: " + reservationRoomId));

        Reservation reservation = reservationRepository.findById(reservationRoom.getReservationId())
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found with id: " + reservationRoom.getReservationId()));

        if (isEnforcementEnabled()) {
            if (!isPropertyInScope(reservation.getPropertyId())) {
                log.warn("Reservation room out of scope: Reservation [{}] belongs to property [{}] outside staff scope. Returning 404.",
                        reservation.getId(), reservation.getPropertyId());
                throw new ResourceNotFoundException("Reservation room not found with id: " + reservationRoomId);
            }

            if (!authorizationService.hasPropertyAccess(reservation.getPropertyId(), StaffOperation.RESERVATION_MANAGE)) {
                log.warn("Access denied: Staff principal lacks RESERVATION_MANAGE on property {} for reservation room {}",
                        reservation.getPropertyId(), reservationRoomId);
                throw new AccessDeniedException("Access is denied.");
            }
        }

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + roomId));

        if (isEnforcementEnabled()) {
            if (!isPropertyInScope(room.getPropertyId())) {
                log.warn("Room out of scope: Room [{}] belongs to property [{}] outside staff scope. Returning 404.",
                        roomId, room.getPropertyId());
                throw new ResourceNotFoundException("Room not found with id: " + roomId);
            }
        }
    }
}
