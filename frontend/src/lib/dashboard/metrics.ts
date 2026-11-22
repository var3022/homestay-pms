import {
  type PhysicalRoom,
  type RoomStatus,
  type HousekeepingStatus,
  isRoomReadyForCheckIn,
} from "@/lib/rooms/housekeeping";

export { isRoomReadyForCheckIn };
export type { PhysicalRoom, RoomStatus, HousekeepingStatus };

export const PROPERTY_TIMEZONE = "Asia/Kolkata";

export type ReservationStatus =
  | "PENDING"
  | "CONFIRMED"
  | "CHECKED_IN"
  | "CHECKED_OUT"
  | "CANCELLED"
  | "NO_SHOW";

export interface Reservation {
  id: string;
  propertyId?: string;
  primaryGuestId: string;
  bookingSourceId?: string;
  checkInDate: string;
  checkOutDate: string;
  adults?: number;
  children?: number;
  infants?: number;
  status: ReservationStatus | string;
  checkoutDue: boolean;
  checkoutDueAt?: string | null;
  actualCheckedOutAt?: string | null;
  checkedOutByStaffUserId?: string | null;
  specialRequests?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface Guest {
  id: string;
  name: string;
  phone?: string | null;
  email?: string | null;
  active?: boolean;
}

export interface RoomType {
  id: string;
  name: string;
  code: string;
}

export interface RoomMetrics {
  total: number;
  occupied: number;
  ready: number;
  dirty: number;
  cleaningInProgress: number;
  outOfService: number;
}

export interface ReservationMetrics {
  totalReservations: number;
  arrivalsTodayTotal: number;
  arrivalsExpected: number;
  arrivalsCheckedIn: number;
  checkedInTotal: number;
  checkoutsDueTotal: number;
  departuresTodayTotal: number;
}

/**
 * Returns today's calendar date in the specified property timezone as "YYYY-MM-DD".
 * Defaults to Asia/Kolkata. Never relies on browser-local or UTC timestamps.
 */
export function getTodayInPropertyTimezone(
  timeZone: string = PROPERTY_TIMEZONE,
  referenceDate: Date = new Date()
): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(referenceDate);
}

/**
 * Formats an ISO instant in the property timezone (Asia/Kolkata).
 * e.g. "2026-11-22T06:30:00Z" -> "22 Nov 2026, 12:00 PM"
 */
export function formatDateTimeInPropertyTimezone(
  isoString?: string | null,
  timeZone: string = PROPERTY_TIMEZONE
): string {
  if (!isoString) return "—";
  try {
    const date = new Date(isoString);
    if (isNaN(date.getTime())) return isoString;
    return new Intl.DateTimeFormat("en-IN", {
      timeZone,
      year: "numeric",
      month: "short",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hour12: true,
    }).format(date);
  } catch {
    return isoString;
  }
}

/**
 * Formats a calendar date "YYYY-MM-DD" as "DD/MM/YYYY".
 */
export function formatDateDisplay(dateStr?: string | null): string {
  if (!dateStr) return "—";
  const parts = dateStr.split("-");
  if (parts.length === 3) {
    return `${parts[2]}/${parts[1]}/${parts[0]}`;
  }
  return dateStr;
}

/**
 * Derives live operational room metrics from physical room responses.
 *
 * Operational Definitions:
 * - total: Count of all physical rooms registered for the property.
 * - occupied: Count of physical rooms with RoomStatus === 'OCCUPIED'.
 * - ready: Count of active rooms with RoomStatus === 'AVAILABLE' AND HousekeepingStatus === 'CLEAN'.
 * - dirty: Count of rooms with HousekeepingStatus === 'DIRTY'.
 * - cleaningInProgress: Count of rooms with HousekeepingStatus === 'CLEANING_IN_PROGRESS'.
 * - outOfService: Count of rooms with RoomStatus === 'OUT_OF_SERVICE' or active === false.
 */
