-- =============================================================================
-- Phase A: Pre-V7 Database Role & Schema Provisioning
-- Run by: PostgreSQL Superuser / Master DBA
-- Timing: Prior to executing Flyway V7 migration
--
-- Objectives:
-- 1. Idempotently create database roles (pms_admin, pms_app, pms_finance_admin)
--    without embedded credentials (passwords provisioned via external vault).
-- 2. Establish schema usage and creation privileges for migration runner (pms_admin).
-- 3. Scoped, safe, repeatable ownership transfer of existing PMS V1-V6 tables
--    and flyway_schema_history to pms_admin (avoiding blanket reassignments).
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

-- 2. Schema Usage & DDL Creation Privileges for Migration Runner
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
