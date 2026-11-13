package com.homestay.pms_backend.security.authorization;

import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative staff role assignment record loaded from vw_staff_effective_permissions.
 *
 * @param staffUserId unique UUID of the staff user
 * @param email       staff email address
 * @param role        authoritative StaffRole
 * @param propertyId  UUID of the property (null for global roles)
 * @param isGlobal    true if assigned globally, false if property-scoped
 * @param assignedAt  timestamp when the role assignment was created
 */
public record StaffPropertyAssignment(
        UUID staffUserId,
        String email,
        StaffRole role,
        UUID propertyId,
        boolean isGlobal,
        Instant assignedAt
) {
    public StaffPropertyAssignment {
        if (staffUserId == null) {
            throw new IllegalArgumentException("staffUserId must not be null");
        }
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (isGlobal && propertyId != null) {
            throw new IllegalArgumentException("Global role assignment must have null propertyId");
        }
        if (!isGlobal && propertyId == null) {
            throw new IllegalArgumentException("Property-scoped role assignment must have non-null propertyId");
        }
    }
}
