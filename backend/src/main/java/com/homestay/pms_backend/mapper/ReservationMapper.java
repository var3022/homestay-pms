package com.homestay.pms_backend.mapper;

import com.homestay.pms_backend.dto.request.ReservationCreateRequest;
import com.homestay.pms_backend.dto.request.ReservationUpdateRequest;
import com.homestay.pms_backend.dto.response.ReservationResponse;
import com.homestay.pms_backend.entity.Reservation;
import com.homestay.pms_backend.enums.ReservationStatus;
import org.springframework.stereotype.Component;

@Component
public class ReservationMapper {

    public Reservation toEntity(ReservationCreateRequest request) {

        Reservation reservation = new Reservation();

        reservation.setPropertyId(request.getPropertyId());
        reservation.setPrimaryGuestId(request.getPrimaryGuestId());
        reservation.setCheckInDate(request.getCheckInDate());
        reservation.setCheckOutDate(request.getCheckOutDate());
        reservation.setAdults(request.getAdults());
        reservation.setChildren(request.getChildren());
        reservation.setInfants(request.getInfants());
        reservation.setBookingSourceId(request.getBookingSourceId());
        reservation.setExternalBookingId(request.getExternalBookingId());
        reservation.setSpecialRequests(request.getSpecialRequests());

        // New reservations start in PENDING state.
        reservation.setStatus(ReservationStatus.PENDING);

        return reservation;
    }

    public void updateEntity(
            Reservation reservation,
            ReservationUpdateRequest request) {

        if (request.getCheckInDate() != null) {
            reservation.setCheckInDate(request.getCheckInDate());
        }

        if (request.getCheckOutDate() != null) {
            reservation.setCheckOutDate(request.getCheckOutDate());
        }

        if (request.getAdults() != null) {
            reservation.setAdults(request.getAdults());
        }

        if (request.getChildren() != null) {
            reservation.setChildren(request.getChildren());
        }

        if (request.getInfants() != null) {
            reservation.setInfants(request.getInfants());
        }

        if (request.getSpecialRequests() != null) {
            reservation.setSpecialRequests(request.getSpecialRequests());
        }
    }

    public ReservationResponse toResponse(Reservation reservation) {

        ReservationResponse response = new ReservationResponse();

        response.setId(reservation.getId());
        response.setPropertyId(reservation.getPropertyId());
        response.setPrimaryGuestId(reservation.getPrimaryGuestId());
        response.setCheckInDate(reservation.getCheckInDate());
        response.setCheckOutDate(reservation.getCheckOutDate());
        response.setAdults(reservation.getAdults());
        response.setChildren(reservation.getChildren());
        response.setInfants(reservation.getInfants());
        response.setBookingSourceId(reservation.getBookingSourceId());
        response.setExternalBookingId(reservation.getExternalBookingId());
        response.setStatus(reservation.getStatus());
        response.setSpecialRequests(reservation.getSpecialRequests());
        response.setCreatedAt(reservation.getCreatedAt());
        response.setUpdatedAt(reservation.getUpdatedAt());

        return response;
    }
}