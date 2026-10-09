package com.homestay.pms_backend.dto.request;

import com.homestay.pms_backend.enums.RoomStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class RoomStatusUpdateRequest {

    @NotNull
    private RoomStatus status;
}