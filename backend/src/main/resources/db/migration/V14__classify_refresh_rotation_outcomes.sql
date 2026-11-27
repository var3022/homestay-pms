-- =============================================================================
-- Migration: V14__classify_refresh_rotation_outcomes.sql
-- Description: Structured Machine-Readable Refresh Token Rotation Outcomes,
--              Concurrency Grace Window Classification, and Transactional Safety.
-- Owner: pms_admin (Schema Owner / Migration Runner)
-- =============================================================================

-- =============================================================================
-- 1. STRUCTURED REFRESH TOKEN ROTATION ROUTINE (fn_rotate_refresh_token_v2)
-- =============================================================================
--
-- TRUST BOUNDARY & TRANSACTIONAL SAFETY:
-- 1. Explicit Machine-Readable Outcomes:
--    - 'SUCCESS': Predecessor revoked as ROTATED; successor created; new_token_id returned.
--    - 'CONCURRENCY_COLLISION': Predecessor was revoked as ROTATED within 10-second grace window.
--      Does NOT revoke family. Does NOT create new token. Does NOT emit replay alert.
--    - 'REPLAY_DETECTED': Predecessor revoked prior to grace window or with reason != ROTATED.
--      Revokes entire token family, commits security audit event, returns REPLAY_DETECTED.
--    - 'TOKEN_EXPIRED': Predecessor was active but past expires_at. Revokes token as EXPIRED.
--    - 'ACCOUNT_DEACTIVATED': Target staff user is inactive. Revokes token family.
--    - 'ACCOUNT_LOCKED': Target staff user is currently locked out. Revokes family, emits audit.
--    - 'INVALID_TOKEN': Presented token hash does not exist or has invalid format.
--
-- 2. Transactional Commit Invariant:
--    Normal and malicious authentication rejections MUST NOT raise unhandled SQL exceptions.
--    Uncaught exceptions roll back family revocations and audit logs.
--    All outcomes commit security state cleanly and return structured metadata.
-- =============================================================================

CREATE OR REPLACE FUNCTION fn_rotate_refresh_token_v2(
    p_old_token_hash VARCHAR,
    p_new_token_hash VARCHAR,
    p_expires_at TIMESTAMPTZ,
    p_user_agent VARCHAR DEFAULT NULL,
    p_ip_address VARCHAR DEFAULT NULL
) RETURNS TABLE (
    outcome VARCHAR(30),
    new_token_id UUID,
    staff_user_id UUID,
    token_family UUID,
    sequence_number INTEGER,
    expires_at TIMESTAMPTZ
) AS $$
DECLARE
    v_old_token RECORD;
    v_new_token_id UUID;
    v_user_email VARCHAR;
    v_user_active BOOLEAN;
    v_user_locked BOOLEAN;
    v_user_lockout_until TIMESTAMPTZ;
