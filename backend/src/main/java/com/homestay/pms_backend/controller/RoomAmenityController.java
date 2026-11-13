package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.dto.request.RoomAmenityCreateRequest;
import com.homestay.pms_backend.dto.response.RoomAmenityResponse;
import com.homestay.pms_backend.security.authorization.PropertyAuthorizationGuard;
import com.homestay.pms_backend.security.authorization.StaffOperation;
import com.homestay.pms_backend.service.RoomAmenityService;
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
@RequestMapping("/api/v1/room-amenities")
@RequiredArgsConstructor
public class RoomAmenityController {

    private final RoomAmenityService roomAmenityService;
    private final PropertyAuthorizationGuard propertyAuthorizationGuard;

    @PostMapping
    public ResponseEntity<RoomAmenityResponse> addAmenityToRoom(
            @Valid @RequestBody RoomAmenityCreateRequest request) {

        propertyAuthorizationGuard.validateRoomAmenityAssignment(request.getRoomId());

        RoomAmenityResponse response =
                roomAmenityService.addAmenityToRoom(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/room/{roomId}")
    public ResponseEntity<List<RoomAmenityResponse>> getAmenitiesByRoomId(
            @PathVariable UUID roomId) {

        propertyAuthorizationGuard.requireRoomAccess(roomId, StaffOperation.ROOM_READ);

        List<RoomAmenityResponse> response =
                roomAmenityService.getAmenitiesByRoomId(roomId);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/amenity/{amenityId}")
    public ResponseEntity<List<RoomAmenityResponse>> getRoomsByAmenityId(
            @PathVariable UUID amenityId) {

        propertyAuthorizationGuard.validateAmenityExists(amenityId);

        if (!propertyAuthorizationGuard.isEnforcementEnabled() || propertyAuthorizationGuard.isGlobalSuperAdmin()) {
            return ResponseEntity.ok(
                    roomAmenityService.getRoomsByAmenityId(amenityId)
            );
        }

        Set<UUID> authorizedPropertyIds = propertyAuthorizationGuard.getAuthorizedPropertyIds(StaffOperation.ROOM_READ);
        if (authorizedPropertyIds.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        return ResponseEntity.ok(
                roomAmenityService.getRoomsByAmenityIdAndPropertyIds(amenityId, authorizedPropertyIds)
        );
    }

    @DeleteMapping("/{roomId}/{amenityId}")
    public ResponseEntity<Void> removeAmenityFromRoom(
            @PathVariable UUID roomId,
            @PathVariable UUID amenityId) {

        propertyAuthorizationGuard.requireRoomAccess(roomId, StaffOperation.ROOM_MANAGE);

        roomAmenityService.removeAmenityFromRoom(
                roomId,
                amenityId
        );

        return ResponseEntity.noContent().build();
    }
}
