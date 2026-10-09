package com.homestay.pms_backend.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class ReservationRoomCreateRequest {

    @NotNull
    private UUID roomTypeId;

    @Min(1)
    private int quantity = 1;

    @NotNull
    @DecimalMin(value = "0.00")
    private BigDecimal unitPrice;
}