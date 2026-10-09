package com.homestay.pms_backend.dto.request;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class RoomBedUpdateRequest {

    @Min(1)
    private Integer quantity;

    private Boolean extraBed;
}