package com.homestay.pms_backend.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class RoomCreateRequest {

    @NotNull
    private UUID propertyId;

    @NotNull
    private UUID roomTypeId;

    @NotBlank
    @Size(max = 30)
    private String roomNumber;

    @Size(max = 100)
    private String name;

    @Size(max = 30)
    private String floor;

    @NotNull
    @Min(1)
    private Integer baseOccupancy;

    @NotNull
    @Min(1)
    private Integer maxOccupancy;

    @NotNull
    @Min(0)
    private Integer maxExtraBeds;

    @NotNull
    @Min(1)
    private Integer bathroomCount;
}