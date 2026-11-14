package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.dto.request.GuestCreateRequest;
import com.homestay.pms_backend.dto.request.GuestUpdateRequest;
import com.homestay.pms_backend.dto.response.GuestResponse;
import com.homestay.pms_backend.security.authorization.GuestAuthorizationGuard;
import com.homestay.pms_backend.security.authorization.StaffOperation;
import com.homestay.pms_backend.service.GuestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GuestController Unit Tests")
class GuestControllerUnitTest {

    @Mock
    private GuestService guestService;

    @Mock
    private GuestAuthorizationGuard authorizationGuard;

    @InjectMocks
    private GuestController guestController;

    private final UUID propertyId = UUID.randomUUID();
    private final UUID guestId = UUID.randomUUID();

    @Test
    @DisplayName("createGuest delegates to guard and service under enforcement")
    void createGuest_delegatesToGuardAndService() {
        GuestCreateRequest request = new GuestCreateRequest();
        request.setPropertyId(propertyId);
        request.setName("John Doe");

        GuestResponse response = new GuestResponse();
        response.setId(guestId);
        response.setName("John Doe");

        when(authorizationGuard.isEnforcementEnabled()).thenReturn(true);
        when(guestService.createGuest(request)).thenReturn(response);

        ResponseEntity<GuestResponse> result = guestController.createGuest(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isSameAs(response);

        verify(authorizationGuard).validateGuestRegistration(propertyId);
        verify(guestService).createGuest(request);
    }

    @Test
    @DisplayName("getGuestById checks GUEST_READ permission and returns guest")
    void getGuestById_checksPermissionAndReturnsGuest() {
        GuestResponse response = new GuestResponse();
        response.setId(guestId);

        when(guestService.getGuestById(guestId)).thenReturn(response);

        ResponseEntity<GuestResponse> result = guestController.getGuestById(guestId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(response);

        verify(authorizationGuard).requireGuestAccess(guestId, StaffOperation.GUEST_READ);
        verify(guestService).getGuestById(guestId);
    }

    @Test
    @DisplayName("getAllGuests with propertyId checks property access and returns filtered guests")
    void getAllGuests_withPropertyId_checksPropertyAccess() {
        GuestResponse response = new GuestResponse();
        response.setId(guestId);

        when(guestService.getGuestsByPropertyId(propertyId, 0, 50)).thenReturn(List.of(response));

        ResponseEntity<List<GuestResponse>> result = guestController.getAllGuests(propertyId, 0, 50);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).containsExactly(response);

        verify(authorizationGuard).requirePropertyExistenceAndAccess(propertyId, StaffOperation.GUEST_READ);
        verify(guestService).getGuestsByPropertyId(propertyId, 0, 50);
    }

    @Test
    @DisplayName("getAllGuests as SuperAdmin returns paged guests across properties")
    void getAllGuests_asSuperAdmin_returnsPagedGuests() {
        when(authorizationGuard.isEnforcementEnabled()).thenReturn(true);
        when(authorizationGuard.isGlobalSuperAdmin()).thenReturn(true);

        GuestResponse response = new GuestResponse();
        response.setId(guestId);
        when(guestService.getAllGuestsPaged(0, 50)).thenReturn(List.of(response));

        ResponseEntity<List<GuestResponse>> result = guestController.getAllGuests(null, 0, 50);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).containsExactly(response);

        verify(guestService).getAllGuestsPaged(0, 50);
    }

    @Test
    @DisplayName("getAllGuests as scoped staff queries authorized properties")
    void getAllGuests_asScopedStaff_queriesAuthorizedProperties() {
        when(authorizationGuard.isEnforcementEnabled()).thenReturn(true);
        when(authorizationGuard.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationGuard.getAuthorizedPropertyIds(StaffOperation.GUEST_READ)).thenReturn(Set.of(propertyId));

        GuestResponse response = new GuestResponse();
        response.setId(guestId);
        when(guestService.getGuestsByPropertyIds(Set.of(propertyId), 0, 50)).thenReturn(List.of(response));

        ResponseEntity<List<GuestResponse>> result = guestController.getAllGuests(null, 0, 50);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).containsExactly(response);

        verify(guestService).getGuestsByPropertyIds(Set.of(propertyId), 0, 50);
    }

    @Test
    @DisplayName("getAllGuests as unassigned or viewer staff returns empty list")
    void getAllGuests_asUnassignedStaff_returnsEmptyList() {
        when(authorizationGuard.isEnforcementEnabled()).thenReturn(true);
        when(authorizationGuard.isGlobalSuperAdmin()).thenReturn(false);
        when(authorizationGuard.getAuthorizedPropertyIds(StaffOperation.GUEST_READ)).thenReturn(Collections.emptySet());

        ResponseEntity<List<GuestResponse>> result = guestController.getAllGuests(null, 0, 50);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEmpty();

        verify(guestService, never()).getAllGuests();
        verify(guestService, never()).getGuestsByPropertyIds(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("updateGuest checks requireGuestUpdateAccess and updates guest")
    void updateGuest_checksUpdateAccess() {
        GuestUpdateRequest request = new GuestUpdateRequest();
        request.setName("Updated");

        GuestResponse response = new GuestResponse();
        response.setId(guestId);

        when(guestService.updateGuest(guestId, request)).thenReturn(response);

        ResponseEntity<GuestResponse> result = guestController.updateGuest(guestId, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(response);

        verify(authorizationGuard).requireGuestUpdateAccess(guestId);
        verify(guestService).updateGuest(guestId, request);
    }

    @Test
    @DisplayName("deactivateGuest requires SUPER_ADMIN authority")
    void deactivateGuest_requiresSuperAdmin() {
        ResponseEntity<Void> result = guestController.deactivateGuest(guestId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        verify(authorizationGuard).requireGlobalSuperAdmin("deactivate-guest");
        verify(guestService).deactivateGuest(guestId);
    }

    @Test
    @DisplayName("activateGuest requires SUPER_ADMIN authority")
    void activateGuest_requiresSuperAdmin() {
        ResponseEntity<Void> result = guestController.activateGuest(guestId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        verify(authorizationGuard).requireGlobalSuperAdmin("activate-guest");
        verify(guestService).activateGuest(guestId);
    }
}
