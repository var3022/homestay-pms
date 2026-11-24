
"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { StaffHeader } from "../StaffHeader";
import {
  evaluateReservationAssignmentCompleteness,
  getActiveReservedQuantity,
} from "@/lib/reservations/completeness";

type Reservation = {
  id: string;
  propertyId?: string;
  primaryGuestId: string;
  bookingSourceId: string;
  checkInDate: string;
  checkOutDate: string;
  adults: number;
  children: number;
  infants: number;
  externalBookingId: string | null;
  status: string;
  checkoutDue: boolean;
  checkoutDueAt?: string | null;
  actualCheckedOutAt?: string | null;
  checkedOutByStaffUserId?: string | null;
  specialRequests?: string | null;
  createdAt?: string;
  updatedAt?: string;
};

type Guest = {
  id: string;
  name: string;
  phone?: string | null;
  email?: string | null;
  active?: boolean;
};

type BookingSource = {
  id: string;
  code: string;
  name: string;
  active: boolean;
};

type RoomType = {
  id: string;
  name: string;
  code: string;
  baseOccupancy: number;
  maxOccupancy: number;
  active: boolean;
};

type RoomRequest = {
  roomTypeId: string;
  quantity: number;
  unitPrice: string;
};


type ReservationRoom = {
  id: string;
  reservationId: string;
  roomTypeId: string;
  quantity: number;
  cancelledQuantity: number;
  activeQuantity: number;
  unitPrice: number | string;
  subtotal: number | string | null;
  status: string;
};

type RoomAssignment = {
  id: string;
  reservationRoomId: string;
  roomId: string;
  assignedFrom: string;
  assignedUntil: string;
  status: string;
};

type PhysicalRoom = {
  id: string;
  propertyId?: string;
  roomNumber: string;
  name?: string | null;
  floor?: string | null;
  roomTypeId: string;
  status?: string;
  housekeepingStatus?: string;
  active?: boolean;
};

const filters = [
  "ALL",
  "PENDING",
  "CONFIRMED",
  "CHECKED_IN",
  "CHECKED_OUT",
  "CANCELLED",
  "NO_SHOW",
];

const inputClass =
  "mt-1.5 w-full rounded-xl border border-[#dce2d9] bg-white px-3.5 py-3 text-sm text-[#24382e] outline-none focus:border-[#557762] focus:ring-2 focus:ring-[#557762]/10";

const labelClass = "block text-sm font-medium text-[#435448]";

function formatDate(value: string) {
  if (!value) return "—";
  const [year, month, day] = value.split("-");
  return `${day}/${month}/${year}`;
}

function formatDateTimeIST(value?: string | null) {
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


function isUncertainError(message: string): boolean {
  if (!message) return false;
  const lower = message.toLowerCase();
  return (
    lower.includes("cannot connect") ||
    lower.includes("network") ||
    lower.includes("failed (502)") ||
    lower.includes("504") ||
    lower.includes("gateway") ||
    lower.includes("timeout")
  );
}

function todayInIndia() {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Kolkata",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
}

function statusStyle(status: string) {
  switch (status) {
    case "CONFIRMED":
      return "bg-blue-50 text-blue-700";
    case "CHECKED_IN":
      return "bg-green-50 text-green-700";
    case "CHECKED_OUT":
      return "bg-gray-100 text-gray-700";
    case "CANCELLED":
    case "NO_SHOW":
      return "bg-red-50 text-red-700";
    case "PENDING":
      return "bg-amber-50 text-amber-700";
    default:
      return "bg-gray-100 text-gray-700";
  }
}

function unwrapList<T>(data: unknown): T[] {
  if (Array.isArray(data)) return data as T[];

  if (data && typeof data === "object") {
    const obj = data as Record<string, unknown>;

    for (const key of ["value", "data", "content", "items"]) {
      if (Array.isArray(obj[key])) return obj[key] as T[];
    }
  }

  throw new Error("The server returned an unexpected list response.");
}

function unwrapObject<T>(data: unknown): T {
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    throw new Error("The server returned an unexpected response.");
  }

  const obj = data as Record<string, unknown>;

  for (const key of ["value", "data"]) {
    const nested = obj[key];
    if (nested && typeof nested === "object" && !Array.isArray(nested)) {
      return nested as T;
    }
  }

  return data as T;
}

async function readResponse(response: Response): Promise<unknown> {
  const text = await response.text();
  let data: unknown = null;

  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }

  if (!response.ok) {
    let message = `Request failed (${response.status}).`;

    if (typeof data === "string" && data.trim()) {
      message = data;
    } else if (data && typeof data === "object") {
      const obj = data as Record<string, unknown>;
      const nested =
        obj.error && typeof obj.error === "object"
          ? (obj.error as Record<string, unknown>)
          : null;

      const candidate =
        obj.message ?? obj.detail ?? nested?.message ?? obj.error;

      if (typeof candidate === "string" && candidate.trim()) {
        message = candidate;
      }
    }

    throw new Error(message);
  }

  return data;
}

