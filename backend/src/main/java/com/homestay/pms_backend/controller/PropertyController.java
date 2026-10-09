package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.dto.request.PropertyCreateRequest;
import com.homestay.pms_backend.dto.request.PropertyUpdateRequest;
import com.homestay.pms_backend.dto.response.PropertyResponse;
import com.homestay.pms_backend.service.PropertyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/properties")
@RequiredArgsConstructor
public class PropertyController {

    private final PropertyService propertyService;

    @PostMapping
    public ResponseEntity<PropertyResponse> createProperty(
            @Valid @RequestBody PropertyCreateRequest request) {

        PropertyResponse response = propertyService.createProperty(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PropertyResponse> getPropertyById(
            @PathVariable UUID id) {

        PropertyResponse response = propertyService.getPropertyById(id);

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<PropertyResponse>> getAllProperties() {

        List<PropertyResponse> response = propertyService.getAllProperties();

        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PropertyResponse> updateProperty(
            @PathVariable UUID id,
            @Valid @RequestBody PropertyUpdateRequest request) {

        PropertyResponse response =
                propertyService.updateProperty(id, request);

        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivateProperty(
            @PathVariable UUID id) {

        propertyService.deactivateProperty(id);

        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<Void> activateProperty(@PathVariable UUID id) {
        propertyService.activateProperty(id);
        return ResponseEntity.noContent().build();
    }
}