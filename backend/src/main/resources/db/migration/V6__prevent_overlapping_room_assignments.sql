CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE room_assignments
    ADD CONSTRAINT ex_room_assignments_no_overlap
    EXCLUDE USING gist (
        room_id WITH =,
        daterange(assigned_from, assigned_until, '[)') WITH &&
    )
    WHERE (status = 'ASSIGNED');