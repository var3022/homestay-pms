package com.homestay.pms_backend.dto.request;

import com.homestay.pms_backend.enums.RoomTypeCategory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class RoomTypeUpdateRequest {

    @Size(max = 100)
    private String name;

    @Size(max = 50)
    private String code;

    private String description;

    private RoomTypeCategory category;

    @Min(1)
    private Integer baseOccupancy;

    @Min(1)
    private Integer maxOccupancy;

    @Min(0)
    private Integer maxExtraBeds;

    private Boolean active;
}