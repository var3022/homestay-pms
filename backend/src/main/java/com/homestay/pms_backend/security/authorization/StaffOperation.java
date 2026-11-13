package com.homestay.pms_backend.security.authorization;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * PMS Domain Operations and their explicit Staff Role allowlists.
 *
 * Implements a centralized least-privilege operation-to-role policy:
 * - VIEWER has strictly read-only visibility and is denied all state mutations.
 * - ACCOUNTANT has financial/operational reconciliation read visibility but is denied
 *   room management, reservation mutations, and guest identity document privileges.
 * - RECEPTIONIST has front-desk operational authority but is denied property/room administrative configuration.
 * - PROPERTY_ADMIN has administrative authority scoped strictly to assigned properties.
 * - SUPER_ADMIN has global authority across all properties.
 */
public enum StaffOperation {

    /**
     * Read property metadata, settings, and general configurations.
     */
    PROPERTY_READ(Set.of(
            StaffRole.SUPER_ADMIN,
            StaffRole.PROPERTY_ADMIN,
            StaffRole.RECEPTIONIST,
            StaffRole.ACCOUNTANT,
            StaffRole.VIEWER
    )),

    /**
     * Administrative modification of property settings, policies, and operational details.
     */
    PROPERTY_MANAGE(Set.of(
            StaffRole.SUPER_ADMIN,
            StaffRole.PROPERTY_ADMIN
    )),

    /**
     * Read room details, types, beds, and availability.
     */
    ROOM_READ(Set.of(
            StaffRole.SUPER_ADMIN,
            StaffRole.PROPERTY_ADMIN,
            StaffRole.RECEPTIONIST,
            StaffRole.ACCOUNTANT,
            StaffRole.VIEWER
    )),

    /**
     * Administrative configuration of rooms, room types, and bed layouts.
     */
    ROOM_MANAGE(Set.of(
            StaffRole.SUPER_ADMIN,
            StaffRole.PROPERTY_ADMIN
    )),

    /**
     * Operational update of housekeeping and maintenance room statuses.
     */
    ROOM_STATUS_UPDATE(Set.of(
            StaffRole.SUPER_ADMIN,
            StaffRole.PROPERTY_ADMIN,
            StaffRole.RECEPTIONIST
    )),

    /**
     * Read reservation schedules, statuses, and assigned rooms.
     */
    RESERVATION_READ(Set.of(
            StaffRole.SUPER_ADMIN,
            StaffRole.PROPERTY_ADMIN,
            StaffRole.RECEPTIONIST,
            StaffRole.ACCOUNTANT,
            StaffRole.VIEWER
    )),

    /**
     * Create, modify, confirm, or cancel guest reservations.
     */
    RESERVATION_MANAGE(Set.of(
            StaffRole.SUPER_ADMIN,
            StaffRole.PROPERTY_ADMIN,
            StaffRole.RECEPTIONIST
    )),

    /**
     * Read full guest profiles including sensitive PII (names, phone numbers, email addresses,
     * and identity document numbers).
     *
     * Restricted to operational roles requiring identity verification during check-in/management:
     * SUPER_ADMIN, PROPERTY_ADMIN, and RECEPTIONIST.
     *
     * Privacy & Security Constraints:
     * - VIEWER and ACCOUNTANT are strictly excluded from full guest-profile reads.
     * - Sanitized guest summaries can be introduced in a future hardening step with a distinct
     *   operation and restricted DTO.
     */
    GUEST_READ(Set.of(
            StaffRole.SUPER_ADMIN,
            StaffRole.PROPERTY_ADMIN,
            StaffRole.RECEPTIONIST
    )),

    /**
     * Create and update guest profiles.
     */
    GUEST_MANAGE(Set.of(
            StaffRole.SUPER_ADMIN,
            StaffRole.PROPERTY_ADMIN,
            StaffRole.RECEPTIONIST
    ));

    private final Set<StaffRole> authorizedRoles;

    StaffOperation(Set<StaffRole> authorizedRoles) {
        this.authorizedRoles = Collections.unmodifiableSet(EnumSet.copyOf(authorizedRoles));
    }

    /**
     * Returns the unmodifiable set of StaffRoles authorized for this operation.
     */
    public Set<StaffRole> getAuthorizedRoles() {
        return authorizedRoles;
    }

    /**
     * Determines whether the given role is authorized to perform this operation.
     *
     * @param role the staff role to check
     * @return true if authorized, false otherwise
     */
    public boolean isAuthorized(StaffRole role) {
        return role != null && authorizedRoles.contains(role);
    }
}
