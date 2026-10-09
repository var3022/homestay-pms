package com.homestay.pms_backend.dto.request;

import com.homestay.pms_backend.enums.RoomTypeCategory;
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
public class RoomTypeCreateRequest {

    @NotNull
    private UUID propertyId;

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Size(max = 50)
    private String code;

    private String description;

    @NotNull
    private RoomTypeCategory category;

    @NotNull
    @Min(1)
    private Integer baseOccupancy;

    @NotNull
    @Min(1)
    private Integer maxOccupancy;

    @NotNull
    @Min(0)
    private Integer maxExtraBeds;
}