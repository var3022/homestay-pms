package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.ReservationRoomAddRequest;
import com.homestay.pms_backend.dto.response.ReservationRoomResponse;
import com.homestay.pms_backend.entity.ReservationRoom;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.mapper.ReservationRoomMapper;
import com.homestay.pms_backend.repository.ReservationRepository;
import com.homestay.pms_backend.repository.ReservationRoomRepository;
import com.homestay.pms_backend.repository.RoomTypeRepository;
import com.homestay.pms_backend.service.ReservationRoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ReservationRoomServiceImpl implements ReservationRoomService {

    private final ReservationRoomRepository reservationRoomRepository;
    private final ReservationRoomMapper reservationRoomMapper;
    private final ReservationRepository reservationRepository;
    private final RoomTypeRepository roomTypeRepository;

    @Override
    public ReservationRoomResponse addReservationRoom(ReservationRoomAddRequest request) {

        if (!reservationRepository.existsById(request.getReservationId())) {
                throw new ResourceNotFoundException(
                        "Reservation not found with id: " + request.getReservationId()
                );
        }

        if (!roomTypeRepository.existsById(request.getRoomTypeId())) {
                throw new ResourceNotFoundException(
                        "Room type not found with id: " + request.getRoomTypeId()
                );
        }

        ReservationRoom reservationRoom =
                reservationRoomMapper.toEntity(request);

        ReservationRoom savedReservationRoom =
                reservationRoomRepository.saveAndFlush(reservationRoom);

        return reservationRoomMapper.toResponse(savedReservationRoom);
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationRoomResponse getReservationRoomById(UUID id) {

        ReservationRoom reservationRoom =
                reservationRoomRepository.findById(id)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Reservation room not found with id: "
                                                + id
                                ));

        return reservationRoomMapper.toResponse(reservationRoom);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReservationRoomResponse> getReservationRoomsByReservationId(
            UUID reservationId) {

        if (!reservationRepository.existsById(reservationId)) {
            throw new ResourceNotFoundException(
                    "Reservation not found with id: " + reservationId
            );
        }

        return reservationRoomRepository
                .findByReservationId(reservationId)
                .stream()
                .map(reservationRoomMapper::toResponse)
                .toList();
    }
}