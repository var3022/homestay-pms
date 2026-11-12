package com.homestay.pms_backend.security;

import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IsolatedDatabaseTest
@DisplayName("V8 Staff Authentication & RBAC Database Foundation Integration Tests")
class StaffAuthDatabaseFoundationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String CLEAN_VALIDATION_DB_URL = "jdbc:postgresql://localhost:5433/v8_clean_validation_db";
    private static final String DISPOSABLE_DB_URL = "jdbc:postgresql://localhost:5433/v7_disposable_db";
    private static final String ADMIN_USER = "pms_admin";
    private static final String ADMIN_PASS = "disposable_pass";

    private JdbcTemplate createAdminJdbcTemplate() {
        DriverManagerDataSource adminDs = new DriverManagerDataSource();
        adminDs.setDriverClassName("org.postgresql.Driver");
        adminDs.setUrl(DISPOSABLE_DB_URL);
        adminDs.setUsername(ADMIN_USER);
        adminDs.setPassword(ADMIN_PASS);
        return new JdbcTemplate(adminDs);
    }

    @FunctionalInterface
    interface AdminAction {
        void execute(JdbcTemplate adminJdbc);
    }

    private void withAdminTransaction(AdminAction action) {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(Objects.requireNonNull(adminJdbc.getDataSource()));
        TransactionStatus status = txManager.getTransaction(def);
        try {
            action.execute(adminJdbc);
        } finally {
            txManager.rollback(status);
        }
    }

    // =========================================================================
    // 1. Clean Database Flyway V1-V8 Migration & Upgrade Validation
    // =========================================================================

    @Test
    @DisplayName("Flyway V1–V8 executes successfully on clean disposable database, installing all 8 migrations in order")
    void shouldExecuteCleanV1ThroughV8Migration() {
        Flyway flyway = Flyway.configure()
                .dataSource(CLEAN_VALIDATION_DB_URL, ADMIN_USER, ADMIN_PASS)
                .locations("classpath:db/migration")
                .target("8")
                .cleanDisabled(false)
                .load();

        flyway.clean();
        var result = flyway.migrate();
        assertThat(result.migrationsExecuted).isEqualTo(8);

        MigrationInfo current = flyway.info().current();
        assertThat(current).isNotNull();
        assertThat(current.getVersion().getVersion()).isEqualTo("8");
        assertThat(current.getDescription()).isEqualTo("create staff auth and rbac schema");

        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).hasSize(8);
        for (MigrationInfo info : applied) {
            assertThat(info.getState().isApplied())
                    .as("Migration V%s must be successfully applied", info.getVersion().getVersion())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("All V8 tables, triggers, and views are owned by migration runner pms_admin")
    void shouldVerifyV8ObjectsOwnedByPmsAdmin() {
        List<String> v8Tables = List.of(
                "staff_users",
                "staff_roles",
                "staff_property_roles",
                "staff_global_roles",
                "staff_refresh_tokens",
                "staff_security_audit_events"
        );

        for (String table : v8Tables) {
            String owner = jdbcTemplate.queryForObject(
                    "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = ?",
                    String.class,
                    table
            );
            assertThat(owner)
                    .as("Table %s must be owned by pms_admin", table)
                    .isEqualTo("pms_admin");
        }

        String viewOwner = jdbcTemplate.queryForObject(
                "SELECT viewowner FROM pg_views WHERE schemaname = 'public' AND viewname = 'vw_staff_effective_permissions'",
                String.class
        );
        assertThat(viewOwner).isEqualTo("pms_admin");
    }

    // =========================================================================
    // 2. Database Integrity, Account Absence & Role Dictionary Tests
    // =========================================================================

    @Test
    @DisplayName("Migration must NOT automatically create any staff accounts or assigned global roles")
    void shouldVerifyNoAutomaticStaffAccountsCreated() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        Integer userCount = jdbcTemplate.queryForObject("SELECT count(*) FROM staff_users", Integer.class);
        Integer propRoleCount = jdbcTemplate.queryForObject("SELECT count(*) FROM staff_property_roles", Integer.class);
        Integer globalRoleCount = jdbcTemplate.queryForObject("SELECT count(*) FROM staff_global_roles", Integer.class);
        Integer tokenCount = adminJdbc.queryForObject("SELECT count(*) FROM staff_refresh_tokens", Integer.class);

        assertThat(userCount).isZero();
        assertThat(propRoleCount).isZero();
        assertThat(globalRoleCount).isZero();
        assertThat(tokenCount).isZero();
    }

    @Test
    @DisplayName("Role dictionary contains exactly the 5 authorized roles and rejects invalid additions")
    void shouldVerifyRoleDictionaryIntegrity() {
        List<String> roles = jdbcTemplate.queryForList(
                "SELECT role_code FROM staff_roles ORDER BY role_code",
                String.class
        );

        assertThat(roles).containsExactly(
                "ACCOUNTANT",
                "PROPERTY_ADMIN",
                "RECEPTIONIST",
                "SUPER_ADMIN",
                "VIEWER"
        );

        // 1. Runtime role pms_app is denied INSERT on staff_roles
        assertThatThrownBy(() -> jdbcTemplate.execute(
                "INSERT INTO staff_roles (role_code, name, is_property_scopable) VALUES ('HACKER', 'Hacker Role', true)"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_roles");

        // 2. Table-level CHECK constraint rejects invalid role codes even under admin
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        assertThatThrownBy(() -> adminJdbc.execute(
                "INSERT INTO staff_roles (role_code, name, is_property_scopable) VALUES ('HACKER', 'Hacker Role', true)"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("chk_staff_roles_valid_code");
    }

    // =========================================================================
    // 3. Staff Users Table Constraints & Password Hash Integrity Validation
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Staff user insertion succeeds with valid standard BCrypt hash (60 chars) and normalized email")
    void shouldInsertValidStaffUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO staff_users (id, email, password_hash, first_name, last_name, phone) " +
                "VALUES (?, 'admin@homestay.com', '$2a$12$e80yq7KjVpEw3W6xK9qXxeQjJcM1q4RkQ9aVbY2vK8n1o2p3q4r5s', 'Admin', 'User', '+919876543210')",
                userId
        );

        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT email, is_active, is_locked, failed_login_attempts FROM staff_users WHERE id = ?",
                userId
        );
        assertThat(user.get("email")).isEqualTo("admin@homestay.com");
        assertThat(user.get("is_active")).isEqualTo(true);
        assertThat(user.get("is_locked")).isEqualTo(false);
        assertThat(user.get("failed_login_attempts")).isEqualTo(0);
    }

    @Test
    @Transactional
    @DisplayName("Staff user insertion fails when password hash is raw/plaintext or does not match hash format")
    void shouldRejectRawPlaintextOrInvalidPasswordHash() {
        // Raw plaintext password rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO staff_users (email, password_hash, first_name) " +
                "VALUES ('rawpass@homestay.com', 'raw_unhashed_secret_password', 'Admin')"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("chk_staff_users_password_hash");

        // Truncated hash rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO staff_users (email, password_hash, first_name) " +
                "VALUES ('truncated@homestay.com', '$2a$12$short', 'Admin')"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("chk_staff_users_password_hash");
    }

    @Test
    @Transactional
    @DisplayName("Staff user insertion fails when email is not lowercase or trimmed")
    void shouldRejectUnnormalizedEmail() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO staff_users (email, password_hash, first_name) " +
                "VALUES ('ADMIN@HOMESTAY.COM', '$2a$12$e80yq7KjVpEw3W6xK9qXxeQjJcM1q4RkQ9aVbY2vK8n1o2p3q4r5s', 'Admin')"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("chk_staff_users_email_format");
    }

    @Test
    @Transactional
    @DisplayName("Duplicate email registration is strictly rejected by unique constraint")
    void shouldRejectDuplicateEmail() {
        jdbcTemplate.update(
                "INSERT INTO staff_users (email, password_hash, first_name) " +
                "VALUES ('duplicate@homestay.com', '$2a$12$e80yq7KjVpEw3W6xK9qXxeQjJcM1q4RkQ9aVbY2vK8n1o2p3q4r5s', 'First')"
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO staff_users (email, password_hash, first_name) " +
                "VALUES ('duplicate@homestay.com', '$2a$12$e80yq7KjVpEw3W6xK9qXxeQjJcM1q4RkQ9aVbY2vK8n1o2p3q4r5s', 'Second')"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("uq_staff_users_email");
    }

    // =========================================================================
    // 4. Privileged Global Role Protection & Audited Mutation Paths
    // =========================================================================

    private UUID createTestUser(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO staff_users (id, email, password_hash, first_name) " +
                "VALUES (?, ?, '$2a$12$e80yq7KjVpEw3W6xK9qXxeQjJcM1q4RkQ9aVbY2vK8n1o2p3q4r5s', 'Test')",
                id, email
        );
        return id;
    }

    private UUID getExistingPropertyId() {
        return jdbcTemplate.queryForObject("SELECT id FROM properties LIMIT 1", UUID.class);
    }

    private void callVoidFunction(String sql, Object... args) {
        jdbcTemplate.query(sql, rs -> null, args);
    }

    private void callAdminVoidFunction(JdbcTemplate adminJdbc, String sql, Object... args) {
        adminJdbc.query(sql, rs -> null, args);
    }

    private UUID createAdminTestUser(JdbcTemplate adminJdbc, String email) {
        UUID id = UUID.randomUUID();
        adminJdbc.update(
                "INSERT INTO staff_users (id, email, password_hash, first_name) " +
                "VALUES (?, ?, '$2a$12$e80yq7KjVpEw3W6xK9qXxeQjJcM1q4RkQ9aVbY2vK8n1o2p3q4r5s', 'AdminTest')",
                id, email
        );
        return id;
    }

    private void cleanUpAdminUser(JdbcTemplate adminJdbc, UUID userId) {
        if (userId == null) return;
        adminJdbc.execute(
                "ALTER TABLE staff_security_audit_events DISABLE TRIGGER trg_staff_security_audit_events_immutable; " +
                "DELETE FROM staff_global_roles WHERE staff_user_id = '" + userId + "'; " +
                "DELETE FROM staff_property_roles WHERE staff_user_id = '" + userId + "'; " +
                "DELETE FROM staff_refresh_tokens WHERE staff_user_id = '" + userId + "'; " +
                "DELETE FROM staff_security_audit_events WHERE staff_user_id = '" + userId + "'; " +
                "DELETE FROM staff_users WHERE id = '" + userId + "'; " +
                "ALTER TABLE staff_security_audit_events ENABLE TRIGGER trg_staff_security_audit_events_immutable;"
        );
    }

    // =========================================================================
    // 4. Privileged Global Role Protection & Strict Privilege Segregation
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Negative Test: Runtime role pms_app is strictly DENIED direct writes to staff_global_roles")
    void shouldDenyDirectWriteToStaffGlobalRoles() {
        UUID userId = createTestUser("escalate_direct@homestay.com");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO staff_global_roles (staff_user_id, role_code) VALUES (?, 'SUPER_ADMIN')",
                userId
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_global_roles");
    }

    @Test
    @Transactional
    @DisplayName("Privilege Escalation Protection: pms_app is strictly DENIED execution of fn_assign_staff_global_role")
    void shouldDenyAssignGlobalRoleToPmsApp() {
        UUID userId = createTestUser("escalate_assign@homestay.com");

        assertThatThrownBy(() -> callVoidFunction(
                "SELECT fn_assign_staff_global_role(?, 'SUPER_ADMIN', 'actor')",
                userId
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_assign_staff_global_role");
    }

    @Test
    @Transactional
    @DisplayName("Privilege Escalation Protection: pms_app is strictly DENIED execution of fn_revoke_staff_global_role")
    void shouldDenyRevokeGlobalRoleToPmsApp() {
        UUID userId = createTestUser("escalate_revoke@homestay.com");

        assertThatThrownBy(() -> callVoidFunction(
                "SELECT fn_revoke_staff_global_role(?, 'SUPER_ADMIN', 'actor')",
                userId
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_revoke_staff_global_role");
    }

    @Test
    @Transactional
    @DisplayName("Forged Actor Identity: pms_app cannot escalate even when supplying a legitimate superadmin ID as actor")
    void shouldRejectForgedActorIdentityUnderPmsApp() {
        UUID userId = createTestUser("spoofed_actor@homestay.com");

        // Attempting to pass a forged actor ID like 'super_admin_master' or a real UUID
        assertThatThrownBy(() -> callVoidFunction(
                "SELECT fn_assign_staff_global_role(?, 'SUPER_ADMIN', 'super_admin_master@homestay.com')",
                userId
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_assign_staff_global_role");
    }

    @Test
    @DisplayName("Audited Path: Assigning SUPER_ADMIN succeeds via pms_admin credentials and captures session_user in audit log")
    void shouldAssignGlobalRoleViaAdminCredentialsAndCaptureSessionUser() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            userId = createAdminTestUser(adminJdbc, "audited_admin_" + UUID.randomUUID() + "@homestay.com");

            callAdminVoidFunction(
                    adminJdbc,
                    "SELECT fn_assign_staff_global_role(?, 'SUPER_ADMIN', 'master_admin_actor')",
                    userId
            );

            String role = adminJdbc.queryForObject(
                    "SELECT role_code FROM staff_global_roles WHERE staff_user_id = ?",
                    String.class,
                    userId
            );
            assertThat(role).isEqualTo("SUPER_ADMIN");

            // Verify audit event automatically logged with session_user = 'pms_admin'
            Map<String, Object> audit = adminJdbc.queryForMap(
                    "SELECT actor_id, details::text as details_txt FROM staff_security_audit_events WHERE staff_user_id = ? AND event_type = 'GLOBAL_ROLE_ASSIGNED'",
                    userId
            );
            assertThat(audit.get("actor_id")).isEqualTo("master_admin_actor");
            assertThat((String) audit.get("details_txt")).contains("\"session_user\": \"pms_admin\"");
            assertThat((String) audit.get("details_txt")).contains("\"claimed_actor\": \"master_admin_actor\"");
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    @Test
    @DisplayName("Negative Test: fn_assign_staff_global_role rejects assignment to deactivated user")
    void shouldRejectGlobalRoleForDeactivatedUser() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            userId = createAdminTestUser(adminJdbc, "deactivated_admin_" + UUID.randomUUID() + "@homestay.com");
            adminJdbc.update("UPDATE staff_users SET is_active = FALSE WHERE id = ?", userId);

            final UUID finalUserId = userId;
            assertThatThrownBy(() -> callAdminVoidFunction(
                    adminJdbc,
                    "SELECT fn_assign_staff_global_role(?, 'SUPER_ADMIN', 'actor')",
                    finalUserId
            ))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("Cannot grant global role to deactivated staff user");
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    @Test
    @DisplayName("Lockout Protection: fn_revoke_staff_global_role rejects revoking the last active SUPER_ADMIN under admin")
    void shouldPreventRevokingLastActiveSuperAdmin() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID admin1 = null;
        UUID admin2 = null;
        try {
            admin1 = createAdminTestUser(adminJdbc, "first_admin_" + UUID.randomUUID() + "@homestay.com");
            callAdminVoidFunction(adminJdbc, "SELECT fn_assign_staff_global_role(?, 'SUPER_ADMIN', 'bootstrap')", admin1);

            admin2 = createAdminTestUser(adminJdbc, "second_admin_" + UUID.randomUUID() + "@homestay.com");
            callAdminVoidFunction(adminJdbc, "SELECT fn_assign_staff_global_role(?, 'SUPER_ADMIN', 'bootstrap')", admin2);

            // Revoking first admin succeeds because second admin is still active
            callAdminVoidFunction(adminJdbc, "SELECT fn_revoke_staff_global_role(?, 'SUPER_ADMIN', 'actor')", admin1);
            Integer admin1Count = adminJdbc.queryForObject(
                    "SELECT count(*) FROM staff_global_roles WHERE staff_user_id = ?",
                    Integer.class,
                    admin1
            );
            assertThat(admin1Count).isZero();

            // Attempting to revoke the sole remaining superadmin must fail
            final UUID finalAdmin2 = admin2;
            assertThatThrownBy(() -> callAdminVoidFunction(
                    adminJdbc,
                    "SELECT fn_revoke_staff_global_role(?, 'SUPER_ADMIN', 'actor')",
                    finalAdmin2
            ))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("Cannot revoke the last active SUPER_ADMIN");
        } finally {
            cleanUpAdminUser(adminJdbc, admin1);
            cleanUpAdminUser(adminJdbc, admin2);
        }
    }

    @Test
    @Transactional
    @DisplayName("Negative Test: Runtime role pms_app is strictly DENIED direct INSERT on staff_property_roles")
    void shouldDenyDirectInsertOnStaffPropertyRolesByPmsApp() {
        UUID userId = createTestUser("deny_insert_prop@homestay.com");
        UUID propertyId = getExistingPropertyId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO staff_property_roles (staff_user_id, property_id, role_code, assigned_by) " +
                "VALUES (?, ?, 'RECEPTIONIST', 'rogue_app')",
                userId, propertyId
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_property_roles");
    }

    @Test
    @Transactional
    @DisplayName("Negative Test: Runtime role pms_app is strictly DENIED direct UPDATE on staff_property_roles")
    void shouldDenyDirectUpdateOnStaffPropertyRolesByPmsApp() {
        UUID propertyId = getExistingPropertyId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE staff_property_roles SET role_code = 'PROPERTY_ADMIN' WHERE property_id = ?",
                propertyId
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_property_roles");
    }

    @Test
    @Transactional
    @DisplayName("Negative Test: Runtime role pms_app is strictly DENIED direct DELETE on staff_property_roles")
    void shouldDenyDirectDeleteOnStaffPropertyRolesByPmsApp() {
        UUID propertyId = getExistingPropertyId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM staff_property_roles WHERE property_id = ?",
                propertyId
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_property_roles");
    }

    @Test
    @Transactional
    @DisplayName("Negative Test: Runtime role pms_app is strictly DENIED execution of fn_assign_staff_property_role")
    void shouldDenyPmsAppFromCallingFnAssignStaffPropertyRole() {
        UUID userId = createTestUser("deny_prop_assign@homestay.com");
        UUID propertyId = getExistingPropertyId();

        final String assignSql = String.format("SELECT fn_assign_staff_property_role('%s', '%s', 'RECEPTIONIST', 'rogue_app')", userId, propertyId);
        assertThatThrownBy(() -> jdbcTemplate.execute(assignSql))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_assign_staff_property_role");
    }

    @Test
    @Transactional
    @DisplayName("Negative Test: Runtime role pms_app is strictly DENIED execution of fn_revoke_staff_property_role")
    void shouldDenyPmsAppFromCallingFnRevokeStaffPropertyRole() {
        UUID userId = createTestUser("deny_prop_revoke@homestay.com");
        UUID propertyId = getExistingPropertyId();

        final String revokeSql = String.format("SELECT fn_revoke_staff_property_role('%s', '%s', 'RECEPTIONIST', 'rogue_app')", userId, propertyId);
        assertThatThrownBy(() -> jdbcTemplate.execute(revokeSql))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_revoke_staff_property_role");
    }

    @Test
    @DisplayName("Controlled Property Roles: pms_admin assigns and revokes roles with audit logging and multi-property support")
    void shouldAllowAdminToAssignAndRevokePropertyRoles() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            userId = createAdminTestUser(adminJdbc, "admin_prop_test_" + UUID.randomUUID() + "@homestay.com");
            UUID propertyId = getExistingPropertyId();

            // Assign RECEPTIONIST via procedure
            callAdminVoidFunction(adminJdbc, "SELECT fn_assign_staff_property_role(?, ?, 'RECEPTIONIST', 'admin_actor')", userId, propertyId);

            String role = adminJdbc.queryForObject(
                    "SELECT role_code FROM staff_property_roles WHERE staff_user_id = ? AND property_id = ?",
                    String.class,
                    userId, propertyId
            );
            assertThat(role).isEqualTo("RECEPTIONIST");

            // Verify audit event
            Integer auditCount = adminJdbc.queryForObject(
                    "SELECT count(*) FROM staff_security_audit_events WHERE staff_user_id = ? AND event_type = 'PROPERTY_ROLE_ASSIGNED'",
                    Integer.class,
                    userId
            );
            assertThat(auditCount).isEqualTo(1);

            // Revoke role via procedure
            callAdminVoidFunction(adminJdbc, "SELECT fn_revoke_staff_property_role(?, ?, 'RECEPTIONIST', 'admin_actor')", userId, propertyId);

            Integer countAfterRevoke = adminJdbc.queryForObject(
                    "SELECT count(*) FROM staff_property_roles WHERE staff_user_id = ? AND property_id = ?",
                    Integer.class,
                    userId, propertyId
            );
            assertThat(countAfterRevoke).isEqualTo(0);

            Integer revokeAuditCount = adminJdbc.queryForObject(
                    "SELECT count(*) FROM staff_security_audit_events WHERE staff_user_id = ? AND event_type = 'PROPERTY_ROLE_REVOKED'",
                    Integer.class,
                    userId
            );
            assertThat(revokeAuditCount).isEqualTo(1);
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    @Test
    @DisplayName("Crucial Safety Rule: Strictly rejects assigning SUPER_ADMIN as a property-scoped role")
    void shouldRejectSuperAdminInPropertyRoles() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            userId = createAdminTestUser(adminJdbc, "escalate_prop_" + UUID.randomUUID() + "@homestay.com");
            UUID propertyId = getExistingPropertyId();

            final UUID targetUser = userId;
            assertThatThrownBy(() -> callAdminVoidFunction(
                    adminJdbc,
                    "SELECT fn_assign_staff_property_role(?, ?, 'SUPER_ADMIN', 'admin_actor')",
                    targetUser, propertyId
            ))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("SUPER_ADMIN is a global role and cannot be assigned to a property");
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    // =========================================================================
    // 5. Credential & Status Field Protection Tests
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Negative Test: Runtime role pms_app is strictly DENIED direct updates to password_hash")
    void shouldDenyDirectUpdateOnPasswordHash() {
        UUID userId = createTestUser("field_protect_hash@homestay.com");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE staff_users SET password_hash = '$2a$12$e80yq7KjVpEw3W6xK9qXxeQjJcM1q4RkQ9aVbY2vK8n1o2p3q4r5t' WHERE id = ?",
                userId
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_users");
    }

    @Test
    @Transactional
    @DisplayName("Negative Test: Runtime role pms_app is strictly DENIED direct updates to password_changed_at")
    void shouldDenyDirectUpdateOnPasswordChangedAt() {
        UUID userId = createTestUser("field_protect_changed@homestay.com");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE staff_users SET password_changed_at = CURRENT_TIMESTAMP WHERE id = ?",
                userId
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_users");
    }

    @Test
    @Transactional
    @DisplayName("Negative Test: Runtime role pms_app is strictly DENIED direct updates to is_active")
    void shouldDenyDirectUpdateOnIsActive() {
        UUID userId = createTestUser("field_protect_active@homestay.com");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE staff_users SET is_active = FALSE WHERE id = ?",
                userId
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_users");
    }

    @Test
    @Transactional
    @DisplayName("Negative Test: Runtime role pms_app is strictly DENIED direct updates to is_locked")
    void shouldDenyDirectUpdateOnIsLocked() {
        UUID userId = createTestUser("field_protect_locked@homestay.com");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE staff_users SET is_locked = TRUE WHERE id = ?",
                userId
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_users");
    }

    @Test
    @Transactional
    @DisplayName("Negative Test: Runtime role pms_app is strictly DENIED direct updates to failed_login_attempts")
    void shouldDenyDirectUpdateOnFailedLoginAttempts() {
        UUID userId = createTestUser("field_protect_attempts@homestay.com");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE staff_users SET failed_login_attempts = 0 WHERE id = ?",
                userId
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_users");
    }

    @Test
    @Transactional
    @DisplayName("Negative Test: Runtime role pms_app is strictly DENIED direct updates to lockout_until")
    void shouldDenyDirectUpdateOnLockoutUntil() {
        UUID userId = createTestUser("field_protect_until@homestay.com");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE staff_users SET lockout_until = CURRENT_TIMESTAMP WHERE id = ?",
                userId
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_users");
    }

    @Test
    @Transactional
    @DisplayName("Operational Update: Runtime role pms_app can update profile fields (first_name, last_name, phone, updated_at)")
    void shouldAllowDirectUpdateOnOperationalStaffUserFields() {
        UUID userId = createTestUser("profile_update@homestay.com");

        int rows = jdbcTemplate.update(
                "UPDATE staff_users SET first_name = 'UpdatedFirst', last_name = 'UpdatedLast', phone = '+1-555-0199', updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                userId
        );
        assertThat(rows).isEqualTo(1);

        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT first_name, last_name, phone FROM staff_users WHERE id = ?",
                userId
        );
        assertThat(user.get("first_name")).isEqualTo("UpdatedFirst");
        assertThat(user.get("last_name")).isEqualTo("UpdatedLast");
        assertThat(user.get("phone")).isEqualTo("+1-555-0199");
    }

    @Test
    @DisplayName("Controlled Active Status: Deactivating staff user via fn_set_staff_active_status revokes refresh tokens and audits event")
    void shouldDeactivateStaffUserAndRevokeTokens() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            userId = createAdminTestUser(adminJdbc, "deact_token_" + UUID.randomUUID() + "@homestay.com");
            UUID familyId = UUID.randomUUID();
            String tokenHash = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

            adminJdbc.update(
                    "INSERT INTO staff_refresh_tokens (staff_user_id, token_hash, token_family, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                    userId, tokenHash, familyId
            );

            // Deactivate via procedure under adminJdbc
            callAdminVoidFunction(adminJdbc, "SELECT fn_set_staff_active_status(?, FALSE, 'supervisor_actor')", userId);

            Boolean isActive = adminJdbc.queryForObject(
                    "SELECT is_active FROM staff_users WHERE id = ?",
                    Boolean.class,
                    userId
            );
            assertThat(isActive).isFalse();

            // Verify active token was revoked
            Boolean isRevoked = adminJdbc.queryForObject(
                    "SELECT is_revoked FROM staff_refresh_tokens WHERE token_hash = ?",
                    Boolean.class,
                    tokenHash
            );
            String revokedReason = adminJdbc.queryForObject(
                    "SELECT revoked_reason FROM staff_refresh_tokens WHERE token_hash = ?",
                    String.class,
                    tokenHash
            );
            assertThat(isRevoked).isTrue();
            assertThat(revokedReason).isEqualTo("USER_DEACTIVATED");

            // Verify audit log
            Integer auditCount = adminJdbc.queryForObject(
                    "SELECT count(*) FROM staff_security_audit_events WHERE staff_user_id = ? AND event_type = 'STAFF_DEACTIVATED'",
                    Integer.class,
                    userId
            );
            assertThat(auditCount).isEqualTo(1);
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    @Test
    @Transactional
    @DisplayName("Negative Test: Runtime role pms_app is strictly DENIED execution of fn_set_staff_active_status")
    void shouldDenyPmsAppFromCallingFnSetStaffActiveStatus() {
        UUID userId = createTestUser("deny_status_proc@homestay.com");

        final String sql = String.format("SELECT fn_set_staff_active_status('%s', FALSE, 'rogue_app')", userId);
        assertThatThrownBy(() -> jdbcTemplate.execute(sql))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_set_staff_active_status");
    }

    @Test
    @DisplayName("Unauthorized Administrator Status Mutation: Modifying SUPER_ADMIN active status requires pms_admin, and last active SUPER_ADMIN is protected")
    void shouldProtectSuperAdminAgainstUnauthorizedStatusModifications() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID adminId = null;
        try {
            adminId = createAdminTestUser(adminJdbc, "super_status_" + UUID.randomUUID() + "@homestay.com");
            callAdminVoidFunction(adminJdbc, "SELECT fn_assign_staff_global_role(?, 'SUPER_ADMIN', 'bootstrap')", adminId);

            final UUID targetId = adminId;
            // 1. pms_app calling fn_set_staff_active_status is denied at grant level
            assertThatThrownBy(() -> jdbcTemplate.execute(
                    String.format("SELECT fn_set_staff_active_status('%s', FALSE, 'rogue_app')", targetId)
            ))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("permission denied for function fn_set_staff_active_status");

            // 2. adminJdbc attempting to deactivate the sole active SUPER_ADMIN must be rejected
            assertThatThrownBy(() -> callAdminVoidFunction(
                    adminJdbc,
                    "SELECT fn_set_staff_active_status(?, FALSE, 'admin_actor')",
                    targetId
            ))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("Cannot deactivate the last active SUPER_ADMIN in the system");
        } finally {
            cleanUpAdminUser(adminJdbc, adminId);
        }
    }

    // =========================================================================
    // 5. Controlled Password Updates & Token Invalidation Tests
    // =========================================================================

    @Test
    @DisplayName("Controlled Admin Reset: Administrative password reset updates hash, invalidates active refresh tokens, and audits event")
    void shouldAllowAdminResetStaffPasswordAndRevokeTokens() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            userId = createAdminTestUser(adminJdbc, "admin_reset_" + UUID.randomUUID() + "@homestay.com");
            UUID familyId = UUID.randomUUID();
            String tokenHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

            // Insert active refresh token
            adminJdbc.update(
                    "INSERT INTO staff_refresh_tokens (staff_user_id, token_hash, token_family, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                    userId, tokenHash, familyId
            );

            // Update password via admin reset procedure
            String newHash = "$2a$12$f91zq8LkQqFx4X7yL0rYyeRkKdN2r5SlR0bWcZ3wL9o2p3q4r5s6t";
            callAdminVoidFunction(adminJdbc, "SELECT fn_admin_reset_staff_password(?, ?, 'admin_actor')", userId, newHash);

            // Verify password updated
            String updatedHash = adminJdbc.queryForObject(
                    "SELECT password_hash FROM staff_users WHERE id = ?",
                    String.class,
                    userId
            );
            assertThat(updatedHash).isEqualTo(newHash);

            // Verify active token was revoked with reason ADMIN_PASSWORD_RESET
            Boolean isRevoked = adminJdbc.queryForObject(
                    "SELECT is_revoked FROM staff_refresh_tokens WHERE token_hash = ?",
                    Boolean.class,
                    tokenHash
            );
            String revokedReason = adminJdbc.queryForObject(
                    "SELECT revoked_reason FROM staff_refresh_tokens WHERE token_hash = ?",
                    String.class,
                    tokenHash
            );
            assertThat(isRevoked).isTrue();
            assertThat(revokedReason).isEqualTo("ADMIN_PASSWORD_RESET");

            // Verify audit event
            Integer auditCount = adminJdbc.queryForObject(
                    "SELECT count(*) FROM staff_security_audit_events WHERE staff_user_id = ? AND event_type = 'ADMIN_PASSWORD_RESET'",
                    Integer.class,
                    userId
            );
            assertThat(auditCount).isEqualTo(1);
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    @Test
    @DisplayName("Controlled Self-Service: Self-service password update updates hash and invalidates tokens with PASSWORD_CHANGED")
    void shouldAllowStaffSelfUpdatePasswordUnderAdmin() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            userId = createAdminTestUser(adminJdbc, "self_update_" + UUID.randomUUID() + "@homestay.com");
            UUID familyId = UUID.randomUUID();
            String tokenHash = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";

            adminJdbc.update(
                    "INSERT INTO staff_refresh_tokens (staff_user_id, token_hash, token_family, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                    userId, tokenHash, familyId
            );

            String newHash = "$2a$12$f91zq8LkQqFx4X7yL0rYyeRkKdN2r5SlR0bWcZ3wL9o2p3q4r5s6t";
            callAdminVoidFunction(adminJdbc, "SELECT fn_staff_self_update_password(?, ?, 'user_self')", userId, newHash);

            String updatedHash = adminJdbc.queryForObject(
                    "SELECT password_hash FROM staff_users WHERE id = ?",
                    String.class,
                    userId
            );
            assertThat(updatedHash).isEqualTo(newHash);

            String revokedReason = adminJdbc.queryForObject(
                    "SELECT revoked_reason FROM staff_refresh_tokens WHERE token_hash = ?",
                    String.class,
                    tokenHash
            );
            assertThat(revokedReason).isEqualTo("PASSWORD_CHANGED");
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    @Test
    @Transactional
    @DisplayName("Negative Test: Runtime role pms_app is strictly DENIED execution of fn_admin_reset_staff_password")
    void shouldDenyPmsAppFromCallingFnAdminResetStaffPassword() {
        UUID userId = createTestUser("deny_pass_reset@homestay.com");
        String dummyHash = "$2a$12$f91zq8LkQqFx4X7yL0rYyeRkKdN2r5SlR0bWcZ3wL9o2p3q4r5s6t";

        final String adminResetSql = String.format("SELECT fn_admin_reset_staff_password('%s', '%s', 'rogue_app')", userId, dummyHash);
        assertThatThrownBy(() -> jdbcTemplate.execute(adminResetSql))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_admin_reset_staff_password");
    }

    @Test
    @Transactional
    @DisplayName("Negative Test: Runtime role pms_app is strictly DENIED execution of fn_staff_self_update_password")
    void shouldDenyPmsAppFromCallingFnStaffSelfUpdatePassword() {
        UUID userId = createTestUser("deny_pass_self@homestay.com");
        String dummyHash = "$2a$12$f91zq8LkQqFx4X7yL0rYyeRkKdN2r5SlR0bWcZ3wL9o2p3q4r5s6t";

        final String selfUpdateSql = String.format("SELECT fn_staff_self_update_password('%s', '%s', 'rogue_app')", userId, dummyHash);
        assertThatThrownBy(() -> jdbcTemplate.execute(selfUpdateSql))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_staff_self_update_password");
    }

    @Test
    @Transactional
    @DisplayName("Negative Test: Runtime role pms_app is strictly DENIED execution of fn_update_staff_password")
    void shouldDenyPmsAppFromCallingFnUpdateStaffPassword() {
        UUID userId = createTestUser("deny_pass_legacy@homestay.com");
        String dummyHash = "$2a$12$f91zq8LkQqFx4X7yL0rYyeRkKdN2r5SlR0bWcZ3wL9o2p3q4r5s6t";

        final String legacySql = String.format("SELECT fn_update_staff_password('%s', '%s', 'rogue_app')", userId, dummyHash);
        assertThatThrownBy(() -> jdbcTemplate.execute(legacySql))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_update_staff_password");
    }

    // =========================================================================
    // 6. Controlled Login Attempt & Account Lockout Flow
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Negative Test: Untrusted caller (pms_app) is strictly DENIED directly asserting login success (p_success = TRUE)")
    void shouldDenyPmsAppFromDirectlyAssertingLoginSuccess() {
        String email = "deny_success_assert@homestay.com";
        createTestUser(email);

        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                "SELECT fn_record_staff_login_attempt(?, TRUE, '127.0.0.1', 'JUnit')",
                Boolean.class,
                email
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("Direct assertion of login success requires pms_admin database credentials");
    }

    @Test
    @DisplayName("Login attempt procedure tracks failed attempts under pms_app, and allows pms_admin to record success and trigger lockout")
    void shouldHandleLoginAttemptsAndLockout() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            String email = "lockout_flow_" + UUID.randomUUID() + "@homestay.com";
            userId = createAdminTestUser(adminJdbc, email);

            // 1. 3 failed attempts via runtime role pms_app
            for (int i = 1; i <= 3; i++) {
                Boolean res = jdbcTemplate.queryForObject(
                        "SELECT fn_record_staff_login_attempt(?, FALSE, '127.0.0.1', 'JUnit')",
                        Boolean.class,
                        email
                );
                assertThat(res).isFalse();
            }

            Map<String, Object> state = adminJdbc.queryForMap(
                    "SELECT failed_login_attempts, is_locked FROM staff_users WHERE id = ?",
                    userId
            );
            assertThat(state.get("failed_login_attempts")).isEqualTo(3);
            assertThat(state.get("is_locked")).isEqualTo(false);

            // 2. Successful login via authorized adminJdbc resets counter
            Boolean successRes = adminJdbc.queryForObject(
                    "SELECT fn_record_staff_login_attempt(?, TRUE, '127.0.0.1', 'JUnit')",
                    Boolean.class,
                    email
            );
            assertThat(successRes).isTrue();

            state = adminJdbc.queryForMap(
                    "SELECT failed_login_attempts, is_locked, lockout_until FROM staff_users WHERE id = ?",
                    userId
            );
            assertThat(state.get("failed_login_attempts")).isEqualTo(0);
            assertThat(state.get("is_locked")).isEqualTo(false);
            assertThat(state.get("lockout_until")).isNull();

            // 3. 5 consecutive failed attempts via pms_app trigger lockout
            for (int i = 1; i <= 5; i++) {
                jdbcTemplate.queryForObject(
                        "SELECT fn_record_staff_login_attempt(?, FALSE, '127.0.0.1', 'JUnit')",
                        Boolean.class,
                        email
                );
            }
            state = adminJdbc.queryForMap(
                    "SELECT failed_login_attempts, is_locked, lockout_until IS NOT NULL AS has_lockout FROM staff_users WHERE id = ?",
                    userId
            );
            assertThat(state.get("failed_login_attempts")).isEqualTo(5);
            assertThat(state.get("is_locked")).isEqualTo(true);
            assertThat(state.get("has_lockout")).isEqualTo(true);
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    @Test
    @DisplayName("Invalid Login-Success: Deactivated staff account cannot gain access or clear lockouts even via admin success call")
    void shouldRejectLoginSuccessForDeactivatedUser() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            String email = "deactivated_login_" + UUID.randomUUID() + "@homestay.com";
            userId = createAdminTestUser(adminJdbc, email);
            callAdminVoidFunction(adminJdbc, "SELECT fn_set_staff_active_status(?, FALSE, 'admin_actor')", userId);

            Boolean res = adminJdbc.queryForObject(
                    "SELECT fn_record_staff_login_attempt(?, TRUE, '127.0.0.1', 'JUnit')",
                    Boolean.class,
                    email
            );
            assertThat(res).isFalse();

            // Verify user remains deactivated
            Boolean isActive = adminJdbc.queryForObject(
                    "SELECT is_active FROM staff_users WHERE id = ?",
                    Boolean.class,
                    userId
            );
            assertThat(isActive).isFalse();

            // Verify security audit logged failure
            Integer auditCount = adminJdbc.queryForObject(
                    "SELECT count(*) FROM staff_security_audit_events WHERE staff_user_id = ? AND event_type = 'LOGIN_FAILED_DEACTIVATED_USER'",
                    Integer.class,
                    userId
            );
            assertThat(auditCount).isEqualTo(1);
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    @Test
    @DisplayName("Invalid Login-Success: Locked account cannot gain access through a success call while lockout is active")
    void shouldRejectLoginSuccessForLockedUser() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            String email = "locked_login_" + UUID.randomUUID() + "@homestay.com";
            userId = createAdminTestUser(adminJdbc, email);

            // Trigger 5 failed attempts to lock account
            for (int i = 1; i <= 5; i++) {
                jdbcTemplate.queryForObject(
                        "SELECT fn_record_staff_login_attempt(?, FALSE, '127.0.0.1', 'JUnit')",
                        Boolean.class,
                        email
                );
            }

            // A success call while lockout is active MUST be rejected even by admin
            Boolean res = adminJdbc.queryForObject(
                    "SELECT fn_record_staff_login_attempt(?, TRUE, '127.0.0.1', 'JUnit')",
                    Boolean.class,
                    email
            );
            assertThat(res).isFalse();

            // Account must remain locked
            Boolean isLocked = adminJdbc.queryForObject(
                    "SELECT is_locked FROM staff_users WHERE id = ?",
                    Boolean.class,
                    userId
            );
            assertThat(isLocked).isTrue();

            Integer auditCount = adminJdbc.queryForObject(
                    "SELECT count(*) FROM staff_security_audit_events WHERE staff_user_id = ? AND event_type = 'LOGIN_REJECTED_LOCKED_ACCOUNT'",
                    Integer.class,
                    userId
            );
            assertThat(auditCount).isEqualTo(1);
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    @Test
    @DisplayName("Concurrency: Concurrent failed login attempts on the same staff account are serialized safely with row locks (FOR UPDATE)")
    void shouldHandleConcurrentFailedLoginAttemptsSafely() throws InterruptedException {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            String email = "concurrent_login_" + UUID.randomUUID() + "@homestay.com";
            userId = createAdminTestUser(adminJdbc, email);

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        Boolean res = jdbcTemplate.queryForObject(
                                "SELECT fn_record_staff_login_attempt(?, FALSE, '10.0.0.1', 'ConcurrentTester')",
                                Boolean.class,
                                email
                        );
                        if (res != null && !res) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            assertThat(successCount.get()).isEqualTo(threadCount);

            Map<String, Object> state = adminJdbc.queryForMap(
                    "SELECT failed_login_attempts, is_locked FROM staff_users WHERE id = ?",
                    userId
            );
            // Must have counted attempts up to threshold (5) and triggered lockout
            assertThat(((Number) state.get("failed_login_attempts")).intValue()).isEqualTo(5);
            assertThat(state.get("is_locked")).isEqualTo(true);

            // Verify security audit events: 5 attempts before lockout (1-4 failed, 5th locked) + 5 attempts rejected while locked = 10 total events
            Integer totalAuditEvents = adminJdbc.queryForObject(
                    "SELECT count(*) FROM staff_security_audit_events WHERE staff_user_id = ?",
                    Integer.class,
                    userId
            );
            assertThat(totalAuditEvents).isEqualTo(threadCount);
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    // =========================================================================
    // 7. Refresh Token Management & Family Rotation Constraints
    // =========================================================================

    @Test
    @DisplayName("Insertion of valid 64-char SHA-256 hashed refresh token with rotation family succeeds")
    void shouldInsertValidRefreshToken() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            userId = createAdminTestUser(adminJdbc, "token_user_" + UUID.randomUUID() + "@homestay.com");
            UUID familyId = UUID.randomUUID();
            String tokenHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"; // 64 chars

            adminJdbc.update(
                    "INSERT INTO staff_refresh_tokens (staff_user_id, token_hash, token_family, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                    userId, tokenHash, familyId
            );

            Integer count = adminJdbc.queryForObject(
                    "SELECT count(*) FROM staff_refresh_tokens WHERE token_hash = ?",
                    Integer.class,
                    tokenHash
            );
            assertThat(count).isEqualTo(1);
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    @Test
    @DisplayName("Rejects refresh token with invalid hash length (!= 64 characters)")
    void shouldRejectInvalidTokenHashLength() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID userId = null;
        try {
            userId = createAdminTestUser(adminJdbc, "short_token_" + UUID.randomUUID() + "@homestay.com");
            UUID familyId = UUID.randomUUID();
            final UUID finalUserId = userId;

            assertThatThrownBy(() -> adminJdbc.update(
                    "INSERT INTO staff_refresh_tokens (staff_user_id, token_hash, token_family, expires_at) " +
                    "VALUES (?, 'short_hash_only_32_characters_here', ?, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                    finalUserId, familyId
            ))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("chk_staff_refresh_tokens_hash_len");
        } finally {
            cleanUpAdminUser(adminJdbc, userId);
        }
    }

    // =========================================================================
    // 8. Security Audit Log Immutability & Append-Only Enforcement
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Security audit log enforces append-only: denied at grant level for pms_app, and blocked by trigger for pms_admin")
    void shouldEnforceSecurityAuditAppendOnly() {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO staff_security_audit_events (id, event_type, email, actor_id, details) " +
                "VALUES (?, 'LOGIN_FAILED', 'attacker@example.com', 'anonymous', '{\"reason\": \"bad_credentials\"}'::jsonb)",
                eventId
        );

        // 1. Runtime role pms_app is denied UPDATE and DELETE at grant level
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE staff_security_audit_events SET email = 'tampered@example.com' WHERE id = ?",
                eventId
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_security_audit_events");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM staff_security_audit_events WHERE id = ?",
                eventId
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_security_audit_events");

        // 2. Immutability trigger enforces append-only even if executed by table owner (pms_admin)
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID adminEventId = UUID.randomUUID();
        adminJdbc.update(
                "INSERT INTO staff_security_audit_events (id, event_type, email, actor_id, details) " +
                "VALUES (?, 'LOGIN_FAILED', 'attacker2@example.com', 'anonymous', '{\"reason\": \"bad_credentials\"}'::jsonb)",
                adminEventId
        );

        assertThatThrownBy(() -> adminJdbc.update(
                "UPDATE staff_security_audit_events SET email = 'tampered@example.com' WHERE id = ?",
                adminEventId
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("Security integrity violation: Staff security audit events are append-only");

        assertThatThrownBy(() -> adminJdbc.update(
                "DELETE FROM staff_security_audit_events WHERE id = ?",
                adminEventId
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("Security integrity violation: Staff security audit events are append-only");
    }

    // =========================================================================
    // 9. Effective Permissions View Verification
    // =========================================================================

    @Test
    @DisplayName("View vw_staff_effective_permissions accurately consolidates global and property-scoped roles")
    void shouldReturnEffectivePermissions() {
        JdbcTemplate adminJdbc = createAdminJdbcTemplate();
        UUID user1 = null;
        UUID user2 = null;
        try {
            user1 = createAdminTestUser(adminJdbc, "view_user1_" + UUID.randomUUID() + "@pms.com");
            user2 = createAdminTestUser(adminJdbc, "view_user2_" + UUID.randomUUID() + "@pms.com");
            UUID propertyId = getExistingPropertyId();

            callAdminVoidFunction(adminJdbc, "SELECT fn_assign_staff_global_role(?, 'SUPER_ADMIN', 'admin')", user1);
            adminJdbc.update("INSERT INTO staff_property_roles (staff_user_id, property_id, role_code) VALUES (?, ?, 'ACCOUNTANT')", user2, propertyId);

            List<Map<String, Object>> perm1 = jdbcTemplate.queryForList(
                    "SELECT role_code, is_global, property_id FROM vw_staff_effective_permissions WHERE staff_user_id = ?",
                    user1
            );
            assertThat(perm1).hasSize(1);
            assertThat(perm1.get(0).get("role_code")).isEqualTo("SUPER_ADMIN");
            assertThat(perm1.get(0).get("is_global")).isEqualTo(true);
            assertThat(perm1.get(0).get("property_id")).isNull();

            List<Map<String, Object>> perm2 = jdbcTemplate.queryForList(
                    "SELECT role_code, is_global, property_id FROM vw_staff_effective_permissions WHERE staff_user_id = ?",
                    user2
            );
            assertThat(perm2).hasSize(1);
            assertThat(perm2.get(0).get("role_code")).isEqualTo("ACCOUNTANT");
            assertThat(perm2.get(0).get("is_global")).isEqualTo(false);
            assertThat(perm2.get(0).get("property_id")).isEqualTo(propertyId);
        } finally {
            cleanUpAdminUser(adminJdbc, user1);
            cleanUpAdminUser(adminJdbc, user2);
        }
    }

    // =========================================================================
    // 10. Database Privilege Segregation & Role Protection
    // =========================================================================

    @Test
    @DisplayName("Role pms_finance_admin is strictly denied access to staff credentials, tokens, and administrative procedures")
    void shouldDenyFinanceAdminAccessToStaffCredentialsAndRoutines() {
        Boolean finAdminCanSelectUsers = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_finance_admin', 'staff_users', 'SELECT')",
                Boolean.class
        );
        Boolean finAdminCanSelectTokens = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_finance_admin', 'staff_refresh_tokens', 'SELECT')",
                Boolean.class
        );
        Boolean finAdminCanSelectGlobalRoles = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_finance_admin', 'staff_global_roles', 'SELECT')",
                Boolean.class
        );
        Boolean finAdminCanExecuteGlobalAssign = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_finance_admin', 'fn_assign_staff_global_role(UUID, VARCHAR, VARCHAR)', 'EXECUTE')",
                Boolean.class
        );
        Boolean finAdminCanExecutePropAssign = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_finance_admin', 'fn_assign_staff_property_role(UUID, UUID, VARCHAR, VARCHAR)', 'EXECUTE')",
                Boolean.class
        );
        Boolean finAdminCanExecuteAdminReset = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_finance_admin', 'fn_admin_reset_staff_password(UUID, VARCHAR, VARCHAR)', 'EXECUTE')",
                Boolean.class
        );
        Boolean finAdminCanExecuteSetActive = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_finance_admin', 'fn_set_staff_active_status(UUID, BOOLEAN, VARCHAR)', 'EXECUTE')",
                Boolean.class
        );

        assertThat(finAdminCanSelectUsers).isFalse();
        assertThat(finAdminCanSelectTokens).isFalse();
        assertThat(finAdminCanSelectGlobalRoles).isFalse();
        assertThat(finAdminCanExecuteGlobalAssign).isFalse();
        assertThat(finAdminCanExecutePropAssign).isFalse();
        assertThat(finAdminCanExecuteAdminReset).isFalse();
        assertThat(finAdminCanExecuteSetActive).isFalse();
    }

    @Test
    @DisplayName("Role PUBLIC has zero access on staff tables and administrative routines")
    void shouldDenyPublicAccessToStaffTablesAndRoutines() {
        Boolean publicCanSelectUsers = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('public', 'staff_users', 'SELECT')",
                Boolean.class
        );
        Boolean publicCanSelectTokens = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('public', 'staff_refresh_tokens', 'SELECT')",
                Boolean.class
        );
        Boolean publicCanExecuteGlobalAssign = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('public', 'fn_assign_staff_global_role(UUID, VARCHAR, VARCHAR)', 'EXECUTE')",
                Boolean.class
        );
        Boolean publicCanExecutePropAssign = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('public', 'fn_assign_staff_property_role(UUID, UUID, VARCHAR, VARCHAR)', 'EXECUTE')",
                Boolean.class
        );
        Boolean publicCanExecuteAdminReset = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('public', 'fn_admin_reset_staff_password(UUID, VARCHAR, VARCHAR)', 'EXECUTE')",
                Boolean.class
        );
        Boolean publicCanExecuteSetActive = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('public', 'fn_set_staff_active_status(UUID, BOOLEAN, VARCHAR)', 'EXECUTE')",
                Boolean.class
        );

        assertThat(publicCanSelectUsers).isFalse();
        assertThat(publicCanSelectTokens).isFalse();
        assertThat(publicCanExecuteGlobalAssign).isFalse();
        assertThat(publicCanExecutePropAssign).isFalse();
        assertThat(publicCanExecuteAdminReset).isFalse();
        assertThat(publicCanExecuteSetActive).isFalse();
    }

    @Test
    @DisplayName("Role pms_app runtime privilege boundaries are strictly enforced across tables and routines")
    void shouldVerifyRuntimeRolePmsAppPrivilegeBoundaries() {
        Boolean canExecuteGlobalAssign = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_app', 'fn_assign_staff_global_role(UUID, VARCHAR, VARCHAR)', 'EXECUTE')",
                Boolean.class
        );
        Boolean canExecuteGlobalRevoke = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_app', 'fn_revoke_staff_global_role(UUID, VARCHAR, VARCHAR)', 'EXECUTE')",
                Boolean.class
        );
        Boolean canExecutePropAssign = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_app', 'fn_assign_staff_property_role(UUID, UUID, VARCHAR, VARCHAR)', 'EXECUTE')",
                Boolean.class
        );
        Boolean canExecutePropRevoke = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_app', 'fn_revoke_staff_property_role(UUID, UUID, VARCHAR, VARCHAR)', 'EXECUTE')",
                Boolean.class
        );
        Boolean canExecuteAdminReset = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_app', 'fn_admin_reset_staff_password(UUID, VARCHAR, VARCHAR)', 'EXECUTE')",
                Boolean.class
        );
        Boolean canExecuteSelfUpdate = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_app', 'fn_staff_self_update_password(UUID, VARCHAR, VARCHAR)', 'EXECUTE')",
                Boolean.class
        );
        Boolean canExecuteLegacyUpdate = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_app', 'fn_update_staff_password(UUID, VARCHAR, VARCHAR)', 'EXECUTE')",
                Boolean.class
        );
        Boolean canExecuteSetActive = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_app', 'fn_set_staff_active_status(UUID, BOOLEAN, VARCHAR)', 'EXECUTE')",
                Boolean.class
        );
        Boolean canInsertPropRoles = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_app', 'staff_property_roles', 'INSERT')",
                Boolean.class
        );
        Boolean canUpdatePropRoles = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_app', 'staff_property_roles', 'UPDATE')",
                Boolean.class
        );
        Boolean canDeletePropRoles = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_app', 'staff_property_roles', 'DELETE')",
                Boolean.class
        );
        Boolean canInsertGlobalRoles = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_app', 'staff_global_roles', 'INSERT')",
                Boolean.class
        );
        Boolean canSelectTokens = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_app', 'staff_refresh_tokens', 'SELECT')",
                Boolean.class
        );
        Boolean canInsertTokens = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_app', 'staff_refresh_tokens', 'INSERT')",
                Boolean.class
        );
        Boolean canUpdateTokens = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_app', 'staff_refresh_tokens', 'UPDATE')",
                Boolean.class
        );
        Boolean canDeleteTokens = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_app', 'staff_refresh_tokens', 'DELETE')",
                Boolean.class
        );
        Boolean canRecordLogin = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_app', 'fn_record_staff_login_attempt(VARCHAR, BOOLEAN, VARCHAR, VARCHAR)', 'EXECUTE')",
                Boolean.class
        );

        assertThat(canExecuteGlobalAssign).isFalse();
        assertThat(canExecuteGlobalRevoke).isFalse();
        assertThat(canExecutePropAssign).isFalse();
        assertThat(canExecutePropRevoke).isFalse();
        assertThat(canExecuteAdminReset).isFalse();
        assertThat(canExecuteSelfUpdate).isFalse();
        assertThat(canExecuteLegacyUpdate).isFalse();
        assertThat(canExecuteSetActive).isFalse();
        assertThat(canInsertPropRoles).isFalse();
        assertThat(canUpdatePropRoles).isFalse();
        assertThat(canDeletePropRoles).isFalse();
        assertThat(canInsertGlobalRoles).isFalse();
        assertThat(canSelectTokens).isFalse();
        assertThat(canInsertTokens).isFalse();
        assertThat(canUpdateTokens).isFalse();
        assertThat(canDeleteTokens).isFalse();
        assertThat(canRecordLogin).isTrue();
    }
}
