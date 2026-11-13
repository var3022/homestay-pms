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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationAuthorizationGuard Unit Tests")
class ReservationAuthorizationGuardUnitTest {

    @Mock
    private StaffAuthorizationService authorizationService;

    @Mock
    private PropertyRepository propertyRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationRoomRepository reservationRoomRepository;

    @Mock
    private RoomTypeRepository roomTypeRepository;

    private SecurityEnforcementProperties enabledProperties;
    private SecurityEnforcementProperties disabledProperties;

    private ReservationAuthorizationGuard enabledGuard;
    private ReservationAuthorizationGuard disabledGuard;

    private final UUID propertyIdA = UUID.randomUUID();
    private final UUID propertyIdB = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final UUID reservationRoomId = UUID.randomUUID();
    private final UUID roomTypeIdA = UUID.randomUUID();
    private final UUID roomTypeIdB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        enabledProperties = new SecurityEnforcementProperties();
        enabledProperties.setEnabled(true);

        disabledProperties = new SecurityEnforcementProperties();
        disabledProperties.setEnabled(false);

        enabledGuard = new ReservationAuthorizationGuard(
                authorizationService,
                enabledProperties,
                propertyRepository,
                reservationRepository,
                reservationRoomRepository,
                roomTypeRepository
        );

