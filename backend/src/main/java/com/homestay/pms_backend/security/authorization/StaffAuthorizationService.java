package com.homestay.pms_backend.security.authorization;

import com.homestay.pms_backend.security.AuthenticatedStaffPrincipal;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Authoritative, database-backed Staff Authorization Service.
 *
 * Evaluates staff roles and property-scoped permissions using authoritative
 * records from the vw_staff_effective_permissions view over the primary
 * least-privilege pms_app DataSource.
 *
 * Key Security Guarantees:
 * 1. Request-facing methods extract verified staff identity strictly from SecurityContext.
 * 2. Client-supplied staff UUID parameters are never trusted for authorization decisions.
 * 3. Scoped operations with null property IDs are strictly rejected.
 * 4. Deactivated or locked staff accounts immediately fail closed.
 * 5. Unknown or malformed role codes are rejected and fail closed.
 * 6. Permissions are evaluated authoritatively on each request (no caching).
 */
public interface StaffAuthorizationService {

    /**
     * Extracts the currently authenticated staff principal from SecurityContext, if present and verified.
     *
     * @return Optional containing the verified AuthenticatedStaffPrincipal, or empty if unauthenticated
     */
    Optional<AuthenticatedStaffPrincipal> getCurrentPrincipal();

    /**
     * Determines whether the currently authenticated staff member has global SUPER_ADMIN status.
     *
     * @return true if authenticated and global SUPER_ADMIN, false otherwise
     */
    boolean isGlobalSuperAdmin();

    /**
     * Internal/Trusted-Service Method: Determines whether the specified staff user has global SUPER_ADMIN status.
     *
     * <p><strong>Security Warning:</strong> Request-facing controllers must NOT call this method with
     * client-supplied user parameters. Use {@link #isGlobalSuperAdmin()} instead to extract the verified identity
     * from SecurityContext.
     *
     * @param staffUserId the staff UUID to inspect
     * @return true if the staff member has an active global SUPER_ADMIN assignment, false otherwise
     */
    boolean isGlobalSuperAdmin(UUID staffUserId);

    /**
     * Determines whether the currently authenticated staff member is assigned to the given property
     * (either directly via a property-scoped role or universally via global SUPER_ADMIN).
     *
     * @param propertyId the property UUID to check
     * @return true if assigned, false otherwise
     */
    boolean isAssignedToProperty(UUID propertyId);

    /**
     * Internal/Trusted-Service Method: Determines whether the specified staff user is assigned to the given property
     * (either directly via a property-scoped role or universally via global SUPER_ADMIN).
     *
     * <p><strong>Security Warning:</strong> Request-facing controllers must NOT call this method with
     * client-supplied user parameters. Use {@link #isAssignedToProperty(UUID)} instead.
     *
     * @param staffUserId the staff UUID to inspect
     * @param propertyId  the property UUID to check
     * @return true if assigned, false otherwise
     */
    boolean isAssignedToProperty(UUID staffUserId, UUID propertyId);

    /**
     * Evaluates whether the currently authenticated staff member is authorized to perform
     * the specified operation on the target property.
     *
     * @param propertyId the target property UUID (must not be null)
     * @param operation  the requested domain operation (must not be null)
     * @return true if authorized, false otherwise
     */
    boolean hasPropertyAccess(UUID propertyId, StaffOperation operation);

    /**
     * Internal/Trusted-Service Method: Evaluates whether the specified staff member is authorized to perform
     * the specified operation on the target property.
     *
     * <p><strong>Security Warning:</strong> Request-facing controllers must NOT call this method with
     * client-supplied user parameters. Use {@link #hasPropertyAccess(UUID, StaffOperation)} instead.
     *
     * @param staffUserId the staff UUID
     * @param propertyId  the target property UUID (must not be null)
     * @param operation   the requested domain operation (must not be null)
     * @return true if authorized, false otherwise
     */
    boolean hasPropertyAccess(UUID staffUserId, UUID propertyId, StaffOperation operation);

    /**
     * Evaluates whether the currently authenticated staff member holds any of the specified roles
     * on the target property (or global SUPER_ADMIN if SUPER_ADMIN is among the checked roles).
     *
     * @param propertyId the target property UUID
     * @param roles      one or more candidate roles
     * @return true if holding at least one matching role, false otherwise
     */
    boolean hasPropertyRole(UUID propertyId, StaffRole... roles);

    /**
     * Internal/Trusted-Service Method: Evaluates whether the specified staff member holds any of the specified roles
     * on the target property (or global SUPER_ADMIN if SUPER_ADMIN is among the checked roles).
     *
     * <p><strong>Security Warning:</strong> Request-facing controllers must NOT call this method with
     * client-supplied user parameters. Use {@link #hasPropertyRole(UUID, StaffRole...)} instead.
     *
     * @param staffUserId the staff UUID
     * @param propertyId  the target property UUID
     * @param roles       one or more candidate roles
     * @return true if holding at least one matching role, false otherwise
     */
    boolean hasPropertyRole(UUID staffUserId, UUID propertyId, StaffRole... roles);

    /**
     * Retrieves all authoritative active assignments for the currently authenticated staff member.
     *
     * @return immutable list of assignments, or empty list if unauthenticated or no assignments
     */
    List<StaffPropertyAssignment> getStaffAssignments();

    /**
     * Internal/Trusted-Service Method: Retrieves all authoritative active assignments for the specified staff member.
     *
     * <p><strong>Security Warning:</strong> Request-facing controllers must NOT call this method with
     * client-supplied user parameters. Use {@link #getStaffAssignments()} instead.
     *
     * @param staffUserId the staff UUID
     * @return immutable list of assignments, or empty list if no assignments or inactive
     */
    List<StaffPropertyAssignment> getStaffAssignments(UUID staffUserId);

    /**
     * Retrieves all distinct property UUIDs explicitly assigned to the currently authenticated staff member.
     * Note: Does not include null (global SUPER_ADMIN property_id is null).
     *
     * @return set of assigned property UUIDs, or empty set if none
     */
    Set<UUID> getAssignedPropertyIds();

    /**
     * Internal/Trusted-Service Method: Retrieves all distinct property UUIDs explicitly assigned to the specified staff member.
     * Note: Does not include null (global SUPER_ADMIN property_id is null).
     *
     * <p><strong>Security Warning:</strong> Request-facing controllers must NOT call this method with
     * client-supplied user parameters. Use {@link #getAssignedPropertyIds()} instead.
     *
     * @param staffUserId the staff UUID
     * @return set of assigned property UUIDs, or empty set if none
     */
    Set<UUID> getAssignedPropertyIds(UUID staffUserId);
}
