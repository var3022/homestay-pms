package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.ReservationCreateRequest;
import com.homestay.pms_backend.dto.request.ReservationRoomAddRequest;
import com.homestay.pms_backend.dto.request.ReservationRoomCreateRequest;
import com.homestay.pms_backend.dto.request.ReservationUpdateRequest;
import com.homestay.pms_backend.dto.request.RoomAssignmentCreateRequest;
import com.homestay.pms_backend.dto.request.RoomStatusUpdateRequest;
import com.homestay.pms_backend.entity.BookingSource;
import com.homestay.pms_backend.entity.Guest;
import com.homestay.pms_backend.entity.Property;
import com.homestay.pms_backend.entity.Reservation;
import com.homestay.pms_backend.entity.ReservationRoom;
import com.homestay.pms_backend.entity.Room;
import com.homestay.pms_backend.entity.RoomType;
import com.homestay.pms_backend.enums.HousekeepingStatus;
import com.homestay.pms_backend.enums.ReservationStatus;
import com.homestay.pms_backend.enums.RoomAssignmentStatus;
import com.homestay.pms_backend.enums.RoomStatus;
import com.homestay.pms_backend.enums.RoomTypeCategory;
import com.homestay.pms_backend.exception.BusinessValidationException;
import com.homestay.pms_backend.repository.BookingSourceRepository;
import com.homestay.pms_backend.repository.GuestPropertyAssociationRepository;
import com.homestay.pms_backend.repository.GuestRepository;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.repository.ReservationRepository;
import com.homestay.pms_backend.repository.ReservationRoomRepository;
import com.homestay.pms_backend.repository.RoomAssignmentRepository;
import com.homestay.pms_backend.repository.RoomRepository;
import com.homestay.pms_backend.repository.RoomTypeRepository;
import com.homestay.pms_backend.service.GuestPropertyAssociationService;
import com.homestay.pms_backend.service.ReservationRoomService;
import com.homestay.pms_backend.service.ReservationService;
import com.homestay.pms_backend.service.RoomAssignmentService;
import com.homestay.pms_backend.service.RoomService;
import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IsolatedDatabaseTest
@DisplayName("Step 3.4.3.10B: Reservation Inventory & Room Capacity Concurrency Integration Tests")
class ReservationInventoryConcurrencyIntegrationTest {

    @Autowired
    private ReservationService reservationService;
    @Autowired
    private ReservationRoomService reservationRoomService;
    @Autowired
    private RoomService roomService;
    @Autowired
    private RoomAssignmentService roomAssignmentService;
    @Autowired
    private GuestPropertyAssociationService guestPropertyAssociationService;

    @Autowired
    private PropertyRepository propertyRepository;
    @Autowired
    private RoomTypeRepository roomTypeRepository;
    @Autowired
    private RoomRepository roomRepository;
    @Autowired
    private GuestRepository guestRepository;
    @Autowired
    private GuestPropertyAssociationRepository associationRepository;
    @Autowired
    private BookingSourceRepository bookingSourceRepository;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private ReservationRoomRepository reservationRoomRepository;
    @Autowired
    private RoomAssignmentRepository roomAssignmentRepository;

