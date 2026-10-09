package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.dto.request.PropertyAmenityCreateRequest;
import com.homestay.pms_backend.dto.response.PropertyAmenityResponse;
import com.homestay.pms_backend.service.PropertyAmenityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/property-amenities")
@RequiredArgsConstructor
public class PropertyAmenityController {

    private final PropertyAmenityService propertyAmenityService;

    @PostMapping
    public ResponseEntity<PropertyAmenityResponse> addAmenityToProperty(
            @Valid @RequestBody PropertyAmenityCreateRequest request) {

        PropertyAmenityResponse response =
                propertyAmenityService.addAmenityToProperty(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/property/{propertyId}")
    public ResponseEntity<List<PropertyAmenityResponse>>
    getAmenitiesByPropertyId(
            @PathVariable UUID propertyId) {

        List<PropertyAmenityResponse> response =
                propertyAmenityService
                        .getAmenitiesByPropertyId(propertyId);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/amenity/{amenityId}")
    public ResponseEntity<List<PropertyAmenityResponse>>
    getPropertiesByAmenityId(
            @PathVariable UUID amenityId) {

        List<PropertyAmenityResponse> response =
                propertyAmenityService
                        .getPropertiesByAmenityId(amenityId);

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{propertyId}/{amenityId}")
    public ResponseEntity<Void> removeAmenityFromProperty(
            @PathVariable UUID propertyId,
            @PathVariable UUID amenityId) {

        propertyAmenityService.removeAmenityFromProperty(
                propertyId,
                amenityId
        );

        return ResponseEntity.noContent().build();
    }
}