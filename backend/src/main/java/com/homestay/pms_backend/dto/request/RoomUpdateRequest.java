package com.homestay.pms_backend.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class RoomUpdateRequest {

    @Size(max = 30)
    private String roomNumber;

    @Size(max = 100)
    private String name;

    @Size(max = 30)
    private String floor;

    @Min(1)
    private Integer baseOccupancy;

    @Min(1)
    private Integer maxOccupancy;

    @Min(0)
    private Integer maxExtraBeds;

    @Min(1)
    private Integer bathroomCount;
}