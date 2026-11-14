package com.homestay.pms_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GuestPropertyAssociationId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "guest_id", nullable = false)
    private UUID guestId;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GuestPropertyAssociationId that = (GuestPropertyAssociationId) o;
        return Objects.equals(guestId, that.guestId) &&
                Objects.equals(propertyId, that.propertyId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(guestId, propertyId);
    }

    @Override
    public String toString() {
        return "GuestPropertyAssociationId{" +
                "guestId=" + guestId +
                ", propertyId=" + propertyId +
                '}';
    }
}
