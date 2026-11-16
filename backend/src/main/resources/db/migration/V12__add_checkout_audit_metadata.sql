ALTER TABLE reservations
    ADD COLUMN actual_checked_out_at TIMESTAMPTZ,
    ADD COLUMN checked_out_by_staff_user_id UUID;

ALTER TABLE reservations
    ADD CONSTRAINT fk_reservations_checked_out_by
    FOREIGN KEY (checked_out_by_staff_user_id)
    REFERENCES staff_users (id)
    ON DELETE SET NULL;
