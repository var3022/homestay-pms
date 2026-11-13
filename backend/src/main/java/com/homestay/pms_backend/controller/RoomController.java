package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.dto.request.RoomCreateRequest;
import com.homestay.pms_backend.dto.request.RoomStatusUpdateRequest;
import com.homestay.pms_backend.dto.request.RoomUpdateRequest;
import com.homestay.pms_backend.dto.response.RoomResponse;
import com.homestay.pms_backend.security.authorization.PropertyAuthorizationGuard;
import com.homestay.pms_backend.security.authorization.StaffOperation;
import com.homestay.pms_backend.service.RoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.homestay.pms_backend.exception.BusinessValidationException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;
    private final PropertyAuthorizationGuard propertyAuthorizationGuard;

    @PostMapping
    public ResponseEntity<RoomResponse> createRoom(
            @Valid @RequestBody RoomCreateRequest request) {

        propertyAuthorizationGuard.validateRoomCreation(request.getPropertyId(), request.getRoomTypeId());

        RoomResponse response =
                roomService.createRoom(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoomResponse> getRoomById(
            @PathVariable UUID id) {

        propertyAuthorizationGuard.requireRoomAccess(id, StaffOperation.ROOM_READ);

        RoomResponse response =
                roomService.getRoomById(id);

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<RoomResponse>> getRooms(
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) UUID roomTypeId) {

        if (propertyId != null && roomTypeId != null) {
            propertyAuthorizationGuard.validateRoomQuery(propertyId, roomTypeId);
            return ResponseEntity.ok(
                    roomService.getRoomsByRoomTypeId(roomTypeId)
            );
        }

        if (propertyId != null) {
            propertyAuthorizationGuard.requirePropertyExistenceAndAccess(propertyId, StaffOperation.ROOM_READ);
            return ResponseEntity.ok(
                    roomService.getRoomsByPropertyId(propertyId)
            );
        }

        if (roomTypeId != null) {
            propertyAuthorizationGuard.requireRoomTypeAccess(roomTypeId, StaffOperation.ROOM_READ);
            return ResponseEntity.ok(
                    roomService.getRoomsByRoomTypeId(roomTypeId)
            );
        }

        throw new BusinessValidationException(
                "Either propertyId or roomTypeId must be provided"
        );
    }

    @PutMapping("/{id}")
    public ResponseEntity<RoomResponse> updateRoom(
            @PathVariable UUID id,
            @Valid @RequestBody RoomUpdateRequest request) {

        propertyAuthorizationGuard.requireRoomAccess(id, StaffOperation.ROOM_MANAGE);

        RoomResponse response =
                roomService.updateRoom(id, request);

        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<Void> activateRoom(
            @PathVariable UUID id) {

        propertyAuthorizationGuard.requireRoomAccess(id, StaffOperation.ROOM_MANAGE);

        roomService.activateRoom(id);

        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivateRoom(
            @PathVariable UUID id) {

        propertyAuthorizationGuard.requireRoomAccess(id, StaffOperation.ROOM_MANAGE);

        roomService.deactivateRoom(id);

        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<RoomResponse> updateRoomStatus(
            @PathVariable UUID id,
            @Valid @RequestBody RoomStatusUpdateRequest request) {

        propertyAuthorizationGuard.requireRoomAccess(id, StaffOperation.ROOM_STATUS_UPDATE);

        return ResponseEntity.ok(
                roomService.updateRoomStatus(id, request)
        );
    }
}