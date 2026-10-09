package com.homestay.pms_backend.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class RoomBedCreateRequest {

    @NotNull
    private UUID roomId;

    @NotNull
    private UUID bedTypeId;

    @NotNull
    @Min(1)
    private Integer quantity;

    private boolean extraBed = false;
}