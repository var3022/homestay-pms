package com.homestay.pms_backend.mapper;

import com.homestay.pms_backend.dto.request.ReservationRoomAddRequest;
import com.homestay.pms_backend.dto.request.ReservationRoomCreateRequest;
import com.homestay.pms_backend.dto.response.ReservationRoomResponse;
import com.homestay.pms_backend.entity.ReservationRoom;
import com.homestay.pms_backend.enums.ReservationRoomStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ReservationRoomMapper {

    public ReservationRoom toEntity(ReservationRoomAddRequest request) {

        ReservationRoom reservationRoom = new ReservationRoom();

        reservationRoom.setReservationId(request.getReservationId());
        reservationRoom.setRoomTypeId(request.getRoomTypeId());
        reservationRoom.setQuantity(request.getQuantity());
        reservationRoom.setCancelledQuantity(0);
        reservationRoom.setUnitPrice(request.getUnitPrice());
        reservationRoom.setSubtotal(null);
        reservationRoom.setStatus(ReservationRoomStatus.CONFIRMED);

        return reservationRoom;
    }

    public ReservationRoom toEntity(
            ReservationRoomCreateRequest request,
            UUID reservationId) {

        ReservationRoom reservationRoom = new ReservationRoom();

        reservationRoom.setReservationId(reservationId);
        reservationRoom.setRoomTypeId(request.getRoomTypeId());
        reservationRoom.setQuantity(request.getQuantity());
        reservationRoom.setCancelledQuantity(0);
        reservationRoom.setUnitPrice(request.getUnitPrice());
        reservationRoom.setSubtotal(null);
        reservationRoom.setStatus(ReservationRoomStatus.CONFIRMED);

        return reservationRoom;
    }

    public ReservationRoomResponse toResponse(
            ReservationRoom reservationRoom) {

        ReservationRoomResponse response = new ReservationRoomResponse();

        response.setId(reservationRoom.getId());
        response.setReservationId(reservationRoom.getReservationId());
        response.setRoomTypeId(reservationRoom.getRoomTypeId());
        response.setQuantity(reservationRoom.getQuantity());
        response.setCancelledQuantity(reservationRoom.getCancelledQuantity());

        response.setActiveQuantity(
                reservationRoom.getQuantity()
                        - reservationRoom.getCancelledQuantity()
        );

        response.setUnitPrice(reservationRoom.getUnitPrice());
        response.setSubtotal(reservationRoom.getSubtotal());
        response.setStatus(reservationRoom.getStatus());
        response.setCreatedAt(reservationRoom.getCreatedAt());
        response.setUpdatedAt(reservationRoom.getUpdatedAt());

        return response;
    }
}