package com.homestay.pms_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "room_beds")
@Getter
@Setter
@NoArgsConstructor
public class RoomBed {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "bed_type_id", nullable = false)
    private UUID bedTypeId;

    @Column(nullable = false)
    private Integer quantity = 1;

    @Column(name = "is_extra_bed", nullable = false)
    private boolean extraBed = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}