        disabledGuard = new ReservationAuthorizationGuard(
                authorizationService,
                disabledProperties,
                propertyRepository,
                reservationRepository,
                reservationRoomRepository,
                roomTypeRepository
        );
    }

    @Test
    @DisplayName("isEnforcementEnabled reflects configuration properties")
    void isEnforcementEnabled_reflectsConfiguration() {
        assertThat(enabledGuard.isEnforcementEnabled()).isTrue();
        assertThat(disabledGuard.isEnforcementEnabled()).isFalse();
    }

    @Test
    @DisplayName("Constructor rejects null SecurityEnforcementProperties to fail closed")
    void constructor_rejectsNullEnforcementProperties_failClosed() {
        assertThatThrownBy(() -> new ReservationAuthorizationGuard(
                authorizationService,
                null,
                propertyRepository,
                reservationRepository,
                reservationRoomRepository,
                roomTypeRepository
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("SecurityEnforcementProperties must not be null");
    }

    @Test
    @DisplayName("Constructor rejects null dependencies for fail-closed integrity")
    void constructor_rejectsNullDependencies() {
        assertThatThrownBy(() -> new ReservationAuthorizationGuard(
                null, enabledProperties, propertyRepository, reservationRepository, reservationRoomRepository, roomTypeRepository
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("StaffAuthorizationService must not be null");

        assertThatThrownBy(() -> new ReservationAuthorizationGuard(
                authorizationService, enabledProperties, null, reservationRepository, reservationRoomRepository, roomTypeRepository
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("PropertyRepository must not be null");

        assertThatThrownBy(() -> new ReservationAuthorizationGuard(
                authorizationService, enabledProperties, propertyRepository, null, reservationRoomRepository, roomTypeRepository
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("ReservationRepository must not be null");

        assertThatThrownBy(() -> new ReservationAuthorizationGuard(
                authorizationService, enabledProperties, propertyRepository, reservationRepository, null, roomTypeRepository
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("ReservationRoomRepository must not be null");

        assertThatThrownBy(() -> new ReservationAuthorizationGuard(
                authorizationService, enabledProperties, propertyRepository, reservationRepository, reservationRoomRepository, null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("RoomTypeRepository must not be null");
    }

    @Test
    @DisplayName("isGlobalSuperAdmin delegates to StaffAuthorizationService")
    void isGlobalSuperAdmin_delegates() {
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(true);
        assertThat(enabledGuard.isGlobalSuperAdmin()).isTrue();

        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        assertThat(enabledGuard.isGlobalSuperAdmin()).isFalse();
    }

    @Test
    @DisplayName("isPropertyInScope always returns true when enforcement is disabled")
    void isPropertyInScope_whenDisabled_alwaysTrue() {
        assertThat(disabledGuard.isPropertyInScope(propertyIdA)).isTrue();
        assertThat(disabledGuard.isPropertyInScope(null)).isTrue();
    }

    @Test
    @DisplayName("isPropertyInScope checks super admin or assigned property when enabled")
    void isPropertyInScope_whenEnabled_evaluatesService() {
        assertThat(enabledGuard.isPropertyInScope(null)).isFalse();

        when(authorizationService.isGlobalSuperAdmin()).thenReturn(true);
        assertThat(enabledGuard.isPropertyInScope(propertyIdA)).isTrue();

        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        assertThat(enabledGuard.isPropertyInScope(propertyIdA)).isTrue();

        when(authorizationService.isAssignedToProperty(propertyIdB)).thenReturn(false);
        assertThat(enabledGuard.isPropertyInScope(propertyIdB)).isFalse();
    }

    @Test
    @DisplayName("requirePropertyExistenceAndAccess returns 404 for nonexistent property")
    void requirePropertyExistenceAndAccess_nonexistent_throws404() {
        when(propertyRepository.existsById(propertyIdA)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requirePropertyExistenceAndAccess(propertyIdA, StaffOperation.RESERVATION_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Property not found with id: " + propertyIdA);
    }

    @Test
    @DisplayName("requirePropertyExistenceAndAccess returns 404 for out-of-scope property")
    void requirePropertyExistenceAndAccess_outOfScope_throws404() {
        when(propertyRepository.existsById(propertyIdA)).thenReturn(true);
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requirePropertyExistenceAndAccess(propertyIdA, StaffOperation.RESERVATION_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Property not found with id: " + propertyIdA);
    }

    @Test
    @DisplayName("requirePropertyExistenceAndAccess returns 403 when in scope but lacks operation permission")
    void requirePropertyExistenceAndAccess_inScopeLacksPermission_throws403() {
        when(propertyRepository.existsById(propertyIdA)).thenReturn(true);
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.RESERVATION_MANAGE)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requirePropertyExistenceAndAccess(propertyIdA, StaffOperation.RESERVATION_MANAGE))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access is denied.");
    }

    @Test
    @DisplayName("validateReservationCreation checks property existence and RESERVATION_MANAGE")
    void validateReservationCreation_checksPropertyAndManage() {
        assertThatThrownBy(() -> enabledGuard.validateReservationCreation(null))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessage("propertyId must be provided");

        when(propertyRepository.existsById(propertyIdA)).thenReturn(true);
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.RESERVATION_MANAGE)).thenReturn(true);

        enabledGuard.validateReservationCreation(propertyIdA);
        verify(authorizationService).hasPropertyAccess(propertyIdA, StaffOperation.RESERVATION_MANAGE);
    }

    @Test
    @DisplayName("resolveReservationPropertyId returns propertyId or throws 404")
    void resolveReservationPropertyId_behavior() {
        assertThatThrownBy(() -> enabledGuard.resolveReservationPropertyId(null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Reservation not found with id: null");

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> enabledGuard.resolveReservationPropertyId(reservationId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Reservation not found with id: " + reservationId);

        Reservation res = new Reservation();
        res.setPropertyId(propertyIdA);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(res));
        assertThat(enabledGuard.resolveReservationPropertyId(reservationId)).isEqualTo(propertyIdA);
    }

    @Test
    @DisplayName("requireReservationAccess returns 404 for out-of-scope reservation and 403 for insufficient permission")
    void requireReservationAccess_evaluatesScopeAndPermission() {
        // Disabled mode is no-op
        disabledGuard.requireReservationAccess(reservationId, StaffOperation.RESERVATION_READ);

        // Nonexistent reservation throws 404
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> enabledGuard.requireReservationAccess(reservationId, StaffOperation.RESERVATION_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Reservation not found with id: " + reservationId);

        Reservation res = new Reservation();
        res.setPropertyId(propertyIdA);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(res));

        // Out of scope throws 404
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.requireReservationAccess(reservationId, StaffOperation.RESERVATION_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Reservation not found with id: " + reservationId);

        // In scope but unauthorized throws 403
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.RESERVATION_MANAGE)).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.requireReservationAccess(reservationId, StaffOperation.RESERVATION_MANAGE))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access is denied.");

        // Authorized succeeds
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.RESERVATION_MANAGE)).thenReturn(true);
        enabledGuard.requireReservationAccess(reservationId, StaffOperation.RESERVATION_MANAGE);
    }

    @Test
    @DisplayName("validateReservationRoomCreation rejects cross-property room types even in dev mode")
    void validateReservationRoomCreation_crossPropertyRejected_evenInDevMode() {
        Reservation res = new Reservation();
        res.setPropertyId(propertyIdA);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(res));

        RoomType rtB = new RoomType();
        rtB.setPropertyId(propertyIdB);
        when(roomTypeRepository.findById(roomTypeIdB)).thenReturn(Optional.of(rtB));

        assertThatThrownBy(() -> disabledGuard.validateReservationRoomCreation(reservationId, roomTypeIdB))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessage("The selected room type does not belong to the reservation property");

        when(authorizationService.isGlobalSuperAdmin()).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.RESERVATION_MANAGE)).thenReturn(true);
        assertThatThrownBy(() -> enabledGuard.validateReservationRoomCreation(reservationId, roomTypeIdB))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessage("The selected room type does not belong to the reservation property");
    }

    @Test
    @DisplayName("validateReservationRoomCreation enforces scope and RESERVATION_MANAGE under enforcement")
    void validateReservationRoomCreation_enforcesScopeAndManage() {
        Reservation res = new Reservation();
        res.setPropertyId(propertyIdA);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(res));

        // Out of scope reservation returns 404
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.validateReservationRoomCreation(reservationId, roomTypeIdA))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Reservation not found with id: " + reservationId);

        // In scope but lacks RESERVATION_MANAGE returns 403
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.RESERVATION_MANAGE)).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.validateReservationRoomCreation(reservationId, roomTypeIdA))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access is denied.");

        // Authorized succeeds when room type belongs to property A
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.RESERVATION_MANAGE)).thenReturn(true);
        RoomType rtA = new RoomType();
        rtA.setPropertyId(propertyIdA);
        when(roomTypeRepository.findById(roomTypeIdA)).thenReturn(Optional.of(rtA));

        enabledGuard.validateReservationRoomCreation(reservationId, roomTypeIdA);
    }

    @Test
    @DisplayName("requireReservationRoomAccess resolves reservation_room -> reservation -> property and authorizes")
    void requireReservationRoomAccess_evaluatesScopeAndPermission() {
        // Disabled mode is no-op
        disabledGuard.requireReservationRoomAccess(reservationRoomId, StaffOperation.RESERVATION_READ);

        // Nonexistent reservation room throws 404
        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> enabledGuard.requireReservationRoomAccess(reservationRoomId, StaffOperation.RESERVATION_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Reservation room not found with id: " + reservationRoomId);

        ReservationRoom rr = new ReservationRoom();
        rr.setReservationId(reservationId);
        when(reservationRoomRepository.findById(reservationRoomId)).thenReturn(Optional.of(rr));

        // Nonexistent parent reservation throws 404
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> enabledGuard.requireReservationRoomAccess(reservationRoomId, StaffOperation.RESERVATION_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Reservation room not found with id: " + reservationRoomId);

        Reservation res = new Reservation();
        res.setPropertyId(propertyIdA);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(res));

        // Out of scope throws 404
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.requireReservationRoomAccess(reservationRoomId, StaffOperation.RESERVATION_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Reservation room not found with id: " + reservationRoomId);

        // In scope but unauthorized throws 403
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.RESERVATION_MANAGE)).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.requireReservationRoomAccess(reservationRoomId, StaffOperation.RESERVATION_MANAGE))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access is denied.");

        // Authorized succeeds
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.RESERVATION_READ)).thenReturn(true);
        enabledGuard.requireReservationRoomAccess(reservationRoomId, StaffOperation.RESERVATION_READ);
    }

    @Test
    @DisplayName("getAuthorizedPropertyIds returns filtered property IDs")
    void getAuthorizedPropertyIds_filtersByOperation() {
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.getAssignedPropertyIds()).thenReturn(Set.of(propertyIdA, propertyIdB));
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.RESERVATION_READ)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdB, StaffOperation.RESERVATION_READ)).thenReturn(false);

        Set<UUID> result = enabledGuard.getAuthorizedPropertyIds(StaffOperation.RESERVATION_READ);
        assertThat(result).containsExactly(propertyIdA);
    }
}
