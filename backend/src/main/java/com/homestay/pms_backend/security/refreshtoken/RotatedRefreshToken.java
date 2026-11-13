package com.homestay.pms_backend.security.refreshtoken;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable DTO representing a successor refresh token produced by rotation.
 * The raw plaintext token is only returned to the trusted caller and is redacted in toString().
 */
public record RotatedRefreshToken(
        String rawToken,
        UUID tokenId,
        UUID tokenFamily,
        int sequenceNumber,
        Instant expiresAt,
        UUID staffUserId
) {
    @Override
    public String toString() {
        return "RotatedRefreshToken[tokenId=" + tokenId + ", tokenFamily=" + tokenFamily
                + ", sequenceNumber=" + sequenceNumber + ", expiresAt=" + expiresAt
                + ", staffUserId=" + staffUserId + ", rawToken=***REDACTED***]";
    }
}
