package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.dto.request.RoomBedCreateRequest;
import com.homestay.pms_backend.dto.request.RoomBedUpdateRequest;
import com.homestay.pms_backend.dto.response.RoomBedResponse;
import com.homestay.pms_backend.service.RoomBedService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/room-beds")
@RequiredArgsConstructor
public class RoomBedController {

    private final RoomBedService roomBedService;

    @PostMapping
    public ResponseEntity<RoomBedResponse> createRoomBed(
            @Valid @RequestBody RoomBedCreateRequest request) {

        RoomBedResponse response =
                roomBedService.createRoomBed(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoomBedResponse> getRoomBedById(
            @PathVariable UUID id) {

        RoomBedResponse response =
                roomBedService.getRoomBedById(id);

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<RoomBedResponse>> getRoomBeds(
            @RequestParam(required = false) UUID roomId,
            @RequestParam(required = false) UUID bedTypeId) {

        if (roomId != null) {
            return ResponseEntity.ok(
                    roomBedService.getRoomBedsByRoomId(roomId)
            );
        }

        if (bedTypeId != null) {
            return ResponseEntity.ok(
                    roomBedService.getRoomBedsByBedTypeId(bedTypeId)
            );
        }

        throw new com.homestay.pms_backend.exception.BusinessValidationException(
                "Either roomId or bedTypeId must be provided"
        );
    }

    @PutMapping("/{id}")
    public ResponseEntity<RoomBedResponse> updateRoomBed(
            @PathVariable UUID id,
            @Valid @RequestBody RoomBedUpdateRequest request) {

        RoomBedResponse response =
                roomBedService.updateRoomBed(id, request);

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRoomBed(
            @PathVariable UUID id) {

        roomBedService.deleteRoomBed(id);

        return ResponseEntity.noContent().build();
    }
}