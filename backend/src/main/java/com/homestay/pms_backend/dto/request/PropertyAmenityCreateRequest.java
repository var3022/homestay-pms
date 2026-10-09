package com.homestay.pms_backend.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class PropertyAmenityCreateRequest {

    @NotNull
    private UUID propertyId;

    @NotNull
    private UUID amenityId;
}