package com.homestay.pms_backend.dto.request;

import com.homestay.pms_backend.enums.IdentityDocumentType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class GuestUpdateRequest {

    @Size(max = 150)
    private String name;

    @Size(max = 30)
    private String phone;

    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 5000)
    private String address;

    @Size(max = 100)
    private String city;

    @Size(max = 100)
    private String state;

    @Size(max = 100)
    private String country;

    @Size(max = 20)
    private String postalCode;

    private IdentityDocumentType idType;

    @Size(max = 100)
    private String idNumber;

    @Size(max = 500)
    private String idDocumentImageKey;
}