package com.homestay.pms_backend.service;

import com.homestay.pms_backend.dto.request.RoomCreateRequest;
import com.homestay.pms_backend.dto.request.RoomStatusUpdateRequest;
import com.homestay.pms_backend.dto.request.RoomUpdateRequest;
import com.homestay.pms_backend.dto.response.RoomResponse;

import java.util.List;
import java.util.UUID;

public interface RoomService {

    RoomResponse createRoom(RoomCreateRequest request);

    RoomResponse getRoomById(UUID id);

    List<RoomResponse> getRoomsByPropertyId(UUID propertyId);

    List<RoomResponse> getRoomsByRoomTypeId(UUID roomTypeId);

    RoomResponse updateRoom(UUID id, RoomUpdateRequest request);

    void deactivateRoom(UUID id);

    void activateRoom(UUID id);

    RoomResponse updateRoomStatus(UUID id, RoomStatusUpdateRequest request);
}