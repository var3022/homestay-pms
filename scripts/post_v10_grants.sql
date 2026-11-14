-- =============================================================================
-- Phase D: Post-V10 Granular Access Grants and Least-Privilege Hardening
-- Run by: PostgreSQL Superuser or pms_admin (Schema Owner)
-- Timing: After Flyway V10 migration has completed successfully
--
-- Objectives:
-- 1. Ensure table ownership of guest_property_associations belongs strictly to pms_admin.
-- 2. Grant operational runtime role pms_app DML privileges (SELECT, INSERT, UPDATE, DELETE)
--    to maintain guest-property associations.
-- 3. Strictly isolate authentication runtime pms_auth: ZERO access to guest_property_associations.
-- 4. Strictly isolate financial administrator pms_finance_admin: ZERO access to guest_property_associations.
-- 5. Revoke default PUBLIC access on guest_property_associations.
--
-- Dual-DataSource Architecture Notice:
-- Consistent with the PMS security architecture, Flyway schema migrations execute
-- exclusively under migration owner pms_admin. Runtime connections execute under
-- restricted roles (pms_app, pms_auth). Runtime grants are managed explicitly via
-- these post-migration scripts to maintain strict separation of DDL and DML privileges.
-- =============================================================================

-- 1. Schema Owner / Migration Runner Ownership
ALTER TABLE guest_property_associations OWNER TO pms_admin;
GRANT ALL PRIVILEGES ON guest_property_associations TO pms_admin;

-- 2. Revoke Default PUBLIC Access
REVOKE ALL ON guest_property_associations FROM PUBLIC;

-- 3. Operational Runtime Grants (pms_app)
-- pms_app requires full DML to manage guest-property associations during registration and booking
GRANT SELECT, INSERT, UPDATE, DELETE ON guest_property_associations TO pms_app;

-- 4. Dedicated Authentication Runtime Lockdown (pms_auth)
-- pms_auth is strictly restricted to credentials and tokens; zero access to guest data
REVOKE ALL ON guest_property_associations FROM pms_auth;

-- 5. Financial Administrator Lockdown (pms_finance_admin)
-- pms_finance_admin is strictly restricted to general ledger and tax settings
REVOKE ALL ON guest_property_associations FROM pms_finance_admin;
