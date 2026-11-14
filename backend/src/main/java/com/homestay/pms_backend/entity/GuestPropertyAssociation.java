package com.homestay.pms_backend.entity;

import com.homestay.pms_backend.enums.GuestAssociationSource;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "guest_property_associations")
@Getter
@Setter
@NoArgsConstructor
public class GuestPropertyAssociation {

    @EmbeddedId
    private GuestPropertyAssociationId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("guestId")
    @JoinColumn(name = "guest_id", nullable = false)
    private Guest guest;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("propertyId")
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Column(name = "first_associated_at", nullable = false)
    private Instant firstAssociatedAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "association_source", nullable = false, length = 30)
    private GuestAssociationSource associationSource;

    public GuestPropertyAssociation(Guest guest, Property property, GuestAssociationSource associationSource) {
        Objects.requireNonNull(guest, "Guest must not be null");
        Objects.requireNonNull(property, "Property must not be null");
        Objects.requireNonNull(associationSource, "GuestAssociationSource must not be null");

        this.guest = guest;
        this.property = property;
        this.id = new GuestPropertyAssociationId(guest.getId(), property.getId());
        this.associationSource = associationSource;
        Instant now = Instant.now();
        this.firstAssociatedAt = now;
        this.lastActivityAt = now;
    }

    public GuestPropertyAssociation(Guest guest, Property property, GuestAssociationSource associationSource,
                                    Instant firstAssociatedAt, Instant lastActivityAt) {
        Objects.requireNonNull(guest, "Guest must not be null");
        Objects.requireNonNull(property, "Property must not be null");
        Objects.requireNonNull(associationSource, "GuestAssociationSource must not be null");

        this.guest = guest;
        this.property = property;
        this.id = new GuestPropertyAssociationId(guest.getId(), property.getId());
        this.associationSource = associationSource;
        this.firstAssociatedAt = firstAssociatedAt != null ? firstAssociatedAt : Instant.now();
        this.lastActivityAt = lastActivityAt != null ? lastActivityAt : this.firstAssociatedAt;
    }

    public UUID getGuestId() {
        return id != null ? id.getGuestId() : (guest != null ? guest.getId() : null);
    }

    public UUID getPropertyId() {
        return id != null ? id.getPropertyId() : (property != null ? property.getId() : null);
    }
}
