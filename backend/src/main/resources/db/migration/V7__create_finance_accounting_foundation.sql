-- ============================================================================
-- V7: Finance and Accounting Foundation (Double-Entry General Ledger)
-- Compatibility: PostgreSQL 16+ / Flyway Clean DDL
-- Multi-Property Isolated, Double-Entry General Ledger with Strict Audit Trail
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ============================================================================
-- 1. PROPERTY FINANCIAL SETTINGS
-- ============================================================================
CREATE TABLE property_financial_settings (
    property_id UUID PRIMARY KEY,
    fiscal_year_start_month SMALLINT NOT NULL DEFAULT 4,
    fiscal_year_start_day SMALLINT NOT NULL DEFAULT 1,
    base_currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    accounting_method VARCHAR(20) NOT NULL DEFAULT 'ACCRUAL',
    is_tax_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    tax_id_number VARCHAR(50),
    books_locked_until_date DATE,
    auto_post_reservations BOOLEAN NOT NULL DEFAULT FALSE,
    lock_period_grace_days SMALLINT NOT NULL DEFAULT 5,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_financial_settings_property 
        FOREIGN KEY (property_id) REFERENCES properties(id) ON DELETE RESTRICT,
    CONSTRAINT chk_financial_settings_month 
        CHECK (fiscal_year_start_month BETWEEN 1 AND 12),
    CONSTRAINT chk_financial_settings_day 
        CHECK (fiscal_year_start_day BETWEEN 1 AND 31),
    CONSTRAINT chk_financial_settings_method 
        CHECK (accounting_method IN ('ACCRUAL', 'CASH'))
);

-- ============================================================================
-- 2. CHART OF ACCOUNTS
-- ============================================================================
CREATE TABLE finance_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id UUID NOT NULL,
    account_code VARCHAR(50) NOT NULL,
    account_name VARCHAR(150) NOT NULL,
    account_type VARCHAR(20) NOT NULL,
    account_category VARCHAR(50) NOT NULL,
    normal_balance VARCHAR(6) NOT NULL,
    is_contra BOOLEAN NOT NULL DEFAULT FALSE,
    parent_account_id UUID,
    standard_mapping_code VARCHAR(50),
    description TEXT,
    is_allow_posting BOOLEAN NOT NULL DEFAULT TRUE,
    is_system_account BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_finance_accounts_property 
        FOREIGN KEY (property_id) REFERENCES properties(id) ON DELETE RESTRICT,
    CONSTRAINT uq_finance_accounts_property_code 
        UNIQUE (property_id, account_code),
    CONSTRAINT uq_finance_accounts_property_id 
        UNIQUE (property_id, id),
    CONSTRAINT fk_finance_accounts_parent 
        FOREIGN KEY (property_id, parent_account_id) REFERENCES finance_accounts(property_id, id) ON DELETE RESTRICT,
    CONSTRAINT chk_finance_accounts_type 
        CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    CONSTRAINT chk_finance_accounts_balance 
        CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_finance_accounts_no_self_parent 
        CHECK (parent_account_id IS NULL OR parent_account_id <> id)
);

CREATE INDEX idx_finance_accounts_type ON finance_accounts(property_id, account_type, is_active);
CREATE INDEX idx_finance_accounts_mapping ON finance_accounts(standard_mapping_code);

-- ============================================================================
-- 3. ACCOUNTING PERIODS
-- ============================================================================
CREATE TABLE finance_accounting_periods (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id UUID NOT NULL,
    period_name VARCHAR(100) NOT NULL,
    fiscal_year INTEGER NOT NULL,
    period_number SMALLINT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    closed_at TIMESTAMPTZ,
    closed_by VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_finance_periods_property 
        FOREIGN KEY (property_id) REFERENCES properties(id) ON DELETE RESTRICT,
    CONSTRAINT uq_finance_periods_property_id 
        UNIQUE (property_id, id),
    CONSTRAINT uq_finance_periods_property_fiscal 
        UNIQUE (property_id, fiscal_year, period_number),
    CONSTRAINT chk_finance_periods_dates 
        CHECK (start_date <= end_date),
    CONSTRAINT chk_finance_periods_status 
        CHECK (status IN ('OPEN', 'CLOSED', 'LOCKED')),
    CONSTRAINT chk_finance_periods_closure 
        CHECK ((status = 'OPEN' AND closed_at IS NULL AND closed_by IS NULL) OR 
               (status IN ('CLOSED', 'LOCKED') AND closed_at IS NOT NULL AND closed_by IS NOT NULL)),
    CONSTRAINT uq_finance_periods_no_overlap 
        EXCLUDE USING gist (
            property_id WITH =,
            daterange(start_date, end_date, '[]') WITH &&
        )
);

CREATE INDEX idx_finance_periods_dates ON finance_accounting_periods(property_id, start_date, end_date);
CREATE INDEX idx_finance_periods_status ON finance_accounting_periods(property_id, status);

-- Accounting Period Immutability & Transition Enforcement Trigger
CREATE OR REPLACE FUNCTION fn_enforce_accounting_period_immutability()
RETURNS TRIGGER 
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('CLOSED', 'LOCKED') THEN
            RAISE EXCEPTION 'Financial integrity violation: Cannot delete % accounting period %', OLD.status, OLD.period_name;
        END IF;

        IF EXISTS (SELECT 1 FROM finance_journal_entries WHERE accounting_period_id = OLD.id) THEN
            RAISE EXCEPTION 'Financial integrity violation: Cannot delete accounting period % because journal entries reference it', OLD.period_name;
        END IF;

        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        -- Date boundaries, property, and fiscal identifiers are permanently immutable
        IF NEW.id <> OLD.id THEN
            RAISE EXCEPTION 'Financial integrity violation: Cannot mutate accounting period id';
        END IF;

        IF NEW.property_id <> OLD.property_id THEN
            RAISE EXCEPTION 'Financial integrity violation: Cannot mutate property_id of accounting period';
        END IF;

        IF NEW.fiscal_year <> OLD.fiscal_year OR NEW.period_number <> OLD.period_number THEN
            RAISE EXCEPTION 'Financial integrity violation: Cannot mutate fiscal year or period number of accounting period';
        END IF;

        IF NEW.start_date <> OLD.start_date OR NEW.end_date <> OLD.end_date THEN
            RAISE EXCEPTION 'Financial integrity violation: Cannot alter date boundaries (start_date/end_date) of accounting period %', OLD.period_name;
        END IF;

        -- State machine transitions
        IF OLD.status = 'OPEN' THEN
            IF NEW.status = 'CLOSED' THEN
                IF NEW.closed_at IS NULL OR NEW.closed_by IS NULL THEN
                    RAISE EXCEPTION 'Financial integrity violation: Closing period % requires closed_at and closed_by metadata', OLD.period_name;
                END IF;

                -- Chronological closure enforcement: No earlier accounting period for this property may remain OPEN
                IF EXISTS (
                    SELECT 1 
                    FROM finance_accounting_periods
                    WHERE property_id = OLD.property_id
                      AND end_date < OLD.start_date
                      AND status = 'OPEN'
                ) THEN
                    RAISE EXCEPTION 'Financial integrity violation: Cannot close accounting period % (%) because earlier accounting period(s) for this property remain OPEN. Periods must be closed in chronological order.',
                        OLD.period_name, OLD.start_date;
                END IF;
            ELSIF NEW.status <> 'OPEN' THEN
                RAISE EXCEPTION 'Financial integrity violation: Invalid status transition from OPEN to % for period %', NEW.status, OLD.period_name;
            END IF;
            RETURN NEW;
        END IF;

        IF OLD.status = 'CLOSED' THEN
            IF NEW.status = 'LOCKED' THEN
                RETURN NEW;
            ELSIF NEW.status = 'OPEN' THEN
                RAISE EXCEPTION 'Financial integrity violation: Cannot reopen CLOSED accounting period %. Closed periods are sealed.', OLD.period_name;
            ELSIF NEW.status <> 'CLOSED' THEN
                RAISE EXCEPTION 'Financial integrity violation: Invalid status transition from CLOSED to % for period %', NEW.status, OLD.period_name;
            END IF;
            RETURN NEW;
        END IF;

        IF OLD.status = 'LOCKED' THEN
            RAISE EXCEPTION 'Financial integrity violation: LOCKED accounting period % is permanently sealed against all updates.', OLD.period_name;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_finance_accounting_periods_immutable
BEFORE UPDATE OR DELETE ON finance_accounting_periods
FOR EACH ROW
EXECUTE FUNCTION fn_enforce_accounting_period_immutability();

