package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.ReservationCreateRequest;
import com.homestay.pms_backend.dto.request.ReservationUpdateRequest;
import com.homestay.pms_backend.dto.response.ReservationResponse;
import com.homestay.pms_backend.entity.Reservation;
import com.homestay.pms_backend.entity.ReservationRoom;
import com.homestay.pms_backend.entity.RoomAssignment;
import com.homestay.pms_backend.enums.ReservationRoomStatus;
import com.homestay.pms_backend.enums.ReservationStatus;
import com.homestay.pms_backend.enums.RoomAssignmentStatus;
import com.homestay.pms_backend.entity.BookingSource;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.DuplicateResourceException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.mapper.ReservationMapper;
import com.homestay.pms_backend.repository.BookingSourceRepository;
import com.homestay.pms_backend.repository.ReservationRepository;
import com.homestay.pms_backend.repository.GuestRepository;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.homestay.pms_backend.repository.ReservationRoomRepository;
import com.homestay.pms_backend.repository.RoomAssignmentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ReservationServiceImpl implements ReservationService {

    private final ReservationRepository reservationRepository;
    private final ReservationMapper reservationMapper;

    private final PropertyRepository propertyRepository;
    private final GuestRepository guestRepository;
    private final BookingSourceRepository bookingSourceRepository;

    private final ReservationRoomRepository reservationRoomRepository;
    private final RoomAssignmentRepository roomAssignmentRepository;

    @Override
    public ReservationResponse createReservation(
            ReservationCreateRequest request) {

        validateProperty(request.getPropertyId());
        validateGuest(request.getPrimaryGuestId());
        @SuppressWarnings("unused")
        BookingSource bookingSource =
                validateBookingSource(request.getBookingSourceId());

        validateExternalBookingId(
                request.getBookingSourceId(),
                request.getExternalBookingId());

        validateDates(
                request.getCheckInDate(),
                request.getCheckOutDate());

        Reservation reservation =
                reservationMapper.toEntity(request);

        Reservation savedReservation =
                reservationRepository.saveAndFlush(reservation);

        return reservationMapper.toResponse(savedReservation);
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationResponse getReservationById(UUID id) {

        Reservation reservation = reservationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reservation not found with id: " + id));

        return reservationMapper.toResponse(reservation);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReservationResponse> getAllReservations() {

        return reservationRepository.findAll()
                .stream()
                .map(reservationMapper::toResponse)
                .toList();
    }

    @Override
    public ReservationResponse updateReservation(
            UUID id,
            ReservationUpdateRequest request) {

        Reservation reservation = reservationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reservation not found with id: " + id));

        // Prevent updates to reservations that have reached a terminal state.
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
                throw new BusinessValidationException(
                        "Cancelled reservations cannot be modified");
        }

        if (reservation.getStatus() == ReservationStatus.CHECKED_IN) {
                throw new BusinessValidationException(
                        "Checked-in reservations cannot be modified through the generic update endpoint. "
                                        + "Use a dedicated stay modification workflow.");
        }

        if (reservation.getStatus() == ReservationStatus.CHECKED_OUT) {
                throw new BusinessValidationException(
                        "Checked-out reservations cannot be modified");
        }

        if (reservation.getStatus() == ReservationStatus.NO_SHOW) {
                throw new BusinessValidationException(
                        "No-show reservations cannot be modified");
        }

        // Apply the requested changes.
        // Resolve the booking source before allowing any changes.
        BookingSource bookingSource = bookingSourceRepository
                .findById(reservation.getBookingSourceId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Booking source not found with id: "
                                + reservation.getBookingSourceId()));

        // OTA reservations must be modified through an OTA-confirmed workflow.
        if (!"DIRECT".equals(bookingSource.getCode())) {
        throw new BusinessValidationException(
                "OTA reservations cannot be modified through the generic update endpoint. "
                        + "Changes must be confirmed by the booking platform.");
        }

        // Apply changes only to eligible direct reservations.
        reservationMapper.updateEntity(reservation, request);

        // Validate the resulting dates.
        validateDates(
                reservation.getCheckInDate(),
                reservation.getCheckOutDate());

        Reservation updatedReservation =
                reservationRepository.saveAndFlush(reservation);

        return reservationMapper.toResponse(updatedReservation);
    }

    private void validateProperty(UUID propertyId) {

        propertyRepository.findById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Property not found with id: " + propertyId));
    }

    private void validateGuest(UUID guestId) {

        guestRepository.findById(guestId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Guest not found with id: " + guestId));
    }

    private BookingSource validateBookingSource(UUID bookingSourceId) {

        BookingSource bookingSource =
                bookingSourceRepository.findById(bookingSourceId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Booking source not found with id: "
                                        + bookingSourceId));

        if (!bookingSource.isActive()) {
            throw new BusinessValidationException(
                    "Booking source is inactive: "
                            + bookingSource.getCode());
        }

        return bookingSource;
    }

    private void validateExternalBookingId(
            UUID bookingSourceId,
            String externalBookingId) {

        if (externalBookingId == null
                || externalBookingId.isBlank()) {
            return;
        }

        reservationRepository
                .findByBookingSourceIdAndExternalBookingId(
                        bookingSourceId,
                        externalBookingId)
                .ifPresent(existingReservation -> {
                    throw new DuplicateResourceException(
                            "Reservation already exists for booking source "
                                    + bookingSourceId
                                    + " and external booking id "
                                    + externalBookingId);
                });
    }

    private void validateDates(
            java.time.LocalDate checkInDate,
            java.time.LocalDate checkOutDate) {

        if (!checkOutDate.isAfter(checkInDate)) {
            throw new BusinessValidationException(
                    "Check-out date must be after check-in date.");
        }
    }

    @Override
    public ReservationResponse cancelReservation(UUID id) {

        Reservation reservation = reservationRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Reservation not found with id: " + id
                        ));

        BookingSource bookingSource = bookingSourceRepository
                .findById(reservation.getBookingSourceId())
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Booking source not found with id: "
                                        + reservation.getBookingSourceId()
                        ));

        if (!"DIRECT".equals(bookingSource.getCode())) {
            throw new BusinessValidationException(
                    "OTA bookings cannot be cancelled locally. "
                            + "Cancellation must be confirmed by the booking platform."
            );
        }

        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            throw new BusinessValidationException(
                    "Reservation is already cancelled"
            );
        }

        if (reservation.getStatus() == ReservationStatus.CHECKED_IN
                || reservation.getStatus() == ReservationStatus.CHECKED_OUT
                || reservation.getStatus() == ReservationStatus.NO_SHOW) {
                throw new BusinessValidationException(
                        "Reservation cannot be cancelled from status: "
                                + reservation.getStatus()
                );
        }

        List<ReservationRoom> reservationRooms =
                reservationRoomRepository.findByReservationId(id);

        List<ReservationRoom> roomsToSave = new ArrayList<>();
        List<RoomAssignment> assignmentsToSave = new ArrayList<>();

        for (ReservationRoom reservationRoom : reservationRooms) {

                int activeQuantity =
                        reservationRoom.getQuantity()
                                - reservationRoom.getCancelledQuantity();

                if (activeQuantity <= 0) {
                continue;
                }

                reservationRoom.setCancelledQuantity(
                        reservationRoom.getQuantity()
                );
                reservationRoom.setStatus(ReservationRoomStatus.CANCELLED);
                roomsToSave.add(reservationRoom);

                List<RoomAssignment> assignments =
                        roomAssignmentRepository.findByReservationRoomId(
                                reservationRoom.getId()
                        );

                for (RoomAssignment assignment : assignments) {
                if (assignment.getStatus() == RoomAssignmentStatus.ASSIGNED) {
                        assignment.setStatus(RoomAssignmentStatus.RELEASED);
                        assignmentsToSave.add(assignment);
                }
                }
        }

        reservationRoomRepository.saveAll(roomsToSave);
        roomAssignmentRepository.saveAll(assignmentsToSave);

        reservation.setStatus(ReservationStatus.CANCELLED);

        Reservation savedReservation =
                reservationRepository.saveAndFlush(reservation);

        return reservationMapper.toResponse(savedReservation);
    }
}