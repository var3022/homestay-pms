package com.homestay.pms_backend.service;

import com.homestay.pms_backend.dto.request.ReservationCreateRequest;
import com.homestay.pms_backend.dto.request.ReservationUpdateRequest;
import com.homestay.pms_backend.dto.response.ReservationResponse;

import java.util.List;
import java.util.UUID;

public interface ReservationService {

    ReservationResponse createReservation(
            ReservationCreateRequest request);

    ReservationResponse getReservationById(UUID id);

    List<ReservationResponse> getAllReservations();

    ReservationResponse updateReservation(
            UUID id,
            ReservationUpdateRequest request);

    ReservationResponse cancelReservation(UUID id);
}