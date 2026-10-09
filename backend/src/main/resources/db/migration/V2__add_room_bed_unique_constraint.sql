CREATE UNIQUE INDEX uq_room_beds_room_bed_type_extra
ON room_beds (room_id, bed_type_id, is_extra_bed);