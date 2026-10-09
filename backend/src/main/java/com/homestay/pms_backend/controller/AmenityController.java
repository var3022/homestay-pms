package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.dto.request.AmenityCreateRequest;
import com.homestay.pms_backend.dto.request.AmenityUpdateRequest;
import com.homestay.pms_backend.dto.response.AmenityResponse;
import com.homestay.pms_backend.service.AmenityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/amenities")
@RequiredArgsConstructor
public class AmenityController {

    private final AmenityService amenityService;

    @PostMapping
    public ResponseEntity<AmenityResponse> createAmenity(
            @Valid @RequestBody AmenityCreateRequest request) {

        AmenityResponse response =
                amenityService.createAmenity(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AmenityResponse> getAmenityById(
            @PathVariable UUID id) {

        AmenityResponse response =
                amenityService.getAmenityById(id);

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<AmenityResponse>> getAllAmenities() {

        List<AmenityResponse> response =
                amenityService.getAllAmenities();

        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AmenityResponse> updateAmenity(
            @PathVariable UUID id,
            @Valid @RequestBody AmenityUpdateRequest request) {

        AmenityResponse response =
                amenityService.updateAmenity(id, request);

        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivateAmenity(
            @PathVariable UUID id) {

        amenityService.deactivateAmenity(id);

        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<Void> activateAmenity(
            @PathVariable UUID id) {

        amenityService.activateAmenity(id);

        return ResponseEntity.noContent().build();
    }
}