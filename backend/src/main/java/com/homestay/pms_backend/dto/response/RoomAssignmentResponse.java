package com.homestay.pms_backend.dto.response;

import com.homestay.pms_backend.enums.RoomAssignmentStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class RoomAssignmentResponse {

    private UUID id;
    private UUID reservationRoomId;
    private UUID roomId;

    private LocalDate assignedFrom;
    private LocalDate assignedUntil;

    private RoomAssignmentStatus status;

    private Instant createdAt;
    private Instant updatedAt;
}