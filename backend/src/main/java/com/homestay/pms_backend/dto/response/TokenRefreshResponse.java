package com.homestay.pms_backend.dto.response;

/**
 * Successful token refresh response containing a new short-lived JWT access token and staff identity.
 *
 * NOTE: The raw refresh token is NEVER included in this response body.
 * It is delivered exclusively in the replacement HttpOnly cookie.
 */
public record TokenRefreshResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        StaffInfoDto staff
) {
    @Override
    public String toString() {
        return "TokenRefreshResponse[accessToken=***MASKED***, tokenType=" + tokenType
                + ", expiresInSeconds=" + expiresInSeconds + ", staff=" + staff + "]";
    }
}
