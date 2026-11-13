package com.homestay.pms_backend.security.refreshtoken;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable DTO representing a newly issued refresh token.
 * The raw plaintext token is only returned to the trusted caller and is redacted in toString().
 */
public record IssuedRefreshToken(
        String rawToken,
        UUID tokenId,
        UUID tokenFamily,
        int sequenceNumber,
        Instant expiresAt,
        UUID staffUserId
) {
    @Override
    public String toString() {
        return "IssuedRefreshToken[tokenId=" + tokenId + ", tokenFamily=" + tokenFamily
                + ", sequenceNumber=" + sequenceNumber + ", expiresAt=" + expiresAt
                + ", staffUserId=" + staffUserId + ", rawToken=***REDACTED***]";
    }
}
