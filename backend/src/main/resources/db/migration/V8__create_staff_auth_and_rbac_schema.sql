-- =============================================================================
-- Migration: V8__create_staff_auth_and_rbac_schema.sql
-- Description: Staff Authentication, Role-Based Access Control (RBAC),
--              Property-Scoped Permissions, Token Management, and Hardened
--              Security Definer Administration Routines.
-- Owner: pms_admin (Schema Owner / Migration Runner)
-- =============================================================================

-- =============================================================================
-- 1. STAFF USERS TABLE
-- =============================================================================
-- Credential Integrity Notice:
-- The database check constraint below validates structural integrity and
-- prevents accidental persistence of raw/plaintext strings or truncated hashes.
-- True cryptographic validation and salting must be enforced by the application
-- layer using an adaptive work-factored encoder (BCrypt strength 12+ or Argon2id).
-- The application service must never persist or log raw passwords.
CREATE TABLE staff_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100),
    phone VARCHAR(50),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    is_locked BOOLEAN NOT NULL DEFAULT FALSE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    lockout_until TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_staff_users_email UNIQUE (email),
    CONSTRAINT chk_staff_users_email_format CHECK (
        email = lower(trim(email)) AND position('@' in email) > 1
    ),
    CONSTRAINT chk_staff_users_password_hash CHECK (
        length(password_hash) >= 50 AND
        (
            password_hash ~ '^\$2[aby]\$[0-9]{2}\$[A-Za-z0-9./]{53}$' OR -- Standard BCrypt
            password_hash ~ '^\$argon2(id?|d)\$.+'                        -- Standard Argon2
        )
    ),
    CONSTRAINT chk_staff_users_first_name CHECK (
        length(trim(first_name)) > 0
    ),
    CONSTRAINT chk_staff_users_failed_attempts CHECK (
        failed_login_attempts >= 0
    ),
    CONSTRAINT chk_staff_users_lockout CHECK (
        (is_locked = FALSE AND lockout_until IS NULL) OR
        (is_locked = TRUE) OR
        (lockout_until IS NOT NULL)
    )
);

CREATE INDEX idx_staff_users_email ON staff_users(email);
CREATE INDEX idx_staff_users_active_lookup ON staff_users(is_active, is_locked);

-- Updated_at trigger for staff_users
CREATE OR REPLACE FUNCTION fn_update_staff_users_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_staff_users_updated_at
BEFORE UPDATE ON staff_users
FOR EACH ROW
EXECUTE FUNCTION fn_update_staff_users_timestamp();

-- =============================================================================
-- 2. STAFF ROLES DICTIONARY TABLE
-- =============================================================================
CREATE TABLE staff_roles (
    role_code VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    is_property_scopable BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_staff_roles_valid_code CHECK (
        role_code IN ('SUPER_ADMIN', 'PROPERTY_ADMIN', 'RECEPTIONIST', 'ACCOUNTANT', 'VIEWER')
    )
);

-- Seed dictionary of exactly the five authorized roles
INSERT INTO staff_roles (role_code, name, description, is_property_scopable) VALUES
    ('SUPER_ADMIN', 'Super Administrator', 'Global full administrative access across all properties, settings, and audit logs', FALSE),
    ('PROPERTY_ADMIN', 'Property Administrator', 'Administrative and operational management for assigned properties', TRUE),
    ('RECEPTIONIST', 'Front Desk Receptionist', 'Operational guest registration, reservations, and room assignment', TRUE),
    ('ACCOUNTANT', 'Property Accountant', 'Financial accounting, journal entries, period closing, and ledger audits', TRUE),
    ('VIEWER', 'Read-Only Viewer', 'Read-only access to operational schedules, calendars, and occupancy summaries', TRUE);

