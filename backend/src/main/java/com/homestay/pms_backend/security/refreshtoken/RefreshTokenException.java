package com.homestay.pms_backend.security.refreshtoken;

/**
 * Base exception for refresh-token lifecycle operations.
 */
public class RefreshTokenException extends RuntimeException {

    public RefreshTokenException(String message) {
        super(message);
    }

    public RefreshTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
