package com.homestay.pms_backend.security.authorization;

import com.homestay.pms_backend.security.AuthenticatedStaffPrincipal;
import com.homestay.pms_backend.security.auth.StaffAuthenticationToken;
import com.homestay.pms_backend.security.jwt.StaffJwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StaffAuthorizationService Unit Tests")
class StaffAuthorizationServiceUnitTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private StaffAuthorizationServiceImpl authorizationService;

    @BeforeEach
    void setUp() {
        authorizationService = new StaffAuthorizationServiceImpl(jdbcTemplate);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setSecurityContext(AuthenticatedStaffPrincipal principal) {
        Instant now = Instant.now();
        StaffJwtClaims claims = new StaffJwtClaims(
                principal.staffUserId(),
                "homestay-pms",
                "pms-clients",
                now,
                now,
                now.plusSeconds(900),
                "test-jti",
                "Bearer",
                "key-1"
        );
        StaffAuthenticationToken token = new StaffAuthenticationToken(principal, claims);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    // =========================================================================
    // 1. SecurityContext & Identity Extraction Tests
    // =========================================================================

    @Test
    @DisplayName("Request-facing methods fail closed when SecurityContext is empty")
    void shouldFailClosedWhenSecurityContextIsEmpty() {
        UUID propertyId = UUID.randomUUID();

        assertThat(authorizationService.getCurrentPrincipal()).isEmpty();
        assertThat(authorizationService.isGlobalSuperAdmin()).isFalse();
        assertThat(authorizationService.isAssignedToProperty(propertyId)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyId, StaffOperation.ROOM_READ)).isFalse();
        assertThat(authorizationService.hasPropertyRole(propertyId, StaffRole.PROPERTY_ADMIN)).isFalse();
        assertThat(authorizationService.getStaffAssignments()).isEmpty();
        assertThat(authorizationService.getAssignedPropertyIds()).isEmpty();
    }

    @Test
    @DisplayName("Request-facing methods fail closed when Authentication is not authenticated")
    void shouldFailClosedWhenAuthenticationIsNotAuthenticated() {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(auth);

        UUID propertyId = UUID.randomUUID();
        assertThat(authorizationService.getCurrentPrincipal()).isEmpty();
        assertThat(authorizationService.isGlobalSuperAdmin()).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyId, StaffOperation.ROOM_READ)).isFalse();
    }

    @Test
    @DisplayName("Request-facing methods fail closed when Principal is not an AuthenticatedStaffPrincipal")
    void shouldFailClosedWhenPrincipalIsWrongType() {
        Authentication auth = new UsernamePasswordAuthenticationToken("anonymousUser", "credentials");
        SecurityContextHolder.getContext().setAuthentication(auth);

        UUID propertyId = UUID.randomUUID();
        assertThat(authorizationService.getCurrentPrincipal()).isEmpty();
        assertThat(authorizationService.isGlobalSuperAdmin()).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyId, StaffOperation.ROOM_READ)).isFalse();
    }

    @Test
    @DisplayName("Request-facing methods fail closed when AuthenticatedStaffPrincipal has null staffUserId")
    void shouldFailClosedWhenStaffUserIdIsNull() {
        AuthenticatedStaffPrincipal invalidPrincipal = new AuthenticatedStaffPrincipal(null, "user@test.com", "First", "Last");
        setSecurityContext(invalidPrincipal);

        UUID propertyId = UUID.randomUUID();
        assertThat(authorizationService.getCurrentPrincipal()).isEmpty();
        assertThat(authorizationService.isGlobalSuperAdmin()).isFalse();
        assertThat(authorizationService.hasPropertyAccess(propertyId, StaffOperation.ROOM_READ)).isFalse();
    }

    // =========================================================================
    // 2. Null Parameter Handling (Fail-Closed)
    // =========================================================================

    @Test
    @DisplayName("hasPropertyAccess rejects null staffUserId, propertyId, or operation")
    void hasPropertyAccessRejectsNulls() {
        UUID staffId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();

        assertThat(authorizationService.hasPropertyAccess(null, propertyId, StaffOperation.ROOM_READ)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(staffId, null, StaffOperation.ROOM_READ)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(staffId, propertyId, null)).isFalse();
    }

    @Test
    @DisplayName("isGlobalSuperAdmin rejects null staffUserId")
    void isGlobalSuperAdminRejectsNull() {
        assertThat(authorizationService.isGlobalSuperAdmin(null)).isFalse();
    }

    @Test
    @DisplayName("isAssignedToProperty rejects null staffUserId or propertyId")
    void isAssignedToPropertyRejectsNulls() {
        UUID staffId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();

        assertThat(authorizationService.isAssignedToProperty(null, propertyId)).isFalse();
        assertThat(authorizationService.isAssignedToProperty(staffId, null)).isFalse();
    }

    @Test
    @DisplayName("hasPropertyRole rejects null staffUserId, propertyId, or empty roles")
    void hasPropertyRoleRejectsNulls() {
        UUID staffId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();

        assertThat(authorizationService.hasPropertyRole((UUID) null, propertyId, StaffRole.PROPERTY_ADMIN)).isFalse();
        assertThat(authorizationService.hasPropertyRole(staffId, (UUID) null, StaffRole.PROPERTY_ADMIN)).isFalse();
        assertThat(authorizationService.hasPropertyRole(staffId, propertyId, (StaffRole[]) null)).isFalse();
        assertThat(authorizationService.hasPropertyRole(staffId, propertyId, new StaffRole[0])).isFalse();
        assertThat(authorizationService.hasPropertyRole((UUID) null, StaffRole.PROPERTY_ADMIN)).isFalse();
        assertThat(authorizationService.hasPropertyRole(propertyId, (StaffRole[]) null)).isFalse();
        assertThat(authorizationService.hasPropertyRole(propertyId, new StaffRole[0])).isFalse();
    }

    @Test
    @DisplayName("getStaffAssignments and getAssignedPropertyIds reject null staffUserId safely")
    void getAssignmentsRejectsNull() {
        assertThat(authorizationService.getStaffAssignments(null)).isEmpty();
        assertThat(authorizationService.getAssignedPropertyIds(null)).isEmpty();
    }

    // =========================================================================
    // 3. Database Infrastructure Failure Resilience (Fail-Closed)
    // =========================================================================

    @Test
    @DisplayName("Fails closed with false or empty collections when database throws DataAccessException")
    @SuppressWarnings("unchecked")
    void shouldFailClosedOnDatabaseException() {
        UUID staffId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(staffId)))
                .thenThrow(new DataAccessException("Simulated database outage") {});

        assertThat(authorizationService.getStaffAssignments(staffId)).isEmpty();
        assertThat(authorizationService.isGlobalSuperAdmin(staffId)).isFalse();
        assertThat(authorizationService.isAssignedToProperty(staffId, propertyId)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(staffId, propertyId, StaffOperation.ROOM_READ)).isFalse();
        assertThat(authorizationService.hasPropertyRole(staffId, propertyId, StaffRole.PROPERTY_ADMIN)).isFalse();
        assertThat(authorizationService.getAssignedPropertyIds(staffId)).isEmpty();
    }

    // =========================================================================
    // 4. RowMapper & Schema Constraint Defense-In-Depth
    // =========================================================================

    @Test
    @DisplayName("RowMapper strictly filters out corrupted, unknown, or constraint-violating rows")
    @SuppressWarnings("unchecked")
    void shouldFilterInvalidRowsFromDatabase() throws SQLException {
        UUID staffId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();

        // 1. Unknown role code
        ResultSet rsUnknown = mock(ResultSet.class);
        when(rsUnknown.getObject("staff_user_id", UUID.class)).thenReturn(staffId);
        when(rsUnknown.getString("role_code")).thenReturn("HACKER_ROLE");

        // 2. PROPERTY_ADMIN with is_global=true (violates constraint)
        ResultSet rsInvalidGlobal = mock(ResultSet.class);
        when(rsInvalidGlobal.getObject("staff_user_id", UUID.class)).thenReturn(staffId);
        when(rsInvalidGlobal.getString("role_code")).thenReturn("PROPERTY_ADMIN");
        when(rsInvalidGlobal.getBoolean("is_global")).thenReturn(true);

        // 3. SUPER_ADMIN with is_global=false (violates constraint)
        ResultSet rsInvalidProperty = mock(ResultSet.class);
        when(rsInvalidProperty.getObject("staff_user_id", UUID.class)).thenReturn(staffId);
        when(rsInvalidProperty.getString("role_code")).thenReturn("SUPER_ADMIN");
        when(rsInvalidProperty.getBoolean("is_global")).thenReturn(false);

        // 4. Scoped role with null property_id
        ResultSet rsNullProperty = mock(ResultSet.class);
        when(rsNullProperty.getObject("staff_user_id", UUID.class)).thenReturn(staffId);
        when(rsNullProperty.getString("role_code")).thenReturn("RECEPTIONIST");
        when(rsNullProperty.getBoolean("is_global")).thenReturn(false);
        when(rsNullProperty.getObject("property_id", UUID.class)).thenReturn(null);

        // 5. Valid row
        ResultSet rsValid = mock(ResultSet.class);
        when(rsValid.getObject("staff_user_id", UUID.class)).thenReturn(staffId);
        when(rsValid.getString("role_code")).thenReturn("PROPERTY_ADMIN");
        when(rsValid.getBoolean("is_global")).thenReturn(false);
        when(rsValid.getObject("property_id", UUID.class)).thenReturn(propertyId);
        when(rsValid.getString("email")).thenReturn("valid@test.com");
        when(rsValid.getTimestamp("assigned_at")).thenReturn(Timestamp.from(Instant.now()));

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(staffId)))
                .thenAnswer(invocation -> {
                    RowMapper<StaffPropertyAssignment> mapper = invocation.getArgument(1);
                    StaffPropertyAssignment a1 = mapper.mapRow(rsUnknown, 0);
                    StaffPropertyAssignment a2 = mapper.mapRow(rsInvalidGlobal, 1);
                    StaffPropertyAssignment a3 = mapper.mapRow(rsInvalidProperty, 2);
                    StaffPropertyAssignment a4 = mapper.mapRow(rsNullProperty, 3);
                    StaffPropertyAssignment a5 = mapper.mapRow(rsValid, 4);

                    return java.util.Arrays.asList(a1, a2, a3, a4, a5);
                });

        List<StaffPropertyAssignment> result = authorizationService.getStaffAssignments(staffId);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).role()).isEqualTo(StaffRole.PROPERTY_ADMIN);
        assertThat(result.get(0).propertyId()).isEqualTo(propertyId);
        assertThat(result.get(0).email()).isEqualTo("valid@test.com");
    }

    // =========================================================================
    // 5. Authorization Decision Tests with Mocked Roles
    // =========================================================================

    @Test
    @DisplayName("Global SUPER_ADMIN has access to any property for authorized operations")
    @SuppressWarnings("unchecked")
    void globalSuperAdminHasUniversalAccess() {
        UUID staffId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();

        StaffPropertyAssignment superAdmin = new StaffPropertyAssignment(
                staffId, "admin@homestay.com", StaffRole.SUPER_ADMIN, null, true, Instant.now()
        );
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(staffId)))
                .thenReturn(List.of(superAdmin));

        assertThat(authorizationService.isGlobalSuperAdmin(staffId)).isTrue();
        assertThat(authorizationService.isAssignedToProperty(staffId, propertyId)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(staffId, propertyId, StaffOperation.PROPERTY_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(staffId, propertyId, StaffOperation.ROOM_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(staffId, propertyId, StaffOperation.RESERVATION_MANAGE)).isTrue();
    }

    @Test
    @DisplayName("PROPERTY_ADMIN has administrative access on assigned property and zero access on unassigned")
    @SuppressWarnings("unchecked")
    void propertyAdminScopedAccess() {
        UUID staffId = UUID.randomUUID();
        UUID assignedProp = UUID.randomUUID();
        UUID unassignedProp = UUID.randomUUID();

        StaffPropertyAssignment propAdmin = new StaffPropertyAssignment(
                staffId, "padmin@homestay.com", StaffRole.PROPERTY_ADMIN, assignedProp, false, Instant.now()
        );
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(staffId)))
                .thenReturn(List.of(propAdmin));

        assertThat(authorizationService.isGlobalSuperAdmin(staffId)).isFalse();
        // Assigned property
        assertThat(authorizationService.isAssignedToProperty(staffId, assignedProp)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(staffId, assignedProp, StaffOperation.PROPERTY_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(staffId, assignedProp, StaffOperation.ROOM_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyRole(staffId, assignedProp, StaffRole.PROPERTY_ADMIN)).isTrue();

        // Unassigned property
        assertThat(authorizationService.isAssignedToProperty(staffId, unassignedProp)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(staffId, unassignedProp, StaffOperation.ROOM_READ)).isFalse();
        assertThat(authorizationService.hasPropertyAccess(staffId, unassignedProp, StaffOperation.PROPERTY_MANAGE)).isFalse();
    }

    @Test
    @DisplayName("Request-facing methods extract verified principal from SecurityContext")
    @SuppressWarnings("unchecked")
    void requestFacingMethodsUseSecurityContext() {
        UUID staffId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();
        AuthenticatedStaffPrincipal principal = new AuthenticatedStaffPrincipal(staffId, "staff@test.com", "Alice", "Smith");
        setSecurityContext(principal);

        StaffPropertyAssignment assignment = new StaffPropertyAssignment(
                staffId, principal.email(), StaffRole.RECEPTIONIST, propertyId, false, Instant.now()
        );
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(staffId)))
                .thenReturn(List.of(assignment));

        assertThat(authorizationService.hasPropertyAccess(propertyId, StaffOperation.RESERVATION_MANAGE)).isTrue();
        assertThat(authorizationService.hasPropertyAccess(propertyId, StaffOperation.ROOM_MANAGE)).isFalse();
        assertThat(authorizationService.isGlobalSuperAdmin()).isFalse();
        assertThat(authorizationService.isAssignedToProperty(propertyId)).isTrue();
        assertThat(authorizationService.getAssignedPropertyIds()).containsExactly(propertyId);
    }
}
