package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.RoomTypeCreateRequest;
import com.homestay.pms_backend.dto.request.RoomTypeUpdateRequest;
import com.homestay.pms_backend.dto.response.RoomTypeResponse;
import com.homestay.pms_backend.entity.RoomType;
import com.homestay.pms_backend.exception.DuplicateResourceException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.mapper.RoomTypeMapper;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.repository.RoomTypeRepository;
import com.homestay.pms_backend.service.RoomTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.homestay.pms_backend.exception.BusinessValidationException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class RoomTypeServiceImpl implements RoomTypeService {

    private final RoomTypeRepository roomTypeRepository;
    private final PropertyRepository propertyRepository;
    private final RoomTypeMapper roomTypeMapper;

    @Override
    public RoomTypeResponse createRoomType(RoomTypeCreateRequest request) {

        validatePropertyExists(request.getPropertyId());

        validateOccupancy(
                request.getBaseOccupancy(),
                request.getMaxOccupancy()
        );

        if (roomTypeRepository.existsByPropertyIdAndCode(
                request.getPropertyId(),
                request.getCode())) {

            throw new DuplicateResourceException(
                    "Room type with code '" + request.getCode()
                            + "' already exists for this property"
            );
        }

        RoomType roomType = roomTypeMapper.toEntity(request);

        RoomType savedRoomType = roomTypeRepository.saveAndFlush(roomType);

        return roomTypeMapper.toResponse(savedRoomType);
    }

    @Override
    @Transactional(readOnly = true)
    public RoomTypeResponse getRoomTypeById(UUID id) {

        RoomType roomType = roomTypeRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Room type not found with id: " + id
                        )
                );

        return roomTypeMapper.toResponse(roomType);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomTypeResponse> getRoomTypesByPropertyId(
            UUID propertyId) {

        validatePropertyExists(propertyId);

        return roomTypeRepository.findByPropertyId(propertyId)
                .stream()
                .map(roomTypeMapper::toResponse)
                .toList();
    }

    @Override
    public RoomTypeResponse updateRoomType(
            UUID id,
            RoomTypeUpdateRequest request) {

        RoomType roomType = roomTypeRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Room type not found with id: " + id
                        )
                );

        validateUpdatedOccupancy(roomType, request);

        if (request.getCode() != null
                && !request.getCode().equals(roomType.getCode())
                && roomTypeRepository.existsByPropertyIdAndCode(
                        roomType.getPropertyId(),
                        request.getCode())) {

            throw new DuplicateResourceException(
                    "Room type with code '" + request.getCode()
                            + "' already exists for this property"
            );
        }

        roomTypeMapper.updateEntity(roomType, request);

        RoomType updatedRoomType =
                roomTypeRepository.saveAndFlush(roomType);

        return roomTypeMapper.toResponse(updatedRoomType);
    }

    @Override
    public void deactivateRoomType(UUID id) {

        RoomType roomType = roomTypeRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Room type not found with id: " + id
                        )
                );

        roomType.setActive(false);

        roomTypeRepository.saveAndFlush(roomType);
    }

    private void validatePropertyExists(UUID propertyId) {

        if (!propertyRepository.existsById(propertyId)) {
            throw new ResourceNotFoundException(
                    "Property not found with id: " + propertyId
            );
        }
    }

    private void validateOccupancy(
            Integer baseOccupancy,
            Integer maxOccupancy) {

        if (maxOccupancy < baseOccupancy) {
            throw new BusinessValidationException(
                    "Maximum occupancy cannot be less than base occupancy"
            );
        }
    }

    private void validateUpdatedOccupancy(
            RoomType roomType,
            RoomTypeUpdateRequest request) {

        Integer baseOccupancy = request.getBaseOccupancy() != null
                ? request.getBaseOccupancy()
                : roomType.getBaseOccupancy();

        Integer maxOccupancy = request.getMaxOccupancy() != null
                ? request.getMaxOccupancy()
                : roomType.getMaxOccupancy();

        validateOccupancy(baseOccupancy, maxOccupancy);
    }
}