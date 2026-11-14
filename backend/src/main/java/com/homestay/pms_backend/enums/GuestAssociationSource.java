package com.homestay.pms_backend.enums;

/**
 * Designated origin source for guest-property associations.
 */
public enum GuestAssociationSource {

    /**
     * Associated via explicit front-desk guest registration prior to or independent of booking.
     */
    REGISTRATION,

    /**
     * Associated through an active, confirmed, cancelled, or historical reservation.
     */
    RESERVATION,

    /**
     * Associated through historical database data backfill.
     */
    HISTORICAL_BACKFILL
}
