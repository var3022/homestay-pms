"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { StaffHeader } from "../StaffHeader";
import {
  type HousekeepingStatus,
  type PhysicalRoom,
  type RoomStatus,
  type RoomType,
  isRoomReadyForCheckIn,
  getAllowedTransitions,
  isUncertainError,
} from "@/lib/rooms/housekeeping";

export type { HousekeepingStatus, PhysicalRoom, RoomStatus, RoomType };
export { isRoomReadyForCheckIn, getAllowedTransitions, isUncertainError };

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

async function readResponse(response: Response): Promise<unknown> {
  const contentType = response.headers.get("content-type");
  const data = contentType?.includes("application/json")
    ? await response.json()
    : await response.text();

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

export default function RoomsHousekeepingPage() {
  const [rooms, setRooms] = useState<PhysicalRoom[]>([]);
  const [roomTypes, setRoomTypes] = useState<RoomType[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  // Filters & Search
  const [housekeepingFilter, setHousekeepingFilter] = useState<string>("ALL");
  const [roomStatusFilter, setRoomStatusFilter] = useState<string>("ALL");
  const [searchQuery, setSearchQuery] = useState("");

  // Action states
  const [updatingRoomId, setUpdatingRoomId] = useState<string | null>(null);
  const [actionError, setActionError] = useState("");
  const [actionSuccess, setActionSuccess] = useState("");
  const [uncertainOutcome, setUncertainOutcome] = useState(false);

  // Confirmation dialog modal
  const [pendingAction, setPendingAction] = useState<{
    room: PhysicalRoom;
    targetStatus: HousekeepingStatus;
  } | null>(null);

  useEffect(() => {
    if (!actionSuccess) return;
    const timer = window.setTimeout(() => {
      setActionSuccess("");
    }, 5000);
    return () => window.clearTimeout(timer);
  }, [actionSuccess]);

  const loadData = useCallback(async () => {
    try {
      setError("");
      const [roomsRes, roomTypesRes] = await Promise.all([
        fetch("/api/rooms", { cache: "no-store" }),
        fetch("/api/room-types", { cache: "no-store" }),
      ]);

      const [roomsData, roomTypesData] = await Promise.all([
        readResponse(roomsRes),
        readResponse(roomTypesRes),
      ]);

      setRooms(unwrapList<PhysicalRoom>(roomsData));
      setRoomTypes(unwrapList<RoomType>(roomTypesData));
      setUncertainOutcome(false);
      setActionError("");
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to load rooms and room types."
      );
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadData();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [loadData]);

  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape" && pendingAction && !updatingRoomId) {
        setPendingAction(null);
      }
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [pendingAction, updatingRoomId]);

  const roomTypeMap = useMemo(() => {
    const map = new Map<string, RoomType>();
    for (const rt of roomTypes) {
      map.set(rt.id, rt);
    }
    return map;
  }, [roomTypes]);

  // Statistics calculation
  const stats = useMemo(() => {
    let readyCount = 0;
    let dirtyCount = 0;
    let inProgressCount = 0;
    let occupiedCount = 0;
    let outOfServiceCount = 0;

    for (const room of rooms) {
      if (isRoomReadyForCheckIn(room)) {
        readyCount++;
      }
      if (room.housekeepingStatus === "DIRTY") {
        dirtyCount++;
      } else if (room.housekeepingStatus === "CLEANING_IN_PROGRESS") {
        inProgressCount++;
      }

      if (room.status === "OCCUPIED") {
        occupiedCount++;
      } else if (room.status === "OUT_OF_SERVICE" || !room.active) {
        outOfServiceCount++;
      }
    }

    return {
      total: rooms.length,
      readyCount,
      dirtyCount,
      inProgressCount,
      occupiedCount,
      outOfServiceCount,
    };
  }, [rooms]);

  // Filtered rooms
  const filteredRooms = useMemo(() => {
    return rooms.filter((room) => {
      if (
        housekeepingFilter !== "ALL" &&
        room.housekeepingStatus !== housekeepingFilter
      ) {
        return false;
      }
      if (roomStatusFilter !== "ALL" && room.status !== roomStatusFilter) {
        return false;
      }
      if (searchQuery.trim()) {
        const query = searchQuery.toLowerCase();
        const matchesNumber = room.roomNumber.toLowerCase().includes(query);
        const matchesName = (room.name || "").toLowerCase().includes(query);
        const matchesFloor = (room.floor || "").toLowerCase().includes(query);
        const typeName = roomTypeMap.get(room.roomTypeId)?.name || "";
        const matchesType = typeName.toLowerCase().includes(query);
        if (!matchesNumber && !matchesName && !matchesFloor && !matchesType) {
          return false;
        }
      }
      return true;
    });
  }, [rooms, housekeepingFilter, roomStatusFilter, searchQuery, roomTypeMap]);

  async function executeHousekeepingUpdate(
    room: PhysicalRoom,
    newStatus: HousekeepingStatus
  ) {
    if (updatingRoomId || uncertainOutcome) return;

    setUpdatingRoomId(room.id);
    setActionError("");

    try {
      const response = await fetch(
        `/api/rooms/${encodeURIComponent(room.id)}/housekeeping`,
        {
          method: "PATCH",
          headers: {
            "Content-Type": "application/json",
            "X-PMS-Client": "staff-web",
          },
          body: JSON.stringify({ housekeepingStatus: newStatus }),
        }
      );

      const raw = await readResponse(response);
      const updated = raw as PhysicalRoom;

      // Update room in local list
      setRooms((prev) =>
        prev.map((r) => (r.id === updated.id ? { ...r, ...updated } : r))
      );

      setPendingAction(null);
      setUncertainOutcome(false);
      setActionSuccess(
        `Room ${room.roomNumber} updated to ${newStatus.replace(/_/g, " ")}.`
      );
    } catch (err) {
      const message =
        err instanceof Error
          ? err.message
          : "Failed to update room housekeeping status.";
      setActionError(message);
      if (isUncertainError(message)) {
        setUncertainOutcome(true);
      }
    } finally {
      setUpdatingRoomId(null);
    }
  }

  function handleOpenConfirm(room: PhysicalRoom, targetStatus: HousekeepingStatus) {
    if (uncertainOutcome) return;
    setActionError("");
    setPendingAction({ room, targetStatus });
  }

  function handleCloseConfirm() {
    if (updatingRoomId) return;
    setPendingAction(null);
  }

  return (
    <main className="min-h-screen bg-[#f5f6f3] text-[#24382e]">
      {/* Top Header */}
      <header className="border-b border-[#e1e6df] bg-white px-5 py-3 sm:px-8 sm:py-3.5">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-4">
          <div>
            <div className="flex items-center gap-2 text-xs font-medium text-[#748077]">
              <Link href="/dashboard" className="hover:text-[#294638]">
                ← Back to overview
              </Link>
              <span>•</span>
              <Link
                href="/dashboard/reservations"
                className="hover:text-[#294638]"
              >
                Front desk reservations
              </Link>
            </div>
            <h1 className="mt-1 font-serif text-xl sm:text-2xl text-[#1c352a]">
              Housekeeping &amp; Room Operations
            </h1>
            <p className="mt-0.5 text-xs sm:text-sm text-[#748077]">
              Track room turnaround, inspect physical readiness, and execute verified housekeeping transitions. (Operational readiness signifies immediate physical check-in readiness; stay inventory requires reservation date checks.)
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <button
              type="button"
              onClick={() => {
                setLoading(true);
                void loadData();
              }}
              disabled={loading || updatingRoomId !== null}
              className="rounded-full border border-[#dce2d9] bg-white px-4 py-2 text-xs sm:text-sm font-medium text-[#435448] transition hover:bg-[#f5f6f3] disabled:opacity-50"
            >
              {loading ? "Refreshing…" : "↻ Refresh rooms"}
            </button>
            <StaffHeader />
          </div>
        </div>
      </header>

      <div className="mx-auto max-w-7xl space-y-6 px-5 py-6 sm:px-8 sm:py-8">
        {/* Global Feedback Banners */}
        {actionSuccess && (
          <div
            role="status"
            aria-live="polite"
            className="flex items-center justify-between rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800 shadow-sm"
          >
            <div className="flex items-center gap-2">
              <span className="font-semibold">✓</span>
              <span>{actionSuccess}</span>
            </div>
            <button
              type="button"
              onClick={() => setActionSuccess("")}
              className="text-xs font-semibold text-emerald-600 hover:text-emerald-900"
            >
              Dismiss
            </button>
          </div>
        )}

        {(actionError || uncertainOutcome) && (
          <div
            role="alert"
            className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800 shadow-sm"
          >
            <div className="flex items-start justify-between gap-3">
              <div className="space-y-1">
                <p className="font-semibold">{uncertainOutcome ? "Network Outcome Uncertain" : "Operation Error"}</p>
                {actionError && <p className="text-xs sm:text-sm text-rose-700">{actionError}</p>}
                {uncertainOutcome && (
                  <p className="text-xs font-medium text-amber-800 bg-amber-50 border border-amber-200 rounded p-2 mt-2">
                    ⚠ Network outcome uncertain. Repeated mutations are blocked until room state is refreshed.
                    Click &ldquo;Refresh rooms&rdquo; to reconcile authoritative state from the server.
                  </p>
                )}
              </div>
              <div className="flex items-center gap-2">
                {uncertainOutcome && (
                  <button
                    type="button"
                    onClick={() => {
                      setLoading(true);
                      void loadData();
                    }}
                    className="rounded-lg bg-amber-600 px-3 py-1 text-xs font-medium text-white hover:bg-amber-700"
                  >
                    Refresh rooms
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => {
                    setActionError("");
                  }}
                  className="text-xs font-semibold text-rose-600 hover:text-rose-900"
                >
                  Dismiss
                </button>
              </div>
            </div>
          </div>
        )}

        {error && (
          <div
            role="alert"
            className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800"
          >
            <p className="font-semibold">Failed to load room inventory</p>
            <p className="mt-1 text-xs">{error}</p>
            <button
              type="button"
              onClick={() => {
                setLoading(true);
                void loadData();
              }}
              className="mt-3 rounded-lg bg-rose-700 px-3 py-1.5 text-xs font-medium text-white hover:bg-rose-800"
            >
              Retry
            </button>
          </div>
        )}

        {/* Status KPI Overview */}
        <section aria-label="Housekeeping summary metrics" className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          <div className="rounded-xl border border-[#e1e6df] bg-white p-4 shadow-sm">
            <p className="text-xs font-medium text-[#748077]">Total Rooms</p>
            <p className="mt-1 font-serif text-2xl text-[#1c352a]">{stats.total}</p>
            <p className="mt-1 text-[11px] text-[#748077]">All physical units</p>
          </div>

          <div className="rounded-xl border border-emerald-200 bg-emerald-50/60 p-4 shadow-sm">
            <div className="flex items-center justify-between">
              <p className="text-xs font-medium text-emerald-800">Ready for Check-In</p>
              <span className="flex h-2 w-2 rounded-full bg-emerald-500" />
            </div>
            <p className="mt-1 font-serif text-2xl text-emerald-900">{stats.readyCount}</p>
            <p className="mt-1 text-[11px] text-emerald-700">Available + Clean</p>
          </div>

          <div className="rounded-xl border border-rose-200 bg-rose-50/60 p-4 shadow-sm">
            <div className="flex items-center justify-between">
              <p className="text-xs font-medium text-rose-800">Needs Cleaning</p>
              <span className="flex h-2 w-2 rounded-full bg-rose-500" />
            </div>
            <p className="mt-1 font-serif text-2xl text-rose-900">{stats.dirtyCount}</p>
            <p className="mt-1 text-[11px] text-rose-700">Dirty rooms</p>
          </div>

          <div className="rounded-xl border border-amber-200 bg-amber-50/60 p-4 shadow-sm">
            <div className="flex items-center justify-between">
              <p className="text-xs font-medium text-amber-800">In Progress</p>
              <span className="flex h-2 w-2 rounded-full bg-amber-500" />
            </div>
            <p className="mt-1 font-serif text-2xl text-amber-900">{stats.inProgressCount}</p>
            <p className="mt-1 text-[11px] text-amber-700">Being serviced</p>
          </div>

          <div className="rounded-xl border border-blue-200 bg-blue-50/60 p-4 shadow-sm">
            <div className="flex items-center justify-between">
              <p className="text-xs font-medium text-blue-800">Occupied</p>
              <span className="flex h-2 w-2 rounded-full bg-blue-500" />
            </div>
            <p className="mt-1 font-serif text-2xl text-blue-900">{stats.occupiedCount}</p>
            <p className="mt-1 text-[11px] text-blue-700">In-house guests</p>
          </div>

          <div className="rounded-xl border border-gray-200 bg-gray-50/80 p-4 shadow-sm">
            <div className="flex items-center justify-between">
              <p className="text-xs font-medium text-gray-700">Out of Service</p>
              <span className="flex h-2 w-2 rounded-full bg-gray-400" />
            </div>
            <p className="mt-1 font-serif text-2xl text-gray-800">{stats.outOfServiceCount}</p>
            <p className="mt-1 text-[11px] text-gray-600">Maintenance / Inactive</p>
          </div>
        </section>

        {/* Filter and Search Bar */}
        <div className="flex flex-col gap-3 rounded-2xl border border-[#e1e6df] bg-white p-4 shadow-sm md:flex-row md:items-center md:justify-between">
          <div className="flex flex-1 flex-wrap items-center gap-3">
            <div className="relative min-w-[200px] flex-1 sm:max-w-xs">
              <input
                type="text"
                placeholder="Search room #, name, floor…"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="w-full rounded-xl border border-[#dce2d9] bg-[#f9faf8] px-3.5 py-2 text-xs sm:text-sm text-[#24382e] outline-none transition focus:border-[#294638] focus:bg-white focus:ring-1 focus:ring-[#294638]"
              />
              {searchQuery && (
                <button
                  type="button"
                  onClick={() => setSearchQuery("")}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 text-xs text-[#748077] hover:text-[#24382e]"
                >
                  ✕
                </button>
              )}
            </div>

            {/* Housekeeping Status Selector */}
            <div className="flex items-center gap-1.5">
              <span className="text-xs font-medium text-[#748077]">Housekeeping:</span>
              <select
                value={housekeepingFilter}
                onChange={(e) => setHousekeepingFilter(e.target.value)}
                className="rounded-xl border border-[#dce2d9] bg-[#f9faf8] px-2.5 py-1.5 text-xs font-medium text-[#24382e] outline-none focus:border-[#294638] focus:bg-white"
              >
                <option value="ALL">All States</option>
                <option value="DIRTY">Dirty</option>
                <option value="CLEANING_IN_PROGRESS">Cleaning In Progress</option>
                <option value="CLEAN">Clean</option>
              </select>
            </div>

            {/* Room Status Selector */}
            <div className="flex items-center gap-1.5">
              <span className="text-xs font-medium text-[#748077]">Occupancy:</span>
              <select
                value={roomStatusFilter}
                onChange={(e) => setRoomStatusFilter(e.target.value)}
                className="rounded-xl border border-[#dce2d9] bg-[#f9faf8] px-2.5 py-1.5 text-xs font-medium text-[#24382e] outline-none focus:border-[#294638] focus:bg-white"
              >
                <option value="ALL">All Statuses</option>
                <option value="AVAILABLE">Available</option>
                <option value="OCCUPIED">Occupied</option>
                <option value="OUT_OF_SERVICE">Out of Service</option>
              </select>
            </div>
          </div>

          {(housekeepingFilter !== "ALL" || roomStatusFilter !== "ALL" || searchQuery) && (
            <button
              type="button"
              onClick={() => {
                setHousekeepingFilter("ALL");
                setRoomStatusFilter("ALL");
                setSearchQuery("");
              }}
              className="text-xs font-medium text-[#294638] underline underline-offset-2 hover:opacity-80"
            >
              Reset filters
            </button>
          )}
        </div>

        {/* Room Inventory List */}
        {loading ? (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {[1, 2, 3, 4, 5, 6].map((i) => (
              <div
                key={i}
                className="h-48 animate-pulse rounded-2xl border border-[#e1e6df] bg-white p-5"
              />
            ))}
          </div>
        ) : filteredRooms.length === 0 ? (
          <div className="rounded-2xl border border-[#e1e6df] bg-white p-12 text-center">
            <p className="font-serif text-lg text-[#1c352a]">No rooms match your filters</p>
            <p className="mt-1 text-xs text-[#748077]">
              Adjust your search keywords or clear status filters to view all physical rooms.
            </p>
            <button
              type="button"
              onClick={() => {
                setHousekeepingFilter("ALL");
                setRoomStatusFilter("ALL");
                setSearchQuery("");
              }}
              className="mt-4 rounded-full bg-[#294638] px-4 py-2 text-xs font-medium text-white hover:bg-[#20382c]"
            >
              Clear filters
            </button>
          </div>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {filteredRooms.map((room) => {
              const roomType = roomTypeMap.get(room.roomTypeId);
              const isReady = isRoomReadyForCheckIn(room);
              const allowedTransitions = getAllowedTransitions(room.housekeepingStatus);
              const isUpdating = updatingRoomId === room.id;

              return (
                <article
                  key={room.id}
                  className={`flex flex-col justify-between rounded-2xl border bg-white p-5 shadow-sm transition hover:shadow-md ${
                    isReady
                      ? "border-emerald-200/80"
                      : room.housekeepingStatus === "DIRTY"
                      ? "border-rose-200/80"
                      : "border-[#e1e6df]"
                  }`}
                >
                  {/* Top: Room Identification & Readiness Badge */}
                  <div>
                    <div className="flex items-start justify-between gap-2">
                      <div>
                        <div className="flex items-center gap-2">
                          <span className="font-serif text-xl font-bold text-[#1c352a]">
                            Room {room.roomNumber}
                          </span>
                          {!room.active && (
                            <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] font-semibold text-gray-600">
                              Inactive
                            </span>
                          )}
                        </div>
                        <p className="text-xs font-medium text-[#748077]">
                          {room.name || roomType?.name || "Standard Accommodation"}
                        </p>
                      </div>

                      {/* Explicit Readiness Indicator Badge */}
                      {isReady ? (
                        <span
                          title="Ready for Guest Check-In"
                          className="inline-flex items-center gap-1 rounded-full bg-emerald-100 px-2.5 py-1 text-[11px] font-semibold text-emerald-800"
                        >
                          <span aria-hidden="true">✓</span> Ready for check-in
                        </span>
                      ) : room.status === "AVAILABLE" && room.housekeepingStatus === "DIRTY" ? (
                        <span
                          title="Room is Available but Dirty — Requires Housekeeping"
                          className="inline-flex items-center gap-1 rounded-full bg-rose-100 px-2.5 py-1 text-[11px] font-semibold text-rose-800"
                        >
                          <span aria-hidden="true">⚠</span> Dirty — Not ready
                        </span>
                      ) : room.status === "AVAILABLE" && room.housekeepingStatus === "CLEANING_IN_PROGRESS" ? (
                        <span
                          title="Cleaning in Progress"
                          className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-2.5 py-1 text-[11px] font-semibold text-amber-800"
                        >
                          <span aria-hidden="true">⏳</span> Cleaning in progress
                        </span>
                      ) : room.status === "OCCUPIED" ? (
                        <span
                          title="Room is currently occupied by checked-in guest"
                          className="inline-flex items-center gap-1 rounded-full bg-blue-100 px-2.5 py-1 text-[11px] font-semibold text-blue-800"
                        >
                          <span aria-hidden="true">👤</span> In-house (Occupied)
                        </span>
                      ) : (
                        <span
                          title="Room is out of service"
                          className="inline-flex items-center gap-1 rounded-full bg-gray-100 px-2.5 py-1 text-[11px] font-semibold text-gray-700"
                        >
                          <span aria-hidden="true">⛔</span> Out of service
                        </span>
                      )}
                    </div>

                    {/* Meta info: Type, Floor, Capacity */}
                    <div className="mt-3.5 space-y-1 rounded-xl bg-[#f9faf8] p-3 text-xs text-[#526056]">
                      <div className="flex justify-between">
                        <span className="text-[#748077]">Category:</span>
                        <span className="font-medium text-[#24382e]">
                          {roomType?.name || room.roomTypeId.substring(0, 8)}
                        </span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-[#748077]">Floor:</span>
                        <span className="font-medium text-[#24382e]">
                          {room.floor || "Standard"}
                        </span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-[#748077]">Occupancy:</span>
                        <span className="font-medium text-[#24382e]">
                          {room.baseOccupancy} base / {room.maxOccupancy} max
                        </span>
                      </div>
                    </div>

                    {/* Operational Status Badges */}
                    <div className="mt-3.5 flex flex-wrap gap-2 text-xs">
                      {/* Physical Room Status */}
                      <span className="inline-flex items-center gap-1 rounded-md border border-[#dce2d9] bg-white px-2 py-0.5 text-[11px] font-medium text-[#435448]">
                        <span className="text-[#748077]">Status:</span>
                        <span
                          className={`font-semibold ${
                            room.status === "AVAILABLE"
                              ? "text-emerald-700"
                              : room.status === "OCCUPIED"
                              ? "text-blue-700"
                              : "text-amber-700"
                          }`}
                        >
                          {room.status}
                        </span>
                      </span>

                      {/* Housekeeping Status */}
                      <span
                        className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[11px] font-medium ${
                          room.housekeepingStatus === "CLEAN"
                            ? "bg-emerald-50 text-emerald-800 border border-emerald-200"
                            : room.housekeepingStatus === "CLEANING_IN_PROGRESS"
                            ? "bg-amber-50 text-amber-800 border border-amber-200"
                            : "bg-rose-50 text-rose-800 border border-rose-200"
                        }`}
                      >
                        <span className="opacity-75">Housekeeping:</span>
                        <span className="font-semibold">
                          {room.housekeepingStatus.replace(/_/g, " ")}
                        </span>
                      </span>
                    </div>
                  </div>

                  {/* Bottom: Housekeeping Transitions & Actions */}
                  <div className="mt-4 border-t border-[#f0f2ee] pt-3.5">
                    <p className="text-[11px] font-semibold text-[#748077] uppercase tracking-wider">
                      Housekeeping Actions
                    </p>
                    <div className="mt-2 flex flex-wrap gap-2">
                      {/* Allowed Transitions */}
                      {allowedTransitions.map((target) => {
                        const isClean = target === "CLEAN";
                        const isInProgress = target === "CLEANING_IN_PROGRESS";
                        const isDirty = target === "DIRTY";

                        return (
                          <button
                            key={target}
                            type="button"
                            disabled={isUpdating || uncertainOutcome}
                            onClick={() => handleOpenConfirm(room, target)}
                            aria-label={`Mark Room ${room.roomNumber} as ${target}`}
                            title={
                              uncertainOutcome
                                ? "Action blocked: room state must be refreshed to reconcile uncertain network outcome"
                                : undefined
                            }
                            className={`rounded-lg px-3 py-1.5 text-xs font-semibold transition disabled:opacity-50 ${
                              isClean
                                ? "bg-emerald-600 text-white hover:bg-emerald-700"
                                : isInProgress
                                ? "bg-amber-600 text-white hover:bg-amber-700"
                                : isDirty
                                ? "bg-rose-50 border border-rose-300 text-rose-800 hover:bg-rose-100"
                                : "bg-gray-100 text-gray-800 hover:bg-gray-200"
                            }`}
                          >
                            {isUpdating ? (
                              "Updating…"
                            ) : isInProgress ? (
                              "▶ Start Cleaning"
                            ) : isClean ? (
                              "✓ Mark Clean"
                            ) : (
                              "⚠ Mark Dirty"
                            )}
                          </button>
                        );
                      })}

                      {allowedTransitions.length === 0 && (
                        <p className="text-xs italic text-[#748077]">
                          No actions available for current state.
                        </p>
                      )}
                    </div>
                  </div>
                </article>
              );
            })}
          </div>
        )}
      </div>

      {/* Confirmation Modal */}
      {pendingAction && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="confirm-modal-title"
          aria-describedby="confirm-modal-description"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4 backdrop-blur-xs"
        >
          <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-xl">
            <h3
              id="confirm-modal-title"
              className="font-serif text-lg font-bold text-[#1c352a]"
            >
              Confirm Housekeeping Update
            </h3>

            <div id="confirm-modal-description" className="mt-3 space-y-3 text-sm text-[#435448]">
              <div className="rounded-xl bg-[#f9faf8] p-3 text-xs space-y-1">
                <div className="flex justify-between">
                  <span className="text-[#748077]">Room:</span>
                  <span className="font-semibold text-[#1c352a]">
                    Room {pendingAction.room.roomNumber}{" "}
                    {pendingAction.room.name ? `(${pendingAction.room.name})` : ""}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-[#748077]">Current Status:</span>
                  <span className="font-medium text-[#24382e]">
                    {pendingAction.room.housekeepingStatus.replace(/_/g, " ")}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-[#748077]">New Target:</span>
                  <span className="font-bold text-[#294638]">
                    {pendingAction.targetStatus.replace(/_/g, " ")}
                  </span>
                </div>
              </div>

              {pendingAction.targetStatus === "CLEAN" && (
                <p className="text-xs text-emerald-800 bg-emerald-50 border border-emerald-200 rounded-lg p-2.5">
                  ✓ Marking this room as <strong>CLEAN</strong> confirms that housekeeping and inspection are complete.
                  {pendingAction.room.status === "AVAILABLE" && (
                    <span> The room will become immediately ready for guest check-in at the front desk.</span>
                  )}
                </p>
              )}

              {pendingAction.targetStatus === "CLEANING_IN_PROGRESS" && (
                <p className="text-xs text-amber-800 bg-amber-50 border border-amber-200 rounded-lg p-2.5">
                  ⏳ Marking this room as <strong>CLEANING IN PROGRESS</strong> logs that housekeeping staff has begun servicing the room.
                </p>
              )}

              {pendingAction.targetStatus === "DIRTY" && (
                <p className="text-xs text-rose-800 bg-rose-50 border border-rose-200 rounded-lg p-2.5">
                  ⚠ Marking this room as <strong>DIRTY</strong> indicates the room requires housekeeping and cannot be checked in until cleaned.
                </p>
              )}

              {pendingAction.room.status === "OCCUPIED" &&
                (pendingAction.targetStatus === "DIRTY" ||
                  pendingAction.targetStatus === "CLEANING_IN_PROGRESS") && (
                  <p className="text-xs font-semibold text-rose-700 bg-rose-50 border border-rose-200 rounded-lg p-2">
                    Notice: This room is physically OCCUPIED. If an active checked-in reservation occupies this room, backend business validation will reject transitions to DIRTY or CLEANING IN PROGRESS. (Completed stayover cleaning can be marked CLEAN).
                  </p>
                )}
            </div>

            <div className="mt-6 flex justify-end gap-3">
              <button
                type="button"
                onClick={handleCloseConfirm}
                disabled={updatingRoomId !== null}
                className="rounded-xl border border-[#dce2d9] bg-white px-4 py-2 text-xs font-medium text-[#435448] hover:bg-[#f5f6f3] disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={updatingRoomId !== null || uncertainOutcome}
                onClick={() =>
                  void executeHousekeepingUpdate(
                    pendingAction.room,
                    pendingAction.targetStatus
                  )
                }
                className={`rounded-xl px-4 py-2 text-xs font-semibold text-white disabled:opacity-50 ${
                  pendingAction.targetStatus === "CLEAN"
                    ? "bg-emerald-600 hover:bg-emerald-700"
                    : pendingAction.targetStatus === "CLEANING_IN_PROGRESS"
                    ? "bg-amber-600 hover:bg-amber-700"
                    : "bg-rose-600 hover:bg-rose-700"
                }`}
              >
                {updatingRoomId ? "Updating…" : "Confirm Change"}
              </button>
            </div>
          </div>
        </div>
      )}
    </main>
  );
}
