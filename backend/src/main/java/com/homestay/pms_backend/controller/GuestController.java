package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.dto.request.GuestCreateRequest;
import com.homestay.pms_backend.dto.request.GuestUpdateRequest;
import com.homestay.pms_backend.dto.response.GuestResponse;
import com.homestay.pms_backend.security.authorization.GuestAuthorizationGuard;
import com.homestay.pms_backend.security.authorization.StaffOperation;
import com.homestay.pms_backend.service.GuestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/guests")
@RequiredArgsConstructor
public class GuestController {

    private final GuestService guestService;
    private final GuestAuthorizationGuard guestAuthorizationGuard;

    @PostMapping
    public ResponseEntity<GuestResponse> createGuest(
            @Valid @RequestBody GuestCreateRequest request) {

        if (guestAuthorizationGuard.isEnforcementEnabled()) {
            guestAuthorizationGuard.validateGuestRegistration(request.getPropertyId());
        }

        GuestResponse response = guestService.createGuest(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<GuestResponse> getGuestById(
            @PathVariable UUID id) {

        guestAuthorizationGuard.requireGuestAccess(id, StaffOperation.GUEST_READ);

        GuestResponse response = guestService.getGuestById(id);

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<GuestResponse>> getAllGuests(
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {

        int clampedPage = Math.max(0, page);
        int clampedSize = Math.min(Math.max(1, size), 100);

        if (propertyId != null) {
            guestAuthorizationGuard.requirePropertyExistenceAndAccess(propertyId, StaffOperation.GUEST_READ);
            return ResponseEntity.ok(
                    guestService.getGuestsByPropertyId(propertyId, clampedPage, clampedSize));
        }

        if (!guestAuthorizationGuard.isEnforcementEnabled()) {
            return ResponseEntity.ok(guestService.getAllGuests());
        }

        if (guestAuthorizationGuard.isGlobalSuperAdmin()) {
            return ResponseEntity.ok(
                    guestService.getAllGuestsPaged(clampedPage, clampedSize));
        }

        Set<UUID> authorizedPropertyIds = guestAuthorizationGuard.getAuthorizedPropertyIds(StaffOperation.GUEST_READ);
        if (authorizedPropertyIds.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        return ResponseEntity.ok(
                guestService.getGuestsByPropertyIds(authorizedPropertyIds, clampedPage, clampedSize));
    }

    @PutMapping("/{id}")
    public ResponseEntity<GuestResponse> updateGuest(
            @PathVariable UUID id,
            @Valid @RequestBody GuestUpdateRequest request) {

        guestAuthorizationGuard.requireGuestUpdateAccess(id);

        GuestResponse response =
                guestService.updateGuest(id, request);

        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivateGuest(
            @PathVariable UUID id) {

        guestAuthorizationGuard.requireGlobalSuperAdmin("deactivate-guest");

        guestService.deactivateGuest(id);

        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<Void> activateGuest(
            @PathVariable UUID id) {

        guestAuthorizationGuard.requireGlobalSuperAdmin("activate-guest");

        guestService.activateGuest(id);

        return ResponseEntity.noContent().build();
    }
}
