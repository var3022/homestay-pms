package com.homestay.pms_backend.security;

/**
 * Thrown when staff authentication fails.
 * Uses a uniform, generic external message ("Invalid email or password.") to prevent
 * account-enumeration timing or message-based oracle attacks.
 */
public class StaffAuthenticationException extends RuntimeException {

    public static final String DEFAULT_MESSAGE = "Invalid email or password.";

    public StaffAuthenticationException() {
        super(DEFAULT_MESSAGE);
    }

    public StaffAuthenticationException(String message) {
        super(message != null && !message.isBlank() ? message : DEFAULT_MESSAGE);
    }

    public StaffAuthenticationException(String message, Throwable cause) {
        super(message != null && !message.isBlank() ? message : DEFAULT_MESSAGE, cause);
    }
}
