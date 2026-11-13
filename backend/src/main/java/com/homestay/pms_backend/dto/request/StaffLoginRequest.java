package com.homestay.pms_backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Validated request DTO for staff authentication.
 */
public record StaffLoginRequest(
        @NotBlank(message = "Email must not be blank")
        @Email(message = "Email must be a well-formed email address")
        String email,

        @NotBlank(message = "Password must not be blank")
        String password
) {
    @Override
    public String toString() {
        return "StaffLoginRequest[email=" + email + ", password=***MASKED***]";
    }
}
