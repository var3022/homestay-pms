package com.homestay.pms_backend.service;

import com.homestay.pms_backend.dto.request.PropertyCreateRequest;
import com.homestay.pms_backend.dto.request.PropertyUpdateRequest;
import com.homestay.pms_backend.dto.response.PropertyResponse;

import java.util.List;
import java.util.UUID;

public interface PropertyService {

    PropertyResponse createProperty(PropertyCreateRequest request);

    PropertyResponse getPropertyById(UUID id);

    List<PropertyResponse> getAllProperties();

    PropertyResponse updateProperty(UUID id, PropertyUpdateRequest request);

    void deactivateProperty(UUID id);

    void activateProperty(UUID id);
}