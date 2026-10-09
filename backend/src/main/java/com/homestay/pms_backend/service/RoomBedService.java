package com.homestay.pms_backend.service;

import com.homestay.pms_backend.dto.request.RoomBedCreateRequest;
import com.homestay.pms_backend.dto.request.RoomBedUpdateRequest;
import com.homestay.pms_backend.dto.response.RoomBedResponse;

import java.util.List;
import java.util.UUID;

public interface RoomBedService {

    RoomBedResponse createRoomBed(RoomBedCreateRequest request);

    RoomBedResponse getRoomBedById(UUID id);

    List<RoomBedResponse> getRoomBedsByRoomId(UUID roomId);

    List<RoomBedResponse> getRoomBedsByBedTypeId(UUID bedTypeId);

    RoomBedResponse updateRoomBed(
            UUID id,
            RoomBedUpdateRequest request
    );

    void deleteRoomBed(UUID id);
}