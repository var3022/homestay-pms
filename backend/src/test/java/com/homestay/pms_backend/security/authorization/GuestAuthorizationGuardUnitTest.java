package com.homestay.pms_backend.security.authorization;

import com.homestay.pms_backend.config.SecurityEnforcementProperties;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.repository.GuestPropertyAssociationRepository;
import com.homestay.pms_backend.repository.GuestRepository;
import com.homestay.pms_backend.repository.PropertyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GuestAuthorizationGuard Unit Tests")
class GuestAuthorizationGuardUnitTest {

    @Mock
    private StaffAuthorizationService authorizationService;

    @Mock
    private PropertyRepository propertyRepository;

    @Mock
    private GuestRepository guestRepository;

    @Mock
    private GuestPropertyAssociationRepository associationRepository;

    private SecurityEnforcementProperties enabledProperties;
    private SecurityEnforcementProperties disabledProperties;

    private GuestAuthorizationGuard enabledGuard;
    private GuestAuthorizationGuard disabledGuard;

    private final UUID propertyIdA = UUID.randomUUID();
    private final UUID propertyIdB = UUID.randomUUID();
    private final UUID guestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        enabledProperties = new SecurityEnforcementProperties();
        enabledProperties.setEnabled(true);

        disabledProperties = new SecurityEnforcementProperties();
        disabledProperties.setEnabled(false);

        enabledGuard = new GuestAuthorizationGuard(
                authorizationService, enabledProperties, propertyRepository, guestRepository, associationRepository
        );

