"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { StaffHeader } from "./StaffHeader";
import {
  type PhysicalRoom,
  type Reservation,
  type Guest,
  type RoomType,
  type RoomMetrics,
  type ReservationMetrics,
  PROPERTY_TIMEZONE,
  getTodayInPropertyTimezone,
  formatDateTimeInPropertyTimezone,
  formatDateDisplay,
  computeRoomMetrics,
  computeReservationMetrics,
  filterExpectedArrivals,
  filterPendingDeparturesQueue,
  filterInHouseReservations,
  filterHousekeepingAttentionRooms,
  unwrapResponseList,
} from "@/lib/dashboard/metrics";

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

const quickActions = [
  {
    title: "Reservations",
    description: "Manage guest bookings, check-in arrivals, and confirm checkouts.",
    href: "/dashboard/reservations",
    symbol: "↗",
  },
  {
    title: "Room turnover",
    description: "Review physical room readiness, turn over dirty units, and track cleaning.",
    href: "/dashboard/rooms",
    symbol: "⌂",
  },
  {
    title: "Guest records",
    description: "Access guest profiles, contact numbers, and identification records.",
    href: "/dashboard/guests",
    symbol: "◎",
  },
];

type ActiveTab = "arrivals" | "checkouts" | "inhouse" | "housekeeping";

const TAB_ORDER: ActiveTab[] = ["arrivals", "checkouts", "inhouse", "housekeeping"];

