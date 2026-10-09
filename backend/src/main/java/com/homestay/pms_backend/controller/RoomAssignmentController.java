package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.dto.request.RoomAssignmentCreateRequest;
import com.homestay.pms_backend.dto.response.RoomAssignmentResponse;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.service.RoomAssignmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/room-assignments")
@RequiredArgsConstructor
public class RoomAssignmentController {

    private final RoomAssignmentService roomAssignmentService;

    @PostMapping
    public ResponseEntity<RoomAssignmentResponse> assignRoom(
            @Valid @RequestBody RoomAssignmentCreateRequest request) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(roomAssignmentService.assignRoom(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoomAssignmentResponse> getRoomAssignmentById(
            @PathVariable UUID id) {

        return ResponseEntity.ok(
                roomAssignmentService.getRoomAssignmentById(id)
        );
    }

    @GetMapping
    public ResponseEntity<List<RoomAssignmentResponse>> getAssignments(
            @RequestParam(required = false) UUID reservationRoomId,
            @RequestParam(required = false) UUID roomId) {

        if (reservationRoomId != null) {
            return ResponseEntity.ok(
                    roomAssignmentService
                            .getAssignmentsByReservationRoomId(
                                    reservationRoomId
                            )
            );
        }

        if (roomId != null) {
            return ResponseEntity.ok(
                    roomAssignmentService
                            .getAssignmentsByRoomId(roomId)
            );
        }

        throw new BusinessValidationException(
                "Either reservationRoomId or roomId must be provided"
        );
    }

    @PatchMapping("/{id}/release")
    public ResponseEntity<RoomAssignmentResponse> releaseRoomAssignment(
            @PathVariable UUID id) {

        return ResponseEntity.ok(
                roomAssignmentService.releaseRoomAssignment(id)
        );
    }
}