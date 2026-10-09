package com.homestay.pms_backend.mapper;

import com.homestay.pms_backend.dto.request.AmenityCreateRequest;
import com.homestay.pms_backend.dto.request.AmenityUpdateRequest;
import com.homestay.pms_backend.dto.response.AmenityResponse;
import com.homestay.pms_backend.entity.Amenity;
import org.springframework.stereotype.Component;

@Component
public class AmenityMapper {

    public Amenity toEntity(AmenityCreateRequest request) {

        Amenity amenity = new Amenity();

        amenity.setCode(request.getCode());
        amenity.setName(request.getName());
        amenity.setCategory(request.getCategory());
        amenity.setDescription(request.getDescription());

        return amenity;
    }

    public void updateEntity(
            Amenity amenity,
            AmenityUpdateRequest request) {

        if (request.getName() != null) {
            amenity.setName(request.getName());
        }

        if (request.getCategory() != null) {
            amenity.setCategory(request.getCategory());
        }

        if (request.getDescription() != null) {
            amenity.setDescription(request.getDescription());
        }
    }

    public AmenityResponse toResponse(Amenity amenity) {

        AmenityResponse response = new AmenityResponse();

        response.setId(amenity.getId());
        response.setCode(amenity.getCode());
        response.setName(amenity.getName());
        response.setCategory(amenity.getCategory());
        response.setDescription(amenity.getDescription());
        response.setActive(amenity.isActive());
        response.setCreatedAt(amenity.getCreatedAt());

        return response;
    }
}