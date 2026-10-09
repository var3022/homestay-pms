-- ============================================================
-- V1: Property and Room Management Schema
-- ============================================================

-- ============================================================
-- 1. PROPERTIES
-- ============================================================

CREATE TABLE properties (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    name VARCHAR(150) NOT NULL,
    code VARCHAR(50) NOT NULL,
    description TEXT,

    address_line1 VARCHAR(255) NOT NULL,
    address_line2 VARCHAR(255),
    city VARCHAR(100) NOT NULL,
    state VARCHAR(100) NOT NULL,
    country VARCHAR(100) NOT NULL DEFAULT 'India',
    postal_code VARCHAR(20),

    phone VARCHAR(30),
    email VARCHAR(255),

    timezone VARCHAR(50) NOT NULL DEFAULT 'Asia/Kolkata',

    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_properties_code UNIQUE (code)
);

-- ============================================================
-- 2. ROOM TYPES
-- ============================================================

CREATE TABLE room_types (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    property_id UUID NOT NULL,

    name VARCHAR(100) NOT NULL,
    code VARCHAR(50) NOT NULL,
    description TEXT,

    category VARCHAR(30) NOT NULL,

    base_occupancy INTEGER NOT NULL DEFAULT 1,
    max_occupancy INTEGER NOT NULL DEFAULT 1,
    max_extra_beds INTEGER NOT NULL DEFAULT 0,

    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_room_types_property
        FOREIGN KEY (property_id)
        REFERENCES properties(id),

    CONSTRAINT uq_room_types_property_code
        UNIQUE (property_id, code),

    CONSTRAINT chk_room_types_base_occupancy
        CHECK (base_occupancy >= 1),

    CONSTRAINT chk_room_types_max_occupancy
        CHECK (max_occupancy >= base_occupancy),

    CONSTRAINT chk_room_types_extra_beds
        CHECK (max_extra_beds >= 0)
);

-- ============================================================
-- 3. ROOMS
-- ============================================================

CREATE TABLE rooms (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    property_id UUID NOT NULL,
    room_type_id UUID NOT NULL,

    room_number VARCHAR(30) NOT NULL,
    name VARCHAR(100),
    floor VARCHAR(30),

    base_occupancy INTEGER NOT NULL DEFAULT 1,
    max_occupancy INTEGER NOT NULL DEFAULT 1,
    max_extra_beds INTEGER NOT NULL DEFAULT 0,

    bathroom_count INTEGER NOT NULL DEFAULT 1,

    status VARCHAR(30) NOT NULL DEFAULT 'AVAILABLE',

    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_rooms_property
        FOREIGN KEY (property_id)
        REFERENCES properties(id),

    CONSTRAINT fk_rooms_room_type
        FOREIGN KEY (room_type_id)
        REFERENCES room_types(id),

    CONSTRAINT uq_rooms_property_room_number
        UNIQUE (property_id, room_number),

    CONSTRAINT chk_rooms_base_occupancy
        CHECK (base_occupancy >= 1),

    CONSTRAINT chk_rooms_max_occupancy
        CHECK (max_occupancy >= base_occupancy),

    CONSTRAINT chk_rooms_extra_beds
        CHECK (max_extra_beds >= 0),

    CONSTRAINT chk_rooms_bathroom_count
        CHECK (bathroom_count >= 1),

    CONSTRAINT chk_rooms_status
        CHECK (
            status IN (
                'AVAILABLE',
                'OCCUPIED',
                'OUT_OF_SERVICE'
            )
        )
);

-- ============================================================
-- 4. BED TYPES
-- ============================================================

CREATE TABLE bed_types (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    name VARCHAR(50) NOT NULL,
    code VARCHAR(30) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_bed_types_code
        UNIQUE (code)
);

-- ============================================================
-- 5. ROOM BEDS
-- ============================================================

CREATE TABLE room_beds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    room_id UUID NOT NULL,
    bed_type_id UUID NOT NULL,

    quantity INTEGER NOT NULL DEFAULT 1,
    is_extra_bed BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_room_beds_room
        FOREIGN KEY (room_id)
        REFERENCES rooms(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_room_beds_bed_type
        FOREIGN KEY (bed_type_id)
        REFERENCES bed_types(id),

    CONSTRAINT chk_room_beds_quantity
        CHECK (quantity >= 1)
);

-- ============================================================
-- 6. AMENITIES
-- ============================================================

CREATE TABLE amenities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    name VARCHAR(100) NOT NULL,
    code VARCHAR(50) NOT NULL,
    category VARCHAR(30) NOT NULL,

    description TEXT,

    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_amenities_code
        UNIQUE (code)
);

-- ============================================================
-- 7. PROPERTY AMENITIES
-- ============================================================

CREATE TABLE property_amenities (
    property_id UUID NOT NULL,
    amenity_id UUID NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (property_id, amenity_id),

    CONSTRAINT fk_property_amenities_property
        FOREIGN KEY (property_id)
        REFERENCES properties(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_property_amenities_amenity
        FOREIGN KEY (amenity_id)
        REFERENCES amenities(id)
);

-- ============================================================
-- 8. ROOM AMENITIES
-- ============================================================

CREATE TABLE room_amenities (
    room_id UUID NOT NULL,
    amenity_id UUID NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (room_id, amenity_id),

    CONSTRAINT fk_room_amenities_room
        FOREIGN KEY (room_id)
        REFERENCES rooms(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_room_amenities_amenity
        FOREIGN KEY (amenity_id)
        REFERENCES amenities(id)
);

-- ============================================================
-- 9. INDEXES
-- ============================================================

CREATE INDEX idx_room_types_property_id
    ON room_types(property_id);

CREATE INDEX idx_rooms_property_id
    ON rooms(property_id);

CREATE INDEX idx_rooms_room_type_id
    ON rooms(room_type_id);

CREATE INDEX idx_rooms_status
    ON rooms(status);

CREATE INDEX idx_room_beds_room_id
    ON room_beds(room_id);

CREATE INDEX idx_room_beds_bed_type_id
    ON room_beds(bed_type_id);

CREATE INDEX idx_property_amenities_amenity_id
    ON property_amenities(amenity_id);

CREATE INDEX idx_room_amenities_amenity_id
    ON room_amenities(amenity_id);