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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PropertyAuthorizationGuard Unit Tests")
class PropertyAuthorizationGuardUnitTest {

    @Mock
    private StaffAuthorizationService authorizationService;

    @Mock
    private PropertyRepository propertyRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private RoomTypeRepository roomTypeRepository;

    @Mock
    private RoomBedRepository roomBedRepository;

    @Mock
    private BedTypeRepository bedTypeRepository;

    @Mock
    private AmenityRepository amenityRepository;

    private SecurityEnforcementProperties enabledProperties;
    private SecurityEnforcementProperties disabledProperties;

    private PropertyAuthorizationGuard enabledGuard;
    private PropertyAuthorizationGuard disabledGuard;

    private final UUID propertyIdA = UUID.randomUUID();
    private final UUID propertyIdB = UUID.randomUUID();
    private final UUID roomId = UUID.randomUUID();
    private final UUID roomTypeId = UUID.randomUUID();
    private final UUID roomBedId = UUID.randomUUID();
    private final UUID bedTypeId = UUID.randomUUID();
    private final UUID amenityId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        enabledProperties = new SecurityEnforcementProperties();
        enabledProperties.setEnabled(true);

        disabledProperties = new SecurityEnforcementProperties();
        disabledProperties.setEnabled(false);

        enabledGuard = new PropertyAuthorizationGuard(
                authorizationService,
                enabledProperties,
                propertyRepository,
                roomRepository,
                roomTypeRepository,
                roomBedRepository,
                bedTypeRepository,
                amenityRepository
        );

