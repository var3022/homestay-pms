package com.homestay.pms_backend.security.auth;

/**
 * Sanitized caller context for database security audit events.
 */
public record ClientContext(
        String ipAddress,
        String userAgent
) {
}