    private UUID directSourceId;
    private final List<UUID> createdPropertyIds = new ArrayList<>();
    private final List<UUID> createdGuestIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        BookingSource direct = bookingSourceRepository.findByCode("DIRECT")
                .orElseThrow(() -> new IllegalStateException("DIRECT booking source missing"));
        directSourceId = direct.getId();
    }

    @AfterEach
    void tearDown() {
        for (UUID propertyId : createdPropertyIds) {
            List<Reservation> resList = reservationRepository.findByPropertyId(propertyId);
            for (Reservation r : resList) {
                List<ReservationRoom> rrs = reservationRoomRepository.findByReservationId(r.getId());
                for (ReservationRoom rr : rrs) {
                    roomAssignmentRepository.deleteAll(roomAssignmentRepository.findByReservationRoomId(rr.getId()));
                }
                reservationRoomRepository.deleteAll(rrs);
            }
            reservationRepository.deleteAll(resList);
            roomRepository.deleteAll(roomRepository.findByPropertyId(propertyId));
            roomTypeRepository.deleteAll(roomTypeRepository.findByPropertyId(propertyId));
            associationRepository.deleteAll(
                    associationRepository.findAll().stream()
                            .filter(a -> a.getId().getPropertyId().equals(propertyId))
                            .toList()
            );
            propertyRepository.deleteById(propertyId);
        }
        createdPropertyIds.clear();
        for (UUID guestId : createdGuestIds) {
            guestRepository.deleteById(guestId);
        }
        createdGuestIds.clear();
    }

    private Property createProperty(String codeSuffix) {
        Property p = new Property();
        p.setName("Concurrency Test Property " + codeSuffix);
        p.setCode("CONC-" + codeSuffix + "-" + UUID.randomUUID().toString().substring(0, 6));
        p.setAddressLine1("1 Test Ave");
        p.setCity("Ooty");
        p.setState("TN");
        p.setCountry("India");
        p.setTimezone("Asia/Kolkata");
        p.setActive(true);
        Property saved = propertyRepository.saveAndFlush(p);
        createdPropertyIds.add(saved.getId());
        return saved;
    }

    private RoomType createRoomType(UUID propertyId, String code) {
        RoomType rt = new RoomType();
        rt.setPropertyId(propertyId);
        rt.setName("RoomType " + code);
        rt.setCode(code);
        rt.setCategory(RoomTypeCategory.SUITE);
        rt.setBaseOccupancy(2);
        rt.setMaxOccupancy(4);
        rt.setMaxExtraBeds(0);
        rt.setActive(true);
        return roomTypeRepository.saveAndFlush(rt);
    }

    private Room createRoom(UUID propertyId, UUID roomTypeId, String number) {
        Room r = new Room();
        r.setPropertyId(propertyId);
        r.setRoomTypeId(roomTypeId);
        r.setRoomNumber(number);
        r.setStatus(RoomStatus.AVAILABLE);
        r.setHousekeepingStatus(HousekeepingStatus.CLEAN);
        r.setBaseOccupancy(2);
        r.setMaxOccupancy(4);
        r.setActive(true);
        return roomRepository.saveAndFlush(r);
    }

    private Guest createGuestForProperties(String name, UUID... propertyIds) {
        Guest g = new Guest();
        g.setName(name);
        g.setPhone("+9199000" + (int) (Math.random() * 90000 + 10000));
        g.setActive(true);
        Guest saved = guestRepository.saveAndFlush(g);
        createdGuestIds.add(saved.getId());
        for (UUID propId : propertyIds) {
            guestPropertyAssociationService.createRegistrationAssociation(saved.getId(), propId);
        }
        return saved;
    }

    private ReservationCreateRequest buildCreateRequest(
            UUID propertyId, UUID guestId, LocalDate checkIn, LocalDate checkOut, List<UUID> roomTypeIds) {
        ReservationCreateRequest req = new ReservationCreateRequest();
        req.setPropertyId(propertyId);
        req.setPrimaryGuestId(guestId);
        req.setCheckInDate(checkIn);
        req.setCheckOutDate(checkOut);
        req.setAdults(1);
        req.setChildren(0);
        req.setInfants(0);
        req.setBookingSourceId(directSourceId);
        List<ReservationRoomCreateRequest> rooms = new ArrayList<>();
        for (UUID rtId : roomTypeIds) {
            ReservationRoomCreateRequest rr = new ReservationRoomCreateRequest();
            rr.setRoomTypeId(rtId);
            rr.setQuantity(1);
            rr.setUnitPrice(new BigDecimal("2500.00"));
            rooms.add(rr);
        }
        req.setReservationRooms(rooms);
        return req;
    }

    private <T> List<Outcome<T>> runWithBarrier(List<Callable<T>> tasks) throws Exception {
        int n = tasks.size();
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        return new Outcome<>(task.call(), null);
                    } catch (Throwable t) {
                        return new Outcome<>(null, t);
                    }
                }));
            }
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private record Outcome<T>(T value, Throwable error) {
        boolean isSuccess() {
            return error == null;
        }
    }

    private long activeReserved(UUID propertyId, UUID roomTypeId, LocalDate from, LocalDate to) {
        Long q = reservationRoomRepository.sumReservedQuantityForOverlappingDates(
                propertyId,
                roomTypeId,
                from,
                to,
                List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN)
        );
        return q == null ? 0L : q;
    }

    private long sellableCapacity(UUID propertyId, UUID roomTypeId) {
        return roomRepository.countByPropertyIdAndRoomTypeIdAndActiveTrueAndStatusNot(
                propertyId, roomTypeId, RoomStatus.OUT_OF_SERVICE);
    }

    @Test
    @DisplayName("Scenario A: 2 simultaneous DIRECT creations for capacity=1 never overbook across 10 trials")
    void scenarioA_twoCreationsCapacityOne() throws Exception {
        LocalDate from = LocalDate.of(2026, 12, 10);
        LocalDate to = LocalDate.of(2026, 12, 12);

        for (int trial = 0; trial < 10; trial++) {
            Property p = createProperty("A" + trial);
            RoomType rt = createRoomType(p.getId(), "DLX");
            createRoom(p.getId(), rt.getId(), "101");
            Guest g1 = createGuestForProperties("Guest A1", p.getId());
            Guest g2 = createGuestForProperties("Guest A2", p.getId());

            var outcomes = runWithBarrier(List.of(
                    () -> reservationService.createReservation(buildCreateRequest(p.getId(), g1.getId(), from, to, List.of(rt.getId()))),
                    () -> reservationService.createReservation(buildCreateRequest(p.getId(), g2.getId(), from, to, List.of(rt.getId())))
            ));

            long successes = outcomes.stream().filter(Outcome::isSuccess).count();
            long failures = outcomes.stream().filter(o -> o.error() instanceof BusinessValidationException).count();
            assertThat(successes).isEqualTo(1);
            assertThat(failures).isEqualTo(1);
            assertThat(activeReserved(p.getId(), rt.getId(), from, to))
                    .isEqualTo(1)
                    .isLessThanOrEqualTo(sellableCapacity(p.getId(), rt.getId()));
        }
    }

    @Test
    @DisplayName("Scenario B: 5 simultaneous creations for capacity=2 allow exactly 2 across 5 trials")
    void scenarioB_fiveCreationsCapacityTwo() throws Exception {
        LocalDate from = LocalDate.of(2026, 12, 10);
        LocalDate to = LocalDate.of(2026, 12, 12);

        for (int trial = 0; trial < 5; trial++) {
            Property p = createProperty("B" + trial);
            RoomType rt = createRoomType(p.getId(), "DLX");
            createRoom(p.getId(), rt.getId(), "101");
            createRoom(p.getId(), rt.getId(), "102");
            Guest g1 = createGuestForProperties("Guest B1", p.getId());

            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                tasks.add(() -> reservationService.createReservation(
                        buildCreateRequest(p.getId(), g1.getId(), from, to, List.of(rt.getId()))));
            }
            var outcomes = runWithBarrier(tasks);

            long successes = outcomes.stream().filter(Outcome::isSuccess).count();
            long businessRejects = outcomes.stream().filter(o -> o.error() instanceof BusinessValidationException).count();
            assertThat(successes).isEqualTo(2);
            assertThat(businessRejects).isEqualTo(3);
            assertThat(activeReserved(p.getId(), rt.getId(), from, to)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("Scenario C: Concurrent creation vs date extension into overlapping dates never overbooks (5 trials)")
    void scenarioC_creationVsDateExtension() throws Exception {
        for (int trial = 0; trial < 5; trial++) {
            Property p = createProperty("C" + trial);
            RoomType rt = createRoomType(p.getId(), "DLX");
            createRoom(p.getId(), rt.getId(), "101");
            Guest g1 = createGuestForProperties("Guest C1", p.getId());
            Guest g2 = createGuestForProperties("Guest C2", p.getId());

            var existing = reservationService.createReservation(buildCreateRequest(
                    p.getId(), g1.getId(), LocalDate.of(2026, 12, 8), LocalDate.of(2026, 12, 10), List.of(rt.getId())));

            ReservationUpdateRequest extendReq = new ReservationUpdateRequest();
            extendReq.setCheckOutDate(LocalDate.of(2026, 12, 12));

            var outcomes = runWithBarrier(List.of(
                    () -> reservationService.createReservation(buildCreateRequest(
                            p.getId(), g2.getId(), LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 12), List.of(rt.getId()))),
                    () -> reservationService.updateReservation(existing.getId(), extendReq)
            ));

            long successes = outcomes.stream().filter(Outcome::isSuccess).count();
            long businessRejects = outcomes.stream().filter(o -> o.error() instanceof BusinessValidationException).count();
            assertThat(successes).isEqualTo(1);
            assertThat(businessRejects).isEqualTo(1);
            assertThat(activeReserved(p.getId(), rt.getId(), LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 12)))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Scenario D: Concurrent addReservationRoom on two reservations competing for remaining unit (5 trials)")
    void scenarioD_concurrentRoomAdditions() throws Exception {
        LocalDate from = LocalDate.of(2026, 12, 10);
        LocalDate to = LocalDate.of(2026, 12, 12);

        for (int trial = 0; trial < 5; trial++) {
            Property p = createProperty("D" + trial);
            RoomType rtTarget = createRoomType(p.getId(), "DLX");
            RoomType rtOther = createRoomType(p.getId(), "STD");
            createRoom(p.getId(), rtTarget.getId(), "101");
            createRoom(p.getId(), rtOther.getId(), "201");
            createRoom(p.getId(), rtOther.getId(), "202");
            Guest g1 = createGuestForProperties("Guest D1", p.getId());

            var r1 = reservationService.createReservation(buildCreateRequest(p.getId(), g1.getId(), from, to, List.of(rtOther.getId())));
            var r2 = reservationService.createReservation(buildCreateRequest(p.getId(), g1.getId(), from, to, List.of(rtOther.getId())));

            ReservationRoomAddRequest add1 = new ReservationRoomAddRequest();
            add1.setReservationId(r1.getId());
            add1.setRoomTypeId(rtTarget.getId());
            add1.setQuantity(1);
            add1.setUnitPrice(new BigDecimal("2500.00"));

            ReservationRoomAddRequest add2 = new ReservationRoomAddRequest();
            add2.setReservationId(r2.getId());
            add2.setRoomTypeId(rtTarget.getId());
            add2.setQuantity(1);
            add2.setUnitPrice(new BigDecimal("2500.00"));

            var outcomes = runWithBarrier(List.of(
                    () -> reservationRoomService.addReservationRoom(add1),
                    () -> reservationRoomService.addReservationRoom(add2)
            ));

            long successes = outcomes.stream().filter(Outcome::isSuccess).count();
            long businessRejects = outcomes.stream().filter(o -> o.error() instanceof BusinessValidationException).count();
            assertThat(successes).isEqualTo(1);
            assertThat(businessRejects).isEqualTo(1);
            assertThat(activeReserved(p.getId(), rtTarget.getId(), from, to)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Scenario E: Concurrent booking creation vs OUT_OF_SERVICE capacity reduction preserves invariant (5 trials)")
    void scenarioE_creationVsCapacityReduction() throws Exception {
        LocalDate from = LocalDate.of(2026, 12, 10);
        LocalDate to = LocalDate.of(2026, 12, 12);

        for (int trial = 0; trial < 5; trial++) {
            Property p = createProperty("E" + trial);
            RoomType rt = createRoomType(p.getId(), "DLX");
            Room room = createRoom(p.getId(), rt.getId(), "101");
            Guest g1 = createGuestForProperties("Guest E1", p.getId());

            RoomStatusUpdateRequest oos = new RoomStatusUpdateRequest();
            oos.setStatus(RoomStatus.OUT_OF_SERVICE);

            var outcomes = runWithBarrier(List.of(
                    () -> reservationService.createReservation(buildCreateRequest(p.getId(), g1.getId(), from, to, List.of(rt.getId()))),
                    () -> roomService.updateRoomStatus(room.getId(), oos)
            ));

            long successes = outcomes.stream().filter(Outcome::isSuccess).count();
            assertThat(successes).isEqualTo(1);
            assertThat(activeReserved(p.getId(), rt.getId(), from, to))
                    .isLessThanOrEqualTo(sellableCapacity(p.getId(), rt.getId()));
        }
    }

    @Test
    @DisplayName("Scenario E2: Peak-demand half-open interval validation allows non-overlapping stays and blocks overlapping deficit")
    void scenarioE2_peakDemandHalfOpenIntervals() {
        Property p = createProperty("E2");
        RoomType rt = createRoomType(p.getId(), "DLX");
        Room r1 = createRoom(p.getId(), rt.getId(), "101");
        Room r2 = createRoom(p.getId(), rt.getId(), "102");
        Guest g1 = createGuestForProperties("Guest E2", p.getId());

        // Non-overlapping back-to-back stays: [Dec 10..12] and [Dec 12..14] => peak demand = 1
        reservationService.createReservation(buildCreateRequest(
                p.getId(), g1.getId(), LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 12), List.of(rt.getId())));
        reservationService.createReservation(buildCreateRequest(
                p.getId(), g1.getId(), LocalDate.of(2026, 12, 12), LocalDate.of(2026, 12, 14), List.of(rt.getId())));

        // Deactivating 1 room leaves 1 sellable room >= peakDemand 1 => ALLOWED
        roomService.deactivateRoom(r1.getId());
        assertThat(sellableCapacity(p.getId(), rt.getId())).isEqualTo(1);

        // Deactivating the 2nd room leaves 0 sellable rooms < peakDemand 1 => REJECTED
        assertThatThrownBy(() -> roomService.deactivateRoom(r2.getId()))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cannot reduce sellable capacity");
    }

    @Test
    @DisplayName("Scenario F: Concurrent cancellation vs creation never exceeds capacity (5 trials)")
    void scenarioF_cancellationVsCreation() throws Exception {
        LocalDate from = LocalDate.of(2026, 12, 10);
        LocalDate to = LocalDate.of(2026, 12, 12);

        for (int trial = 0; trial < 5; trial++) {
            Property p = createProperty("F" + trial);
            RoomType rt = createRoomType(p.getId(), "DLX");
            createRoom(p.getId(), rt.getId(), "101");
            Guest g1 = createGuestForProperties("Guest F1", p.getId());

            var existing = reservationService.createReservation(buildCreateRequest(p.getId(), g1.getId(), from, to, List.of(rt.getId())));

            var outcomes = runWithBarrier(List.of(
                    () -> reservationService.cancelReservation(existing.getId()),
                    () -> reservationService.createReservation(buildCreateRequest(p.getId(), g1.getId(), from, to, List.of(rt.getId())))
            ));

            assertThat(outcomes.get(0).isSuccess()).isTrue();
            assertThat(activeReserved(p.getId(), rt.getId(), from, to))
                    .isLessThanOrEqualTo(sellableCapacity(p.getId(), rt.getId()));
        }
    }

    @Test
    @DisplayName("Scenario G: Concurrent same-room overlapping physical assignments yield clean BusinessValidationException without deadlock (5 trials)")
    void scenarioG_sameRoomOverlappingAssignments() throws Exception {
        LocalDate from = LocalDate.of(2026, 12, 10);
        LocalDate to = LocalDate.of(2026, 12, 12);

        for (int trial = 0; trial < 5; trial++) {
            Property p = createProperty("G" + trial);
            RoomType rt = createRoomType(p.getId(), "DLX");
            Room room101 = createRoom(p.getId(), rt.getId(), "101");
            createRoom(p.getId(), rt.getId(), "102");
            Guest g1 = createGuestForProperties("Guest G1", p.getId());

            var r1 = reservationService.createReservation(buildCreateRequest(p.getId(), g1.getId(), from, to, List.of(rt.getId())));
            var r2 = reservationService.createReservation(buildCreateRequest(p.getId(), g1.getId(), from, to, List.of(rt.getId())));

            UUID rr1 = reservationRoomRepository.findByReservationId(r1.getId()).get(0).getId();
            UUID rr2 = reservationRoomRepository.findByReservationId(r2.getId()).get(0).getId();

            RoomAssignmentCreateRequest req1 = new RoomAssignmentCreateRequest();
            req1.setReservationRoomId(rr1);
            req1.setRoomId(room101.getId());
            req1.setAssignedFrom(from);
            req1.setAssignedUntil(to);

            RoomAssignmentCreateRequest req2 = new RoomAssignmentCreateRequest();
            req2.setReservationRoomId(rr2);
            req2.setRoomId(room101.getId());
            req2.setAssignedFrom(from);
            req2.setAssignedUntil(to);

            var outcomes = runWithBarrier(List.of(
                    () -> roomAssignmentService.assignRoom(req1),
                    () -> roomAssignmentService.assignRoom(req2)
            ));

            long successes = outcomes.stream().filter(Outcome::isSuccess).count();
            long businessRejects = outcomes.stream().filter(o -> o.error() instanceof BusinessValidationException).count();
            assertThat(successes).isEqualTo(1);
            assertThat(businessRejects).isEqualTo(1);
            assertThat(roomAssignmentRepository.findByRoomIdAndStatus(room101.getId(), RoomAssignmentStatus.ASSIGNED))
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("Scenario H & I: Concurrent confirm vs cancel and concurrent updates serialize safely without deadlocks")
    void scenarioHI_sameReservationConfirmCancelAndUpdates() throws Exception {
        LocalDate from = LocalDate.of(2026, 12, 10);
        LocalDate to = LocalDate.of(2026, 12, 12);

        for (int trial = 0; trial < 5; trial++) {
            Property p = createProperty("H" + trial);
            RoomType rt = createRoomType(p.getId(), "DLX");
            createRoom(p.getId(), rt.getId(), "101");
            Guest g1 = createGuestForProperties("Guest H1", p.getId());

            var r = reservationService.createReservation(buildCreateRequest(p.getId(), g1.getId(), from, to, List.of(rt.getId())));
            var hc = runWithBarrier(List.of(
                    () -> reservationService.confirmReservation(r.getId()),
                    () -> reservationService.cancelReservation(r.getId())
            ));
            assertThat(hc.get(1).isSuccess()).isTrue();
            assertThat(reservationRepository.findById(r.getId()).orElseThrow().getStatus())
                    .isEqualTo(ReservationStatus.CANCELLED);

            var r2 = reservationService.createReservation(buildCreateRequest(p.getId(), g1.getId(), from, to, List.of(rt.getId())));
            ReservationUpdateRequest u1 = new ReservationUpdateRequest();
            u1.setAdults(2);
            u1.setSpecialRequests("Alpha");
            ReservationUpdateRequest u2 = new ReservationUpdateRequest();
            u2.setAdults(3);
            u2.setSpecialRequests("Beta");

            var iu = runWithBarrier(List.of(
                    () -> reservationService.updateReservation(r2.getId(), u1),
                    () -> reservationService.updateReservation(r2.getId(), u2)
            ));
            assertThat(iu.stream().filter(Outcome::isSuccess).count()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("Scenario J & Multi-RoomType Deadlock Prevention: Cross-property isolation and opposite-order multi-room-type requests never deadlock")
    void scenarioJ_crossPropertyAndMultiRoomTypeDeadlockFree() throws Exception {
        LocalDate from = LocalDate.of(2026, 12, 10);
        LocalDate to = LocalDate.of(2026, 12, 12);

        Property pA = createProperty("JA");
        Property pB = createProperty("JB");
        RoomType rtA1 = createRoomType(pA.getId(), "DLX1");
        RoomType rtA2 = createRoomType(pA.getId(), "DLX2");
        RoomType rtB1 = createRoomType(pB.getId(), "STD1");
        createRoom(pA.getId(), rtA1.getId(), "A1");
        createRoom(pA.getId(), rtA2.getId(), "A2");
        createRoom(pB.getId(), rtB1.getId(), "B1");

        Guest g = createGuestForProperties("Guest J", pA.getId(), pB.getId());

        // Opposite-order multi-room-type requests on Property A + concurrent request on Property B
        var outcomes = runWithBarrier(List.of(
                () -> reservationService.createReservation(buildCreateRequest(pA.getId(), g.getId(), from, to, List.of(rtA1.getId(), rtA2.getId()))),
                () -> reservationService.createReservation(buildCreateRequest(pA.getId(), g.getId(), from, to, List.of(rtA2.getId(), rtA1.getId()))),
                () -> reservationService.createReservation(buildCreateRequest(pB.getId(), g.getId(), from, to, List.of(rtB1.getId())))
        ));

        // Property A: exactly 1 of the 2 opposite-order requests succeeds, 1 rejected cleanly (0 deadlocks)
        long propASuccess = outcomes.subList(0, 2).stream().filter(Outcome::isSuccess).count();
        long propAReject = outcomes.subList(0, 2).stream().filter(o -> o.error() instanceof BusinessValidationException).count();
        assertThat(propASuccess).isEqualTo(1);
        assertThat(propAReject).isEqualTo(1);

        // Property B: independent property succeeds
        assertThat(outcomes.get(2).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Scenario E3: Partial cancelled quantities, long future reservations, and simultaneous room deactivations")
    void scenarioE3_partialCancelAndSimultaneousDeactivation() throws Exception {
        Property p = createProperty("E3");
        RoomType rt = createRoomType(p.getId(), "DLX");
        Room r1 = createRoom(p.getId(), rt.getId(), "101");
        Room r2 = createRoom(p.getId(), rt.getId(), "102");
        Guest g = createGuestForProperties("Guest E3", p.getId());

        // Create reservation with quantity=2 far in the future, then partially cancel 1 unit => active demand = 1
        var req = buildCreateRequest(p.getId(), g.getId(),
                LocalDate.of(2027, 6, 1), LocalDate.of(2027, 6, 10), List.of(rt.getId()));
        req.getReservationRooms().get(0).setQuantity(2);
        var created = reservationService.createReservation(req);

        ReservationRoom rr = reservationRoomRepository.findByReservationId(created.getId()).get(0);
        rr.setCancelledQuantity(1);
        reservationRoomRepository.saveAndFlush(rr);

        // Two simultaneous room deactivations competing for 2 rooms when peak demand = 1:
        // Exactly 1 deactivation succeeds (leaving 1 sellable room == peak demand 1), 1 fails!
        var outcomes = runWithBarrier(List.of(
                () -> { roomService.deactivateRoom(r1.getId()); return true; },
                () -> { roomService.deactivateRoom(r2.getId()); return true; }
        ));

        assertThat(outcomes.stream().filter(Outcome::isSuccess).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o.error() instanceof BusinessValidationException).count()).isEqualTo(1);
        assertThat(sellableCapacity(p.getId(), rt.getId())).isEqualTo(1);

        // Reactivating the deactivated room restores capacity = 2
        UUID deactivatedId = outcomes.get(0).isSuccess() ? r1.getId() : r2.getId();
        roomService.activateRoom(deactivatedId);
        assertThat(sellableCapacity(p.getId(), rt.getId())).isEqualTo(2);

        // OUT_OF_SERVICE -> AVAILABLE transition increases capacity cleanly
        RoomStatusUpdateRequest oos = new RoomStatusUpdateRequest();
        oos.setStatus(RoomStatus.OUT_OF_SERVICE);
        roomService.updateRoomStatus(r1.getId(), oos);
        assertThat(sellableCapacity(p.getId(), rt.getId())).isEqualTo(1);

        RoomStatusUpdateRequest avail = new RoomStatusUpdateRequest();
        avail.setStatus(RoomStatus.AVAILABLE);
        roomService.updateRoomStatus(r1.getId(), avail);
        assertThat(sellableCapacity(p.getId(), rt.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("Scenario K: Reservation confirmation under concurrent room deactivation preserves invariants")
    void scenarioK_confirmationUnderConcurrentDeactivation() throws Exception {
        Property p = createProperty("K");
        RoomType rt = createRoomType(p.getId(), "DLX");
        Room r1 = createRoom(p.getId(), rt.getId(), "101");
        Guest g = createGuestForProperties("Guest K", p.getId());

        var pending = reservationService.createReservation(buildCreateRequest(
                p.getId(), g.getId(), LocalDate.of(2026, 12, 20), LocalDate.of(2026, 12, 22), List.of(rt.getId())));

        // Deactivate r1 concurrently with confirming the PENDING reservation:
        // PENDING already holds 1 unit of demand, so deactivation must be rejected while confirmation succeeds
        var outcomes = runWithBarrier(List.of(
                () -> reservationService.confirmReservation(pending.getId()),
                () -> { roomService.deactivateRoom(r1.getId()); return true; }
        ));

        assertThat(outcomes.get(0).isSuccess()).isTrue();
        assertThat(outcomes.get(1).error()).isInstanceOf(BusinessValidationException.class);
        assertThat(sellableCapacity(p.getId(), rt.getId())).isEqualTo(1);
    }
}
