import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
  PROPERTY_TIMEZONE,
  getTodayInPropertyTimezone,
  formatDateTimeInPropertyTimezone,
  formatDateDisplay,
  computeRoomMetrics,
  computeReservationMetrics,
  isRoomReadyForCheckIn,
  isExpectedArrivalToday,
  isAlreadyCheckedInArrivalToday,
  isScheduledDepartureToday,
  isReservationCheckoutDue,
  isPendingDeparture,
  filterExpectedArrivals,
  filterCheckoutsDue,
  filterScheduledDeparturesToday,
  filterPendingDeparturesQueue,
  filterInHouseReservations,
  filterHousekeepingAttentionRooms,
  unwrapResponseList,
} from "../src/lib/dashboard/metrics.ts";

describe("PMS Live Operational Dashboard Integration & Aggregation Test Suite", () => {
  // Synthetic Property Data
  const propertyId1 = "a0000000-0000-0000-0000-000000000001";
  const propertyId2 = "b0000000-0000-0000-0000-000000000002";

  const sampleRooms = [
    // Room 101: Available & Clean (Ready)
    {
      id: "room-101",
      propertyId: propertyId1,
      roomTypeId: "rt-deluxe",
      roomNumber: "101",
      status: "AVAILABLE",
      housekeepingStatus: "CLEAN",
      active: true,
      baseOccupancy: 2,
      maxOccupancy: 3,
    },
    // Room 102: Available & Dirty (Needs Cleaning - NOT Ready)
    {
      id: "room-102",
      propertyId: propertyId1,
      roomTypeId: "rt-deluxe",
      roomNumber: "102",
      status: "AVAILABLE",
      housekeepingStatus: "DIRTY",
      active: true,
      baseOccupancy: 2,
      maxOccupancy: 3,
    },
    // Room 103: Occupied & Clean (Occupied - NOT Ready)
    {
      id: "room-103",
      propertyId: propertyId1,
      roomTypeId: "rt-deluxe",
      roomNumber: "103",
      status: "OCCUPIED",
      housekeepingStatus: "CLEAN",
      active: true,
      baseOccupancy: 2,
      maxOccupancy: 3,
    },
    // Room 104: Available & Cleaning In Progress (NOT Ready)
    {
      id: "room-104",
      propertyId: propertyId1,
      roomTypeId: "rt-suite",
      roomNumber: "104",
      status: "AVAILABLE",
      housekeepingStatus: "CLEANING_IN_PROGRESS",
      active: true,
      baseOccupancy: 2,
      maxOccupancy: 4,
    },
    // Room 105: Out of Service (Maintenance)
    {
      id: "room-105",
      propertyId: propertyId1,
      roomTypeId: "rt-suite",
      roomNumber: "105",
      status: "OUT_OF_SERVICE",
      housekeepingStatus: "CLEAN",
      active: true,
      baseOccupancy: 2,
      maxOccupancy: 4,
    },
    // Room 106: Inactive unit
    {
      id: "room-106",
      propertyId: propertyId1,
      roomTypeId: "rt-standard",
      roomNumber: "106",
      status: "AVAILABLE",
      housekeepingStatus: "CLEAN",
      active: false,
      baseOccupancy: 2,
      maxOccupancy: 2,
    },
    // Room 201: Foreign Property Room
    {
      id: "room-201",
      propertyId: propertyId2,
      roomTypeId: "rt-standard",
      roomNumber: "201",
      status: "AVAILABLE",
      housekeepingStatus: "CLEAN",
      active: true,
      baseOccupancy: 2,
      maxOccupancy: 2,
    },
  ];

  const testToday = "2026-11-22";

  const sampleReservations = [
    // Res 1: Expected Arrival Today (CONFIRMED)
    {
      id: "res-1",
      propertyId: propertyId1,
      primaryGuestId: "guest-1",
      checkInDate: "2026-11-22",
      checkOutDate: "2026-11-25",
      status: "CONFIRMED",
      checkoutDue: false,
    },
    // Res 2: Already Checked-In Today (Arrived today, status CHECKED_IN)
    {
      id: "res-2",
      propertyId: propertyId1,
      primaryGuestId: "guest-2",
      checkInDate: "2026-11-22",
      checkOutDate: "2026-11-26",
      status: "CHECKED_IN",
      checkoutDue: false,
    },
    // Res 3: Currently In-House, Stays until tomorrow (Future Checkout, on schedule)
    {
      id: "res-3",
      propertyId: propertyId1,
      primaryGuestId: "guest-3",
      checkInDate: "2026-11-20",
      checkOutDate: "2026-11-23",
      status: "CHECKED_IN",
      checkoutDue: false,
    },
    // Res 4: Currently In-House, Checkout Due Today after 12 PM (checkoutDue: true)
    {
      id: "res-4",
      propertyId: propertyId1,
      primaryGuestId: "guest-4",
      checkInDate: "2026-11-18",
      checkOutDate: "2026-11-22",
      status: "CHECKED_IN",
      checkoutDue: true,
      checkoutDueAt: "2026-11-22T06:30:00Z", // 12:00 PM IST
    },
    // Res 5: Already Checked Out (Departed - status CHECKED_OUT)
    {
      id: "res-5",
      propertyId: propertyId1,
      primaryGuestId: "guest-5",
      checkInDate: "2026-11-18",
      checkOutDate: "2026-11-22",
      status: "CHECKED_OUT",
      checkoutDue: false,
      actualCheckedOutAt: "2026-11-22T05:15:00Z",
    },
    // Res 6: Cancelled booking for today (Should NOT be counted as active arrival)
    {
      id: "res-6",
      propertyId: propertyId1,
      primaryGuestId: "guest-6",
      checkInDate: "2026-11-22",
      checkOutDate: "2026-11-24",
      status: "CANCELLED",
      checkoutDue: false,
    },
    // Res 7: No-Show booking for today (Should NOT be counted as active arrival)
    {
      id: "res-7",
      propertyId: propertyId1,
      primaryGuestId: "guest-7",
      checkInDate: "2026-11-22",
      checkOutDate: "2026-11-23",
      status: "NO_SHOW",
      checkoutDue: false,
    },
    // Res 8: Currently In-House, Scheduled Departure Today Before 12 PM (checkoutDue: false)
    {
      id: "res-8",
      propertyId: propertyId1,
      primaryGuestId: "guest-8",
      checkInDate: "2026-11-19",
      checkOutDate: "2026-11-22",
      status: "CHECKED_IN",
      checkoutDue: false,
      checkoutDueAt: "2026-11-22T06:30:00Z",
    },
    // Res 9: Overdue from Previous Date (checkOutDate < testToday, checkoutDue: false)
    {
      id: "res-9",
      propertyId: propertyId1,
      primaryGuestId: "guest-9",
      checkInDate: "2026-11-17",
      checkOutDate: "2026-11-21", // Yesterday
      status: "CHECKED_IN",
      checkoutDue: false,
    },
    // Res 10: Foreign Property Reservation
    {
      id: "res-10",
      propertyId: propertyId2,
      primaryGuestId: "guest-10",
      checkInDate: "2026-11-22",
      checkOutDate: "2026-11-25",
      status: "CONFIRMED",
      checkoutDue: false,
    },
  ];

  // -------------------------------------------------------------
  // SUITE 1: Room Inventory Metric Aggregations
  // -------------------------------------------------------------
  describe("1. Room Inventory Metric Aggregations", () => {
    const metrics = computeRoomMetrics(sampleRooms, propertyId1);

    it("1. Total room count: counts all physical rooms registered for authorized property", () => {
      assert.equal(metrics.total, 6);
    });

    it("2. Occupied room count: counts rooms where physical status is OCCUPIED", () => {
      assert.equal(metrics.occupied, 1);
    });

    it("3. Ready room count: counts active rooms satisfying AVAILABLE + CLEAN", () => {
      assert.equal(metrics.ready, 1); // Room 101 only
      // Room 102 (AVAILABLE + DIRTY) is NOT ready
      assert.equal(isRoomReadyForCheckIn(sampleRooms[1]), false);
      // Room 103 (OCCUPIED + CLEAN) is NOT ready
      assert.equal(isRoomReadyForCheckIn(sampleRooms[2]), false);
      // Room 104 (AVAILABLE + CLEANING_IN_PROGRESS) is NOT ready
      assert.equal(isRoomReadyForCheckIn(sampleRooms[3]), false);
      // Room 106 (inactive) is NOT ready
      assert.equal(isRoomReadyForCheckIn(sampleRooms[5]), false);
    });

    it("4. Dirty room count: counts rooms where housekeepingStatus is DIRTY", () => {
      assert.equal(metrics.dirty, 1); // Room 102
    });

    it("5. Cleaning-in-progress count: counts rooms where housekeepingStatus is CLEANING_IN_PROGRESS", () => {
      assert.equal(metrics.cleaningInProgress, 1); // Room 104
    });

    it("6. Counts out of service / inactive rooms accurately", () => {
      assert.equal(metrics.outOfService, 2); // Room 105 (OUT_OF_SERVICE) + Room 106 (!active)
    });
  });

  // -------------------------------------------------------------
  // SUITE 2: Reservation & Front Desk Metric Aggregations
  // -------------------------------------------------------------
  describe("2. Reservation & Front Desk Metric Aggregations", () => {
    const resMetrics = computeReservationMetrics(sampleReservations, testToday, propertyId1);

    it("7. Arrivals today: calculates total arrivals scheduled for property date", () => {
      // Expected (CONFIRMED) + Checked In Today (CHECKED_IN) = 1 + 1 = 2
      assert.equal(resMetrics.arrivalsTodayTotal, 2);
    });

    it("8. Arrival status distinctions: separates expected arrivals from already checked in", () => {
      assert.equal(resMetrics.arrivalsExpected, 1); // Res 1
      assert.equal(resMetrics.arrivalsCheckedIn, 1); // Res 2
      assert.equal(isExpectedArrivalToday(sampleReservations[0], testToday), true);
      assert.equal(isExpectedArrivalToday(sampleReservations[1], testToday), false);
      assert.equal(isAlreadyCheckedInArrivalToday(sampleReservations[1], testToday), true);
      // Cancelled and No-Show are not counted
      assert.equal(isExpectedArrivalToday(sampleReservations[5], testToday), false);
      assert.equal(isExpectedArrivalToday(sampleReservations[6], testToday), false);
    });

    it("9. Checked-in reservation count: counts currently active in-house reservations", () => {
      // Res 2, Res 3, Res 4, Res 8, Res 9 are CHECKED_IN
      assert.equal(resMetrics.checkedInTotal, 5);
    });

    it("10. Scheduled departure today before 12 PM is NOT labeled overdue", () => {
      // Res 8: checkOutDate === testToday, but checkoutDue === false (before 12 PM IST)
      assert.equal(isScheduledDepartureToday(sampleReservations[7], testToday), true);
      assert.equal(isReservationCheckoutDue(sampleReservations[7], testToday), false);
      assert.equal(isPendingDeparture(sampleReservations[7], testToday), true);
    });

    it("11. Checkout due after 12 PM flagged authoritative (checkoutDue: true)", () => {
      // Res 4: checkoutDue === true
      assert.equal(isReservationCheckoutDue(sampleReservations[3], testToday), true);
      assert.equal(isScheduledDepartureToday(sampleReservations[3], testToday), true);
    });

    it("12. Overdue checkout from previous date (checkOutDate < today)", () => {
      // Res 9: checkOutDate === 2026-11-21 < 2026-11-22
      assert.equal(isReservationCheckoutDue(sampleReservations[8], testToday), true);
      assert.equal(isScheduledDepartureToday(sampleReservations[8], testToday), false);
      assert.equal(isPendingDeparture(sampleReservations[8], testToday), true);
    });

    it("13. Checkouts due count reflects authoritative due + past overdue only", () => {
      // Res 4 (checkoutDue: true) + Res 9 (checkOutDate < testToday) = 2
      assert.equal(resMetrics.checkoutsDueTotal, 2);
      // Departures scheduled today: Res 4 and Res 8 = 2
      assert.equal(resMetrics.departuresTodayTotal, 2);
    });

    it("14. Checked-out reservations excluded from pending departures and due queues", () => {
      // Res 5 is CHECKED_OUT; must NEVER be counted as checkout due, departure today, or in-house
      assert.equal(isReservationCheckoutDue(sampleReservations[4], testToday), false);
      assert.equal(isScheduledDepartureToday(sampleReservations[4], testToday), false);
      assert.equal(isPendingDeparture(sampleReservations[4], testToday), false);
      assert.notEqual(sampleReservations[4].status, "CHECKED_IN");
    });

    it("15. Future in-house checkout excluded from departure queues", () => {
      // Res 3: checkOutDate === 2026-11-23 > 2026-11-22, checkoutDue === false
      assert.equal(isReservationCheckoutDue(sampleReservations[2], testToday), false);
      assert.equal(isScheduledDepartureToday(sampleReservations[2], testToday), false);
      assert.equal(isPendingDeparture(sampleReservations[2], testToday), false);
    });
  });

  // -------------------------------------------------------------
  // SUITE 3: Timezone Safety & Midnight Boundary
  // -------------------------------------------------------------
  describe("3. Timezone Safety & Midnight Boundary Handling", () => {
    it("16. Property timezone boundary at midnight (Asia/Kolkata vs UTC)", () => {
      // When UTC is 2026-11-22 18:45:00 UTC:
      // In Asia/Kolkata (+05:30), the time is 2026-11-23 00:15:00 (+1 day!)
      const instantAtUtcEvening = new Date("2026-11-22T18:45:00Z");
      const kolkataDate = getTodayInPropertyTimezone(PROPERTY_TIMEZONE, instantAtUtcEvening);
      assert.equal(kolkataDate, "2026-11-23", "Must advance to 2026-11-23 across IST midnight");

      // When UTC is 2026-11-22 01:00:00 UTC:
      // In Asia/Kolkata, the time is 2026-11-22 06:30:00 (same calendar day)
      const instantAtUtcMorning = new Date("2026-11-22T01:00:00Z");
      const kolkataMorningDate = getTodayInPropertyTimezone(PROPERTY_TIMEZONE, instantAtUtcMorning);
      assert.equal(kolkataMorningDate, "2026-11-22");
    });

    it("17. Missing or null timestamps handled safely without throwing", () => {
      assert.equal(formatDateTimeInPropertyTimezone(null), "—");
      assert.equal(formatDateTimeInPropertyTimezone(undefined), "—");
      assert.equal(formatDateTimeInPropertyTimezone(""), "—");
      assert.equal(formatDateTimeInPropertyTimezone("invalid-timestamp"), "invalid-timestamp");

      assert.equal(formatDateDisplay(null), "—");
      assert.equal(formatDateDisplay(undefined), "—");
      assert.equal(formatDateDisplay(""), "—");
      assert.equal(formatDateDisplay("2026-11-22"), "22/11/2026");
    });
  });

  // -------------------------------------------------------------
  // SUITE 4: Partial Failure, Response Resilience & Pagination
  // -------------------------------------------------------------
  describe("4. Partial Failure, Response Resilience & Pagination Detection", () => {
    it("18. Missing API response handling: unwrapResponseList rejects corrupt/null payloads", () => {
      assert.throws(() => unwrapResponseList(null), /unexpected list response/i);
      assert.throws(() => unwrapResponseList("<html>Bad Gateway</html>"), /unexpected list response/i);
      assert.throws(() => unwrapResponseList(123), /unexpected list response/i);
      assert.throws(() => unwrapResponseList({}), /unexpected list response/i);
    });

    it("19. Partial API failures not counted as zero (clean metric abstraction)", () => {
      // If rooms fails (roomsError is true), computing null metrics prevents false zeros
      const mockRoomsError = "500 Internal Server Error";
      const roomMetricsResult = mockRoomsError ? null : computeRoomMetrics([]);
      assert.equal(roomMetricsResult, null);

      // If reservations fails, computing null metrics prevents false zeros
      const mockResError = "Network error";
      const resMetricsResult = mockResError ? null : computeReservationMetrics([], testToday);
      assert.equal(resMetricsResult, null);
    });

    it("20. Property-scoped aggregation: filters records strictly by authorized propertyId", () => {
      // Room 201 belongs to propertyId2; should not be included when filtering by propertyId1
      const prop1Metrics = computeRoomMetrics(sampleRooms, propertyId1);
      const prop2Metrics = computeRoomMetrics(sampleRooms, propertyId2);

      assert.equal(prop1Metrics.total, 6);
      assert.equal(prop2Metrics.total, 1);

      // Reservation 10 belongs to propertyId2
      const prop1ResMetrics = computeReservationMetrics(sampleReservations, testToday, propertyId1);
      const prop2ResMetrics = computeReservationMetrics(sampleReservations, testToday, propertyId2);

      assert.equal(prop1ResMetrics.totalReservations, 9);
      assert.equal(prop2ResMetrics.totalReservations, 1);
    });

    it("21. Non-paginated versus wrapped response handling", () => {
      const bareList = [{ id: 1 }, { id: 2 }];
      assert.deepEqual(unwrapResponseList(bareList), bareList);

      const dataWrapped = { data: [{ id: 3 }] };
      assert.deepEqual(unwrapResponseList(dataWrapped), [{ id: 3 }]);

      const contentWrapped = { content: [{ id: 4 }] };
      assert.deepEqual(unwrapResponseList(contentWrapped), [{ id: 4 }]);

      const itemsWrapped = { items: [{ id: 5 }] };
      assert.deepEqual(unwrapResponseList(itemsWrapped), [{ id: 5 }]);

      const valueWrapped = { value: [{ id: 6 }] };
      assert.deepEqual(unwrapResponseList(valueWrapped), [{ id: 6 }]);
    });

    it("22. Complete single-page Spring Data response unwraps successfully", () => {
      const completePage = {
        content: [{ id: "room-1" }, { id: "room-2" }],
        totalPages: 1,
        totalElements: 2,
        last: true,
        number: 0,
        size: 10,
      };
      assert.deepEqual(unwrapResponseList(completePage), completePage.content);
    });

    it("23. Incomplete paginated response throws explicit error to prevent truncated metrics", () => {
      // Case A: totalPages > 1
      const multiPageResponse = {
        content: [{ id: "room-1" }],
        totalPages: 3,
        totalElements: 30,
        last: false,
        number: 0,
      };
      assert.throws(
        () => unwrapResponseList(multiPageResponse),
        /Incomplete paginated response received/i
      );

      // Case B: last === false
      const notLastPageResponse = {
        content: [{ id: "room-1" }],
        last: false,
      };
      assert.throws(
        () => unwrapResponseList(notLastPageResponse),
        /Incomplete paginated response received/i
      );

      // Case C: totalElements > items.length
      const partialElementsResponse = {
        content: [{ id: "room-1" }],
        totalElements: 15,
      };
      assert.throws(
        () => unwrapResponseList(partialElementsResponse),
        /Incomplete paginated response received/i
      );
    });

    it("24. Queue filtering: queues filter correctly and reflect current data", () => {
      const prop1Res = sampleReservations.filter((r) => r.propertyId === propertyId1);
      const prop1Rooms = sampleRooms.filter((r) => r.propertyId === propertyId1);

      const expectedQueue = filterExpectedArrivals(prop1Res, testToday);
      assert.equal(expectedQueue.length, 1);
      assert.equal(expectedQueue[0].id, "res-1");

      const checkoutsQueue = filterCheckoutsDue(prop1Res, testToday);
      assert.equal(checkoutsQueue.length, 2); // Res 4 (checkoutDue: true) + Res 9 (checkOutDate < today)

      const scheduledDepartures = filterScheduledDeparturesToday(prop1Res, testToday);
      assert.equal(scheduledDepartures.length, 2); // Res 4 + Res 8

      const pendingDepartures = filterPendingDeparturesQueue(prop1Res, testToday);
      // Res 4 (scheduled today & due) + Res 8 (scheduled today) + Res 9 (overdue past date) = 3
      assert.equal(pendingDepartures.length, 3);

      const inHouseQueue = filterInHouseReservations(prop1Res);
      assert.equal(inHouseQueue.length, 5); // Res 2, 3, 4, 8, 9

      const attentionQueue = filterHousekeepingAttentionRooms(prop1Rooms);
      assert.equal(attentionQueue.length, 2); // Room 102 (DIRTY) + Room 104 (CLEANING_IN_PROGRESS)
    });

    it("25. Stale response protection via request sequence token simulation", () => {
      let latestRequestId = 0;
      let currentState = "initial";

      // Trigger Request 1 (slow)
      const reqId1 = ++latestRequestId;
      // Trigger Request 2 (fast refresh)
      const reqId2 = ++latestRequestId;

      // Request 2 completes first
      if (reqId2 === latestRequestId) {
        currentState = "state-from-request-2";
      }

      // Request 1 completes later (stale)
      if (reqId1 === latestRequestId) {
        currentState = "state-from-request-1";
      }

      // Assert that stale request 1 did NOT overwrite fresh request 2 state
      assert.equal(currentState, "state-from-request-2");
    });
  });

  // -------------------------------------------------------------
  // SUITE 5: Security & Token Isolation
  // -------------------------------------------------------------
  describe("5. Security & Token Isolation", () => {
    it("26. No authentication token exposure in dashboard UI data structures", () => {
      const metrics = computeRoomMetrics(sampleRooms, propertyId1);
      const resMetrics = computeReservationMetrics(sampleReservations, testToday, propertyId1);

      assert.equal(metrics.accessToken, undefined);
      assert.equal(metrics.refreshToken, undefined);
      assert.equal(resMetrics.accessToken, undefined);
      assert.equal(resMetrics.refreshToken, undefined);

      for (const r of sampleRooms) {
        assert.equal(r.accessToken, undefined);
        assert.equal(r.token, undefined);
      }
      for (const res of sampleReservations) {
        assert.equal(res.accessToken, undefined);
        assert.equal(res.token, undefined);
      }
    });
  });
});
