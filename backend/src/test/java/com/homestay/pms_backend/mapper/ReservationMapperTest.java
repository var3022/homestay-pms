package com.homestay.pms_backend.mapper;

import com.homestay.pms_backend.dto.response.ReservationResponse;
import com.homestay.pms_backend.entity.Reservation;
import com.homestay.pms_backend.enums.ReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationMapperTest {

    private final ReservationMapper reservationMapper = new ReservationMapper();

    @Test
    @DisplayName("toResponse should correctly map checkoutDue and checkoutDueAt when due")
    void toResponse_mapsCheckoutDueAndCheckoutDueAt_whenDue() {
        Reservation reservation = new Reservation();
        reservation.setId(UUID.randomUUID());
        reservation.setPropertyId(UUID.randomUUID());
        reservation.setPrimaryGuestId(UUID.randomUUID());
        reservation.setBookingSourceId(UUID.randomUUID());
        reservation.setCheckInDate(LocalDate.of(2026, 11, 10));
        reservation.setCheckOutDate(LocalDate.of(2026, 11, 12));
        reservation.setStatus(ReservationStatus.CHECKED_IN);
        reservation.setAdults(2);
        reservation.setChildren(1);
        reservation.setInfants(0);
        reservation.setSpecialRequests("Late arrival");

        Instant dueAt = Instant.parse("2026-11-12T06:30:00Z");
        reservation.setCheckoutDue(true);
        reservation.setCheckoutDueAt(dueAt);

        ReservationResponse response = reservationMapper.toResponse(reservation);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(reservation.getId());
        assertThat(response.isCheckoutDue()).isTrue();
        assertThat(response.getCheckoutDueAt()).isEqualTo(dueAt);
        assertThat(response.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
        assertThat(response.getAdults()).isEqualTo(2);
        assertThat(response.getChildren()).isEqualTo(1);
    }

    @Test
    @DisplayName("toResponse should correctly map checkoutDue=false and null checkoutDueAt")
    void toResponse_mapsCheckoutDueFalseAndNullDueAt() {
        Reservation reservation = new Reservation();
        reservation.setId(UUID.randomUUID());
        reservation.setPropertyId(UUID.randomUUID());
        reservation.setPrimaryGuestId(UUID.randomUUID());
        reservation.setBookingSourceId(UUID.randomUUID());
        reservation.setCheckInDate(LocalDate.of(2026, 11, 10));
        reservation.setCheckOutDate(LocalDate.of(2026, 11, 12));
        reservation.setStatus(ReservationStatus.PENDING);
        reservation.setCheckoutDue(false);
        reservation.setCheckoutDueAt(null);

        ReservationResponse response = reservationMapper.toResponse(reservation);

        assertThat(response).isNotNull();
        assertThat(response.isCheckoutDue()).isFalse();
        assertThat(response.getCheckoutDueAt()).isNull();
        assertThat(response.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }
}

