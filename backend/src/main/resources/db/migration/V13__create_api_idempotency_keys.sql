-- =============================================================================
-- Migration: V13__create_api_idempotency_keys.sql
-- Description: Transactional API Idempotency Ledger for Staff Guest and Direct
--              Reservation Creation.
-- Owner: pms_admin (Schema Owner / Migration Runner)
-- =============================================================================

CREATE TABLE api_idempotency_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    operation VARCHAR(50) NOT NULL,
    principal_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    resource_id UUID NULL,
    response_status INTEGER NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_idempotency_scope UNIQUE (property_id, operation, principal_id, idempotency_key),
    CONSTRAINT chk_idempotency_operation CHECK (operation IN ('CREATE_GUEST', 'CREATE_RESERVATION')),
    CONSTRAINT chk_idempotency_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT chk_idempotency_expires CHECK (expires_at > created_at)
);

CREATE INDEX idx_idempotency_expires_at ON api_idempotency_keys (expires_at);
CREATE INDEX idx_idempotency_resource ON api_idempotency_keys (resource_id);

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'pms_app') THEN
        GRANT SELECT, INSERT, UPDATE ON api_idempotency_keys TO pms_app;
    END IF;
END
$$;
