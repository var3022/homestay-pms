package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.RoomBedCreateRequest;
import com.homestay.pms_backend.dto.request.RoomBedUpdateRequest;
import com.homestay.pms_backend.dto.response.RoomBedResponse;
import com.homestay.pms_backend.entity.Room;
import com.homestay.pms_backend.entity.RoomBed;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.DuplicateResourceException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.mapper.RoomBedMapper;
import com.homestay.pms_backend.repository.BedTypeRepository;
import com.homestay.pms_backend.repository.RoomBedRepository;
import com.homestay.pms_backend.repository.RoomRepository;
import com.homestay.pms_backend.service.RoomBedService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class RoomBedServiceImpl implements RoomBedService {

    private final RoomBedRepository roomBedRepository;
    private final RoomRepository roomRepository;
    private final BedTypeRepository bedTypeRepository;
    private final RoomBedMapper roomBedMapper;

    @Override
    public RoomBedResponse createRoomBed(
            RoomBedCreateRequest request) {

        Room room = getRoomEntity(request.getRoomId());

        validateBedTypeExists(request.getBedTypeId());

        validateDuplicateConfiguration(
                request.getRoomId(),
                request.getBedTypeId(),
                request.isExtraBed()
        );

        if (request.isExtraBed()) {
            validateExtraBedCapacity(
                    room,
                    request.getQuantity()
            );
        }

        RoomBed roomBed =
                roomBedMapper.toEntity(request);

        RoomBed savedRoomBed =
                roomBedRepository.saveAndFlush(roomBed);

        return roomBedMapper.toResponse(savedRoomBed);
    }

    @Override
    @Transactional(readOnly = true)
    public RoomBedResponse getRoomBedById(UUID id) {

        RoomBed roomBed =
                getRoomBedEntity(id);

        return roomBedMapper.toResponse(roomBed);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomBedResponse> getRoomBedsByRoomId(
            UUID roomId) {

        validateRoomExists(roomId);

        return roomBedRepository.findByRoomId(roomId)
                .stream()
                .map(roomBedMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomBedResponse> getRoomBedsByBedTypeId(
            UUID bedTypeId) {

        validateBedTypeExists(bedTypeId);

        return roomBedRepository.findByBedTypeId(bedTypeId)
                .stream()
                .map(roomBedMapper::toResponse)
                .toList();
    }

    @Override
    public RoomBedResponse updateRoomBed(
            UUID id,
            RoomBedUpdateRequest request) {

        RoomBed roomBed =
                getRoomBedEntity(id);

        Room room =
                getRoomEntity(roomBed.getRoomId());

        boolean finalExtraBed =
                request.getExtraBed() != null
                        ? request.getExtraBed()
                        : roomBed.isExtraBed();

        int finalQuantity =
                request.getQuantity() != null
                        ? request.getQuantity()
                        : roomBed.getQuantity();

        if (request.getExtraBed() != null
                && request.getExtraBed() != roomBed.isExtraBed()) {

            validateDuplicateConfiguration(
                    roomBed.getRoomId(),
                    roomBed.getBedTypeId(),
                    request.getExtraBed()
            );
        }

        if (finalExtraBed) {
            validateExtraBedCapacityForUpdate(
                    room,
                    roomBed,
                    finalQuantity
            );
        }

        roomBedMapper.updateEntity(
                roomBed,
                request
        );

        RoomBed updatedRoomBed =
                roomBedRepository.saveAndFlush(roomBed);

        return roomBedMapper.toResponse(updatedRoomBed);
    }

    @Override
    public void deleteRoomBed(UUID id) {

        RoomBed roomBed =
                getRoomBedEntity(id);

        roomBedRepository.delete(roomBed);
    }

    private RoomBed getRoomBedEntity(UUID id) {

        return roomBedRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Room bed configuration not found with id: "
                                        + id
                        )
                );
    }

    private Room getRoomEntity(UUID roomId) {

        return roomRepository.findById(roomId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Room not found with id: " + roomId
                        )
                );
    }

    private void validateRoomExists(UUID roomId) {

        if (!roomRepository.existsById(roomId)) {

            throw new ResourceNotFoundException(
                    "Room not found with id: " + roomId
            );
        }
    }

    private void validateBedTypeExists(UUID bedTypeId) {

        if (!bedTypeRepository.existsById(bedTypeId)) {

            throw new ResourceNotFoundException(
                    "Bed type not found with id: " + bedTypeId
            );
        }
    }

    private void validateDuplicateConfiguration(
            UUID roomId,
            UUID bedTypeId,
            boolean extraBed) {

        if (roomBedRepository
                .existsByRoomIdAndBedTypeIdAndExtraBed(
                        roomId,
                        bedTypeId,
                        extraBed
                )) {

            throw new DuplicateResourceException(
                    "This bed configuration already exists for the room"
            );
        }
    }

    private void validateExtraBedCapacity(
            Room room,
            int requestedQuantity) {

        int currentExtraBedQuantity =
                roomBedRepository
                        .sumExtraBedQuantityByRoomId(room.getId());

        int projectedExtraBedQuantity =
                currentExtraBedQuantity + requestedQuantity;

        if (projectedExtraBedQuantity > room.getMaxExtraBeds()) {

            throw new BusinessValidationException(
                    "Extra bed quantity exceeds the room's maximum allowed extra beds"
            );
        }
    }

    private void validateExtraBedCapacityForUpdate(
            Room room,
            RoomBed currentRoomBed,
            int requestedQuantity) {

        int currentExtraBedQuantity =
                roomBedRepository
                        .sumExtraBedQuantityByRoomId(room.getId());

        int currentConfigurationQuantity =
                currentRoomBed.isExtraBed()
                        ? currentRoomBed.getQuantity()
                        : 0;

        int projectedExtraBedQuantity =
                currentExtraBedQuantity
                        - currentConfigurationQuantity
                        + requestedQuantity;

        if (projectedExtraBedQuantity > room.getMaxExtraBeds()) {

            throw new BusinessValidationException(
                    "Extra bed quantity exceeds the room's maximum allowed extra beds"
            );
        }
    }
}