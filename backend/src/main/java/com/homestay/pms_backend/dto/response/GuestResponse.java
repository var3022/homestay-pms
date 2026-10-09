package com.homestay.pms_backend.dto.response;

import com.homestay.pms_backend.enums.IdentityDocumentType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class GuestResponse {

    private UUID id;

    private String name;

    private String phone;

    private String email;

    private String address;

    private String city;

    private String state;

    private String country;

    private String postalCode;

    private IdentityDocumentType idType;

    private String idNumber;

    private String idDocumentImageKey;

    private boolean active;

    private Instant createdAt;

    private Instant updatedAt;
}