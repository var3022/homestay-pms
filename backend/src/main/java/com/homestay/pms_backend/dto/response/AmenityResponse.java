package com.homestay.pms_backend.dto.response;

import com.homestay.pms_backend.enums.AmenityCategory;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class AmenityResponse {

    private UUID id;
    private String code;
    private String name;
    private AmenityCategory category;
    private String description;
    private boolean active;
    private Instant createdAt;
}