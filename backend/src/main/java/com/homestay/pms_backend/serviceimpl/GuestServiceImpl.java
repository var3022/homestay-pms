package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.GuestCreateRequest;
import com.homestay.pms_backend.dto.request.GuestUpdateRequest;
import com.homestay.pms_backend.dto.response.GuestResponse;
import com.homestay.pms_backend.entity.Guest;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.mapper.GuestMapper;
import com.homestay.pms_backend.repository.GuestRepository;
import com.homestay.pms_backend.service.GuestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class GuestServiceImpl implements GuestService {

    private final GuestRepository guestRepository;
    private final GuestMapper guestMapper;

    @Override
    public GuestResponse createGuest(GuestCreateRequest request) {
        Guest guest = guestMapper.toEntity(request);

        Guest savedGuest = guestRepository.saveAndFlush(guest);

        return guestMapper.toResponse(savedGuest);
    }

    @Override
    @Transactional(readOnly = true)
    public GuestResponse getGuestById(UUID id) {
        Guest guest = guestRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Guest not found with id: " + id
                        )
                );

        return guestMapper.toResponse(guest);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GuestResponse> getAllGuests() {
        return guestRepository.findAll()
                .stream()
                .map(guestMapper::toResponse)
                .toList();
    }

    @Override
    public GuestResponse updateGuest(
            UUID id,
            GuestUpdateRequest request) {

        Guest guest = guestRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Guest not found with id: " + id
                        )
                );

        guestMapper.updateEntity(guest, request);

        Guest updatedGuest = guestRepository.saveAndFlush(guest);

        return guestMapper.toResponse(updatedGuest);
    }

    @Override
    public void deactivateGuest(UUID id) {
        Guest guest = guestRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Guest not found with id: " + id
                        )
                );

        guest.setActive(false);

        guestRepository.saveAndFlush(guest);
    }

    @Override
    public void activateGuest(UUID id) {
        Guest guest = guestRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Guest not found with id: " + id
                        )
                );

        guest.setActive(true);

        guestRepository.saveAndFlush(guest);
    }
}