package com.homestay.pms_backend.security.authorization;

import com.homestay.pms_backend.security.AuthenticatedStaffPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Production implementation of StaffAuthorizationService.
 *
 * Direct database authorization provider evaluating effective permissions from the
 * authoritative PostgreSQL view vw_staff_effective_permissions over the least-privilege
 * pms_app application DataSource.
 *
 * Characteristics:
 * 1. Executes parameterized SQL queries directly (no string concatenation).
 * 2. Un-cached: every authorization evaluation checks live database state so revocations
 *    and deactivations take effect immediately.
 * 3. Fails closed on any unexpected database error, deactivated user, locked account,
 *    unrecognized role code, or missing SecurityContext.
 */
@Service
public class StaffAuthorizationServiceImpl implements StaffAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(StaffAuthorizationServiceImpl.class);

    private static final String SQL_SELECT_EFFECTIVE_PERMISSIONS = """
            SELECT staff_user_id, email, is_active, is_locked, role_code, property_id, is_global, assigned_at
            FROM vw_staff_effective_permissions
            WHERE staff_user_id = ? AND is_active = TRUE AND is_locked = FALSE
            """;

    private static final StaffPropertyAssignmentRowMapper ROW_MAPPER = new StaffPropertyAssignmentRowMapper();

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public StaffAuthorizationServiceImpl(@Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<AuthenticatedStaffPrincipal> getCurrentPrincipal() {
        return resolveCurrentPrincipal();
    }

    @Override
    public boolean isGlobalSuperAdmin() {
        return resolveCurrentPrincipal()
                .map(p -> isGlobalSuperAdmin(p.staffUserId()))
                .orElse(false);
    }

    @Override
    public boolean isGlobalSuperAdmin(UUID staffUserId) {
        if (staffUserId == null) {
            return false;
        }
        try {
            List<StaffPropertyAssignment> assignments = getStaffAssignments(staffUserId);
            return assignments.stream()
                    .anyMatch(a -> a.isGlobal() && a.role() == StaffRole.SUPER_ADMIN);
        } catch (Exception e) {
            log.error("Unexpected error evaluating global SUPER_ADMIN for staff user [{}]: {}", staffUserId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isAssignedToProperty(UUID propertyId) {
        return resolveCurrentPrincipal()
                .map(p -> isAssignedToProperty(p.staffUserId(), propertyId))
                .orElse(false);
    }

    @Override
    public boolean isAssignedToProperty(UUID staffUserId, UUID propertyId) {
        if (staffUserId == null || propertyId == null) {
            return false;
        }
        try {
            List<StaffPropertyAssignment> assignments = getStaffAssignments(staffUserId);
            return assignments.stream()
                    .anyMatch(a -> (a.isGlobal() && a.role() == StaffRole.SUPER_ADMIN)
                            || (!a.isGlobal() && propertyId.equals(a.propertyId())));
        } catch (Exception e) {
            log.error("Unexpected error evaluating property assignment for staff user [{}] and property [{}]: {}",
                    staffUserId, propertyId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean hasPropertyAccess(UUID propertyId, StaffOperation operation) {
        return resolveCurrentPrincipal()
                .map(p -> hasPropertyAccess(p.staffUserId(), propertyId, operation))
                .orElse(false);
    }

    @Override
    public boolean hasPropertyAccess(UUID staffUserId, UUID propertyId, StaffOperation operation) {
        if (staffUserId == null || propertyId == null || operation == null) {
            return false;
        }
        try {
            List<StaffPropertyAssignment> assignments = getStaffAssignments(staffUserId);
            if (assignments.isEmpty()) {
                return false;
            }

            for (StaffPropertyAssignment assignment : assignments) {
                if (assignment.isGlobal() && assignment.role() == StaffRole.SUPER_ADMIN) {
                    if (operation.isAuthorized(StaffRole.SUPER_ADMIN)) {
                        return true;
                    }
                } else if (!assignment.isGlobal() && propertyId.equals(assignment.propertyId())) {
                    if (operation.isAuthorized(assignment.role())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            log.error("Unexpected error evaluating property access for staff user [{}], property [{}], operation [{}]: {}",
                    staffUserId, propertyId, operation, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean hasPropertyRole(UUID propertyId, StaffRole... roles) {
        return resolveCurrentPrincipal()
                .map(p -> hasPropertyRole(p.staffUserId(), propertyId, roles))
                .orElse(false);
    }

    @Override
    public boolean hasPropertyRole(UUID staffUserId, UUID propertyId, StaffRole... roles) {
        if (staffUserId == null || propertyId == null || roles == null || roles.length == 0) {
            return false;
        }

        Set<StaffRole> targetRoles = EnumSet.copyOf(Arrays.asList(roles));
        try {
            List<StaffPropertyAssignment> assignments = getStaffAssignments(staffUserId);
            for (StaffPropertyAssignment assignment : assignments) {
                if (assignment.isGlobal() && assignment.role() == StaffRole.SUPER_ADMIN) {
                    if (targetRoles.contains(StaffRole.SUPER_ADMIN)) {
                        return true;
                    }
                } else if (!assignment.isGlobal() && propertyId.equals(assignment.propertyId())) {
                    if (targetRoles.contains(assignment.role())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            log.error("Unexpected error evaluating property role for staff user [{}] and property [{}]: {}",
                    staffUserId, propertyId, e.getMessage());
            return false;
        }
    }

    @Override
    public List<StaffPropertyAssignment> getStaffAssignments() {
        return resolveCurrentPrincipal()
                .map(p -> getStaffAssignments(p.staffUserId()))
                .orElseGet(Collections::emptyList);
    }

    @Override
    public List<StaffPropertyAssignment> getStaffAssignments(UUID staffUserId) {
        if (staffUserId == null) {
            return Collections.emptyList();
        }
        try {
            List<StaffPropertyAssignment> raw = jdbcTemplate.query(
                    SQL_SELECT_EFFECTIVE_PERMISSIONS,
                    ROW_MAPPER,
                    staffUserId
            );
            return raw.stream().filter(Objects::nonNull).toList();
        } catch (DataAccessException e) {
            log.error("Database error while retrieving assignments for staff user [{}]: {}", staffUserId, e.getMessage());
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Unexpected error retrieving assignments for staff user [{}]: {}", staffUserId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public Set<UUID> getAssignedPropertyIds() {
        return resolveCurrentPrincipal()
                .map(p -> getAssignedPropertyIds(p.staffUserId()))
                .orElseGet(Collections::emptySet);
    }

    @Override
    public Set<UUID> getAssignedPropertyIds(UUID staffUserId) {
        if (staffUserId == null) {
            return Collections.emptySet();
        }
        try {
            List<StaffPropertyAssignment> assignments = getStaffAssignments(staffUserId);
            return assignments.stream()
                    .filter(a -> !a.isGlobal() && a.propertyId() != null)
                    .map(StaffPropertyAssignment::propertyId)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (Exception e) {
            log.error("Unexpected error retrieving assigned property IDs for staff user [{}]: {}", staffUserId, e.getMessage());
            return Collections.emptySet();
        }
    }

    private Optional<AuthenticatedStaffPrincipal> resolveCurrentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            log.debug("No authenticated SecurityContext present during authorization check.");
            return Optional.empty();
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof AuthenticatedStaffPrincipal authenticatedStaff) {
            if (authenticatedStaff.staffUserId() == null) {
                log.warn("AuthenticatedStaffPrincipal present but has null staffUserId. Failing closed.");
                return Optional.empty();
            }
            return Optional.of(authenticatedStaff);
        }
        log.debug("SecurityContext principal is not an AuthenticatedStaffPrincipal (was {}). Failing closed.",
                principal != null ? principal.getClass().getName() : "null");
        return Optional.empty();
    }

    private static final class StaffPropertyAssignmentRowMapper implements RowMapper<StaffPropertyAssignment> {
        @Override
        public StaffPropertyAssignment mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID staffUserId = rs.getObject("staff_user_id", UUID.class);
            String roleCode = rs.getString("role_code");

            Optional<StaffRole> optRole = StaffRole.findByCode(roleCode);
            if (optRole.isEmpty()) {
                log.warn("Unknown role code [{}] encountered for staff user [{}]. Rejecting assignment.", roleCode, staffUserId);
                return null;
            }

            StaffRole role = optRole.get();
            boolean isGlobal = rs.getBoolean("is_global");

            if (isGlobal && !role.isGlobalAllowed()) {
                log.warn("Role [{}] is not permitted as a global role for staff user [{}]. Rejecting assignment.", roleCode, staffUserId);
                return null;
            }
            if (!isGlobal && !role.isPropertyAllowed()) {
                log.warn("Role [{}] is not permitted as a property-scoped role for staff user [{}]. Rejecting assignment.", roleCode, staffUserId);
                return null;
            }

            UUID propertyId = rs.getObject("property_id", UUID.class);
            if (!isGlobal && propertyId == null) {
                log.warn("Property-scoped role [{}] has null property_id for staff user [{}]. Rejecting assignment.", roleCode, staffUserId);
                return null;
            }

            String email = rs.getString("email");
            Timestamp ts = rs.getTimestamp("assigned_at");
            Instant assignedAt = ts != null ? ts.toInstant() : null;

            return new StaffPropertyAssignment(staffUserId, email, role, propertyId, isGlobal, assignedAt);
        }
    }
}
