-- =============================================================================
-- PMS Database Roles and Granular Security Grants (Consolidated Specification)
-- Environment: Staging / Production / Isolated Test Environments
-- 
-- Principle of Least Privilege:
-- 1. pms_admin: Schema owner and migration runner (Flyway). Full DDL/DML.
-- 2. pms_app: Restricted application runtime role.
--    - Full DML (SELECT, INSERT, UPDATE, DELETE) on V1-V6 operational tables.
--    - Zero DDL privileges (no ALTER, DROP, CREATE TABLE).
--    - Selective read/write on V7 finance tables (guarded by triggers/functions).
--    - NO direct delete on journal entries.
--    - NO direct mutation on accounts, accounting periods, settings, or audit log.
--    - Execution rights on daily operational procedures (post, reverse, discard).
-- 3. pms_finance_admin: Authorized financial administrator role.
--    - Management of custom chart of accounts (INSERT, controlled column UPDATE).
--    - Modification of tax configuration & operational settings (controlled column UPDATE).
--    - Execution rights on period closing, cutover approval, and initialization.
--    - NO direct mutation of accounting periods, audit events, or books_locked_until_date.
--
-- Deployment Note:
-- In fresh or staged deployments, execute scripts/pre_v7_provisioning.sql before
-- Flyway V7, and scripts/post_v7_grants.sql after Flyway V7.
-- =============================================================================

-- 1. Idempotent Role Creation (Credentials managed externally via vault/env)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_admin') THEN
        CREATE ROLE pms_admin WITH LOGIN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_finance_admin') THEN
        CREATE ROLE pms_finance_admin WITH LOGIN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_app') THEN
        CREATE ROLE pms_app WITH LOGIN;
    END IF;
END $$;

-- 2. Schema Usage & DDL Privileges for Migration Runner
GRANT USAGE, CREATE ON SCHEMA public TO pms_admin;
ALTER SCHEMA public OWNER TO pms_admin;

-- 3. Explicit Scoped Legacy Ownership Transfer (Safe, Targeted, Repeatable)
-- Transfers only known PMS V1-V6 domain tables and Flyway history to pms_admin.
-- Avoids touching unrelated database objects, system catalogs, or extensions.
DO $$
DECLARE
    t TEXT;
    v_pms_tables TEXT[] := ARRAY[
        'flyway_schema_history',
        'properties',
        'room_types',
        'bed_types',
        'rooms',
        'room_beds',
        'amenities',
        'property_amenities',
        'room_amenities',
        'guests',
        'booking_sources',
        'reservations',
        'reservation_rooms',
        'reservation_guests',
        'room_assignments'
    ];
BEGIN
    FOREACH t IN ARRAY v_pms_tables LOOP
        IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = t) THEN
            EXECUTE format('ALTER TABLE public.%I OWNER TO pms_admin', t);
        END IF;
    END LOOP;
END $$;

-- 4. Schema Usage and DDL Lockdown for Runtime Roles
GRANT USAGE ON SCHEMA public TO pms_app, pms_finance_admin;
REVOKE CREATE ON SCHEMA public FROM pms_app, pms_finance_admin, PUBLIC;

-- 5. Revoke Default PUBLIC Execution on All Routines
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL PROCEDURES IN SCHEMA public FROM PUBLIC;

-- 6. V1-V6 Operational Table Grants for Runtime Role (pms_app)
GRANT SELECT, INSERT, UPDATE, DELETE ON 
    properties,
    rooms,
    room_types,
    bed_types,
    room_beds,
    amenities,
    property_amenities,
    room_amenities,
    guests,
    reservations,
    reservation_rooms,
    reservation_guests,
    room_assignments,
    booking_sources
TO pms_app;

-- Grant SELECT to finance admin on operational tables
GRANT SELECT ON 
    properties,
    rooms,
    room_types,
    bed_types,
    room_beds,
    amenities,
    property_amenities,
    room_amenities,
    guests,
    reservations,
    reservation_rooms,
    reservation_guests,
    room_assignments,
    booking_sources
TO pms_finance_admin;

-- Grant USAGE on sequences to runtime roles
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO pms_app, pms_finance_admin;

-- 7. V7 Finance Table Grants (Least Privilege)

-- A. Accounting Periods & Audit Events: Strictly read-only for runtime & finance admin
-- (Mutations permitted exclusively via validated stored procedures)
REVOKE ALL ON finance_accounting_periods, finance_audit_events FROM pms_app, pms_finance_admin, PUBLIC;
GRANT SELECT ON finance_accounting_periods, finance_audit_events TO pms_app, pms_finance_admin;

