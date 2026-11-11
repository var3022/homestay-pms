-- =============================================================================
-- Phase D: Post-V8 Granular Access Grants and Least-Privilege Hardening
-- Run by: PostgreSQL Superuser or pms_admin (Schema Owner)
-- Timing: After Flyway V8 migration has completed successfully
--
-- Objectives:
-- 1. Lock down V8 staff authentication and RBAC tables from PUBLIC.
-- 2. Strictly protect staff_global_roles and staff_property_roles against direct DML writes from pms_app.
-- 3. Grant least-privilege access to runtime role pms_app on operational staff tables.
-- 4. Exclude pms_finance_admin from accessing staff credentials, tokens, and routines.
-- 5. Restrict privileged administrative routines strictly to pms_admin.
-- 6. Grant pms_admin full administrative and migration ownership.
-- =============================================================================

-- 1. Revoke Default PUBLIC Access on V8 Tables, Views, and Routines
REVOKE ALL ON
    staff_users,
    staff_roles,
    staff_property_roles,
    staff_global_roles,
    staff_refresh_tokens,
    staff_security_audit_events,
    vw_staff_effective_permissions
FROM PUBLIC;

REVOKE ALL ON FUNCTION fn_assign_staff_global_role(UUID, VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_revoke_staff_global_role(UUID, VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_assign_staff_property_role(UUID, UUID, VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_revoke_staff_property_role(UUID, UUID, VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_admin_reset_staff_password(UUID, VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_staff_self_update_password(UUID, VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_update_staff_password(UUID, VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_record_staff_login_attempt(VARCHAR, BOOLEAN, VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_set_staff_active_status(UUID, BOOLEAN, VARCHAR) FROM PUBLIC;

-- 2. Application Runtime Grants (pms_app)
-- Staff user identity: Read and create accounts; direct mutations to credentials, lockouts, and active status are REVOKED
GRANT SELECT, INSERT ON staff_users TO pms_app;
REVOKE UPDATE, DELETE ON staff_users FROM pms_app;
-- Allow operational profile updates only (name and phone; credentials, status, and lockouts are protected)
GRANT UPDATE (first_name, last_name, phone, updated_at) ON staff_users TO pms_app;

-- Property-scoped roles: Read-only for runtime role pms_app. Direct mutations REVOKED.
GRANT SELECT ON staff_property_roles TO pms_app;
REVOKE INSERT, UPDATE, DELETE ON staff_property_roles FROM pms_app;

-- Refresh tokens: Application runtime can issue, read, and revoke refresh tokens
GRANT SELECT, INSERT, UPDATE, DELETE ON staff_refresh_tokens TO pms_app;

-- Read-only role dictionary and effective permissions view
GRANT SELECT ON staff_roles TO pms_app;
GRANT SELECT ON vw_staff_effective_permissions TO pms_app;

-- CRITICAL SECURITY CONTROL: staff_global_roles is STRICTLY READ-ONLY for pms_app
-- Direct INSERT, UPDATE, DELETE are denied to prevent arbitrary SUPER_ADMIN escalation.
GRANT SELECT ON staff_global_roles TO pms_app;
REVOKE INSERT, UPDATE, DELETE ON staff_global_roles FROM pms_app;

-- Append-only access to staff security audit events
GRANT SELECT, INSERT ON staff_security_audit_events TO pms_app;
REVOKE UPDATE, DELETE ON staff_security_audit_events FROM pms_app;

-- CRITICAL PRIVILEGE HARDENING: Global role administration routines are strictly REVOKED from pms_app
-- Initial provisioning and global role assignments must be performed exclusively under pms_admin credentials
REVOKE EXECUTE ON FUNCTION fn_assign_staff_global_role(UUID, VARCHAR, VARCHAR) FROM pms_app;
REVOKE EXECUTE ON FUNCTION fn_revoke_staff_global_role(UUID, VARCHAR, VARCHAR) FROM pms_app;

-- CRITICAL PRIVILEGE HARDENING: Property role administration routines are strictly REVOKED from pms_app
-- Must be managed under pms_admin until authenticated Spring Security service is implemented in Step 3.3
REVOKE EXECUTE ON FUNCTION fn_assign_staff_property_role(UUID, UUID, VARCHAR, VARCHAR) FROM pms_app;
REVOKE EXECUTE ON FUNCTION fn_revoke_staff_property_role(UUID, UUID, VARCHAR, VARCHAR) FROM pms_app;

-- CRITICAL PRIVILEGE HARDENING: Password modification routines are strictly REVOKED from pms_app
-- Administrative resets require pms_admin; self-service requires Spring Security authenticated caller validation in Step 3.3
REVOKE EXECUTE ON FUNCTION fn_admin_reset_staff_password(UUID, VARCHAR, VARCHAR) FROM pms_app;
REVOKE EXECUTE ON FUNCTION fn_staff_self_update_password(UUID, VARCHAR, VARCHAR) FROM pms_app;
REVOKE EXECUTE ON FUNCTION fn_update_staff_password(UUID, VARCHAR, VARCHAR) FROM pms_app;

-- CRITICAL PRIVILEGE HARDENING: Active status modification is strictly REVOKED from pms_app
-- Account activation and deactivation must be performed under pms_admin until Step 3.3
REVOKE EXECUTE ON FUNCTION fn_set_staff_active_status(UUID, BOOLEAN, VARCHAR) FROM pms_app;

-- Controlled operational login attempt recording: pms_app can record login failures.
-- Asserting login success is blocked inside the function for non-pms_admin callers.
GRANT EXECUTE ON FUNCTION fn_record_staff_login_attempt(VARCHAR, BOOLEAN, VARCHAR, VARCHAR) TO pms_app;

-- 3. Strict Privilege Denial for pms_finance_admin
-- Finance operations must not have access to staff credentials, passwords, or tokens
REVOKE ALL ON
    staff_users,
    staff_property_roles,
    staff_global_roles,
    staff_refresh_tokens,
    staff_security_audit_events,
    vw_staff_effective_permissions
FROM pms_finance_admin;

REVOKE ALL ON FUNCTION fn_assign_staff_global_role(UUID, VARCHAR, VARCHAR) FROM pms_finance_admin;
REVOKE ALL ON FUNCTION fn_revoke_staff_global_role(UUID, VARCHAR, VARCHAR) FROM pms_finance_admin;
REVOKE ALL ON FUNCTION fn_assign_staff_property_role(UUID, UUID, VARCHAR, VARCHAR) FROM pms_finance_admin;
REVOKE ALL ON FUNCTION fn_revoke_staff_property_role(UUID, UUID, VARCHAR, VARCHAR) FROM pms_finance_admin;
REVOKE ALL ON FUNCTION fn_admin_reset_staff_password(UUID, VARCHAR, VARCHAR) FROM pms_finance_admin;
REVOKE ALL ON FUNCTION fn_staff_self_update_password(UUID, VARCHAR, VARCHAR) FROM pms_finance_admin;
REVOKE ALL ON FUNCTION fn_update_staff_password(UUID, VARCHAR, VARCHAR) FROM pms_finance_admin;
REVOKE ALL ON FUNCTION fn_record_staff_login_attempt(VARCHAR, BOOLEAN, VARCHAR, VARCHAR) FROM pms_finance_admin;
REVOKE ALL ON FUNCTION fn_set_staff_active_status(UUID, BOOLEAN, VARCHAR) FROM pms_finance_admin;

-- Optional read-only access to role dictionary for administrative reference
GRANT SELECT ON staff_roles TO pms_finance_admin;

-- 4. Administrative & Migration Ownership to pms_admin
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO pms_admin;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO pms_admin;
GRANT ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public TO pms_admin;
