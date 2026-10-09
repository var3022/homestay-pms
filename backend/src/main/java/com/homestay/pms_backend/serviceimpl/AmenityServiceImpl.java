package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.AmenityCreateRequest;
import com.homestay.pms_backend.dto.request.AmenityUpdateRequest;
import com.homestay.pms_backend.dto.response.AmenityResponse;
import com.homestay.pms_backend.entity.Amenity;
import com.homestay.pms_backend.exception.DuplicateResourceException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.mapper.AmenityMapper;
import com.homestay.pms_backend.repository.AmenityRepository;
import com.homestay.pms_backend.service.AmenityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class AmenityServiceImpl implements AmenityService {

    private final AmenityRepository amenityRepository;
    private final AmenityMapper amenityMapper;

    @Override
    public AmenityResponse createAmenity(
            AmenityCreateRequest request) {

        validateCodeDoesNotExist(request.getCode());

        Amenity amenity =
                amenityMapper.toEntity(request);

        Amenity savedAmenity =
                amenityRepository.saveAndFlush(amenity);

        return amenityMapper.toResponse(savedAmenity);
    }

    @Override
    @Transactional(readOnly = true)
    public AmenityResponse getAmenityById(UUID id) {

        Amenity amenity =
                getAmenityEntity(id);

        return amenityMapper.toResponse(amenity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AmenityResponse> getAllAmenities() {

        return amenityRepository.findAll()
                .stream()
                .map(amenityMapper::toResponse)
                .toList();
    }

    @Override
    public AmenityResponse updateAmenity(
            UUID id,
            AmenityUpdateRequest request) {

        Amenity amenity =
                getAmenityEntity(id);

        amenityMapper.updateEntity(
                amenity,
                request
        );

        Amenity updatedAmenity =
                amenityRepository.saveAndFlush(amenity);

        return amenityMapper.toResponse(updatedAmenity);
    }

    private Amenity getAmenityEntity(UUID id) {

        return amenityRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Amenity not found with id: " + id
                        )
                );
    }

    private void validateCodeDoesNotExist(String code) {

        if (amenityRepository.existsByCode(code)) {

            throw new DuplicateResourceException(
                    "Amenity with code '" + code
                            + "' already exists"
            );
        }
    }

    @Override
    public void deactivateAmenity(UUID id) {

        Amenity amenity = getAmenityEntity(id);

        amenity.setActive(false);

        amenityRepository.saveAndFlush(amenity);
    }

    @Override
    public void activateAmenity(UUID id) {

        Amenity amenity = getAmenityEntity(id);

        amenity.setActive(true);

        amenityRepository.saveAndFlush(amenity);
    }
}