        disabledGuard = new GuestAuthorizationGuard(
                authorizationService, disabledProperties, propertyRepository, guestRepository, associationRepository
        );
    }

    // =========================================================================
    // 1. Mandatory Fail-Closed Constructor Dependencies
    // =========================================================================

    @Test
    @DisplayName("Fail-Closed Constructor: Null authorizationService throws NullPointerException")
    void constructorRejectsNullAuthorizationService() {
        assertThatThrownBy(() -> new GuestAuthorizationGuard(
                null, enabledProperties, propertyRepository, guestRepository, associationRepository))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("StaffAuthorizationService must not be null");
    }

    @Test
    @DisplayName("Fail-Closed Constructor: Null enforcementProperties throws NullPointerException")
    void constructorRejectsNullEnforcementProperties() {
        assertThatThrownBy(() -> new GuestAuthorizationGuard(
                authorizationService, null, propertyRepository, guestRepository, associationRepository))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("SecurityEnforcementProperties must not be null");
    }

    @Test
    @DisplayName("Fail-Closed Constructor: Null propertyRepository throws NullPointerException")
    void constructorRejectsNullPropertyRepository() {
        assertThatThrownBy(() -> new GuestAuthorizationGuard(
                authorizationService, enabledProperties, null, guestRepository, associationRepository))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("PropertyRepository must not be null");
    }

    @Test
    @DisplayName("Fail-Closed Constructor: Null guestRepository throws NullPointerException")
    void constructorRejectsNullGuestRepository() {
        assertThatThrownBy(() -> new GuestAuthorizationGuard(
                authorizationService, enabledProperties, propertyRepository, null, associationRepository))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("GuestRepository must not be null");
    }

    @Test
    @DisplayName("Fail-Closed Constructor: Null associationRepository throws NullPointerException")
    void constructorRejectsNullAssociationRepository() {
        assertThatThrownBy(() -> new GuestAuthorizationGuard(
                authorizationService, enabledProperties, propertyRepository, guestRepository, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("GuestPropertyAssociationRepository must not be null");
    }

    // =========================================================================
    // 2. Scope & Registration Permission Tests
    // =========================================================================

    @Test
    @DisplayName("validateGuestRegistration: Null propertyId throws BusinessValidationException")
    void validateGuestRegistrationRejectsNullPropertyId() {
        assertThatThrownBy(() -> enabledGuard.validateGuestRegistration(null))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("propertyId must be provided");
    }

    @Test
    @DisplayName("validateGuestRegistration: Non-existent property throws 404")
    void validateGuestRegistrationRejectsNonExistentProperty() {
        when(propertyRepository.existsById(propertyIdA)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.validateGuestRegistration(propertyIdA))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Property not found with id: " + propertyIdA);
    }

    @Test
    @DisplayName("validateGuestRegistration: Out-of-scope property throws 404")
    void validateGuestRegistrationRejectsOutOfScopeProperty() {
        when(propertyRepository.existsById(propertyIdA)).thenReturn(true);
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.validateGuestRegistration(propertyIdA))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Property not found with id: " + propertyIdA);
    }

    @Test
    @DisplayName("validateGuestRegistration: Caller lacking GUEST_MANAGE throws 403")
    void validateGuestRegistrationRejectsLackingGuestManage() {
        when(propertyRepository.existsById(propertyIdA)).thenReturn(true);
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.GUEST_MANAGE)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.validateGuestRegistration(propertyIdA))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access is denied.");
    }

    @Test
    @DisplayName("validateGuestRegistration: Caller with GUEST_MANAGE passes successfully")
    void validateGuestRegistrationAllowsAuthorizedCaller() {
        when(propertyRepository.existsById(propertyIdA)).thenReturn(true);
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.GUEST_MANAGE)).thenReturn(true);

        enabledGuard.validateGuestRegistration(propertyIdA);
    }

    // =========================================================================
    // 3. Guest Access by Persisted Guest ID
    // =========================================================================

    @Test
    @DisplayName("requireGuestAccess: Null guestId throws 404")
    void requireGuestAccessRejectsNullGuestId() {
        assertThatThrownBy(() -> enabledGuard.requireGuestAccess(null, StaffOperation.GUEST_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Guest not found with id: null");
    }

    @Test
    @DisplayName("requireGuestAccess: Disabled enforcement checks guest existence in repository")
    void requireGuestAccessDisabledEnforcementChecksExistence() {
        when(guestRepository.existsById(guestId)).thenReturn(true);
        disabledGuard.requireGuestAccess(guestId, StaffOperation.GUEST_READ);

        when(guestRepository.existsById(guestId)).thenReturn(false);
        assertThatThrownBy(() -> disabledGuard.requireGuestAccess(guestId, StaffOperation.GUEST_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Guest not found with id: " + guestId);
    }

    @Test
    @DisplayName("requireGuestAccess: Global SUPER_ADMIN checks existence and allows")
    void requireGuestAccessSuperAdminAllowsExistingGuest() {
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(true);
        when(guestRepository.existsById(guestId)).thenReturn(true);

        enabledGuard.requireGuestAccess(guestId, StaffOperation.GUEST_READ);
    }

    @Test
    @DisplayName("requireGuestAccess: Global SUPER_ADMIN throws 404 for non-existent guest")
    void requireGuestAccessSuperAdminRejectsNonExistentGuest() {
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(true);
        when(guestRepository.existsById(guestId)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requireGuestAccess(guestId, StaffOperation.GUEST_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Guest not found with id: " + guestId);
    }

    @Test
    @DisplayName("requireGuestAccess: Guest with zero associations returns 404 (indistinguishable from nonexistent)")
    void requireGuestAccessRejectsGuestWithZeroAssociations() {
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(associationRepository.findPropertyIdsByGuestId(guestId)).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> enabledGuard.requireGuestAccess(guestId, StaffOperation.GUEST_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Guest not found with id: " + guestId);
    }

    @Test
    @DisplayName("requireGuestAccess: Guest associated exclusively with Property B (out of caller's scope) returns uniform 404")
    void requireGuestAccessRejectsOutOfScopeGuestWith404() {
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(associationRepository.findPropertyIdsByGuestId(guestId)).thenReturn(List.of(propertyIdB));
        when(authorizationService.isAssignedToProperty(propertyIdB)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requireGuestAccess(guestId, StaffOperation.GUEST_READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Guest not found with id: " + guestId);
    }

    @Test
    @DisplayName("requireGuestAccess: Caller assigned to Property A but lacking GUEST_READ (VIEWER/ACCOUNTANT) throws 403")
    void requireGuestAccessDeniesViewerOrAccountantWith403() {
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(associationRepository.findPropertyIdsByGuestId(guestId)).thenReturn(List.of(propertyIdA));
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.GUEST_READ)).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requireGuestAccess(guestId, StaffOperation.GUEST_READ))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access is denied.");
    }

    @Test
    @DisplayName("requireGuestAccess: Caller assigned to Property A with GUEST_READ passes successfully")
    void requireGuestAccessAllowsAuthorizedStaff() {
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(associationRepository.findPropertyIdsByGuestId(guestId)).thenReturn(List.of(propertyIdA));
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.GUEST_READ)).thenReturn(true);

        enabledGuard.requireGuestAccess(guestId, StaffOperation.GUEST_READ);
    }

    @Test
    @DisplayName("requireGuestAccess: Shared guest at Property A and B is accessible by staff assigned to Property A")
    void requireGuestAccessAllowsSharedGuestForStaffAtPropertyA() {
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(associationRepository.findPropertyIdsByGuestId(guestId)).thenReturn(List.of(propertyIdA, propertyIdB));
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.isAssignedToProperty(propertyIdB)).thenReturn(false);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.GUEST_READ)).thenReturn(true);

        enabledGuard.requireGuestAccess(guestId, StaffOperation.GUEST_READ);
    }

    // =========================================================================
    // 4. Shared Guest Update Protection Tests
    // =========================================================================

    @Test
    @DisplayName("requireGuestUpdateAccess: Single-property guest can be updated by authorized staff with GUEST_MANAGE")
    void requireGuestUpdateAccessAllowsSinglePropertyGuest() {
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(associationRepository.findPropertyIdsByGuestId(guestId)).thenReturn(List.of(propertyIdA));
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.GUEST_MANAGE)).thenReturn(true);

        enabledGuard.requireGuestUpdateAccess(guestId);
    }

    @Test
    @DisplayName("requireGuestUpdateAccess: Multi-property shared guest update by property-scoped staff is DENIED with 403")
    void requireGuestUpdateAccessDeniesSharedGuestUpdateByScopedStaff() {
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(associationRepository.findPropertyIdsByGuestId(guestId)).thenReturn(List.of(propertyIdA, propertyIdB));
        when(authorizationService.isAssignedToProperty(propertyIdA)).thenReturn(true);
        when(authorizationService.isAssignedToProperty(propertyIdB)).thenReturn(false);
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.GUEST_MANAGE)).thenReturn(true);

        assertThatThrownBy(() -> enabledGuard.requireGuestUpdateAccess(guestId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Shared multi-property guest identity cannot be modified by property-scoped staff.");
    }

    @Test
    @DisplayName("requireGuestUpdateAccess: Multi-property shared guest can be updated by global SUPER_ADMIN")
    void requireGuestUpdateAccessAllowsSharedGuestUpdateBySuperAdmin() {
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(true);
        when(guestRepository.existsById(guestId)).thenReturn(true);

        enabledGuard.requireGuestUpdateAccess(guestId);
    }

    // =========================================================================
    // 5. Global Super Admin Operations
    // =========================================================================

    @Test
    @DisplayName("requireGlobalSuperAdmin: Non-super-admin throws 403 AccessDeniedException")
    void requireGlobalSuperAdminRejectsNonSuperAdmin() {
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);

        assertThatThrownBy(() -> enabledGuard.requireGlobalSuperAdmin("guest-deactivation"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access is denied.");
    }

    @Test
    @DisplayName("requireGlobalSuperAdmin: Global SUPER_ADMIN passes successfully")
    void requireGlobalSuperAdminAllowsSuperAdmin() {
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(true);

        enabledGuard.requireGlobalSuperAdmin("guest-deactivation");
    }

    // =========================================================================
    // 6. Authorized Property & Guest IDs Resolution
    // =========================================================================

    @Test
    @DisplayName("getAuthorizedPropertyIds: Scoped staff resolves assigned properties filtered by operation")
    void getAuthorizedPropertyIdsResolvesFilteredProperties() {
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.getAssignedPropertyIds()).thenReturn(Set.of(propertyIdA, propertyIdB));
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.GUEST_READ)).thenReturn(true);
        when(authorizationService.hasPropertyAccess(propertyIdB, StaffOperation.GUEST_READ)).thenReturn(false);

        Set<UUID> result = enabledGuard.getAuthorizedPropertyIds(StaffOperation.GUEST_READ);
        assertThat(result).containsExactly(propertyIdA);
    }

    @Test
    @DisplayName("getAuthorizedGuestIds: Resolves guest IDs for authorized properties")
    void getAuthorizedGuestIdsResolvesMappedGuests() {
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationService.getAssignedPropertyIds()).thenReturn(Set.of(propertyIdA));
        when(authorizationService.hasPropertyAccess(propertyIdA, StaffOperation.GUEST_READ)).thenReturn(true);
        when(associationRepository.findGuestIdsByPropertyIdIn(Set.of(propertyIdA))).thenReturn(List.of(guestId));

        Set<UUID> result = enabledGuard.getAuthorizedGuestIds(StaffOperation.GUEST_READ);
        assertThat(result).containsExactly(guestId);
    }
}
