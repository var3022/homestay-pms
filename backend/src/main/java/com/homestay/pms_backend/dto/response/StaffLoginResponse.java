package com.homestay.pms_backend.dto.response;

/**
 * Successful authentication response containing JWT access token and minimal staff identity.
 */
public record StaffLoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        StaffInfoDto staff
) {
    @Override
    public String toString() {
        return "StaffLoginResponse[accessToken=***MASKED***, tokenType=" + tokenType
                + ", expiresInSeconds=" + expiresInSeconds + ", staff=" + staff + "]";
    }
}
