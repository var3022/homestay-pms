package com.homestay.pms_backend.security.refreshtoken;

import java.util.UUID;

/**
 * Core interface for the backend refresh-token lifecycle service.
 * Handles cryptographically secure issuance, SHA-256 storage, atomic rotation,
 * replay detection, and session revocation over the dedicated pms_auth DataSource.
 */
public interface RefreshTokenService {

    /**
     * Issues an initial refresh token for an authenticated, eligible staff user.
     *
     * @param staffUserId ID of the staff user
     * @param clientIp    Caller client IP address
     * @param userAgent   Caller User-Agent string
     * @return IssuedRefreshToken containing plaintext token and session metadata
     * @throws InvalidRefreshTokenException if the staff account does not exist, is deactivated, or locked
     */
    IssuedRefreshToken issueInitialToken(UUID staffUserId, String clientIp, String userAgent);

    /**
     * Atomically rotates an active refresh token, issuing a single successor token and invalidating the predecessor.
     * Detects token reuse (replay attacks), invalidates the entire token family, and commits security audit records.
     *
     * @param rawRefreshToken Presented plaintext refresh token
     * @param clientIp        Caller client IP address
     * @param userAgent       Caller User-Agent string
     * @return RotatedRefreshToken containing successor plaintext token and session metadata
     * @throws InvalidRefreshTokenException if the token is invalid, expired, revoked, or replay-detected
     */
    RotatedRefreshToken rotateToken(String rawRefreshToken, String clientIp, String userAgent);

    /**
     * Revokes the token family associated with the presented refresh token.
     *
     * @param rawRefreshToken     Presented plaintext refresh token
     * @param expectedStaffUserId Optional authenticated staff user ID for ownership validation (null if unauthenticated)
     * @param actorId             Audit actor identity (defaults to 'system:auth' if null)
     * @throws InvalidRefreshTokenException if expectedStaffUserId does not match the token owner
     */
    void revokeSession(String rawRefreshToken, UUID expectedStaffUserId, String actorId);
}