        disabledGuard = new PropertyAuthorizationGuard(
                authorizationService,
                disabledProperties,
                propertyRepository,
                roomRepository,
                roomTypeRepository,
                roomBedRepository,
                bedTypeRepository,
                amenityRepository
        );
    }

    @Test
    @DisplayName("isEnforcementEnabled reflects configuration properties")
    void isEnforcementEnabled_reflectsConfiguration() {
        assertThat(enabledGuard.isEnforcementEnabled()).isTrue();
        assertThat(disabledGuard.isEnforcementEnabled()).isFalse();
    }

    @Test
    @DisplayName("Constructor rejects null SecurityEnforcementProperties to fail closed on missing configuration")
    void constructor_rejectsNullEnforcementProperties_failClosed() {
        assertThatThrownBy(() -> new PropertyAuthorizationGuard(
                authorizationService,
                null,
                propertyRepository,
                roomRepository,
                roomTypeRepository,
                roomBedRepository,
                bedTypeRepository,
                amenityRepository
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("SecurityEnforcementProperties must not be null");
    }

    @Test
    @DisplayName("Constructor rejects null required dependencies to guarantee fail-closed integrity")
    void constructor_rejectsNullDependencies() {
        assertThatThrownBy(() -> new PropertyAuthorizationGuard(
                null, enabledProperties, propertyRepository, roomRepository, roomTypeRepository,
                roomBedRepository, bedTypeRepository, amenityRepository
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("StaffAuthorizationService must not be null");

        assertThatThrownBy(() -> new PropertyAuthorizationGuard(
                authorizationService, enabledProperties, null, roomRepository, roomTypeRepository,
                roomBedRepository, bedTypeRepository, amenityRepository
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("PropertyRepository must not be null");

        assertThatThrownBy(() -> new PropertyAuthorizationGuard(
                authorizationService, enabledProperties, propertyRepository, null, roomTypeRepository,
                roomBedRepository, bedTypeRepository, amenityRepository
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("RoomRepository must not be null");

        assertThatThrownBy(() -> new PropertyAuthorizationGuard(
                authorizationService, enabledProperties, propertyRepository, roomRepository, null,
                roomBedRepository, bedTypeRepository, amenityRepository
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("RoomTypeRepository must not be null");

        assertThatThrownBy(() -> new PropertyAuthorizationGuard(
                authorizationService, enabledProperties, propertyRepository, roomRepository, roomTypeRepository,
                null, bedTypeRepository, amenityRepository
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("RoomBedRepository must not be null");

        assertThatThrownBy(() -> new PropertyAuthorizationGuard(
                authorizationService, enabledProperties, propertyRepository, roomRepository, roomTypeRepository,
                roomBedRepository, null, amenityRepository
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("BedTypeRepository must not be null");

        assertThatThrownBy(() -> new PropertyAuthorizationGuard(
                authorizationService, enabledProperties, propertyRepository, roomRepository, roomTypeRepository,
                roomBedRepository, bedTypeRepository, null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("AmenityRepository must not be null");
    }

    @Test
    @DisplayName("isPropertyInScope behaves correctly across enforcement modes and roles")
    void isPropertyInScope_behavior() {
        // Disabled mode -> always in scope
        assertThat(disabledGuard.isPropertyInScope(propertyIdA)).isTrue();
        assertThat(disabledGuard.isPropertyInScope(null)).isTrue();

        // Enabled mode: null propertyId -> false
        assertThat(enabledGuard.isPropertyInScope(null)).isFalse();

        // Enabled mode: assigned property -> true
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        assertThat(enabledGuard.isPropertyInScope(propertyIdA)).isTrue();

        // Enabled mode: unassigned property -> false
        when(authorizationService.isAssignedToProperty(propertyIdB)).thenReturn(false);
        assertThat(enabledGuard.isPropertyInScope(propertyIdB)).isFalse();

        // Enabled mode: global SUPER_ADMIN -> true even if not explicitly assigned
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(true);
        assertThat(enabledGuard.isPropertyInScope(propertyIdB)).isTrue();
    }

    @Test
    @DisplayName("requireGlobalSuperAdmin allows super admin and denies non-super-admin")
    void requireGlobalSuperAdmin_enforcement() {
        // Disabled enforcement bypasses check
        disabledGuard.requireGlobalSuperAdmin();
        verifyNoInteractions(authorizationService);

        // Enabled enforcement - super admin allowed
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(true);
        enabledGuard.requireGlobalSuperAdmin();

        // Enabled enforcement - non-super-admin denied
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.requireGlobalSuperAdmin())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access is denied.");
    }

    @Test
    @DisplayName("requirePropertyExistenceAndSuperAdmin enforces 403 first to prevent existence probing")
    void requirePropertyExistenceAndSuperAdmin_precedence() {
        // Disabled mode bypasses
        disabledGuard.requirePropertyExistenceAndSuperAdmin(propertyIdA);
        verifyNoInteractions(propertyRepository);

        // Non-super-admin receives 403 immediately without hitting repository
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.requirePropertyExistenceAndSuperAdmin(propertyIdA))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access is denied.");
        verifyNoInteractions(propertyRepository);

        // Super-admin with non-existent property -> 404
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(true);
        when(propertyRepository.existsById(propertyIdA)).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.requirePropertyExistenceAndSuperAdmin(propertyIdA))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(propertyIdA.toString());

        // Super-admin with existing property -> success
        when(propertyRepository.existsById(propertyIdA)).thenReturn(true);
        enabledGuard.requirePropertyExistenceAndSuperAdmin(propertyIdA);
    }

    @Test
    @DisplayName("requirePropertyAccess verifies authorization service or fails closed")
    void requirePropertyAccess_behavior() {
        // Disabled mode bypasses
        disabledGuard.requirePropertyAccess(propertyIdA, StaffOperation.PROPERTY_READ);
        verifyNoInteractions(authorizationService);

        // Null propertyId -> 403
        assertThatThrownBy(() -> enabledGuard.requirePropertyAccess(null, StaffOperation.PROPERTY_READ))
                .isInstanceOf(AccessDeniedException.class);

        // Denied access -> 403
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.PROPERTY_READ)).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.requirePropertyAccess(propertyIdA, StaffOperation.PROPERTY_READ))
                .isInstanceOf(AccessDeniedException.class);

        // Granted access -> success
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.PROPERTY_READ)).thenReturn(true);
        enabledGuard.requirePropertyAccess(propertyIdA, StaffOperation.PROPERTY_READ);
    }

    @Test
    @DisplayName("requirePropertyExistenceAndAccess returns 404 for nonexistent OR out-of-scope properties")
    void requirePropertyExistenceAndAccess_precedence() {
        // Disabled mode bypasses
        disabledGuard.requirePropertyExistenceAndAccess(propertyIdA, StaffOperation.PROPERTY_READ);
        verifyNoInteractions(propertyRepository);

        // Not found in DB -> 404
        when(propertyRepository.existsById(propertyIdA)).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.requirePropertyExistenceAndAccess(propertyIdA, StaffOperation.PROPERTY_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Property not found with id: " + propertyIdA);

        // Found in DB, but out-of-scope -> 404 (indistinguishable from nonexistent)
        when(propertyRepository.existsById(propertyIdA)).thenReturn(true);
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.requirePropertyExistenceAndAccess(propertyIdA, StaffOperation.PROPERTY_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Property not found with id: " + propertyIdA);

        // Found in DB, in scope, but lacking permission -> 403
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.PROPERTY_READ)).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.requirePropertyExistenceAndAccess(propertyIdA, StaffOperation.PROPERTY_READ))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access is denied.");

        // Found in DB, in scope, authorized -> success
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.PROPERTY_READ)).thenReturn(true);
        enabledGuard.requirePropertyExistenceAndAccess(propertyIdA, StaffOperation.PROPERTY_READ);
    }

    @Test
    @DisplayName("resolveRoomPropertyId resolves authoritative propertyId or throws 404")
    void resolveRoomPropertyId_behavior() {
        // Null roomId -> 404
        assertThatThrownBy(() -> enabledGuard.resolveRoomPropertyId(null))
                .isInstanceOf(ResourceNotFoundException.class);

        // Room not found -> 404
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> enabledGuard.resolveRoomPropertyId(roomId))
                .isInstanceOf(ResourceNotFoundException.class);

        // Room found -> returns propertyId
        Room room = new Room();
        room.setId(roomId);
        room.setPropertyId(propertyIdA);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        UUID resolved = enabledGuard.resolveRoomPropertyId(roomId);
        assertThat(resolved).isEqualTo(propertyIdA);
    }

    @Test
    @DisplayName("requireRoomAccess returns 404 for nonexistent OR out-of-scope rooms, 403 for permission denial")
    void requireRoomAccess_behavior() {
        // Disabled mode bypasses
        disabledGuard.requireRoomAccess(roomId, StaffOperation.ROOM_READ);
        verifyNoInteractions(roomRepository);

        // Room not found -> 404
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> enabledGuard.requireRoomAccess(roomId, StaffOperation.ROOM_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Room not found with id: " + roomId);

        // Room found, but belongs to out-of-scope property -> 404 (indistinguishable from nonexistent)
        Room roomB = new Room();
        roomB.setId(roomId);
        roomB.setPropertyId(propertyIdB);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(roomB));
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdB)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requireRoomAccess(roomId, StaffOperation.ROOM_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Room not found with id: " + roomId);

        // Room found, property in scope, but lacking operation permission -> 403
        Room roomA = new Room();
        roomA.setId(roomId);
        roomA.setPropertyId(propertyIdA);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(roomA));
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.ROOM_READ)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requireRoomAccess(roomId, StaffOperation.ROOM_READ))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access is denied.");

        // Room found, property in scope, authorized -> success
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.ROOM_READ)).thenReturn(true);
        enabledGuard.requireRoomAccess(roomId, StaffOperation.ROOM_READ);
    }

    @Test
    @DisplayName("resolveRoomTypePropertyId resolves authoritative propertyId or throws 404")
    void resolveRoomTypePropertyId_behavior() {
        // Null roomTypeId -> 404
        assertThatThrownBy(() -> enabledGuard.resolveRoomTypePropertyId(null))
                .isInstanceOf(ResourceNotFoundException.class);

        // RoomType not found -> 404
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> enabledGuard.resolveRoomTypePropertyId(roomTypeId))
                .isInstanceOf(ResourceNotFoundException.class);

        // RoomType found -> returns propertyId
        RoomType roomType = new RoomType();
        roomType.setId(roomTypeId);
        roomType.setPropertyId(propertyIdA);
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(roomType));

        UUID resolved = enabledGuard.resolveRoomTypePropertyId(roomTypeId);
        assertThat(resolved).isEqualTo(propertyIdA);
    }

    @Test
    @DisplayName("requireRoomTypeAccess returns 404 for nonexistent OR out-of-scope room types, 403 for permission denial")
    void requireRoomTypeAccess_behavior() {
        // Disabled mode bypasses
        disabledGuard.requireRoomTypeAccess(roomTypeId, StaffOperation.ROOM_READ);
        verifyNoInteractions(roomTypeRepository);

        // RoomType not found -> 404
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> enabledGuard.requireRoomTypeAccess(roomTypeId, StaffOperation.ROOM_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Room type not found with id: " + roomTypeId);

        // RoomType found, but out of scope -> 404 (indistinguishable from nonexistent)
        RoomType roomTypeB = new RoomType();
        roomTypeB.setId(roomTypeId);
        roomTypeB.setPropertyId(propertyIdB);
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(roomTypeB));
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdB)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requireRoomTypeAccess(roomTypeId, StaffOperation.ROOM_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Room type not found with id: " + roomTypeId);

        // RoomType found, in scope, but lacking permission -> 403
        RoomType roomTypeA = new RoomType();
        roomTypeA.setId(roomTypeId);
        roomTypeA.setPropertyId(propertyIdA);
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(roomTypeA));
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.ROOM_READ)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requireRoomTypeAccess(roomTypeId, StaffOperation.ROOM_READ))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access is denied.");

        // RoomType found, in scope, authorized -> success
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.ROOM_READ)).thenReturn(true);
        enabledGuard.requireRoomTypeAccess(roomTypeId, StaffOperation.ROOM_READ);
    }

    @Test
    @DisplayName("validateRoomCreation enforces cross-property mismatch even when enforcement is disabled")
    void validateRoomCreation_enforcesBusinessValidationWhenDisabled() {
        // Property exists
        when(propertyRepository.existsById(propertyIdA)).thenReturn(true);

        // Cross-property room type
        RoomType crossRoomType = new RoomType();
        crossRoomType.setId(roomTypeId);
        crossRoomType.setPropertyId(propertyIdB);
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(crossRoomType));

        // When enforcement is DISABLED, cross-property mismatch is STILL rejected with 400
        assertThatThrownBy(() -> disabledGuard.validateRoomCreation(propertyIdA, roomTypeId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessage("Room type does not belong to the specified property");

        // Matching room type succeeds in disabled mode without requiring RBAC check
        RoomType matchingRoomType = new RoomType();
        matchingRoomType.setId(roomTypeId);
        matchingRoomType.setPropertyId(propertyIdA);
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(matchingRoomType));

        disabledGuard.validateRoomCreation(propertyIdA, roomTypeId);
        verifyNoInteractions(authorizationService);
    }

    @Test
    @DisplayName("validateRoomCreation enforces existence, scope, cross-property mismatch, and authorization")
    void validateRoomCreation_fullInvariants() {
        // Null arguments -> 400
        assertThatThrownBy(() -> enabledGuard.validateRoomCreation(null, roomTypeId))
                .isInstanceOf(BusinessValidationException.class);
        assertThatThrownBy(() -> enabledGuard.validateRoomCreation(propertyIdA, null))
                .isInstanceOf(BusinessValidationException.class);

        // Property not found -> 404
        when(propertyRepository.existsById(propertyIdA)).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.validateRoomCreation(propertyIdA, roomTypeId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Property not found with id: " + propertyIdA);

        // Property exists in DB, but outside caller's scope -> 404
        when(propertyRepository.existsById(propertyIdA)).thenReturn(true);
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.validateRoomCreation(propertyIdA, roomTypeId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Property not found with id: " + propertyIdA);

        // Property exists and in scope, roomType not found -> 404
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> enabledGuard.validateRoomCreation(propertyIdA, roomTypeId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Room type not found with id: " + roomTypeId);

        // RoomType belongs to property B, requested for property A -> 400 cross-property mismatch
        RoomType crossRoomType = new RoomType();
        crossRoomType.setId(roomTypeId);
        crossRoomType.setPropertyId(propertyIdB);
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(crossRoomType));

        assertThatThrownBy(() -> enabledGuard.validateRoomCreation(propertyIdA, roomTypeId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessage("Room type does not belong to the specified property");

        // RoomType matches property A, but caller lacks ROOM_MANAGE -> 403
        RoomType matchingRoomType = new RoomType();
        matchingRoomType.setId(roomTypeId);
        matchingRoomType.setPropertyId(propertyIdA);
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(matchingRoomType));
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.ROOM_MANAGE)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.validateRoomCreation(propertyIdA, roomTypeId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access is denied.");

        // RoomType matches and authorized -> success
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.ROOM_MANAGE)).thenReturn(true);
        enabledGuard.validateRoomCreation(propertyIdA, roomTypeId);
    }

    @Test
    @DisplayName("validateRoomQuery enforces cross-property mismatch even when enforcement is disabled")
    void validateRoomQuery_enforcesBusinessValidationWhenDisabled() {
        // Property exists
        when(propertyRepository.existsById(propertyIdA)).thenReturn(true);

        // Cross-property room type
        RoomType crossRoomType = new RoomType();
        crossRoomType.setId(roomTypeId);
        crossRoomType.setPropertyId(propertyIdB);
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(crossRoomType));

        // When enforcement is DISABLED, cross-property mismatch is STILL rejected with 400
        assertThatThrownBy(() -> disabledGuard.validateRoomQuery(propertyIdA, roomTypeId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessage("Room type does not belong to the specified property");

        // Matching room type succeeds in disabled mode without requiring RBAC check
        RoomType matchingRoomType = new RoomType();
        matchingRoomType.setId(roomTypeId);
        matchingRoomType.setPropertyId(propertyIdA);
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(matchingRoomType));

        disabledGuard.validateRoomQuery(propertyIdA, roomTypeId);
        verifyNoInteractions(authorizationService);
    }

    @Test
    @DisplayName("validateRoomQuery checks existence, scope, cross-property mismatch, and authorization")
    void validateRoomQuery_behavior() {
        // Null parameters bypass check
        enabledGuard.validateRoomQuery(null, roomTypeId);
        enabledGuard.validateRoomQuery(propertyIdA, null);

        // Property not found -> 404
        when(propertyRepository.existsById(propertyIdA)).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.validateRoomQuery(propertyIdA, roomTypeId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Property not found with id: " + propertyIdA);

        // Property exists, but out of scope -> 404
        when(propertyRepository.existsById(propertyIdA)).thenReturn(true);
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.validateRoomQuery(propertyIdA, roomTypeId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Property not found with id: " + propertyIdA);

        // Property in scope, roomType not found -> 404
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> enabledGuard.validateRoomQuery(propertyIdA, roomTypeId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Room type not found with id: " + roomTypeId);

        // RoomType belongs to property B, requested for property A -> 400
        RoomType crossRoomType = new RoomType();
        crossRoomType.setId(roomTypeId);
        crossRoomType.setPropertyId(propertyIdB);
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(crossRoomType));

        assertThatThrownBy(() -> enabledGuard.validateRoomQuery(propertyIdA, roomTypeId))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessage("Room type does not belong to the specified property");

        // Matching property, exists, in scope, but unauthorized -> 403
        RoomType matchingRoomType = new RoomType();
        matchingRoomType.setId(roomTypeId);
        matchingRoomType.setPropertyId(propertyIdA);
        when(roomTypeRepository.findById(roomTypeId)).thenReturn(Optional.of(matchingRoomType));
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.ROOM_READ)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.validateRoomQuery(propertyIdA, roomTypeId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access is denied.");

        // Matching property, exists, in scope, authorized -> success
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.ROOM_READ)).thenReturn(true);
        enabledGuard.validateRoomQuery(propertyIdA, roomTypeId);
    }

    @Test
    @DisplayName("validateRoomBedCreation enforces existence, scope, and authorization")
    void validateRoomBedCreation_behavior() {
        // Null roomId -> 400
        assertThatThrownBy(() -> enabledGuard.validateRoomBedCreation(null))
                .isInstanceOf(BusinessValidationException.class);

        // Room not found -> 404
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> enabledGuard.validateRoomBedCreation(roomId))
                .isInstanceOf(ResourceNotFoundException.class);

        // Room exists, but out of scope -> 404
        Room roomB = new Room();
        roomB.setId(roomId);
        roomB.setPropertyId(propertyIdB);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(roomB));
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdB)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.validateRoomBedCreation(roomId))
                .isInstanceOf(ResourceNotFoundException.class);

        // Room in scope, but lacks ROOM_MANAGE -> 403
        Room roomA = new Room();
        roomA.setId(roomId);
        roomA.setPropertyId(propertyIdA);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(roomA));
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.ROOM_MANAGE)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.validateRoomBedCreation(roomId))
                .isInstanceOf(AccessDeniedException.class);

        // Room in scope, authorized -> success
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.ROOM_MANAGE)).thenReturn(true);
        enabledGuard.validateRoomBedCreation(roomId);

        // Disabled mode bypasses scope and permissions
        disabledGuard.validateRoomBedCreation(roomId);
    }

    @Test
    @DisplayName("requireRoomBedAccess enforces 404 for nonexistent/out-of-scope, 403 for permission denial")
    void requireRoomBedAccess_behavior() {
        // Disabled mode bypasses
        disabledGuard.requireRoomBedAccess(roomBedId, StaffOperation.ROOM_READ);

        // RoomBed not found -> 404
        when(roomBedRepository.findById(roomBedId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> enabledGuard.requireRoomBedAccess(roomBedId, StaffOperation.ROOM_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Room bed configuration not found with id: " + roomBedId);

        // RoomBed found, Room not found -> 404
        RoomBed rb = new RoomBed();
        rb.setId(roomBedId);
        rb.setRoomId(roomId);
        when(roomBedRepository.findById(roomBedId)).thenReturn(Optional.of(rb));
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> enabledGuard.requireRoomBedAccess(roomBedId, StaffOperation.ROOM_READ))
                .isInstanceOf(ResourceNotFoundException.class);

        // RoomBed found, Room belongs to out-of-scope property -> 404
        Room roomB = new Room();
        roomB.setId(roomId);
        roomB.setPropertyId(propertyIdB);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(roomB));
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdB)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requireRoomBedAccess(roomBedId, StaffOperation.ROOM_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Room bed configuration not found with id: " + roomBedId);

        // In scope, lacks permission -> 403
        Room roomA = new Room();
        roomA.setId(roomId);
        roomA.setPropertyId(propertyIdA);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(roomA));
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.ROOM_MANAGE)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requireRoomBedAccess(roomBedId, StaffOperation.ROOM_MANAGE))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access is denied.");

        // In scope, authorized -> success
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.ROOM_MANAGE)).thenReturn(true);
        enabledGuard.requireRoomBedAccess(roomBedId, StaffOperation.ROOM_MANAGE);
    }

    @Test
    @DisplayName("validateRoomAmenityAssignment enforces existence, scope, and authorization")
    void validateRoomAmenityAssignment_behavior() {
        // Null roomId -> 400
        assertThatThrownBy(() -> enabledGuard.validateRoomAmenityAssignment(null))
                .isInstanceOf(BusinessValidationException.class);

        // Room not found -> 404
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> enabledGuard.validateRoomAmenityAssignment(roomId))
                .isInstanceOf(ResourceNotFoundException.class);

        // Room out of scope -> 404
        Room roomB = new Room();
        roomB.setId(roomId);
        roomB.setPropertyId(propertyIdB);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(roomB));
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdB)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.validateRoomAmenityAssignment(roomId))
                .isInstanceOf(ResourceNotFoundException.class);

        // In scope, lacks ROOM_MANAGE -> 403
        Room roomA = new Room();
        roomA.setId(roomId);
        roomA.setPropertyId(propertyIdA);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(roomA));
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.ROOM_MANAGE)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.validateRoomAmenityAssignment(roomId))
                .isInstanceOf(AccessDeniedException.class);

        // In scope, authorized -> success
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.ROOM_MANAGE)).thenReturn(true);
        enabledGuard.validateRoomAmenityAssignment(roomId);

        // Disabled mode bypasses scope and permissions
        disabledGuard.validateRoomAmenityAssignment(roomId);
    }

    @Test
    @DisplayName("validateBedTypeExists and validateAmenityExists enforce existence")
    void dictionaryEntityValidations_behavior() {
        // Bed type null or not found -> 404
        assertThatThrownBy(() -> enabledGuard.validateBedTypeExists(null))
                .isInstanceOf(ResourceNotFoundException.class);
        when(bedTypeRepository.existsById(bedTypeId)).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.validateBedTypeExists(bedTypeId))
                .isInstanceOf(ResourceNotFoundException.class);

        // Bed type exists -> success
        when(bedTypeRepository.existsById(bedTypeId)).thenReturn(true);
        enabledGuard.validateBedTypeExists(bedTypeId);

        // Amenity null or not found -> 404
        assertThatThrownBy(() -> enabledGuard.validateAmenityExists(null))
                .isInstanceOf(ResourceNotFoundException.class);
        when(amenityRepository.existsById(amenityId)).thenReturn(false);
        assertThatThrownBy(() -> enabledGuard.validateAmenityExists(amenityId))
                .isInstanceOf(ResourceNotFoundException.class);

        // Amenity exists -> success
        when(amenityRepository.existsById(amenityId)).thenReturn(true);
        enabledGuard.validateAmenityExists(amenityId);
    }

    @Test
    @DisplayName("filterAuthorizedProperties returns appropriate list based on staff permissions")
    void filterAuthorizedProperties_behavior() {
        PropertyResponse prop1 = new PropertyResponse();
        prop1.setId(propertyIdA);
        prop1.setName("Property A");

        PropertyResponse prop2 = new PropertyResponse();
        prop2.setId(propertyIdB);
        prop2.setName("Property B");

        List<PropertyResponse> allProps = List.of(prop1, prop2);

        // Disabled mode -> returns all
        assertThat(disabledGuard.filterAuthorizedProperties(allProps)).containsExactlyElementsOf(allProps);

        // Global super admin -> returns all
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(true);
        assertThat(enabledGuard.filterAuthorizedProperties(allProps)).containsExactlyElementsOf(allProps);

        // Non-super-admin with no assigned properties -> empty list
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.getAssignedPropertyIds()).thenReturn(Set.of());
        assertThat(enabledGuard.filterAuthorizedProperties(allProps)).isEmpty();

        // Non-super-admin assigned to property A only
        when(authorizationService.getAssignedPropertyIds()).thenReturn(Set.of(propertyIdA));
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.PROPERTY_READ)).thenReturn(true);
        List<PropertyResponse> filtered = enabledGuard.filterAuthorizedProperties(allProps);
        assertThat(filtered).containsExactly(prop1);
    }

    @Test
    @DisplayName("filterAuthorizedPropertyAmenities filters associations based on PROPERTY_READ permission")
    void filterAuthorizedPropertyAmenities_behavior() {
        PropertyAmenityResponse pa1 = new PropertyAmenityResponse();
        pa1.setPropertyId(propertyIdA);
        pa1.setAmenityId(amenityId);

        PropertyAmenityResponse pa2 = new PropertyAmenityResponse();
        pa2.setPropertyId(propertyIdB);
        pa2.setAmenityId(amenityId);

        List<PropertyAmenityResponse> list = List.of(pa1, pa2);

        // Disabled mode -> all
        assertThat(disabledGuard.filterAuthorizedPropertyAmenities(list)).containsExactlyElementsOf(list);

        // Super admin -> all
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(true);
        assertThat(enabledGuard.filterAuthorizedPropertyAmenities(list)).containsExactlyElementsOf(list);

        // Unassigned staff -> empty
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.getAssignedPropertyIds()).thenReturn(Set.of());
        assertThat(enabledGuard.filterAuthorizedPropertyAmenities(list)).isEmpty();

        // Assigned to property A only
        when(authorizationService.getAssignedPropertyIds()).thenReturn(Set.of(propertyIdA));
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.PROPERTY_READ)).thenReturn(true);
        assertThat(enabledGuard.filterAuthorizedPropertyAmenities(list)).containsExactly(pa1);
    }
}
