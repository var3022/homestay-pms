package com.homestay.pms_backend.dto.response;

import java.util.UUID;

/**
 * Minimal non-sensitive staff profile information returned in login response.
 * Excludes password hashes, lockout counts, and internal audit details.
 */
public record StaffInfoDto(
        UUID id,
        String email,
        String firstName,
        String lastName
) {
}
