package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.IdempotentResult;
import com.homestay.pms_backend.dto.request.ReservationCreateRequest;
import com.homestay.pms_backend.dto.request.ReservationUpdateRequest;
import com.homestay.pms_backend.dto.response.ReservationResponse;
import com.homestay.pms_backend.entity.Reservation;
import com.homestay.pms_backend.entity.ReservationRoom;
import com.homestay.pms_backend.entity.RoomAssignment;
import com.homestay.pms_backend.enums.HousekeepingStatus;
import com.homestay.pms_backend.enums.IdempotencyOperation;
import com.homestay.pms_backend.enums.ReservationRoomStatus;
import com.homestay.pms_backend.enums.ReservationStatus;
import com.homestay.pms_backend.enums.RoomAssignmentStatus;
import com.homestay.pms_backend.entity.BookingSource;
import com.homestay.pms_backend.entity.Guest;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.exception.DuplicateResourceException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.mapper.ReservationMapper;
import com.homestay.pms_backend.mapper.ReservationRoomMapper;
import com.homestay.pms_backend.repository.BookingSourceRepository;
import com.homestay.pms_backend.repository.ReservationRepository;
import com.homestay.pms_backend.repository.GuestRepository;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.service.ClaimOutcome;
import com.homestay.pms_backend.service.GuestPropertyAssociationService;
import com.homestay.pms_backend.service.IdempotencyFingerprintService;
import com.homestay.pms_backend.service.IdempotencyService;
import com.homestay.pms_backend.service.ReservationPricingService;
import com.homestay.pms_backend.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.homestay.pms_backend.repository.ReservationRoomRepository;
import com.homestay.pms_backend.repository.RoomAssignmentRepository;
import com.homestay.pms_backend.repository.RoomRepository;
import com.homestay.pms_backend.repository.RoomTypeRepository;
import com.homestay.pms_backend.entity.Property;
import com.homestay.pms_backend.entity.Room;
import com.homestay.pms_backend.entity.RoomType;
import com.homestay.pms_backend.enums.RoomStatus;
import com.homestay.pms_backend.config.SecurityEnforcementProperties;
import com.homestay.pms_backend.security.AuthenticatedStaffPrincipal;
import com.homestay.pms_backend.security.authorization.StaffAuthorizationService;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class ReservationServiceImpl implements ReservationService {

        private final ReservationRepository reservationRepository;
        private final ReservationMapper reservationMapper;

        private final PropertyRepository propertyRepository;
        private final GuestRepository guestRepository;
        private final BookingSourceRepository bookingSourceRepository;

        private final ReservationRoomRepository reservationRoomRepository;
        private final RoomAssignmentRepository roomAssignmentRepository;

        private final RoomRepository roomRepository;
        private final RoomTypeRepository roomTypeRepository;
        private final ReservationRoomMapper reservationRoomMapper;
        private final GuestPropertyAssociationService guestPropertyAssociationService;
        private final EntityManager entityManager;
        private final Clock clock;
        private final StaffAuthorizationService staffAuthorizationService;
        private final SecurityEnforcementProperties securityEnforcementProperties;
        private final IdempotencyService idempotencyService;
        private final IdempotencyFingerprintService idempotencyFingerprintService;
        private final ReservationPricingService reservationPricingService;

        @Autowired
        public ReservationServiceImpl(
                        ReservationRepository reservationRepository,
                        ReservationMapper reservationMapper,
                        PropertyRepository propertyRepository,
                        GuestRepository guestRepository,
                        BookingSourceRepository bookingSourceRepository,
                        ReservationRoomRepository reservationRoomRepository,
                        RoomAssignmentRepository roomAssignmentRepository,
                        RoomRepository roomRepository,
                        RoomTypeRepository roomTypeRepository,
                        ReservationRoomMapper reservationRoomMapper,
                        GuestPropertyAssociationService guestPropertyAssociationService,
                        EntityManager entityManager,
                        @Autowired(required = false) Clock clock,
                        @Autowired(required = false) StaffAuthorizationService staffAuthorizationService,
                        @Autowired(required = false) SecurityEnforcementProperties securityEnforcementProperties,
                        @Autowired(required = false) IdempotencyService idempotencyService,
                        @Autowired(required = false) IdempotencyFingerprintService idempotencyFingerprintService,
                        @Autowired(required = false) ReservationPricingService reservationPricingService) {
                this.reservationRepository = reservationRepository;
                this.reservationMapper = reservationMapper;
                this.propertyRepository = propertyRepository;
                this.guestRepository = guestRepository;
                this.bookingSourceRepository = bookingSourceRepository;
                this.reservationRoomRepository = reservationRoomRepository;
                this.roomAssignmentRepository = roomAssignmentRepository;
                this.roomRepository = roomRepository;
                this.roomTypeRepository = roomTypeRepository;
                this.reservationRoomMapper = reservationRoomMapper;
                this.guestPropertyAssociationService = guestPropertyAssociationService;
                this.entityManager = entityManager;
                this.clock = clock != null ? clock : Clock.systemUTC();
                this.staffAuthorizationService = staffAuthorizationService;
                this.securityEnforcementProperties = securityEnforcementProperties != null
                                ? securityEnforcementProperties
                                : new SecurityEnforcementProperties();
                this.idempotencyService = idempotencyService;
                this.idempotencyFingerprintService = idempotencyFingerprintService;
                this.reservationPricingService = reservationPricingService != null
                                ? reservationPricingService
                                : new ReservationPricingService();
        }

        public ReservationServiceImpl(
                        ReservationRepository reservationRepository,
                        ReservationMapper reservationMapper,
                        PropertyRepository propertyRepository,
                        GuestRepository guestRepository,
                        BookingSourceRepository bookingSourceRepository,
                        ReservationRoomRepository reservationRoomRepository,
                        RoomAssignmentRepository roomAssignmentRepository,
                        RoomRepository roomRepository,
                        RoomTypeRepository roomTypeRepository,
                        ReservationRoomMapper reservationRoomMapper,
                        GuestPropertyAssociationService guestPropertyAssociationService,
                        EntityManager entityManager,
                        Clock clock,
                        StaffAuthorizationService staffAuthorizationService,
                        SecurityEnforcementProperties securityEnforcementProperties,
                        IdempotencyService idempotencyService,
                        IdempotencyFingerprintService idempotencyFingerprintService) {
                this(reservationRepository, reservationMapper, propertyRepository, guestRepository,
                                bookingSourceRepository, reservationRoomRepository, roomAssignmentRepository,
                                roomRepository, roomTypeRepository, reservationRoomMapper,
                                guestPropertyAssociationService, entityManager, clock,
                                staffAuthorizationService, securityEnforcementProperties,
                                idempotencyService, idempotencyFingerprintService, null);
        }

        public ReservationServiceImpl(
                        ReservationRepository reservationRepository,
                        ReservationMapper reservationMapper,
                        PropertyRepository propertyRepository,
                        GuestRepository guestRepository,
                        BookingSourceRepository bookingSourceRepository,
                        ReservationRoomRepository reservationRoomRepository,
                        RoomAssignmentRepository roomAssignmentRepository,
                        RoomRepository roomRepository,
                        RoomTypeRepository roomTypeRepository,
                        ReservationRoomMapper reservationRoomMapper,
                        GuestPropertyAssociationService guestPropertyAssociationService,
                        EntityManager entityManager,
                        Clock clock,
                        StaffAuthorizationService staffAuthorizationService,
                        SecurityEnforcementProperties securityEnforcementProperties) {
                this(reservationRepository, reservationMapper, propertyRepository, guestRepository,
                                bookingSourceRepository, reservationRoomRepository, roomAssignmentRepository,
                                roomRepository, roomTypeRepository, reservationRoomMapper,
                                guestPropertyAssociationService, entityManager, clock,
                                staffAuthorizationService, securityEnforcementProperties, null, null, null);
        }

        @Override
        public ReservationResponse createReservation(
                        ReservationCreateRequest request) {
                return createReservationIdempotent(request, null).data();
        }

        @Override
        public IdempotentResult<ReservationResponse> createReservationIdempotent(
                        ReservationCreateRequest request,
                        String idempotencyKey) {

                if (idempotencyKey == null) {
                        return new IdempotentResult<>(executeCreateReservation(request), false, null);
                }

                if (idempotencyService != null) {
                        idempotencyService.validateKeyHeader(idempotencyKey);
                }

                validateProperty(request.getPropertyId());
                validateGuest(request.getPrimaryGuestId());
                guestPropertyAssociationService.validateExistingGuestUsableAtProperty(
                                request.getPrimaryGuestId(), request.getPropertyId());
                @SuppressWarnings("unused")
                BookingSource bookingSource = validateBookingSource(request.getBookingSourceId());

                validateExternalBookingId(
                                request.getBookingSourceId(),
                                request.getExternalBookingId());

                validateDates(
                        request.getCheckInDate(),
                        request.getCheckOutDate());

                if (idempotencyService == null || idempotencyFingerprintService == null) {
                        return new IdempotentResult<>(executeCreateReservation(request), false, idempotencyKey);
                }

                String fingerprint = idempotencyFingerprintService.generateReservationFingerprint(request);
                UUID principalId = idempotencyService.resolvePrincipalId();

                ClaimOutcome outcome = idempotencyService.claimKey(
                                request.getPropertyId(),
                                IdempotencyOperation.CREATE_RESERVATION,
                                principalId,
                                idempotencyKey,
                                fingerprint);

                if (outcome instanceof ClaimOutcome.Replay replay) {
                        ReservationResponse existing = getReservationById(replay.resourceId());
                        if (!existing.getPropertyId().equals(request.getPropertyId())) {
                                throw new BusinessValidationException("Replayed reservation does not belong to the target property.");
                        }
                        return new IdempotentResult<>(existing, true, idempotencyKey);
                }

                ClaimOutcome.Claimed claimed = (ClaimOutcome.Claimed) outcome;

                lockRoomTypesInDeterministicOrder(
                                request.getReservationRooms().stream()
                                                .map(r -> r.getRoomTypeId())
                                                .toList());

                validateRequestedRoomTypesAndAvailability(request);

                Reservation reservation = reservationMapper.toEntity(request);
                Reservation savedReservation = reservationRepository.saveAndFlush(reservation);

                List<ReservationRoom> reservationRoomsToSave = request.getReservationRooms()
                                .stream()
                                .map(roomRequest -> {
                                        ReservationRoom entity = reservationRoomMapper.toEntity(
                                                        roomRequest,
                                                        savedReservation.getId());
                                        BigDecimal subtotal = reservationPricingService.calculateAccommodationSubtotal(
                                                        roomRequest.getQuantity(),
                                                        0,
                                                        roomRequest.getUnitPrice(),
                                                        savedReservation.getCheckInDate(),
                                                        savedReservation.getCheckOutDate());
                                        entity.setSubtotal(subtotal);
                                        return entity;
                                })
                                .toList();

                reservationRoomRepository.saveAll(reservationRoomsToSave);

                guestPropertyAssociationService.recordReservationActivity(
                                request.getPrimaryGuestId(), request.getPropertyId());

                idempotencyService.completeClaim(claimed.claimId(), savedReservation.getId(), 201);

                return new IdempotentResult<>(reservationMapper.toResponse(savedReservation), false, idempotencyKey);
        }

        private ReservationResponse executeCreateReservation(ReservationCreateRequest request) {
                validateProperty(request.getPropertyId());
                validateGuest(request.getPrimaryGuestId());
                guestPropertyAssociationService.validateExistingGuestUsableAtProperty(
                                request.getPrimaryGuestId(), request.getPropertyId());
                @SuppressWarnings("unused")
                BookingSource bookingSource = validateBookingSource(request.getBookingSourceId());

                validateExternalBookingId(
                                request.getBookingSourceId(),
                                request.getExternalBookingId());

                validateDates(
                                request.getCheckInDate(),
                                request.getCheckOutDate());

                lockRoomTypesInDeterministicOrder(
                                request.getReservationRooms().stream()
                                                .map(r -> r.getRoomTypeId())
                                                .toList());

                validateRequestedRoomTypesAndAvailability(request);

                Reservation reservation = reservationMapper.toEntity(request);

                Reservation savedReservation = reservationRepository.saveAndFlush(reservation);

                List<ReservationRoom> reservationRoomsToSave = request.getReservationRooms()
                                .stream()
                                .map(roomRequest -> {
                                        ReservationRoom entity = reservationRoomMapper.toEntity(
                                                        roomRequest,
                                                        savedReservation.getId());
                                        BigDecimal subtotal = reservationPricingService.calculateAccommodationSubtotal(
                                                        roomRequest.getQuantity(),
                                                        0,
                                                        roomRequest.getUnitPrice(),
                                                        savedReservation.getCheckInDate(),
                                                        savedReservation.getCheckOutDate());
                                        entity.setSubtotal(subtotal);
                                        return entity;
                                })
                                .toList();

                reservationRoomRepository.saveAll(reservationRoomsToSave);

                guestPropertyAssociationService.recordReservationActivity(
                                request.getPrimaryGuestId(), request.getPropertyId());

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
        @Transactional(readOnly = true)
        public List<ReservationResponse> getReservationsByPropertyId(UUID propertyId) {
                validateProperty(propertyId);
                return reservationRepository.findByPropertyId(propertyId)
                                .stream()
                                .map(reservationMapper::toResponse)
                                .toList();
        }

        @Override
        @Transactional(readOnly = true)
        public List<ReservationResponse> getReservationsByPropertyIds(Collection<UUID> propertyIds) {
                if (propertyIds == null || propertyIds.isEmpty()) {
                        return Collections.emptyList();
                }
                return reservationRepository.findByPropertyIdIn(propertyIds)
                                .stream()
                                .map(reservationMapper::toResponse)
                                .toList();
        }

        @Override
        public ReservationResponse updateReservation(
                        UUID id,
                        ReservationUpdateRequest request) {

                Reservation reservation = reservationRepository.findByIdForUpdate(id)
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

                LocalDate targetCheckInDate = request.getCheckInDate() != null
                                ? request.getCheckInDate()
                                : reservation.getCheckInDate();

                LocalDate targetCheckOutDate = request.getCheckOutDate() != null
                                ? request.getCheckOutDate()
                                : reservation.getCheckOutDate();

                // Validate the resulting dates.
                validateDates(targetCheckInDate, targetCheckOutDate);

                boolean datesChanged = !targetCheckInDate.equals(reservation.getCheckInDate())
                                || !targetCheckOutDate.equals(reservation.getCheckOutDate());

                if (datesChanged) {
                        List<ReservationRoom> lockedRooms = reservationRoomRepository
                                        .findByReservationIdForUpdate(reservation.getId());
                        lockRoomTypesForActiveReservationRooms(lockedRooms);
                        validateDateChangeSafety(reservation, targetCheckInDate, targetCheckOutDate, lockedRooms);

                        for (ReservationRoom rr : lockedRooms) {
                                int activeQuantity = rr.getQuantity() - rr.getCancelledQuantity();
                                if (rr.getStatus() == ReservationRoomStatus.CANCELLED || activeQuantity <= 0) {
                                        rr.setSubtotal(BigDecimal.ZERO.setScale(ReservationPricingService.CURRENCY_SCALE, ReservationPricingService.ROUNDING_MODE));
                                } else if (rr.getUnitPrice() != null) {
                                        BigDecimal newSubtotal = reservationPricingService.calculateAccommodationSubtotal(
                                                        rr.getQuantity(),
                                                        rr.getCancelledQuantity(),
                                                        rr.getUnitPrice(),
                                                        targetCheckInDate,
                                                        targetCheckOutDate);
                                        rr.setSubtotal(newSubtotal);
                                }
                        }
                        reservationRoomRepository.saveAll(lockedRooms);
                }

                // Apply changes only to eligible direct reservations.
                reservationMapper.updateEntity(reservation, request);

                Reservation updatedReservation = reservationRepository.saveAndFlush(reservation);

                return reservationMapper.toResponse(updatedReservation);
        }

        private void validateDateChangeSafety(
                        Reservation reservation,
                        LocalDate targetCheckInDate,
                        LocalDate targetCheckOutDate,
                        List<ReservationRoom> reservationRooms) {

                // 1. Ensure every active room assignment covers the complete new reservation
                // date range
                for (ReservationRoom rr : reservationRooms) {
                        if (rr.getStatus() == ReservationRoomStatus.CANCELLED) {
                                continue;
                        }

                        List<RoomAssignment> assignments = roomAssignmentRepository.findByReservationRoomId(rr.getId());

                        for (RoomAssignment assignment : assignments) {
                                if (assignment.getStatus() == RoomAssignmentStatus.ASSIGNED) {
                                        if (!assignment.getAssignedFrom().equals(targetCheckInDate)
                                                        || !assignment.getAssignedUntil().equals(targetCheckOutDate)) {
                                                throw new BusinessValidationException(
                                                                "Cannot change reservation dates because active room assignment for room "
                                                                                + assignment.getRoomId()
                                                                                + " (" + assignment.getAssignedFrom()
                                                                                + " to " + assignment.getAssignedUntil()
                                                                                + ")"
                                                                                + " does not cover the complete new stay period ("
                                                                                + targetCheckInDate + " to "
                                                                                + targetCheckOutDate + "). "
                                                                                + "Please adjust or reassign the room first.");
                                        }
                                }
                        }
                }

                // 2. Validate inventory for changed dates
                Map<UUID, Integer> activeQuantityByRoomType = new HashMap<>();
                for (ReservationRoom rr : reservationRooms) {
                        if (rr.getStatus() == ReservationRoomStatus.CANCELLED) {
                                continue;
                        }

                        int activeQuantity = rr.getQuantity() - rr.getCancelledQuantity();
                        if (activeQuantity > 0) {
                                activeQuantityByRoomType.merge(rr.getRoomTypeId(), activeQuantity, Integer::sum);
                        }
                }

                for (Map.Entry<UUID, Integer> entry : activeQuantityByRoomType.entrySet()) {
                        UUID roomTypeId = entry.getKey();
                        int requiredQuantity = entry.getValue();

                        RoomType roomType = roomTypeRepository.findById(roomTypeId)
                                        .orElseThrow(() -> new ResourceNotFoundException(
                                                        "Room type not found with id: " + roomTypeId));

                        long totalRooms = roomRepository
                                        .countByPropertyIdAndRoomTypeIdAndActiveTrueAndStatusNot(
                                                        reservation.getPropertyId(),
                                                        roomTypeId,
                                                        RoomStatus.OUT_OF_SERVICE);

                        Long reservedQuantity = reservationRoomRepository
                                        .sumReservedQuantityForOverlappingDatesExcludingReservation(
                                                        reservation.getPropertyId(),
                                                        roomTypeId,
                                                        targetCheckInDate,
                                                        targetCheckOutDate,
                                                        List.of(
                                                                        ReservationStatus.PENDING,
                                                                        ReservationStatus.CONFIRMED,
                                                                        ReservationStatus.CHECKED_IN),
                                                        reservation.getId());

                        long alreadyReserved = reservedQuantity == null ? 0L : reservedQuantity;

                        long availableRooms = totalRooms - alreadyReserved;

                        if (requiredQuantity > availableRooms) {
                                throw new BusinessValidationException(
                                                "Insufficient availability for room type '"
                                                                + roomType.getName()
                                                                + "' for the requested dates. Requested: "
                                                                + requiredQuantity
                                                                + ", available: "
                                                                + Math.max(availableRooms, 0L));
                        }
                }
        }

        private void lockRoomTypesForActiveReservationRooms(List<ReservationRoom> reservationRooms) {
                List<UUID> activeRoomTypeIds = reservationRooms.stream()
                                .filter(rr -> rr.getStatus() != ReservationRoomStatus.CANCELLED)
                                .filter(rr -> (rr.getQuantity() - rr.getCancelledQuantity()) > 0)
                                .map(ReservationRoom::getRoomTypeId)
                                .toList();
                lockRoomTypesInDeterministicOrder(activeRoomTypeIds);
        }

        private void lockRoomTypesInDeterministicOrder(Collection<UUID> roomTypeIds) {
                if (roomTypeIds == null || roomTypeIds.isEmpty()) {
                        return;
                }
                List<UUID> sortedDistinctIds = roomTypeIds.stream()
                                .filter(java.util.Objects::nonNull)
                                .distinct()
                                .sorted()
                                .toList();
                // Acquire row locks sequentially in sorted UUID order to guarantee deterministic
                // lock ordering regardless of PostgreSQL's IN-list query plan.
                for (UUID rtId : sortedDistinctIds) {
                        roomTypeRepository.findByIdForUpdate(rtId);
                }
        }

        private void validateRequestedRoomTypesAndAvailability(
                        ReservationCreateRequest request) {

                Set<UUID> requestedRoomTypeIds = new HashSet<>();

                for (var roomRequest : request.getReservationRooms()) {
                        if (roomRequest.getUnitPrice() == null) {
                                throw new BusinessValidationException("Unit price must not be null");
                        }
                        if (roomRequest.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
                                throw new BusinessValidationException("Unit price cannot be negative");
                        }

                        UUID roomTypeId = roomRequest.getRoomTypeId();

                        if (!requestedRoomTypeIds.add(roomTypeId)) {
                                throw new BusinessValidationException(
                                                "Each room type can appear only once in a reservation request. "
                                                                + "Combine quantities for duplicate room types.");
                        }

                        RoomType roomType = roomTypeRepository.findById(roomTypeId)
                                        .orElseThrow(() -> new ResourceNotFoundException(
                                                        "Room type not found with id: " + roomTypeId));

                        if (!roomType.getPropertyId().equals(request.getPropertyId())) {
                                throw new BusinessValidationException(
                                                "The selected room type does not belong to the requested property.");
                        }

                        if (!roomType.isActive()) {
                                throw new BusinessValidationException(
                                                "The selected room type is inactive: "
                                                                + roomType.getName());
                        }

                        long totalRooms = roomRepository
                                        .countByPropertyIdAndRoomTypeIdAndActiveTrueAndStatusNot(
                                                        request.getPropertyId(),
                                                        roomTypeId,
                                                        RoomStatus.OUT_OF_SERVICE);

                        Long reservedQuantity = reservationRoomRepository
                                        .sumReservedQuantityForOverlappingDates(
                                                        request.getPropertyId(),
                                                        roomTypeId,
                                                        request.getCheckInDate(),
                                                        request.getCheckOutDate(),
                                                        List.of(
                                                                        ReservationStatus.PENDING,
                                                                        ReservationStatus.CONFIRMED,
                                                                        ReservationStatus.CHECKED_IN));

                        long alreadyReserved = reservedQuantity == null ? 0L : reservedQuantity;

                        long availableRooms = totalRooms - alreadyReserved;

                        if (roomRequest.getQuantity() > availableRooms) {
                                throw new BusinessValidationException(
                                                "Insufficient availability for room type '"
                                                                + roomType.getName()
                                                                + "'. Requested: "
                                                                + roomRequest.getQuantity()
                                                                + ", available: "
                                                                + Math.max(availableRooms, 0L));
                        }
                }
        }

        private void validateProperty(UUID propertyId) {

                propertyRepository.findById(propertyId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Property not found with id: " + propertyId));
        }

        private void validateGuest(UUID guestId) {

                Guest guest = guestRepository.findById(guestId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Guest not found with id: " + guestId));

                if (!guest.isActive()) {
                        throw new BusinessValidationException("Guest is inactive: " + guestId);
                }
        }

        private BookingSource validateBookingSource(UUID bookingSourceId) {

                BookingSource bookingSource = bookingSourceRepository.findById(bookingSourceId)
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

                Reservation reservation = reservationRepository.findByIdForUpdate(id)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Reservation not found with id: " + id));

                BookingSource bookingSource = bookingSourceRepository
                                .findById(reservation.getBookingSourceId())
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Booking source not found with id: "
                                                                + reservation.getBookingSourceId()));

                if (!"DIRECT".equals(bookingSource.getCode())) {
                        throw new BusinessValidationException(
                                        "OTA bookings cannot be cancelled locally. "
                                                        + "Cancellation must be confirmed by the booking platform.");
                }

                if (reservation.getStatus() == ReservationStatus.CANCELLED) {
                        throw new BusinessValidationException(
                                        "Reservation is already cancelled");
                }

                if (reservation.getStatus() == ReservationStatus.CHECKED_IN
                                || reservation.getStatus() == ReservationStatus.CHECKED_OUT
                                || reservation.getStatus() == ReservationStatus.NO_SHOW) {
                        throw new BusinessValidationException(
                                        "Reservation cannot be cancelled from status: "
                                                        + reservation.getStatus());
                }

                List<ReservationRoom> reservationRooms = reservationRoomRepository.findByReservationIdForUpdate(id);

                List<ReservationRoom> roomsToSave = new ArrayList<>();
                List<RoomAssignment> assignmentsToSave = new ArrayList<>();

                for (ReservationRoom reservationRoom : reservationRooms) {

                        int activeQuantity = reservationRoom.getQuantity()
                                        - reservationRoom.getCancelledQuantity();

                        if (activeQuantity > 0) {
                                reservationRoom.setCancelledQuantity(
                                                reservationRoom.getQuantity());
                                reservationRoom.setStatus(ReservationRoomStatus.CANCELLED);
                                reservationRoom.setSubtotal(BigDecimal.ZERO.setScale(ReservationPricingService.CURRENCY_SCALE, ReservationPricingService.ROUNDING_MODE));
                                roomsToSave.add(reservationRoom);
                        }

                        List<RoomAssignment> assignments = roomAssignmentRepository.findByReservationRoomId(
                                        reservationRoom.getId());

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

                Reservation savedReservation = reservationRepository.saveAndFlush(reservation);

                return reservationMapper.toResponse(savedReservation);
        }

        @Override
        public ReservationResponse confirmReservation(UUID id) {

                // 1. Acquire PESSIMISTIC_WRITE lock on Reservation
                Reservation reservation = reservationRepository.findByIdForUpdate(id)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Reservation not found with id: " + id));
                entityManager.refresh(reservation);

                // 2. Acquire child ReservationRoom locks in deterministic order
                List<ReservationRoom> lockedRooms = reservationRoomRepository
                                .findByReservationIdForUpdate(reservation.getId());

                // 3. Resolve booking source
                BookingSource bookingSource = bookingSourceRepository
                                .findById(reservation.getBookingSourceId())
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Booking source not found with id: "
                                                                + reservation.getBookingSourceId()));

                // 4. Validate booking source (DIRECT only)
                if (!"DIRECT".equals(bookingSource.getCode())) {
                        throw new BusinessValidationException(
                                        "OTA reservations cannot be confirmed locally. "
                                                        + "Confirmation must be confirmed by the booking platform.");
                }

                // 5. Status Validation
                if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
                        throw new BusinessValidationException("Reservation is already confirmed");
                }

                if (reservation.getStatus() != ReservationStatus.PENDING) {
                        throw new BusinessValidationException(
                                        "Cannot confirm reservation from status: " + reservation.getStatus()
                                                        + ". Only PENDING reservations can be confirmed.");
                }

                // 6. Acquire inventory locks and run exclusion-safe availability re-validation
                lockRoomTypesForActiveReservationRooms(lockedRooms);
                validateReservationRoomsAvailabilityExcludingSelf(reservation, lockedRooms);

                // 7. Transition status
                reservation.setStatus(ReservationStatus.CONFIRMED);

                Reservation savedReservation = reservationRepository.saveAndFlush(reservation);

                return reservationMapper.toResponse(savedReservation);
        }

        private void validateReservationRoomsAvailabilityExcludingSelf(
                        Reservation reservation,
                        List<ReservationRoom> reservationRooms) {

                Map<UUID, Integer> activeQuantityByRoomType = new HashMap<>();
                for (ReservationRoom rr : reservationRooms) {
                        if (rr.getStatus() == ReservationRoomStatus.CANCELLED) {
                                continue;
                        }

                        int activeQuantity = rr.getQuantity() - rr.getCancelledQuantity();
                        if (activeQuantity > 0) {
                                activeQuantityByRoomType.merge(rr.getRoomTypeId(), activeQuantity, Integer::sum);
                        }
                }

                for (Map.Entry<UUID, Integer> entry : activeQuantityByRoomType.entrySet()) {
                        UUID roomTypeId = entry.getKey();
                        int requiredQuantity = entry.getValue();

                        RoomType roomType = roomTypeRepository.findById(roomTypeId)
                                        .orElseThrow(() -> new ResourceNotFoundException(
                                                        "Room type not found with id: " + roomTypeId));

                        if (!roomType.isActive()) {
                                throw new BusinessValidationException(
                                                "The selected room type is inactive: " + roomType.getName());
                        }

                        long totalRooms = roomRepository
                                        .countByPropertyIdAndRoomTypeIdAndActiveTrueAndStatusNot(
                                                        reservation.getPropertyId(),
                                                        roomTypeId,
                                                        RoomStatus.OUT_OF_SERVICE);

                        Long reservedQuantity = reservationRoomRepository
                                        .sumReservedQuantityForOverlappingDatesExcludingReservation(
                                                        reservation.getPropertyId(),
                                                        roomTypeId,
                                                        reservation.getCheckInDate(),
                                                        reservation.getCheckOutDate(),
                                                        List.of(
                                                                        ReservationStatus.PENDING,
                                                                        ReservationStatus.CONFIRMED,
                                                                        ReservationStatus.CHECKED_IN),
                                                        reservation.getId());

                        long alreadyReserved = reservedQuantity == null ? 0L : reservedQuantity;

                        long availableRooms = totalRooms - alreadyReserved;

                        if (requiredQuantity > availableRooms) {
                                throw new BusinessValidationException(
                                                "Insufficient availability for room type '"
                                                                + roomType.getName()
                                                                + "' for the requested dates. Requested: "
                                                                + requiredQuantity
                                                                + ", available: "
                                                                + Math.max(availableRooms, 0L));
                        }
                }
        }

        @Override
        public ReservationResponse checkInReservation(UUID id) {

                // 1. Acquire PESSIMISTIC_WRITE lock on Reservation
                Reservation reservation = reservationRepository.findByIdForUpdate(id)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Reservation not found with id: " + id));
                entityManager.refresh(reservation);

                // 2. Acquire child ReservationRoom locks in deterministic order
                List<ReservationRoom> lockedRooms = reservationRoomRepository
                                .findByReservationIdForUpdate(reservation.getId());

                // 3. Status Validation
                if (reservation.getStatus() == ReservationStatus.CHECKED_IN) {
                        throw new BusinessValidationException("Reservation is already checked in");
                }

                if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
                        throw new BusinessValidationException(
                                        "Cannot check in reservation from status: " + reservation.getStatus()
                                                        + ". Only CONFIRMED reservations can be checked in.");
                }

                // 4. Timezone and Arrival Date Validation
                Property property = propertyRepository.findById(reservation.getPropertyId())
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Property not found with id: " + reservation.getPropertyId()));

                ZoneId propertyZone;
                try {
                        if (property.getTimezone() == null || property.getTimezone().isBlank()) {
                                throw new BusinessValidationException(
                                                "Property " + property.getId()
                                                                + " does not have a configured timezone.");
                        }
                        propertyZone = ZoneId.of(property.getTimezone().trim());
                } catch (DateTimeException e) {
                        throw new BusinessValidationException(
                                        "Property " + property.getId() + " has an invalid timezone: "
                                                        + property.getTimezone());
                }

                LocalDate todayInPropertyZone = LocalDate.now(clock.withZone(propertyZone));

                if (todayInPropertyZone.isBefore(reservation.getCheckInDate())) {
                        throw new BusinessValidationException(
                                        "Cannot check in before reservation check-in date. "
                                                        + "Check-in date: " + reservation.getCheckInDate()
                                                        + ", current property date: " + todayInPropertyZone);
                }

                if (!todayInPropertyZone.isBefore(reservation.getCheckOutDate())) {
                        throw new BusinessValidationException(
                                        "Cannot check in on or after reservation check-out date. "
                                                        + "Check-out date: " + reservation.getCheckOutDate()
                                                        + ", current property date: " + todayInPropertyZone);
                }

                // 5. Full Physical Room Coverage Validation
                int totalActiveQuantity = 0;
                for (ReservationRoom rr : lockedRooms) {
                        if (rr.getStatus() != ReservationRoomStatus.CANCELLED) {
                                totalActiveQuantity += (rr.getQuantity() - rr.getCancelledQuantity());
                        }
                }

                if (totalActiveQuantity <= 0) {
                        throw new BusinessValidationException(
                                        "Cannot check in: Reservation has no active rooms.");
                }

                Set<UUID> roomsToOccupy = new HashSet<>();
                LocalDate checkIn = reservation.getCheckInDate();
                LocalDate checkOut = reservation.getCheckOutDate();

                for (ReservationRoom rr : lockedRooms) {
                        if (rr.getStatus() == ReservationRoomStatus.CANCELLED) {
                                continue;
                        }

                        int activeQuantity = rr.getQuantity() - rr.getCancelledQuantity();
                        if (activeQuantity <= 0) {
                                continue;
                        }

                        List<RoomAssignment> assignments = roomAssignmentRepository.findByReservationRoomId(rr.getId());

                        List<RoomAssignment> activeAssignments = assignments.stream()
                                        .filter(a -> a.getStatus() == RoomAssignmentStatus.ASSIGNED)
                                        .toList();

                        // Check for split-stay or partial-date assignments
                        boolean hasPartialOrSplitAssignment = activeAssignments.stream()
                                        .anyMatch(a -> !a.getAssignedFrom().equals(checkIn)
                                                        || !a.getAssignedUntil().equals(checkOut));

                        if (hasPartialOrSplitAssignment) {
                                throw new BusinessValidationException(
                                                "Cannot check in: Split-stay reservations (different physical rooms across stay dates) "
                                                                + "are not supported. Split-stay bookings require consecutive reservations or a "
                                                                + "future room-transfer workflow. All assigned rooms must cover the full stay period "
                                                                + "from " + checkIn + " to " + checkOut + ".");
                        }

                        Set<UUID> assignedRoomIds = activeAssignments.stream()
                                        .map(RoomAssignment::getRoomId)
                                        .collect(Collectors.toSet());

                        if (activeAssignments.size() != activeQuantity || assignedRoomIds.size() != activeQuantity) {
                                RoomType roomType = roomTypeRepository.findById(rr.getRoomTypeId()).orElse(null);
                                String roomTypeName = roomType != null ? roomType.getName()
                                                : rr.getRoomTypeId().toString();

                                throw new BusinessValidationException(
                                                "Incomplete physical room assignment for room type '" + roomTypeName
                                                                + "'. Required: " + activeQuantity
                                                                + ", assigned: " + assignedRoomIds.size()
                                                                + ". All reserved rooms must be physically assigned before check-in.");
                        }

                        // Validate each active assignment's physical room
                        for (RoomAssignment assignment : activeAssignments) {
                                Room room = roomRepository.findById(assignment.getRoomId())
                                                .orElseThrow(() -> new ResourceNotFoundException(
                                                                "Room not found with id: " + assignment.getRoomId()));

                                if (!room.getPropertyId().equals(reservation.getPropertyId())) {
                                        throw new BusinessValidationException(
                                                        "Assigned room " + room.getRoomNumber()
                                                                        + " does not belong to the reservation property");
                                }

                                if (!room.getRoomTypeId().equals(rr.getRoomTypeId())) {
                                        throw new BusinessValidationException(
                                                        "Assigned room " + room.getRoomNumber()
                                                                        + " does not match the reservation room type");
                                }

                                if (!room.isActive()) {
                                        throw new BusinessValidationException(
                                                        "Assigned room " + room.getRoomNumber()
                                                                        + " is inactive and cannot be checked in");
                                }

                                if (room.getStatus() == RoomStatus.OUT_OF_SERVICE) {
                                        throw new BusinessValidationException(
                                                        "Assigned room " + room.getRoomNumber()
                                                                        + " is out of service and cannot be checked in");
                                }

                                if (room.getHousekeepingStatus() != HousekeepingStatus.CLEAN) {
                                        throw new BusinessValidationException(
                                                        "Cannot check in: assigned room requires housekeeping.");
                                }

                                roomsToOccupy.add(room.getId());
                        }
                }

                if (roomsToOccupy.size() != totalActiveQuantity) {
                        throw new BusinessValidationException(
                                        "Duplicate physical room assignment detected across reservation rooms. "
                                                        + "Each reserved room must be assigned to a unique physical room.");
                }

                // 6. Transition assigned physical rooms to OCCUPIED atomically
                List<UUID> sortedRoomIds = roomsToOccupy.stream().sorted().toList();
                List<Room> roomsToSave = new ArrayList<>();
                for (UUID roomId : sortedRoomIds) {
                        Room room = roomRepository.findByIdForUpdate(roomId)
                                        .orElseThrow(() -> new ResourceNotFoundException(
                                                        "Room not found with id: " + roomId));
                        entityManager.refresh(room);

                        if (!room.isActive()) {
                                throw new BusinessValidationException(
                                                "Assigned room " + room.getRoomNumber()
                                                                + " is inactive and cannot be checked in");
                        }

                        if (room.getStatus() == RoomStatus.OUT_OF_SERVICE) {
                                throw new BusinessValidationException(
                                                "Assigned room " + room.getRoomNumber()
                                                                + " is out of service and cannot be checked in");
                        }

                        if (room.getHousekeepingStatus() != HousekeepingStatus.CLEAN) {
                                throw new BusinessValidationException(
                                                "Cannot check in: assigned room requires housekeeping.");
                        }

                        if (room.getStatus() != RoomStatus.OCCUPIED) {
                                room.setStatus(RoomStatus.OCCUPIED);
                                roomsToSave.add(room);
                        }
                }
                if (!roomsToSave.isEmpty()) {
                        roomRepository.saveAllAndFlush(roomsToSave);
                }

                // 7. Calculate checkout deadline (12:00 PM on checkOutDate in
                // Property.timezone)
                ZonedDateTime deadlineZoned = reservation.getCheckOutDate()
                                .atTime(12, 0)
                                .atZone(propertyZone);
                Instant checkoutDueAt = deadlineZoned.toInstant();

                reservation.setCheckoutDueAt(checkoutDueAt);
                reservation.setCheckoutDue(false);

                // 8. Transition status to CHECKED_IN and persist atomically
                reservation.setStatus(ReservationStatus.CHECKED_IN);
                Reservation savedReservation = reservationRepository.saveAndFlush(reservation);

                return reservationMapper.toResponse(savedReservation);
        }

        @Override
        public ReservationResponse checkOutReservation(UUID id) {

                // 1. Acquire PESSIMISTIC_WRITE lock on Reservation
                Reservation reservation = reservationRepository.findByIdForUpdate(id)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Reservation not found with id: " + id));
                entityManager.refresh(reservation);

                // 2. Status Validation
                if (reservation.getStatus() == ReservationStatus.CHECKED_OUT) {
                        throw new BusinessValidationException("Reservation is already checked out");
                }

                if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
                        throw new BusinessValidationException(
                                        "Cannot check out reservation from status: " + reservation.getStatus()
                                                        + ". Only CHECKED_IN reservations can be checked out.");
                }

                // 3. Acquire child ReservationRoom locks in deterministic order
                List<ReservationRoom> lockedRooms = reservationRoomRepository
                                .findByReservationIdForUpdate(reservation.getId());

                // 4. Validate Child Records and Room Assignments
                int totalActiveQuantity = 0;
                for (ReservationRoom rr : lockedRooms) {
                        if (rr.getStatus() != ReservationRoomStatus.CANCELLED) {
                                totalActiveQuantity += (rr.getQuantity() - rr.getCancelledQuantity());
                        }
                }

                if (totalActiveQuantity <= 0) {
                        throw new BusinessValidationException(
                                        "Cannot check out: Reservation has no active rooms.");
                }

                LocalDate checkIn = reservation.getCheckInDate();
                LocalDate checkOut = reservation.getCheckOutDate();
                List<RoomAssignment> assignmentsToRelease = new ArrayList<>();
                Set<UUID> roomsToReconcile = new HashSet<>();

                for (ReservationRoom rr : lockedRooms) {
                        if (rr.getStatus() == ReservationRoomStatus.CANCELLED) {
                                continue;
                        }

                        int activeQuantity = rr.getQuantity() - rr.getCancelledQuantity();
                        if (activeQuantity <= 0) {
                                continue;
                        }

                        List<RoomAssignment> assignments = roomAssignmentRepository.findByReservationRoomId(rr.getId());

                        List<RoomAssignment> activeAssignments = assignments.stream()
                                        .filter(a -> a.getStatus() == RoomAssignmentStatus.ASSIGNED)
                                        .toList();

                        // Legacy split-stay & partial date detection:
                        boolean hasPartialOrSplitAssignment = activeAssignments.stream()
                                        .anyMatch(a -> !a.getAssignedFrom().equals(checkIn)
                                                        || !a.getAssignedUntil().equals(checkOut));

                        if (hasPartialOrSplitAssignment) {
                                throw new BusinessValidationException(
                                                "Cannot check out: Split-stay or partial-date room assignments detected for reservation. "
                                                                + "Legacy split-stay occupancy cannot be safely reconciled at checkout. "
                                                                + "All active room assignments must cover the full stay period from "
                                                                + checkIn + " to " + checkOut + ".");
                        }

                        Set<UUID> assignedRoomIds = activeAssignments.stream()
                                        .map(RoomAssignment::getRoomId)
                                        .collect(Collectors.toSet());

                        if (activeAssignments.size() != activeQuantity || assignedRoomIds.size() != activeQuantity) {
                                RoomType roomType = roomTypeRepository.findById(rr.getRoomTypeId()).orElse(null);
                                String roomTypeName = roomType != null ? roomType.getName()
                                                : rr.getRoomTypeId().toString();

                                throw new BusinessValidationException(
                                                "Cannot check out: Ambiguous or incomplete physical room assignments for room type '"
                                                                + roomTypeName
                                                                + "'. Required: " + activeQuantity
                                                                + ", assigned: " + assignedRoomIds.size()
                                                                + ". Each reserved room must have a distinct full-stay assignment.");
                        }

                        assignmentsToRelease.addAll(activeAssignments);
                        roomsToReconcile.addAll(assignedRoomIds);
                }

                if (roomsToReconcile.size() != totalActiveQuantity) {
                        throw new BusinessValidationException(
                                        "Cannot check out: Duplicate physical room assignment detected across reservation rooms.");
                }

                // 5. Deterministic Physical Room Locking (Sorted UUID order)
                List<UUID> sortedRoomIds = roomsToReconcile.stream().sorted().toList();
                List<Room> lockedPhysicalRooms = new ArrayList<>();

                for (UUID roomId : sortedRoomIds) {
                        Room room = roomRepository.findByIdForUpdate(roomId)
                                        .orElseThrow(() -> new ResourceNotFoundException(
                                                        "Room not found with id: " + roomId));
                        entityManager.refresh(room);

                        // Verify room belongs to the reservation's property
                        if (!room.getPropertyId().equals(reservation.getPropertyId())) {
                                throw new BusinessValidationException(
                                                "Assigned room " + room.getRoomNumber()
                                                                + " does not belong to the reservation property");
                        }

                        // Check if room is referenced by any OTHER active checked-in reservation
                        boolean hasOtherActive = roomAssignmentRepository.hasOtherActiveCheckedInReservation(
                                        room.getId(), reservation.getId());
                        if (hasOtherActive) {
                                throw new BusinessValidationException(
                                                "Cannot check out: Room " + room.getRoomNumber()
                                                                + " is currently referenced by another active checked-in reservation. "
                                                                + "Checkout cannot proceed due to ambiguous room occupancy.");
                        }

                        // Room status reconciliation
                        if (room.getStatus() == RoomStatus.OCCUPIED) {
                                room.setStatus(RoomStatus.AVAILABLE);
                                room.setHousekeepingStatus(HousekeepingStatus.DIRTY);
                        } else if (room.getStatus() == RoomStatus.OUT_OF_SERVICE) {
                                // Preserve OUT_OF_SERVICE; record DIRTY because room was confirmed vacated
                                room.setHousekeepingStatus(HousekeepingStatus.DIRTY);
                        } else if (room.getStatus() == RoomStatus.AVAILABLE) {
                                throw new BusinessValidationException(
                                                "Cannot check out: Room " + room.getRoomNumber()
                                                                + " is unexpectedly in AVAILABLE status while reservation is CHECKED_IN. "
                                                                + "Inconsistent room state detected.");
                        } else {
                                throw new BusinessValidationException(
                                                "Cannot check out: Room " + room.getRoomNumber()
                                                                + " is in unexpected status: " + room.getStatus());
                        }

                        lockedPhysicalRooms.add(room);
                }

                // 6. Resolve Authenticated Staff UUID and Checkout Timestamp
                Instant actualCheckedOutAt = clock.instant();
                UUID staffUserId = null;
                if (staffAuthorizationService != null) {
                        Optional<AuthenticatedStaffPrincipal> currentPrincipalOpt = staffAuthorizationService
                                        .getCurrentPrincipal();
                        if (currentPrincipalOpt.isPresent()) {
                                staffUserId = currentPrincipalOpt.get().staffUserId();
                        }
                }

                if (staffUserId == null && securityEnforcementProperties != null
                                && securityEnforcementProperties.isEnabled()) {
                        throw new BusinessValidationException(
                                        "Cannot check out: Authenticated staff principal is required.");
                }

                // 7. Persist physical room updates
                roomRepository.saveAllAndFlush(lockedPhysicalRooms);

                // 8. Release room assignments atomically within the checkout transaction
                for (RoomAssignment ra : assignmentsToRelease) {
                        ra.setStatus(RoomAssignmentStatus.RELEASED);
                }
                roomAssignmentRepository.saveAllAndFlush(assignmentsToRelease);

                // 9. Update Reservation State and Persist
                reservation.setStatus(ReservationStatus.CHECKED_OUT);
                reservation.setCheckoutDue(false);
                // Preserves original checkInDate, checkOutDate, and checkoutDueAt
                reservation.setActualCheckedOutAt(actualCheckedOutAt);
                reservation.setCheckedOutByStaffUserId(staffUserId);

                Reservation savedReservation = reservationRepository.saveAndFlush(reservation);

                return reservationMapper.toResponse(savedReservation);
        }
}