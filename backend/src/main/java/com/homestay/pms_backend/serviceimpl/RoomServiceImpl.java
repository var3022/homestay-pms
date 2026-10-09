package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.RoomCreateRequest;
import com.homestay.pms_backend.dto.request.RoomStatusUpdateRequest;
import com.homestay.pms_backend.dto.request.RoomUpdateRequest;
import com.homestay.pms_backend.dto.response.RoomResponse;
import com.homestay.pms_backend.entity.Room;
import com.homestay.pms_backend.entity.RoomType;
import com.homestay.pms_backend.enums.RoomStatus;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.DuplicateResourceException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.mapper.RoomMapper;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.repository.RoomRepository;
import com.homestay.pms_backend.repository.RoomTypeRepository;
import com.homestay.pms_backend.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class RoomServiceImpl implements RoomService {

    private final RoomRepository roomRepository;
    private final PropertyRepository propertyRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final RoomMapper roomMapper;

    @Override
    public RoomResponse createRoom(RoomCreateRequest request) {

        validatePropertyExists(request.getPropertyId());

        validateRoomTypeBelongsToProperty(
                request.getRoomTypeId(),
                request.getPropertyId()
        );

        validateOccupancy(
                request.getBaseOccupancy(),
                request.getMaxOccupancy()
        );

        validateRoomNumberDoesNotExist(
                request.getPropertyId(),
                request.getRoomNumber()
        );

        Room room = roomMapper.toEntity(request);

        Room savedRoom = roomRepository.saveAndFlush(room);

        return roomMapper.toResponse(savedRoom);
    }

    @Override
    @Transactional(readOnly = true)
    public RoomResponse getRoomById(UUID id) {

        Room room = roomRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Room not found with id: " + id
                        )
                );

        return roomMapper.toResponse(room);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomResponse> getRoomsByPropertyId(
            UUID propertyId) {

        validatePropertyExists(propertyId);

        return roomRepository.findByPropertyId(propertyId)
                .stream()
                .map(roomMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomResponse> getRoomsByRoomTypeId(
            UUID roomTypeId) {

        validateRoomTypeExists(roomTypeId);

        return roomRepository.findByRoomTypeId(roomTypeId)
                .stream()
                .map(roomMapper::toResponse)
                .toList();
    }

    @Override
    public RoomResponse updateRoom(
            UUID id,
            RoomUpdateRequest request) {

        Room room = roomRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Room not found with id: " + id
                        )
                );

        validateUpdatedOccupancy(room, request);

        if (request.getRoomNumber() != null
                && !request.getRoomNumber()
                .equals(room.getRoomNumber())) {

            validateRoomNumberDoesNotExist(
                    room.getPropertyId(),
                    request.getRoomNumber()
            );
        }

        roomMapper.updateEntity(room, request);

        Room updatedRoom = roomRepository.saveAndFlush(room);

        return roomMapper.toResponse(updatedRoom);
    }

    @Override
    public void activateRoom(UUID id) {

        Room room = roomRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Room not found with id: " + id
                        )
                );

        room.setActive(true);

        roomRepository.saveAndFlush(room);
    }

    @Override
    public void deactivateRoom(UUID id) {

        Room room = roomRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Room not found with id: " + id
                        )
                );

        room.setActive(false);

        roomRepository.saveAndFlush(room);
    }

    private void validatePropertyExists(UUID propertyId) {

        if (!propertyRepository.existsById(propertyId)) {
            throw new ResourceNotFoundException(
                    "Property not found with id: " + propertyId
            );
        }
    }

    private void validateRoomTypeExists(UUID roomTypeId) {

        if (!roomTypeRepository.existsById(roomTypeId)) {
            throw new ResourceNotFoundException(
                    "Room type not found with id: " + roomTypeId
            );
        }
    }

    private void validateRoomTypeBelongsToProperty(
            UUID roomTypeId,
            UUID propertyId) {

        RoomType roomType = roomTypeRepository.findById(roomTypeId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Room type not found with id: " + roomTypeId
                        )
                );

        if (!roomType.getPropertyId().equals(propertyId)) {
            throw new BusinessValidationException(
                    "Room type does not belong to the specified property"
            );
        }

        if (!roomType.isActive()) {
        throw new BusinessValidationException(
                "Room type is inactive and cannot be used for new rooms"
        );
    }
    }

    private void validateRoomNumberDoesNotExist(
            UUID propertyId,
            String roomNumber) {

        if (roomRepository.existsByPropertyIdAndRoomNumber(
                propertyId,
                roomNumber)) {

            throw new DuplicateResourceException(
                    "Room with number '" + roomNumber
                            + "' already exists for this property"
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
            Room room,
            RoomUpdateRequest request) {

        Integer baseOccupancy = request.getBaseOccupancy() != null
                ? request.getBaseOccupancy()
                : room.getBaseOccupancy();

        Integer maxOccupancy = request.getMaxOccupancy() != null
                ? request.getMaxOccupancy()
                : room.getMaxOccupancy();

        validateOccupancy(baseOccupancy, maxOccupancy);
    }

    @Override
    public RoomResponse updateRoomStatus(
            UUID id,
            RoomStatusUpdateRequest request) {

        Room room = roomRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Room not found with id: " + id
                        ));

        RoomStatus newStatus = request.getStatus();

        if (room.getStatus() == newStatus) {
                throw new BusinessValidationException(
                        "Room is already in status: " + newStatus
                );
        }

        room.setStatus(newStatus);

        Room savedRoom = roomRepository.saveAndFlush(room);

        return roomMapper.toResponse(savedRoom);
    }
}