-- =============================================================================
-- 3. PROPERTY-SCOPED STAFF ROLES (staff_property_roles)
-- =============================================================================
CREATE TABLE staff_property_roles (
    staff_user_id UUID NOT NULL,
    property_id UUID NOT NULL,
    role_code VARCHAR(50) NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    assigned_by VARCHAR(100),

    CONSTRAINT pk_staff_property_roles 
        PRIMARY KEY (staff_user_id, property_id, role_code),

    CONSTRAINT fk_staff_property_roles_user 
        FOREIGN KEY (staff_user_id) REFERENCES staff_users(id) ON DELETE CASCADE,

    CONSTRAINT fk_staff_property_roles_property 
        FOREIGN KEY (property_id) REFERENCES properties(id) ON DELETE CASCADE,

    CONSTRAINT fk_staff_property_roles_role 
        FOREIGN KEY (role_code) REFERENCES staff_roles(role_code) ON DELETE RESTRICT,

    -- Property-scoped roles cannot be SUPER_ADMIN (prevent unintended escalation)
    CONSTRAINT chk_staff_property_roles_non_global 
        CHECK (role_code <> 'SUPER_ADMIN')
);

CREATE INDEX idx_staff_property_roles_lookup 
    ON staff_property_roles(staff_user_id, property_id);

CREATE INDEX idx_staff_property_roles_property 
    ON staff_property_roles(property_id, role_code);

-- =============================================================================
-- 4. GLOBAL STAFF ROLES (staff_global_roles)
-- =============================================================================
CREATE TABLE staff_global_roles (
    staff_user_id UUID NOT NULL,
    role_code VARCHAR(50) NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    assigned_by VARCHAR(100),

    CONSTRAINT pk_staff_global_roles 
        PRIMARY KEY (staff_user_id, role_code),

    CONSTRAINT fk_staff_global_roles_user 
        FOREIGN KEY (staff_user_id) REFERENCES staff_users(id) ON DELETE CASCADE,

    CONSTRAINT fk_staff_global_roles_role 
        FOREIGN KEY (role_code) REFERENCES staff_roles(role_code) ON DELETE RESTRICT,

    -- Restricted to explicitly supported global privileges, initially SUPER_ADMIN
    CONSTRAINT chk_staff_global_roles_allowed 
        CHECK (role_code IN ('SUPER_ADMIN'))
);

CREATE INDEX idx_staff_global_roles_user 
    ON staff_global_roles(staff_user_id);

-- =============================================================================
-- 5. REFRESH TOKENS & TOKEN ROTATION (staff_refresh_tokens)
-- =============================================================================
CREATE TABLE staff_refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_user_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    token_family UUID NOT NULL,
    sequence_number INTEGER NOT NULL DEFAULT 1,
    is_revoked BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at TIMESTAMPTZ,
    revoked_reason VARCHAR(100),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMPTZ,
    user_agent VARCHAR(255),
    ip_address VARCHAR(50),

    CONSTRAINT uq_staff_refresh_tokens_hash UNIQUE (token_hash),

    CONSTRAINT fk_staff_refresh_tokens_user 
        FOREIGN KEY (staff_user_id) REFERENCES staff_users(id) ON DELETE CASCADE,

    CONSTRAINT chk_staff_refresh_tokens_hash_len 
        CHECK (length(token_hash) = 64),

    CONSTRAINT chk_staff_refresh_tokens_seq 
        CHECK (sequence_number >= 1),

    CONSTRAINT chk_staff_refresh_tokens_expiry 
        CHECK (expires_at > created_at),

    CONSTRAINT chk_staff_refresh_tokens_revocation CHECK (
        (is_revoked = FALSE AND revoked_at IS NULL AND revoked_reason IS NULL) OR
        (is_revoked = TRUE AND revoked_at IS NOT NULL)
    )
);

CREATE INDEX idx_staff_refresh_tokens_user 
    ON staff_refresh_tokens(staff_user_id);

CREATE INDEX idx_staff_refresh_tokens_family 
    ON staff_refresh_tokens(token_family);

CREATE INDEX idx_staff_refresh_tokens_expires 
    ON staff_refresh_tokens(expires_at) WHERE is_revoked = FALSE;

-- =============================================================================
-- 6. STAFF SECURITY AUDIT LOG (staff_security_audit_events)
-- =============================================================================
CREATE TABLE staff_security_audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type VARCHAR(50) NOT NULL,
    staff_user_id UUID,
    email VARCHAR(255) NOT NULL,
    actor_id VARCHAR(100) NOT NULL,
    property_id UUID,
    ip_address VARCHAR(50),
    user_agent VARCHAR(255),
    details JSONB,
    performed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_staff_security_audit_user 
        FOREIGN KEY (staff_user_id) REFERENCES staff_users(id) ON DELETE SET NULL,

    CONSTRAINT fk_staff_security_audit_property 
        FOREIGN KEY (property_id) REFERENCES properties(id) ON DELETE SET NULL
);

