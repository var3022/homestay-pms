"use client";

import { useEffect, useState, useSyncExternalStore } from "react";
import Link from "next/link";

const subscribeToDate = () => () => {};

const getTodayInIndia = () =>
  new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Kolkata",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());

const getServerToday = () => "";

type Room = {
  id: string;
  roomNumber: string;
  name: string;
  status: string;
};

type Reservation = {
  id: string;
  checkInDate: string;
  checkOutDate: string;
  status: string;
};
const quickActions = [
  {
    title: "Reservations",
    description: "View and manage guest bookings.",
    href: "/dashboard/reservations",
    symbol: "↗",
  },
  {
    title: "Room management",
    description: "Review room inventory and room status.",
    href: "/dashboard/rooms",
    symbol: "⌂",
  },
  {
    title: "Guest records",
    description: "Access guest details and check-in records.",
    href: "/dashboard/guests",
    symbol: "◎",
  },
];

export default function DashboardPage() {

  const [rooms, setRooms] = useState<Room[]>([]);
  const [roomsLoaded, setRoomsLoaded] = useState(false);
  const [roomsError, setRoomsError] = useState(false);
  const [reservations, setReservations] = useState<Reservation[]>([]);
  const [reservationsLoaded, setReservationsLoaded] = useState(false);
  const [reservationsError, setReservationsError] = useState(false);

  useEffect(() => {
    fetch("/api/rooms", { cache: "no-store" })
      .then((response) => {
        if (!response.ok) {
          throw new Error("Failed to load rooms");
        }
        return response.json() as Promise<Room[]>;
      })
      .then((data) => {
        setRooms(data);
        setRoomsError(false);
      })
      .catch(() => {
        setRoomsError(true);
      })
      .finally(() => {
        setRoomsLoaded(true);
      });
  }, []);

  useEffect(() => {
    fetch("/api/reservations", { cache: "no-store" })
      .then((response) => {
        if (!response.ok) {
          throw new Error("Failed to load reservations");
        }
        return response.json() as Promise<Reservation[]>;
      })
      .then((data) => {
        setReservations(data);
        setReservationsError(false);
      })
      .catch(() => {
        setReservationsError(true);
      })
      .finally(() => {
        setReservationsLoaded(true);
      });
  }, []);

  
  
  const today = useSyncExternalStore(
    subscribeToDate,
    getTodayInIndia,
    getServerToday,
  );


  const excludedStatuses = ["CANCELLED", "NO_SHOW"];

  const todaysArrivals = reservations.filter(
    (reservation) =>
      reservation.checkInDate === today &&
      !excludedStatuses.includes(reservation.status),
  ).length;

  const todaysDepartures = reservations.filter(
    (reservation) =>
      reservation.checkOutDate === today &&
      reservation.status === "CHECKED_IN",
  ).length;


  const stats = [
    {
      label: "Total rooms",
      value: roomsError ? "—" : roomsLoaded ? String(rooms.length).padStart(2, "0") : "…",
      note: roomsError
        ? "Unable to load rooms"
        : roomsLoaded
          ? "Live property inventory"
          : "Loading rooms…",
    },
    {
      label: "Occupied rooms",
      value: "—",
      note: "Check-in workflow not connected",
    },
    {
      label: "Today's arrivals",
      value: reservationsError
        ? "—"
        : reservationsLoaded
          ? String(todaysArrivals).padStart(2, "0")
          : "…",
      note: reservationsError
        ? "Unable to load reservations"
        : reservationsLoaded
          ? "Scheduled for today"
          : "Loading reservations…",
    },
    {
      label: "Today's departures",
      value: reservationsError
        ? "—"
        : reservationsLoaded
          ? String(todaysDepartures).padStart(2, "0")
          : "…",
      note: reservationsError
        ? "Unable to load reservations"
        : reservationsLoaded
          ? "Checked-in guests due out today"
          : "Loading reservations…",
    },
  ];

  return (
    <main className="min-h-screen bg-[#f5f6f3] text-[#24382e]">
      <div className="flex min-h-screen flex-col md:flex-row">
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
              ⌂ &nbsp; Rooms
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
            <p className="mt-1 text-sm font-medium">Property operations</p>
          </div>
        </aside>

        <section className="min-w-0 flex-1">
          <header className="flex flex-wrap items-center justify-between gap-4 border-b border-[#e1e6df] bg-white px-5 py-5 sm:px-8">
            <div>
              <p className="text-xs font-medium tracking-[0.18em] text-[#8a7954]">
                PROPERTY OPERATIONS
              </p>
              <h1 className="mt-1 font-serif text-2xl sm:text-3xl">
                Dashboard
              </h1>
            </div>
            <Link
              href="/"
              className="rounded-full border border-[#dce2d9] px-4 py-2.5 text-sm font-medium hover:bg-[#f5f6f3]"
            >
              View website ↗
            </Link>
          </header>

          <div className="mx-auto max-w-7xl space-y-8 px-5 py-7 sm:px-8 sm:py-9">
            <section>
              <p className="text-sm text-[#748077]">
                Your property at a glance
              </p>
              <h2 className="mt-1 font-serif text-2xl">
                Welcome to your workspace
              </h2>
            </section>

            <section className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
              {stats.map((stat) => (
                <article
                  key={stat.label}
                  className="rounded-2xl border border-[#e4e8e0] bg-white p-5 sm:p-6"
                >
                  <p className="text-sm text-[#748077]">{stat.label}</p>
                  <p className="mt-4 font-serif text-4xl">{stat.value}</p>
                  <p className="mt-3 text-xs text-[#89938b]">{stat.note}</p>
                </article>
              ))}
            </section>

            <section>
              <div className="mb-4">
                <h2 className="font-serif text-2xl">Manage your property</h2>
                <p className="mt-1 text-sm leading-6 text-[#748077]">
                  Shortcuts to your everyday operations.
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
                    <h3 className="mt-5 font-serif text-xl">
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

            <section className="rounded-2xl border border-[#e4e8e0] bg-white p-5 sm:p-7">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <h2 className="font-serif text-2xl">Today&apos;s activity</h2>
                  <p className="mt-2 text-sm leading-6 text-[#748077]">
                    Live arrivals, departures and occupancy will appear here
                    after the backend integration.
                  </p>
                </div>
                <span className="rounded-full bg-[#f3f0e7] px-3 py-1.5 text-xs font-medium text-[#806b3f]">
                  Demo preview
                </span>
              </div>
              <div className="mt-6 rounded-xl border border-dashed border-[#dce2d9] px-4 py-8 text-center sm:py-10">
                <p className="font-medium">No live activity connected yet</p>
                <p className="mt-2 text-sm text-[#748077]">
                  Guest and reservation data will be loaded from your PMS API.
                </p>
              </div>
            </section>

            <footer className="pb-3 text-xs text-[#89938b]">
              Homestay PMS · Staff workspace · Preview version
            </footer>
          </div>
        </section>
      </div>
    </main>
  );
}