-- ============================================================================
-- 4. JOURNAL ENTRIES
-- ============================================================================
CREATE TABLE finance_journal_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id UUID NOT NULL,
    accounting_period_id UUID NOT NULL,
    entry_number VARCHAR(50) NOT NULL,
    transaction_date DATE NOT NULL,
    accounting_date DATE NOT NULL,
    entry_type VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    description VARCHAR(255) NOT NULL,
    reference VARCHAR(100),
    source_type VARCHAR(50),
    source_id UUID,
    idempotency_key VARCHAR(100),
    total_amount NUMERIC(18, 2) NOT NULL DEFAULT 0.00,
    reversal_of_entry_id UUID,
    reversed_by_entry_id UUID,
    reversed_at TIMESTAMPTZ,
    posted_at TIMESTAMPTZ,
    posted_by VARCHAR(100),
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_journal_entries_property 
        FOREIGN KEY (property_id) REFERENCES properties(id) ON DELETE RESTRICT,
    CONSTRAINT uq_journal_entries_property_id 
        UNIQUE (property_id, id),
    CONSTRAINT uq_journal_entries_property_number 
        UNIQUE (property_id, entry_number),
    CONSTRAINT fk_journal_entries_period 
        FOREIGN KEY (property_id, accounting_period_id) REFERENCES finance_accounting_periods(property_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_journal_entries_reversal_of 
        FOREIGN KEY (property_id, reversal_of_entry_id) REFERENCES finance_journal_entries(property_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_journal_entries_reversed_by 
        FOREIGN KEY (property_id, reversed_by_entry_id) REFERENCES finance_journal_entries(property_id, id) ON DELETE RESTRICT,
    CONSTRAINT chk_journal_entry_type 
        CHECK (entry_type IN ('STANDARD', 'ADJUSTMENT', 'CLOSING', 'REVERSAL', 'OPENING')),
    CONSTRAINT chk_journal_entry_status 
        CHECK (status IN ('DRAFT', 'POSTED', 'REVERSED')),
    CONSTRAINT chk_journal_entry_amounts 
        CHECK (total_amount >= 0.00),
    CONSTRAINT chk_journal_entry_reversal_self 
        CHECK (reversed_by_entry_id <> id AND reversal_of_entry_id <> id)
);

CREATE UNIQUE INDEX uq_journal_entries_idempotency 
    ON finance_journal_entries(property_id, idempotency_key) 
    WHERE idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX uq_journal_entries_reversal_of 
    ON finance_journal_entries(reversal_of_entry_id) 
    WHERE reversal_of_entry_id IS NOT NULL;

CREATE UNIQUE INDEX uq_journal_entries_reversed_by 
    ON finance_journal_entries(reversed_by_entry_id) 
    WHERE reversed_by_entry_id IS NOT NULL;

CREATE INDEX idx_journal_entries_source 
    ON finance_journal_entries(property_id, source_type, source_id);

CREATE INDEX idx_journal_entries_period_status 
    ON finance_journal_entries(accounting_period_id, status);

CREATE INDEX idx_journal_entries_dates 
    ON finance_journal_entries(property_id, accounting_date, transaction_date);

-- ============================================================================
-- 5. JOURNAL LINES
-- ============================================================================
-- Ensure reservations supports composite foreign key for property isolation
ALTER TABLE reservations ADD CONSTRAINT uq_reservations_property_id UNIQUE (property_id, id);

CREATE TABLE finance_journal_lines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id UUID NOT NULL,
    journal_entry_id UUID NOT NULL,
    account_id UUID NOT NULL,
    line_number SMALLINT NOT NULL,
    debit_amount NUMERIC(18, 2) NOT NULL DEFAULT 0.00,
    credit_amount NUMERIC(18, 2) NOT NULL DEFAULT 0.00,
    description VARCHAR(255),
    reservation_id UUID,
    guest_id UUID,
    entity_type VARCHAR(50),
    entity_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_journal_lines_property 
        FOREIGN KEY (property_id) REFERENCES properties(id) ON DELETE RESTRICT,
    CONSTRAINT uq_journal_lines_entry_line 
        UNIQUE (journal_entry_id, line_number),
    CONSTRAINT fk_journal_lines_entry 
        FOREIGN KEY (property_id, journal_entry_id) REFERENCES finance_journal_entries(property_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_journal_lines_account 
        FOREIGN KEY (property_id, account_id) REFERENCES finance_accounts(property_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_journal_lines_guest 
        FOREIGN KEY (guest_id) REFERENCES guests(id) ON DELETE RESTRICT,
    CONSTRAINT fk_journal_lines_reservation 
        FOREIGN KEY (property_id, reservation_id) REFERENCES reservations(property_id, id) ON DELETE RESTRICT,
    CONSTRAINT chk_journal_lines_non_negative 
        CHECK (debit_amount >= 0.00 AND credit_amount >= 0.00),
    CONSTRAINT chk_journal_lines_exclusive_amounts 
        CHECK ((debit_amount > 0.00 AND credit_amount = 0.00) OR (credit_amount > 0.00 AND debit_amount = 0.00))
);

CREATE INDEX idx_journal_lines_account_entry 
    ON finance_journal_lines(property_id, account_id, journal_entry_id);

CREATE INDEX idx_journal_lines_reservation 
    ON finance_journal_lines(property_id, reservation_id);

CREATE INDEX idx_journal_lines_guest 
    ON finance_journal_lines(guest_id);

CREATE INDEX idx_journal_lines_entity 
    ON finance_journal_lines(property_id, entity_type, entity_id);

-- Enforce guest-reservation association according to V1-V6 schema
CREATE OR REPLACE FUNCTION fn_validate_journal_line_guest_reservation()
RETURNS TRIGGER 
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_primary_guest UUID;
    v_is_assoc BOOLEAN;
BEGIN
    IF NEW.reservation_id IS NOT NULL AND NEW.guest_id IS NOT NULL THEN
        SELECT primary_guest_id INTO v_primary_guest
        FROM reservations
        WHERE id = NEW.reservation_id AND property_id = NEW.property_id;

        IF v_primary_guest = NEW.guest_id THEN
            RETURN NEW;
        END IF;

        SELECT EXISTS (
            SELECT 1 FROM reservation_guests
            WHERE reservation_id = NEW.reservation_id AND guest_id = NEW.guest_id
        ) INTO v_is_assoc;

        IF NOT v_is_assoc THEN
            RAISE EXCEPTION 'Financial integrity violation: Guest % is not associated with reservation %', NEW.guest_id, NEW.reservation_id;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_validate_journal_line_guest_reservation
BEFORE INSERT OR UPDATE ON finance_journal_lines
FOR EACH ROW
EXECUTE FUNCTION fn_validate_journal_line_guest_reservation();

-- ============================================================================
-- 6. AUDIT TRAIL
-- ============================================================================
CREATE TABLE finance_audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id UUID NOT NULL,
    payload JSONB NOT NULL,
    performed_by VARCHAR(100) NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_finance_audit_property 
        FOREIGN KEY (property_id) REFERENCES properties(id) ON DELETE RESTRICT
);

CREATE INDEX idx_finance_audit_aggregate 
    ON finance_audit_events(aggregate_type, aggregate_id, performed_at);

CREATE INDEX idx_finance_audit_property 
    ON finance_audit_events(property_id, performed_at);

-- ============================================================================
-- 7. INTEGRITY & IMMUTABILITY TRIGGERS
-- ============================================================================

-- Account Hierarchy Cycle Prevention (Serialized via Property Settings Level 1 Lock)
CREATE OR REPLACE FUNCTION fn_prevent_account_hierarchy_cycle()
RETURNS TRIGGER 
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_cur_parent UUID;
    v_depth INT := 0;
BEGIN
    IF NEW.parent_account_id IS NULL THEN
        RETURN NEW;
    END IF;

    IF NEW.parent_account_id = NEW.id THEN
        RAISE EXCEPTION 'Financial integrity violation: Account % cannot be its own parent', NEW.account_code;
    END IF;

    -- Property-Level Serialization: Lock the property financial settings FOR UPDATE (Level 1)
    -- This guarantees that concurrent reparenting transactions on the same property are strictly
    -- serialized, preventing concurrent cycle creation races where A->B and B->A pass independently.
    PERFORM 1
    FROM property_financial_settings
    WHERE property_id = NEW.property_id
    FOR UPDATE;

    -- Walk the hierarchy up to root with cycle detection
    v_cur_parent := NEW.parent_account_id;
    WHILE v_cur_parent IS NOT NULL LOOP
        v_depth := v_depth + 1;
        IF v_depth > 50 THEN
            RAISE EXCEPTION 'Financial integrity violation: Account hierarchy loop or excessive depth detected at parent %', v_cur_parent;
        END IF;

        IF v_cur_parent = NEW.id THEN
            RAISE EXCEPTION 'Financial integrity violation: Circular parent-child relationship detected for account %', NEW.account_code;
        END IF;

        SELECT parent_account_id INTO v_cur_parent
        FROM finance_accounts
        WHERE id = v_cur_parent AND property_id = NEW.property_id;
    END LOOP;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_finance_accounts_cycle_check
BEFORE INSERT OR UPDATE OF parent_account_id ON finance_accounts
FOR EACH ROW
EXECUTE FUNCTION fn_prevent_account_hierarchy_cycle();

-- Audit Events Append-Only Enforcement
CREATE OR REPLACE FUNCTION fn_prevent_audit_event_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Financial integrity violation: Audit events are append-only and cannot be updated or deleted. (Event ID: %)', OLD.id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_finance_audit_events_immutable
BEFORE UPDATE OR DELETE ON finance_audit_events
FOR EACH ROW
EXECUTE FUNCTION fn_prevent_audit_event_mutation();

-- Journal Entry Draft Validation Trigger (INSERT and UPDATE)
-- Uses standard MVCC snapshot reads (no FOR SHARE) to avoid reverse-order deadlocks against Level 1/2
CREATE OR REPLACE FUNCTION fn_validate_journal_entry_draft()
RETURNS TRIGGER 
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_period_status VARCHAR(20);
    v_period_start DATE;
    v_period_end DATE;
    v_period_property_id UUID;
    v_locked_date DATE;
    v_base_currency VARCHAR(3);
BEGIN
    -- Level 1: Lock property financial settings FOR SHARE
    SELECT base_currency, books_locked_until_date 
    INTO v_base_currency, v_locked_date
    FROM property_financial_settings
    WHERE property_id = NEW.property_id
    FOR SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Financial integrity violation: Financial settings not found for property %', NEW.property_id;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Financial integrity violation: New journal entries must be created in DRAFT status (attempted: %)', NEW.status;
        END IF;

        IF NEW.total_amount <> 0.00 THEN
            RAISE EXCEPTION 'Financial integrity violation: New draft entries must have total_amount 0.00 (attempted: %)', NEW.total_amount;
        END IF;

        IF NEW.posted_at IS NOT NULL OR NEW.posted_by IS NOT NULL THEN
            RAISE EXCEPTION 'Financial integrity violation: New draft entries cannot have posted timestamps or users set';
        END IF;

        IF NEW.reversed_at IS NOT NULL OR NEW.reversed_by_entry_id IS NOT NULL THEN
            RAISE EXCEPTION 'Financial integrity violation: New draft entries cannot have reversal metadata set';
        END IF;

        -- Currency check: Multi-currency is not supported in V7
        IF NEW.currency <> v_base_currency THEN
            RAISE EXCEPTION 'Financial integrity violation: Multi-currency entries are not supported in V7. Entry currency (%) must match property base currency (%)',
                NEW.currency, v_base_currency;
        END IF;

        -- Period validation
        SELECT status, start_date, end_date, property_id
        INTO v_period_status, v_period_start, v_period_end, v_period_property_id
        FROM finance_accounting_periods
        WHERE id = NEW.accounting_period_id
        FOR SHARE;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Financial integrity violation: Accounting period % does not exist', NEW.accounting_period_id;
        END IF;

        IF v_period_property_id <> NEW.property_id THEN
            RAISE EXCEPTION 'Financial integrity violation: Accounting period % belongs to property %; entry belongs to %',
                NEW.accounting_period_id, v_period_property_id, NEW.property_id;
        END IF;

        IF v_period_status <> 'OPEN' THEN
            RAISE EXCEPTION 'Financial integrity violation: Cannot create journal entry in period with status % (must be OPEN)', v_period_status;
        END IF;

        IF NEW.accounting_date < v_period_start OR NEW.accounting_date > v_period_end THEN
            RAISE EXCEPTION 'Financial integrity violation: Accounting date % is outside period % (% to %)',
                NEW.accounting_date, NEW.accounting_period_id, v_period_start, v_period_end;
        END IF;

        IF v_locked_date IS NOT NULL AND NEW.accounting_date <= v_locked_date THEN
            RAISE EXCEPTION 'Financial integrity violation: Accounting date % is on or before property lock date %',
                NEW.accounting_date, v_locked_date;
        END IF;

        RETURN NEW;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF OLD.status = 'DRAFT' THEN
            -- Protected immutable attributes
            IF NEW.id <> OLD.id THEN
                RAISE EXCEPTION 'Financial integrity violation: Cannot mutate journal entry id';
            END IF;

            IF NEW.property_id <> OLD.property_id THEN
                RAISE EXCEPTION 'Financial integrity violation: Cannot mutate property_id of journal entry';
            END IF;

            IF NEW.entry_number <> OLD.entry_number THEN
                RAISE EXCEPTION 'Financial integrity violation: Cannot mutate entry_number of journal entry';
            END IF;

            IF NEW.created_by <> OLD.created_by OR NEW.created_at <> OLD.created_at THEN
                RAISE EXCEPTION 'Financial integrity violation: Cannot mutate creation metadata (created_by/created_at)';
            END IF;

            IF NEW.entry_type <> OLD.entry_type THEN
                RAISE EXCEPTION 'Financial integrity violation: Cannot mutate entry_type of journal entry';
            END IF;

            IF NEW.reversal_of_entry_id IS DISTINCT FROM OLD.reversal_of_entry_id THEN
                RAISE EXCEPTION 'Financial integrity violation: Cannot mutate reversal_of_entry_id of journal entry';
            END IF;

            IF NEW.reversed_by_entry_id IS NOT NULL OR NEW.reversed_at IS NOT NULL THEN
                RAISE EXCEPTION 'Financial integrity violation: Cannot set reversal metadata on a DRAFT entry';
            END IF;

            -- If remaining in DRAFT, validate mutable fields
            IF NEW.status = 'DRAFT' THEN
                -- Currency check
                IF NEW.currency <> v_base_currency THEN
                    RAISE EXCEPTION 'Financial integrity violation: Multi-currency entries are not supported in V7. Entry currency (%) must match property base currency (%)',
                        NEW.currency, v_base_currency;
                END IF;

                IF NEW.currency <> OLD.currency THEN
                    IF EXISTS (SELECT 1 FROM finance_journal_lines WHERE journal_entry_id = NEW.id) THEN
                        RAISE EXCEPTION 'Financial integrity violation: Cannot change currency of journal entry when lines already exist';
                    END IF;
                END IF;

                -- Date and period checks
                IF NEW.accounting_date <> OLD.accounting_date OR NEW.accounting_period_id <> OLD.accounting_period_id THEN
                    SELECT status, start_date, end_date, property_id
                    INTO v_period_status, v_period_start, v_period_end, v_period_property_id
                    FROM finance_accounting_periods
                    WHERE id = NEW.accounting_period_id
                    FOR SHARE;

                    IF NOT FOUND THEN
                        RAISE EXCEPTION 'Financial integrity violation: Accounting period % does not exist', NEW.accounting_period_id;
                    END IF;

                    IF v_period_property_id <> NEW.property_id THEN
                        RAISE EXCEPTION 'Financial integrity violation: Accounting period % belongs to property %; entry belongs to %',
                            NEW.accounting_period_id, v_period_property_id, NEW.property_id;
                    END IF;

                    IF v_period_status <> 'OPEN' THEN
                        RAISE EXCEPTION 'Financial integrity violation: Cannot update journal entry to period with status % (must be OPEN)', v_period_status;
                    END IF;

                    IF NEW.accounting_date < v_period_start OR NEW.accounting_date > v_period_end THEN
                        RAISE EXCEPTION 'Financial integrity violation: Accounting date % is outside period % (% to %)',
                            NEW.accounting_date, NEW.accounting_period_id, v_period_start, v_period_end;
                    END IF;

                    IF v_locked_date IS NOT NULL AND NEW.accounting_date <= v_locked_date THEN
                        RAISE EXCEPTION 'Financial integrity violation: Accounting date % is on or before property lock date %',
                            NEW.accounting_date, v_locked_date;
                    END IF;
                END IF;

                -- total_amount cannot be mutated directly while in DRAFT
                IF NEW.total_amount <> OLD.total_amount THEN
                    RAISE EXCEPTION 'Financial integrity violation: total_amount on DRAFT entries cannot be manually modified (computed on posting)';
                END IF;
            END IF;
        END IF;

        RETURN NEW;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_finance_journal_entries_draft_validate
BEFORE INSERT OR UPDATE ON finance_journal_entries
FOR EACH ROW
EXECUTE FUNCTION fn_validate_journal_entry_draft();

-- Journal Entry Lifecycle & Immutability (Enforces State Machine & Reversal Invariants)
CREATE OR REPLACE FUNCTION fn_enforce_journal_entry_immutability()
RETURNS TRIGGER 
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('POSTED', 'REVERSED') THEN
            RAISE EXCEPTION 'Financial integrity violation: Cannot delete % journal entry % (Number: %)', OLD.status, OLD.id, OLD.entry_number;
        END IF;

        -- DRAFT deletion is permitted at database trigger level.
        -- Application role access is restricted by REVOKE DELETE FROM pms_app,
        -- requiring use of fn_discard_draft_journal_entry().
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF OLD.status = 'DRAFT' THEN
            IF NEW.status = 'POSTED' THEN
                -- Transition to POSTED is permitted at database trigger level.
                -- Application role access is restricted by REVOKE UPDATE (status) FROM pms_app,
                -- requiring use of fn_post_journal_entry().
                RETURN NEW;
            ELSIF NEW.status <> 'DRAFT' THEN
                RAISE EXCEPTION 'Financial integrity violation: Invalid status transition from DRAFT to %', NEW.status;
            END IF;
            RETURN NEW;
        END IF;

        IF OLD.status = 'POSTED' THEN
            IF NEW.status = 'REVERSED'
               AND NEW.reversed_by_entry_id IS NOT NULL
               AND NEW.reversed_at IS NOT NULL
               AND NEW.id = OLD.id
               AND NEW.property_id = OLD.property_id
               AND NEW.accounting_period_id = OLD.accounting_period_id
               AND NEW.entry_number = OLD.entry_number
               AND NEW.transaction_date = OLD.transaction_date
               AND NEW.accounting_date = OLD.accounting_date
               AND NEW.entry_type = OLD.entry_type
               AND NEW.currency = OLD.currency
               AND NEW.description = OLD.description
               AND (NEW.reference IS NOT DISTINCT FROM OLD.reference)
               AND (NEW.source_type IS NOT DISTINCT FROM OLD.source_type)
               AND (NEW.source_id IS NOT DISTINCT FROM OLD.source_id)
               AND (NEW.idempotency_key IS NOT DISTINCT FROM OLD.idempotency_key)
               AND NEW.total_amount = OLD.total_amount
               AND NEW.created_by = OLD.created_by
               AND NEW.created_at = OLD.created_at
               AND NEW.posted_by = OLD.posted_by
               AND NEW.posted_at = OLD.posted_at
               AND (NEW.reversal_of_entry_id IS NOT DISTINCT FROM OLD.reversal_of_entry_id) THEN
                
                -- Valid transition to REVERSED (only fn_reverse_journal_entry can perform this)
                RETURN NEW;
            ELSE
                RAISE EXCEPTION 'Financial integrity violation: Posted journal entry % is immutable. Direct modification of financial facts is strictly prohibited.', OLD.entry_number;
            END IF;
        END IF;

        IF OLD.status = 'REVERSED' THEN
            RAISE EXCEPTION 'Financial integrity violation: Reversed journal entry % is permanently sealed.', OLD.entry_number;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_finance_journal_entries_immutable
BEFORE UPDATE OR DELETE ON finance_journal_entries
FOR EACH ROW
EXECUTE FUNCTION fn_enforce_journal_entry_immutability();

-- Journal Line Parent Lock Coordination & Entry Move Protection
CREATE OR REPLACE FUNCTION fn_coordinate_journal_line_with_parent()
RETURNS TRIGGER 
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_old_status VARCHAR(20);
    v_new_status VARCHAR(20);
BEGIN
    IF TG_OP = 'DELETE' THEN
        SELECT status INTO v_old_status
        FROM finance_journal_entries
        WHERE id = OLD.journal_entry_id
        FOR UPDATE;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Financial integrity violation: Parent journal entry % does not exist', OLD.journal_entry_id;
        END IF;

        IF v_old_status IN ('POSTED', 'REVERSED') THEN
            RAISE EXCEPTION 'Financial integrity violation: Cannot delete line from % journal entry %', v_old_status, OLD.journal_entry_id;
        END IF;

        RETURN OLD;
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT status INTO v_new_status
        FROM finance_journal_entries
        WHERE id = NEW.journal_entry_id
        FOR UPDATE;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Financial integrity violation: Parent journal entry % does not exist', NEW.journal_entry_id;
        END IF;

        IF v_new_status IN ('POSTED', 'REVERSED') THEN
            RAISE EXCEPTION 'Financial integrity violation: Cannot insert line into % journal entry %', v_new_status, NEW.journal_entry_id;
        END IF;

        RETURN NEW;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF OLD.id <> NEW.id THEN
            RAISE EXCEPTION 'Financial integrity violation: Cannot mutate journal line id';
        END IF;

        IF OLD.property_id <> NEW.property_id THEN
            RAISE EXCEPTION 'Financial integrity violation: Cannot mutate property_id of journal line';
        END IF;

        IF OLD.journal_entry_id <> NEW.journal_entry_id THEN
            IF OLD.journal_entry_id < NEW.journal_entry_id THEN
                SELECT status INTO v_old_status FROM finance_journal_entries WHERE id = OLD.journal_entry_id FOR UPDATE;
                SELECT status INTO v_new_status FROM finance_journal_entries WHERE id = NEW.journal_entry_id FOR UPDATE;
            ELSE
                SELECT status INTO v_new_status FROM finance_journal_entries WHERE id = NEW.journal_entry_id FOR UPDATE;
                SELECT status INTO v_old_status FROM finance_journal_entries WHERE id = OLD.journal_entry_id FOR UPDATE;
            END IF;

            IF v_old_status IN ('POSTED', 'REVERSED') THEN
                RAISE EXCEPTION 'Financial integrity violation: Cannot move line away from % journal entry %', v_old_status, OLD.journal_entry_id;
            END IF;

            IF v_new_status IN ('POSTED', 'REVERSED') THEN
                RAISE EXCEPTION 'Financial integrity violation: Cannot move line into % journal entry %', v_new_status, NEW.journal_entry_id;
            END IF;
        ELSE
            SELECT status INTO v_new_status
            FROM finance_journal_entries
            WHERE id = NEW.journal_entry_id
            FOR UPDATE;

            IF v_new_status IN ('POSTED', 'REVERSED') THEN
                RAISE EXCEPTION 'Financial integrity violation: Cannot update line on % journal entry %', v_new_status, NEW.journal_entry_id;
            END IF;
        END IF;

        RETURN NEW;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_finance_journal_lines_parent_lock
BEFORE INSERT OR UPDATE OR DELETE ON finance_journal_lines
FOR EACH ROW
EXECUTE FUNCTION fn_coordinate_journal_line_with_parent();

-- ============================================================================
-- 7b. PROPERTY FINANCIAL SETTINGS IMMUTABILITY TRIGGER
-- ============================================================================
CREATE OR REPLACE FUNCTION fn_enforce_property_financial_settings_immutability()
RETURNS TRIGGER 
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF EXISTS (SELECT 1 FROM finance_accounting_periods WHERE property_id = OLD.property_id)
           OR EXISTS (SELECT 1 FROM finance_journal_entries WHERE property_id = OLD.property_id) THEN
            RAISE EXCEPTION 'Financial integrity violation: Cannot delete property_financial_settings for property % because dependent financial records exist.', OLD.property_id;
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF NEW.property_id <> OLD.property_id THEN
            RAISE EXCEPTION 'Financial integrity violation: Cannot mutate property_id of property_financial_settings';
        END IF;

        -- Foundational Financial Settings Protection:
        -- base_currency, accounting_method, fiscal_year_start_month, fiscal_year_start_day
        -- Once accounting periods or journal activity exist, these foundational settings are permanently locked.
        IF (NEW.base_currency <> OLD.base_currency)
           OR (NEW.accounting_method <> OLD.accounting_method)
           OR (NEW.fiscal_year_start_month <> OLD.fiscal_year_start_month)
           OR (NEW.fiscal_year_start_day <> OLD.fiscal_year_start_day) THEN
           
            IF EXISTS (SELECT 1 FROM finance_accounting_periods WHERE property_id = OLD.property_id) THEN
                RAISE EXCEPTION 'Financial integrity violation: Cannot mutate foundational financial settings (base_currency, accounting_method, fiscal_year_start_month, fiscal_year_start_day) after accounting periods have been initialized for property %.',
                    OLD.property_id;
            END IF;

            IF EXISTS (SELECT 1 FROM finance_journal_entries WHERE property_id = OLD.property_id) THEN
                RAISE EXCEPTION 'Financial integrity violation: Cannot mutate foundational financial settings (base_currency, accounting_method, fiscal_year_start_month, fiscal_year_start_day) after journal activity exists for property %.',
                    OLD.property_id;
            END IF;
        END IF;

        RETURN NEW;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_property_financial_settings_immutability
BEFORE UPDATE OR DELETE ON property_financial_settings
FOR EACH ROW
EXECUTE FUNCTION fn_enforce_property_financial_settings_immutability();

-- ============================================================================
-- 8. SYSTEM PROCEDURES (SECURITY DEFINER)
-- ============================================================================

-- Controlled Journal Posting Procedure (Strict Lock Hierarchy Enforced)
CREATE OR REPLACE FUNCTION fn_post_journal_entry(
    p_entry_id UUID,
    p_user_id VARCHAR
) RETURNS VOID 
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_entry RECORD;
    v_period RECORD;
    v_settings RECORD;
    v_total_debit NUMERIC(18, 2);
    v_total_credit NUMERIC(18, 2);
    v_line_count INT;
    v_invalid_accounts INT;
    v_property_id UUID;
    v_period_id UUID;
BEGIN
    -- Step 0: Read entry metadata to identify property and period without taking locks yet
    SELECT property_id, accounting_period_id INTO v_property_id, v_period_id
    FROM finance_journal_entries
    WHERE id = p_entry_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Financial integrity violation: Journal entry % does not exist', p_entry_id;
    END IF;

    -- Level 1: Lock Financial Settings (FOR SHARE by property_id)
    SELECT * INTO v_settings
    FROM property_financial_settings
    WHERE property_id = v_property_id
    FOR SHARE;

    -- Level 2: Lock Accounting Period (FOR SHARE by id)
    SELECT * INTO v_period
    FROM finance_accounting_periods
    WHERE id = v_period_id
    FOR SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Financial integrity violation: Accounting period % not found for entry %', v_period_id, p_entry_id;
    END IF;

    IF v_period.status <> 'OPEN' THEN
        RAISE EXCEPTION 'Financial integrity violation: Cannot post entry % into accounting period % with status %', p_entry_id, v_period.period_name, v_period.status;
    END IF;

    -- Level 3: Lock Journal Entry (FOR UPDATE by id)
    SELECT * INTO v_entry
    FROM finance_journal_entries
    WHERE id = p_entry_id
    FOR UPDATE;

    -- Stale Metadata Race Guard: Revalidate property and period against locked Level 1 & Level 2
    IF v_entry.property_id <> v_property_id THEN
        RAISE EXCEPTION 'Financial integrity violation: Concurrency race detected. Journal entry % property_id changed concurrently (expected %, found %). Operation aborted to preserve lock order.',
            p_entry_id, v_property_id, v_entry.property_id;
    END IF;

    IF v_entry.accounting_period_id <> v_period_id THEN
        RAISE EXCEPTION 'Financial integrity violation: Concurrency race detected. Journal entry % accounting_period_id changed concurrently (expected %, found %). Operation aborted to preserve lock order.',
            p_entry_id, v_period_id, v_entry.accounting_period_id;
    END IF;

    IF v_entry.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'Financial integrity violation: Cannot post entry % with status % (only DRAFT entries can be posted)', v_entry.entry_number, v_entry.status;
    END IF;

    -- Date Invariants
    IF v_entry.accounting_date < v_period.start_date OR v_entry.accounting_date > v_period.end_date THEN
        RAISE EXCEPTION 'Financial integrity violation: Accounting date % is outside accounting period % range (% to %)', 
            v_entry.accounting_date, v_period.period_name, v_period.start_date, v_period.end_date;
    END IF;

    IF v_settings.books_locked_until_date IS NOT NULL AND v_entry.accounting_date <= v_settings.books_locked_until_date THEN
        RAISE EXCEPTION 'Financial integrity violation: Accounting date % is on or before property lock date %', v_entry.accounting_date, v_settings.books_locked_until_date;
    END IF;

    -- Level 4: Lock Accounts in strict ASC order (FOR SHARE)
    PERFORM id
    FROM finance_accounts
    WHERE id IN (
        SELECT DISTINCT account_id 
        FROM finance_journal_lines 
        WHERE journal_entry_id = p_entry_id
    )
    ORDER BY id ASC
    FOR SHARE;

    -- Account Active & Posting Validity Check
    IF v_entry.entry_type = 'REVERSAL' THEN
        -- Deliberate accounting policy: Reversing entries posted to accounts subsequently
        -- made inactive is permitted to restore historical ledger integrity and correct errors.
        -- However, summary/header accounts (is_allow_posting = FALSE) remain strictly prohibited.
        SELECT COUNT(*) INTO v_invalid_accounts
        FROM finance_journal_lines jl
        JOIN finance_accounts a ON a.id = jl.account_id
        WHERE jl.journal_entry_id = p_entry_id
          AND a.is_allow_posting = FALSE;

        IF v_invalid_accounts > 0 THEN
            RAISE EXCEPTION 'Financial integrity violation: Reversal journal entry % contains % line(s) referencing non-posting summary accounts', v_entry.entry_number, v_invalid_accounts;
        END IF;
    ELSE
        SELECT COUNT(*) INTO v_invalid_accounts
        FROM finance_journal_lines jl
        JOIN finance_accounts a ON a.id = jl.account_id
        WHERE jl.journal_entry_id = p_entry_id
          AND (a.is_active = FALSE OR a.is_allow_posting = FALSE);

        IF v_invalid_accounts > 0 THEN
            RAISE EXCEPTION 'Financial integrity violation: Journal entry % contains % line(s) referencing inactive or non-posting accounts', v_entry.entry_number, v_invalid_accounts;
        END IF;
    END IF;

    -- Level 5: Validate Lines
    SELECT 
        COALESCE(SUM(debit_amount), 0.00),
        COALESCE(SUM(credit_amount), 0.00),
        COUNT(*)
    INTO v_total_debit, v_total_credit, v_line_count
    FROM finance_journal_lines
    WHERE journal_entry_id = p_entry_id;

    IF v_line_count < 2 THEN
        RAISE EXCEPTION 'Financial integrity violation: Journal entry % must have at least 2 lines (found %)', v_entry.entry_number, v_line_count;
    END IF;

    IF v_total_debit <> v_total_credit THEN
        RAISE EXCEPTION 'Financial integrity violation: Journal entry % is unbalanced: Debits (%) <> Credits (%)', v_entry.entry_number, v_total_debit, v_total_credit;
    END IF;

    IF v_total_debit <= 0.00 THEN
        RAISE EXCEPTION 'Financial integrity violation: Journal entry % total amount must be strictly greater than 0.00', v_entry.entry_number;
    END IF;

    -- Post the journal entry
    UPDATE finance_journal_entries
    SET status = 'POSTED',
        total_amount = v_total_debit,
        posted_at = CURRENT_TIMESTAMP,
        posted_by = p_user_id,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_entry_id;

    -- Record audit event for authorized posting
    INSERT INTO finance_audit_events (
        property_id,
        event_type,
        aggregate_type,
        aggregate_id,
        payload,
        performed_by
    ) VALUES (
        v_entry.property_id,
        'JOURNAL_ENTRY_POSTED',
        'JOURNAL_ENTRY',
        p_entry_id,
        jsonb_build_object(
            'entry_number', v_entry.entry_number,
            'total_amount', v_total_debit,
            'accounting_date', v_entry.accounting_date,
            'transaction_date', v_entry.transaction_date,
            'line_count', v_line_count
        ),
        p_user_id
    );
END;
$$ LANGUAGE plpgsql;

-- Controlled Reversal Procedure (Strict Lock Hierarchy Enforced)
CREATE OR REPLACE FUNCTION fn_reverse_journal_entry(
    p_original_entry_id UUID,
    p_reversal_accounting_date DATE,
    p_reversal_period_id UUID,
    p_reversal_entry_number VARCHAR,
    p_reason TEXT,
    p_user_id VARCHAR,
    p_reversal_transaction_date DATE DEFAULT NULL
) RETURNS UUID 
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_orig RECORD;
    v_rev_period RECORD;
    v_settings RECORD;
    v_new_entry_id UUID;
    v_property_id UUID;
    v_orig_period_id UUID;
    v_property_timezone VARCHAR(50);
    v_effective_tx_date DATE;
BEGIN
    -- Read metadata without taking locks yet
    SELECT property_id, accounting_period_id INTO v_property_id, v_orig_period_id
    FROM finance_journal_entries
    WHERE id = p_original_entry_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Financial integrity violation: Original journal entry % not found', p_original_entry_id;
    END IF;

    -- Level 1: Lock Financial Settings (FOR SHARE by property_id)
    SELECT * INTO v_settings
    FROM property_financial_settings
    WHERE property_id = v_property_id
    FOR SHARE;

    -- Validate currency: Multi-currency is not supported in V7
    IF v_settings.base_currency IS NULL THEN
        RAISE EXCEPTION 'Financial integrity violation: Financial settings not found for property %', v_property_id;
    END IF;

    -- Level 2: Lock Accounting Periods in strict ASC order (FOR SHARE)
    PERFORM id
    FROM finance_accounting_periods
    WHERE id IN (v_orig_period_id, p_reversal_period_id)
    ORDER BY id ASC
    FOR SHARE;

    SELECT * INTO v_rev_period
    FROM finance_accounting_periods
    WHERE id = p_reversal_period_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Financial integrity violation: Reversal period % not found', p_reversal_period_id;
    END IF;

    IF v_rev_period.property_id <> v_property_id THEN
        RAISE EXCEPTION 'Financial integrity violation: Reversal period % belongs to property %; expected %', p_reversal_period_id, v_rev_period.property_id, v_property_id;
    END IF;

    IF v_rev_period.status <> 'OPEN' THEN
        RAISE EXCEPTION 'Financial integrity violation: Reversal period % is not OPEN (status: %)', v_rev_period.period_name, v_rev_period.status;
    END IF;

    IF p_reversal_accounting_date < v_rev_period.start_date OR p_reversal_accounting_date > v_rev_period.end_date THEN
        RAISE EXCEPTION 'Financial integrity violation: Reversal accounting date % is outside period % (% to %)',
            p_reversal_accounting_date, v_rev_period.period_name, v_rev_period.start_date, v_rev_period.end_date;
    END IF;

    IF v_settings.books_locked_until_date IS NOT NULL AND p_reversal_accounting_date <= v_settings.books_locked_until_date THEN
        RAISE EXCEPTION 'Financial integrity violation: Reversal accounting date % is on or before property lock date %',
            p_reversal_accounting_date, v_settings.books_locked_until_date;
    END IF;

    -- Level 3: Lock Journal Entry (FOR UPDATE by id)
    SELECT * INTO v_orig
    FROM finance_journal_entries
    WHERE id = p_original_entry_id
    FOR UPDATE;

    -- Stale Metadata Race Guard: Revalidate original entry metadata against locked hierarchy
    IF v_orig.property_id <> v_property_id THEN
        RAISE EXCEPTION 'Financial integrity violation: Concurrency race detected. Original journal entry % property_id changed concurrently. Reversal aborted.', p_original_entry_id;
    END IF;

    IF v_orig.accounting_period_id <> v_orig_period_id THEN
        RAISE EXCEPTION 'Financial integrity violation: Concurrency race detected. Original journal entry % accounting_period_id changed concurrently. Reversal aborted.', p_original_entry_id;
    END IF;

    IF v_orig.status <> 'POSTED' THEN
        RAISE EXCEPTION 'Financial integrity violation: Cannot reverse journal entry % with status % (only POSTED entries can be reversed)', v_orig.entry_number, v_orig.status;
    END IF;

    IF v_orig.reversed_by_entry_id IS NOT NULL THEN
        RAISE EXCEPTION 'Financial integrity violation: Journal entry % has already been reversed by entry %', v_orig.entry_number, v_orig.reversed_by_entry_id;
    END IF;

    IF v_orig.entry_type = 'REVERSAL' THEN
        RAISE EXCEPTION 'Financial integrity violation: Cannot reverse entry % because it is itself a reversal entry', v_orig.entry_number;
    END IF;

    IF v_orig.currency <> v_settings.base_currency THEN
        RAISE EXCEPTION 'Financial integrity violation: Multi-currency is not supported in V7. Original entry currency (%) differs from property base currency (%)',
            v_orig.currency, v_settings.base_currency;
    END IF;

    -- Level 4: Lock Accounts in strict ASC order (FOR SHARE)
    PERFORM id
    FROM finance_accounts
    WHERE id IN (
        SELECT DISTINCT account_id 
        FROM finance_journal_lines 
        WHERE journal_entry_id = p_original_entry_id
    )
    ORDER BY id ASC
    FOR SHARE;

    -- Level 5: Timezone-Aware Transaction Date Calculation
    SELECT timezone INTO v_property_timezone
    FROM properties
    WHERE id = v_orig.property_id;

    v_effective_tx_date := COALESCE(
        p_reversal_transaction_date,
        (CURRENT_TIMESTAMP AT TIME ZONE COALESCE(v_property_timezone, 'Asia/Kolkata'))::DATE
    );

    v_new_entry_id := gen_random_uuid();

    -- Create Reversal Entry with identical currency and facts
    INSERT INTO finance_journal_entries (
        id,
        property_id,
        accounting_period_id,
        entry_number,
        transaction_date,
        accounting_date,
        entry_type,
        status,
        currency,
        description,
        reference,
        source_type,
        source_id,
        idempotency_key,
        total_amount,
        reversal_of_entry_id,
        created_by
    ) VALUES (
        v_new_entry_id,
        v_orig.property_id,
        p_reversal_period_id,
        p_reversal_entry_number,
        v_effective_tx_date,
        p_reversal_accounting_date,
        'REVERSAL',
        'DRAFT',
        v_orig.currency,
        'Reversal of ' || v_orig.entry_number || ': ' || p_reason,
        v_orig.entry_number,
        v_orig.source_type,
        v_orig.source_id,
        'REV-' || v_orig.entry_number,
        0.00,
        v_orig.id,
        p_user_id
    );

    -- Copy Lines Inverting Debits and Credits Exactly
    INSERT INTO finance_journal_lines (
        property_id,
        journal_entry_id,
        account_id,
        line_number,
        debit_amount,
        credit_amount,
        description,
        reservation_id,
        guest_id,
        entity_type,
        entity_id
    )
    SELECT
        property_id,
        v_new_entry_id,
        account_id,
        line_number,
        credit_amount, -- Inverted: original credit becomes debit
        debit_amount,  -- Inverted: original debit becomes credit
        'Reversal: ' || COALESCE(description, ''),
        reservation_id,
        guest_id,
        entity_type,
        entity_id
    FROM finance_journal_lines
    WHERE journal_entry_id = p_original_entry_id
    ORDER BY line_number ASC;

    -- Post the reversal entry
    PERFORM fn_post_journal_entry(v_new_entry_id, p_user_id);

    -- Link reversal on original entry
    UPDATE finance_journal_entries
    SET status = 'REVERSED',
        reversed_by_entry_id = v_new_entry_id,
        reversed_at = CURRENT_TIMESTAMP,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_original_entry_id;

    -- Record reversal audit event for original entry
    INSERT INTO finance_audit_events (
        property_id,
        event_type,
        aggregate_type,
        aggregate_id,
        payload,
        performed_by
    ) VALUES (
        v_orig.property_id,
        'JOURNAL_ENTRY_REVERSED',
        'JOURNAL_ENTRY',
        p_original_entry_id,
        jsonb_build_object(
            'entry_number', v_orig.entry_number,
            'reversed_by_entry_id', v_new_entry_id,
            'reversal_entry_number', p_reversal_entry_number,
            'reason', p_reason,
            'reversal_accounting_date', p_reversal_accounting_date
        ),
        p_user_id
    );

    RETURN v_new_entry_id;
END;
$$ LANGUAGE plpgsql;

-- Controlled Draft Discard Procedure
CREATE OR REPLACE FUNCTION fn_discard_draft_journal_entry(
    p_entry_id UUID,
    p_reason TEXT,
    p_user_id VARCHAR
) RETURNS VOID 
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_entry RECORD;
    v_line_summary JSONB;
BEGIN
    SELECT * INTO v_entry
    FROM finance_journal_entries
    WHERE id = p_entry_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Financial integrity violation: Journal entry % does not exist', p_entry_id;
    END IF;

    IF v_entry.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'Financial integrity violation: Cannot discard % journal entry % (only DRAFT entries can be discarded)', v_entry.status, v_entry.entry_number;
    END IF;

    SELECT COALESCE(jsonb_agg(
        jsonb_build_object(
            'line_number', line_number,
            'account_id', account_id,
            'debit_amount', debit_amount,
            'credit_amount', credit_amount
        ) ORDER BY line_number ASC
    ), '[]'::jsonb)
    INTO v_line_summary
    FROM finance_journal_lines
    WHERE journal_entry_id = p_entry_id;

    -- Delete lines first
    DELETE FROM finance_journal_lines
    WHERE journal_entry_id = p_entry_id;

    -- Delete entry
    DELETE FROM finance_journal_entries
    WHERE id = p_entry_id;

    -- Record audit event for draft discard
    INSERT INTO finance_audit_events (
        property_id,
        event_type,
        aggregate_type,
        aggregate_id,
        payload,
        performed_by
    ) VALUES (
        v_entry.property_id,
        'JOURNAL_ENTRY_DISCARDED',
        'JOURNAL_ENTRY',
        p_entry_id,
        jsonb_build_object(
            'entry_number', v_entry.entry_number,
            'accounting_period_id', v_entry.accounting_period_id,
            'reason', p_reason,
            'discarded_lines', v_line_summary
        ),
        p_user_id
    );
END;
$$ LANGUAGE plpgsql;

-- Controlled Accounting Period Closing Procedure (Serialized with FOR UPDATE at Level 1)
CREATE OR REPLACE FUNCTION fn_close_accounting_period(
    p_period_id UUID,
    p_user_id VARCHAR
) RETURNS VOID 
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_period RECORD;
    v_property_id UUID;
    v_unposted_count INT;
    v_old_lock_date DATE;
    v_new_lock_date DATE;
BEGIN
    SELECT property_id INTO v_property_id
    FROM finance_accounting_periods
    WHERE id = p_period_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Financial integrity violation: Accounting period % not found', p_period_id;
    END IF;

    -- Level 1: Lock Financial Settings EXCLUSIVELY (FOR UPDATE by property_id)
    SELECT books_locked_until_date INTO v_old_lock_date
    FROM property_financial_settings
    WHERE property_id = v_property_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Financial integrity violation: Financial settings not found for property %', v_property_id;
    END IF;

    -- Level 2: Lock Period (FOR UPDATE by id)
    SELECT * INTO v_period
    FROM finance_accounting_periods
    WHERE id = p_period_id
    FOR UPDATE;

    IF v_period.status <> 'OPEN' THEN
        RAISE EXCEPTION 'Financial integrity violation: Cannot close period % with status % (must be OPEN)', v_period.period_name, v_period.status;
    END IF;

    -- Enforce Chronological Period Closure: No earlier open period may exist for this property
    IF EXISTS (
        SELECT 1 
        FROM finance_accounting_periods
        WHERE property_id = v_property_id
          AND end_date < v_period.start_date
          AND status = 'OPEN'
    ) THEN
        RAISE EXCEPTION 'Financial integrity violation: Cannot close accounting period % (%) because earlier accounting period(s) for this property remain OPEN. Periods must be closed in chronological order.',
            v_period.period_name, v_period.start_date;
    END IF;

    -- Level 3: Check for Unposted Draft Entries in Period
    SELECT COUNT(*) INTO v_unposted_count
    FROM finance_journal_entries
    WHERE accounting_period_id = p_period_id
      AND status = 'DRAFT';

    IF v_unposted_count > 0 THEN
        RAISE EXCEPTION 'Financial integrity violation: Cannot close accounting period % (% unposted DRAFT entries remain)', v_period.period_name, v_unposted_count;
    END IF;

    -- Close the Period
    UPDATE finance_accounting_periods
    SET status = 'CLOSED',
        closed_at = CURRENT_TIMESTAMP,
        closed_by = p_user_id,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_period_id;

    -- Advance books_locked_until_date monotonically
    v_new_lock_date := GREATEST(COALESCE(v_old_lock_date, v_period.end_date), v_period.end_date);

    UPDATE property_financial_settings
    SET books_locked_until_date = v_new_lock_date,
        updated_at = CURRENT_TIMESTAMP
    WHERE property_id = v_property_id;

    -- Record Period Closed Audit Event
    INSERT INTO finance_audit_events (
        property_id,
        event_type,
        aggregate_type,
        aggregate_id,
        payload,
        performed_by
    ) VALUES (
        v_property_id,
        'ACCOUNTING_PERIOD_CLOSED',
        'ACCOUNTING_PERIOD',
        p_period_id,
        jsonb_build_object(
            'period_name', v_period.period_name,
            'fiscal_year', v_period.fiscal_year,
            'period_number', v_period.period_number,
            'start_date', v_period.start_date,
            'end_date', v_period.end_date,
            'previous_locked_date', v_old_lock_date,
            'new_locked_date', v_new_lock_date
        ),
        p_user_id
    );
END;
$$ LANGUAGE plpgsql;

-- Enforced Historical Cutover Reconciliation Gate Procedure
CREATE OR REPLACE FUNCTION fn_approve_historical_cutover(
    p_property_id UUID,
    p_cutover_period_id UUID,
    p_approved_by VARCHAR
) RETURNS VOID 
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_period RECORD;
    v_suspense_balance NUMERIC(18, 2);
    v_total_debits NUMERIC(18, 2);
    v_total_credits NUMERIC(18, 2);
    v_unposted_count INT;
    v_old_lock_date DATE;
    v_new_lock_date DATE;
BEGIN
    -- Verify Property exists
    IF NOT EXISTS (SELECT 1 FROM properties WHERE id = p_property_id) THEN
        RAISE EXCEPTION 'Financial integrity violation: Property % does not exist', p_property_id;
    END IF;

    -- Level 1: Lock Financial Settings EXCLUSIVELY (FOR UPDATE by property_id)
    SELECT books_locked_until_date INTO v_old_lock_date
    FROM property_financial_settings
    WHERE property_id = p_property_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Financial integrity violation: Financial settings not found for property %', p_property_id;
    END IF;

    -- Level 2: Lock Cutover Accounting Period (FOR UPDATE by id)
    SELECT * INTO v_period
    FROM finance_accounting_periods
    WHERE id = p_cutover_period_id AND property_id = p_property_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Financial integrity violation: Cutover accounting period % not found for property %', p_cutover_period_id, p_property_id;
    END IF;

    IF v_period.status <> 'OPEN' THEN
        RAISE EXCEPTION 'Financial integrity violation: Cannot approve cutover for period % with status % (must be OPEN)', v_period.period_name, v_period.status;
    END IF;

    -- Enforce Chronological Period Closure: No earlier open period may exist for this property
    IF EXISTS (
        SELECT 1 
        FROM finance_accounting_periods
        WHERE property_id = p_property_id
          AND end_date < v_period.start_date
          AND status = 'OPEN'
    ) THEN
        RAISE EXCEPTION 'Historical cutover reconciliation gate failed: Cannot approve cutover for accounting period % because earlier accounting period(s) for this property remain OPEN. Historical periods must be closed in chronological order.',
            v_period.period_name;
    END IF;

    -- Level 3: Check for Unposted Drafts in the Cutover Period
    SELECT COUNT(*) INTO v_unposted_count
    FROM finance_journal_entries
    WHERE accounting_period_id = p_cutover_period_id
      AND status = 'DRAFT';

    IF v_unposted_count > 0 THEN
        RAISE EXCEPTION 'Financial integrity violation: Cannot approve cutover for period % (% unposted DRAFT entries remain)',
            v_period.period_name, v_unposted_count;
    END IF;

    -- GATE 1: Enforce Total Trial Balance Debit == Credit across the property historical posted ledger
    SELECT 
        COALESCE(SUM(jl.debit_amount), 0.00),
        COALESCE(SUM(jl.credit_amount), 0.00)
    INTO v_total_debits, v_total_credits
    FROM finance_journal_lines jl
    JOIN finance_journal_entries je ON je.id = jl.journal_entry_id
    WHERE jl.property_id = p_property_id
      AND je.status IN ('POSTED', 'REVERSED')
      AND je.accounting_date <= v_period.end_date;

    IF v_total_debits <> v_total_credits THEN
        RAISE EXCEPTION 'Historical cutover reconciliation gate failed: Property historical ledger is unbalanced. Total posted debits (%) <> total posted credits (%)',
            v_total_debits, v_total_credits;
    END IF;

    -- GATE 2: Enforce ZERO Suspense on Account 3099 (Opening Balance Equity / Cutover Suspense)
    -- In double-entry cutover, any difference between opening assets, liabilities, and imported historical
    -- transactions must be fully reconciled to 0.00 before cutover approval is granted.
    -- Strict scoping: Checks only POSTED ledger entries up to cutover end date, ignoring DRAFT lines.
    SELECT COALESCE(SUM(jl.debit_amount - jl.credit_amount), 0.00)
    INTO v_suspense_balance
    FROM finance_journal_lines jl
    JOIN finance_journal_entries je ON je.id = jl.journal_entry_id
    JOIN finance_accounts a ON a.id = jl.account_id
    WHERE jl.property_id = p_property_id
      AND je.status IN ('POSTED', 'REVERSED')
      AND je.accounting_date <= v_period.end_date
      AND a.account_code = '3099';

    IF v_suspense_balance <> 0.00 THEN
        RAISE EXCEPTION 'Historical cutover reconciliation gate failed: Account 3099 (Opening Balance Equity / Cutover Suspense) has an unreconciled posted balance of % INR (must be exactly 0.00). Cutover approval rejected.',
            v_suspense_balance;
    END IF;

    -- GATE 3: Verify at least one posted journal entry exists in the cutover period
    IF NOT EXISTS (
        SELECT 1 
        FROM finance_journal_entries 
        WHERE property_id = p_property_id 
          AND accounting_period_id = p_cutover_period_id 
          AND status IN ('POSTED', 'REVERSED')
    ) THEN
        RAISE EXCEPTION 'Historical cutover reconciliation gate failed: No posted journal entries found for cutover accounting period %.',
            v_period.period_name;
    END IF;

    -- Close the Cutover Period
    UPDATE finance_accounting_periods
    SET status = 'CLOSED',
        closed_at = CURRENT_TIMESTAMP,
        closed_by = p_approved_by,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_cutover_period_id;

    -- Advance books_locked_until_date monotonically
    v_new_lock_date := GREATEST(COALESCE(v_old_lock_date, v_period.end_date), v_period.end_date);

    UPDATE property_financial_settings
    SET books_locked_until_date = v_new_lock_date,
        updated_at = CURRENT_TIMESTAMP
    WHERE property_id = p_property_id;

    -- Record Audit Event
    INSERT INTO finance_audit_events (
        property_id,
        event_type,
        aggregate_type,
        aggregate_id,
        payload,
        performed_by
    ) VALUES (
        p_property_id,
        'HISTORICAL_CUTOVER_APPROVED',
        'ACCOUNTING_PERIOD',
        p_cutover_period_id,
        jsonb_build_object(
            'period_name', v_period.period_name,
            'cutover_end_date', v_period.end_date,
            'total_reconciled_debits', v_total_debits,
            'total_reconciled_credits', v_total_credits,
            'suspense_balance', v_suspense_balance,
            'approved_by', p_approved_by
        ),
        p_approved_by
    );
END;
$$ LANGUAGE plpgsql;

-- Explicit Property Onboarding Function (Verified UUID, Dynamic Calendar, Strict First Day Check)
CREATE OR REPLACE FUNCTION fn_initialize_property_finances(
    p_property_id UUID,
    p_base_currency VARCHAR(3),
    p_fiscal_start_month SMALLINT,
    p_cutover_date DATE
) RETURNS VOID 
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_start_year INT;
    v_start_month INT;
    v_period_number SMALLINT;
    v_fiscal_year INT;
    v_period_end DATE;
    v_period_name VARCHAR(100);
BEGIN
    -- Strict verification: Property must exist by exact UUID
    IF NOT EXISTS (SELECT 1 FROM properties WHERE id = p_property_id) THEN
        RAISE EXCEPTION 'Financial integrity violation: Property UUID % does not exist in properties table', p_property_id;
    END IF;

    IF p_fiscal_start_month < 1 OR p_fiscal_start_month > 12 THEN
        RAISE EXCEPTION 'Financial integrity violation: Invalid fiscal start month % (must be between 1 and 12)', p_fiscal_start_month;
    END IF;

    -- Strict verification: Cutover date must be day 1 of calendar month
    IF EXTRACT(DAY FROM p_cutover_date) <> 1 THEN
        RAISE EXCEPTION 'Financial integrity violation: Cutover date % must be the first day of the calendar month (day 1)', p_cutover_date;
    END IF;

    v_start_year := EXTRACT(YEAR FROM p_cutover_date)::INT;
    v_start_month := EXTRACT(MONTH FROM p_cutover_date)::INT;

    IF v_start_month >= p_fiscal_start_month THEN
        v_period_number := (v_start_month - p_fiscal_start_month + 1)::SMALLINT;
        v_fiscal_year := v_start_year;
    ELSE
        v_period_number := (v_start_month + 12 - p_fiscal_start_month + 1)::SMALLINT;
        v_fiscal_year := v_start_year - 1;
    END IF;

    -- Accurate end date calculation (handles 28/29/30/31 days dynamically)
    v_period_end := (p_cutover_date + INTERVAL '1 month - 1 day')::DATE;
    v_period_name := 'FY' || v_fiscal_year || '-M' || LPAD(v_period_number::TEXT, 2, '0') || ' (' || TO_CHAR(p_cutover_date, 'Mon YYYY') || ')';

    INSERT INTO property_financial_settings (
        property_id, fiscal_year_start_month, fiscal_year_start_day, base_currency, accounting_method
    ) VALUES (
        p_property_id, p_fiscal_start_month, 1, p_base_currency, 'ACCRUAL'
    ) ON CONFLICT (property_id) DO NOTHING;

    INSERT INTO finance_accounting_periods (
        property_id, period_name, fiscal_year, period_number, start_date, end_date, status
    ) VALUES (
        p_property_id, v_period_name, v_fiscal_year, v_period_number, p_cutover_date, v_period_end, 'OPEN'
    ) ON CONFLICT DO NOTHING;

    INSERT INTO finance_accounts (
        property_id, account_code, account_name, account_type, account_category, normal_balance, is_contra, standard_mapping_code, is_system_account
    ) VALUES
        (p_property_id, '1010', 'Cash on Hand', 'ASSET', 'CASH_AND_EQUIVALENTS', 'DEBIT', FALSE, 'USALI_CASH', TRUE),
        (p_property_id, '1020', 'Operating Bank Account', 'ASSET', 'BANK', 'DEBIT', FALSE, 'USALI_BANK_MAIN', TRUE),
        (p_property_id, '1030', 'Payment Gateway Clearing (Razorpay)', 'ASSET', 'CLEARING', 'DEBIT', FALSE, 'USALI_CLEARING', TRUE),
        (p_property_id, '1100', 'Guest Ledger / Accounts Receivable', 'ASSET', 'ACCOUNTS_RECEIVABLE', 'DEBIT', FALSE, 'USALI_GUEST_AR', TRUE),
        (p_property_id, '2010', 'Accounts Payable / Trade Vendors', 'LIABILITY', 'CURRENT_LIABILITY', 'CREDIT', FALSE, 'USALI_AP', TRUE),
        (p_property_id, '2100', 'Advance Guest Deposits / Unearned Revenue', 'LIABILITY', 'ADVANCE_DEPOSITS', 'CREDIT', FALSE, 'USALI_ADV_DEPOSIT', TRUE),
        (p_property_id, '2150', 'PG Security Deposits Held', 'LIABILITY', 'DEPOSIT_LIABILITY', 'CREDIT', FALSE, 'USALI_SECURITY_DEPOSIT', TRUE),
        (p_property_id, '2200', 'GST Output Tax Payable', 'LIABILITY', 'TAX_PAYABLE', 'CREDIT', FALSE, 'TAX_GST_PAYABLE', TRUE),
        (p_property_id, '3010', 'Owners Capital / Invested Equity', 'EQUITY', 'OWNERS_EQUITY', 'CREDIT', FALSE, 'USALI_OWNER_EQUITY', FALSE),
        (p_property_id, '3020', 'Retained Earnings', 'EQUITY', 'RETAINED_EARNINGS', 'CREDIT', FALSE, 'USALI_RETAINED_EARNINGS', TRUE),
        (p_property_id, '3099', 'Opening Balance Equity / Cutover Suspense', 'EQUITY', 'TRANSITIONAL_EQUITY', 'CREDIT', FALSE, 'USALI_OPENING_EQUITY', TRUE),
        (p_property_id, '4000', 'Homestay Room Revenue', 'REVENUE', 'OPERATING_REVENUE', 'CREDIT', FALSE, 'USALI_ROOM_REV', TRUE),
        (p_property_id, '4100', 'Monthly PG Room Revenue', 'REVENUE', 'OPERATING_REVENUE', 'CREDIT', FALSE, 'USALI_PG_REV', TRUE),
        (p_property_id, '4200', 'Food and Beverage Revenue', 'REVENUE', 'OPERATING_REVENUE', 'CREDIT', FALSE, 'USALI_FB_REV', FALSE),
        (p_property_id, '4300', 'Extra Services & Laundry Revenue', 'REVENUE', 'OPERATING_REVENUE', 'CREDIT', FALSE, 'USALI_OTHER_REV', FALSE),
        (p_property_id, '4900', 'Discounts and Guest Allowances', 'REVENUE', 'CONTRA_REVENUE', 'DEBIT', TRUE, 'USALI_DISCOUNTS', FALSE),
        (p_property_id, '5000', 'Homestay Operating Supplies & Amenities', 'EXPENSE', 'OPERATING_EXPENSE', 'DEBIT', FALSE, 'USALI_OPER_EXPENSE', FALSE),
        (p_property_id, '5100', 'Utilities (Electricity, Water, Wi-Fi)', 'EXPENSE', 'OPERATING_EXPENSE', 'DEBIT', FALSE, 'USALI_UTILITIES', FALSE),
        (p_property_id, '5200', 'Maintenance & Repairs', 'EXPENSE', 'OPERATING_EXPENSE', 'DEBIT', FALSE, 'USALI_MAINTENANCE', FALSE),
        (p_property_id, '5300', 'Payment Gateway & Platform Commission Fees', 'EXPENSE', 'OPERATING_EXPENSE', 'DEBIT', FALSE, 'USALI_COMMISSION_FEES', FALSE)
    ON CONFLICT (property_id, account_code) DO NOTHING;
END;
$$ LANGUAGE plpgsql;

-- Seal execution from PUBLIC on all SECURITY DEFINER functions (Flyway clean, no passwords/roles)
REVOKE ALL ON FUNCTION fn_post_journal_entry(UUID, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_reverse_journal_entry(UUID, DATE, UUID, VARCHAR, TEXT, VARCHAR, DATE) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_discard_draft_journal_entry(UUID, TEXT, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_close_accounting_period(UUID, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_approve_historical_cutover(UUID, UUID, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_initialize_property_finances(UUID, VARCHAR, SMALLINT, DATE) FROM PUBLIC;
