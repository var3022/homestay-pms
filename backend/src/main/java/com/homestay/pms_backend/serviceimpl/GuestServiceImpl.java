package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.GuestCreateRequest;
import com.homestay.pms_backend.dto.request.GuestUpdateRequest;
import com.homestay.pms_backend.dto.response.GuestResponse;
import com.homestay.pms_backend.entity.Guest;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.mapper.GuestMapper;
import com.homestay.pms_backend.repository.GuestRepository;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.security.authorization.GuestAuthorizationGuard;
import com.homestay.pms_backend.security.authorization.StaffOperation;
import com.homestay.pms_backend.service.GuestPropertyAssociationService;
import com.homestay.pms_backend.service.GuestService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class GuestServiceImpl implements GuestService {

    private static final Logger log = LoggerFactory.getLogger(GuestServiceImpl.class);

    private final GuestRepository guestRepository;
    private final GuestMapper guestMapper;
    private final PropertyRepository propertyRepository;
    private final GuestAuthorizationGuard authorizationGuard;
    private final GuestPropertyAssociationService associationService;

    @Override
    public GuestResponse createGuest(GuestCreateRequest request) {
        if (authorizationGuard.isEnforcementEnabled()) {
            authorizationGuard.validateGuestRegistration(request.getPropertyId());
            // Reject client-supplied document storage keys under enforcement
            request.setIdDocumentImageKey(null);
        } else if (request.getPropertyId() != null) {
            if (!propertyRepository.existsById(request.getPropertyId())) {
                throw new ResourceNotFoundException("Property not found with id: " + request.getPropertyId());
            }
        }

        Guest guest = guestMapper.toEntity(request);
        Guest savedGuest = guestRepository.saveAndFlush(guest);

        if (request.getPropertyId() != null) {
            associationService.createRegistrationAssociation(savedGuest.getId(), request.getPropertyId());
        } else {
            log.info("Development mode: created legacy guest [{}] without property association", savedGuest.getId());
        }

        return guestMapper.toResponse(savedGuest);
    }

    @Override
    @Transactional(readOnly = true)
    public GuestResponse getGuestById(UUID id) {
        authorizationGuard.requireGuestAccess(id, StaffOperation.GUEST_READ);

        Guest guest = guestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Guest not found with id: " + id));

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
    @Transactional(readOnly = true)
    public List<GuestResponse> getAllGuestsPaged(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return guestRepository.findAllPaged(pageable)
                .stream()
                .map(guestMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<GuestResponse> getGuestsByPropertyId(UUID propertyId, int page, int size) {
        if (propertyId == null) {
            return List.of();
        }
        Pageable pageable = PageRequest.of(page, size);
        return guestRepository.findByPropertyId(propertyId, pageable)
                .stream()
                .map(guestMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<GuestResponse> getGuestsByPropertyIds(Collection<UUID> propertyIds, int page, int size) {
        if (propertyIds == null || propertyIds.isEmpty()) {
            return List.of();
        }
        Pageable pageable = PageRequest.of(page, size);
        return guestRepository.findByPropertyIdIn(propertyIds, pageable)
                .stream()
                .map(guestMapper::toResponse)
                .toList();
    }

    @Override
    public GuestResponse updateGuest(UUID id, GuestUpdateRequest request) {
        Guest guest = guestRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Guest not found with id: " + id));

        authorizationGuard.requireGuestUpdateAccess(id);

        if (authorizationGuard.isEnforcementEnabled()) {
            request.setIdDocumentImageKey(null);
        }

        guestMapper.updateEntity(guest, request);

        Guest updatedGuest = guestRepository.saveAndFlush(guest);

        return guestMapper.toResponse(updatedGuest);
    }

    @Override
    public void deactivateGuest(UUID id) {
        authorizationGuard.requireGlobalSuperAdmin("deactivate-guest");

        Guest guest = guestRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Guest not found with id: " + id));

        guest.setActive(false);
        guestRepository.saveAndFlush(guest);
    }

    @Override
    public void activateGuest(UUID id) {
        authorizationGuard.requireGlobalSuperAdmin("activate-guest");

        Guest guest = guestRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Guest not found with id: " + id));

        guest.setActive(true);
        guestRepository.saveAndFlush(guest);
    }
}
