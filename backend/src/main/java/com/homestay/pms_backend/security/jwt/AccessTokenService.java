package com.homestay.pms_backend.security.jwt;

import com.homestay.pms_backend.security.AuthenticatedStaff;

/**
 * Access token lifecycle service interface for generation and verification.
 */
public interface AccessTokenService {

    /**
     * Issues a short-lived, signed JWT access token for an authenticated staff member.
     *
     * @param staff Verified staff identity
     * @return DTO containing serialized token, expiration instant, and JTI
     */
    AccessTokenDto generateAccessToken(AuthenticatedStaff staff);

    /**
     * Cryptographically and structurally validates a JWT access token.
     *
     * @param token Serialized JWT access token
     * @return Validated staff claims
     * @throws JwtValidationException if token is invalid, expired, tampered, or untrusted
     */
    StaffJwtClaims validateAccessToken(String token);
}
