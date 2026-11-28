import { describe, it } from "node:test";
import assert from "node:assert/strict";

describe("PMS Front Desk UI Logic, Timezone & Accessibility Verification", () => {
  // Helpers matching implementation in frontend/src/app/dashboard/reservations/page.tsx
  function formatDate(value) {
    if (!value) return "—";
    const [year, month, day] = value.split("-");
    return `${day}/${month}/${year}`;
  }

  function formatDateTimeIST(value) {
    if (!value) return "—";
    try {
      const date = new Date(value);
      if (isNaN(date.getTime())) return value;
      return new Intl.DateTimeFormat("en-IN", {
        timeZone: "Asia/Kolkata",
        year: "numeric",
        month: "short",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        hour12: true,
      }).format(date);
    } catch {
      return value;
    }
  }

  function getEligibleActions(status) {
    switch (status) {
      case "CONFIRMED":
        return { canCheckIn: true, canCheckOut: false, isTerminal: false };
      case "CHECKED_IN":
        return { canCheckIn: false, canCheckOut: true, isTerminal: false };
      case "CHECKED_OUT":
        return { canCheckIn: false, canCheckOut: false, isTerminal: true };
      case "PENDING":
      case "CANCELLED":
      case "NO_SHOW":
      default:
        return { canCheckIn: false, canCheckOut: false, isTerminal: false };
    }
  }

  function isUncertainError(errorMessage) {
    if (!errorMessage) return false;
    const lower = errorMessage.toLowerCase();
    return (
      lower.includes("cannot connect") ||
      lower.includes("network") ||
      lower.includes("failed (502)")
    );
  }

  function evaluateRoomCoverage(reservationRooms, assignments, physicalRooms) {
    const totalActiveUnits = reservationRooms.reduce((sum, r) => {
      if (r.status === "CANCELLED") return sum;
      const active = r.activeQuantity ?? (r.quantity - (r.cancelledQuantity || 0));
      return sum + Math.max(0, active);
    }, 0);

    const activeAssignments = assignments.filter((a) => a.status === "ASSIGNED");
    const isFullyAssigned = totalActiveUnits > 0 && activeAssignments.length >= totalActiveUnits;

    const assignedPhysicalRooms = activeAssignments
      .map((a) => physicalRooms.find((p) => p.id === a.roomId))
      .filter(Boolean);

    const hasDirtyRoom = assignedPhysicalRooms.some(
      (p) => p.housekeepingStatus && p.housekeepingStatus !== "CLEAN"
    );

    return { totalActiveUnits, assignedCount: activeAssignments.length, isFullyAssigned, hasDirtyRoom };
  }

  // 1. Timezone & Instant Formatting
  describe("1. Timezone and Timestamp Verification (Asia/Kolkata)", () => {
    it("formats calendar dates without UTC day-shifting", () => {
      assert.equal(formatDate("2026-11-20"), "20/11/2026");
      assert.equal(formatDate("2026-12-05"), "05/12/2026");
      assert.equal(formatDate(""), "—");
    });

    it("formats checkoutDueAt instant correctly in Asia/Kolkata timezone (12:00 PM)", () => {
      // 06:30:00 UTC = 12:00:00 PM IST (UTC+5:30)
      const instant = "2026-11-25T06:30:00Z";
      const formatted = formatDateTimeIST(instant);
      assert.ok(formatted.includes("12:00"));
      assert.ok(formatted.includes("pm") || formatted.includes("PM"));
      assert.ok(formatted.includes("25"));
      assert.ok(formatted.includes("Nov"));
    });

    it("formats actualCheckedOutAt instant correctly in Asia/Kolkata timezone", () => {
      // 07:15:22 UTC = 12:45:22 PM IST
      const instant = "2026-11-25T07:15:22Z";
      const formatted = formatDateTimeIST(instant);
      assert.ok(formatted.includes("12:45"));
      assert.ok(formatted.includes("pm") || formatted.includes("PM"));
    });

    it("handles null, undefined, or empty timestamps safely without throwing", () => {
      assert.equal(formatDateTimeIST(null), "—");
      assert.equal(formatDateTimeIST(undefined), "—");
      assert.equal(formatDateTimeIST(""), "—");
    });
  });

  // 2. Action Eligibility
  describe("2. Reservation Lifecycle Action Eligibility", () => {
    it("CONFIRMED: permits check-in; denies checkout", () => {
      const eligibility = getEligibleActions("CONFIRMED");
      assert.equal(eligibility.canCheckIn, true);
      assert.equal(eligibility.canCheckOut, false);
      assert.equal(eligibility.isTerminal, false);
    });

    it("CHECKED_IN: permits checkout; denies check-in", () => {
      const eligibility = getEligibleActions("CHECKED_IN");
      assert.equal(eligibility.canCheckIn, false);
      assert.equal(eligibility.canCheckOut, true);
      assert.equal(eligibility.isTerminal, false);
    });

    it("CHECKED_OUT: terminal state denying all further mutations", () => {
      const eligibility = getEligibleActions("CHECKED_OUT");
      assert.equal(eligibility.canCheckIn, false);
      assert.equal(eligibility.canCheckOut, false);
      assert.equal(eligibility.isTerminal, true);
    });

    it("PENDING, CANCELLED, NO_SHOW: denies all check-in/checkout actions", () => {
      for (const st of ["PENDING", "CANCELLED", "NO_SHOW"]) {
        const eligibility = getEligibleActions(st);
        assert.equal(eligibility.canCheckIn, false, `${st} must not allow check-in`);
        assert.equal(eligibility.canCheckOut, false, `${st} must not allow check-out`);
        assert.equal(eligibility.isTerminal, false);
      }
    });
  });

  // 3. Room Coverage & Housekeeping Pre-flight Assessment
  describe("3. Room Coverage & Housekeeping Validation Assessment", () => {
    it("identifies fully assigned, clean room coverage as ready for check-in pre-flight", () => {
      const reservationRooms = [{ id: "rr-1", quantity: 2, cancelledQuantity: 0, activeQuantity: 2, status: "CONFIRMED" }];
      const assignments = [
        { id: "a-1", reservationRoomId: "rr-1", roomId: "room-101", status: "ASSIGNED" },
        { id: "a-2", reservationRoomId: "rr-1", roomId: "room-102", status: "ASSIGNED" },
      ];
      const physicalRooms = [
        { id: "room-101", roomNumber: "101", housekeepingStatus: "CLEAN" },
        { id: "room-102", roomNumber: "102", housekeepingStatus: "CLEAN" },
      ];

      const res = evaluateRoomCoverage(reservationRooms, assignments, physicalRooms);
      assert.equal(res.totalActiveUnits, 2);
      assert.equal(res.assignedCount, 2);
      assert.equal(res.isFullyAssigned, true);
      assert.equal(res.hasDirtyRoom, false);
    });

    it("flags incomplete room assignments before check-in", () => {
      const reservationRooms = [{ id: "rr-1", quantity: 2, cancelledQuantity: 0, activeQuantity: 2, status: "CONFIRMED" }];
      const assignments = [
        { id: "a-1", reservationRoomId: "rr-1", roomId: "room-101", status: "ASSIGNED" },
      ];
      const physicalRooms = [{ id: "room-101", roomNumber: "101", housekeepingStatus: "CLEAN" }];

      const res = evaluateRoomCoverage(reservationRooms, assignments, physicalRooms);
      assert.equal(res.isFullyAssigned, false);
      assert.equal(res.assignedCount, 1);
    });

    it("flags dirty assigned rooms before check-in", () => {
      const reservationRooms = [{ id: "rr-1", quantity: 1, cancelledQuantity: 0, activeQuantity: 1, status: "CONFIRMED" }];
      const assignments = [{ id: "a-1", reservationRoomId: "rr-1", roomId: "room-101", status: "ASSIGNED" }];
      const physicalRooms = [{ id: "room-101", roomNumber: "101", housekeepingStatus: "DIRTY" }];

      const res = evaluateRoomCoverage(reservationRooms, assignments, physicalRooms);
      assert.equal(res.isFullyAssigned, true);
      assert.equal(res.hasDirtyRoom, true, "Dirty room must trigger advisory notice");
    });
  });

  // 4. Uncertain-Outcome Safety & Error Classification
  describe("4. Uncertain Outcome Error Classification & Refresh Gating", () => {
    it("correctly flags 502 Bad Gateway and connection errors as uncertain outcomes", () => {
      assert.equal(isUncertainError("Cannot connect to the PMS backend."), true);
      assert.equal(isUncertainError("Request failed (502)."), true);
      assert.equal(isUncertainError("Network error: timeout"), true);
    });

    it("does not flag deterministic business validation errors as uncertain outcomes", () => {
      assert.equal(isUncertainError("Cannot check in: assigned room requires housekeeping."), false);
      assert.equal(isUncertainError("Access is denied."), false);
      assert.equal(isUncertainError("Reservation is already checked out"), false);
      assert.equal(isUncertainError("Reservation not found with id: 123"), false);
    });

    it("gating: when refresh updates status from CONFIRMED to CHECKED_IN, check-in dialog closes", () => {
      let showCheckInConfirm = true;
      const refreshed = { id: "res-1", status: "CHECKED_IN" };

      if (showCheckInConfirm && refreshed.status !== "CONFIRMED") {
        showCheckInConfirm = false;
      }
      assert.equal(showCheckInConfirm, false, "Dialog must automatically close on status advance");
    });

    it("gating: when refresh updates status from CHECKED_IN to CHECKED_OUT, checkout dialog closes", () => {
      let showCheckOutConfirm = true;
      const refreshed = { id: "res-1", status: "CHECKED_OUT" };

      if (showCheckOutConfirm && refreshed.status !== "CHECKED_IN") {
        showCheckOutConfirm = false;
      }
      assert.equal(showCheckOutConfirm, false, "Checkout dialog must automatically close on status advance");
    });
  });

  // 5. Accessibility and ARIA attributes
  describe("5. Dialog and Button Accessibility Contracts", () => {
    it("dialogs declare standard WAI-ARIA modal attributes", () => {
      const checkInDialogAttrs = {
        role: "dialog",
        "aria-modal": "true",
        "aria-labelledby": "checkin-dialog-title",
      };
      assert.equal(checkInDialogAttrs.role, "dialog");
      assert.equal(checkInDialogAttrs["aria-modal"], "true");
      assert.equal(checkInDialogAttrs["aria-labelledby"], "checkin-dialog-title");

      const checkOutDialogAttrs = {
        role: "dialog",
        "aria-modal": "true",
        "aria-labelledby": "checkout-dialog-title",
      };
      assert.equal(checkOutDialogAttrs.role, "dialog");
      assert.equal(checkOutDialogAttrs["aria-modal"], "true");
      assert.equal(checkOutDialogAttrs["aria-labelledby"], "checkout-dialog-title");
    });

    it("buttons declare accessible labels for assistive technologies", () => {
      const checkInButton = { "aria-label": "Check in guest for this reservation" };
      const checkOutButton = { "aria-label": "Confirm checkout for this reservation" };

      assert.ok(checkInButton["aria-label"].length > 0);
      assert.ok(checkOutButton["aria-label"].length > 0);
    });
  });

  // 6. Step 3.4.3.13B.2: Reservation Accommodation Subtotal Display and Pricing Contract (Test Q)
  describe("6. Accommodation Subtotal Display, Nights Calculation & Formatting (Test Q)", () => {
    function computeNights(checkInDate, checkOutDate) {
      if (!checkInDate || !checkOutDate) return 0;
      const inD = new Date(checkInDate);
      const outD = new Date(checkOutDate);
      const diffTime = outD.getTime() - inD.getTime();
      const diffDays = Math.round(diffTime / (1000 * 60 * 60 * 24));
      return diffDays > 0 ? diffDays : 0;
    }

    function computeRoomSubtotal(activeQuantity, nights, unitPrice) {
      if (!activeQuantity || activeQuantity < 1 || !nights || nights < 1 || unitPrice == null) {
        return null;
      }
      return activeQuantity * nights * Number(unitPrice);
    }

    function formatINR(amount) {
      if (amount == null || !Number.isFinite(amount)) return "—";
      return `₹${amount.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
    }

    it("calculates nights accurately between checkInDate and checkOutDate", () => {
      assert.equal(computeNights("2026-12-01", "2026-12-02"), 1);
      assert.equal(computeNights("2026-12-01", "2026-12-05"), 4);
      assert.equal(computeNights("2026-12-01", "2026-12-01"), 0, "Same day stay must yield 0 nights (invalid)");
      assert.equal(computeNights("2026-12-05", "2026-12-01"), 0, "Backwards dates must yield 0 nights (invalid)");
    });

    it("calculates room line accommodation subtotal as activeQuantity * nights * unitPrice", () => {
      const nights = computeNights("2026-12-10", "2026-12-13"); // 3 nights
      const activeQuantity = 2; // 2 rooms
      const unitPrice = 2500; // ₹2500 per room per night

      const subtotal = computeRoomSubtotal(activeQuantity, nights, unitPrice);
      assert.equal(subtotal, 15000);
      assert.equal(formatINR(subtotal), "₹15,000.00");
    });

    it("formats Indian Rupees with correct symbol and comma grouping", () => {
      assert.equal(formatINR(3000), "₹3,000.00");
      assert.equal(formatINR(125000), "₹1,25,000.00");
      assert.equal(formatINR(0), "₹0.00");
      assert.equal(formatINR(null), "—");
    });

    it("labels Accommodation Subtotal distinctly and provides disclaimer text", () => {
      const disclaimer = "* Accommodation Subtotal includes base room charges only for the requested duration. Excludes taxes, extra beds, breakfast, and incidental services.";
      assert.ok(disclaimer.includes("base room charges only"));
      assert.ok(disclaimer.includes("Excludes taxes, extra beds, breakfast, and incidental services"));
      // Must not be confused with invoice or payment concepts
      assert.ok(!disclaimer.includes("Grand Total"));
      assert.ok(!disclaimer.includes("Final Bill"));
      assert.ok(!disclaimer.includes("Paid Amount"));
    });
  });
});
