package com.homestay.pms_backend.security.refreshtoken;

/**
 * Thrown when a refresh-token rotation request encounters a benign concurrency collision
 * where another concurrent request has already rotated the token within the allowed grace window.
 *
 * This is distinctly separated from terminal authentication rejections (InvalidRefreshTokenException)
 * so that the client session cookie is NOT cleared and the valid successor token family is NOT revoked.
 */
public class RefreshTokenCollisionException extends RefreshTokenException {

    public RefreshTokenCollisionException(String message) {
        super(message);
    }

    public RefreshTokenCollisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
