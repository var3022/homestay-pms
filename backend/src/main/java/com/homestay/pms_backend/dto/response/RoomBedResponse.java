package com.homestay.pms_backend.dto.response;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class RoomBedResponse {

    private UUID id;

    private UUID roomId;

    private UUID bedTypeId;

    private Integer quantity;

    private boolean extraBed;

    private Instant createdAt;
}