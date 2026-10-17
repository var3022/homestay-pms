
"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";

type Reservation = {
  id: string;
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
  roomNumber: string;
  name?: string;
  roomTypeId: string;
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

    try {
      const response = await fetch("/api/reservations", {
        cache: "no-store",
      });
      const data = await readResponse(response);
      setReservations(unwrapList<Reservation>(data));
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Could not load reservations.",
      );
    } finally {
      setLoading(false);
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
  useEffect(() => {
    if (!selectedReservation) {
      return;
    }

    let cancelled = false;
    const reservationId = selectedReservation.id;

    async function loadRoomDetails() {
      setRoomDetailsLoading(true);
      setRoomDetailsError("");

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

        if (!cancelled) {
          setReservationRooms(roomList);
          setPhysicalRooms(physicalRoomList);
          setRoomAssignments(assignmentLists.flat());
        }
      } catch (err) {
        if (!cancelled) {
          setRoomDetailsError(
            err instanceof Error
              ? err.message
              : "Unable to load room details.",
          );
        }
      } finally {
        if (!cancelled) {
          setRoomDetailsLoading(false);
        }
      }
    }

    void loadRoomDetails();

    return () => {
      cancelled = true;
    };
  }, [selectedReservation]);

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

  return (
    <main className="min-h-screen bg-[#f5f6f3] text-[#24382e]">
      <header className="border-b border-[#e1e6df] bg-white px-5 py-3 sm:px-8 sm:py-3">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-4">
          <div>
            <Link
              href="/dashboard"
              className="text-sm text-[#748077] hover:text-[#294638]"
            >
              ← Back to dashboard
            </Link>
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
                          onClick={() => setSelectedReservation(reservation)}
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
          onClick={() => setSelectedReservation(null)}
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
                onClick={() => setSelectedReservation(null)}
                aria-label="Close reservation details"
                className="rounded-lg px-3 py-2 text-xl text-[#59675e] hover:bg-[#FF605C] hover:text-white"
              >
                ×
              </button>
            </div>

            <div className="space-y-6 p-6">
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

                      const assignments = roomAssignments.filter(
                        (item) =>
                          item.reservationRoomId === reservedRoom.id &&
                          item.status !== "RELEASED",
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
                                Quantity: {reservedRoom.activeQuantity}
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

                          <div className="mt-4 border-t border-[#e8ebe5] pt-3">
                            <p className="text-xs font-medium text-[#748077]">
                              Physical room assignments
                            </p>

                            {assignments.length === 0 ? (
                              <p className="mt-2 text-sm text-[#89938b]">
                                Room not assigned yet
                              </p>
                            ) : (
                              <div className="mt-2 space-y-2">
                                {assignments.map((assignment) => {
                                  const physicalRoom = physicalRooms.find(
                                    (room) => room.id === assignment.roomId,
                                  );

                                  return (
                                    <div
                                      key={assignment.id}
                                      className="rounded-lg bg-[#f8f9f6] p-3"
                                    >
                                      <p className="text-sm font-medium text-[#24382e]">
                                        Room {physicalRoom?.roomNumber ?? "unavailable"}
                                        {physicalRoom?.name
                                          ? ` · ${physicalRoom.name}`
                                          : ""}
                                      </p>
                                      <p className="mt-1 text-xs text-[#748077]">
                                        {formatDate(assignment.assignedFrom)} –{" "}
                                        {formatDate(assignment.assignedUntil)}
                                      </p>
                                      <p className="mt-1 text-xs text-[#748077]">
                                        Assignment: {assignment.status.replaceAll("_", " ")}
                                      </p>
                                    </div>
                                  );
                                })}
                              </div>
                            )}
                          </div>

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
            </div>

            {/* <div className="flex justify-end border-t border-[#e8ebe5] px-6 py-4">
              <button
                type="button"
                onClick={() => setSelectedReservation(null)}
                className="rounded-xl bg-[#294638] px-5 py-2.5 text-sm font-medium text-white hover:bg-[#3b5b49]"
              >
                Close
              </button>
            </div> */}
          </section>
        </div>
      )}
    </main>
  );
}


