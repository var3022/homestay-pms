package com.homestay.pms_backend.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class RoomAssignmentCreateRequest {

    @NotNull
    private UUID reservationRoomId;

    @NotNull
    private UUID roomId;

    @NotNull
    private LocalDate assignedFrom;

    @NotNull
    private LocalDate assignedUntil;
}