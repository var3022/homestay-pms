package com.homestay.pms_backend.dto.response;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class PropertyAmenityResponse {

    private UUID propertyId;
    private UUID amenityId;
    private Instant createdAt;
}