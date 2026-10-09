package com.homestay.pms_backend.service;

import com.homestay.pms_backend.dto.request.ReservationRoomAddRequest;
import com.homestay.pms_backend.dto.response.ReservationRoomResponse;

import java.util.List;
import java.util.UUID;

public interface ReservationRoomService {

    ReservationRoomResponse addReservationRoom(
            ReservationRoomAddRequest request
    );

    ReservationRoomResponse getReservationRoomById(UUID id);

    List<ReservationRoomResponse> getReservationRoomsByReservationId(
            UUID reservationId
    );
}