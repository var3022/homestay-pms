-- =============================================================================
-- PMS Database Roles and Granular Security Grants
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
--    - NO direct mutation of accounting periods or audit events.
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

-- 1b. Legacy Upgrade Object Ownership Reassignment
-- If upgrading from legacy pms_user installation, reassign ownership of all existing objects to pms_admin
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_user') THEN
        EXECUTE 'REASSIGN OWNED BY pms_user TO pms_admin';
    END IF;
END $$;

-- 2. Schema Usage & Prevention of DDL Tampering
GRANT USAGE ON SCHEMA public TO pms_admin, pms_app, pms_finance_admin;
REVOKE CREATE ON SCHEMA public FROM pms_app, pms_finance_admin, PUBLIC;
GRANT CREATE ON SCHEMA public TO pms_admin;

-- Ensure schema object ownership belongs to pms_admin
ALTER SCHEMA public OWNER TO pms_admin;

-- 3. Revoke Default PUBLIC Execution on All Routines
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL PROCEDURES IN SCHEMA public FROM PUBLIC;

-- 4. V1-V6 Operational Table Grants for Runtime Role (pms_app)
-- Standard operational CRUD for PMS business domains:
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

-- 5. V7 Finance Table Grants (Least Privilege)

-- A. Accounting Periods & Audit Events: Strictly read-only for runtime & finance admin
-- (Mutations permitted exclusively via validated stored procedures)
GRANT SELECT ON finance_accounting_periods, finance_audit_events TO pms_app, pms_finance_admin;
REVOKE INSERT, UPDATE, DELETE ON finance_accounting_periods, finance_audit_events FROM pms_app, pms_finance_admin, PUBLIC;

-- B. Property Financial Settings:
-- - pms_app: Strictly SELECT only (cannot modify settings or tax config)
-- - pms_finance_admin: SELECT + controlled column UPDATE on tax config and operational settings.
--   Foundational settings (base_currency, accounting_method, fiscal_year_start_month/day) are immutable once periods exist.
GRANT SELECT ON property_financial_settings TO pms_app, pms_finance_admin;
REVOKE INSERT, UPDATE, DELETE ON property_financial_settings FROM pms_app, PUBLIC;
REVOKE INSERT, DELETE ON property_financial_settings FROM pms_finance_admin;
GRANT UPDATE (is_tax_enabled, tax_id_number, books_locked_until_date, auto_post_reservations, lock_period_grace_days, updated_at) 
ON property_financial_settings TO pms_finance_admin;

-- C. Chart of Accounts (finance_accounts):
-- - pms_app: Strictly SELECT only (posting accounts lookup)
-- - pms_finance_admin: SELECT + INSERT (custom sub-accounts) + controlled column UPDATE
--   Protected fields (id, property_id, account_code, account_type, normal_balance, is_system_account) cannot be modified.
--   Hierarchy cycle prevention enforced by trigger trg_prevent_account_hierarchy_cycle.
--   Direct DELETE revoked (soft-delete via is_active = false).
GRANT SELECT ON finance_accounts TO pms_app;
REVOKE INSERT, UPDATE, DELETE ON finance_accounts FROM pms_app, PUBLIC;
GRANT SELECT, INSERT ON finance_accounts TO pms_finance_admin;
GRANT UPDATE (account_name, description, is_allow_posting, is_active, parent_account_id, updated_at) 
ON finance_accounts TO pms_finance_admin;
REVOKE DELETE ON finance_accounts FROM pms_finance_admin;

-- D. Journal Entries: Controlled insertion & DRAFT editing only (Direct DELETE prohibited)
GRANT SELECT, INSERT ON finance_journal_entries TO pms_app, pms_finance_admin;
GRANT UPDATE (description, reference) ON finance_journal_entries TO pms_app, pms_finance_admin;
REVOKE DELETE ON finance_journal_entries FROM pms_app, pms_finance_admin, PUBLIC;

-- E. Journal Lines: Controlled DML (immutability of posted lines enforced by triggers)
GRANT SELECT, INSERT, UPDATE, DELETE ON finance_journal_lines TO pms_app, pms_finance_admin;

-- 6. Stored Procedure & Routine Execution Rights
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

-- Full administrative and migration permissions to pms_admin
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO pms_admin;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO pms_admin;
GRANT ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public TO pms_admin;
