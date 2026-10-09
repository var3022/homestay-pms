package com.homestay.pms_backend.service;

import com.homestay.pms_backend.dto.request.RoomAmenityCreateRequest;
import com.homestay.pms_backend.dto.response.RoomAmenityResponse;

import java.util.List;
import java.util.UUID;

public interface RoomAmenityService {

    RoomAmenityResponse addAmenityToRoom(
            RoomAmenityCreateRequest request
    );

    List<RoomAmenityResponse> getAmenitiesByRoomId(
            UUID roomId
    );

    List<RoomAmenityResponse> getRoomsByAmenityId(
            UUID amenityId
    );

    void removeAmenityFromRoom(
            UUID roomId,
            UUID amenityId
    );
}