-- =============================================================================
-- Phase D: Post-V9 Granular Access Grants and Least-Privilege Hardening
-- Run by: PostgreSQL Superuser or pms_admin (Schema Owner)
-- Timing: After Flyway V9 migration has completed successfully
--
-- Objectives:
-- 1. Lock down V9 authentication routines from PUBLIC.
-- 2. Restrict pms_app: column-level SELECT on staff_users (NO password_hash access).
-- 3. Grant least-privilege authentication access to dedicated role pms_auth.
-- 4. Strictly deny privileged staff administration routines from pms_auth
--    (deferred to Step 3.3C/D).
-- 5. Isolate PMS domain and finance tables from pms_auth.
-- 6. Grant pms_admin full administrative and migration ownership.
-- =============================================================================

-- 1. Revoke Default PUBLIC Access on V9 Routines
REVOKE ALL ON FUNCTION fn_record_staff_login_success(UUID, VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_record_staff_login_attempt(VARCHAR, BOOLEAN, VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_rotate_refresh_token(VARCHAR, VARCHAR, TIMESTAMPTZ, VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_revoke_refresh_token_family(UUID, VARCHAR, VARCHAR) FROM PUBLIC;

-- 2. Application Runtime Grants & Hardening (pms_app)
-- Column-specific SELECT: pms_app CANNOT read password_hash!
REVOKE SELECT ON staff_users FROM pms_app;
GRANT SELECT (
    id, email, first_name, last_name, phone,
    is_active, is_locked, failed_login_attempts,
    lockout_until, last_login_at, password_changed_at,
    created_at, updated_at
) ON staff_users TO pms_app;

-- Retain operational user creation and profile updates
GRANT INSERT ON staff_users TO pms_app;
GRANT UPDATE (first_name, last_name, phone, updated_at) ON staff_users TO pms_app;
REVOKE DELETE ON staff_users FROM pms_app;

-- Refresh tokens: EXCLUSIVELY managed by pms_auth. ALL privileges strictly REVOKED from pms_app.
REVOKE ALL ON staff_refresh_tokens FROM pms_app, PUBLIC;

-- Function execution: pms_app can record login failures, but cannot assert success or rotate tokens
GRANT EXECUTE ON FUNCTION fn_record_staff_login_attempt(VARCHAR, BOOLEAN, VARCHAR, VARCHAR) TO pms_app;
REVOKE EXECUTE ON FUNCTION fn_record_staff_login_success(UUID, VARCHAR, VARCHAR) FROM pms_app;
REVOKE EXECUTE ON FUNCTION fn_rotate_refresh_token(VARCHAR, VARCHAR, TIMESTAMPTZ, VARCHAR, VARCHAR) FROM pms_app;
REVOKE EXECUTE ON FUNCTION fn_revoke_refresh_token_family(UUID, VARCHAR, VARCHAR) FROM pms_app;

-- 3. Dedicated Authentication Runtime Grants (pms_auth)
GRANT USAGE ON SCHEMA public TO pms_auth;
REVOKE CREATE ON SCHEMA public FROM pms_auth;

-- Staff users: pms_auth needs password_hash for application-layer BCrypt verification
REVOKE ALL ON staff_users FROM pms_auth;
GRANT SELECT (
    id, email, password_hash, first_name, last_name, phone,
    is_active, is_locked, failed_login_attempts,
    lockout_until, last_login_at, password_changed_at,
    created_at, updated_at
) ON staff_users TO pms_auth;
GRANT INSERT ON staff_users TO pms_auth;
GRANT UPDATE (first_name, last_name, phone, updated_at) ON staff_users TO pms_auth;
REVOKE DELETE ON staff_users FROM pms_auth;

-- Refresh tokens: Full lifecycle management for session tokens
GRANT SELECT, INSERT, UPDATE, DELETE ON staff_refresh_tokens TO pms_auth;

-- Security audit events: Append-only audit logging
GRANT SELECT, INSERT ON staff_security_audit_events TO pms_auth;
REVOKE UPDATE, DELETE ON staff_security_audit_events FROM pms_auth;

-- Roles & effective permissions: Read-only access for session claim generation
GRANT SELECT ON staff_roles TO pms_auth;
GRANT SELECT ON vw_staff_effective_permissions TO pms_auth;
GRANT SELECT ON staff_global_roles TO pms_auth;
GRANT SELECT ON staff_property_roles TO pms_auth;
REVOKE INSERT, UPDATE, DELETE ON staff_global_roles FROM pms_auth;
REVOKE INSERT, UPDATE, DELETE ON staff_property_roles FROM pms_auth;

-- Allowed authentication routines for pms_auth
GRANT EXECUTE ON FUNCTION fn_record_staff_login_attempt(VARCHAR, BOOLEAN, VARCHAR, VARCHAR) TO pms_auth;
GRANT EXECUTE ON FUNCTION fn_record_staff_login_success(UUID, VARCHAR, VARCHAR) TO pms_auth;
GRANT EXECUTE ON FUNCTION fn_rotate_refresh_token(VARCHAR, VARCHAR, TIMESTAMPTZ, VARCHAR, VARCHAR) TO pms_auth;
GRANT EXECUTE ON FUNCTION fn_revoke_refresh_token_family(UUID, VARCHAR, VARCHAR) TO pms_auth;

-- CRITICAL PRIVILEGE HARDENING: Privileged administrative routines remain strictly REVOKED from pms_auth
-- Deferred to Step 3.3C/D pending authorized administrative caller review
REVOKE EXECUTE ON FUNCTION fn_assign_staff_global_role(UUID, VARCHAR, VARCHAR) FROM pms_auth;
REVOKE EXECUTE ON FUNCTION fn_revoke_staff_global_role(UUID, VARCHAR, VARCHAR) FROM pms_auth;
REVOKE EXECUTE ON FUNCTION fn_assign_staff_property_role(UUID, UUID, VARCHAR, VARCHAR) FROM pms_auth;
REVOKE EXECUTE ON FUNCTION fn_revoke_staff_property_role(UUID, UUID, VARCHAR, VARCHAR) FROM pms_auth;
REVOKE EXECUTE ON FUNCTION fn_admin_reset_staff_password(UUID, VARCHAR, VARCHAR) FROM pms_auth;
REVOKE EXECUTE ON FUNCTION fn_set_staff_active_status(UUID, BOOLEAN, VARCHAR) FROM pms_auth;
REVOKE EXECUTE ON FUNCTION fn_staff_self_update_password(UUID, VARCHAR, VARCHAR) FROM pms_auth;
REVOKE EXECUTE ON FUNCTION fn_update_staff_password(UUID, VARCHAR, VARCHAR) FROM pms_auth;

-- Explicit denial on PMS business domain and finance tables for pms_auth
REVOKE ALL ON
    properties,
    room_types,
    bed_types,
    rooms,
    room_beds,
    amenities,
    property_amenities,
    room_amenities,
    guests,
    booking_sources,
    reservations,
    reservation_rooms,
    reservation_guests,
    room_assignments
FROM pms_auth;

REVOKE ALL ON
    finance_accounts,
    finance_journal_entries,
    finance_journal_lines,
    finance_accounting_periods,
    property_financial_settings,
    finance_audit_events
FROM pms_auth;

-- 4. Strict Privilege Denial for pms_finance_admin
REVOKE ALL ON FUNCTION fn_record_staff_login_success(UUID, VARCHAR, VARCHAR) FROM pms_finance_admin;
REVOKE ALL ON FUNCTION fn_rotate_refresh_token(VARCHAR, VARCHAR, TIMESTAMPTZ, VARCHAR, VARCHAR) FROM pms_finance_admin;
REVOKE ALL ON FUNCTION fn_revoke_refresh_token_family(UUID, VARCHAR, VARCHAR) FROM pms_finance_admin;

-- 5. Administrative & Migration Ownership for pms_admin
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO pms_admin;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO pms_admin;
GRANT ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public TO pms_admin;
