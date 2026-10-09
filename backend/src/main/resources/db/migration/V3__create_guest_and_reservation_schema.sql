-- ============================================================
-- V3: Guest and Reservation Schema
-- ============================================================

-- ============================================================
-- Guests
-- ============================================================

CREATE TABLE guests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    name VARCHAR(150) NOT NULL,

    phone VARCHAR(30),
    email VARCHAR(255),

    address TEXT,
    city VARCHAR(100),
    state VARCHAR(100),
    country VARCHAR(100),
    postal_code VARCHAR(20),

    id_type VARCHAR(30),
    id_number VARCHAR(100),
    id_document_image_key VARCHAR(500),

    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_guests_phone
    ON guests (phone);

CREATE INDEX idx_guests_email
    ON guests (email);


-- ============================================================
-- Booking Sources
-- ============================================================

CREATE TABLE booking_sources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,

    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- ============================================================
-- Reservations
-- ============================================================

CREATE TABLE reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    property_id UUID NOT NULL,
    primary_guest_id UUID NOT NULL,

    check_in_date DATE NOT NULL,
    check_out_date DATE NOT NULL,

    adults INTEGER NOT NULL DEFAULT 1,
    children INTEGER NOT NULL DEFAULT 0,
    infants INTEGER NOT NULL DEFAULT 0,

    booking_source_id UUID NOT NULL,
    external_booking_id VARCHAR(255),

    status VARCHAR(30) NOT NULL,

    special_requests TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_reservations_property
        FOREIGN KEY (property_id)
        REFERENCES properties(id),

    CONSTRAINT fk_reservations_primary_guest
        FOREIGN KEY (primary_guest_id)
        REFERENCES guests(id),

    CONSTRAINT fk_reservations_booking_source
        FOREIGN KEY (booking_source_id)
        REFERENCES booking_sources(id),

    CONSTRAINT chk_reservations_dates
        CHECK (check_out_date > check_in_date),

    CONSTRAINT chk_reservations_adults
        CHECK (adults >= 1),

    CONSTRAINT chk_reservations_children
        CHECK (children >= 0),

    CONSTRAINT chk_reservations_infants
        CHECK (infants >= 0)
);

CREATE UNIQUE INDEX uq_reservations_source_external_id
    ON reservations (booking_source_id, external_booking_id)
    WHERE external_booking_id IS NOT NULL;

CREATE INDEX idx_reservations_property
    ON reservations (property_id);

CREATE INDEX idx_reservations_primary_guest
    ON reservations (primary_guest_id);

CREATE INDEX idx_reservations_check_in_date
    ON reservations (check_in_date);

CREATE INDEX idx_reservations_check_out_date
    ON reservations (check_out_date);

CREATE INDEX idx_reservations_status
    ON reservations (status);


-- ============================================================
-- Reservation Guests
-- ============================================================

CREATE TABLE reservation_guests (
    reservation_id UUID NOT NULL,
    guest_id UUID NOT NULL,

    role VARCHAR(30) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (reservation_id, guest_id),

    CONSTRAINT fk_reservation_guests_reservation
        FOREIGN KEY (reservation_id)
        REFERENCES reservations(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_reservation_guests_guest
        FOREIGN KEY (guest_id)
        REFERENCES guests(id)
);

CREATE INDEX idx_reservation_guests_guest
    ON reservation_guests (guest_id);


-- ============================================================
-- Reservation Rooms
-- ============================================================

CREATE TABLE reservation_rooms (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    reservation_id UUID NOT NULL,
    room_type_id UUID NOT NULL,

    quantity INTEGER NOT NULL,
    cancelled_quantity INTEGER NOT NULL DEFAULT 0,

    unit_price NUMERIC(12, 2),
    subtotal NUMERIC(12, 2),

    status VARCHAR(30) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_reservation_rooms_reservation
        FOREIGN KEY (reservation_id)
        REFERENCES reservations(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_reservation_rooms_room_type
        FOREIGN KEY (room_type_id)
        REFERENCES room_types(id),

    CONSTRAINT chk_reservation_rooms_quantity
        CHECK (quantity >= 1),

    CONSTRAINT chk_reservation_rooms_cancelled_quantity
        CHECK (
            cancelled_quantity >= 0
            AND cancelled_quantity <= quantity
        ),

    CONSTRAINT chk_reservation_rooms_unit_price
        CHECK (
            unit_price IS NULL
            OR unit_price >= 0
        ),

    CONSTRAINT chk_reservation_rooms_subtotal
        CHECK (
            subtotal IS NULL
            OR subtotal >= 0
        )
);

CREATE INDEX idx_reservation_rooms_reservation
    ON reservation_rooms (reservation_id);

CREATE INDEX idx_reservation_rooms_room_type
    ON reservation_rooms (room_type_id);


-- ============================================================
-- Room Assignments
-- ============================================================

CREATE TABLE room_assignments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    reservation_room_id UUID NOT NULL,
    room_id UUID NOT NULL,

    assigned_from DATE NOT NULL,
    assigned_until DATE NOT NULL,

    status VARCHAR(30) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_room_assignments_reservation_room
        FOREIGN KEY (reservation_room_id)
        REFERENCES reservation_rooms(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_room_assignments_room
        FOREIGN KEY (room_id)
        REFERENCES rooms(id),

    CONSTRAINT chk_room_assignments_dates
        CHECK (assigned_until > assigned_from)
);

CREATE INDEX idx_room_assignments_reservation_room
    ON room_assignments (reservation_room_id);

CREATE INDEX idx_room_assignments_room
    ON room_assignments (room_id);

CREATE INDEX idx_room_assignments_dates
    ON room_assignments (assigned_from, assigned_until);