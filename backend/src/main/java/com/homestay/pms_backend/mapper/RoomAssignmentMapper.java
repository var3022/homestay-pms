package com.homestay.pms_backend.mapper;

import com.homestay.pms_backend.dto.request.RoomAssignmentCreateRequest;
import com.homestay.pms_backend.dto.response.RoomAssignmentResponse;
import com.homestay.pms_backend.entity.RoomAssignment;
import com.homestay.pms_backend.enums.RoomAssignmentStatus;
import org.springframework.stereotype.Component;

@Component
public class RoomAssignmentMapper {

    public RoomAssignment toEntity(
            RoomAssignmentCreateRequest request) {

        RoomAssignment assignment = new RoomAssignment();

        assignment.setReservationRoomId(
                request.getReservationRoomId()
        );
        assignment.setRoomId(request.getRoomId());
        assignment.setAssignedFrom(request.getAssignedFrom());
        assignment.setAssignedUntil(request.getAssignedUntil());
        assignment.setStatus(RoomAssignmentStatus.ASSIGNED);

        return assignment;
    }

    public RoomAssignmentResponse toResponse(
            RoomAssignment assignment) {

        RoomAssignmentResponse response =
                new RoomAssignmentResponse();

        response.setId(assignment.getId());
        response.setReservationRoomId(
                assignment.getReservationRoomId()
        );
        response.setRoomId(assignment.getRoomId());

        response.setAssignedFrom(
                assignment.getAssignedFrom()
        );
        response.setAssignedUntil(
                assignment.getAssignedUntil()
        );

        response.setStatus(assignment.getStatus());

        response.setCreatedAt(assignment.getCreatedAt());
        response.setUpdatedAt(assignment.getUpdatedAt());

        return response;
    }
}