BEGIN
    -- Privilege gate: Caller must be authenticated auth role, admin, or postgres
    IF SESSION_USER NOT IN ('pms_admin', 'pms_auth', 'postgres') THEN
        RAISE EXCEPTION 'Security privilege violation: Refresh token rotation requires pms_admin or pms_auth database credentials (caller: %)', SESSION_USER;
    END IF;

    -- Cryptographic token hash format check
    IF p_old_token_hash IS NULL OR p_new_token_hash IS NULL OR
       length(p_old_token_hash) <> 64 OR length(p_new_token_hash) <> 64 THEN
        RETURN QUERY SELECT
            'INVALID_TOKEN'::VARCHAR(30),
            NULL::UUID,
            NULL::UUID,
            NULL::UUID,
            0::INTEGER,
            NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    -- Successor expiration must be in the future
    IF p_expires_at IS NULL OR p_expires_at <= CURRENT_TIMESTAMP THEN
        RAISE EXCEPTION 'Token expiry violation: Expiration timestamp must be in the future';
    END IF;

    -- Concurrency control: Row-lock the presented refresh token
    SELECT * INTO v_old_token
    FROM staff_refresh_tokens
    WHERE token_hash = p_old_token_hash
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN QUERY SELECT
            'INVALID_TOKEN'::VARCHAR(30),
            NULL::UUID,
            NULL::UUID,
            NULL::UUID,
            0::INTEGER,
            NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    -- Target staff user active and lockout validation
    SELECT email, is_active, is_locked, lockout_until
    INTO v_user_email, v_user_active, v_user_locked, v_user_lockout_until
    FROM staff_users
    WHERE id = v_old_token.staff_user_id
    FOR UPDATE;

    IF NOT FOUND OR NOT v_user_active THEN
        PERFORM fn_revoke_refresh_token_family(v_old_token.token_family, 'ACCOUNT_DEACTIVATED', 'system:auth');
        RETURN QUERY SELECT
            'ACCOUNT_DEACTIVATED'::VARCHAR(30),
            NULL::UUID,
            v_old_token.staff_user_id,
            v_old_token.token_family,
            v_old_token.sequence_number,
            NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    IF v_user_locked AND (v_user_lockout_until IS NULL OR v_user_lockout_until > CURRENT_TIMESTAMP) THEN
        PERFORM fn_revoke_refresh_token_family(v_old_token.token_family, 'ACCOUNT_LOCKED', 'system:auth');
        INSERT INTO staff_security_audit_events (
            event_type, staff_user_id, email, actor_id, ip_address, user_agent, details
        ) VALUES (
            'REFRESH_REJECTED_LOCKED_ACCOUNT',
            v_old_token.staff_user_id,
            v_user_email,
            'system:auth',
            p_ip_address,
            p_user_agent,
            jsonb_build_object('reason', 'ACCOUNT_LOCKED', 'lockout_until', v_user_lockout_until)
        );
        RETURN QUERY SELECT
            'ACCOUNT_LOCKED'::VARCHAR(30),
            NULL::UUID,
            v_old_token.staff_user_id,
            v_old_token.token_family,
            v_old_token.sequence_number,
            NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    -- CRITICAL REPLAY DETECTION & SIMULTANEOUS ROTATION HANDLING:
    IF v_old_token.is_revoked THEN
        IF v_old_token.revoked_reason = 'ROTATED'
           AND v_old_token.revoked_at >= CURRENT_TIMESTAMP - INTERVAL '10 seconds' THEN
            -- Benign Concurrency Collision: Another tab/thread rotated this token within 10 seconds.
            -- Reject without invalidating family, allowing active successor to remain valid.
            RETURN QUERY SELECT
                'CONCURRENCY_COLLISION'::VARCHAR(30),
                NULL::UUID,
                v_old_token.staff_user_id,
                v_old_token.token_family,
                v_old_token.sequence_number,
                NULL::TIMESTAMPTZ;
            RETURN;
        END IF;

        -- True Replay Attack (token revoked outside grace window or revoked for reason other than ROTATED)
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
                'prior_revoked_reason', v_old_token.revoked_reason,
                'prior_revoked_at', v_old_token.revoked_at
            )
        );

        RETURN QUERY SELECT
            'REPLAY_DETECTED'::VARCHAR(30),
            NULL::UUID,
            v_old_token.staff_user_id,
            v_old_token.token_family,
            v_old_token.sequence_number,
            NULL::TIMESTAMPTZ;
        RETURN;
    END IF;

    -- Token Expiration Check
    IF v_old_token.expires_at <= CURRENT_TIMESTAMP THEN
        UPDATE staff_refresh_tokens
        SET is_revoked = TRUE,
            revoked_at = CURRENT_TIMESTAMP,
            revoked_reason = 'EXPIRED'
        WHERE id = v_old_token.id;

        RETURN QUERY SELECT
            'TOKEN_EXPIRED'::VARCHAR(30),
            NULL::UUID,
            v_old_token.staff_user_id,
            v_old_token.token_family,
            v_old_token.sequence_number,
            NULL::TIMESTAMPTZ;
        RETURN;
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

    RETURN QUERY SELECT
        'SUCCESS'::VARCHAR(30),
        v_new_token_id,
        v_old_token.staff_user_id,
        v_old_token.token_family,
        (v_old_token.sequence_number + 1)::INTEGER,
        p_expires_at;
    RETURN;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- =============================================================================
-- 2. BACKWARDS-COMPATIBLE WRAPPER (fn_rotate_refresh_token)
-- =============================================================================
-- Preserves compatibility with existing V9 callers and integration test assertions
-- expecting a UUID return type and legacy exception semantics for non-existent tokens.
-- =============================================================================

CREATE OR REPLACE FUNCTION fn_rotate_refresh_token(
    p_old_token_hash VARCHAR,
    p_new_token_hash VARCHAR,
    p_expires_at TIMESTAMPTZ,
    p_user_agent VARCHAR DEFAULT NULL,
    p_ip_address VARCHAR DEFAULT NULL
) RETURNS UUID AS $$
DECLARE
    v_res RECORD;
BEGIN
    SELECT * INTO v_res FROM fn_rotate_refresh_token_v2(
        p_old_token_hash, p_new_token_hash, p_expires_at, p_user_agent, p_ip_address
    );

    IF v_res.outcome = 'INVALID_TOKEN' THEN
        RAISE EXCEPTION 'Token rotation violation: Presented refresh token does not exist';
    END IF;

    RETURN v_res.new_token_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- =============================================================================
-- 3. LEAST-PRIVILEGE ACCESS GRANTS AND PRIVILEGE HARDENING
-- =============================================================================
REVOKE ALL ON FUNCTION fn_rotate_refresh_token_v2(VARCHAR, VARCHAR, TIMESTAMPTZ, VARCHAR, VARCHAR) FROM PUBLIC;

