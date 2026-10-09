package com.homestay.pms_backend.mapper;

import com.homestay.pms_backend.dto.request.PropertyCreateRequest;
import com.homestay.pms_backend.dto.request.PropertyUpdateRequest;
import com.homestay.pms_backend.dto.response.PropertyResponse;
import com.homestay.pms_backend.entity.Property;
import org.springframework.stereotype.Component;

@Component
public class PropertyMapper {

    public Property toEntity(PropertyCreateRequest request) {

        Property property = new Property();

        property.setName(request.getName());
        property.setCode(request.getCode());
        property.setDescription(request.getDescription());
        property.setAddressLine1(request.getAddressLine1());
        property.setAddressLine2(request.getAddressLine2());
        property.setCity(request.getCity());
        property.setState(request.getState());
        property.setCountry(request.getCountry());
        property.setPostalCode(request.getPostalCode());
        property.setPhone(request.getPhone());
        property.setEmail(request.getEmail());
        property.setTimezone(request.getTimezone());

        return property;
    }

    public void updateEntity(Property property, PropertyUpdateRequest request) {

        if (request.getName() != null) {
            property.setName(request.getName());
        }

        if (request.getCode() != null) {
            property.setCode(request.getCode());
        }

        if (request.getDescription() != null) {
            property.setDescription(request.getDescription());
        }

        if (request.getAddressLine1() != null) {
            property.setAddressLine1(request.getAddressLine1());
        }

        if (request.getAddressLine2() != null) {
            property.setAddressLine2(request.getAddressLine2());
        }

        if (request.getCity() != null) {
            property.setCity(request.getCity());
        }

        if (request.getState() != null) {
            property.setState(request.getState());
        }

        if (request.getCountry() != null) {
            property.setCountry(request.getCountry());
        }

        if (request.getPostalCode() != null) {
            property.setPostalCode(request.getPostalCode());
        }

        if (request.getPhone() != null) {
            property.setPhone(request.getPhone());
        }

        if (request.getEmail() != null) {
            property.setEmail(request.getEmail());
        }

        if (request.getTimezone() != null) {
            property.setTimezone(request.getTimezone());
        }

        if (request.getActive() != null) {
            property.setActive(request.getActive());
        }
    }

    public PropertyResponse toResponse(Property property) {

        PropertyResponse response = new PropertyResponse();

        response.setId(property.getId());
        response.setName(property.getName());
        response.setCode(property.getCode());
        response.setDescription(property.getDescription());
        response.setAddressLine1(property.getAddressLine1());
        response.setAddressLine2(property.getAddressLine2());
        response.setCity(property.getCity());
        response.setState(property.getState());
        response.setCountry(property.getCountry());
        response.setPostalCode(property.getPostalCode());
        response.setPhone(property.getPhone());
        response.setEmail(property.getEmail());
        response.setTimezone(property.getTimezone());
        response.setActive(property.isActive());
        response.setCreatedAt(property.getCreatedAt());
        response.setUpdatedAt(property.getUpdatedAt());

        return response;
    }
}