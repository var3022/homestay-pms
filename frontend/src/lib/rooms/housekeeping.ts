export type HousekeepingStatus = "CLEAN" | "DIRTY" | "CLEANING_IN_PROGRESS";
export type RoomStatus = "AVAILABLE" | "OCCUPIED" | "OUT_OF_SERVICE";

export interface PhysicalRoom {
  id: string;
  propertyId: string;
  roomTypeId: string;
  roomNumber: string;
  name?: string;
  floor?: string;
  baseOccupancy: number;
  maxOccupancy: number;
  maxExtraBeds?: number;
  bathroomCount?: number;
  status: RoomStatus;
  housekeepingStatus: HousekeepingStatus;
  active: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface RoomType {
  id: string;
  name: string;
  code: string;
}

/**
 * Checks whether a room is ready for immediate guest check-in.
 * In accordance with PMS business rules:
 * A room is ONLY ready when:
 * 1. Physical RoomStatus is AVAILABLE
 * 2. HousekeepingStatus is CLEAN
 * 3. Room is active
 *
 * An AVAILABLE but DIRTY room is NOT ready.
 * An OCCUPIED room is NOT ready for new check-in.
 * An OUT_OF_SERVICE or inactive room is NOT ready.
 *
 * Note: Operational physical/housekeeping readiness signifies that the room
 * has been cleaned, inspected, and is available for immediate occupancy.
 * It does not alone guarantee inventory availability for specific future or
 * overlapping stay dates, which is governed by reservation date assignments.
 */
export function isRoomReadyForCheckIn(room: PhysicalRoom): boolean {
  return (
    room.status === "AVAILABLE" &&
    room.housekeepingStatus === "CLEAN" &&
    Boolean(room.active)
  );
}

/**
 * Derives allowable next housekeeping status transitions based on Spring Boot RoomServiceImpl:
 * - DIRTY -> CLEANING_IN_PROGRESS or CLEAN
 * - CLEANING_IN_PROGRESS -> CLEAN
 * - CLEAN -> DIRTY
 */
export function getAllowedTransitions(
  currentStatus: HousekeepingStatus
): HousekeepingStatus[] {
  switch (currentStatus) {
    case "DIRTY":
      return ["CLEANING_IN_PROGRESS", "CLEAN"];
    case "CLEANING_IN_PROGRESS":
      return ["CLEAN"];
    case "CLEAN":
      return ["DIRTY"];
    default:
      return [];
  }
}

/**
 * Classifies whether an error message indicates an uncertain outcome
 * (e.g. network timeout or 502 bad gateway during mutating PATCH).
 */
export function isUncertainError(errorMessage?: string | null): boolean {
  if (!errorMessage) return false;
  const lower = errorMessage.toLowerCase();
  return (
    lower.includes("cannot connect") ||
    lower.includes("network") ||
    lower.includes("failed (502)") ||
    lower.includes("timeout")
  );
}
