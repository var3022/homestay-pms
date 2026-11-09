package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.ReservationRoomAddRequest;
import com.homestay.pms_backend.dto.response.ReservationRoomResponse;
import com.homestay.pms_backend.entity.Reservation;
import com.homestay.pms_backend.entity.ReservationRoom;
import com.homestay.pms_backend.entity.RoomType;
import com.homestay.pms_backend.enums.ReservationStatus;
import com.homestay.pms_backend.enums.RoomStatus;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.mapper.ReservationRoomMapper;
import com.homestay.pms_backend.repository.ReservationRepository;
import com.homestay.pms_backend.repository.ReservationRoomRepository;
import com.homestay.pms_backend.repository.RoomRepository;
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
    private final RoomRepository roomRepository;

    @Override
    public ReservationRoomResponse addReservationRoom(ReservationRoomAddRequest request) {

        Reservation reservation = reservationRepository.findById(request.getReservationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reservation not found with id: " + request.getReservationId()
                ));

        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            throw new BusinessValidationException("Cannot add rooms to a cancelled reservation");
        }
        if (reservation.getStatus() == ReservationStatus.CHECKED_IN) {
            throw new BusinessValidationException("Cannot add rooms to a checked-in reservation");
        }
        if (reservation.getStatus() == ReservationStatus.CHECKED_OUT) {
            throw new BusinessValidationException("Cannot add rooms to a checked-out reservation");
        }
        if (reservation.getStatus() == ReservationStatus.NO_SHOW) {
            throw new BusinessValidationException("Cannot add rooms to a no-show reservation");
        }

        RoomType roomType = roomTypeRepository.findById(request.getRoomTypeId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Room type not found with id: " + request.getRoomTypeId()
                ));

        if (!roomType.getPropertyId().equals(reservation.getPropertyId())) {
            throw new BusinessValidationException(
                    "The selected room type does not belong to the reservation property");
        }

        if (!roomType.isActive()) {
            throw new BusinessValidationException(
                    "The selected room type is inactive: " + roomType.getName());
        }

        long totalRooms = roomRepository.countByPropertyIdAndRoomTypeIdAndActiveTrueAndStatusNot(
                reservation.getPropertyId(),
                request.getRoomTypeId(),
                RoomStatus.OUT_OF_SERVICE);

        Long reservedQuantity = reservationRoomRepository.sumReservedQuantityForOverlappingDates(
                reservation.getPropertyId(),
                request.getRoomTypeId(),
                reservation.getCheckInDate(),
                reservation.getCheckOutDate(),
                List.of(
                        ReservationStatus.PENDING,
                        ReservationStatus.CONFIRMED,
                        ReservationStatus.CHECKED_IN
                ));

        long alreadyReserved = reservedQuantity == null ? 0L : reservedQuantity;
        long availableRooms = totalRooms - alreadyReserved;

        if (request.getQuantity() > availableRooms) {
            throw new BusinessValidationException(
                    "Insufficient availability for room type '"
                            + roomType.getName()
                            + "'. Requested: "
                            + request.getQuantity()
                            + ", available: "
                            + Math.max(availableRooms, 0L));
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