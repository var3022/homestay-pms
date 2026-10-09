package com.homestay.pms_backend.dto.response;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class BedTypeResponse {

    private UUID id;
    private String code;
    private String name;
    private Instant createdAt;
}