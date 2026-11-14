-- =============================================================================
-- V10: Guest-Property Association Database Foundation
-- =============================================================================
-- Objectives:
-- 1. Create guest_property_associations to establish explicit, database-enforced
--    associations between guests and properties.
-- 2. Support multi-property homestays: guests can belong to multiple properties
--    without duplicating the underlying guest identity record.
-- 3. Preserve referential integrity without ON DELETE CASCADE to prevent accidental
--    loss of historical guest associations.
-- 4. Enable efficient property-scoped guest lookups and listings.
-- 5. Backfill historical associations idempotently from reservations and reservation_guests.
-- =============================================================================

CREATE TABLE guest_property_associations (
    guest_id UUID NOT NULL,
    property_id UUID NOT NULL,

    first_associated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    association_source VARCHAR(30) NOT NULL,

    CONSTRAINT pk_guest_property_associations
        PRIMARY KEY (guest_id, property_id),

    CONSTRAINT fk_gpa_guest
        FOREIGN KEY (guest_id)
        REFERENCES guests (id),

    CONSTRAINT fk_gpa_property
        FOREIGN KEY (property_id)
        REFERENCES properties (id),

    CONSTRAINT chk_gpa_association_source
        CHECK (association_source IN ('REGISTRATION', 'RESERVATION', 'HISTORICAL_BACKFILL')),

    CONSTRAINT chk_gpa_timestamps
        CHECK (last_activity_at >= first_associated_at)
);

-- Index for property-scoped guest lookups and listings
CREATE INDEX idx_gpa_property_guest
    ON guest_property_associations (property_id, guest_id);

-- Index for guest-centric lookups across properties
CREATE INDEX idx_gpa_guest_id
    ON guest_property_associations (guest_id);


-- =============================================================================
-- Historical Backfill from Reservations and Reservation Guests
-- =============================================================================

-- 1. Backfill from primary guests in reservations
-- Preserves all historical associations regardless of reservation status (including CANCELLED and CHECKED_OUT).
INSERT INTO guest_property_associations (
    guest_id,
    property_id,
    first_associated_at,
    last_activity_at,
    association_source
)
SELECT
    r.primary_guest_id AS guest_id,
    r.property_id AS property_id,
    MIN(r.created_at) AS first_associated_at,
    GREATEST(MIN(r.created_at), MAX(COALESCE(r.updated_at, r.created_at))) AS last_activity_at,
    'RESERVATION' AS association_source
FROM reservations r
GROUP BY r.primary_guest_id, r.property_id
ON CONFLICT (guest_id, property_id) DO NOTHING;

-- 2. Backfill from secondary guests in reservation_guests (if present in schema)
INSERT INTO guest_property_associations (
    guest_id,
    property_id,
    first_associated_at,
    last_activity_at,
    association_source
)
SELECT
    rg.guest_id AS guest_id,
    r.property_id AS property_id,
    MIN(rg.created_at) AS first_associated_at,
    GREATEST(MIN(rg.created_at), MAX(COALESCE(r.updated_at, rg.created_at))) AS last_activity_at,
    'RESERVATION' AS association_source
FROM reservation_guests rg
JOIN reservations r ON rg.reservation_id = r.id
GROUP BY rg.guest_id, r.property_id
ON CONFLICT (guest_id, property_id) DO UPDATE SET
    first_associated_at = LEAST(guest_property_associations.first_associated_at, EXCLUDED.first_associated_at),
    last_activity_at = GREATEST(guest_property_associations.last_activity_at, EXCLUDED.last_activity_at);
