-- =============================================================================
-- Migration: V9__harden_auth_execution_paths.sql
-- Description: Restricted Authentication Execution Paths: Hardened Login Success
--              State Recording, Concurrency-Safe Attempt Tracking, Refresh Token
--              Family Rotation & Replay Detection, and Performance Indexes.
-- Owner: pms_admin (Schema Owner / Migration Runner)
-- =============================================================================

-- =============================================================================
-- 1. HARDENED LOGIN SUCCESS STATE RECORDING (fn_record_staff_login_success)
-- =============================================================================
--
-- TRUST BOUNDARY SPECIFICATION:
-- 1. Division of Responsibility:
--    - Spring Security (Application Layer) owns credential verification. It
--      evaluates the raw candidate password against the stored adaptive hash
--      (BCrypt strength 12+ or Argon2id) in application memory.
--    - PostgreSQL (Database Layer) owns state invariant enforcement. Even if an
--      authorized runtime role (pms_auth) invokes this procedure, the database
--      engine independently verifies and enforces:
--      a) Target account exists and is locked for update (FOR UPDATE).
--      b) Account is active (is_active = TRUE). Deactivated accounts are rejected.
--      c) Account is not under an active lockout (lockout_until <= NOW()).
-- 2. State Mutation:
--    - Atomically resets failed_login_attempts to 0.
--    - Clears is_locked to FALSE and lockout_until to NULL.
--    - Updates last_login_at and updated_at to CURRENT_TIMESTAMP.
-- 3. Immutability & Audit:
--    - Writes an immutable LOGIN_SUCCESS audit event to staff_security_audit_events.
-- =============================================================================
CREATE OR REPLACE FUNCTION fn_record_staff_login_success(
    p_user_id UUID,
    p_ip_address VARCHAR DEFAULT NULL,
    p_user_agent VARCHAR DEFAULT NULL
) RETURNS BOOLEAN AS $$
DECLARE
    v_user_email VARCHAR;
    v_is_active BOOLEAN;
    v_is_locked BOOLEAN;
    v_lockout_until TIMESTAMPTZ;
BEGIN
    -- Privilege gate: Caller must be authenticated auth role, admin, or postgres
    IF SESSION_USER NOT IN ('pms_admin', 'pms_auth', 'postgres') THEN
        RAISE EXCEPTION 'Security privilege violation: Direct assertion of login success requires pms_admin or pms_auth database credentials (caller: %)', SESSION_USER;
    END IF;

    -- Concurrency control: Serialize state updates on target user
    SELECT email, is_active, is_locked, lockout_until
    INTO v_user_email, v_is_active, v_is_locked, v_lockout_until
    FROM staff_users
    WHERE id = p_user_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Target staff user % does not exist', p_user_id;
    END IF;

    -- Invariant 1: Inactive/deactivated accounts cannot record login success
    IF NOT v_is_active THEN
        INSERT INTO staff_security_audit_events (
            event_type, staff_user_id, email, actor_id, ip_address, user_agent, details
        ) VALUES (
            'LOGIN_FAILED_DEACTIVATED_USER',
            p_user_id,
            v_user_email,
            p_user_id::text,
            p_ip_address,
            p_user_agent,
            jsonb_build_object('reason', 'ACCOUNT_DEACTIVATED', 'session_user', SESSION_USER)
        );
        RETURN FALSE;
    END IF;

    -- Invariant 2: Active lockout cannot be cleared by a success call while lockout is active
    IF v_is_locked AND v_lockout_until IS NOT NULL AND v_lockout_until > CURRENT_TIMESTAMP THEN
        INSERT INTO staff_security_audit_events (
            event_type, staff_user_id, email, actor_id, ip_address, user_agent, details
        ) VALUES (
            'LOGIN_REJECTED_LOCKED_ACCOUNT',
            p_user_id,
            v_user_email,
            p_user_id::text,
            p_ip_address,
            p_user_agent,
            jsonb_build_object('reason', 'ACCOUNT_LOCKED', 'lockout_until', v_lockout_until, 'session_user', SESSION_USER)
        );
        RETURN FALSE;
    END IF;

    -- State Reset: Clear lockouts and failed attempts atomically
    UPDATE staff_users
    SET failed_login_attempts = 0,
        lockout_until = NULL,
        is_locked = FALSE,
        last_login_at = CURRENT_TIMESTAMP,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_user_id;

    -- Immutable Audit Trail
    INSERT INTO staff_security_audit_events (
        event_type, staff_user_id, email, actor_id, ip_address, user_agent, details
    ) VALUES (
        'LOGIN_SUCCESS',
        p_user_id,
        v_user_email,
        p_user_id::text,
        p_ip_address,
        p_user_agent,
        jsonb_build_object('action', 'LOGIN_SUCCESS', 'session_user', SESSION_USER)
    );

    RETURN TRUE;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- =============================================================================
