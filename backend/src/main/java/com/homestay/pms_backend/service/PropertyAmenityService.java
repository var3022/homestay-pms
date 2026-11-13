package com.homestay.pms_backend.service;

import com.homestay.pms_backend.dto.request.PropertyAmenityCreateRequest;
import com.homestay.pms_backend.dto.response.PropertyAmenityResponse;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PropertyAmenityService {

    PropertyAmenityResponse addAmenityToProperty(
            PropertyAmenityCreateRequest request
    );

    List<PropertyAmenityResponse> getAmenitiesByPropertyId(
            UUID propertyId
    );

    List<PropertyAmenityResponse> getPropertiesByAmenityId(
            UUID amenityId
    );

    List<PropertyAmenityResponse> getPropertiesByAmenityIdAndPropertyIds(
            UUID amenityId,
            Collection<UUID> propertyIds
    );

    void removeAmenityFromProperty(
            UUID propertyId,
            UUID amenityId
    );
}
