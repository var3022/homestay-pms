package com.homestay.pms_backend.mapper;

import com.homestay.pms_backend.dto.request.RoomCreateRequest;
import com.homestay.pms_backend.dto.request.RoomUpdateRequest;
import com.homestay.pms_backend.dto.response.RoomResponse;
import com.homestay.pms_backend.entity.Room;
import org.springframework.stereotype.Component;

@Component
public class RoomMapper {

    public Room toEntity(RoomCreateRequest request) {

        Room room = new Room();

        room.setPropertyId(request.getPropertyId());
        room.setRoomTypeId(request.getRoomTypeId());
        room.setRoomNumber(request.getRoomNumber());
        room.setName(request.getName());
        room.setFloor(request.getFloor());
        room.setBaseOccupancy(request.getBaseOccupancy());
        room.setMaxOccupancy(request.getMaxOccupancy());
        room.setMaxExtraBeds(request.getMaxExtraBeds());
        room.setBathroomCount(request.getBathroomCount());

        return room;
    }

    public void updateEntity(
            Room room,
            RoomUpdateRequest request) {

        if (request.getRoomNumber() != null) {
            room.setRoomNumber(request.getRoomNumber());
        }

        if (request.getName() != null) {
            room.setName(request.getName());
        }

        if (request.getFloor() != null) {
            room.setFloor(request.getFloor());
        }

        if (request.getBaseOccupancy() != null) {
            room.setBaseOccupancy(request.getBaseOccupancy());
        }

        if (request.getMaxOccupancy() != null) {
            room.setMaxOccupancy(request.getMaxOccupancy());
        }

        if (request.getMaxExtraBeds() != null) {
            room.setMaxExtraBeds(request.getMaxExtraBeds());
        }

        if (request.getBathroomCount() != null) {
            room.setBathroomCount(request.getBathroomCount());
        }
    }

    public RoomResponse toResponse(Room room) {

        RoomResponse response = new RoomResponse();

        response.setId(room.getId());
        response.setPropertyId(room.getPropertyId());
        response.setRoomTypeId(room.getRoomTypeId());
        response.setRoomNumber(room.getRoomNumber());
        response.setName(room.getName());
        response.setFloor(room.getFloor());
        response.setBaseOccupancy(room.getBaseOccupancy());
        response.setMaxOccupancy(room.getMaxOccupancy());
        response.setMaxExtraBeds(room.getMaxExtraBeds());
        response.setBathroomCount(room.getBathroomCount());
        response.setStatus(room.getStatus());
        response.setActive(room.isActive());
        response.setCreatedAt(room.getCreatedAt());
        response.setUpdatedAt(room.getUpdatedAt());

        return response;
    }
}