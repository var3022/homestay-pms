-- =============================================================================
-- Phase A: Pre-V9 Database Role Provisioning (pms_auth)
-- Run by: PostgreSQL Superuser / Master DBA
-- Timing: Prior to executing Flyway V9 migration
--
-- Principle of Least Privilege:
-- 1. pms_auth is the dedicated authentication and identity runtime role.
--    - Handles staff login state tracking and atomic refresh token rotation.
--    - Reads staff credentials (password_hash) strictly for BCrypt verification.
--    - ZERO access to PMS business domain tables (reservations, guests, rooms, etc.).
--    - ZERO access to financial ledger, accounts, or lock dates.
--    - ZERO superuser or pms_admin privileges.
--    - NO automatic password rotation on subsequent runs.
--
-- Security Controls:
-- 1. No plaintext credentials stored in source control.
-- 2. External credential injection supported via session variable:
--      SET pms.auth_password = '...'; (or external vault ALTER ROLE).
-- 3. Idempotent role creation: only creates role if it does not already exist.
-- 4. Safe against psql variable interpolation bugs inside dollar quotes.
-- =============================================================================

-- 1. Idempotent Role Creation
DO $$
DECLARE
    v_pwd TEXT := current_setting('pms.auth_password', true);
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_auth') THEN
        IF v_pwd IS NOT NULL AND length(trim(v_pwd)) > 0 THEN
            EXECUTE format('CREATE ROLE pms_auth WITH LOGIN PASSWORD %L', v_pwd);
        ELSE
            CREATE ROLE pms_auth WITH LOGIN;
        END IF;
    END IF;
END $$;

-- 2. Schema Usage & DDL Lockdown
-- Role requires USAGE on schema public to access permitted tables and functions,
-- but is strictly DENIED schema creation privileges (no DDL).
GRANT USAGE ON SCHEMA public TO pms_auth;
REVOKE CREATE ON SCHEMA public FROM pms_auth;

-- 3. Explicit Privilege Denial on Domain and Finance Objects
-- Guarantees pms_auth cannot read or mutate business reservations or financial data
DO $$
DECLARE
    t TEXT;
    v_forbidden_tables TEXT[] := ARRAY[
        'properties', 'room_types', 'bed_types', 'rooms', 'room_beds',
        'amenities', 'property_amenities', 'room_amenities', 'guests',
        'booking_sources', 'reservations', 'reservation_rooms',
        'reservation_guests', 'room_assignments', 'finance_accounts',
        'finance_journal_entries', 'finance_journal_lines',
        'finance_accounting_periods', 'property_financial_settings',
        'finance_audit_events'
    ];
BEGIN
    FOREACH t IN ARRAY v_forbidden_tables LOOP
        IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = t) THEN
            EXECUTE format('REVOKE ALL ON TABLE public.%I FROM pms_auth', t);
        END IF;
    END LOOP;
END $$;
