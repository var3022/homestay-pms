export interface ReservationStayScope {
  id: string;
  propertyId?: string | null;
  checkInDate: string;
  checkOutDate: string;
  status?: string | null;
}

export interface ReservationRoomScope {
  id: string;
  roomTypeId: string;
  quantity?: number | null;
  cancelledQuantity?: number | null;
  activeQuantity?: number | null;
  status?: string | null;
}

export interface RoomAssignmentScope {
  id: string;
  reservationRoomId: string;
  roomId: string;
  assignedFrom: string;
  assignedUntil: string;
  status: string;
}

export interface PhysicalRoomScope {
  id: string;
  roomNumber?: string | null;
  roomTypeId?: string | null;
  propertyId?: string | null;
  active?: boolean | null;
  status?: string | null;
  housekeepingStatus?: string | null;
}

export interface RoomTypeScope {
  id: string;
  name: string;
}

export interface PerRoomTypeCompleteness {
  reservationRoomId: string;
  roomTypeId: string;
  roomTypeName: string;
  activeQuantity: number;
  assignedCount: number;
  unassignedCount: number;
  isFullyAssigned: boolean;
  hasSplitOrPartialDates: boolean;
  hasDuplicateRoom: boolean;
}

export interface AssignmentCompletenessResult {
  status: "COMPLETE" | "INCOMPLETE" | "LOADING" | "ERROR" | "NO_ACTIVE_ROOMS";
  isComplete: boolean;
  canCheckIn: boolean;
  totalActiveUnits: number;
  totalValidAssignedUnits: number;
  unassignedActiveUnits: number;
  missingCategoryDetails: string[];
  notice: string | null;
  perRoomTypeDetails: PerRoomTypeCompleteness[];
}

export function getActiveReservedQuantity(room?: {
  activeQuantity?: number | null;
  quantity?: number | null;
  cancelledQuantity?: number | null;
} | null): number {
  if (!room) return 0;
  if (typeof room.activeQuantity === "number" && Number.isFinite(room.activeQuantity)) {
    return Math.max(0, room.activeQuantity);
  }
  const qty = typeof room.quantity === "number" && Number.isFinite(room.quantity) ? room.quantity : 0;
  const cancelled = typeof room.cancelledQuantity === "number" && Number.isFinite(room.cancelledQuantity) ? room.cancelledQuantity : 0;
  return Math.max(0, qty - cancelled);
}

