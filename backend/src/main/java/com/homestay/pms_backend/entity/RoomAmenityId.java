package com.homestay.pms_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class RoomAmenityId implements Serializable {

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "amenity_id", nullable = false)
    private UUID amenityId;
}