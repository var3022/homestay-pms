package com.homestay.pms_backend.service;

import com.homestay.pms_backend.dto.request.RoomTypeCreateRequest;
import com.homestay.pms_backend.dto.request.RoomTypeUpdateRequest;
import com.homestay.pms_backend.dto.response.RoomTypeResponse;

import java.util.List;
import java.util.UUID;

public interface RoomTypeService {

    RoomTypeResponse createRoomType(RoomTypeCreateRequest request);

    RoomTypeResponse getRoomTypeById(UUID id);

    List<RoomTypeResponse> getRoomTypesByPropertyId(UUID propertyId);

    RoomTypeResponse updateRoomType(
            UUID id,
            RoomTypeUpdateRequest request
    );

    void deactivateRoomType(UUID id);
}