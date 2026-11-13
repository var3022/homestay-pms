package com.homestay.pms_backend.security.jwt;

import java.time.Instant;
import java.util.UUID;

/**
 * Strongly typed representation of validated JWT access-token claims.
 */
public record StaffJwtClaims(
        UUID staffUserId,
        String issuer,
        String audience,
        Instant issuedAt,
        Instant notBefore,
        Instant expiresAt,
        String jti,
        String tokenType,
        String keyId
) {
    /**
     * Millisecond epoch timestamp derived from the mandatory iat_millis claim.
     *
     * @return issued-at timestamp in milliseconds since Unix epoch
     */
    public long iatMillis() {
        return issuedAt.toEpochMilli();
    }
}