-- Ensure privileged staff administration routines from V8 are locked down from PUBLIC
REVOKE ALL ON FUNCTION fn_assign_staff_global_role(UUID, VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_revoke_staff_global_role(UUID, VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_assign_staff_property_role(UUID, UUID, VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_revoke_staff_property_role(UUID, UUID, VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_admin_reset_staff_password(UUID, VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_set_staff_active_status(UUID, BOOLEAN, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_staff_self_update_password(UUID, VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_update_staff_password(UUID, VARCHAR, VARCHAR) FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_app') THEN
        REVOKE ALL ON staff_refresh_tokens FROM pms_app;
        REVOKE ALL ON staff_security_audit_events FROM pms_app;
        REVOKE ALL ON FUNCTION fn_rotate_refresh_token_v2(VARCHAR, VARCHAR, TIMESTAMPTZ, VARCHAR, VARCHAR) FROM pms_app;
        REVOKE ALL ON FUNCTION fn_assign_staff_global_role(UUID, VARCHAR, VARCHAR) FROM pms_app;
        REVOKE ALL ON FUNCTION fn_revoke_staff_global_role(UUID, VARCHAR, VARCHAR) FROM pms_app;
        REVOKE ALL ON FUNCTION fn_assign_staff_property_role(UUID, UUID, VARCHAR, VARCHAR) FROM pms_app;
        REVOKE ALL ON FUNCTION fn_revoke_staff_property_role(UUID, UUID, VARCHAR, VARCHAR) FROM pms_app;
        REVOKE ALL ON FUNCTION fn_admin_reset_staff_password(UUID, VARCHAR, VARCHAR) FROM pms_app;
        REVOKE ALL ON FUNCTION fn_set_staff_active_status(UUID, BOOLEAN, VARCHAR) FROM pms_app;
        REVOKE ALL ON FUNCTION fn_staff_self_update_password(UUID, VARCHAR, VARCHAR) FROM pms_app;
        REVOKE ALL ON FUNCTION fn_update_staff_password(UUID, VARCHAR, VARCHAR) FROM pms_app;
        REVOKE ALL ON FUNCTION fn_record_staff_login_attempt(VARCHAR, BOOLEAN, VARCHAR, VARCHAR) FROM pms_app;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_finance_admin') THEN
        REVOKE ALL ON staff_refresh_tokens FROM pms_finance_admin;
        REVOKE ALL ON staff_users FROM pms_finance_admin;
        REVOKE ALL ON staff_global_roles FROM pms_finance_admin;
        REVOKE ALL ON staff_property_roles FROM pms_finance_admin;
        REVOKE ALL ON staff_security_audit_events FROM pms_finance_admin;
        REVOKE ALL ON FUNCTION fn_rotate_refresh_token_v2(VARCHAR, VARCHAR, TIMESTAMPTZ, VARCHAR, VARCHAR) FROM pms_finance_admin;
        REVOKE ALL ON FUNCTION fn_assign_staff_global_role(UUID, VARCHAR, VARCHAR) FROM pms_finance_admin;
        REVOKE ALL ON FUNCTION fn_revoke_staff_global_role(UUID, VARCHAR, VARCHAR) FROM pms_finance_admin;
        REVOKE ALL ON FUNCTION fn_assign_staff_property_role(UUID, UUID, VARCHAR, VARCHAR) FROM pms_finance_admin;
        REVOKE ALL ON FUNCTION fn_revoke_staff_property_role(UUID, UUID, VARCHAR, VARCHAR) FROM pms_finance_admin;
        REVOKE ALL ON FUNCTION fn_admin_reset_staff_password(UUID, VARCHAR, VARCHAR) FROM pms_finance_admin;
        REVOKE ALL ON FUNCTION fn_set_staff_active_status(UUID, BOOLEAN, VARCHAR) FROM pms_finance_admin;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_auth') THEN
        GRANT EXECUTE ON FUNCTION fn_rotate_refresh_token_v2(VARCHAR, VARCHAR, TIMESTAMPTZ, VARCHAR, VARCHAR) TO pms_auth;
        REVOKE EXECUTE ON FUNCTION fn_assign_staff_global_role(UUID, VARCHAR, VARCHAR) FROM pms_auth;
        REVOKE EXECUTE ON FUNCTION fn_revoke_staff_global_role(UUID, VARCHAR, VARCHAR) FROM pms_auth;
        REVOKE EXECUTE ON FUNCTION fn_assign_staff_property_role(UUID, UUID, VARCHAR, VARCHAR) FROM pms_auth;
        REVOKE EXECUTE ON FUNCTION fn_revoke_staff_property_role(UUID, UUID, VARCHAR, VARCHAR) FROM pms_auth;
        REVOKE EXECUTE ON FUNCTION fn_admin_reset_staff_password(UUID, VARCHAR, VARCHAR) FROM pms_auth;
        REVOKE EXECUTE ON FUNCTION fn_set_staff_active_status(UUID, BOOLEAN, VARCHAR) FROM pms_auth;
        REVOKE EXECUTE ON FUNCTION fn_staff_self_update_password(UUID, VARCHAR, VARCHAR) FROM pms_auth;
        REVOKE EXECUTE ON FUNCTION fn_update_staff_password(UUID, VARCHAR, VARCHAR) FROM pms_auth;
    END IF;
END $$;
