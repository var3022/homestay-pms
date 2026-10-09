package com.homestay.pms_backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class PropertyUpdateRequest {

    @Size(max = 150)
    private String name;

    @Size(max = 50)
    private String code;

    private String description;

    @Size(max = 255)
    private String addressLine1;

    @Size(max = 255)
    private String addressLine2;

    @Size(max = 100)
    private String city;

    @Size(max = 100)
    private String state;

    @Size(max = 100)
    private String country;

    @Size(max = 20)
    private String postalCode;

    @Size(max = 30)
    private String phone;

    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 50)
    private String timezone;

    private Boolean active;
}