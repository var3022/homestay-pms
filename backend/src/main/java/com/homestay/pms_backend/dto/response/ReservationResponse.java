package com.homestay.pms_backend.dto.response;

import com.homestay.pms_backend.enums.ReservationStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class ReservationResponse {

    private UUID id;

    private UUID propertyId;

    private UUID primaryGuestId;

    private LocalDate checkInDate;

    private LocalDate checkOutDate;

    private int adults;

    private int children;

    private int infants;

    private UUID bookingSourceId;

    private String externalBookingId;

    private ReservationStatus status;

    private String specialRequests;

    private boolean checkoutDue;

    private Instant checkoutDueAt;

    private Instant createdAt;

    private Instant updatedAt;
}