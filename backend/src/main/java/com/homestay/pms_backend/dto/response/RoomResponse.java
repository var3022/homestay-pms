package com.homestay.pms_backend.dto.response;

import com.homestay.pms_backend.enums.RoomStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class RoomResponse {

    private UUID id;

    private UUID propertyId;

    private UUID roomTypeId;

    private String roomNumber;

    private String name;

    private String floor;

    private Integer baseOccupancy;

    private Integer maxOccupancy;

    private Integer maxExtraBeds;

    private Integer bathroomCount;

    private RoomStatus status;

    private boolean active;

    private Instant createdAt;

    private Instant updatedAt;
}