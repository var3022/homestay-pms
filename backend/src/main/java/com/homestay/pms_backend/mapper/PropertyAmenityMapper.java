package com.homestay.pms_backend.mapper;

import com.homestay.pms_backend.dto.request.PropertyAmenityCreateRequest;
import com.homestay.pms_backend.dto.response.PropertyAmenityResponse;
import com.homestay.pms_backend.entity.PropertyAmenity;
import com.homestay.pms_backend.entity.PropertyAmenityId;
import org.springframework.stereotype.Component;

@Component
public class PropertyAmenityMapper {

    public PropertyAmenity toEntity(
            PropertyAmenityCreateRequest request) {

        PropertyAmenity propertyAmenity =
                new PropertyAmenity();

        propertyAmenity.setId(
                new PropertyAmenityId(
                        request.getPropertyId(),
                        request.getAmenityId()
                )
        );

        return propertyAmenity;
    }

    public PropertyAmenityResponse toResponse(
            PropertyAmenity propertyAmenity) {

        PropertyAmenityResponse response =
                new PropertyAmenityResponse();

        response.setPropertyId(
                propertyAmenity.getId().getPropertyId()
        );

        response.setAmenityId(
                propertyAmenity.getId().getAmenityId()
        );

        response.setCreatedAt(
                propertyAmenity.getCreatedAt()
        );

        return response;
    }
}