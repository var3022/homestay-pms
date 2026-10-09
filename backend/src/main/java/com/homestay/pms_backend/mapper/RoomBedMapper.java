package com.homestay.pms_backend.mapper;

import com.homestay.pms_backend.dto.request.RoomBedCreateRequest;
import com.homestay.pms_backend.dto.request.RoomBedUpdateRequest;
import com.homestay.pms_backend.dto.response.RoomBedResponse;
import com.homestay.pms_backend.entity.RoomBed;
import org.springframework.stereotype.Component;

@Component
public class RoomBedMapper {

    public RoomBed toEntity(RoomBedCreateRequest request) {

        RoomBed roomBed = new RoomBed();

        roomBed.setRoomId(request.getRoomId());
        roomBed.setBedTypeId(request.getBedTypeId());
        roomBed.setQuantity(request.getQuantity());
        roomBed.setExtraBed(request.isExtraBed());

        return roomBed;
    }

    public void updateEntity(
            RoomBed roomBed,
            RoomBedUpdateRequest request) {

        if (request.getQuantity() != null) {
            roomBed.setQuantity(request.getQuantity());
        }

        if (request.getExtraBed() != null) {
            roomBed.setExtraBed(request.getExtraBed());
        }
    }

    public RoomBedResponse toResponse(RoomBed roomBed) {

        RoomBedResponse response = new RoomBedResponse();

        response.setId(roomBed.getId());
        response.setRoomId(roomBed.getRoomId());
        response.setBedTypeId(roomBed.getBedTypeId());
        response.setQuantity(roomBed.getQuantity());
        response.setExtraBed(roomBed.isExtraBed());
        response.setCreatedAt(roomBed.getCreatedAt());

        return response;
    }
}