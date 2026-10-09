package com.homestay.pms_backend.mapper;

import com.homestay.pms_backend.dto.request.RoomAmenityCreateRequest;
import com.homestay.pms_backend.dto.response.RoomAmenityResponse;
import com.homestay.pms_backend.entity.RoomAmenity;
import com.homestay.pms_backend.entity.RoomAmenityId;
import org.springframework.stereotype.Component;

@Component
public class RoomAmenityMapper {

    public RoomAmenity toEntity(
            RoomAmenityCreateRequest request) {

        RoomAmenity roomAmenity = new RoomAmenity();

        roomAmenity.setId(
                new RoomAmenityId(
                        request.getRoomId(),
                        request.getAmenityId()
                )
        );

        return roomAmenity;
    }

    public RoomAmenityResponse toResponse(
            RoomAmenity roomAmenity) {

        RoomAmenityResponse response =
                new RoomAmenityResponse();

        response.setRoomId(
                roomAmenity.getId().getRoomId()
        );

        response.setAmenityId(
                roomAmenity.getId().getAmenityId()
        );

        response.setCreatedAt(
                roomAmenity.getCreatedAt()
        );

        return response;
    }
}