CREATE INDEX idx_staff_security_audit_user_time 
    ON staff_security_audit_events(staff_user_id, performed_at);

CREATE INDEX idx_staff_security_audit_email_time 
    ON staff_security_audit_events(email, performed_at);

CREATE INDEX idx_staff_security_audit_event_time 
    ON staff_security_audit_events(event_type, performed_at);

-- Immutability enforcement: Append-only security audit log
CREATE OR REPLACE FUNCTION fn_prevent_staff_audit_event_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Security integrity violation: Staff security audit events are append-only and cannot be updated or deleted. (Event ID: %)', OLD.id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_staff_security_audit_events_immutable
BEFORE UPDATE OR DELETE ON staff_security_audit_events
FOR EACH ROW
EXECUTE FUNCTION fn_prevent_staff_audit_event_mutation();

-- =============================================================================
-- 7. EFFECTIVE STAFF PERMISSIONS CONSOLIDATED VIEW
-- =============================================================================
CREATE OR REPLACE VIEW vw_staff_effective_permissions AS
SELECT 
    u.id AS staff_user_id,
    u.email,
    u.is_active,
    u.is_locked,
    g.role_code AS role_code,
    NULL::UUID AS property_id,
    TRUE AS is_global,
    g.assigned_at
FROM staff_users u
JOIN staff_global_roles g ON u.id = g.staff_user_id
UNION ALL
SELECT 
    u.id AS staff_user_id,
    u.email,
    u.is_active,
    u.is_locked,
    p.role_code AS role_code,
    p.property_id,
    FALSE AS is_global,
    p.assigned_at
FROM staff_users u
JOIN staff_property_roles p ON u.id = p.staff_user_id;

-- =============================================================================
-- 8. HARDENED SECURITY DEFINER ADMINISTRATIVE & CREDENTIAL PROCEDURES
-- =============================================================================

-- A. Assign Staff Global Role (Explicit, auditable mutation path)
CREATE OR REPLACE FUNCTION fn_assign_staff_global_role(
    p_target_user_id UUID,
    p_role_code VARCHAR,
    p_actor_id VARCHAR
) RETURNS VOID AS $$
DECLARE
    v_user_email VARCHAR;
    v_user_active BOOLEAN;
