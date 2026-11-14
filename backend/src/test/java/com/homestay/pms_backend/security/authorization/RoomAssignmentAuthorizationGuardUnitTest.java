package com.homestay.pms_backend.security.authorization;

import com.homestay.pms_backend.config.SecurityEnforcementProperties;
import com.homestay.pms_backend.entity.Reservation;
import com.homestay.pms_backend.entity.ReservationRoom;
import com.homestay.pms_backend.entity.Room;
import com.homestay.pms_backend.entity.RoomAssignment;
import com.homestay.pms_backend.enums.RoomAssignmentStatus;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.repository.ReservationRepository;
import com.homestay.pms_backend.repository.ReservationRoomRepository;
import com.homestay.pms_backend.repository.RoomAssignmentRepository;
import com.homestay.pms_backend.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RoomAssignmentAuthorizationGuard Unit Tests")
class RoomAssignmentAuthorizationGuardUnitTest {

    @Mock
    private StaffAuthorizationService authorizationService;

    @Mock
    private SecurityEnforcementProperties enforcementProperties;

    @Mock
    private RoomAssignmentRepository roomAssignmentRepository;

    @Mock
    private ReservationRoomRepository reservationRoomRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private PropertyRepository propertyRepository;

    private SecurityEnforcementProperties enabledProperties;
    private SecurityEnforcementProperties disabledProperties;

    private RoomAssignmentAuthorizationGuard enabledGuard;
    private RoomAssignmentAuthorizationGuard disabledGuard;

    private final UUID propertyIdA = UUID.randomUUID();
    private final UUID propertyIdB = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final UUID reservationRoomId = UUID.randomUUID();
    private final UUID roomId = UUID.randomUUID();
    private final UUID assignmentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        enabledProperties = new SecurityEnforcementProperties();
        enabledProperties.setEnabled(true);

        disabledProperties = new SecurityEnforcementProperties();
        disabledProperties.setEnabled(false);

        enabledGuard = new RoomAssignmentAuthorizationGuard(
                authorizationService,
                enabledProperties,
                roomAssignmentRepository,
                reservationRoomRepository,
                reservationRepository,
                roomRepository,
                propertyRepository
        );

