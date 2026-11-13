package com.homestay.pms_backend.security;

import java.security.Principal;
import java.util.UUID;

/**
 * Minimal authenticated staff principal containing verified staff identity.
 *
 * Populated strictly from authoritative database records upon successful
 * JWT validation and real-time staff account verification.
 *
 * Security Constraints:
 * 1. Does not contain credentials, password hashes, or sensitive tokens.
 * 2. Does not invent or assume administrative roles (e.g. SUPER_ADMIN).
 * 3. Does not contain unverified authorization claims from JWT payloads.
 * 4. Property permissions are resolved authoritatively in subsequent RBAC evaluations.
 */
public record AuthenticatedStaffPrincipal(
        UUID staffUserId,
        String email,
        String firstName,
        String lastName
) implements Principal {

    @Override
    public String getName() {
        return email != null ? email : staffUserId.toString();
    }
}
