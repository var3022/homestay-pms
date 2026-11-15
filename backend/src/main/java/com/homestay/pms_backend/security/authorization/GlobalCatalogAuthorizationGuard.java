package com.homestay.pms_backend.security.authorization;

import com.homestay.pms_backend.config.SecurityEnforcementProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Authoritative Global Catalog Authorization Guard.
 *
 * Centralizes RBAC enforcement for globally shared PMS configuration catalogs:
 * - Booking Sources (/api/v1/booking-sources)
 * - Amenities (/api/v1/amenities)
 * - Bed Types (/api/v1/bed-types)
 *
 * Key Security Guarantees:
 * 1. Derives authenticated staff identity exclusively from SecurityContext via StaffAuthorizationService.
 * 2. Evaluates global SUPER_ADMIN status dynamically against database-backed permissions (no cross-request caching).
 * 3. Never trusts client-supplied staff identity, roles, or headers.
 * 4. Fails closed with 403 AccessDeniedException if caller lacks global SUPER_ADMIN status for mutation.
 * 5. Strictly isolates global master catalogs from property-scoped roles (PROPERTY_ADMIN, RECEPTIONIST, ACCOUNTANT, VIEWER).
 * 6. Preserves development compatibility when security enforcement is explicitly disabled.
 */
@Component
public class GlobalCatalogAuthorizationGuard {

    private static final Logger log = LoggerFactory.getLogger(GlobalCatalogAuthorizationGuard.class);

    private final StaffAuthorizationService authorizationService;
    private final SecurityEnforcementProperties enforcementProperties;

    @Autowired
    public GlobalCatalogAuthorizationGuard(
            StaffAuthorizationService authorizationService,
            SecurityEnforcementProperties enforcementProperties
    ) {
        this.authorizationService = Objects.requireNonNull(authorizationService, "StaffAuthorizationService must not be null");
        this.enforcementProperties = Objects.requireNonNull(enforcementProperties, "SecurityEnforcementProperties must not be null");
    }

    /**
     * Checks if JWT/RBAC security enforcement is actively enabled.
     */
    public boolean isEnforcementEnabled() {
        return enforcementProperties.isEnabled();
    }

    /**
     * Enforces that the current authenticated principal possesses global SUPER_ADMIN status.
     * Must be called at the beginning of all global catalog mutation operations (create, update, activate, deactivate).
     *
     * @throws AccessDeniedException if caller is not authorized as global SUPER_ADMIN
     */
    public void requireGlobalSuperAdminForMutation() {
        if (!isEnforcementEnabled()) {
            return;
        }
        if (!authorizationService.isGlobalSuperAdmin()) {
            log.warn("Access denied: Global catalog mutation requires global SUPER_ADMIN authority.");
            throw new AccessDeniedException("Access is denied.");
        }
    }
}
