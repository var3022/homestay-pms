package com.homestay.pms_backend.mapper;

import com.homestay.pms_backend.dto.request.RoomTypeCreateRequest;
import com.homestay.pms_backend.dto.request.RoomTypeUpdateRequest;
import com.homestay.pms_backend.dto.response.RoomTypeResponse;
import com.homestay.pms_backend.entity.RoomType;
import org.springframework.stereotype.Component;

@Component
public class RoomTypeMapper {

    public RoomType toEntity(RoomTypeCreateRequest request) {

        RoomType roomType = new RoomType();

        roomType.setPropertyId(request.getPropertyId());
        roomType.setName(request.getName());
        roomType.setCode(request.getCode());
        roomType.setDescription(request.getDescription());
        roomType.setCategory(request.getCategory());
        roomType.setBaseOccupancy(request.getBaseOccupancy());
        roomType.setMaxOccupancy(request.getMaxOccupancy());
        roomType.setMaxExtraBeds(request.getMaxExtraBeds());

        return roomType;
    }

    public void updateEntity(
            RoomType roomType,
            RoomTypeUpdateRequest request) {

        if (request.getName() != null) {
            roomType.setName(request.getName());
        }

        if (request.getCode() != null) {
            roomType.setCode(request.getCode());
        }

        if (request.getDescription() != null) {
            roomType.setDescription(request.getDescription());
        }

        if (request.getCategory() != null) {
            roomType.setCategory(request.getCategory());
        }

        if (request.getBaseOccupancy() != null) {
            roomType.setBaseOccupancy(request.getBaseOccupancy());
        }

        if (request.getMaxOccupancy() != null) {
            roomType.setMaxOccupancy(request.getMaxOccupancy());
        }

        if (request.getMaxExtraBeds() != null) {
            roomType.setMaxExtraBeds(request.getMaxExtraBeds());
        }

        if (request.getActive() != null) {
            roomType.setActive(request.getActive());
        }
    }

    public RoomTypeResponse toResponse(RoomType roomType) {

        RoomTypeResponse response = new RoomTypeResponse();

        response.setId(roomType.getId());
        response.setPropertyId(roomType.getPropertyId());
        response.setName(roomType.getName());
        response.setCode(roomType.getCode());
        response.setDescription(roomType.getDescription());
        response.setCategory(roomType.getCategory());
        response.setBaseOccupancy(roomType.getBaseOccupancy());
        response.setMaxOccupancy(roomType.getMaxOccupancy());
        response.setMaxExtraBeds(roomType.getMaxExtraBeds());
        response.setActive(roomType.isActive());
        response.setCreatedAt(roomType.getCreatedAt());
        response.setUpdatedAt(roomType.getUpdatedAt());

        return response;
    }
}