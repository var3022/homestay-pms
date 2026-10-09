package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.RoomAmenityCreateRequest;
import com.homestay.pms_backend.dto.response.RoomAmenityResponse;
import com.homestay.pms_backend.entity.Amenity;
import com.homestay.pms_backend.entity.Room;
import com.homestay.pms_backend.entity.RoomAmenity;
import com.homestay.pms_backend.entity.RoomAmenityId;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.DuplicateResourceException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.mapper.RoomAmenityMapper;
import com.homestay.pms_backend.repository.AmenityRepository;
import com.homestay.pms_backend.repository.RoomAmenityRepository;
import com.homestay.pms_backend.repository.RoomRepository;
import com.homestay.pms_backend.service.RoomAmenityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class RoomAmenityServiceImpl implements RoomAmenityService {

    private final RoomAmenityRepository roomAmenityRepository;
    private final RoomRepository roomRepository;
    private final AmenityRepository amenityRepository;
    private final RoomAmenityMapper roomAmenityMapper;

    @Override
    public RoomAmenityResponse addAmenityToRoom(
            RoomAmenityCreateRequest request) {

        Room room = roomRepository.findById(
                request.getRoomId()
        ).orElseThrow(() ->
                new ResourceNotFoundException(
                        "Room not found with id: "
                                + request.getRoomId()
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

        if (!room.isActive()) {
            throw new BusinessValidationException(
                    "Cannot add amenity to an inactive room"
            );
        }

        if (!amenity.isActive()) {
            throw new BusinessValidationException(
                    "Cannot assign an inactive amenity"
            );
        }

        if (roomAmenityRepository
                .existsByIdRoomIdAndIdAmenityId(
                        request.getRoomId(),
                        request.getAmenityId()
                )) {

            throw new DuplicateResourceException(
                    "Amenity is already assigned to this room"
            );
        }

        RoomAmenity roomAmenity =
                roomAmenityMapper.toEntity(request);

        RoomAmenity savedRoomAmenity =
                roomAmenityRepository.saveAndFlush(
                        roomAmenity
                );

        return roomAmenityMapper.toResponse(
                savedRoomAmenity
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomAmenityResponse> getAmenitiesByRoomId(
            UUID roomId) {

        if (!roomRepository.existsById(roomId)) {
            throw new ResourceNotFoundException(
                    "Room not found with id: " + roomId
            );
        }

        return roomAmenityRepository
                .findByIdRoomId(roomId)
                .stream()
                .map(roomAmenityMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomAmenityResponse> getRoomsByAmenityId(
            UUID amenityId) {

        if (!amenityRepository.existsById(amenityId)) {
            throw new ResourceNotFoundException(
                    "Amenity not found with id: " + amenityId
            );
        }

        return roomAmenityRepository
                .findByIdAmenityId(amenityId)
                .stream()
                .map(roomAmenityMapper::toResponse)
                .toList();
    }

    @Override
    public void removeAmenityFromRoom(
            UUID roomId,
            UUID amenityId) {

        RoomAmenityId id =
                new RoomAmenityId(
                        roomId,
                        amenityId
                );

        if (!roomAmenityRepository.existsById(id)) {
            throw new ResourceNotFoundException(
                    "Amenity is not assigned to this room"
            );
        }

        roomAmenityRepository.deleteById(id);
    }
}