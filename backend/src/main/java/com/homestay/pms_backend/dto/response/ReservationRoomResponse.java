package com.homestay.pms_backend.dto.response;

import com.homestay.pms_backend.enums.ReservationRoomStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class ReservationRoomResponse {

    private UUID id;
    private UUID reservationId;
    private UUID roomTypeId;

    private int quantity;
    private int cancelledQuantity;
    private int activeQuantity;

    private BigDecimal unitPrice;
    private BigDecimal subtotal;

    private ReservationRoomStatus status;

    private Instant createdAt;
    private Instant updatedAt;
}