export function evaluateReservationAssignmentCompleteness(
  reservation: ReservationStayScope | null | undefined,
  reservationRooms: ReservationRoomScope[] | null | undefined,
  roomAssignments: RoomAssignmentScope[] | null | undefined,
  physicalRooms?: PhysicalRoomScope[] | null | undefined,
  roomTypes?: RoomTypeScope[] | null | undefined,
  loading = false,
  error = ""
): AssignmentCompletenessResult {
  if (loading) {
    return {
      status: "LOADING",
      isComplete: false,
      canCheckIn: false,
      totalActiveUnits: 0,
      totalValidAssignedUnits: 0,
      unassignedActiveUnits: 0,
      missingCategoryDetails: [],
      notice: "Loading physical room assignments…",
      perRoomTypeDetails: [],
    };
  }

  if (error) {
    return {
      status: "ERROR",
      isComplete: false,
      canCheckIn: false,
      totalActiveUnits: 0,
      totalValidAssignedUnits: 0,
      unassignedActiveUnits: 0,
      missingCategoryDetails: [],
      notice: `Unable to verify room assignments: ${error}`,
      perRoomTypeDetails: [],
    };
  }

  if (!reservation || !reservation.checkInDate || !reservation.checkOutDate) {
    return {
      status: "INCOMPLETE",
      isComplete: false,
      canCheckIn: false,
      totalActiveUnits: 0,
      totalValidAssignedUnits: 0,
      unassignedActiveUnits: 0,
      missingCategoryDetails: ["Reservation dates are missing or incomplete"],
      notice: "Reservation check-in/checkout dates are missing or incomplete.",
      perRoomTypeDetails: [],
    };
  }

  const rooms = reservationRooms ?? [];
  const assignments = roomAssignments ?? [];
  const pRooms = physicalRooms ?? [];
  const rTypes = roomTypes ?? [];

  const totalActiveUnits = rooms.reduce((sum, r) => sum + getActiveReservedQuantity(r), 0);

  if (totalActiveUnits <= 0) {
    return {
      status: "NO_ACTIVE_ROOMS",
      isComplete: false,
      canCheckIn: false,
      totalActiveUnits: 0,
      totalValidAssignedUnits: 0,
      unassignedActiveUnits: 0,
      missingCategoryDetails: ["No active rooms"],
      notice: "Cannot check in: Reservation has no active rooms.",
      perRoomTypeDetails: [],
    };
  }

  const perRoomTypeDetails: PerRoomTypeCompleteness[] = [];
  const missingCategoryDetails: string[] = [];
  const globalAssignedPhysicalIds = new Set<string>();
  let hasCrossRoomDuplicate = false;
  let hasGlobalSplitOrPartial = false;
  let totalValidAssignedUnits = 0;

  for (const rr of rooms) {
    const activeQty = getActiveReservedQuantity(rr);
    const rtName = rTypes.find((t) => t.id === rr.roomTypeId)?.name || "Reserved Room";

    if (rr.status === "CANCELLED" || activeQty === 0) {
      perRoomTypeDetails.push({
        reservationRoomId: rr.id,
        roomTypeId: rr.roomTypeId,
        roomTypeName: rtName,
        activeQuantity: 0,
        assignedCount: 0,
        unassignedCount: 0,
        isFullyAssigned: true,
        hasSplitOrPartialDates: false,
        hasDuplicateRoom: false,
      });
      continue;
    }

    // 1. Filter assignments strictly belonging to this reservation room unit with status ASSIGNED
    const unitAssignments = assignments.filter(
      (a) => a.reservationRoomId === rr.id && a.status === "ASSIGNED"
    );

    // 2. Full stay date verification
    const fullStayAssignments = unitAssignments.filter(
      (a) => a.assignedFrom === reservation.checkInDate && a.assignedUntil === reservation.checkOutDate
    );
    const hasSplitOrPartialDates = unitAssignments.length > fullStayAssignments.length;
    if (hasSplitOrPartialDates) {
      hasGlobalSplitOrPartial = true;
    }

    // 3. Physical room validation (property scope, room type, active status, out-of-service)
    const validPhysicalAssignments = fullStayAssignments.filter((a) => {
      const pRoom = pRooms.find((p) => p.id === a.roomId);
      if (!pRoom) return true; // Authoritative details not present in cache; rely on backend
      if (reservation.propertyId && pRoom.propertyId && pRoom.propertyId !== reservation.propertyId) {
        return false;
      }
      if (rr.roomTypeId && pRoom.roomTypeId && pRoom.roomTypeId !== rr.roomTypeId) {
        return false;
      }
      if (pRoom.active === false) return false;
      if (pRoom.status === "OUT_OF_SERVICE") return false;
      return true;
    });

    // 4. Distinct physical rooms within unit and across entire reservation
    const distinctUnitRoomIds = new Set<string>();
    const distinctAssignments: RoomAssignmentScope[] = [];
    let hasDuplicateInUnit = false;

    for (const a of validPhysicalAssignments) {
      if (distinctUnitRoomIds.has(a.roomId)) {
        hasDuplicateInUnit = true;
      } else {
        distinctUnitRoomIds.add(a.roomId);
        distinctAssignments.push(a);

        if (globalAssignedPhysicalIds.has(a.roomId)) {
          hasCrossRoomDuplicate = true;
        } else {
          globalAssignedPhysicalIds.add(a.roomId);
        }
      }
    }

    const assignedCount = distinctAssignments.length;
    totalValidAssignedUnits += assignedCount;
    const isFullyAssigned = assignedCount === activeQty;
    const unassignedCount = Math.max(0, activeQty - assignedCount);

    if (assignedCount < activeQty) {
      missingCategoryDetails.push(`${rtName} (${assignedCount}/${activeQty} assigned)`);
    } else if (assignedCount > activeQty) {
      missingCategoryDetails.push(`${rtName} (overassigned: ${assignedCount}/${activeQty})`);
    }

    perRoomTypeDetails.push({
      reservationRoomId: rr.id,
      roomTypeId: rr.roomTypeId,
      roomTypeName: rtName,
      activeQuantity: activeQty,
      assignedCount,
      unassignedCount,
      isFullyAssigned,
      hasSplitOrPartialDates,
      hasDuplicateRoom: hasDuplicateInUnit,
    });
  }

  const unassignedActiveUnits = Math.max(0, totalActiveUnits - totalValidAssignedUnits);
  const isComplete =
    !hasGlobalSplitOrPartial &&
    !hasCrossRoomDuplicate &&
    missingCategoryDetails.length === 0 &&
    totalValidAssignedUnits === totalActiveUnits;

  let notice: string | null = null;
  if (hasGlobalSplitOrPartial) {
    notice = `One or more assigned rooms do not cover the full stay period (${reservation.checkInDate} to ${reservation.checkOutDate}). Split-stay or partial-date assignments are not permitted.`;
  } else if (hasCrossRoomDuplicate) {
    notice = "Duplicate physical room assignment detected across reservation rooms. Each reserved room must be assigned to a unique physical room.";
  } else if (missingCategoryDetails.length > 0) {
    notice = `Incomplete physical room assignment for: ${missingCategoryDetails.join(", ")}. All active reserved units must have a full-stay physical room assignment before check-in.`;
  }

  return {
    status: isComplete ? "COMPLETE" : "INCOMPLETE",
    isComplete,
    canCheckIn: isComplete,
    totalActiveUnits,
    totalValidAssignedUnits,
    unassignedActiveUnits,
    missingCategoryDetails,
    notice,
    perRoomTypeDetails,
  };
}
