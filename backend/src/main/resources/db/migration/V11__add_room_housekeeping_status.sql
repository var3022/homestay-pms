ALTER TABLE rooms
    ADD COLUMN housekeeping_status VARCHAR(30) NOT NULL DEFAULT 'CLEAN';

ALTER TABLE rooms
    ADD CONSTRAINT chk_rooms_housekeeping_status
    CHECK (housekeeping_status IN ('CLEAN', 'DIRTY', 'CLEANING_IN_PROGRESS'));

CREATE INDEX idx_rooms_housekeeping_status
    ON rooms (housekeeping_status);
