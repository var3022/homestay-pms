package com.homestay.pms_backend.security.refreshtoken;

/**
 * Thrown when a presented refresh token is invalid, expired, revoked, replayed,
 * or belongs to an ineligible or locked staff user.
 */
public class InvalidRefreshTokenException extends RefreshTokenException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }

    public InvalidRefreshTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