-- 2. HARDENED LOGIN ATTEMPT RECORDING (fn_record_staff_login_attempt)
-- =============================================================================
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
        -- Untrusted caller guard: Direct assertion of login success requires pms_admin or pms_auth credentials
        IF SESSION_USER NOT IN ('pms_admin', 'pms_auth', 'postgres') THEN
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

-- =============================================================================
-- 3. REFRESH TOKEN FAMILY REVOCATION (fn_revoke_refresh_token_family)
-- =============================================================================
CREATE OR REPLACE FUNCTION fn_revoke_refresh_token_family(
    p_token_family UUID,
    p_revoked_reason VARCHAR,
    p_actor_id VARCHAR DEFAULT 'system:auth'
) RETURNS INTEGER AS $$
DECLARE
    v_revoked_count INTEGER;
    v_user_id UUID;
    v_user_email VARCHAR;
BEGIN
    IF SESSION_USER NOT IN ('pms_admin', 'pms_auth', 'postgres') THEN
        RAISE EXCEPTION 'Security privilege violation: Caller % cannot revoke refresh token families', SESSION_USER;
    END IF;

    IF p_token_family IS NULL THEN
        RAISE EXCEPTION 'Invalid argument: token family UUID is required';
    END IF;

    -- Resolve affected user
    SELECT staff_user_id INTO v_user_id
    FROM staff_refresh_tokens
    WHERE token_family = p_token_family
    LIMIT 1;

    IF NOT FOUND THEN
        RETURN 0;
    END IF;

    SELECT email INTO v_user_email FROM staff_users WHERE id = v_user_id;

    -- Revoke all active tokens in the family atomically
    UPDATE staff_refresh_tokens
    SET is_revoked = TRUE,
        revoked_at = CURRENT_TIMESTAMP,
        revoked_reason = p_revoked_reason
    WHERE token_family = p_token_family AND is_revoked = FALSE;

    GET DIAGNOSTICS v_revoked_count = ROW_COUNT;

    -- Audit event logging
    INSERT INTO staff_security_audit_events (
        event_type, staff_user_id, email, actor_id, details
    ) VALUES (
        'TOKEN_FAMILY_REVOKED',
        v_user_id,
        COALESCE(v_user_email, 'unknown'),
        p_actor_id,
        jsonb_build_object(
            'token_family', p_token_family,
            'revoked_count', v_revoked_count,
            'reason', p_revoked_reason,
            'session_user', SESSION_USER
        )
    );

    RETURN v_revoked_count;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- =============================================================================
-- 4. ATOMIC REFRESH TOKEN ROTATION & REPLAY DETECTION (fn_rotate_refresh_token)
-- =============================================================================
CREATE OR REPLACE FUNCTION fn_rotate_refresh_token(
    p_old_token_hash VARCHAR,
    p_new_token_hash VARCHAR,
    p_expires_at TIMESTAMPTZ,
    p_user_agent VARCHAR DEFAULT NULL,
    p_ip_address VARCHAR DEFAULT NULL
) RETURNS UUID AS $$
DECLARE
    v_old_token RECORD;
    v_new_token_id UUID;
    v_user_email VARCHAR;
    v_user_active BOOLEAN;
