package com.homestay.pms_backend.dto.response;

import com.homestay.pms_backend.enums.RoomTypeCategory;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class RoomTypeResponse {

    private UUID id;
    private UUID propertyId;
    private String name;
    private String code;
    private String description;
    private RoomTypeCategory category;
    private Integer baseOccupancy;
    private Integer maxOccupancy;
    private Integer maxExtraBeds;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
}