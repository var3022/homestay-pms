package com.homestay.pms_backend.service;

import com.homestay.pms_backend.dto.request.AmenityCreateRequest;
import com.homestay.pms_backend.dto.request.AmenityUpdateRequest;
import com.homestay.pms_backend.dto.response.AmenityResponse;

import java.util.List;
import java.util.UUID;

public interface AmenityService {

    AmenityResponse createAmenity(AmenityCreateRequest request);

    AmenityResponse getAmenityById(UUID id);

    List<AmenityResponse> getAllAmenities();

    AmenityResponse updateAmenity(
            UUID id,
            AmenityUpdateRequest request
    );

    void deactivateAmenity(UUID id);

    void activateAmenity(UUID id);
}