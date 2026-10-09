package com.homestay.pms_backend.entity;

import com.homestay.pms_backend.enums.RoomAssignmentStatus;
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
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "room_assignments")
@Getter
@Setter
@NoArgsConstructor
public class RoomAssignment {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "reservation_room_id", nullable = false)
    private UUID reservationRoomId;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "assigned_from", nullable = false)
    private LocalDate assignedFrom;

    @Column(name = "assigned_until", nullable = false)
    private LocalDate assignedUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RoomAssignmentStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}