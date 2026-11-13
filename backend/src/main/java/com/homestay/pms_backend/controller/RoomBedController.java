package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.dto.request.RoomBedCreateRequest;
import com.homestay.pms_backend.dto.request.RoomBedUpdateRequest;
import com.homestay.pms_backend.dto.response.RoomBedResponse;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.security.authorization.PropertyAuthorizationGuard;
import com.homestay.pms_backend.security.authorization.StaffOperation;
import com.homestay.pms_backend.service.RoomBedService;
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
@RequestMapping("/api/v1/room-beds")
@RequiredArgsConstructor
public class RoomBedController {

    private final RoomBedService roomBedService;
    private final PropertyAuthorizationGuard propertyAuthorizationGuard;

    @PostMapping
    public ResponseEntity<RoomBedResponse> createRoomBed(
            @Valid @RequestBody RoomBedCreateRequest request) {

        propertyAuthorizationGuard.validateRoomBedCreation(request.getRoomId());

        RoomBedResponse response =
                roomBedService.createRoomBed(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoomBedResponse> getRoomBedById(
            @PathVariable UUID id) {

        propertyAuthorizationGuard.requireRoomBedAccess(id, StaffOperation.ROOM_READ);

        RoomBedResponse response =
                roomBedService.getRoomBedById(id);

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<RoomBedResponse>> getRoomBeds(
            @RequestParam(required = false) UUID roomId,
            @RequestParam(required = false) UUID bedTypeId) {

        if (roomId != null && bedTypeId != null) {
            propertyAuthorizationGuard.requireRoomAccess(roomId, StaffOperation.ROOM_READ);
            propertyAuthorizationGuard.validateBedTypeExists(bedTypeId);
            List<RoomBedResponse> responses = roomBedService.getRoomBedsByRoomId(roomId)
                    .stream()
                    .filter(rb -> bedTypeId.equals(rb.getBedTypeId()))
                    .toList();
            return ResponseEntity.ok(responses);
        }

        if (roomId != null) {
            propertyAuthorizationGuard.requireRoomAccess(roomId, StaffOperation.ROOM_READ);
            return ResponseEntity.ok(
                    roomBedService.getRoomBedsByRoomId(roomId)
            );
        }

        if (bedTypeId != null) {
            propertyAuthorizationGuard.validateBedTypeExists(bedTypeId);
            if (!propertyAuthorizationGuard.isEnforcementEnabled() || propertyAuthorizationGuard.isGlobalSuperAdmin()) {
                return ResponseEntity.ok(
                        roomBedService.getRoomBedsByBedTypeId(bedTypeId)
                );
            }
            Set<UUID> authorizedPropertyIds = propertyAuthorizationGuard.getAuthorizedPropertyIds(StaffOperation.ROOM_READ);
            if (authorizedPropertyIds.isEmpty()) {
                return ResponseEntity.ok(Collections.emptyList());
            }
            return ResponseEntity.ok(
                    roomBedService.getRoomBedsByBedTypeIdAndPropertyIds(bedTypeId, authorizedPropertyIds)
            );
        }

        throw new BusinessValidationException(
                "Either roomId or bedTypeId must be provided"
        );
    }

    @PutMapping("/{id}")
    public ResponseEntity<RoomBedResponse> updateRoomBed(
            @PathVariable UUID id,
            @Valid @RequestBody RoomBedUpdateRequest request) {

        propertyAuthorizationGuard.requireRoomBedAccess(id, StaffOperation.ROOM_MANAGE);

        RoomBedResponse response =
                roomBedService.updateRoomBed(id, request);

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRoomBed(
            @PathVariable UUID id) {

        propertyAuthorizationGuard.requireRoomBedAccess(id, StaffOperation.ROOM_MANAGE);

        roomBedService.deleteRoomBed(id);

        return ResponseEntity.noContent().build();
    }
}
