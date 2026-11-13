package com.homestay.pms_backend.security.authorization;

import java.util.Optional;

/**
 * Authoritative Staff Roles recognized by the PMS RBAC model.
 *
 * Conforms strictly to the PostgreSQL V8 role schema constraints:
 * - staff_roles: 'SUPER_ADMIN', 'PROPERTY_ADMIN', 'RECEPTIONIST', 'ACCOUNTANT', 'VIEWER'
 * - staff_global_roles constraint chk_staff_global_role_super_admin: role_code = 'SUPER_ADMIN'
 * - staff_property_roles constraint chk_staff_property_role_no_super_admin: role_code != 'SUPER_ADMIN'
 */
public enum StaffRole {

    /**
     * Universal administrative role. Global scope only (cannot be property-scoped).
     */
    SUPER_ADMIN("SUPER_ADMIN", true, false),

    /**
     * Property administrator. Manages configuration, rooms, reservations, and operational staff on assigned properties.
     */
    PROPERTY_ADMIN("PROPERTY_ADMIN", false, true),

    /**
     * Front-desk operations. Manages reservations, room statuses, and guest check-ins on assigned properties.
     */
    RECEPTIONIST("RECEPTIONIST", false, true),

    /**
     * Financial auditor / accountant. Read-only operational and billing reconciliation on assigned properties.
     */
    ACCOUNTANT("ACCOUNTANT", false, true),

    /**
     * Read-only viewer. Strictly read-only visibility into property and reservation state on assigned properties.
     */
    VIEWER("VIEWER", false, true);

    private final String code;
    private final boolean globalAllowed;
    private final boolean propertyAllowed;

    StaffRole(String code, boolean globalAllowed, boolean propertyAllowed) {
        this.code = code;
        this.globalAllowed = globalAllowed;
        this.propertyAllowed = propertyAllowed;
    }

    public String getCode() {
        return code;
    }

    public boolean isGlobalAllowed() {
        return globalAllowed;
    }

    public boolean isPropertyAllowed() {
        return propertyAllowed;
    }

    /**
     * Resolves a StaffRole from its exact database role code.
     *
     * @param code database role code
     * @return matching StaffRole
     * @throws IllegalArgumentException if code is null, blank, or unrecognized
     */
    public static StaffRole fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Staff role code must not be null or blank");
        }
        for (StaffRole role : values()) {
            if (role.code.equalsIgnoreCase(code.trim())) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown staff role code: " + code);
    }

    /**
     * Safely finds a StaffRole from a code string without throwing.
     *
     * @param code role code
     * @return Optional containing the matching StaffRole, or empty if unrecognized
     */
    public static Optional<StaffRole> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        for (StaffRole role : values()) {
            if (role.code.equalsIgnoreCase(code.trim())) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
