package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.exception.DuplicateResourceException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.dto.request.PropertyCreateRequest;
import com.homestay.pms_backend.dto.request.PropertyUpdateRequest;
import com.homestay.pms_backend.dto.response.PropertyResponse;
import com.homestay.pms_backend.entity.Property;
import com.homestay.pms_backend.mapper.PropertyMapper;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.service.PropertyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class PropertyServiceImpl implements PropertyService {

    private final PropertyRepository propertyRepository;
    private final PropertyMapper propertyMapper;

    @Override
    public PropertyResponse createProperty(PropertyCreateRequest request) {

        if (propertyRepository.existsByCode(request.getCode())) {
            throw new DuplicateResourceException(
                    "Property with code '" + request.getCode() + "' already exists"
            );
        }

        Property property = propertyMapper.toEntity(request);

        Property savedProperty = propertyRepository.saveAndFlush(property);

        return propertyMapper.toResponse(savedProperty);
    }

    @Override
    @Transactional(readOnly = true)
    public PropertyResponse getPropertyById(UUID id) {

        Property property = propertyRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Property not found with id: " + id
                        )
                );

        return propertyMapper.toResponse(property);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PropertyResponse> getAllProperties() {

        return propertyRepository.findAll()
                .stream()
                .map(propertyMapper::toResponse)
                .toList();
    }

    @Override
    public PropertyResponse updateProperty(
            UUID id,
            PropertyUpdateRequest request
    ) {

        Property property = propertyRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Property not found with id: " + id
                        )
                );

        if (request.getCode() != null
                && !request.getCode().equals(property.getCode())
                && propertyRepository.existsByCode(request.getCode())) {

            throw new DuplicateResourceException(
                    "Property with code '" + request.getCode() + "' already exists"
            );
        }

        propertyMapper.updateEntity(property, request);

        Property updatedProperty = propertyRepository.save(property);

        return propertyMapper.toResponse(updatedProperty);
    }

    @Override
    public void deactivateProperty(UUID id) {

        Property property = propertyRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Property not found with id: " + id
                        )
                );

        property.setActive(false);

        propertyRepository.save(property);
    }

    @Override
    public void activateProperty(UUID id) {
        Property property = propertyRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Property not found with id: " + id
                        )
                );

        property.setActive(true);

        propertyRepository.saveAndFlush(property);
    }
}