BEGIN
    IF SESSION_USER NOT IN ('pms_admin', 'pms_auth', 'postgres') THEN
        RAISE EXCEPTION 'Security privilege violation: Refresh token rotation requires pms_admin or pms_auth database credentials (caller: %)', SESSION_USER;
    END IF;

    IF length(p_old_token_hash) <> 64 OR length(p_new_token_hash) <> 64 THEN
        RAISE EXCEPTION 'Cryptographic token violation: Token hash must be exactly 64 hexadecimal characters';
    END IF;

    IF p_expires_at <= CURRENT_TIMESTAMP THEN
        RAISE EXCEPTION 'Token expiry violation: Expiration timestamp must be in the future';
    END IF;

    -- Concurrency control: Row-lock the presented refresh token
    SELECT * INTO v_old_token
    FROM staff_refresh_tokens
    WHERE token_hash = p_old_token_hash
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Token rotation violation: Presented refresh token does not exist';
    END IF;

    -- Target staff user active validation
    SELECT email, is_active INTO v_user_email, v_user_active
    FROM staff_users
    WHERE id = v_old_token.staff_user_id
    FOR UPDATE;

    IF NOT FOUND OR NOT v_user_active THEN
        PERFORM fn_revoke_refresh_token_family(v_old_token.token_family, 'ACCOUNT_DEACTIVATED', 'system:auth');
        RETURN NULL;
    END IF;

    -- CRITICAL REPLAY DETECTION & SIMULTANEOUS ROTATION HANDLING:
    -- If the presented token was ALREADY revoked:
    -- 1. If it was rotated within the last 10 seconds (grace window), this is a simultaneous
    --    refresh from another tab/thread on the same token. Reject this attempt without
    --    invalidating the family, allowing the already-issued successor to remain valid.
    -- 2. Otherwise, this is a true replay attack: invalidate the ENTIRE token family
    --    permanently and emit a REFRESH_TOKEN_REPLAY_DETECTED security audit event.
    IF v_old_token.is_revoked THEN
        IF v_old_token.revoked_reason = 'ROTATED'
           AND v_old_token.revoked_at >= CURRENT_TIMESTAMP - INTERVAL '10 seconds' THEN
            -- Simultaneous rotation attempt: reject safely so only the single successor remains valid
            RETURN NULL;
        END IF;

        PERFORM fn_revoke_refresh_token_family(v_old_token.token_family, 'REPLAY_ATTACK_DETECTED', 'system:auth');

        INSERT INTO staff_security_audit_events (
            event_type, staff_user_id, email, actor_id, ip_address, user_agent, details
        ) VALUES (
            'REFRESH_TOKEN_REPLAY_DETECTED',
            v_old_token.staff_user_id,
            v_user_email,
            'system:auth',
            p_ip_address,
            p_user_agent,
            jsonb_build_object(
                'token_family', v_old_token.token_family,
                'presented_token_id', v_old_token.id,
                'presented_sequence', v_old_token.sequence_number,
                'prior_revoked_reason', v_old_token.revoked_reason
            )
        );

        RETURN NULL;
    END IF;

    -- Token Expiration Check
    IF v_old_token.expires_at <= CURRENT_TIMESTAMP THEN
        UPDATE staff_refresh_tokens
        SET is_revoked = TRUE,
            revoked_at = CURRENT_TIMESTAMP,
            revoked_reason = 'EXPIRED'
        WHERE id = v_old_token.id;

        RETURN NULL;
    END IF;

    -- Atomic Rotation: Invalidate old token with reason ROTATED
    UPDATE staff_refresh_tokens
    SET is_revoked = TRUE,
        revoked_at = CURRENT_TIMESTAMP,
        revoked_reason = 'ROTATED',
        last_used_at = CURRENT_TIMESTAMP
    WHERE id = v_old_token.id;

    -- Issue successor token into same family with incremented sequence number
    INSERT INTO staff_refresh_tokens (
        staff_user_id,
        token_hash,
        token_family,
        sequence_number,
        expires_at,
        user_agent,
        ip_address
    ) VALUES (
        v_old_token.staff_user_id,
        p_new_token_hash,
        v_old_token.token_family,
        v_old_token.sequence_number + 1,
        p_expires_at,
        p_user_agent,
        p_ip_address
    ) RETURNING id INTO v_new_token_id;

    RETURN v_new_token_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- =============================================================================
-- 5. PERFORMANCE & ROTATION INTEGRITY INDEXES
-- =============================================================================
CREATE INDEX IF NOT EXISTS idx_staff_refresh_tokens_family_seq
    ON staff_refresh_tokens(token_family, sequence_number);

CREATE INDEX IF NOT EXISTS idx_staff_refresh_tokens_active_user
    ON staff_refresh_tokens(staff_user_id) WHERE is_revoked = FALSE;

-- =============================================================================
-- 6. STRICT PRIVILEGE REVOCATION ON staff_refresh_tokens FROM pms_app & PUBLIC
-- =============================================================================
-- CRITICAL SECURITY HARDENING: staff_refresh_tokens is EXCLUSIVELY managed by
-- pms_auth. All direct privileges (SELECT, INSERT, UPDATE, DELETE, TRUNCATE)
-- are completely revoked from pms_app, pms_finance_admin, and PUBLIC.
REVOKE ALL ON staff_refresh_tokens FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_app') THEN
        REVOKE ALL ON staff_refresh_tokens FROM pms_app;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_finance_admin') THEN
        REVOKE ALL ON staff_refresh_tokens FROM pms_finance_admin;
    END IF;
END $$;