export default function ReservationsPage() {
  const [reservations, setReservations] = useState<Reservation[]>([]);
  const [selectedReservation, setSelectedReservation] =
  useState<Reservation | null>(null);
  const [guests, setGuests] = useState<Guest[]>([]);
  const [bookingSources, setBookingSources] = useState<BookingSource[]>([]);
  const [roomTypes, setRoomTypes] = useState<RoomType[]>([]);

  const [filter, setFilter] = useState("ALL");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [resourceError, setResourceError] = useState("");

  const [showCreateForm, setShowCreateForm] = useState(false);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState("");
  const [successMessage, setSuccessMessage] = useState("");
  useEffect(() => {
    if (!successMessage) return;

    const timer = window.setTimeout(() => {
      setSuccessMessage("");
    }, 5000);

    return () => window.clearTimeout(timer);
  }, [successMessage]);

  const [checkingIn, setCheckingIn] = useState(false);
  const [checkingOut, setCheckingOut] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [showCheckInConfirm, setShowCheckInConfirm] = useState(false);
  const [showCheckOutConfirm, setShowCheckOutConfirm] = useState(false);
  const [showConfirmModal, setShowConfirmModal] = useState(false);
  const [showAssignModal, setShowAssignModal] = useState(false);
  const [assigningReservationRoom, setAssigningReservationRoom] = useState<ReservationRoom | null>(null);
  const [selectedPhysicalRoomId, setSelectedPhysicalRoomId] = useState("");
  const [assigningRoom, setAssigningRoom] = useState(false);
  const [assignError, setAssignError] = useState("");
  const [uncertainAssignmentAttempts, setUncertainAssignmentAttempts] = useState<Set<string>>(new Set());

  const [actionError, setActionError] = useState("");
  const [actionSuccess, setActionSuccess] = useState("");
  const [uncertainOutcome, setUncertainOutcome] = useState(false);
  const [uncertainReservationIds, setUncertainReservationIds] = useState<Set<string>>(new Set());

  const confirmTriggerRef = useRef<HTMLButtonElement | null>(null);
  const confirmDialogRef = useRef<HTMLDivElement | null>(null);
  const assignTriggerRef = useRef<HTMLButtonElement | null>(null);
  const assignDialogRef = useRef<HTMLDivElement | null>(null);
  const listRequestSeqRef = useRef(0);
  const roomDetailsSeqRef = useRef(0);

  const isCurrentReservationUncertain = Boolean(
    selectedReservation &&
      (uncertainReservationIds.has(selectedReservation.id) || uncertainOutcome)
  );

  useEffect(() => {
    if (!actionSuccess) return;

    const timer = window.setTimeout(() => {
      setActionSuccess("");
    }, 5000);

    return () => window.clearTimeout(timer);
  }, [actionSuccess]);

  useEffect(() => {
    if (showConfirmModal) {
      const timer = window.setTimeout(() => {
        if (confirmDialogRef.current) {
          const focusable = confirmDialogRef.current.querySelectorAll<HTMLElement>(
            'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
          );
          if (focusable.length > 0) {
            focusable[0].focus();
          }
        }
      }, 0);
      return () => window.clearTimeout(timer);
    } else {
      if (confirmTriggerRef.current) {
        confirmTriggerRef.current.focus();
      }
    }
  }, [showConfirmModal]);

  const handleConfirmDialogKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLDivElement>) => {
      if (e.key === "Escape" && !confirming) {
        e.stopPropagation();
        setShowConfirmModal(false);
        return;
      }
      if (e.key === "Tab" && confirmDialogRef.current) {
        const focusable = Array.from(
          confirmDialogRef.current.querySelectorAll<HTMLElement>(
            'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
          )
        );
        if (focusable.length === 0) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (e.shiftKey) {
          if (document.activeElement === first) {
            e.preventDefault();
            last.focus();
          }
        } else {
          if (document.activeElement === last) {
            e.preventDefault();
            first.focus();
          }
        }
      }
    },
    [confirming]
  );

  useEffect(() => {
    if (showAssignModal) {
      const timer = window.setTimeout(() => {
        if (assignDialogRef.current) {
          const focusable = assignDialogRef.current.querySelectorAll<HTMLElement>(
            'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
          );
          if (focusable.length > 0) {
            focusable[0].focus();
          }
        }
      }, 0);
      return () => window.clearTimeout(timer);
    } else {
      if (assignTriggerRef.current) {
        assignTriggerRef.current.focus();
      }
    }
  }, [showAssignModal]);

  const handleAssignDialogKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLDivElement>) => {
      if (e.key === "Escape" && !assigningRoom) {
        e.stopPropagation();
        setShowAssignModal(false);
        return;
      }
      if (e.key === "Tab" && assignDialogRef.current) {
        const focusable = Array.from(
          assignDialogRef.current.querySelectorAll<HTMLElement>(
            'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
          )
        );
        if (focusable.length === 0) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (e.shiftKey) {
          if (document.activeElement === first) {
            e.preventDefault();
            last.focus();
          }
        } else {
          if (document.activeElement === last) {
            e.preventDefault();
            first.focus();
          }
        }
      }
    },
    [assigningRoom]
  );
  const [guestMode, setGuestMode] = useState<"existing" | "new">("existing");
  const [primaryGuestId, setPrimaryGuestId] = useState("");
  const [newGuestName, setNewGuestName] = useState("");
  const [newGuestPhone, setNewGuestPhone] = useState("");
  const [newGuestEmail, setNewGuestEmail] = useState("");

  const [bookingSourceId, setBookingSourceId] = useState("");
  const [externalBookingId, setExternalBookingId] = useState("");
  const [checkInDate, setCheckInDate] = useState("");
  const [checkOutDate, setCheckOutDate] = useState("");
  const [adults, setAdults] = useState("1");
  const [children, setChildren] = useState("0");
  const [infants, setInfants] = useState("0");
  const [specialRequests, setSpecialRequests] = useState("");
  
  const [reservationRooms, setReservationRooms] = useState<ReservationRoom[]>([]);
  const [roomAssignments, setRoomAssignments] = useState<RoomAssignment[]>([]);
  const [physicalRooms, setPhysicalRooms] = useState<PhysicalRoom[]>([]);
  const [roomDetailsLoading, setRoomDetailsLoading] = useState(false);
  const [roomDetailsError, setRoomDetailsError] = useState("");

  const [roomRequests, setRoomRequests] = useState<RoomRequest[]>([
    { roomTypeId: "", quantity: 1, unitPrice: "" },
  ]);

  const loadReservations = useCallback(async () => {
    const seq = ++listRequestSeqRef.current;
    try {
      const response = await fetch("/api/reservations", {
        cache: "no-store",
      });
      const data = await readResponse(response);
      if (seq < listRequestSeqRef.current) return;
      setReservations(unwrapList<Reservation>(data));
    } catch (err) {
      if (seq < listRequestSeqRef.current) return;
      setError(
        err instanceof Error ? err.message : "Could not load reservations.",
      );
    } finally {
      if (seq >= listRequestSeqRef.current) {
        setLoading(false);
      }
    }
  }, []);

  const loadFormOptions = useCallback(async () => {

    try {
      const [guestResponse, sourceResponse, roomTypeResponse] =
        await Promise.all([
          fetch("/api/guests", { cache: "no-store" }),
          fetch("/api/booking-sources", { cache: "no-store" }),
          fetch("/api/room-types", { cache: "no-store" }),
        ]);

      const [guestData, sourceData, roomTypeData] = await Promise.all([
        readResponse(guestResponse),
        readResponse(sourceResponse),
        readResponse(roomTypeResponse),
      ]);

      const guestList = unwrapList<Guest>(guestData).filter(
        (guest) => guest.active !== false,
      );
      const sourceList = unwrapList<BookingSource>(sourceData).filter(
        (source) => source.active,
      );
      const roomTypeList = unwrapList<RoomType>(roomTypeData).filter(
        (roomType) => roomType.active,
      );

      setGuests(guestList);
      setBookingSources(sourceList);
      setRoomTypes(roomTypeList);

      setBookingSourceId((current) => {
        if (current && sourceList.some((source) => source.id === current)) {
          return current;
        }

        return (
          sourceList.find((source) => source.code === "DIRECT")?.id ??
          sourceList[0]?.id ??
          ""
        );
      });
    } catch (err) {
      setResourceError(
        err instanceof Error
          ? err.message
          : "Could not load guests, booking sources, and room types.",
      );
    }
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadReservations();
      void loadFormOptions();
    }, 0);

    return () => window.clearTimeout(timer);
  }, [loadReservations, loadFormOptions]);
  const loadRoomDetails = useCallback(async (reservationId: string) => {
    const seq = ++roomDetailsSeqRef.current;
    setRoomDetailsLoading(true);
    setRoomDetailsError("");
    setReservationRooms([]);
    setRoomAssignments([]);

    try {
      const reservationRoomsResponse = await fetch(
        `/api/reservation-rooms?reservationId=${encodeURIComponent(reservationId)}`,
        { cache: "no-store" },
      );

      const reservationRoomsData = await readResponse(reservationRoomsResponse);
      const roomList = unwrapList<ReservationRoom>(reservationRoomsData);

      const [roomsResponse, ...assignmentResponses] = await Promise.all([
        fetch("/api/rooms", { cache: "no-store" }),
        ...roomList.map((room) =>
          fetch(
            `/api/room-assignments?reservationRoomId=${encodeURIComponent(room.id)}`,
            { cache: "no-store" },
          ),
        ),
      ]);

      const roomsData = await readResponse(roomsResponse);
      const physicalRoomList = unwrapList<PhysicalRoom>(roomsData);

      const assignmentLists = await Promise.all(
        assignmentResponses.map(async (response) =>
          unwrapList<RoomAssignment>(await readResponse(response)),
        ),
      );

      if (seq < roomDetailsSeqRef.current) return;

      setReservationRooms(roomList);
      setPhysicalRooms(physicalRoomList);
      setRoomAssignments(assignmentLists.flat());
    } catch (err) {
      if (seq < roomDetailsSeqRef.current) return;
      const message =
        err instanceof Error ? err.message : "Unable to load room details.";
      setRoomDetailsError(message);
      throw err;
    } finally {
      if (seq >= roomDetailsSeqRef.current) {
        setRoomDetailsLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    if (!selectedReservation) {
      return;
    }
    const timer = window.setTimeout(() => {
      void loadRoomDetails(selectedReservation.id).catch(() => {});
    }, 0);

    return () => window.clearTimeout(timer);
  }, [selectedReservation, loadRoomDetails]);

  const filteredReservations =
    filter === "ALL"
      ? reservations
      : reservations.filter((reservation) => reservation.status === filter);

  function updateRoomRequest(
    index: number,
    field: keyof RoomRequest,
    value: string | number,
  ) {
    setRoomRequests((current) =>
      current.map((room, i) =>
        i === index ? { ...room, [field]: value } : room,
      ),
    );
  }

  function resetReservationForm() {
    setGuestMode("existing");
    setPrimaryGuestId("");
    setNewGuestName("");
    setNewGuestPhone("");
    setNewGuestEmail("");
    setExternalBookingId("");
    setCheckInDate("");
    setCheckOutDate("");
    setAdults("1");
    setChildren("0");
    setInfants("0");
    setSpecialRequests("");
    setRoomRequests([{ roomTypeId: "", quantity: 1, unitPrice: "" }]);
    setFormError("");
  }

  async function handleCreateReservation(
    event: React.FormEvent<HTMLFormElement>,
  ) {
    event.preventDefault();
    setFormError("");
    setSuccessMessage("");

    if (!bookingSourceId) {
      setFormError("Please select a booking source.");
      return;
    }

    if (!checkInDate || !checkOutDate || checkOutDate <= checkInDate) {
      setFormError("Check-out date must be after check-in date.");
      return;
    }

    if (guestMode === "existing" && !primaryGuestId) {
      setFormError("Please select a guest.");
      return;
    }

    if (guestMode === "new" && !newGuestName.trim()) {
      setFormError("Please enter the guest's name.");
      return;
    }

    if (
      roomRequests.some(
        (room) =>
          !room.roomTypeId ||
          !Number.isInteger(Number(room.quantity)) ||
          Number(room.quantity) < 1 ||
          room.unitPrice.trim() === "" ||
          !Number.isFinite(Number(room.unitPrice)) ||
          Number(room.unitPrice) < 0,
      )
    ) {
      setFormError(
        "Select a room type, quantity, and a valid non-negative price for every room entry.",
      );
      return;
    }

    const roomTypeIds = roomRequests.map((room) => room.roomTypeId);
    if (new Set(roomTypeIds).size !== roomTypeIds.length) {
      setFormError(
        "Each room type should appear once. Combine the quantity for duplicate room types.",
      );
      return;
    }

    if (Number(adults) < 1 || Number(children) < 0 || Number(infants) < 0) {
      setFormError("Enter valid guest counts.");
      return;
    }

    setSaving(true);

    try {
      let guestId = primaryGuestId;

      if (guestMode === "new") {
        const guestPayload: Record<string, string> = {
          name: newGuestName.trim(),
        };

        if (newGuestPhone.trim()) guestPayload.phone = newGuestPhone.trim();
        if (newGuestEmail.trim()) guestPayload.email = newGuestEmail.trim();

        const guestResponse = await fetch("/api/guests", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(guestPayload),
        });

        const createdGuest = unwrapObject<Guest>(
          await readResponse(guestResponse),
        );

        if (!createdGuest.id) {
          throw new Error(
            "The guest was created, but the response did not include a guest ID. Refresh guests before retrying.",
          );
        }

        guestId = createdGuest.id;
        setGuests((current) => [
          ...current.filter((guest) => guest.id !== createdGuest.id),
          createdGuest,
        ]);
        setPrimaryGuestId(createdGuest.id);
        setGuestMode("existing");
      }

      const reservationPayload = {
        primaryGuestId: guestId,
        checkInDate,
        checkOutDate,
        adults: Number(adults),
        children: Number(children),
        infants: Number(infants),
        bookingSourceId,
        externalBookingId: externalBookingId.trim() || null,
        specialRequests: specialRequests.trim() || null,
        reservationRooms: roomRequests.map((room) => ({
          roomTypeId: room.roomTypeId,
          quantity: Number(room.quantity),
          unitPrice: Number(room.unitPrice),
        })),
      };

      const reservationResponse = await fetch("/api/reservations", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(reservationPayload),
      });

      await readResponse(reservationResponse);

      setSuccessMessage(
        "Reservation created successfully. Its initial status is PENDING.",
      );
      resetReservationForm();
      setShowCreateForm(false);

      await Promise.all([loadReservations(), loadFormOptions()]);
    } catch (err) {
      setFormError(
        err instanceof Error
          ? err.message
          : "Could not create the reservation.",
      );
    } finally {
      setSaving(false);
    }
  }

  const handleCloseModal = useCallback(() => {
    if (
      uncertainOutcome ||
      (selectedReservation && uncertainReservationIds.has(selectedReservation.id))
    ) {
      void loadReservations();
    }
    setSelectedReservation(null);
    setReservationRooms([]);
    setRoomAssignments([]);
    roomDetailsSeqRef.current++;
    setShowConfirmModal(false);
    setShowCheckInConfirm(false);
    setShowCheckOutConfirm(false);
    setShowAssignModal(false);
    setAssigningReservationRoom(null);
    setSelectedPhysicalRoomId("");
    setAssignError("");
    setActionError("");
    setUncertainOutcome(false);
  }, [uncertainOutcome, selectedReservation, uncertainReservationIds, loadReservations]);

  async function handleConfirmSubmit() {
    if (!selectedReservation || confirming) return;
    const resId = selectedReservation.id;
    if (uncertainReservationIds.has(resId)) return;

    setConfirming(true);
    setActionError("");
    setUncertainOutcome(false);
    listRequestSeqRef.current++;

    try {
      const response = await fetch(
        `/api/reservations/${encodeURIComponent(resId)}/confirm`,
        {
          method: "PATCH",
          headers: {
            "Content-Type": "application/json",
            "X-PMS-Client": "staff-web",
          },
        }
      );

      const data = await readResponse(response);
      const updated = unwrapObject<Reservation>(data);

      setSelectedReservation(updated);
      setReservations((prev) =>
        prev.map((r) => (r.id === updated.id ? updated : r))
      );
      void loadReservations();
      setShowConfirmModal(false);
      setActionSuccess("Reservation confirmed successfully. The reservation is now CONFIRMED.");
    } catch (err) {
      const message =
        err instanceof Error ? err.message : "Failed to confirm reservation.";
      setActionError(message);
      if (isUncertainError(message)) {
        setUncertainOutcome(true);
        setUncertainReservationIds((prev) => new Set(prev).add(resId));
      }
    } finally {
      setConfirming(false);
    }
  }

  async function handleCheckInSubmit() {
    if (!selectedReservation || checkingIn) return;
    const resId = selectedReservation.id;
    if (uncertainReservationIds.has(resId)) return;

    setCheckingIn(true);
    setActionError("");
    setUncertainOutcome(false);
    listRequestSeqRef.current++;

    try {
      const response = await fetch(
        `/api/reservations/${encodeURIComponent(resId)}/check-in`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-PMS-Client": "staff-web",
          },
        }
      );

      const data = await readResponse(response);
      const updated = unwrapObject<Reservation>(data);

      setSelectedReservation(updated);
      setReservations((prev) =>
        prev.map((r) => (r.id === updated.id ? updated : r))
      );
      void loadReservations();
      setShowCheckInConfirm(false);
      setActionSuccess("Guest checked in successfully. Assigned physical room(s) marked OCCUPIED.");
    } catch (err) {
      const message =
        err instanceof Error ? err.message : "Failed to check in reservation.";
      setActionError(message);
      if (isUncertainError(message)) {
        setUncertainOutcome(true);
        setUncertainReservationIds((prev) => new Set(prev).add(resId));
      }
    } finally {
      setCheckingIn(false);
    }
  }

  async function handleCheckOutSubmit() {
    if (!selectedReservation || checkingOut) return;
    const resId = selectedReservation.id;
    if (uncertainReservationIds.has(resId)) return;

    setCheckingOut(true);
    setActionError("");
    setUncertainOutcome(false);
    listRequestSeqRef.current++;

    try {
      const response = await fetch(
        `/api/reservations/${encodeURIComponent(resId)}/check-out`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-PMS-Client": "staff-web",
          },
        }
      );

      const data = await readResponse(response);
      const updated = unwrapObject<Reservation>(data);

      setSelectedReservation(updated);
      setReservations((prev) =>
        prev.map((r) => (r.id === updated.id ? updated : r))
      );
      void loadReservations();
      setShowCheckOutConfirm(false);
      setActionSuccess(
        "Checkout confirmed successfully. Physical room assignments released and vacated room(s) marked DIRTY for housekeeping."
      );
    } catch (err) {
      const message =
        err instanceof Error ? err.message : "Failed to check out reservation.";
      setActionError(message);
      if (isUncertainError(message)) {
        setUncertainOutcome(true);
        setUncertainReservationIds((prev) => new Set(prev).add(resId));
      }
    } finally {
      setCheckingOut(false);
    }
  }

  async function handleAssignRoomSubmit() {
    if (
      !selectedReservation ||
      !assigningReservationRoom ||
      !selectedPhysicalRoomId ||
      assigningRoom
    ) {
      return;
    }

    if (!["PENDING", "CONFIRMED"].includes(selectedReservation.status)) {
      setAssignError(
        "Room assignment is only permitted for pre-check-in reservations (PENDING or CONFIRMED)."
      );
      return;
    }

    const resId = selectedReservation.id;
    const attemptKey = `${assigningReservationRoom.id}:${selectedPhysicalRoomId}`;

    if (
      uncertainReservationIds.has(resId) ||
      uncertainAssignmentAttempts.has(attemptKey)
    ) {
      return;
    }

    setAssigningRoom(true);
    setAssignError("");
    setUncertainOutcome(false);
    listRequestSeqRef.current++;

    try {
      const response = await fetch("/api/room-assignments", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-PMS-Client": "staff-web",
        },
        body: JSON.stringify({
          reservationRoomId: assigningReservationRoom.id,
          roomId: selectedPhysicalRoomId,
          assignedFrom: selectedReservation.checkInDate,
          assignedUntil: selectedReservation.checkOutDate,
        }),
      });

      const data = await readResponse(response);
      const createdAssignment = unwrapObject<RoomAssignment>(data);

      setRoomAssignments((prev) => [
        ...prev.filter((a) => a.id !== createdAssignment.id),
        createdAssignment,
      ]);

      await loadRoomDetails(resId);
      setShowAssignModal(false);

      const assignedPhysical = physicalRooms.find((r) => r.id === selectedPhysicalRoomId);
      const roomNum = assignedPhysical ? `Room ${assignedPhysical.roomNumber}` : "Room";
      setActionSuccess(`${roomNum} assigned successfully for the stay.`);
    } catch (err) {
      const message =
        err instanceof Error ? err.message : "Failed to assign room.";
      setAssignError(message);

      if (isUncertainError(message)) {
        setUncertainOutcome(true);
        setUncertainReservationIds((prev) => new Set(prev).add(resId));
        setUncertainAssignmentAttempts((prev) => new Set(prev).add(attemptKey));
      }
    } finally {
      setAssigningRoom(false);
    }
  }

  async function handleRefreshSelectedReservation() {
    if (!selectedReservation) return;
    const resId = selectedReservation.id;
    const seq = ++listRequestSeqRef.current;
    try {
      const response = await fetch("/api/reservations", { cache: "no-store" });
      const data = await readResponse(response);
      if (seq < listRequestSeqRef.current) return;
      const list = unwrapList<Reservation>(data);
      setReservations(list);
      const found = list.find((r) => r.id === resId);
      if (found) {
        setSelectedReservation(found);
        await loadRoomDetails(resId);
        setUncertainReservationIds((prev) => {
          const next = new Set(prev);
          next.delete(resId);
          return next;
        });
        setUncertainAssignmentAttempts(new Set());
        if (showConfirmModal && found.status !== "PENDING") {
          setShowConfirmModal(false);
        }
        if (showCheckInConfirm && found.status !== "CONFIRMED") {
          setShowCheckInConfirm(false);
        }
        if (showCheckOutConfirm && found.status !== "CHECKED_IN") {
          setShowCheckOutConfirm(false);
        }
        if (!["PENDING", "CONFIRMED"].includes(found.status)) {
          setShowAssignModal(false);
        }
      }
      setActionError("");
      setAssignError("");
      setUncertainOutcome(false);
    } catch (err) {
      if (seq < listRequestSeqRef.current) return;
      setActionError(
        err instanceof Error ? err.message : "Failed to refresh reservation."
      );
      setUncertainOutcome(true);
      setUncertainReservationIds((prev) => new Set(prev).add(resId));
    }
  }

  return (
    <main className="min-h-screen bg-[#f5f6f3] text-[#24382e]">
      <header className="border-b border-[#e1e6df] bg-white px-5 py-3 sm:px-8 sm:py-3">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-4">
          <div>
            <div className="flex items-center gap-2 text-xs font-medium text-[#748077]">
              <Link
                href="/dashboard"
                className="hover:text-[#294638]"
              >
                ← Back to dashboard
              </Link>
              <span>•</span>
              <Link
                href="/dashboard/rooms"
                className="hover:text-[#294638]"
              >
                Housekeeping &amp; Rooms
              </Link>
            </div>
            <h1 className="mt-1 font-serif text-xl sm:text-2xl">
              Reservations
            </h1>
            <p className="mt-1 text-sm text-[#748077]">
              View and manage your property bookings.
            </p>
          </div>

          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => {
                setLoading(true);
                setError("");
                void loadReservations();
              }}
              disabled={loading}
              className="rounded-full border border-[#dce2d9] bg-white px-5 py-2.5 text-sm font-medium hover:bg-[#f5f6f3] disabled:opacity-50"
            >
              {loading ? "Refreshing…" : "↻ Refresh"}
            </button>

            <button
              type="button"
              onClick={() => {
                setShowCreateForm((current) => !current);
                setFormError("");
                setSuccessMessage("");
              }}
              className="rounded-full bg-[#294638] px-5 py-2.5 text-sm font-medium text-white hover:bg-[#20382c]"
            >
              {showCreateForm ? "Close form" : "+ New reservation"}
            </button>

            <StaffHeader />
          </div>
        </div>
      </header>

      <div className="mx-auto max-w-7xl space-y-6 px-5 py-7 sm:px-8 sm:py-9">
        {successMessage && (
          <div
            role="status"
            aria-live="polite"
            className="fixed left-1/2 top-5 z-50 w-max max-w-[calc(100vw-2rem)] -translate-x-1/2 rounded-xl border border-green-200 bg-green-50 px-5 py-4 text-sm text-green-800 shadow-lg sm:whitespace-nowrap"
          >
            <div className="flex items-start gap-3">
              {/* <span className="text-lg" aria-hidden="true">✓</span> */}
              <p>{successMessage}</p>
            </div>
          </div>
        )}

        {showCreateForm && (
          <section className="rounded-2xl border border-[#e4e8e0] bg-white p-5 sm:p-7">
            <div className="mb-6">
              <h2 className="font-serif text-xl sm:text-2xl">
                Create reservation
              </h2>
              <p className="mt-1 text-sm leading-6 text-[#748077]">
                Enter the guest, stay dates, booking source, and requested room
                types. New reservations are saved as pending.
              </p>
            </div>

            {resourceError && (
              <div className="mb-5 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
                <p>{resourceError}</p>
                <button
                  type="button"
                  onClick={() => {
                    setResourceError("");
                    void loadFormOptions();
                  }}
                  className="mt-2 font-semibold underline"
                >
                  Retry loading form options
                </button>
              </div>
            )}

            <form onSubmit={handleCreateReservation} className="space-y-7">
              <section>
                <h3 className="mb-4 text-sm font-semibold uppercase tracking-wide text-[#657469]">
                  Guest details
                </h3>

                <div className="mb-4 flex flex-wrap gap-2">
                  <button
                    type="button"
                    onClick={() => setGuestMode("existing")}
                    className={`rounded-full px-4 py-2 text-sm ${guestMode === "existing"
                      ? "bg-[#294638] text-white"
                      : "bg-[#f0f3ed] text-[#435448]"
                      }`}
                  >
                    Existing guest
                  </button>
                  <button
                    type="button"
                    onClick={() => setGuestMode("new")}
                    className={`rounded-full px-4 py-2 text-sm ${guestMode === "new"
                      ? "bg-[#294638] text-white"
                      : "bg-[#f0f3ed] text-[#435448]"
                      }`}
                  >
                    Create new guest
                  </button>
                </div>

                {guestMode === "existing" ? (
                  <div className="max-w-xl">
                    <label className={labelClass}>
                      Primary guest
                      <select
                        className={inputClass}
                        value={primaryGuestId}
                        onChange={(event) =>
                          setPrimaryGuestId(event.target.value)
                        }
                        required
                      >
                        <option value="">Select a guest</option>
                        {guests.map((guest) => (
                          <option key={guest.id} value={guest.id}>
                            {guest.name}
                            {guest.phone ? ` — ${guest.phone}` : ""}
                          </option>
                        ))}
                      </select>
                    </label>
                    {guests.length === 0 && !resourceError && (
                      <p className="mt-2 text-xs text-[#748077]">
                        No guests found yet. Choose “Create new guest” to add
                        one.
                      </p>
                    )}
                  </div>
                ) : (
                  <div className="grid gap-4 sm:grid-cols-3">
                    <label className={labelClass}>
                      Guest name *
                      <input
                        className={inputClass}
                        value={newGuestName}
                        onChange={(event) =>
                          setNewGuestName(event.target.value)
                        }
                        maxLength={150}
                        required
                        placeholder="Full name"
                      />
                    </label>
                    <label className={labelClass}>
                      Phone
                      <input
                        className={inputClass}
                        value={newGuestPhone}
                        onChange={(event) =>
                          setNewGuestPhone(event.target.value)
                        }
                        maxLength={30}
                        placeholder="Phone number"
                      />
                    </label>
                    <label className={labelClass}>
                      Email
                      <input
                        type="email"
                        className={inputClass}
                        value={newGuestEmail}
                        onChange={(event) =>
                          setNewGuestEmail(event.target.value)
                        }
                        maxLength={255}
                        placeholder="Email address"
                      />
                    </label>
                  </div>
                )}
              </section>

              <section>
                <h3 className="mb-4 text-sm font-semibold uppercase tracking-wide text-[#657469]">
                  Stay details
                </h3>

                <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                  <label className={labelClass}>
                    Check-in date *
                    <input
                      type="date"
                      className={inputClass}
                      value={checkInDate}
                      min={todayInIndia()}
                      onChange={(event) => setCheckInDate(event.target.value)}
                      required
                    />
                  </label>

                  <label className={labelClass}>
                    Check-out date *
                    <input
                      type="date"
                      className={inputClass}
                      value={checkOutDate}
                      min={checkInDate || todayInIndia()}
                      onChange={(event) => setCheckOutDate(event.target.value)}
                      required
                    />
                  </label>

                  <label className={labelClass}>
                    Adults *
                    <input
                      type="number"
                      className={inputClass}
                      min={1}
                      value={adults}
                      onChange={(event) => setAdults(event.target.value)}
                      required
                    />
                  </label>

                  <label className={labelClass}>
                    Children
                    <input
                      type="number"
                      className={inputClass}
                      min={0}
                      value={children}
                      onChange={(event) => setChildren(event.target.value)}
                    />
                  </label>

                  <label className={labelClass}>
                    Infants
                    <input
                      type="number"
                      className={inputClass}
                      min={0}
                      value={infants}
                      onChange={(event) => setInfants(event.target.value)}
                    />
                  </label>

                  <label className={labelClass}>
                    Booking source *
                    <select
                      className={inputClass}
                      value={bookingSourceId}
                      onChange={(event) =>
                        setBookingSourceId(event.target.value)
                      }
                      required
                    >
                      <option value="">Select source</option>
                      {bookingSources.map((source) => (
                        <option key={source.id} value={source.id}>
                          {source.name}
                        </option>
                      ))}
                    </select>
                  </label>

                  <label className={labelClass}>
                    External booking ID
                    <input
                      className={inputClass}
                      value={externalBookingId}
                      onChange={(event) =>
                        setExternalBookingId(event.target.value)
                      }
                      maxLength={255}
                      placeholder="OTA confirmation number"
                    />
                  </label>
                </div>
              </section>

              <section>
                <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                  <div>
                    <h3 className="text-sm font-semibold uppercase tracking-wide text-[#657469]">
                      Room types
                    </h3>
                    <p className="mt-1 text-xs text-[#748077]">
                      Enter the price per room per night. Availability is
                      checked by the backend when you save.
                    </p>
                  </div>

                  <button
                    type="button"
                    disabled={
                      roomTypes.length === 0 ||
                      roomRequests.length >= roomTypes.length
                    }
                    onClick={() =>
                      setRoomRequests((current) => [
                        ...current,
                        { roomTypeId: "", quantity: 1, unitPrice: "" },
                      ])
                    }
                    className="rounded-full border border-[#dce2d9] px-4 py-2 text-sm font-medium hover:bg-[#f5f6f3] disabled:opacity-40"
                  >
                    + Add room type
                  </button>
                </div>

                {roomTypes.length === 0 ? (
                  <p className="rounded-xl bg-[#f8f9f6] p-4 text-sm text-[#748077]">
                    No active room types were returned by the backend.
                  </p>
                ) : (
                  <div className="space-y-3">
                    {roomRequests.map((room, index) => {
                      const selectedElsewhere = roomRequests
                        .filter((_, i) => i !== index)
                        .map((item) => item.roomTypeId);

                      return (
                        <div
                          key={index}
                          className="grid gap-3 rounded-xl border border-[#e4e8e0] p-4 sm:grid-cols-[minmax(0,2fr)_minmax(90px,0.7fr)_minmax(120px,1fr)_auto] sm:items-end"
                        >
                          <label className={labelClass}>
                            Room type *
                            <select
                              className={inputClass}
                              value={room.roomTypeId}
                              onChange={(event) =>
                                updateRoomRequest(
                                  index,
                                  "roomTypeId",
                                  event.target.value,
                                )
                              }
                              required
                            >
                              <option value="">Select room type</option>
                              {roomTypes.map((roomType) => (
                                <option
                                  key={roomType.id}
                                  value={roomType.id}
                                  disabled={
                                    selectedElsewhere.includes(roomType.id)
                                  }
                                >
                                  {roomType.name} · {roomType.baseOccupancy}–
                                  {roomType.maxOccupancy} guests
                                </option>
                              ))}
                            </select>
                          </label>

                          <label className={labelClass}>
                            Quantity *
                            <input
                              type="number"
                              className={inputClass}
                              min={1}
                              step={1}
                              value={room.quantity}
                              onChange={(event) =>
                                updateRoomRequest(
                                  index,
                                  "quantity",
                                  Number(event.target.value),
                                )
                              }
                              required
                            />
                          </label>

                          <label className={labelClass}>
                            Price / room / night (₹) *
                            <input
                              type="number"
                              className={inputClass}
                              min={0}
                              step="0.01"
                              value={room.unitPrice}
                              onChange={(event) =>
                                updateRoomRequest(
                                  index,
                                  "unitPrice",
                                  event.target.value,
                                )
                              }
                              required
                              placeholder="e.g. 2500"
                            />
                          </label>

                          <button
                            type="button"
                            disabled={roomRequests.length === 1}
                            onClick={() =>
                              setRoomRequests((current) =>
                                current.filter((_, i) => i !== index),
                              )
                            }
                            className="rounded-lg px-3 py-3 text-sm text-red-700 hover:bg-red-50 disabled:opacity-30"
                          >
                            Remove
                          </button>
                        </div>
                      );
                    })}
                  </div>
                )}
              </section>

              <section>
                <label className={labelClass}>
                  Special requests
                  <textarea
                    className={inputClass}
                    value={specialRequests}
                    onChange={(event) =>
                      setSpecialRequests(event.target.value)
                    }
                    maxLength={5000}
                    rows={3}
                    placeholder="Arrival time, preferences, or other notes"
                  />
                </label>
              </section>

              {formError && (
                <div
                  role="alert"
                  className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800"
                >
                  {formError}
                </div>
              )}

              <div className="flex flex-wrap justify-end gap-3 border-t border-[#e8ebe5] pt-5">
                <button
                  type="button"
                  onClick={() => {
                    resetReservationForm();
                    setShowCreateForm(false);
                  }}
                  disabled={saving}
                  className="rounded-full border border-[#dce2d9] px-5 py-2.5 text-sm font-medium hover:bg-[#f5f6f3] disabled:opacity-50"
                >
                  Cancel
                </button>

                <button
                  type="submit"
                  disabled={saving || roomTypes.length === 0}
                  className="rounded-full bg-[#294638] px-6 py-2.5 text-sm font-medium text-white hover:bg-[#20382c] disabled:opacity-50"
                >
                  {saving ? "Saving reservation…" : "Create reservation"}
                </button>
              </div>
            </form>
          </section>
        )}

        <section className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <article className="rounded-2xl border border-[#e4e8e0] bg-white p-5">
            <p className="text-sm text-[#748077]">All reservations</p>
            <p className="mt-3 font-serif text-3xl">
              {loading ? "…" : reservations.length}
            </p>
          </article>

          <article className="rounded-2xl border border-[#e4e8e0] bg-white p-5">
            <p className="text-sm text-[#748077]">Confirmed</p>
            <p className="mt-3 font-serif text-3xl">
              {loading
                ? "…"
                : reservations.filter((item) => item.status === "CONFIRMED")
                  .length}
            </p>
          </article>

          <article className="rounded-2xl border border-[#e4e8e0] bg-white p-5">
            <p className="text-sm text-[#748077]">Currently checked in</p>
            <p className="mt-3 font-serif text-3xl">
              {loading
                ? "…"
                : reservations.filter((item) => item.status === "CHECKED_IN")
                  .length}
            </p>
          </article>
        </section>

        <section className="overflow-hidden rounded-2xl border border-[#e4e8e0] bg-white">
          <div className="border-b border-[#e8ebe5] p-5 sm:p-6">
            <h2 className="font-serif text-xl">Booking records</h2>
            <div className="mt-4 flex gap-2 overflow-x-auto pb-1">
              {filters.map((item) => (
                <button
                  key={item}
                  type="button"
                  onClick={() => setFilter(item)}
                  className={`shrink-0 rounded-full px-4 py-2 text-xs font-medium transition ${filter === item
                    ? "bg-[#294638] text-white"
                    : "bg-[#f3f5f1] text-[#59675e] hover:bg-[#e8ede6]"
                    }`}
                >
                  {item.replaceAll("_", " ")}
                </button>
              ))}
            </div>
          </div>

          {error ? (
            <div className="p-8 text-center">
              <p className="font-medium text-red-700">{error}</p>
              <button
                type="button"
                onClick={() => {
                  setLoading(true);
                  setError("");
                  void loadReservations();
                }}
                className="mt-4 rounded-full bg-[#294638] px-5 py-2.5 text-sm font-medium text-white"
              >
                Try again
              </button>
            </div>
          ) : loading ? (
            <p className="px-5 py-12 text-center text-sm text-[#748077]">
              Loading reservations…
            </p>
          ) : filteredReservations.length === 0 ? (
            <div className="px-5 py-14 text-center sm:py-16">
              <span className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-[#eaf0e9] text-2xl text-[#294638]">
                ▽
              </span>
              <h3 className="mt-4 font-serif text-xl">
                {filter === "ALL"
                  ? "No reservations yet"
                  : `No ${filter.replaceAll("_", " ").toLowerCase()} reservations`}
              </h3>
              <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-[#748077]">
                {filter === "ALL"
                  ? "Reservations received through your booking channels will appear here when they are saved in your PMS."
                  : "Try selecting another status to see more bookings."}
              </p>
              {filter !== "ALL" && (
                <button
                  type="button"
                  onClick={() => setFilter("ALL")}
                  className="mt-4 text-sm font-semibold text-[#294638] underline"
                >
                  Show all reservations
                </button>
              )}
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[760px] text-left text-sm">
                <thead className="bg-[#f8f9f6] text-xs uppercase tracking-wide text-[#748077]">
                  <tr>
                    <th className="px-5 py-4 font-medium">Reservation</th>
                    <th className="px-5 py-4 font-medium">Check-in</th>
                    <th className="px-5 py-4 font-medium">Check-out</th>
                    <th className="px-5 py-4 font-medium">Guests</th>
                    <th className="px-5 py-4 font-medium">Status</th>
                    <th className="px-5 py-4 font-medium">Checkout due</th>
                    <th className="px-5 py-4 font-medium">Customer Details</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#edf0ea]">
                  {filteredReservations.map((reservation) => (
                    <tr key={reservation.id} className="hover:bg-[#fafbf9]">
                      <td className="px-5 py-4">
                        <p className="font-medium">
                          {guests.find((guest) => guest.id === reservation.primaryGuestId)
                            ?.name ?? "Guest details unavailable"}
                        </p>

                        <p className="mt-1 text-xs text-[#59675e]">
                          {bookingSources.find(
                            (source) => source.id === reservation.bookingSourceId,
                          )?.name ?? "Booking source unavailable"}
                        </p>

                        <p className="mt-1 text-xs text-[#89938b]">
                          Ref: {reservation.externalBookingId || "Direct / PMS booking"}
                        </p>

                        <p className="mt-1 text-xs text-[#89938b]">
                          ID: {reservation.id.slice(0, 8)}…
                        </p>
                      </td>
                      <td className="whitespace-nowrap px-5 py-4">
                        {formatDate(reservation.checkInDate)}
                      </td>
                      <td className="whitespace-nowrap px-5 py-4">
                        {formatDate(reservation.checkOutDate)}
                      </td>
                      <td className="whitespace-nowrap px-5 py-4">
                        {reservation.adults} adults
                        {reservation.children > 0 &&
                          `, ${reservation.children} children`}
                        {reservation.infants > 0 &&
                          `, ${reservation.infants} infants`}
                      </td>
                      <td className="px-5 py-4">
                        <span
                          className={`rounded-full px-3 py-1.5 text-xs font-medium ${statusStyle(reservation.status)}`}
                        >
                          {reservation.status.replaceAll("_", " ")}
                        </span>
                      </td>
                      <td className="px-5 py-4">
                        {reservation.checkoutDue ? "Due" : "—"}
                      </td>
                      <td className="px-5 py-4">
                        <button
                          type="button"
                          onClick={() => {
                            setReservationRooms([]);
                            setRoomAssignments([]);
                            setSelectedReservation(reservation);
                          }}
                          className="text-sm font-medium text-[#294638] hover:underline"
                        >
                          View details →
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {!loading && !error && reservations.length > 0 && (
            <div className="border-t border-[#e8ebe5] px-5 py-4 text-xs text-[#89938b]">
              Showing {filteredReservations.length} of {reservations.length}{" "}
              reservations
            </div>
          )}
        </section>

        <p className="text-xs leading-5 text-[#89938b]">
          Reservation records are loaded from the PMS backend. Creating a
          reservation does not check a guest in or assign a physical room.
        </p>
      </div>
      {selectedReservation && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4 overflow-y-auto [&::-webkit-scrollbar]:hidden [-ms-overflow-style:none] [scrollbar-width:none]"
          onClick={handleCloseModal}
        >
          <section
            role="dialog"
            aria-modal="true"
            aria-labelledby="reservation-details-title"
            className="max-h-[90vh] w-full max-w-2xl overflow-y-auto rounded-2xl bg-white shadow-2xl"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="flex items-start justify-between border-b border-[#e8ebe5] px-6 py-5">
              <div>
                <p className="text-xs font-medium uppercase tracking-wider text-[#89938b]">
                  Reservation details
                </p>
                <h2
                  id="reservation-details-title"
                  className="mt-1 font-serif text-2xl text-[#24382e]"
                >
                  {guests.find(
                    (guest) =>
                      guest.id === selectedReservation.primaryGuestId,
                  )?.name ?? "Guest details unavailable"}
                </h2>
                <p className="mt-1 text-xs text-[#89938b]">
                  ID: {selectedReservation.id}
                </p>
              </div>

              <button
                type="button"
                onClick={handleCloseModal}
                aria-label="Close reservation details"
                className="rounded-lg px-3 py-2 text-xl text-[#59675e] hover:bg-[#FF605C] hover:text-white"
              >
                ×
              </button>
            </div>

            <div className="space-y-6 p-6">
              {actionSuccess && (
                <div role="status" aria-live="polite" className="rounded-xl bg-green-50 p-4 text-sm text-green-800 border border-green-200">
                  {actionSuccess}
                </div>
              )}

              {actionError && !showCheckInConfirm && !showCheckOutConfirm && !showConfirmModal && (
                <div role="alert" aria-live="polite" className="rounded-xl bg-red-50 p-4 text-sm text-red-700 border border-red-200 space-y-2">
                  <p className="font-semibold">Action error:</p>
                  <p>{actionError}</p>
                  {isCurrentReservationUncertain && (
                    <button
                      type="button"
                      onClick={handleRefreshSelectedReservation}
                      className="mt-2 rounded-lg bg-white px-3 py-1.5 text-xs font-medium text-[#24382e] border border-[#dce2d9] hover:bg-[#f5f6f3]"
                    >
                      Refresh reservation state
                    </button>
                  )}
                </div>
              )}

              <div>
                <h3 className="mb-3 text-sm font-semibold text-[#294638]">
                  Guest information
                </h3>

                {(() => {
                  const guest = guests.find(
                    (item) =>
                      item.id === selectedReservation.primaryGuestId,
                  );

                  return (
                    <div className="grid grid-cols-1 gap-3 rounded-xl bg-[#f8f9f6] p-4 sm:grid-cols-2">
                      <div>
                        <p className="text-xs text-[#89938b]">Guest name</p>
                        <p className="mt-1 text-sm font-medium text-[#24382e]">
                          {guest?.name ?? "Unavailable"}
                        </p>
                      </div>

                      <div>
                        <p className="text-xs text-[#89938b]">Phone</p>
                        <p className="mt-1 text-sm text-[#24382e]">
                          {guest?.phone || "Not provided"}
                        </p>
                      </div>

                      <div className="sm:col-span-2">
                        <p className="text-xs text-[#89938b]">Email</p>
                        <p className="mt-1 break-words text-sm text-[#24382e]">
                          {guest?.email || "Not provided"}
                        </p>
                      </div>
                    </div>
                  );
                })()}
              </div>

              <div>
                <h3 className="mb-3 text-sm font-semibold text-[#294638]">
                  Room details
                </h3>

                {roomDetailsLoading ? (
                  <p className="rounded-xl bg-[#f8f9f6] p-4 text-sm text-[#748077]">
                    Loading room details…
                  </p>
                ) : roomDetailsError ? (
                  <p className="rounded-xl bg-red-50 p-4 text-sm text-red-700">
                    {roomDetailsError}
                  </p>
                ) : reservationRooms.length === 0 ? (
                  <p className="rounded-xl bg-[#f8f9f6] p-4 text-sm text-[#748077]">
                    No room details are recorded for this reservation.
                  </p>
                ) : (
                  <div className="space-y-3">
                    {reservationRooms.map((reservedRoom) => {
                      const roomType = roomTypes.find(
                        (item) => item.id === reservedRoom.roomTypeId,
                      );

                      const unitPrice = Number(reservedRoom.unitPrice);
                      const subtotal =
                        reservedRoom.subtotal == null
                          ? null
                          : Number(reservedRoom.subtotal);

                      return (
                        <div
                          key={reservedRoom.id}
                          className="rounded-xl border border-[#e8ebe5] p-4"
                        >
                          <div className="flex flex-wrap items-start justify-between gap-3">
                            <div>
                              <p className="font-medium text-[#24382e]">
                                {roomType?.name ?? "Room type unavailable"}
                              </p>
                              <p className="mt-1 text-sm text-[#748077]">
                                Quantity: {getActiveReservedQuantity(reservedRoom)}
                                {reservedRoom.cancelledQuantity > 0 &&
                                  ` · Cancelled: ${reservedRoom.cancelledQuantity}`}
                              </p>
                            </div>

                            <span className="rounded-full bg-[#f0f3ed] px-3 py-1 text-xs text-[#435448]">
                              {reservedRoom.status.replaceAll("_", " ")}
                            </span>
                          </div>

                          <div className="mt-4 grid grid-cols-2 gap-3 text-sm">
                            <div>
                              <p className="text-xs text-[#89938b]">Unit price</p>
                              <p className="mt-1 font-medium text-[#24382e]">
                                {Number.isFinite(unitPrice)
                                  ? `₹${unitPrice.toLocaleString("en-IN")}`
                                  : "—"}
                              </p>
                            </div>

                            <div>
                              <p className="text-xs text-[#89938b]">Subtotal</p>
                              <p className="mt-1 font-medium text-[#24382e]">
                                {subtotal !== null && Number.isFinite(subtotal)
                                  ? `₹${subtotal.toLocaleString("en-IN")}`
                                  : "Not provided"}
                              </p>
                            </div>
                          </div>

                          {(() => {
                            const activeQuantity = getActiveReservedQuantity(reservedRoom);
                            const unitAssignments = roomAssignments.filter(
                              (item) =>
                                item.reservationRoomId === reservedRoom.id &&
                                item.status === "ASSIGNED",
                            );
                            const fullStayAssignments = unitAssignments.filter(
                              (item) =>
                                item.assignedFrom === selectedReservation.checkInDate &&
                                item.assignedUntil === selectedReservation.checkOutDate,
                            );
                            const distinctRoomIds = new Set(fullStayAssignments.map((a) => a.roomId));
                            const assignedCount = distinctRoomIds.size;
                            const unassignedCount = Math.max(0, activeQuantity - assignedCount);
                            const hasSplitOrPartial = unitAssignments.length > fullStayAssignments.length;
                            const canAssignRooms =
                              ["PENDING", "CONFIRMED"].includes(
                                selectedReservation.status,
                              ) && unassignedCount > 0;

                            return (
                              <div className="mt-4 border-t border-[#e8ebe5] pt-3 space-y-3">
                                <div className="flex flex-wrap items-center justify-between gap-2">
                                  <div className="flex items-center gap-2">
                                    <p className="text-xs font-semibold uppercase tracking-wider text-[#748077]">
                                      Physical room assignments
                                    </p>
                                    {activeQuantity === 0 ? (
                                      <span className="rounded-full bg-gray-100 px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-gray-700">
                                        Cancelled (0 active)
                                      </span>
                                    ) : assignedCount >= activeQuantity ? (
                                      <span className="rounded-full bg-green-100 px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-green-800">
                                        Fully Assigned ({assignedCount}/{activeQuantity})
                                      </span>
                                    ) : assignedCount > 0 ? (
                                      <span className="rounded-full bg-blue-100 px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-blue-800">
                                        Partially Assigned ({assignedCount}/{activeQuantity})
                                      </span>
                                    ) : (
                                      <span className="rounded-full bg-amber-100 px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-amber-800">
                                        Unassigned (0/{activeQuantity})
                                      </span>
                                    )}
                                    {hasSplitOrPartial && (
                                      <span className="rounded-full bg-amber-50 px-2 py-0.5 text-[10px] font-medium text-amber-800 border border-amber-200">
                                        Partial stay dates
                                      </span>
                                    )}
                                  </div>

                                  {canAssignRooms && (
                                    <button
                                      type="button"
                                      disabled={isCurrentReservationUncertain}
                                      onClick={(e) => {
                                        assignTriggerRef.current = e.currentTarget;
                                        setAssigningReservationRoom(reservedRoom);
                                        setSelectedPhysicalRoomId("");
                                        setAssignError("");
                                        setShowAssignModal(true);
                                      }}
                                      aria-label={`Assign physical room for ${roomType?.name || "reserved unit"}`}
                                      className="rounded-full bg-[#294638] px-3.5 py-1 text-xs font-medium text-white hover:bg-[#20382c] transition shadow-sm disabled:opacity-50 disabled:cursor-not-allowed"
                                    >
                                      + Assign Room ({unassignedCount} remaining)
                                    </button>
                                  )}
                                </div>

                                {unitAssignments.length === 0 ? (
                                  <p className="text-xs text-[#89938b]">
                                    No physical rooms assigned yet. {unassignedCount} unit(s) remaining to assign for stay {formatDate(selectedReservation.checkInDate)} – {formatDate(selectedReservation.checkOutDate)}.
                                  </p>
                                ) : (
                                  <div className="space-y-2">
                                    {unitAssignments.map((assignment) => {
                                      const physicalRoom = physicalRooms.find(
                                        (room) => room.id === assignment.roomId,
                                      );

                                      return (
                                        <div
                                          key={assignment.id}
                                          className="rounded-lg bg-[#f8f9f6] p-3 border border-[#e8ebe5]"
                                        >
                                          <div className="flex flex-wrap items-center justify-between gap-2">
                                            <p className="text-sm font-medium text-[#24382e]">
                                              Room {physicalRoom?.roomNumber ?? "unavailable"}
                                              {physicalRoom?.name
                                                ? ` · ${physicalRoom.name}`
                                                : ""}
                                            </p>
                                            {physicalRoom?.housekeepingStatus && (
                                              <span
                                                className={`rounded-full px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider ${
                                                  physicalRoom.housekeepingStatus === "CLEAN"
                                                    ? "bg-green-100 text-green-800"
                                                    : "bg-amber-100 text-amber-800"
                                                }`}
                                              >
                                                {physicalRoom.housekeepingStatus}
                                              </span>
                                            )}
                                          </div>
                                          <p className="mt-1 text-xs text-[#748077]">
                                            Full stay: {formatDate(assignment.assignedFrom)} –{" "}
                                            {formatDate(assignment.assignedUntil)}
                                          </p>
                                          <p className="mt-0.5 text-xs text-[#89938b]">
                                            Status: {assignment.status.replaceAll("_", " ")}
                                          </p>
                                        </div>
                                      );
                                    })}
                                    {unassignedCount > 0 && (
                                      <p className="text-xs text-amber-700 font-medium">
                                        Notice: {unassignedCount} more physical room unit(s) must be assigned before check-in.
                                      </p>
                                    )}
                                  </div>
                                )}
                              </div>
                            );
                          })()}

                          <p className="mt-3 text-xs text-[#89938b]">
                            Extra beds: booking-specific extra-bed details are not available
                            in the current reservation-room data.
                          </p>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>

              <div>
                <h3 className="mb-3 text-sm font-semibold text-[#294638]">
                  Stay information
                </h3>

                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                  <div className="rounded-xl border border-[#e8ebe5] p-4">
                    <p className="text-xs text-[#89938b]">Check-in</p>
                    <p className="mt-1 font-medium text-[#24382e]">
                      {formatDate(selectedReservation.checkInDate)}
                    </p>
                  </div>

                  <div className="rounded-xl border border-[#e8ebe5] p-4">
                    <p className="text-xs text-[#89938b]">Check-out</p>
                    <p className="mt-1 font-medium text-[#24382e]">
                      {formatDate(selectedReservation.checkOutDate)}
                    </p>
                  </div>

                  <div className="rounded-xl border border-[#e8ebe5] p-4 sm:col-span-2">
                    <p className="text-xs text-[#89938b]">Guests</p>
                    <p className="mt-1 text-sm text-[#24382e]">
                      {selectedReservation.adults} adults
                      {selectedReservation.children > 0 &&
                        `, ${selectedReservation.children} children`}
                      {selectedReservation.infants > 0 &&
                        `, ${selectedReservation.infants} infants`}
                    </p>
                  </div>
                </div>
              </div>

              <div>
                <h3 className="mb-3 text-sm font-semibold text-[#294638]">
                  Booking information
                </h3>

                <div className="space-y-3 rounded-xl bg-[#f8f9f6] p-4">
                  <div className="flex items-start justify-between gap-4">
                    <span className="text-sm text-[#748077]">
                      Booking source
                    </span>
                    <span className="text-right text-sm font-medium text-[#24382e]">
                      {bookingSources.find(
                        (source) =>
                          source.id === selectedReservation.bookingSourceId,
                      )?.name ?? "Unavailable"}
                    </span>
                  </div>

                  <div className="flex items-start justify-between gap-4">
                    <span className="text-sm text-[#748077]">
                      External reference
                    </span>
                    <span className="break-all text-right text-sm text-[#24382e]">
                      {selectedReservation.externalBookingId ||
                        "Direct / PMS booking"}
                    </span>
                  </div>

                  <div className="flex items-center justify-between gap-4">
                    <span className="text-sm text-[#748077]">Status</span>
                    <span
                      className={`rounded-full px-3 py-1.5 text-xs font-medium ${statusStyle(selectedReservation.status)}`}
                    >
                      {selectedReservation.status.replaceAll("_", " ")}
                    </span>
                  </div>

                  <div className="flex items-center justify-between gap-4">
                    <span className="text-sm text-[#748077]">
                      Checkout due
                    </span>
                    <span className="text-sm font-medium text-[#24382e]">
                      {selectedReservation.checkoutDue ? "Due" : "—"}
                    </span>
                  </div>
                </div>
              </div>

              {/* Front Desk Operations Section */}
              <div className="border-t border-[#e8ebe5] pt-6">
                <h3 className="mb-3 text-sm font-semibold text-[#294638]">
                  Front desk operations
                </h3>

                {selectedReservation.status === "CONFIRMED" && (() => {
                  const completeness = evaluateReservationAssignmentCompleteness(
                    selectedReservation,
                    reservationRooms,
                    roomAssignments,
                    physicalRooms,
                    roomTypes,
                    roomDetailsLoading,
                    roomDetailsError
                  );

                  return (
                    <div className="rounded-xl border border-[#dce2d9] bg-[#f8f9f6] p-4 space-y-3">
                      <div>
                        <p className="text-xs font-semibold uppercase tracking-wider text-[#557762]">
                          Guest Arrival / Check-in
                        </p>
                        <p className="mt-1 text-xs text-[#59675e] leading-relaxed">
                          Staff check-in policy: The PMS backend requires that all reserved rooms have complete full-stay physical room assignments and that all assigned rooms are in <strong className="font-semibold text-[#24382e]">CLEAN</strong> housekeeping status in the property timezone (Asia/Kolkata). Check-in cannot occur before the scheduled arrival date.
                        </p>
                      </div>

                      <div className="rounded-lg bg-white p-3 border border-[#e8ebe5] text-xs space-y-1.5">
                        <div className="flex justify-between text-[#435448]">
                          <span>Reserved active room units:</span>
                          <span className="font-semibold">{completeness.totalActiveUnits}</span>
                        </div>
                        <div className="flex justify-between text-[#435448]">
                          <span>Assigned physical rooms:</span>
                          <span className="font-semibold">{completeness.totalValidAssignedUnits}</span>
                        </div>
                        {completeness.notice && (
                          <p className="mt-1 text-amber-700 font-medium">
                            Notice: {completeness.notice}
                          </p>
                        )}
                        {physicalRooms.some(
                          (p) =>
                            roomAssignments.some(
                              (a) => a.roomId === p.id && a.status === "ASSIGNED"
                            ) &&
                            p.housekeepingStatus &&
                            p.housekeepingStatus !== "CLEAN"
                        ) && (
                          <p className="mt-1 text-amber-700 font-medium">
                            Notice: One or more assigned physical rooms are not marked CLEAN. Backend check-in validation will enforce cleanliness.
                          </p>
                        )}
                      </div>

                      <div className="pt-1 flex items-center justify-between gap-3">
                        <p className="text-xs text-[#89938b]">
                          Scheduled arrival: {formatDate(selectedReservation.checkInDate)}
                        </p>
                        <button
                          type="button"
                          disabled={!completeness.canCheckIn || isCurrentReservationUncertain}
                          onClick={() => {
                            setActionError("");
                            setShowCheckInConfirm(true);
                          }}
                          aria-label="Check in guest for this reservation"
                          className="rounded-full bg-[#294638] px-5 py-2 text-sm font-medium text-white hover:bg-[#20382c] transition shadow-sm disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                          Check in guest →
                        </button>
                      </div>
                    </div>
                  );
                })()}

                {selectedReservation.status === "CHECKED_IN" && (
                  <div className="rounded-xl border border-[#dce2d9] bg-[#f8f9f6] p-4 space-y-3">
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-wider text-[#557762]">
                        Guest Departure / Checkout
                      </p>
                      <p className="mt-1 text-xs text-[#59675e] leading-relaxed">
                        Staff checkout policy: The reservation remains in <strong className="font-semibold text-[#24382e]">CHECKED_IN</strong> status until front desk staff confirms guest departure. Confirming checkout releases all physical room assignments and marks vacated rooms <strong className="font-semibold text-[#24382e]">DIRTY</strong> for housekeeping.
                      </p>
                    </div>

                    <div className="rounded-lg bg-white p-3 border border-[#e8ebe5] text-xs space-y-1.5">
                      <div className="flex justify-between text-[#435448]">
                        <span>Scheduled checkout date:</span>
                        <span className="font-semibold">{formatDate(selectedReservation.checkOutDate)}</span>
                      </div>
                      <div className="flex justify-between text-[#435448]">
                        <span>Checkout deadline:</span>
                        <span className="font-semibold">{formatDateTimeIST(selectedReservation.checkoutDueAt)}</span>
                      </div>
                      <div className="flex justify-between text-[#435448]">
                        <span>Checkout status:</span>
                        <span className={`font-semibold ${selectedReservation.checkoutDue ? "text-amber-700" : "text-green-700"}`}>
                          {selectedReservation.checkoutDue ? "Overdue / Due today" : "On schedule"}
                        </span>
                      </div>
                    </div>

                    <div className="pt-1 flex items-center justify-between gap-3">
                      <p className="text-xs text-[#89938b]">
                        Active in-house stay
                      </p>
                      <button
                        type="button"
                        onClick={() => {
                          setActionError("");
                          setShowCheckOutConfirm(true);
                        }}
                        aria-label="Confirm checkout for this reservation"
                        className="rounded-full bg-[#8c3327] px-5 py-2 text-sm font-medium text-white hover:bg-[#73291f] transition shadow-sm"
                      >
                        Confirm checkout →
                      </button>
                    </div>
                  </div>
                )}

                {selectedReservation.status === "CHECKED_OUT" && (
                  <div className="rounded-xl border border-[#e8ebe5] bg-[#f8f9f6] p-4 space-y-2">
                    <div className="flex items-center gap-2">
                      <span className="inline-block h-2.5 w-2.5 rounded-full bg-gray-500" />
                      <p className="text-xs font-semibold uppercase tracking-wider text-gray-700">
                        Stay Completed · Checked Out
                      </p>
                    </div>
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 text-xs text-[#435448] pt-1">
                      <div>
                        <span className="text-[#89938b]">Actual checkout time:</span>
                        <p className="font-medium text-[#24382e] mt-0.5">
                          {formatDateTimeIST(selectedReservation.actualCheckedOutAt)}
                        </p>
                      </div>
                      {selectedReservation.checkedOutByStaffUserId && (
                        <div>
                          <span className="text-[#89938b]">Staff actor ID:</span>
                          <p className="font-mono text-xs text-[#24382e] mt-0.5 break-all">
                            {selectedReservation.checkedOutByStaffUserId}
                          </p>
                        </div>
                      )}
                    </div>
                    <p className="text-xs text-[#748077] pt-1">
                      This reservation is in terminal CHECKED_OUT status. Physical room assignments were released and vacated rooms were marked dirty.
                    </p>
                  </div>
                )}

                {selectedReservation.status === "PENDING" && (() => {
                  const bookingSource = bookingSources.find(
                    (source) => source.id === selectedReservation.bookingSourceId
                  );
                  const isDirect = bookingSource?.code?.toUpperCase() === "DIRECT";

                  if (isDirect) {
                    return (
                      <div className="rounded-xl border border-[#dce2d9] bg-[#f8f9f6] p-4 space-y-3">
                        <div>
                          <p className="text-xs font-semibold uppercase tracking-wider text-[#557762]">
                            Reservation Confirmation
                          </p>
                          <p className="mt-1 text-xs text-[#59675e] leading-relaxed">
                            Staff confirmation policy: Eligible DIRECT reservations must be confirmed by front desk staff before guest arrival and room assignment. Room availability was already held upon reservation creation; confirmation validates guest arrival readiness without assigning a physical room.
                          </p>
                        </div>

                        <div className="rounded-lg bg-white p-3 border border-[#e8ebe5] text-xs space-y-1.5">
                          <div className="flex justify-between text-[#435448]">
                            <span>Booking source:</span>
                            <span className="font-semibold text-[#24382e]">
                              {bookingSource?.name || "Direct / PMS"} ({bookingSource?.code || "DIRECT"})
                            </span>
                          </div>
                          <div className="flex justify-between text-[#435448]">
                            <span>Scheduled arrival:</span>
                            <span className="font-semibold">{formatDate(selectedReservation.checkInDate)}</span>
                          </div>
                          <div className="flex justify-between text-[#435448]">
                            <span>Reserved active room units:</span>
                            <span className="font-semibold">
                              {reservationRooms.reduce(
                                (sum, r) => sum + getActiveReservedQuantity(r),
                                0
                              )}
                            </span>
                          </div>
                        </div>

                        <div className="pt-1 flex items-center justify-between gap-3">
                          <p className="text-xs text-[#89938b]">
                            Pending front desk confirmation
                          </p>
                          <button
                            ref={confirmTriggerRef}
                            type="button"
                            disabled={confirming || isCurrentReservationUncertain}
                            onClick={() => {
                              setActionError("");
                              setShowConfirmModal(true);
                            }}
                            aria-label="Confirm this reservation"
                            className="rounded-full bg-[#294638] px-5 py-2 text-sm font-medium text-white hover:bg-[#20382c] transition shadow-sm disabled:opacity-50 disabled:cursor-not-allowed"
                          >
                            Confirm reservation →
                          </button>
                        </div>
                      </div>
                    );
                  }

                  return (
                    <div className="rounded-xl border border-[#e8ebe5] bg-[#f8f9f6] p-4 text-xs text-[#748077] space-y-2">
                      <div className="flex items-center gap-2">
                        <span className="inline-block h-2 w-2 rounded-full bg-amber-500" />
                        <p className="font-semibold text-[#435448]">
                          External Channel / OTA Reservation
                        </p>
                      </div>
                      <p>
                        This reservation was booked via {bookingSource?.name || "an external platform"} ({bookingSource?.code || "OTA"}). OTA reservations cannot be confirmed locally and must be managed through the booking platform.
                      </p>
                    </div>
                  );
                })()}

                {["CANCELLED", "NO_SHOW"].includes(selectedReservation.status) && (
                  <div className="rounded-xl border border-[#e8ebe5] bg-[#f8f9f6] p-4 text-xs text-[#748077]">
                    {selectedReservation.status === "CANCELLED" && (
                      <p>This reservation has been CANCELLED. No further front desk operations can be performed.</p>
                    )}
                    {selectedReservation.status === "NO_SHOW" && (
                      <p>This reservation was marked as NO_SHOW. Front desk check-in/checkout is not applicable.</p>
                    )}
                  </div>
                )}
              </div>
            </div>

            <div className="flex justify-end border-t border-[#e8ebe5] px-6 py-4">
              <button
                type="button"
                onClick={handleCloseModal}
                className="rounded-full border border-[#dce2d9] bg-white px-5 py-2 text-sm font-medium text-[#435448] hover:bg-[#f5f6f3]"
              >
                Close
              </button>
            </div>

            {/* Reservation Confirmation Dialog */}
            {showConfirmModal && (
              <div
                className="fixed inset-0 z-60 flex items-center justify-center bg-black/50 p-4"
                role="dialog"
                aria-modal="true"
                aria-labelledby="confirm-dialog-title"
                aria-describedby="confirm-dialog-description"
                onKeyDown={handleConfirmDialogKeyDown}
              >
                <div ref={confirmDialogRef} className="w-full max-w-md rounded-2xl bg-white p-6 shadow-2xl space-y-4">
                  <div className="flex items-start justify-between">
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-wider text-[#557762]">
                        Front Desk Confirmation
                      </p>
                      <h3 id="confirm-dialog-title" className="mt-1 font-serif text-xl text-[#24382e]">
                        Confirm Direct Reservation
                      </h3>
                    </div>
                    <button
                      type="button"
                      disabled={confirming}
                      onClick={() => setShowConfirmModal(false)}
                      className="text-xl text-[#59675e] hover:text-[#24382e] disabled:opacity-50"
                      aria-label="Close reservation confirmation dialog"
                    >
                      ×
                    </button>
                  </div>

                  <div className="space-y-2 rounded-xl bg-[#f8f9f6] p-3 text-xs text-[#435448]">
                    <div className="flex justify-between">
                      <span className="text-[#89938b]">Guest:</span>
                      <span className="font-medium text-[#24382e]">
                        {guests.find((g) => g.id === selectedReservation.primaryGuestId)?.name ?? "Guest"}
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-[#89938b]">Stay period:</span>
                      <span>{formatDate(selectedReservation.checkInDate)} – {formatDate(selectedReservation.checkOutDate)}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-[#89938b]">Booking source:</span>
                      <span className="font-semibold text-[#24382e]">
                        {bookingSources.find((s) => s.id === selectedReservation.bookingSourceId)?.name ?? "Direct"} (DIRECT)
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-[#89938b]">Room types &amp; quantities:</span>
                      <span className="font-medium text-[#24382e]">
                        {reservationRooms.length > 0
                          ? reservationRooms
                              .map((r) => {
                                const rtName = roomTypes.find((t) => t.id === r.roomTypeId)?.name || "Room";
                                const qty = getActiveReservedQuantity(r);
                                return `${qty}x ${rtName}`;
                              })
                              .join(", ")
                          : "1 unit"}
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-[#89938b]">Reservation ID:</span>
                      <span className="font-mono">{selectedReservation.id.slice(0, 8)}…</span>
                    </div>
                  </div>

                  <p id="confirm-dialog-description" className="text-xs text-[#59675e] leading-relaxed">
                    Confirming will transition this reservation from <strong className="font-semibold text-[#24382e]">PENDING</strong> to <strong className="font-semibold text-[#24382e]">CONFIRMED</strong>.
                    Room availability was already held upon reservation creation; this action confirms the booking and authorizes front-desk processing without assigning a physical room number or checking in the guest.
                  </p>

                  {actionError && (
                    <div role="alert" aria-live="polite" className="rounded-xl bg-red-50 p-3 text-xs text-red-700 border border-red-200 space-y-2">
                      <div>
                        <p className="font-semibold">Confirmation could not be completed:</p>
                        <p className="mt-0.5">{actionError}</p>
                      </div>
                      {isCurrentReservationUncertain && (
                        <div className="pt-1">
                          <p className="text-amber-800">
                            The connection timed out or an error occurred. Please refresh the reservation state to check current status before re-attempting.
                          </p>
                          <button
                            type="button"
                            onClick={handleRefreshSelectedReservation}
                            className="mt-2 rounded-lg bg-white px-3 py-1 text-xs font-medium text-[#24382e] border border-[#dce2d9] hover:bg-[#f5f6f3]"
                          >
                            Refresh reservation state
                          </button>
                        </div>
                      )}
                    </div>
                  )}

                  <div className="flex items-center justify-end gap-3 pt-2">
                    <button
                      type="button"
                      disabled={confirming}
                      onClick={() => setShowConfirmModal(false)}
                      className="rounded-full border border-[#dce2d9] bg-white px-4 py-2 text-xs font-medium text-[#435448] hover:bg-[#f5f6f3] disabled:opacity-50"
                    >
                      Cancel
                    </button>
                    <button
                      type="button"
                      disabled={confirming || isCurrentReservationUncertain}
                      onClick={handleConfirmSubmit}
                      aria-label="Confirm reservation now"
                      className="rounded-full bg-[#294638] px-5 py-2 text-xs font-medium text-white hover:bg-[#20382c] transition shadow-sm disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      {confirming ? "Confirming…" : "Confirm Reservation"}
                    </button>
                  </div>
                </div>
              </div>
            )}

            {/* Check-In Confirmation Dialog */}
            {showCheckInConfirm && (
              <div
                className="fixed inset-0 z-60 flex items-center justify-center bg-black/50 p-4"
                role="dialog"
                aria-modal="true"
                aria-labelledby="checkin-dialog-title"
              >
                <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-2xl space-y-4">
                  <div className="flex items-start justify-between">
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-wider text-[#557762]">
                        Front Desk Confirmation
                      </p>
                      <h3 id="checkin-dialog-title" className="mt-1 font-serif text-xl text-[#24382e]">
                        Confirm Guest Check-In
                      </h3>
                    </div>
                    <button
                      type="button"
                      disabled={checkingIn}
                      onClick={() => setShowCheckInConfirm(false)}
                      className="text-xl text-[#59675e] hover:text-[#24382e] disabled:opacity-50"
                      aria-label="Close check-in confirmation"
                    >
                      ×
                    </button>
                  </div>

                  <div className="space-y-2 rounded-xl bg-[#f8f9f6] p-3 text-xs text-[#435448]">
                    <div className="flex justify-between">
                      <span className="text-[#89938b]">Guest:</span>
                      <span className="font-medium text-[#24382e]">
                        {guests.find((g) => g.id === selectedReservation.primaryGuestId)?.name ?? "Guest"}
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-[#89938b]">Stay period:</span>
                      <span>{formatDate(selectedReservation.checkInDate)} – {formatDate(selectedReservation.checkOutDate)}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-[#89938b]">Reservation ID:</span>
                      <span className="font-mono">{selectedReservation.id.slice(0, 8)}…</span>
                    </div>
                  </div>

                  <p className="text-xs text-[#59675e] leading-relaxed">
                    Confirming check-in will transition the reservation to <strong className="font-semibold text-[#24382e]">CHECKED_IN</strong> and mark assigned physical rooms as <strong className="font-semibold text-[#24382e]">OCCUPIED</strong>. The PMS backend will verify full-stay room assignments and that all assigned rooms are CLEAN in the property timezone.
                  </p>

                  {actionError && (
                    <div className="rounded-xl bg-red-50 p-3 text-xs text-red-700 border border-red-200 space-y-2">
                      <div>
                        <p className="font-semibold">Check-in could not be completed:</p>
                        <p className="mt-0.5">{actionError}</p>
                      </div>
                      {uncertainOutcome && (
                        <div className="pt-1">
                          <p className="text-amber-800">
                            The connection timed out or an error occurred. Please refresh the reservation state to check current status before re-attempting.
                          </p>
                          <button
                            type="button"
                            onClick={handleRefreshSelectedReservation}
                            className="mt-2 rounded-lg bg-white px-3 py-1 text-xs font-medium text-[#24382e] border border-[#dce2d9] hover:bg-[#f5f6f3]"
                          >
                            Refresh reservation state
                          </button>
                        </div>
                      )}
                    </div>
                  )}

                  <div className="flex justify-end gap-3 pt-2">
                    <button
                      type="button"
                      disabled={checkingIn}
                      onClick={() => setShowCheckInConfirm(false)}
                      className="rounded-full border border-[#dce2d9] px-4 py-2 text-xs font-medium text-[#435448] hover:bg-[#f5f6f3] disabled:opacity-50"
                    >
                      Cancel
                    </button>
                    <button
                      type="button"
                      disabled={checkingIn}
                      onClick={handleCheckInSubmit}
                      className="rounded-full bg-[#294638] px-5 py-2 text-xs font-medium text-white hover:bg-[#20382c] transition shadow-sm disabled:opacity-50"
                    >
                      {checkingIn ? "Checking in guest…" : "Confirm Check-in"}
                    </button>
                  </div>
                </div>
              </div>
            )}

            {/* Checkout Confirmation Dialog */}
            {showCheckOutConfirm && (
              <div
                className="fixed inset-0 z-60 flex items-center justify-center bg-black/50 p-4"
                role="dialog"
                aria-modal="true"
                aria-labelledby="checkout-dialog-title"
              >
                <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-2xl space-y-4">
                  <div className="flex items-start justify-between">
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-wider text-[#8c3327]">
                        Irreversible Action
                      </p>
                      <h3 id="checkout-dialog-title" className="mt-1 font-serif text-xl text-[#24382e]">
                        Confirm Guest Checkout
                      </h3>
                    </div>
                    <button
                      type="button"
                      disabled={checkingOut}
                      onClick={() => setShowCheckOutConfirm(false)}
                      className="text-xl text-[#59675e] hover:text-[#24382e] disabled:opacity-50"
                      aria-label="Close checkout confirmation"
                    >
                      ×
                    </button>
                  </div>

                  <div className="space-y-2 rounded-xl bg-[#f8f9f6] p-3 text-xs text-[#435448]">
                    <div className="flex justify-between">
                      <span className="text-[#89938b]">Guest:</span>
                      <span className="font-medium text-[#24382e]">
                        {guests.find((g) => g.id === selectedReservation.primaryGuestId)?.name ?? "Guest"}
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-[#89938b]">Scheduled departure:</span>
                      <span>{formatDate(selectedReservation.checkOutDate)}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-[#89938b]">Checkout deadline:</span>
                      <span>{formatDateTimeIST(selectedReservation.checkoutDueAt)}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-[#89938b]">Reservation ID:</span>
                      <span className="font-mono">{selectedReservation.id.slice(0, 8)}…</span>
                    </div>
                  </div>

                  <div className="rounded-xl bg-amber-50 p-3 text-xs text-amber-800 border border-amber-200">
                    <p className="font-semibold">Irreversible Lifecycle Transition:</p>
                    <p className="mt-0.5 leading-relaxed">
                      Confirming checkout transitions this reservation to <strong className="font-semibold">CHECKED_OUT</strong>. All room assignments will be <strong className="font-semibold">RELEASED</strong>, and vacated rooms will be marked <strong className="font-semibold">DIRTY</strong> for housekeeping.
                    </p>
                  </div>

                  {actionError && (
                    <div className="rounded-xl bg-red-50 p-3 text-xs text-red-700 border border-red-200 space-y-2">
                      <div>
                        <p className="font-semibold">Checkout failed:</p>
                        <p className="mt-0.5">{actionError}</p>
                      </div>
                      {uncertainOutcome && (
                        <div className="pt-1">
                          <p className="text-amber-800">
                            The connection timed out or an error occurred. Please refresh the reservation state to check current status before re-attempting.
                          </p>
                          <button
                            type="button"
                            onClick={handleRefreshSelectedReservation}
                            className="mt-2 rounded-lg bg-white px-3 py-1 text-xs font-medium text-[#24382e] border border-[#dce2d9] hover:bg-[#f5f6f3]"
                          >
                            Refresh reservation state
                          </button>
                        </div>
                      )}
                    </div>
                  )}

                  <div className="flex justify-end gap-3 pt-2">
                    <button
                      type="button"
                      disabled={checkingOut}
                      onClick={() => setShowCheckOutConfirm(false)}
                      className="rounded-full border border-[#dce2d9] px-4 py-2 text-xs font-medium text-[#435448] hover:bg-[#f5f6f3] disabled:opacity-50"
                    >
                      Cancel
                    </button>
                    <button
                      type="button"
                      disabled={checkingOut}
                      onClick={handleCheckOutSubmit}
                      className="rounded-full bg-[#8c3327] px-5 py-2 text-xs font-medium text-white hover:bg-[#73291f] transition shadow-sm disabled:opacity-50"
                    >
                      {checkingOut ? "Processing checkout…" : "Confirm Checkout"}
                    </button>
                  </div>
                </div>
              </div>
            )}

            {/* Physical Room Assignment Dialog */}
            {showAssignModal && assigningReservationRoom && (
              <div
                className="fixed inset-0 z-60 flex items-center justify-center bg-black/50 p-4"
                role="dialog"
                aria-modal="true"
                aria-labelledby="assign-dialog-title"
                aria-describedby="assign-dialog-description"
                onKeyDown={handleAssignDialogKeyDown}
              >
                <div
                  ref={assignDialogRef}
                  className="w-full max-w-lg rounded-2xl bg-white p-6 shadow-2xl space-y-4 max-h-[90vh] overflow-y-auto"
                >
                  <div className="flex items-start justify-between">
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-wider text-[#557762]">
                        Front Desk Room Assignment
                      </p>
                      <h3
                        id="assign-dialog-title"
                        className="mt-1 font-serif text-xl text-[#24382e]"
                      >
                        Assign Physical Room
                      </h3>
                    </div>
                    <button
                      type="button"
                      disabled={assigningRoom}
                      onClick={() => setShowAssignModal(false)}
                      className="text-xl text-[#59675e] hover:text-[#24382e] disabled:opacity-50"
                      aria-label="Close room assignment dialog"
                    >
                      ×
                    </button>
                  </div>

                  <div className="space-y-2 rounded-xl bg-[#f8f9f6] p-3 text-xs text-[#435448]">
                    <div className="flex justify-between">
                      <span className="text-[#89938b]">Guest:</span>
                      <span className="font-medium text-[#24382e]">
                        {guests.find((g) => g.id === selectedReservation.primaryGuestId)?.name ?? "Guest"}
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-[#89938b]">Stay period:</span>
                      <span className="font-semibold text-[#24382e]">
                        {formatDate(selectedReservation.checkInDate)} – {formatDate(selectedReservation.checkOutDate)}
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-[#89938b]">Target room type:</span>
                      <span className="font-semibold text-[#24382e]">
                        {roomTypes.find((t) => t.id === assigningReservationRoom.roomTypeId)?.name || "Room Type"}
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-[#89938b]">Unit progress:</span>
                      <span>
                        Assigning unit #{roomAssignments.filter((a) => a.reservationRoomId === assigningReservationRoom.id && a.status === "ASSIGNED").length + 1} of{" "}
                        {getActiveReservedQuantity(assigningReservationRoom)}
                      </span>
                    </div>
                  </div>

                  <p
                    id="assign-dialog-description"
                    className="text-xs text-[#59675e] leading-relaxed"
                  >
                    Provisional candidate selection: Candidate physical rooms below match the reserved room type and are currently active. Rooms with housekeeping status DIRTY or CLEANING IN PROGRESS may be pre-assigned for future dates, but must be CLEAN at check-in. The backend authoritatively enforces full-stay date availability and database overlap constraints upon assignment.
                  </p>

                  <div className="space-y-2">
                    <p className="text-xs font-semibold text-[#294638]">
                      Available candidate physical rooms:
                    </p>

                    {(() => {
                      const assignedPhysicalIds = new Set(
                        roomAssignments
                          .filter((a) => a.status === "ASSIGNED")
                          .map((a) => a.roomId)
                      );

                      const candidates = physicalRooms.filter((room) => {
                        if (room.roomTypeId !== assigningReservationRoom.roomTypeId) return false;
                        if (
                          !selectedReservation.propertyId ||
                          !room.propertyId ||
                          room.propertyId !== selectedReservation.propertyId
                        ) {
                          return false;
                        }
                        if (room.active !== true) return false;
                        if (room.status === "OUT_OF_SERVICE") return false;
                        if (assignedPhysicalIds.has(room.id)) return false;
                        return true;
                      });

                      if (candidates.length === 0) {
                        return (
                          <div className="rounded-xl border border-dashed border-[#dce2d9] p-4 text-center text-xs text-[#748077]">
                            <p>No candidate physical rooms found for this room type.</p>
                            <p className="mt-1 text-[11px] text-[#89938b]">
                              All physical rooms may already be assigned within this reservation, out of service, or inactive.
                            </p>
                          </div>
                        );
                      }

                      return (
                        <div className="max-h-56 space-y-2 overflow-y-auto pr-1">
                          {candidates.map((room) => {
                            const isSelected = selectedPhysicalRoomId === room.id;
                            const isDirty = room.housekeepingStatus === "DIRTY";
                            const isCleaning = room.housekeepingStatus === "CLEANING_IN_PROGRESS";
                            const isClean = room.housekeepingStatus === "CLEAN";

                            return (
                              <label
                                key={room.id}
                                className={`flex items-center justify-between rounded-xl border p-3 cursor-pointer transition ${
                                  isSelected
                                    ? "border-[#294638] bg-[#f0f5f2] ring-1 ring-[#294638]"
                                    : "border-[#e8ebe5] bg-white hover:border-[#cfd6cc]"
                                }`}
                              >
                                <div className="flex items-center gap-3">
                                  <input
                                    type="radio"
                                    name="candidatePhysicalRoom"
                                    value={room.id}
                                    checked={isSelected}
                                    disabled={assigningRoom}
                                    onChange={() => setSelectedPhysicalRoomId(room.id)}
                                    aria-label={`Select Room ${room.roomNumber}`}
                                    className="h-4 w-4 text-[#294638] focus:ring-[#294638]"
                                  />
                                  <div>
                                    <p className="text-sm font-semibold text-[#24382e]">
                                      Room {room.roomNumber}
                                      {room.name ? ` · ${room.name}` : ""}
                                    </p>
                                    <p className="text-xs text-[#748077]">
                                      {room.floor ? `Floor ${room.floor} · ` : ""}
                                      Status: {room.status || "AVAILABLE"}
                                    </p>
                                  </div>
                                </div>

                                <div className="text-right">
                                  <span
                                    className={`inline-block rounded-full px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider ${
                                      isClean
                                        ? "bg-green-100 text-green-800"
                                        : isCleaning
                                        ? "bg-blue-100 text-blue-800"
                                        : isDirty
                                        ? "bg-amber-100 text-amber-800"
                                        : "bg-gray-100 text-gray-800"
                                    }`}
                                  >
                                    {room.housekeepingStatus || "UNKNOWN"}
                                  </span>
                                  {isDirty && (
                                    <p className="text-[10px] text-amber-700 mt-0.5">
                                      Dirty (Pre-assignment allowed)
                                    </p>
                                  )}
                                </div>
                              </label>
                            );
                          })}
                        </div>
                      );
                    })()}
                  </div>

                  {assignError && (
                    <div
                      role="alert"
                      aria-live="polite"
                      className="rounded-xl bg-red-50 p-3 text-xs text-red-700 border border-red-200 space-y-2"
                    >
                      <div>
                        <p className="font-semibold">Assignment could not be completed:</p>
                        <p className="mt-0.5">{assignError}</p>
                      </div>
                      {isCurrentReservationUncertain && (
                        <div className="pt-1">
                          <p className="text-amber-800">
                            The connection timed out or an ambiguous network error occurred. Please refresh the reservation state to verify if the assignment was created.
                          </p>
                          <button
                            type="button"
                            onClick={handleRefreshSelectedReservation}
                            className="mt-2 rounded-lg bg-white px-3 py-1 text-xs font-medium text-[#24382e] border border-[#dce2d9] hover:bg-[#f5f6f3]"
                          >
                            Refresh reservation state
                          </button>
                        </div>
                      )}
                    </div>
                  )}

                  <div className="flex items-center justify-end gap-3 pt-2 border-t border-[#e8ebe5]">
                    <button
                      type="button"
                      disabled={assigningRoom}
                      onClick={() => setShowAssignModal(false)}
                      className="rounded-full border border-[#dce2d9] bg-white px-4 py-2 text-xs font-medium text-[#435448] hover:bg-[#f5f6f3] disabled:opacity-50"
                    >
                      Cancel
                    </button>
                    <button
                      type="button"
                      disabled={
                        !selectedPhysicalRoomId ||
                        assigningRoom ||
                        isCurrentReservationUncertain
                      }
                      onClick={handleAssignRoomSubmit}
                      aria-label="Confirm room assignment now"
                      className="rounded-full bg-[#294638] px-5 py-2 text-xs font-medium text-white hover:bg-[#20382c] transition shadow-sm disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      {assigningRoom ? "Assigning room…" : "Confirm Assignment"}
                    </button>
                  </div>
                </div>
              </div>
            )}
          </section>
        </div>
      )}
    </main>
  );
}


