package com.homestay.pms_backend.exception;

/**
 * Thrown when an HTTP request fails Origin or CSRF validation checks.
 */
public class OriginValidationException extends RuntimeException {

    public OriginValidationException(String message) {
        super(message);
    }
}