        disabledGuard = new RoomAssignmentAuthorizationGuard(
                authorizationService,
                disabledProperties,
                roomAssignmentRepository,
                reservationRoomRepository,
                reservationRepository,
                roomRepository,
                propertyRepository
        );
    }

    // =========================================================================
    // 1. Constructor and Fail-Closed Initialization
    // =========================================================================

    @Test
    @DisplayName("Constructor rejects null StaffAuthorizationService")
    void constructor_rejectsNullAuthorizationService() {
        assertThatThrownBy(() -> new RoomAssignmentAuthorizationGuard(
                null, enabledProperties, roomAssignmentRepository, reservationRoomRepository, reservationRepository, roomRepository, propertyRepository
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("StaffAuthorizationService must not be null");
    }

    @Test
    @DisplayName("Constructor rejects null SecurityEnforcementProperties")
    void constructor_rejectsNullEnforcementProperties() {
        assertThatThrownBy(() -> new RoomAssignmentAuthorizationGuard(
                authorizationService, null, roomAssignmentRepository, reservationRoomRepository, reservationRepository, roomRepository, propertyRepository
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("SecurityEnforcementProperties must not be null");
    }

    @Test
    @DisplayName("Constructor rejects null RoomAssignmentRepository")
    void constructor_rejectsNullRoomAssignmentRepository() {
        assertThatThrownBy(() -> new RoomAssignmentAuthorizationGuard(
                authorizationService, enabledProperties, null, reservationRoomRepository, reservationRepository, roomRepository, propertyRepository
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("RoomAssignmentRepository must not be null");
    }

    @Test
    @DisplayName("Constructor rejects null ReservationRoomRepository")
    void constructor_rejectsNullReservationRoomRepository() {
        assertThatThrownBy(() -> new RoomAssignmentAuthorizationGuard(
                authorizationService, enabledProperties, roomAssignmentRepository, null, reservationRepository, roomRepository, propertyRepository
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("ReservationRoomRepository must not be null");
    }

    @Test
    @DisplayName("Constructor rejects null ReservationRepository")
    void constructor_rejectsNullReservationRepository() {
        assertThatThrownBy(() -> new RoomAssignmentAuthorizationGuard(
                authorizationService, enabledProperties, roomAssignmentRepository, reservationRoomRepository, null, roomRepository, propertyRepository
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("ReservationRepository must not be null");
    }

    @Test
    @DisplayName("Constructor rejects null RoomRepository")
    void constructor_rejectsNullRoomRepository() {
        assertThatThrownBy(() -> new RoomAssignmentAuthorizationGuard(
                authorizationService, enabledProperties, roomAssignmentRepository, reservationRoomRepository, reservationRepository, null, propertyRepository
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("RoomRepository must not be null");
    }

    @Test
    @DisplayName("Constructor rejects null PropertyRepository")
    void constructor_rejectsNullPropertyRepository() {
        assertThatThrownBy(() -> new RoomAssignmentAuthorizationGuard(
                authorizationService, enabledProperties, roomAssignmentRepository, reservationRoomRepository, reservationRepository, roomRepository, null
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("PropertyRepository must not be null");
    }

    // =========================================================================
    // 2. Enforcement & Property Scoping
    // =========================================================================

    @Test
    @DisplayName("isEnforcementEnabled reflects configuration properties")
    void isEnforcementEnabled_reflectsConfiguration() {
        assertThat(enabledGuard.isEnforcementEnabled()).isTrue();
        assertThat(disabledGuard.isEnforcementEnabled()).isFalse();
    }

    @Test
    @DisplayName("isGlobalSuperAdmin delegates to authorizationService")
    void isGlobalSuperAdmin_delegates() {
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(true);
        assertThat(enabledGuard.isGlobalSuperAdmin()).isTrue();

        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        assertThat(enabledGuard.isGlobalSuperAdmin()).isFalse();
    }

    @Test
    @DisplayName("isPropertyInScope returns true when enforcement disabled")
    void isPropertyInScope_whenDisabled_returnsTrue() {
        assertThat(disabledGuard.isPropertyInScope(propertyIdA)).isTrue();
        assertThat(disabledGuard.isPropertyInScope(null)).isTrue();
    }

    @Test
    @DisplayName("isPropertyInScope returns false for null propertyId when enabled")
    void isPropertyInScope_nullPropertyId_returnsFalse() {
        assertThat(enabledGuard.isPropertyInScope(null)).isFalse();
    }

    @Test
    @DisplayName("isPropertyInScope returns true for SUPER_ADMIN or assigned staff")
    void isPropertyInScope_superAdminOrAssigned_returnsTrue() {
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(true);
        assertThat(enabledGuard.isPropertyInScope(propertyIdA)).isTrue();

        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        assertThat(enabledGuard.isPropertyInScope(propertyIdA)).isTrue();

        when(authorizationService.isAssignedToProperty(propertyIdB)).thenReturn(false);
        assertThat(enabledGuard.isPropertyInScope(propertyIdB)).isFalse();
    }

    // =========================================================================
    // 3. Property Resolution
    // =========================================================================

    @Test
    @DisplayName("resolveAssignmentPropertyId resolves property from assignment -> reservation room -> reservation")
    void resolveAssignmentPropertyId_resolvesAuthoritativeProperty() {
        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(assignmentId);
        assignment.setReservationRoomId(reservationRoomId);
        assignment.setRoomId(roomId);

        ReservationRoom rr = new ReservationRoom();
        rr.setId(reservationRoomId);
        rr.setReservationId(reservationId);

        Reservation r = new Reservation();
        r.setId(reservationId);
        r.setPropertyId(propertyIdA);

        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyIdA);

        when(roomAssignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(rr));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(r));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        assertThat(enabledGuard.resolveAssignmentPropertyId(assignmentId)).isEqualTo(propertyIdA);
    }

    @Test
    @DisplayName("resolveAssignmentPropertyId throws 404 when physical room belongs to different property")
    void resolveAssignmentPropertyId_mismatchedPhysicalRoomProperty_throws404() {
        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(assignmentId);
        assignment.setReservationRoomId(reservationRoomId);
        assignment.setRoomId(roomId);

        ReservationRoom rr = new ReservationRoom();
        rr.setId(reservationRoomId);
        rr.setReservationId(reservationId);

        Reservation r = new Reservation();
        r.setId(reservationId);
        r.setPropertyId(propertyIdA);

        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyIdB); // mismatched!

        when(roomAssignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(rr));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(r));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> enabledGuard.resolveAssignmentPropertyId(assignmentId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Room assignment not found with id: " + assignmentId);
    }

    @Test
    @DisplayName("resolveAssignmentPropertyId throws 404 for nonexistent resources")
    void resolveAssignmentPropertyId_throws404WhenNotFound() {
        assertThatThrownBy(() -> enabledGuard.resolveAssignmentPropertyId(null))
                .isInstanceOf(ResourceNotFoundException.class);

        when(roomAssignmentRepository.findById(assignmentId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> enabledGuard.resolveAssignmentPropertyId(assignmentId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("resolveReservationRoomPropertyId resolves property through reservation")
    void resolveReservationRoomPropertyId_resolvesAuthoritativeProperty() {
        ReservationRoom rr = new ReservationRoom();
        rr.setId(reservationRoomId);
        rr.setReservationId(reservationId);

        Reservation r = new Reservation();
        r.setId(reservationId);
        r.setPropertyId(propertyIdA);

        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(rr));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(r));

        assertThat(enabledGuard.resolveReservationRoomPropertyId(reservationRoomId)).isEqualTo(propertyIdA);
    }

    @Test
    @DisplayName("resolveRoomPropertyId resolves property directly from room")
    void resolveRoomPropertyId_resolvesAuthoritativeProperty() {
        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyIdA);

        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        assertThat(enabledGuard.resolveRoomPropertyId(roomId)).isEqualTo(propertyIdA);
    }

    // =========================================================================
    // 4. requireAssignmentAccess
    // =========================================================================

    @Test
    @DisplayName("requireAssignmentAccess passes in disabled mode when assignment exists")
    void requireAssignmentAccess_disabledMode_passesWhenExists() {
        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(assignmentId);
        when(roomAssignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));

        disabledGuard.requireAssignmentAccess(assignmentId, StaffOperation.RESERVATION_READ);
        verifyNoInteractions(authorizationService);
    }

    @Test
    @DisplayName("requireAssignmentAccess throws uniform 404 for out-of-scope assignment")
    void requireAssignmentAccess_outOfScope_returns404() {
        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(assignmentId);
        assignment.setReservationRoomId(reservationRoomId);
        assignment.setRoomId(roomId);

        ReservationRoom rr = new ReservationRoom();
        rr.setId(reservationRoomId);
        rr.setReservationId(reservationId);

        Reservation r = new Reservation();
        r.setId(reservationId);
        r.setPropertyId(propertyIdA);

        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyIdA);

        when(roomAssignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(rr));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(r));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requireAssignmentAccess(assignmentId, StaffOperation.RESERVATION_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Room assignment not found with id: " + assignmentId);
    }

    @Test
    @DisplayName("requireAssignmentAccess throws 404 when physical room belongs to different property")
    void requireAssignmentAccess_mismatchedPhysicalRoomProperty_throws404() {
        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(assignmentId);
        assignment.setReservationRoomId(reservationRoomId);
        assignment.setRoomId(roomId);

        ReservationRoom rr = new ReservationRoom();
        rr.setId(reservationRoomId);
        rr.setReservationId(reservationId);

        Reservation r = new Reservation();
        r.setId(reservationId);
        r.setPropertyId(propertyIdA);

        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyIdB); // mismatched!

        when(roomAssignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(rr));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(r));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> enabledGuard.requireAssignmentAccess(assignmentId, StaffOperation.RESERVATION_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Room assignment not found with id: " + assignmentId);

        verify(authorizationService, never()).hasPropertyAccess(any(), any());
    }

    @Test
    @DisplayName("requireAssignmentAccess throws 403 when caller is in scope but lacks operation")
    void requireAssignmentAccess_inScopeLackingOperation_throws403() {
        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(assignmentId);
        assignment.setReservationRoomId(reservationRoomId);
        assignment.setRoomId(roomId);

        ReservationRoom rr = new ReservationRoom();
        rr.setId(reservationRoomId);
        rr.setReservationId(reservationId);

        Reservation r = new Reservation();
        r.setId(reservationId);
        r.setPropertyId(propertyIdA);

        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyIdA);

        when(roomAssignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(rr));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(r));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.RESERVATION_MANAGE)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requireAssignmentAccess(assignmentId, StaffOperation.RESERVATION_MANAGE))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access is denied.");
    }

    @Test
    @DisplayName("requireAssignmentAccess succeeds when caller is in scope and authorized")
    void requireAssignmentAccess_authorized_succeeds() {
        RoomAssignment assignment = new RoomAssignment();
        assignment.setId(assignmentId);
        assignment.setReservationRoomId(reservationRoomId);
        assignment.setRoomId(roomId);

        ReservationRoom rr = new ReservationRoom();
        rr.setId(reservationRoomId);
        rr.setReservationId(reservationId);

        Reservation r = new Reservation();
        r.setId(reservationId);
        r.setPropertyId(propertyIdA);

        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyIdA);

        when(roomAssignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(rr));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(r));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.RESERVATION_READ)).thenReturn(true);

        enabledGuard.requireAssignmentAccess(assignmentId, StaffOperation.RESERVATION_READ);
    }

    // =========================================================================
    // 5. requireReservationRoomAccess
    // =========================================================================

    @Test
    @DisplayName("requireReservationRoomAccess throws uniform 404 when out-of-scope")
    void requireReservationRoomAccess_outOfScope_returns404() {
        ReservationRoom rr = new ReservationRoom();
        rr.setId(reservationRoomId);
        rr.setReservationId(reservationId);

        Reservation r = new Reservation();
        r.setId(reservationId);
        r.setPropertyId(propertyIdA);

        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(rr));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(r));

        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requireReservationRoomAccess(reservationRoomId, StaffOperation.RESERVATION_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Reservation room not found with id: " + reservationRoomId);
    }

    @Test
    @DisplayName("requireReservationRoomAccess throws 403 when in-scope lacking operation")
    void requireReservationRoomAccess_inScopeLackingOperation_throws403() {
        ReservationRoom rr = new ReservationRoom();
        rr.setId(reservationRoomId);
        rr.setReservationId(reservationId);

        Reservation r = new Reservation();
        r.setId(reservationId);
        r.setPropertyId(propertyIdA);

        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(rr));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(r));

        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.RESERVATION_READ)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requireReservationRoomAccess(reservationRoomId, StaffOperation.RESERVATION_READ))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access is denied.");
    }

    // =========================================================================
    // 6. requireRoomAccess
    // =========================================================================

    @Test
    @DisplayName("requireRoomAccess throws uniform 404 when out-of-scope")
    void requireRoomAccess_outOfScope_returns404() {
        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyIdA);

        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requireRoomAccess(roomId, StaffOperation.RESERVATION_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Room not found with id: " + roomId);
    }

    @Test
    @DisplayName("requireRoomAccess throws 403 when in-scope lacking operation")
    void requireRoomAccess_inScopeLackingOperation_throws403() {
        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyIdA);

        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.RESERVATION_READ)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requireRoomAccess(roomId, StaffOperation.RESERVATION_READ))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access is denied.");
    }

    // =========================================================================
    // 7. validateAssignmentCreation
    // =========================================================================

    @Test
    @DisplayName("validateAssignmentCreation rejects null parameters with 400")
    void validateAssignmentCreation_nullParameters_throws400() {
        assertThatThrownBy(() -> enabledGuard.validateAssignmentCreation(null, roomId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("reservationRoomId must be provided");

        assertThatThrownBy(() -> enabledGuard.validateAssignmentCreation(reservationRoomId, null))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("roomId must be provided");
    }

    @Test
    @DisplayName("validateAssignmentCreation throws uniform 404 when reservation room is out of scope")
    void validateAssignmentCreation_reservationRoomOutOfScope_returns404() {
        ReservationRoom rr = new ReservationRoom();
        rr.setId(reservationRoomId);
        rr.setReservationId(reservationId);

        Reservation r = new Reservation();
        r.setId(reservationId);
        r.setPropertyId(propertyIdA);

        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(rr));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(r));

        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.validateAssignmentCreation(reservationRoomId, roomId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Reservation room not found with id: " + reservationRoomId);
    }

    @Test
    @DisplayName("validateAssignmentCreation throws 403 when caller lacks RESERVATION_MANAGE on reservation property")
    void validateAssignmentCreation_lackingReservationManage_throws403() {
        ReservationRoom rr = new ReservationRoom();
        rr.setId(reservationRoomId);
        rr.setReservationId(reservationId);

        Reservation r = new Reservation();
        r.setId(reservationId);
        r.setPropertyId(propertyIdA);

        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(rr));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(r));

        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.RESERVATION_MANAGE)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.validateAssignmentCreation(reservationRoomId, roomId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access is denied.");
    }

    @Test
    @DisplayName("validateAssignmentCreation throws 404 for physical room when room property is out of scope (anti-enumeration)")
    void validateAssignmentCreation_roomOutOfScope_returns404ForRoom() {
        ReservationRoom rr = new ReservationRoom();
        rr.setId(reservationRoomId);
        rr.setReservationId(reservationId);

        Reservation r = new Reservation();
        r.setId(reservationId);
        r.setPropertyId(propertyIdA);

        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyIdB); // Room belongs to Property B

        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(rr));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(r));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.RESERVATION_MANAGE)).thenReturn(true);

        // Caller is NOT assigned to Property B
        when(authorizationService.isAssignedToProperty(propertyIdB)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.validateAssignmentCreation(reservationRoomId, roomId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Room not found with id: " + roomId);
    }

    @Test
    @DisplayName("validateAssignmentCreation succeeds when both resources are in scope and authorized")
    void validateAssignmentCreation_authorized_succeeds() {
        ReservationRoom rr = new ReservationRoom();
        rr.setId(reservationRoomId);
        rr.setReservationId(reservationId);

        Reservation r = new Reservation();
        r.setId(reservationId);
        r.setPropertyId(propertyIdA);

        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyIdA);

        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(rr));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(r));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.RESERVATION_MANAGE)).thenReturn(true);

        enabledGuard.validateAssignmentCreation(reservationRoomId, roomId);
    }
}
