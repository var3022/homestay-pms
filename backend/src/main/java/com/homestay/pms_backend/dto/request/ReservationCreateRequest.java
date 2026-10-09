package com.homestay.pms_backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class ReservationCreateRequest {

    @NotNull
    private UUID propertyId;

    @NotNull
    private UUID primaryGuestId;

    @NotNull
    private LocalDate checkInDate;

    @NotNull
    private LocalDate checkOutDate;

    @Min(1)
    private int adults = 1;

    @Min(0)
    private int children = 0;

    @Min(0)
    private int infants = 0;

    @NotNull
    private UUID bookingSourceId;

    @Size(max = 255)
    private String externalBookingId;

    @Size(max = 5000)
    private String specialRequests;

    @NotEmpty
    @Valid
    private List<ReservationRoomCreateRequest> reservationRooms;
}