export default function DashboardPage() {
  // Authoritative data states
  const [rooms, setRooms] = useState<PhysicalRoom[]>([]);
  const [reservations, setReservations] = useState<Reservation[]>([]);
  const [guests, setGuests] = useState<Guest[]>([]);
  const [roomTypes, setRoomTypes] = useState<RoomType[]>([]);

  // Loading and error states tracked independently for safe partial-failure handling
  const [loading, setLoading] = useState(true);
  const [roomsLoaded, setRoomsLoaded] = useState(false);
  const [roomsError, setRoomsError] = useState<string | null>(null);

  const [reservationsLoaded, setReservationsLoaded] = useState(false);
  const [reservationsError, setReservationsError] = useState<string | null>(null);

  const [lastRefreshedAt, setLastRefreshedAt] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<ActiveTab>("arrivals");

  // Monotonic sequence token to discard stale out-of-order async responses
  const latestRequestIdRef = useRef(0);

  // Today in Property Timezone (Asia/Kolkata)
  const today = useMemo(() => getTodayInPropertyTimezone(PROPERTY_TIMEZONE), []);

  const loadDashboardData = useCallback(async () => {
    const reqId = ++latestRequestIdRef.current;
    setLoading(true);

    const [roomsResult, roomTypesResult, reservationsResult, guestsResult] =
      await Promise.allSettled([
        fetch("/api/rooms", { cache: "no-store" }).then(readResponse),
        fetch("/api/room-types", { cache: "no-store" }).then(readResponse),
        fetch("/api/reservations", { cache: "no-store" }).then(readResponse),
        fetch("/api/guests", { cache: "no-store" }).then(readResponse),
      ]);

    // Check if this request was superseded by a newer refresh trigger
    if (reqId !== latestRequestIdRef.current) {
      return;
    }

    let coreSucceeded = false;

    // Process physical rooms
    if (roomsResult.status === "fulfilled") {
      try {
        const parsedRooms = unwrapResponseList<PhysicalRoom>(roomsResult.value);
        setRooms(parsedRooms);
        setRoomsError(null);
        setRoomsLoaded(true);
        coreSucceeded = true;
      } catch (err) {
        setRoomsError(err instanceof Error ? err.message : "Failed to parse rooms.");
      }
    } else {
      setRoomsError(
        roomsResult.reason instanceof Error
          ? roomsResult.reason.message
          : "Failed to load rooms."
      );
    }

    // Process room types (non-blocking metadata)
    if (roomTypesResult.status === "fulfilled") {
      try {
        setRoomTypes(unwrapResponseList<RoomType>(roomTypesResult.value));
      } catch {
        // Non-blocking metadata failure does not invalidate core metrics
      }
    }

    // Process reservations
    if (reservationsResult.status === "fulfilled") {
      try {
        const parsedReservations = unwrapResponseList<Reservation>(
          reservationsResult.value
        );
        setReservations(parsedReservations);
        setReservationsError(null);
        setReservationsLoaded(true);
        coreSucceeded = true;
      } catch (err) {
        setReservationsError(
          err instanceof Error ? err.message : "Failed to parse reservations."
        );
      }
    } else {
      setReservationsError(
        reservationsResult.reason instanceof Error
          ? reservationsResult.reason.message
          : "Failed to load reservations."
      );
    }

    // Process guests (non-blocking metadata)
    if (guestsResult.status === "fulfilled") {
      try {
        setGuests(unwrapResponseList<Guest>(guestsResult.value));
      } catch {
        // Non-blocking metadata failure does not invalidate core metrics
      }
    }

    // Only update timestamp if at least one core dataset succeeded
    if (coreSucceeded) {
      setLastRefreshedAt(new Date().toISOString());
    }

    setLoading(false);
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadDashboardData();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [loadDashboardData]);

  // Keyboard navigation for WAI-ARIA tablist
  const handleTabKeyDown = (e: React.KeyboardEvent, currentTab: ActiveTab) => {
    const currentIndex = TAB_ORDER.indexOf(currentTab);
    let nextIndex = -1;

    if (e.key === "ArrowRight") {
      nextIndex = (currentIndex + 1) % TAB_ORDER.length;
    } else if (e.key === "ArrowLeft") {
      nextIndex = (currentIndex - 1 + TAB_ORDER.length) % TAB_ORDER.length;
    } else if (e.key === "Home") {
      nextIndex = 0;
    } else if (e.key === "End") {
      nextIndex = TAB_ORDER.length - 1;
    }

    if (nextIndex >= 0) {
      e.preventDefault();
      const nextTab = TAB_ORDER[nextIndex];
      setActiveTab(nextTab);
      const nextTabEl = document.getElementById(`tab-${nextTab}`);
      nextTabEl?.focus();
    }
  };

  // Lookup maps for fast guest and room-type rendering
  const guestMap = useMemo(() => {
    const map = new Map<string, Guest>();
    for (const g of guests) {
      map.set(g.id, g);
    }
    return map;
  }, [guests]);

  const roomTypeMap = useMemo(() => {
    const map = new Map<string, RoomType>();
    for (const rt of roomTypes) {
      map.set(rt.id, rt);
    }
    return map;
  }, [roomTypes]);

  // Derived metrics using pure domain helpers
  const roomMetrics: RoomMetrics | null = useMemo(() => {
    if (roomsError || !roomsLoaded) return null;
    return computeRoomMetrics(rooms);
  }, [rooms, roomsLoaded, roomsError]);

  const reservationMetrics: ReservationMetrics | null = useMemo(() => {
    if (reservationsError || !reservationsLoaded) return null;
    return computeReservationMetrics(reservations, today);
  }, [reservations, reservationsLoaded, reservationsError, today]);

  // Operational Queues
  const expectedArrivals = useMemo(() => {
    if (!reservationsLoaded || reservationsError) return [];
    return filterExpectedArrivals(reservations, today);
  }, [reservations, reservationsLoaded, reservationsError, today]);

  const pendingDepartures = useMemo(() => {
    if (!reservationsLoaded || reservationsError) return [];
    return filterPendingDeparturesQueue(reservations, today);
  }, [reservations, reservationsLoaded, reservationsError, today]);

  const inHouseReservations = useMemo(() => {
    if (!reservationsLoaded || reservationsError) return [];
    return filterInHouseReservations(reservations);
  }, [reservations, reservationsLoaded, reservationsError]);

  const housekeepingAttentionRooms = useMemo(() => {
    if (!roomsLoaded || roomsError) return [];
    return filterHousekeepingAttentionRooms(rooms);
  }, [rooms, roomsLoaded, roomsError]);

  return (
    <main className="min-h-screen bg-[#f5f6f3] text-[#24382e]">
      <div className="flex min-h-screen flex-col md:flex-row">
        {/* Navigation Sidebar */}
        <aside className="flex w-full shrink-0 flex-col border-b border-[#e1e6df] bg-white md:min-h-screen md:w-64 md:border-b-0 md:border-r">
          <Link
            href="/dashboard"
            className="flex items-center gap-3 px-5 py-5 sm:px-7 md:px-6"
          >
            <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-[#294638] font-serif text-xl text-white">
              H.
            </span>
            <span>
              <span className="block font-serif text-lg">Homestay PMS</span>
              <span className="block text-xs text-[#7a877e]">
                PROPERTY MANAGEMENT
              </span>
            </span>
          </Link>

          <nav className="flex gap-2 overflow-x-auto px-4 pb-4 md:flex-1 md:flex-col md:px-3 md:pt-7">
            <Link
              href="/dashboard"
              className="shrink-0 rounded-lg bg-[#eaf0e9] px-4 py-3 text-sm font-semibold text-[#294638] md:w-full"
            >
              ▦ &nbsp; Overview
            </Link>
            <Link
              href="/dashboard/reservations"
              className="shrink-0 rounded-lg px-4 py-3 text-sm text-[#59675e] hover:bg-[#f3f5f1] md:w-full"
            >
              ▤ &nbsp; Reservations
            </Link>
            <Link
              href="/dashboard/rooms"
              className="shrink-0 rounded-lg px-4 py-3 text-sm text-[#59675e] hover:bg-[#f3f5f1] md:w-full"
            >
              ⌂ &nbsp; Rooms &amp; Housekeeping
            </Link>
            <Link
              href="/dashboard/guests"
              className="shrink-0 rounded-lg px-4 py-3 text-sm text-[#59675e] hover:bg-[#f3f5f1] md:w-full"
            >
              ◎ &nbsp; Guests
            </Link>
            <Link
              href="/dashboard/settings"
              className="shrink-0 rounded-lg px-4 py-3 text-sm text-[#59675e] hover:bg-[#f3f5f1] md:mt-auto md:w-full"
            >
              ⚙ &nbsp; Settings
            </Link>
          </nav>

          <div className="hidden border-t border-[#e8ebe5] p-5 md:block">
            <p className="text-xs text-[#7a877e]">STAFF WORKSPACE</p>
            <p className="mt-1 text-sm font-medium">Property Operations</p>
          </div>
        </aside>

        {/* Main Dashboard Section */}
        <section className="min-w-0 flex-1">
          {/* Header Bar */}
          <header className="flex flex-wrap items-center justify-between gap-4 border-b border-[#e1e6df] bg-white px-5 py-4 sm:px-8">
            <div>
              <div className="flex items-center gap-2">
                <p className="text-xs font-semibold tracking-[0.18em] text-[#8a7954]">
                  PROPERTY OPERATIONS
                </p>
                <span className="text-xs text-[#748077]">•</span>
                <span className="text-xs font-medium text-[#748077]">
                  Timezone: {PROPERTY_TIMEZONE} (IST)
                </span>
              </div>
              <h1 className="mt-1 font-serif text-2xl sm:text-3xl text-[#1c352a]">
                Live Operational Dashboard
              </h1>
            </div>

            <div className="flex flex-wrap items-center gap-3">
              <button
                type="button"
                onClick={() => void loadDashboardData()}
                disabled={loading}
                className="rounded-full border border-[#dce2d9] bg-white px-4 py-2 text-xs sm:text-sm font-medium text-[#435448] transition hover:bg-[#f5f6f3] disabled:opacity-50"
              >
                {loading ? "Refreshing…" : "↻ Refresh Dashboard"}
              </button>
              <StaffHeader />
            </div>
          </header>

          <div className="mx-auto max-w-7xl space-y-7 px-5 py-6 sm:px-8 sm:py-8">
            {/* Header Status & Last Refreshed Notice */}
            <div className="flex flex-wrap items-center justify-between gap-3 text-xs text-[#748077]">
              <p>
                Operational date: <strong className="text-[#24382e]">{today}</strong> (Asia/Kolkata)
              </p>
              {lastRefreshedAt && (
                <p>
                  Last updated:{" "}
                  <span className="font-medium text-[#24382e]">
                    {formatDateTimeInPropertyTimezone(lastRefreshedAt)}
                  </span>
                </p>
              )}
            </div>

            {/* Partial Failure Feedback Banners */}
            {roomsError && (
              <div
                role="alert"
                className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-xs sm:text-sm text-rose-800"
              >
                <div className="flex items-start justify-between gap-2">
                  <div>
                    <strong className="font-semibold">Room Inventory Unavailable:</strong>{" "}
                    {roomsError}. Room counts are omitted to prevent misleading statistics.
                  </div>
                  <button
                    type="button"
                    onClick={() => void loadDashboardData()}
                    className="font-medium text-rose-700 underline hover:text-rose-900"
                  >
                    Retry
                  </button>
                </div>
              </div>
            )}

            {reservationsError && (
              <div
                role="alert"
                className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-xs sm:text-sm text-rose-800"
              >
                <div className="flex items-start justify-between gap-2">
                  <div>
                    <strong className="font-semibold">Reservations Unavailable:</strong>{" "}
                    {reservationsError}. Arrival and checkout queues are omitted to prevent misleading statistics.
                  </div>
                  <button
                    type="button"
                    onClick={() => void loadDashboardData()}
                    className="font-medium text-rose-700 underline hover:text-rose-900"
                  >
                    Retry
                  </button>
                </div>
              </div>
            )}

            {/* Row 1: Front Desk Activity KPI Cards */}
            <section aria-label="Front desk operational metrics">
              <div className="mb-3">
                <h2 className="font-serif text-lg font-bold text-[#1c352a]">
                  Today&apos;s Front Desk Flow
                </h2>
                <p className="text-xs text-[#748077]">
                  Real-time arrival, departure, and in-house guest counts for {today}.
                </p>
              </div>

              <div className="grid grid-cols-2 gap-3 sm:grid-cols-2 lg:grid-cols-4">
                {/* Expected Arrivals Today */}
                <article className="rounded-2xl border border-blue-200 bg-blue-50/50 p-4 sm:p-5 shadow-xs">
                  <div className="flex items-center justify-between">
                    <p className="text-xs font-semibold text-blue-900">Expected Arrivals</p>
                    <span className="flex h-2 w-2 rounded-full bg-blue-500" />
                  </div>
                  <p className="mt-3 font-serif text-3xl font-bold text-blue-950">
                    {reservationsError
                      ? "—"
                      : !reservationsLoaded
                      ? "…"
                      : String(reservationMetrics?.arrivalsExpected ?? 0).padStart(2, "0")}
                  </p>
                  <p className="mt-2 text-[11px] text-blue-800">
                    {reservationsError
                      ? "Data unavailable"
                      : `${reservationMetrics?.arrivalsCheckedIn ?? 0} already checked in today`}
                  </p>
                </article>

                {/* In-House Guests */}
                <article className="rounded-2xl border border-emerald-200 bg-emerald-50/50 p-4 sm:p-5 shadow-xs">
                  <div className="flex items-center justify-between">
                    <p className="text-xs font-semibold text-emerald-900">In-House Guests</p>
                    <span className="flex h-2 w-2 rounded-full bg-emerald-500" />
                  </div>
                  <p className="mt-3 font-serif text-3xl font-bold text-emerald-950">
                    {reservationsError
                      ? "—"
                      : !reservationsLoaded
                      ? "…"
                      : String(reservationMetrics?.checkedInTotal ?? 0).padStart(2, "0")}
                  </p>
                  <p className="mt-2 text-[11px] text-emerald-800">
                    {reservationsError
                      ? "Data unavailable"
                      : "Active checked-in reservations"}
                  </p>
                </article>

                {/* Checkouts Due / Overdue */}
                <article className="rounded-2xl border border-amber-200 bg-amber-50/50 p-4 sm:p-5 shadow-xs">
                  <div className="flex items-center justify-between">
                    <p className="text-xs font-semibold text-amber-900">Checkouts Due / Overdue</p>
                    <span className="flex h-2 w-2 rounded-full bg-amber-500" />
                  </div>
                  <p className="mt-3 font-serif text-3xl font-bold text-amber-950">
                    {reservationsError
                      ? "—"
                      : !reservationsLoaded
                      ? "…"
                      : String(reservationMetrics?.checkoutsDueTotal ?? 0).padStart(2, "0")}
                  </p>
                  <p className="mt-2 text-[11px] text-amber-800">
                    {reservationsError
                      ? "Data unavailable"
                      : `${reservationMetrics?.departuresTodayTotal ?? 0} departure(s) scheduled today`}
                  </p>
                </article>

                {/* Total Property Reservations */}
                <article className="rounded-2xl border border-[#e4e8e0] bg-white p-4 sm:p-5 shadow-xs">
                  <p className="text-xs font-medium text-[#748077]">Total Bookings</p>
                  <p className="mt-3 font-serif text-3xl font-bold text-[#1c352a]">
                    {reservationsError
                      ? "—"
                      : !reservationsLoaded
                      ? "…"
                      : String(reservationMetrics?.totalReservations ?? 0).padStart(2, "0")}
                  </p>
                  <p className="mt-2 text-[11px] text-[#748077]">
                    {reservationsError ? "Data unavailable" : "All authorized records"}
                  </p>
                </article>
              </div>
            </section>

            {/* Row 2: Physical Room & Housekeeping KPI Cards */}
            <section aria-label="Room inventory and housekeeping metrics">
              <div className="mb-3">
                <h2 className="font-serif text-lg font-bold text-[#1c352a]">
                  Room Inventory &amp; Housekeeping Turnover
                </h2>
                <p className="text-xs text-[#748077]">
                  Physical room readiness, turnaround states, and maintenance occupancy.
                </p>
              </div>

              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
                {/* Total Rooms */}
                <article className="rounded-xl border border-[#e1e6df] bg-white p-4 shadow-xs">
                  <p className="text-xs font-medium text-[#748077]">Total Rooms</p>
                  <p className="mt-2 font-serif text-2xl text-[#1c352a]">
                    {roomsError ? "—" : !roomsLoaded ? "…" : roomMetrics?.total ?? 0}
                  </p>
                  <p className="mt-1 text-[11px] text-[#748077]">Physical units</p>
                </article>

                {/* Ready for Check-In */}
                <article className="rounded-xl border border-emerald-200 bg-emerald-50/60 p-4 shadow-xs">
                  <div className="flex items-center justify-between">
                    <p className="text-xs font-semibold text-emerald-800">Ready</p>
                    <span className="flex h-2 w-2 rounded-full bg-emerald-500" />
                  </div>
                  <p className="mt-2 font-serif text-2xl text-emerald-950 font-bold">
                    {roomsError ? "—" : !roomsLoaded ? "…" : roomMetrics?.ready ?? 0}
                  </p>
                  <p className="mt-1 text-[11px] text-emerald-700">Available + Clean</p>
                </article>

                {/* Occupied */}
                <article className="rounded-xl border border-blue-200 bg-blue-50/60 p-4 shadow-xs">
                  <div className="flex items-center justify-between">
                    <p className="text-xs font-semibold text-blue-800">Occupied</p>
                    <span className="flex h-2 w-2 rounded-full bg-blue-500" />
                  </div>
                  <p className="mt-2 font-serif text-2xl text-blue-950 font-bold">
                    {roomsError ? "—" : !roomsLoaded ? "…" : roomMetrics?.occupied ?? 0}
                  </p>
                  <p className="mt-1 text-[11px] text-blue-700">In-house occupied</p>
                </article>

                {/* Needs Cleaning */}
                <article className="rounded-xl border border-rose-200 bg-rose-50/60 p-4 shadow-xs">
                  <div className="flex items-center justify-between">
                    <p className="text-xs font-semibold text-rose-800">Dirty</p>
                    <span className="flex h-2 w-2 rounded-full bg-rose-500" />
                  </div>
                  <p className="mt-2 font-serif text-2xl text-rose-950 font-bold">
                    {roomsError ? "—" : !roomsLoaded ? "…" : roomMetrics?.dirty ?? 0}
                  </p>
                  <p className="mt-1 text-[11px] text-rose-700">Requires turnover</p>
                </article>

                {/* Cleaning in Progress */}
                <article className="rounded-xl border border-amber-200 bg-amber-50/60 p-4 shadow-xs">
                  <div className="flex items-center justify-between">
                    <p className="text-xs font-semibold text-amber-800">In Progress</p>
                    <span className="flex h-2 w-2 rounded-full bg-amber-500" />
                  </div>
                  <p className="mt-2 font-serif text-2xl text-amber-950 font-bold">
                    {roomsError ? "—" : !roomsLoaded ? "…" : roomMetrics?.cleaningInProgress ?? 0}
                  </p>
                  <p className="mt-1 text-[11px] text-amber-700">Housekeeping active</p>
                </article>

                {/* Out of Service */}
                <article className="rounded-xl border border-gray-200 bg-gray-50/80 p-4 shadow-xs">
                  <div className="flex items-center justify-between">
                    <p className="text-xs font-medium text-gray-700">Out of Service</p>
                    <span className="flex h-2 w-2 rounded-full bg-gray-400" />
                  </div>
                  <p className="mt-2 font-serif text-2xl text-gray-800">
                    {roomsError ? "—" : !roomsLoaded ? "…" : roomMetrics?.outOfService ?? 0}
                  </p>
                  <p className="mt-1 text-[11px] text-gray-600">Offline / Inactive</p>
                </article>
              </div>
            </section>

            {/* Actionable Operational Queues Section */}
            <section className="rounded-2xl border border-[#e1e6df] bg-white p-5 sm:p-7 shadow-xs">
              <div className="flex flex-col gap-4 border-b border-[#f0f2ee] pb-4 sm:flex-row sm:items-center sm:justify-between">
                <div>
                  <h2 className="font-serif text-xl sm:text-2xl text-[#1c352a]">
                    Actionable Operational Queues
                  </h2>
                  <p className="mt-1 text-xs sm:text-sm text-[#748077]">
                    Manage priority check-ins, impending departures, and housekeeping turnover.
                  </p>
                </div>

                {/* Queue Tab Navigation (WAI-ARIA Compliant) */}
                <div
                  role="tablist"
                  aria-label="Operational queues"
                  className="flex flex-wrap gap-1.5 rounded-xl bg-[#f5f6f3] p-1 text-xs font-semibold"
                >
                  <button
                    type="button"
                    role="tab"
                    id="tab-arrivals"
                    aria-selected={activeTab === "arrivals"}
                    aria-controls="panel-arrivals"
                    tabIndex={activeTab === "arrivals" ? 0 : -1}
                    onClick={() => setActiveTab("arrivals")}
                    onKeyDown={(e) => handleTabKeyDown(e, "arrivals")}
                    className={`rounded-lg px-3 py-1.5 transition ${
                      activeTab === "arrivals"
                        ? "bg-white text-[#1c352a] shadow-xs"
                        : "text-[#748077] hover:text-[#1c352a]"
                    }`}
                  >
                    Arrivals Today ({expectedArrivals.length})
                  </button>
                  <button
                    type="button"
                    role="tab"
                    id="tab-checkouts"
                    aria-selected={activeTab === "checkouts"}
                    aria-controls="panel-checkouts"
                    tabIndex={activeTab === "checkouts" ? 0 : -1}
                    onClick={() => setActiveTab("checkouts")}
                    onKeyDown={(e) => handleTabKeyDown(e, "checkouts")}
                    className={`rounded-lg px-3 py-1.5 transition ${
                      activeTab === "checkouts"
                        ? "bg-white text-[#1c352a] shadow-xs"
                        : "text-[#748077] hover:text-[#1c352a]"
                    }`}
                  >
                    Departures ({pendingDepartures.length})
                  </button>
                  <button
                    type="button"
                    role="tab"
                    id="tab-inhouse"
                    aria-selected={activeTab === "inhouse"}
                    aria-controls="panel-inhouse"
                    tabIndex={activeTab === "inhouse" ? 0 : -1}
                    onClick={() => setActiveTab("inhouse")}
                    onKeyDown={(e) => handleTabKeyDown(e, "inhouse")}
                    className={`rounded-lg px-3 py-1.5 transition ${
                      activeTab === "inhouse"
                        ? "bg-white text-[#1c352a] shadow-xs"
                        : "text-[#748077] hover:text-[#1c352a]"
                    }`}
                  >
                    In-House ({inHouseReservations.length})
                  </button>
                  <button
                    type="button"
                    role="tab"
                    id="tab-housekeeping"
                    aria-selected={activeTab === "housekeeping"}
                    aria-controls="panel-housekeeping"
                    tabIndex={activeTab === "housekeeping" ? 0 : -1}
                    onClick={() => setActiveTab("housekeeping")}
                    onKeyDown={(e) => handleTabKeyDown(e, "housekeeping")}
                    className={`rounded-lg px-3 py-1.5 transition ${
                      activeTab === "housekeeping"
                        ? "bg-white text-[#1c352a] shadow-xs"
                        : "text-[#748077] hover:text-[#1c352a]"
                    }`}
                  >
                    Housekeeping ({housekeepingAttentionRooms.length})
                  </button>
                </div>
              </div>

              {/* Tab Content Display */}
              <div className="mt-5">
                {/* 1. Expected Arrivals Today */}
                {activeTab === "arrivals" && (
                  <div
                    role="tabpanel"
                    id="panel-arrivals"
                    aria-labelledby="tab-arrivals"
                    tabIndex={0}
                    className="focus:outline-none"
                  >
                    {expectedArrivals.length === 0 ? (
                      <div className="rounded-xl border border-dashed border-[#dce2d9] p-8 text-center">
                        <p className="font-serif text-base text-[#1c352a]">
                          No pending arrivals remaining for today
                        </p>
                        <p className="mt-1 text-xs text-[#748077]">
                          All scheduled arrivals for {today} are either checked in or no new arrivals are scheduled.
                        </p>
                        <Link
                          href="/dashboard/reservations"
                          className="mt-3 inline-block rounded-full bg-[#294638] px-4 py-1.5 text-xs font-semibold text-white hover:bg-[#20382c]"
                        >
                          View all bookings →
                        </Link>
                      </div>
                    ) : (
                      <div className="space-y-3">
                        <div className="overflow-x-auto">
                          <table className="w-full text-left text-xs text-[#24382e]">
                            <thead className="border-b border-[#e1e6df] bg-[#f9faf8] text-[11px] font-semibold text-[#748077] uppercase">
                              <tr>
                                <th scope="col" className="px-4 py-2.5">Guest</th>
                                <th scope="col" className="px-4 py-2.5">Check-In</th>
                                <th scope="col" className="px-4 py-2.5">Check-Out</th>
                                <th scope="col" className="px-4 py-2.5">Party</th>
                                <th scope="col" className="px-4 py-2.5">Status</th>
                                <th scope="col" className="px-4 py-2.5 text-right">Action</th>
                              </tr>
                            </thead>
                            <tbody className="divide-y divide-[#f0f2ee]">
                              {expectedArrivals.map((res) => {
                                const guest = guestMap.get(res.primaryGuestId);
                                return (
                                  <tr key={res.id} className="hover:bg-[#f9faf8]/60">
                                    <td className="px-4 py-3 font-semibold text-[#1c352a]">
                                       {guest?.name || `Guest (${res.primaryGuestId.substring(0, 8)})`}
                                      {guest?.phone && (
                                        <span className="block text-[11px] font-normal text-[#748077]">
                                          {guest.phone}
                                        </span>
                                      )}
                                    </td>
                                    <td className="px-4 py-3">{formatDateDisplay(res.checkInDate)}</td>
                                    <td className="px-4 py-3">{formatDateDisplay(res.checkOutDate)}</td>
                                    <td className="px-4 py-3">
                                      {res.adults ?? 1} Adults
                                      {Boolean(res.children) && `, ${res.children} Ch`}
                                    </td>
                                    <td className="px-4 py-3">
                                      <span className="rounded-full bg-blue-50 px-2.5 py-0.5 text-[10px] font-bold text-blue-800">
                                        {res.status}
                                      </span>
                                    </td>
                                    <td className="px-4 py-3 text-right">
                                      <Link
                                        href="/dashboard/reservations"
                                        className="rounded-lg bg-[#294638] px-3 py-1.5 text-xs font-semibold text-white hover:bg-[#20382c]"
                                      >
                                        Check In →
                                      </Link>
                                    </td>
                                  </tr>
                                );
                              })}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    )}
                  </div>
                )}

                {/* 2. Departures / Checkouts Due */}
                {activeTab === "checkouts" && (
                  <div
                    role="tabpanel"
                    id="panel-checkouts"
                    aria-labelledby="tab-checkouts"
                    tabIndex={0}
                    className="focus:outline-none"
                  >
                    {pendingDepartures.length === 0 ? (
                      <div className="rounded-xl border border-dashed border-[#dce2d9] p-8 text-center">
                        <p className="font-serif text-base text-[#1c352a]">
                          No pending departures or checkouts due
                        </p>
                        <p className="mt-1 text-xs text-[#748077]">
                          All in-house guests are either on schedule for future dates or have already completed checkout.
                        </p>
                        <Link
                          href="/dashboard/reservations"
                          className="mt-3 inline-block rounded-full bg-[#294638] px-4 py-1.5 text-xs font-semibold text-white hover:bg-[#20382c]"
                        >
                          View reservations →
                        </Link>
                      </div>
                    ) : (
                      <div className="space-y-3">
                        <div className="overflow-x-auto">
                          <table className="w-full text-left text-xs text-[#24382e]">
                            <thead className="border-b border-[#e1e6df] bg-[#f9faf8] text-[11px] font-semibold text-[#748077] uppercase">
                              <tr>
                                <th scope="col" className="px-4 py-2.5">Guest</th>
                                <th scope="col" className="px-4 py-2.5">Check-Out Date</th>
                                <th scope="col" className="px-4 py-2.5">Checkout Deadline (IST)</th>
                                <th scope="col" className="px-4 py-2.5">Departure State</th>
                                <th scope="col" className="px-4 py-2.5 text-right">Action</th>
                              </tr>
                            </thead>
                            <tbody className="divide-y divide-[#f0f2ee]">
                              {pendingDepartures.map((res) => {
                                const guest = guestMap.get(res.primaryGuestId);
                                const isPastDateOverdue = Boolean(res.checkOutDate && res.checkOutDate < today);
                                const isDeadlinePassed = Boolean(res.checkoutDue);
                                return (
                                  <tr key={res.id} className="hover:bg-[#f9faf8]/60">
                                    <td className="px-4 py-3 font-semibold text-[#1c352a]">
                                      {guest?.name || `Guest (${res.primaryGuestId.substring(0, 8)})`}
                                      {guest?.phone && (
                                        <span className="block text-[11px] font-normal text-[#748077]">
                                          {guest.phone}
                                        </span>
                                      )}
                                    </td>
                                    <td className="px-4 py-3">{formatDateDisplay(res.checkOutDate)}</td>
                                    <td className="px-4 py-3 font-medium">
                                      {formatDateTimeInPropertyTimezone(res.checkoutDueAt)}
                                    </td>
                                    <td className="px-4 py-3">
                                      {isDeadlinePassed ? (
                                        <span className="rounded-full bg-amber-100 px-2.5 py-0.5 text-[10px] font-bold text-amber-900 border border-amber-300">
                                          Due (12 PM Deadline Passed)
                                        </span>
                                      ) : isPastDateOverdue ? (
                                        <span className="rounded-full bg-rose-100 px-2.5 py-0.5 text-[10px] font-bold text-rose-900 border border-rose-300">
                                          Overdue (Past Date)
                                        </span>
                                      ) : (
                                        <span className="rounded-full bg-sky-50 px-2.5 py-0.5 text-[10px] font-semibold text-sky-800 border border-sky-200">
                                          Scheduled Departure (Before 12 PM)
                                        </span>
                                      )}
                                    </td>
                                    <td className="px-4 py-3 text-right">
                                      <Link
                                        href="/dashboard/reservations"
                                        className={`rounded-lg px-3 py-1.5 text-xs font-semibold text-white transition ${
                                          isDeadlinePassed || isPastDateOverdue
                                            ? "bg-amber-700 hover:bg-amber-800"
                                            : "bg-[#294638] hover:bg-[#20382c]"
                                        }`}
                                      >
                                        Check Out →
                                      </Link>
                                    </td>
                                  </tr>
                                );
                              })}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    )}
                  </div>
                )}

                {/* 3. In-House Guests */}
                {activeTab === "inhouse" && (
                  <div
                    role="tabpanel"
                    id="panel-inhouse"
                    aria-labelledby="tab-inhouse"
                    tabIndex={0}
                    className="focus:outline-none"
                  >
                    {inHouseReservations.length === 0 ? (
                      <div className="rounded-xl border border-dashed border-[#dce2d9] p-8 text-center">
                        <p className="font-serif text-base text-[#1c352a]">
                          No in-house guests currently checked in
                        </p>
                        <p className="mt-1 text-xs text-[#748077]">
                          Check in arriving guests from the Front Desk queue to populate in-house records.
                        </p>
                        <Link
                          href="/dashboard/reservations"
                          className="mt-3 inline-block rounded-full bg-[#294638] px-4 py-1.5 text-xs font-semibold text-white hover:bg-[#20382c]"
                        >
                          Front desk check-in →
                        </Link>
                      </div>
                    ) : (
                      <div className="space-y-3">
                        <div className="overflow-x-auto">
                          <table className="w-full text-left text-xs text-[#24382e]">
                            <thead className="border-b border-[#e1e6df] bg-[#f9faf8] text-[11px] font-semibold text-[#748077] uppercase">
                              <tr>
                                <th scope="col" className="px-4 py-2.5">Guest</th>
                                <th scope="col" className="px-4 py-2.5">Stay Dates</th>
                                <th scope="col" className="px-4 py-2.5">Party Size</th>
                                <th scope="col" className="px-4 py-2.5">Status</th>
                                <th scope="col" className="px-4 py-2.5 text-right">Action</th>
                              </tr>
                            </thead>
                            <tbody className="divide-y divide-[#f0f2ee]">
                              {inHouseReservations.map((res) => {
                                const guest = guestMap.get(res.primaryGuestId);
                                return (
                                  <tr key={res.id} className="hover:bg-[#f9faf8]/60">
                                    <td className="px-4 py-3 font-semibold text-[#1c352a]">
                                      {guest?.name || `Guest (${res.primaryGuestId.substring(0, 8)})`}
                                    </td>
                                    <td className="px-4 py-3">
                                      {formatDateDisplay(res.checkInDate)} – {formatDateDisplay(res.checkOutDate)}
                                    </td>
                                    <td className="px-4 py-3">
                                      {res.adults ?? 1} Adults{Boolean(res.children) && `, ${res.children} Children`}
                                    </td>
                                    <td className="px-4 py-3">
                                      <span className="rounded-full bg-emerald-100 px-2.5 py-0.5 text-[10px] font-bold text-emerald-900">
                                        CHECKED_IN
                                      </span>
                                    </td>
                                    <td className="px-4 py-3 text-right">
                                      <Link
                                        href="/dashboard/reservations"
                                        className="text-xs font-semibold text-[#294638] hover:underline"
                                      >
                                        View Details →
                                      </Link>
                                    </td>
                                  </tr>
                                );
                              })}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    )}
                  </div>
                )}

                {/* 4. Housekeeping Attention Queue */}
                {activeTab === "housekeeping" && (
                  <div
                    role="tabpanel"
                    id="panel-housekeeping"
                    aria-labelledby="tab-housekeeping"
                    tabIndex={0}
                    className="focus:outline-none"
                  >
                    {housekeepingAttentionRooms.length === 0 ? (
                      <div className="rounded-xl border border-dashed border-[#dce2d9] p-8 text-center">
                        <p className="font-serif text-base text-[#1c352a]">
                          All rooms clean and inspected
                        </p>
                        <p className="mt-1 text-xs text-[#748077]">
                          Zero rooms currently require housekeeping turnover or cleaning in progress.
                        </p>
                        <Link
                          href="/dashboard/rooms"
                          className="mt-3 inline-block rounded-full bg-[#294638] px-4 py-1.5 text-xs font-semibold text-white hover:bg-[#20382c]"
                        >
                          View room grid →
                        </Link>
                      </div>
                    ) : (
                      <div className="space-y-3">
                        <div className="overflow-x-auto">
                          <table className="w-full text-left text-xs text-[#24382e]">
                            <thead className="border-b border-[#e1e6df] bg-[#f9faf8] text-[11px] font-semibold text-[#748077] uppercase">
                              <tr>
                                <th scope="col" className="px-4 py-2.5">Room</th>
                                <th scope="col" className="px-4 py-2.5">Category</th>
                                <th scope="col" className="px-4 py-2.5">Floor</th>
                                <th scope="col" className="px-4 py-2.5">Occupancy Status</th>
                                <th scope="col" className="px-4 py-2.5">Housekeeping</th>
                                <th scope="col" className="px-4 py-2.5 text-right">Action</th>
                              </tr>
                            </thead>
                            <tbody className="divide-y divide-[#f0f2ee]">
                              {housekeepingAttentionRooms.map((room) => {
                                const roomType = roomTypeMap.get(room.roomTypeId);
                                return (
                                  <tr key={room.id} className="hover:bg-[#f9faf8]/60">
                                    <td className="px-4 py-3 font-semibold text-[#1c352a]">
                                      Room {room.roomNumber}
                                      {room.name && (
                                        <span className="block text-[11px] font-normal text-[#748077]">
                                          {room.name}
                                        </span>
                                      )}
                                    </td>
                                    <td className="px-4 py-3">{roomType?.name || "Standard Room"}</td>
                                    <td className="px-4 py-3">{room.floor || "—"}</td>
                                    <td className="px-4 py-3">
                                      <span
                                        className={`rounded-md px-2 py-0.5 text-[10px] font-semibold ${
                                          room.status === "AVAILABLE"
                                            ? "bg-emerald-50 text-emerald-800"
                                            : room.status === "OCCUPIED"
                                            ? "bg-blue-50 text-blue-800"
                                            : "bg-gray-100 text-gray-700"
                                        }`}
                                      >
                                        {room.status}
                                      </span>
                                    </td>
                                    <td className="px-4 py-3">
                                      <span
                                        className={`rounded-md px-2 py-0.5 text-[10px] font-bold ${
                                          room.housekeepingStatus === "DIRTY"
                                            ? "bg-rose-50 text-rose-800 border border-rose-200"
                                            : "bg-amber-50 text-amber-800 border border-amber-200"
                                        }`}
                                      >
                                        {room.housekeepingStatus.replace(/_/g, " ")}
                                      </span>
                                    </td>
                                    <td className="px-4 py-3 text-right">
                                      <Link
                                        href="/dashboard/rooms"
                                        className="rounded-lg bg-[#294638] px-3 py-1.5 text-xs font-semibold text-white hover:bg-[#20382c]"
                                      >
                                        Manage Room →
                                      </Link>
                                    </td>
                                  </tr>
                                );
                              })}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </div>
            </section>

            {/* Quick Operations Shortcuts */}
            <section>
              <div className="mb-4">
                <h2 className="font-serif text-2xl text-[#1c352a]">Manage Your Property</h2>
                <p className="mt-1 text-sm leading-6 text-[#748077]">
                  Quick shortcuts to primary front desk and turnover operations.
                </p>
              </div>

              <div className="grid gap-4 lg:grid-cols-3">
                {quickActions.map((action) => (
                  <Link
                    key={action.title}
                    href={action.href}
                    className="group rounded-2xl border border-[#e4e8e0] bg-white p-6 transition hover:border-[#b8c8b8] hover:shadow-sm"
                  >
                    <span className="flex h-11 w-11 items-center justify-center rounded-xl bg-[#eaf0e9] text-xl text-[#294638]">
                      {action.symbol}
                    </span>
                    <h3 className="mt-5 font-serif text-xl text-[#1c352a]">
                      {action.title}
                    </h3>
                    <p className="mt-2 text-sm leading-6 text-[#748077]">
                      {action.description}
                    </p>
                    <p className="mt-5 text-sm font-semibold text-[#294638]">
                      Open section <span className="group-hover:ml-1">→</span>
                    </p>
                  </Link>
                ))}
              </div>
            </section>

            {/* Footer */}
            <footer className="pb-3 text-xs text-[#89938b]">
              Homestay PMS · Property Operations Workspace · Live Integration
            </footer>
          </div>
        </section>
      </div>
    </main>
  );
}
