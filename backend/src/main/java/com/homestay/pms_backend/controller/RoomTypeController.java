package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.dto.request.RoomTypeCreateRequest;
import com.homestay.pms_backend.dto.request.RoomTypeUpdateRequest;
import com.homestay.pms_backend.dto.response.RoomTypeResponse;
import com.homestay.pms_backend.service.RoomTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/room-types")
@RequiredArgsConstructor
public class RoomTypeController {

    private final RoomTypeService roomTypeService;

    @PostMapping
    public ResponseEntity<RoomTypeResponse> createRoomType(
            @Valid @RequestBody RoomTypeCreateRequest request) {

        RoomTypeResponse response =
                roomTypeService.createRoomType(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoomTypeResponse> getRoomTypeById(
            @PathVariable UUID id) {

        RoomTypeResponse response =
                roomTypeService.getRoomTypeById(id);

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<RoomTypeResponse>> getRoomTypesByPropertyId(
            @RequestParam UUID propertyId) {

        List<RoomTypeResponse> response =
                roomTypeService.getRoomTypesByPropertyId(propertyId);

        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<RoomTypeResponse> updateRoomType(
            @PathVariable UUID id,
            @Valid @RequestBody RoomTypeUpdateRequest request) {

        RoomTypeResponse response =
                roomTypeService.updateRoomType(id, request);

        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivateRoomType(
            @PathVariable UUID id) {

        roomTypeService.deactivateRoomType(id);

        return ResponseEntity.noContent().build();
    }
}