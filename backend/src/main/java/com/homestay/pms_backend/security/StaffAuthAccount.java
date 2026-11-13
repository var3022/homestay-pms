package com.homestay.pms_backend.security;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal staff authentication domain model.
 * Holds database-retrieved staff identity, lockout state, and the password hash
 * strictly for in-memory cryptographic verification by StaffAuthenticationService.
 *
 * SENSITIVITY NOTICE:
 * This record must NEVER be returned to external callers or exposed across API boundaries.
 * The toString() method explicitly redacts the password hash to protect against accidental log exposure.
 */
public record StaffAuthAccount(
        UUID id,
        String email,
        String passwordHash,
        String firstName,
        String lastName,
        String phone,
        boolean isActive,
        boolean isLocked,
        int failedLoginAttempts,
        Instant lockoutUntil,
        Instant lastLoginAt,
        Instant passwordChangedAt,
        Instant createdAt,
        Instant updatedAt
) {
    @Override
    public String toString() {
        return "StaffAuthAccount[id=" + id +
                ", email=" + email +
                ", firstName=" + firstName +
                ", lastName=" + lastName +
                ", isActive=" + isActive +
                ", isLocked=" + isLocked +
                ", failedLoginAttempts=" + failedLoginAttempts +
                ", lockoutUntil=" + lockoutUntil +
                ", passwordHash=[REDACTED]]";
    }
}
