package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.PropertyAmenityCreateRequest;
import com.homestay.pms_backend.dto.response.PropertyAmenityResponse;
import com.homestay.pms_backend.entity.Amenity;
import com.homestay.pms_backend.entity.PropertyAmenity;
import com.homestay.pms_backend.entity.PropertyAmenityId;
import com.homestay.pms_backend.entity.Property;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.DuplicateResourceException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.mapper.PropertyAmenityMapper;
import com.homestay.pms_backend.repository.AmenityRepository;
import com.homestay.pms_backend.repository.PropertyAmenityRepository;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.service.PropertyAmenityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class PropertyAmenityServiceImpl
        implements PropertyAmenityService {

    private final PropertyAmenityRepository propertyAmenityRepository;
    private final PropertyRepository propertyRepository;
    private final AmenityRepository amenityRepository;
    private final PropertyAmenityMapper propertyAmenityMapper;

    @Override
    public PropertyAmenityResponse addAmenityToProperty(
            PropertyAmenityCreateRequest request) {

        Property property = propertyRepository.findById(
                request.getPropertyId()
        ).orElseThrow(() ->
                new ResourceNotFoundException(
                        "Property not found with id: "
                                + request.getPropertyId()
                )
        );

        Amenity amenity = amenityRepository.findById(
                request.getAmenityId()
        ).orElseThrow(() ->
                new ResourceNotFoundException(
                        "Amenity not found with id: "
                                + request.getAmenityId()
                )
        );

        if (!property.isActive()) {
            throw new BusinessValidationException(
                    "Cannot add amenity to an inactive property"
            );
        }

        if (!amenity.isActive()) {
            throw new BusinessValidationException(
                    "Cannot assign an inactive amenity"
            );
        }

        if (propertyAmenityRepository
                .existsByIdPropertyIdAndIdAmenityId(
                        request.getPropertyId(),
                        request.getAmenityId()
                )) {

            throw new DuplicateResourceException(
                    "Amenity is already assigned to this property"
            );
        }

        PropertyAmenity propertyAmenity =
                propertyAmenityMapper.toEntity(request);

        PropertyAmenity savedPropertyAmenity =
                propertyAmenityRepository.saveAndFlush(
                        propertyAmenity
                );

        return propertyAmenityMapper.toResponse(
                savedPropertyAmenity
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<PropertyAmenityResponse> getAmenitiesByPropertyId(
            UUID propertyId) {

        if (!propertyRepository.existsById(propertyId)) {
            throw new ResourceNotFoundException(
                    "Property not found with id: " + propertyId
            );
        }

        return propertyAmenityRepository
                .findByIdPropertyId(propertyId)
                .stream()
                .map(propertyAmenityMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PropertyAmenityResponse> getPropertiesByAmenityId(
            UUID amenityId) {

        if (!amenityRepository.existsById(amenityId)) {
            throw new ResourceNotFoundException(
                    "Amenity not found with id: " + amenityId
            );
        }

        return propertyAmenityRepository
                .findByIdAmenityId(amenityId)
                .stream()
                .map(propertyAmenityMapper::toResponse)
                .toList();
    }

    @Override
    public void removeAmenityFromProperty(
            UUID propertyId,
            UUID amenityId) {

        PropertyAmenityId id =
                new PropertyAmenityId(
                        propertyId,
                        amenityId
                );

        if (!propertyAmenityRepository.existsById(id)) {
            throw new ResourceNotFoundException(
                    "Amenity is not assigned to this property"
            );
        }

        propertyAmenityRepository.deleteById(id);
    }
}