-- B. Property Financial Settings:
-- - pms_app: Strictly SELECT only (cannot modify settings or tax config)
-- - pms_finance_admin: SELECT + controlled column UPDATE on tax config and operational settings.
--   CRITICAL SECURITY CONTROL: books_locked_until_date direct UPDATE is strictly REVOKED from both
--   pms_app and pms_finance_admin! Lock dates can ONLY be advanced monotonically via authorized
--   fn_close_accounting_period and fn_approve_historical_cutover procedures.
--   Foundational settings (base_currency, accounting_method, fiscal_year_start_month/day) are immutable.
REVOKE ALL ON property_financial_settings FROM pms_app, pms_finance_admin, PUBLIC;
GRANT SELECT ON property_financial_settings TO pms_app, pms_finance_admin;
GRANT UPDATE (is_tax_enabled, tax_id_number, auto_post_reservations, lock_period_grace_days, updated_at)
ON property_financial_settings TO pms_finance_admin;

-- C. Chart of Accounts (finance_accounts):
-- - pms_app: Strictly SELECT only (posting accounts lookup)
-- - pms_finance_admin: SELECT + INSERT (custom sub-accounts) + controlled column UPDATE
--   Protected fields (id, property_id, account_code, account_type, normal_balance, is_system_account) cannot be modified.
--   Hierarchy cycles prevented by trigger trg_prevent_account_hierarchy_cycle.
--   Direct DELETE revoked (soft-delete via is_active = false).
REVOKE ALL ON finance_accounts FROM pms_app, pms_finance_admin, PUBLIC;
GRANT SELECT ON finance_accounts TO pms_app;
GRANT SELECT, INSERT ON finance_accounts TO pms_finance_admin;
GRANT UPDATE (account_name, description, is_allow_posting, is_active, parent_account_id, updated_at) 
ON finance_accounts TO pms_finance_admin;

-- D. Journal Entries: Controlled insertion & DRAFT editing only (Direct DELETE prohibited)
REVOKE ALL ON finance_journal_entries FROM pms_app, pms_finance_admin, PUBLIC;
GRANT SELECT, INSERT ON finance_journal_entries TO pms_app, pms_finance_admin;
GRANT UPDATE (description, reference) ON finance_journal_entries TO pms_app, pms_finance_admin;

-- E. Journal Lines: Controlled DML (immutability of posted lines enforced by triggers)
REVOKE ALL ON finance_journal_lines FROM pms_app, pms_finance_admin, PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON finance_journal_lines TO pms_app, pms_finance_admin;

-- 8. Stored Procedure & Routine Execution Rights
-- Operational accounting procedures granted to runtime pms_app and pms_finance_admin:
GRANT EXECUTE ON FUNCTION fn_post_journal_entry(UUID, VARCHAR) TO pms_app, pms_finance_admin;
GRANT EXECUTE ON FUNCTION fn_reverse_journal_entry(UUID, DATE, UUID, VARCHAR, TEXT, VARCHAR, DATE) TO pms_app, pms_finance_admin;
GRANT EXECUTE ON FUNCTION fn_discard_draft_journal_entry(UUID, TEXT, VARCHAR) TO pms_app, pms_finance_admin;

-- Administrative procedures strictly reserved for pms_finance_admin (REVOKED from pms_app):
REVOKE EXECUTE ON FUNCTION fn_close_accounting_period(UUID, VARCHAR) FROM pms_app, PUBLIC;
REVOKE EXECUTE ON FUNCTION fn_approve_historical_cutover(UUID, UUID, VARCHAR) FROM pms_app, PUBLIC;
REVOKE EXECUTE ON FUNCTION fn_initialize_property_finances(UUID, VARCHAR, SMALLINT, DATE) FROM pms_app, PUBLIC;

GRANT EXECUTE ON FUNCTION fn_close_accounting_period(UUID, VARCHAR) TO pms_finance_admin;
GRANT EXECUTE ON FUNCTION fn_approve_historical_cutover(UUID, UUID, VARCHAR) TO pms_finance_admin;
GRANT EXECUTE ON FUNCTION fn_initialize_property_finances(UUID, VARCHAR, SMALLINT, DATE) TO pms_finance_admin;

-- 9. V8 Staff Authentication & RBAC Table Grants
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
