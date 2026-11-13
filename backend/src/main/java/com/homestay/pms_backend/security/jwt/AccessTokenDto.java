package com.homestay.pms_backend.security.jwt;

import java.time.Instant;

/**
 * Data transfer object representing an issued access token.
 */
public record AccessTokenDto(
        String token,
        Instant expiresAt,
        String jti,
        String tokenType
) {
}
