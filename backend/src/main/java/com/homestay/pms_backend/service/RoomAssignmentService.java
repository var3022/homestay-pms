package com.homestay.pms_backend.service;

import com.homestay.pms_backend.dto.request.RoomAssignmentCreateRequest;
import com.homestay.pms_backend.dto.response.RoomAssignmentResponse;

import java.util.List;
import java.util.UUID;

public interface RoomAssignmentService {

    RoomAssignmentResponse assignRoom(RoomAssignmentCreateRequest request);

    RoomAssignmentResponse getRoomAssignmentById(UUID id);

    List<RoomAssignmentResponse> getAssignmentsByReservationRoomId(UUID reservationRoomId);

    List<RoomAssignmentResponse> getAssignmentsByRoomId(UUID roomId);

    RoomAssignmentResponse releaseRoomAssignment(UUID id);
}