BEGIN
    -- Privilege check: Global role administration strictly requires pms_admin database credentials
    IF SESSION_USER NOT IN ('pms_admin', 'postgres') THEN
        RAISE EXCEPTION 'Security privilege violation: Global role administration requires pms_admin database credentials (caller: %)', SESSION_USER;
    END IF;

    IF p_actor_id IS NULL OR length(trim(p_actor_id)) = 0 THEN
        RAISE EXCEPTION 'Privileged mutation violation: Actor ID is required for global role assignment';
    END IF;

    -- Concurrency control: Row lock target staff user
    SELECT email, is_active INTO v_user_email, v_user_active
    FROM staff_users
    WHERE id = p_target_user_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Privileged mutation violation: Target staff user % does not exist', p_target_user_id;
    END IF;

    IF NOT v_user_active THEN
        RAISE EXCEPTION 'Privileged mutation violation: Cannot grant global role to deactivated staff user %', p_target_user_id;
    END IF;

    -- Insert into staff_global_roles (guarded by chk_staff_global_roles_allowed)
    INSERT INTO staff_global_roles (staff_user_id, role_code, assigned_by)
    VALUES (p_target_user_id, p_role_code, p_actor_id)
    ON CONFLICT (staff_user_id, role_code) DO NOTHING;

    -- Mandatory immutable security audit log (capturing claimed actor and verified database session user)
    INSERT INTO staff_security_audit_events (
        event_type, staff_user_id, email, actor_id, details
    ) VALUES (
        'GLOBAL_ROLE_ASSIGNED',
        p_target_user_id,
        v_user_email,
        p_actor_id,
        jsonb_build_object('role_code', p_role_code, 'action', 'ASSIGN', 'session_user', SESSION_USER, 'claimed_actor', p_actor_id)
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- B. Revoke Staff Global Role (With last-SUPER_ADMIN lockout protection)
CREATE OR REPLACE FUNCTION fn_revoke_staff_global_role(
    p_target_user_id UUID,
    p_role_code VARCHAR,
    p_actor_id VARCHAR
) RETURNS VOID AS $$
DECLARE
    v_user_email VARCHAR;
    v_remaining_superadmins INTEGER;
BEGIN
    -- Privilege check: Global role revocation strictly requires pms_admin database credentials
    IF SESSION_USER NOT IN ('pms_admin', 'postgres') THEN
        RAISE EXCEPTION 'Security privilege violation: Global role administration requires pms_admin database credentials (caller: %)', SESSION_USER;
    END IF;

    IF p_actor_id IS NULL OR length(trim(p_actor_id)) = 0 THEN
        RAISE EXCEPTION 'Privileged mutation violation: Actor ID is required for global role revocation';
    END IF;

    -- Concurrency control: Row lock target staff user
    SELECT email INTO v_user_email
    FROM staff_users
    WHERE id = p_target_user_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Privileged mutation violation: Target staff user % does not exist', p_target_user_id;
    END IF;

    -- System lockout protection: Do not allow revoking the last active SUPER_ADMIN
    IF p_role_code = 'SUPER_ADMIN' THEN
        SELECT count(*) INTO v_remaining_superadmins
        FROM staff_global_roles g
        JOIN staff_users u ON g.staff_user_id = u.id
        WHERE g.role_code = 'SUPER_ADMIN' AND u.is_active = TRUE AND g.staff_user_id <> p_target_user_id;

        IF v_remaining_superadmins < 1 THEN
            RAISE EXCEPTION 'Security lockout protection: Cannot revoke the last active SUPER_ADMIN in the system';
        END IF;
    END IF;

    DELETE FROM staff_global_roles
    WHERE staff_user_id = p_target_user_id AND role_code = p_role_code;

    -- Mandatory immutable security audit log
    INSERT INTO staff_security_audit_events (
        event_type, staff_user_id, email, actor_id, details
    ) VALUES (
        'GLOBAL_ROLE_REVOKED',
        p_target_user_id,
        v_user_email,
        p_actor_id,
        jsonb_build_object('role_code', p_role_code, 'action', 'REVOKE', 'session_user', SESSION_USER, 'claimed_actor', p_actor_id)
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- C. Assign Staff Property Role (Controlled & Audited Mutation Path)
CREATE OR REPLACE FUNCTION fn_assign_staff_property_role(
    p_target_user_id UUID,
    p_property_id UUID,
    p_role_code VARCHAR,
    p_actor_id VARCHAR
) RETURNS VOID AS $$
DECLARE
    v_user_email VARCHAR;
    v_user_active BOOLEAN;
    v_role_exists BOOLEAN;
    v_property_exists BOOLEAN;
BEGIN
    IF p_actor_id IS NULL OR length(trim(p_actor_id)) = 0 THEN
        RAISE EXCEPTION 'Privileged mutation violation: Actor ID is required for property role assignment';
    END IF;

    -- Validate role exists in dictionary
    SELECT EXISTS (SELECT 1 FROM staff_roles WHERE role_code = p_role_code) INTO v_role_exists;
    IF NOT v_role_exists THEN
        RAISE EXCEPTION 'Role % does not exist', p_role_code;
    END IF;

    -- SUPER_ADMIN is strictly a global role and cannot be assigned to a property
    IF p_role_code = 'SUPER_ADMIN' THEN
        RAISE EXCEPTION 'Security privilege violation: SUPER_ADMIN is a global role and cannot be assigned to a property';
    END IF;

    -- Validate property exists
    SELECT EXISTS (SELECT 1 FROM properties WHERE id = p_property_id) INTO v_property_exists;
    IF NOT v_property_exists THEN
        RAISE EXCEPTION 'Property % does not exist', p_property_id;
    END IF;

    -- Privileged roles protection: PROPERTY_ADMIN and ACCOUNTANT require pms_admin database credentials
    IF p_role_code IN ('PROPERTY_ADMIN', 'ACCOUNTANT') AND SESSION_USER NOT IN ('pms_admin', 'postgres') THEN
        RAISE EXCEPTION 'Security privilege violation: Assigning privileged property role % requires pms_admin database credentials (caller: %)', p_role_code, SESSION_USER;
    END IF;

    -- Concurrency control: Row lock target staff user
    SELECT email, is_active INTO v_user_email, v_user_active
    FROM staff_users
    WHERE id = p_target_user_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Privileged mutation violation: Target staff user % does not exist', p_target_user_id;
    END IF;

    IF NOT v_user_active THEN
        RAISE EXCEPTION 'Privileged mutation violation: Cannot grant property role to deactivated staff user %', p_target_user_id;
    END IF;

    INSERT INTO staff_property_roles (staff_user_id, property_id, role_code, assigned_by)
    VALUES (p_target_user_id, p_property_id, p_role_code, p_actor_id)
    ON CONFLICT (staff_user_id, property_id, role_code) DO NOTHING;

    INSERT INTO staff_security_audit_events (
        event_type, staff_user_id, email, actor_id, property_id, details
    ) VALUES (
        'PROPERTY_ROLE_ASSIGNED',
        p_target_user_id,
        v_user_email,
        p_actor_id,
        p_property_id,
        jsonb_build_object(
            'role_code', p_role_code,
            'property_id', p_property_id,
            'action', 'ASSIGN',
            'session_user', SESSION_USER,
            'claimed_actor', p_actor_id
        )
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- D. Revoke Staff Property Role (Controlled & Audited Mutation Path)
CREATE OR REPLACE FUNCTION fn_revoke_staff_property_role(
    p_target_user_id UUID,
    p_property_id UUID,
    p_role_code VARCHAR,
    p_actor_id VARCHAR
) RETURNS VOID AS $$
DECLARE
    v_user_email VARCHAR;
BEGIN
    IF p_actor_id IS NULL OR length(trim(p_actor_id)) = 0 THEN
        RAISE EXCEPTION 'Privileged mutation violation: Actor ID is required for property role revocation';
    END IF;

    -- Privileged roles protection: Revoking PROPERTY_ADMIN and ACCOUNTANT requires pms_admin database credentials
    IF p_role_code IN ('PROPERTY_ADMIN', 'ACCOUNTANT') AND SESSION_USER NOT IN ('pms_admin', 'postgres') THEN
        RAISE EXCEPTION 'Security privilege violation: Revoking privileged property role % requires pms_admin database credentials (caller: %)', p_role_code, SESSION_USER;
    END IF;

    -- Concurrency control: Row lock target staff user
    SELECT email INTO v_user_email
    FROM staff_users
    WHERE id = p_target_user_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Privileged mutation violation: Target staff user % does not exist', p_target_user_id;
    END IF;

    DELETE FROM staff_property_roles
    WHERE staff_user_id = p_target_user_id
      AND property_id = p_property_id
      AND role_code = p_role_code;

    INSERT INTO staff_security_audit_events (
        event_type, staff_user_id, email, actor_id, property_id, details
    ) VALUES (
        'PROPERTY_ROLE_REVOKED',
        p_target_user_id,
        v_user_email,
        p_actor_id,
        p_property_id,
        jsonb_build_object(
            'role_code', p_role_code,
            'property_id', p_property_id,
            'action', 'REVOKE',
            'session_user', SESSION_USER,
            'claimed_actor', p_actor_id
        )
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- E. Privileged Administrative Password Reset
CREATE OR REPLACE FUNCTION fn_admin_reset_staff_password(
    p_user_id UUID,
    p_new_password_hash VARCHAR,
    p_actor_id VARCHAR
) RETURNS VOID AS $$
DECLARE
    v_user_email VARCHAR;
BEGIN
    -- Privilege check: Administrative password reset strictly requires pms_admin database credentials
    IF SESSION_USER NOT IN ('pms_admin', 'postgres') THEN
        RAISE EXCEPTION 'Security privilege violation: Administrative password reset requires pms_admin database credentials (caller: %)', SESSION_USER;
    END IF;

    IF p_actor_id IS NULL OR length(trim(p_actor_id)) = 0 THEN
        RAISE EXCEPTION 'Credential mutation violation: Actor ID is required for administrative password reset';
    END IF;

    IF length(trim(p_new_password_hash)) < 60 THEN
        RAISE EXCEPTION 'Credential mutation violation: Password hash does not meet minimum storage requirements';
    END IF;

    -- Concurrency control: Row lock target staff user
    SELECT email INTO v_user_email
    FROM staff_users
    WHERE id = p_user_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Credential mutation violation: Staff user % does not exist', p_user_id;
    END IF;

    -- Update password and reset lockout counters
    UPDATE staff_users
    SET password_hash = p_new_password_hash,
        password_changed_at = CURRENT_TIMESTAMP,
        failed_login_attempts = 0,
        is_locked = FALSE,
        lockout_until = NULL,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_user_id;

    -- Security best practice: Invalidate all existing active refresh tokens across all devices
    UPDATE staff_refresh_tokens
    SET is_revoked = TRUE,
        revoked_at = CURRENT_TIMESTAMP,
        revoked_reason = 'ADMIN_PASSWORD_RESET'
    WHERE staff_user_id = p_user_id AND is_revoked = FALSE;

    -- Mandatory immutable security audit log
    INSERT INTO staff_security_audit_events (
        event_type, staff_user_id, email, actor_id, details
    ) VALUES (
        'ADMIN_PASSWORD_RESET',
        p_user_id,
        v_user_email,
        p_actor_id,
        jsonb_build_object('action', 'ADMIN_PASSWORD_RESET', 'session_user', SESSION_USER, 'claimed_actor', p_actor_id)
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- F. Staff Self-Service Password Update
CREATE OR REPLACE FUNCTION fn_staff_self_update_password(
    p_user_id UUID,
    p_new_password_hash VARCHAR,
    p_actor_id VARCHAR
) RETURNS VOID AS $$
DECLARE
    v_user_email VARCHAR;
BEGIN
    IF p_actor_id IS NULL OR length(trim(p_actor_id)) = 0 THEN
        RAISE EXCEPTION 'Credential mutation violation: Actor ID is required for password update';
    END IF;

    IF length(trim(p_new_password_hash)) < 60 THEN
        RAISE EXCEPTION 'Credential mutation violation: Password hash does not meet minimum storage requirements';
    END IF;

    -- Concurrency control: Row lock target staff user
    SELECT email INTO v_user_email
    FROM staff_users
    WHERE id = p_user_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Credential mutation violation: Staff user % does not exist', p_user_id;
    END IF;

    -- Update password and reset lockout counters
    UPDATE staff_users
    SET password_hash = p_new_password_hash,
        password_changed_at = CURRENT_TIMESTAMP,
        failed_login_attempts = 0,
        is_locked = FALSE,
        lockout_until = NULL,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_user_id;

    -- Security best practice: Invalidate all existing active refresh tokens across all devices
    UPDATE staff_refresh_tokens
    SET is_revoked = TRUE,
        revoked_at = CURRENT_TIMESTAMP,
        revoked_reason = 'PASSWORD_CHANGED'
    WHERE staff_user_id = p_user_id AND is_revoked = FALSE;

    -- Mandatory immutable security audit log
    INSERT INTO staff_security_audit_events (
        event_type, staff_user_id, email, actor_id, details
    ) VALUES (
        'PASSWORD_CHANGED',
        p_user_id,
        v_user_email,
        p_actor_id,
        jsonb_build_object('action', 'PASSWORD_CHANGED', 'session_user', SESSION_USER, 'claimed_actor', p_actor_id)
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- Legacy / Self-service alias
CREATE OR REPLACE FUNCTION fn_update_staff_password(
    p_user_id UUID,
    p_new_password_hash VARCHAR,
    p_actor_id VARCHAR
) RETURNS VOID AS $$
BEGIN
    PERFORM fn_staff_self_update_password(p_user_id, p_new_password_hash, p_actor_id);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- D. Controlled Staff Login Attempt Recording (Audited & Safe Rate Control)
CREATE OR REPLACE FUNCTION fn_record_staff_login_attempt(
    p_email VARCHAR,
    p_success BOOLEAN,
    p_ip_address VARCHAR DEFAULT NULL,
    p_user_agent VARCHAR DEFAULT NULL
) RETURNS BOOLEAN AS $$
DECLARE
    v_user_id UUID;
    v_attempts INTEGER;
    v_locked BOOLEAN;
    v_lockout_until TIMESTAMPTZ;
    v_is_active BOOLEAN;
BEGIN
    SELECT id, failed_login_attempts, is_locked, lockout_until, is_active
    INTO v_user_id, v_attempts, v_locked, v_lockout_until, v_is_active
    FROM staff_users
    WHERE email = lower(trim(p_email))
    FOR UPDATE;

    IF NOT FOUND THEN
        INSERT INTO staff_security_audit_events (
            event_type, email, actor_id, ip_address, user_agent, details
        ) VALUES (
            'LOGIN_FAILED_UNKNOWN_USER',
            lower(trim(p_email)),
            'system:auth',
            p_ip_address,
            p_user_agent,
            jsonb_build_object('reason', 'USER_NOT_FOUND')
        );
        RETURN FALSE;
    END IF;

    -- 1. Inactive/deactivated account guard: Never grant access or clear lockouts
    IF NOT v_is_active THEN
        INSERT INTO staff_security_audit_events (
            event_type, staff_user_id, email, actor_id, ip_address, user_agent, details
        ) VALUES (
            'LOGIN_FAILED_DEACTIVATED_USER',
            v_user_id,
            lower(trim(p_email)),
            'system:auth',
            p_ip_address,
            p_user_agent,
            jsonb_build_object('reason', 'ACCOUNT_DEACTIVATED')
        );
        RETURN FALSE;
    END IF;

    -- 2. Active lockout guard: Reject login attempts while lockout duration is active
    IF v_locked AND v_lockout_until IS NOT NULL AND v_lockout_until > CURRENT_TIMESTAMP THEN
        INSERT INTO staff_security_audit_events (
            event_type, staff_user_id, email, actor_id, ip_address, user_agent, details
        ) VALUES (
            'LOGIN_REJECTED_LOCKED_ACCOUNT',
            v_user_id,
            lower(trim(p_email)),
            'system:auth',
            p_ip_address,
            p_user_agent,
            jsonb_build_object('reason', 'ACCOUNT_LOCKED', 'lockout_until', v_lockout_until)
        );
        RETURN FALSE;
    END IF;

    -- 3. Expired lockout handling: Reset temporary lockout if duration has elapsed
    IF v_locked AND (v_lockout_until IS NULL OR v_lockout_until <= CURRENT_TIMESTAMP) THEN
        v_locked := FALSE;
        v_attempts := 0;
    END IF;

    IF p_success THEN
        -- Untrusted caller guard: Direct assertion of login success requires pms_admin credentials
        -- In application runtime, Spring Security verifies credentials prior to asserting success via an authorized provider
        IF SESSION_USER NOT IN ('pms_admin', 'postgres') THEN
            RAISE EXCEPTION 'Security privilege violation: Direct assertion of login success requires pms_admin database credentials or authenticated security provider (caller: %)', SESSION_USER;
        END IF;

        UPDATE staff_users
        SET failed_login_attempts = 0,
            lockout_until = NULL,
            is_locked = FALSE,
            last_login_at = CURRENT_TIMESTAMP,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = v_user_id;

        INSERT INTO staff_security_audit_events (
            event_type, staff_user_id, email, actor_id, ip_address, user_agent, details
        ) VALUES (
            'LOGIN_SUCCESS',
            v_user_id,
            lower(trim(p_email)),
            v_user_id::text,
            p_ip_address,
            p_user_agent,
            jsonb_build_object('action', 'LOGIN', 'session_user', SESSION_USER)
        );
        RETURN TRUE;
    ELSE
        v_attempts := v_attempts + 1;
        IF v_attempts >= 5 THEN
            UPDATE staff_users
            SET failed_login_attempts = v_attempts,
                is_locked = TRUE,
                lockout_until = CURRENT_TIMESTAMP + INTERVAL '15 minutes',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = v_user_id;

            INSERT INTO staff_security_audit_events (
                event_type, staff_user_id, email, actor_id, ip_address, user_agent, details
            ) VALUES (
                'ACCOUNT_LOCKED',
                v_user_id,
                lower(trim(p_email)),
                'system:auth',
                p_ip_address,
                p_user_agent,
                jsonb_build_object('failed_attempts', v_attempts, 'lockout_minutes', 15)
            );
        ELSE
            UPDATE staff_users
            SET failed_login_attempts = v_attempts,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = v_user_id;

            INSERT INTO staff_security_audit_events (
                event_type, staff_user_id, email, actor_id, ip_address, user_agent, details
            ) VALUES (
                'LOGIN_FAILED',
                v_user_id,
                lower(trim(p_email)),
                'system:auth',
                p_ip_address,
                p_user_agent,
                jsonb_build_object('failed_attempts', v_attempts)
            );
        END IF;
        RETURN FALSE;
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- E. Controlled Staff Active Status Modification (Deactivation / Activation)
CREATE OR REPLACE FUNCTION fn_set_staff_active_status(
    p_user_id UUID,
    p_is_active BOOLEAN,
    p_actor_id VARCHAR
) RETURNS VOID AS $$
DECLARE
    v_user_email VARCHAR;
    v_current_active BOOLEAN;
    v_is_superadmin BOOLEAN;
    v_remaining_superadmins INTEGER;
BEGIN
    IF p_actor_id IS NULL OR length(trim(p_actor_id)) = 0 THEN
        RAISE EXCEPTION 'Privileged mutation violation: Actor ID is required for status change';
    END IF;

    -- Concurrency control: Row lock target staff user
    SELECT email, is_active INTO v_user_email, v_current_active
    FROM staff_users
    WHERE id = p_user_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Privileged mutation violation: Staff user % does not exist', p_user_id;
    END IF;

    -- Check if target user has SUPER_ADMIN role
    SELECT EXISTS (
        SELECT 1 FROM staff_global_roles
        WHERE staff_user_id = p_user_id AND role_code = 'SUPER_ADMIN'
    ) INTO v_is_superadmin;

    -- If target is SUPER_ADMIN, modifying active status requires pms_admin database credentials
    IF v_is_superadmin AND SESSION_USER NOT IN ('pms_admin', 'postgres') THEN
        RAISE EXCEPTION 'Security privilege violation: Modifying active status for a SUPER_ADMIN requires pms_admin database credentials (caller: %)', SESSION_USER;
    END IF;

    -- Last active SUPER_ADMIN protection
    IF v_is_superadmin AND NOT p_is_active THEN
        SELECT count(*) INTO v_remaining_superadmins
        FROM staff_global_roles g
        JOIN staff_users u ON g.staff_user_id = u.id
        WHERE g.role_code = 'SUPER_ADMIN' AND u.is_active = TRUE AND g.staff_user_id <> p_user_id;

        IF v_remaining_superadmins < 1 THEN
            RAISE EXCEPTION 'Security lockout protection: Cannot deactivate the last active SUPER_ADMIN in the system';
        END IF;
    END IF;

    -- Update active status
    UPDATE staff_users
    SET is_active = p_is_active,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_user_id;

    -- If deactivating, invalidate all active refresh tokens immediately
    IF NOT p_is_active THEN
        UPDATE staff_refresh_tokens
        SET is_revoked = TRUE,
            revoked_at = CURRENT_TIMESTAMP,
            revoked_reason = 'USER_DEACTIVATED'
        WHERE staff_user_id = p_user_id AND is_revoked = FALSE;
    END IF;

    -- Mandatory immutable security audit log
    INSERT INTO staff_security_audit_events (
        event_type, staff_user_id, email, actor_id, details
    ) VALUES (
        CASE WHEN p_is_active THEN 'STAFF_ACTIVATED' ELSE 'STAFF_DEACTIVATED' END,
        p_user_id,
        v_user_email,
        p_actor_id,
        jsonb_build_object(
            'action', CASE WHEN p_is_active THEN 'ACTIVATE' ELSE 'DEACTIVATE' END,
            'session_user', SESSION_USER,
            'claimed_actor', p_actor_id
        )
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;
