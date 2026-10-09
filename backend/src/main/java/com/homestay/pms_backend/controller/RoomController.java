package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.dto.request.RoomCreateRequest;
import com.homestay.pms_backend.dto.request.RoomStatusUpdateRequest;
import com.homestay.pms_backend.dto.request.RoomUpdateRequest;
import com.homestay.pms_backend.dto.response.RoomResponse;
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

    @PostMapping
    public ResponseEntity<RoomResponse> createRoom(
            @Valid @RequestBody RoomCreateRequest request) {

        RoomResponse response =
                roomService.createRoom(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoomResponse> getRoomById(
            @PathVariable UUID id) {

        RoomResponse response =
                roomService.getRoomById(id);

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<RoomResponse>> getRooms(
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) UUID roomTypeId) {

        if (propertyId != null) {
            return ResponseEntity.ok(
                    roomService.getRoomsByPropertyId(propertyId)
            );
        }

        if (roomTypeId != null) {
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

        RoomResponse response =
                roomService.updateRoom(id, request);

        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<Void> activateRoom(
            @PathVariable UUID id) {

        roomService.activateRoom(id);

        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivateRoom(
            @PathVariable UUID id) {

        roomService.deactivateRoom(id);

        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<RoomResponse> updateRoomStatus(
            @PathVariable UUID id,
            @Valid @RequestBody RoomStatusUpdateRequest request) {
        return ResponseEntity.ok(
                roomService.updateRoomStatus(id, request)
        );
    }
}