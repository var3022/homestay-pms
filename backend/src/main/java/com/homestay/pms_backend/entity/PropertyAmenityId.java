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
public class PropertyAmenityId implements Serializable {

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(name = "amenity_id", nullable = false)
    private UUID amenityId;
}