export function computeRoomMetrics(
  rooms: PhysicalRoom[],
  filterPropertyId?: string
): RoomMetrics {
  const targetRooms = filterPropertyId
    ? rooms.filter((r) => r.propertyId === filterPropertyId)
    : rooms;

  let occupied = 0;
  let ready = 0;
  let dirty = 0;
  let cleaningInProgress = 0;
  let outOfService = 0;

  for (const room of targetRooms) {
    if (room.status === "OCCUPIED") {
      occupied++;
    }
    if (isRoomReadyForCheckIn(room)) {
      ready++;
    }
    if (room.housekeepingStatus === "DIRTY") {
      dirty++;
    } else if (room.housekeepingStatus === "CLEANING_IN_PROGRESS") {
      cleaningInProgress++;
    }
    if (room.status === "OUT_OF_SERVICE" || !room.active) {
      outOfService++;
    }
  }

  return {
    total: targetRooms.length,
    occupied,
    ready,
    dirty,
    cleaningInProgress,
    outOfService,
  };
}

/**
 * Checks if a reservation is expected to arrive today and has not yet checked in.
 * Status must be CONFIRMED.
 */
export function isExpectedArrivalToday(
  reservation: Reservation,
  todayDate: string
): boolean {
  return (
    reservation.checkInDate === todayDate &&
    reservation.status === "CONFIRMED"
  );
}

/**
 * Checks if a reservation arrived today and has already completed check-in.
 * Status must be CHECKED_IN.
 */
export function isAlreadyCheckedInArrivalToday(
  reservation: Reservation,
  todayDate: string
): boolean {
  return (
    reservation.checkInDate === todayDate &&
    reservation.status === "CHECKED_IN"
  );
}

/**
 * Checks if a reservation is currently in-house and scheduled to depart today.
 * Status must be CHECKED_IN.
 * Note: Scheduled departures today are NOT considered overdue unless checkoutDue === true
 * (authoritative backend flag indicating 12:00 PM IST checkout deadline has passed).
 */
export function isScheduledDepartureToday(
  reservation: Reservation,
  todayDate: string
): boolean {
  return (
    reservation.status === "CHECKED_IN" &&
    reservation.checkOutDate === todayDate
  );
}

/**
 * Determines whether an active in-house reservation is due or overdue for checkout.
 *
 * Operational Rules:
 * 1. Must be currently CHECKED_IN. (CONFIRMED, CHECKED_OUT, CANCELLED are never checkout-due).
 * 2. If checkoutDue is explicitly true (authoritative backend flag), returns true (12 PM deadline passed).
 * 3. If checkOutDate is strictly earlier than today in property timezone (checkOutDate < todayDate),
 *    returns true (past-date overdue stay).
 * 4. If checkOutDate === todayDate and checkoutDue is false, returns false (scheduled departure today,
 *    not yet overdue).
 * 5. Future checkout dates (checkOutDate > todayDate) return false.
 */
export function isReservationCheckoutDue(
  reservation: Reservation,
  todayDate?: string
): boolean {
  if (reservation.status !== "CHECKED_IN") {
    return false;
  }
  if (reservation.checkoutDue === true) {
    return true;
  }
  if (todayDate && reservation.checkOutDate && reservation.checkOutDate < todayDate) {
    return true;
  }
  return false;
}

/**
 * Checks whether an in-house reservation is a pending departure:
 * either scheduled to depart today OR overdue from a prior date / flagged checkoutDue.
 * Excludes CHECKED_OUT reservations unconditionally.
 */
export function isPendingDeparture(
  reservation: Reservation,
  todayDate: string
): boolean {
  if (reservation.status !== "CHECKED_IN") {
    return false;
  }
  return (
    reservation.checkOutDate === todayDate ||
    isReservationCheckoutDue(reservation, todayDate)
  );
}

/**
 * Derives operational reservation metrics from property reservations.
 */
export function computeReservationMetrics(
  reservations: Reservation[],
  todayDate: string,
  filterPropertyId?: string
): ReservationMetrics {
  const targetReservations = filterPropertyId
    ? reservations.filter((r) => r.propertyId === filterPropertyId)
    : reservations;

  let arrivalsExpected = 0;
  let arrivalsCheckedIn = 0;
  let checkedInTotal = 0;
  let checkoutsDueTotal = 0;
  let departuresTodayTotal = 0;

  for (const r of targetReservations) {
    if (r.status === "CHECKED_IN") {
      checkedInTotal++;
    }

    if (isExpectedArrivalToday(r, todayDate)) {
      arrivalsExpected++;
    } else if (isAlreadyCheckedInArrivalToday(r, todayDate)) {
      arrivalsCheckedIn++;
    }

    if (isReservationCheckoutDue(r, todayDate)) {
      checkoutsDueTotal++;
    }

    if (isScheduledDepartureToday(r, todayDate)) {
      departuresTodayTotal++;
    }
  }

  return {
    totalReservations: targetReservations.length,
    arrivalsTodayTotal: arrivalsExpected + arrivalsCheckedIn,
    arrivalsExpected,
    arrivalsCheckedIn,
    checkedInTotal,
    checkoutsDueTotal,
    departuresTodayTotal,
  };
}

