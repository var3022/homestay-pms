package com.homestay.pms_backend.dto.request;

import com.homestay.pms_backend.enums.HousekeepingStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class RoomHousekeepingUpdateRequest {

    @NotNull(message = "housekeepingStatus must be provided")
    private HousekeepingStatus housekeepingStatus;
}
