package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.dto.request.RoomAmenityCreateRequest;
import com.homestay.pms_backend.dto.response.RoomAmenityResponse;
import com.homestay.pms_backend.service.RoomAmenityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/room-amenities")
@RequiredArgsConstructor
public class RoomAmenityController {

    private final RoomAmenityService roomAmenityService;

    @PostMapping
    public ResponseEntity<RoomAmenityResponse> addAmenityToRoom(
            @Valid @RequestBody RoomAmenityCreateRequest request) {

        RoomAmenityResponse response =
                roomAmenityService.addAmenityToRoom(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/room/{roomId}")
    public ResponseEntity<List<RoomAmenityResponse>>
    getAmenitiesByRoomId(
            @PathVariable UUID roomId) {

        List<RoomAmenityResponse> response =
                roomAmenityService.getAmenitiesByRoomId(roomId);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/amenity/{amenityId}")
    public ResponseEntity<List<RoomAmenityResponse>>
    getRoomsByAmenityId(
            @PathVariable UUID amenityId) {

        List<RoomAmenityResponse> response =
                roomAmenityService.getRoomsByAmenityId(amenityId);

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{roomId}/{amenityId}")
    public ResponseEntity<Void> removeAmenityFromRoom(
            @PathVariable UUID roomId,
            @PathVariable UUID amenityId) {

        roomAmenityService.removeAmenityFromRoom(
                roomId,
                amenityId
        );

        return ResponseEntity.noContent().build();
    }
}