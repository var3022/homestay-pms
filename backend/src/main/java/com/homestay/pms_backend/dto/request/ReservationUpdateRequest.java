package com.homestay.pms_backend.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
public class ReservationUpdateRequest {

    private LocalDate checkInDate;

    private LocalDate checkOutDate;

    @Min(1)
    private Integer adults;

    @Min(0)
    private Integer children;

    @Min(0)
    private Integer infants;

    @Size(max = 5000)
    private String specialRequests;
}