/**
 * Returns expected arrivals today (CONFIRMED status, checkInDate === today).
 */
export function filterExpectedArrivals(
  reservations: Reservation[],
  todayDate: string
): Reservation[] {
  return reservations.filter((r) => isExpectedArrivalToday(r, todayDate));
}

/**
 * Returns reservations that are CHECKED_IN and due/overdue for checkout
 * (authoritative checkoutDue === true or checkOutDate < today).
 */
export function filterCheckoutsDue(
  reservations: Reservation[],
  todayDate: string
): Reservation[] {
  return reservations.filter((r) => isReservationCheckoutDue(r, todayDate));
}

/**
 * Returns reservations that are CHECKED_IN and scheduled to depart today.
 */
export function filterScheduledDeparturesToday(
  reservations: Reservation[],
  todayDate: string
): Reservation[] {
  return reservations.filter((r) => isScheduledDepartureToday(r, todayDate));
}

/**
 * Returns all active in-house pending departures:
 * scheduled for today OR overdue from earlier dates.
 * Excludes CHECKED_OUT reservations unconditionally.
 */
export function filterPendingDeparturesQueue(
  reservations: Reservation[],
  todayDate: string
): Reservation[] {
  return reservations.filter((r) => isPendingDeparture(r, todayDate));
}

/**
 * Returns currently in-house reservations (status === 'CHECKED_IN').
 */
export function filterInHouseReservations(
  reservations: Reservation[]
): Reservation[] {
  return reservations.filter((r) => r.status === "CHECKED_IN");
}

/**
 * Returns rooms requiring housekeeping attention (DIRTY or CLEANING_IN_PROGRESS).
 */
export function filterHousekeepingAttentionRooms(
  rooms: PhysicalRoom[]
): PhysicalRoom[] {
  return rooms.filter(
    (r) =>
      r.housekeepingStatus === "DIRTY" ||
      r.housekeepingStatus === "CLEANING_IN_PROGRESS"
  );
}

/**
 * Unwraps list data whether returned bare [ ... ] or wrapped in { data: [ ... ] }, { content: [ ... ] }, etc.
 *
 * Safety & Completeness:
 * Inspects for Spring Data Page metadata (totalPages > 1, totalElements > items.length, last === false).
 * If incomplete pagination is detected, throws an explicit error rather than silently treating
 * a partial page as the entire property inventory.
 */
export function unwrapResponseList<T>(data: unknown): T[] {
  if (Array.isArray(data)) {
    return data as T[];
  }

  if (data && typeof data === "object") {
    const obj = data as Record<string, unknown>;

    // Inspect Spring Data pagination indicators
    const totalPages = typeof obj.totalPages === "number" ? obj.totalPages : null;
    const totalElements = typeof obj.totalElements === "number" ? obj.totalElements : null;
    const last = typeof obj.last === "boolean" ? obj.last : null;

    for (const key of ["content", "data", "items", "value"]) {
      if (Array.isArray(obj[key])) {
        const items = obj[key] as T[];

        // Check if this payload is an incomplete page of a paginated dataset
        const isIncompletePage =
          (totalPages !== null && totalPages > 1) ||
          (last !== null && last === false) ||
          (totalElements !== null && totalElements > items.length);

        if (isIncompletePage) {
          throw new Error(
            `Incomplete paginated response received (${items.length} of ${totalElements ?? "many"} items, page ${Number(obj.number ?? 0) + 1} of ${totalPages ?? "?"}). The operational dashboard requires the complete property dataset.`
          );
        }

        return items;
      }
    }
  }

  throw new Error("The server returned an unexpected list response.");
}
