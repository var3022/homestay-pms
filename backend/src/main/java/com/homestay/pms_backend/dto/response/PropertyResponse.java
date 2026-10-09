package com.homestay.pms_backend.dto.response;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class PropertyResponse {

    private UUID id;

    private String name;

    private String code;

    private String description;

    private String addressLine1;

    private String addressLine2;

    private String city;

    private String state;

    private String country;

    private String postalCode;

    private String phone;

    private String email;

    private String timezone;

    private boolean active;

    private Instant createdAt;

    private Instant updatedAt;
}