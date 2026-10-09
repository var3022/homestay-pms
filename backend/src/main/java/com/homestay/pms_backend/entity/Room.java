package com.homestay.pms_backend.entity;

import com.homestay.pms_backend.enums.RoomStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rooms")
@Getter
@Setter
@NoArgsConstructor
public class Room {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(name = "room_type_id", nullable = false)
    private UUID roomTypeId;

    @Column(name = "room_number", nullable = false, length = 30)
    private String roomNumber;

    @Column(length = 100)
    private String name;

    @Column(length = 30)
    private String floor;

    @Column(name = "base_occupancy", nullable = false)
    private Integer baseOccupancy = 1;

    @Column(name = "max_occupancy", nullable = false)
    private Integer maxOccupancy = 1;

    @Column(name = "max_extra_beds", nullable = false)
    private Integer maxExtraBeds = 0;

    @Column(name = "bathroom_count", nullable = false)
    private Integer bathroomCount = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RoomStatus status = RoomStatus.AVAILABLE;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}