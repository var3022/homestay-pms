package com.homestay.pms_backend.security;

import java.util.UUID;

/**
 * Minimal verified staff identity returned upon successful authentication.
 * Omits credentials, password hashes, failed attempts, and internal audit details.
 */
public record AuthenticatedStaff(
        UUID id,
        String email,
        String firstName,
        String lastName,
        String phone
) {
}
