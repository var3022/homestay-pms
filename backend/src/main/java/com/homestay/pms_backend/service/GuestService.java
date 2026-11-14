package com.homestay.pms_backend.service;

import com.homestay.pms_backend.dto.request.GuestCreateRequest;
import com.homestay.pms_backend.dto.request.GuestUpdateRequest;
import com.homestay.pms_backend.dto.response.GuestResponse;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface GuestService {

    GuestResponse createGuest(GuestCreateRequest request);

    GuestResponse getGuestById(UUID id);

    List<GuestResponse> getAllGuests();

    List<GuestResponse> getAllGuestsPaged(int page, int size);

    List<GuestResponse> getGuestsByPropertyId(UUID propertyId, int page, int size);

    List<GuestResponse> getGuestsByPropertyIds(Collection<UUID> propertyIds, int page, int size);

    GuestResponse updateGuest(UUID id, GuestUpdateRequest request);

    void deactivateGuest(UUID id);

    void activateGuest(UUID id);
}