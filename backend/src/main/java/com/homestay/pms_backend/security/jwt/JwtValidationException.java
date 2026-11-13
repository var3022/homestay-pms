package com.homestay.pms_backend.security.jwt;

import com.homestay.pms_backend.security.StaffAuthenticationException;

/**
 * Thrown when an access token fails cryptographic or structural validation.
 * Uses a safe, generic message to prevent leaking internal verification details.
 */
public class JwtValidationException extends StaffAuthenticationException {

    public static final String DEFAULT_MESSAGE = "Invalid or expired access token.";

    public JwtValidationException() {
        super(DEFAULT_MESSAGE);
    }

    public JwtValidationException(String message) {
        super(message != null && !message.isBlank() ? message : DEFAULT_MESSAGE);
    }

    public JwtValidationException(String message, Throwable cause) {
        super(message != null && !message.isBlank() ? message : DEFAULT_MESSAGE, cause);
    }
}
