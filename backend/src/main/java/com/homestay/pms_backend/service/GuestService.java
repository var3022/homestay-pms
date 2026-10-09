package com.homestay.pms_backend.service;

import com.homestay.pms_backend.dto.request.GuestCreateRequest;
import com.homestay.pms_backend.dto.request.GuestUpdateRequest;
import com.homestay.pms_backend.dto.response.GuestResponse;

import java.util.List;
import java.util.UUID;

public interface GuestService {

    GuestResponse createGuest(GuestCreateRequest request);

    GuestResponse getGuestById(UUID id);

    List<GuestResponse> getAllGuests();

    GuestResponse updateGuest(UUID id, GuestUpdateRequest request);

    void deactivateGuest(UUID id);

    void activateGuest(UUID id);
}