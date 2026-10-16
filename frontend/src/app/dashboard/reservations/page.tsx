
"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";

type Reservation = {
  id: string;
  checkInDate: string;
  checkOutDate: string;
  adults: number;
  children: number;
  infants: number;
  externalBookingId: string | null;
  status: string;
  checkoutDue: boolean;
};

const filters = ["ALL", "PENDING", "CONFIRMED", "CHECKED_IN", "CHECKED_OUT", "CANCELLED", "NO_SHOW"];

function formatDate(value: string) {
  if (!value) return "—";
  const [year, month, day] = value.split("-");
  return `${day}/${month}/${year}`;
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

export default function ReservationsPage() {
  const [reservations, setReservations] = useState<Reservation[]>([]);
  const [filter, setFilter] = useState("ALL");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const loadReservations = useCallback(async () => {
    setLoading(true);
    setError("");

    try {
      const response = await fetch("/api/reservations", {
        cache: "no-store",
      });

      if (!response.ok) {
        throw new Error("Could not load reservations. Please try again.");
      }

      const data: unknown = await response.json();

      if (!Array.isArray(data)) {
        throw new Error("The server returned an unexpected response.");
      }

      setReservations(data as Reservation[]);
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Something went wrong.",
      );
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();

    async function fetchReservations() {
        setError("");

        try {
            const response = await fetch("/api/reservations", {
                cache: "no-store",
                signal: controller.signal,
            });

            if (!response.ok) {
                throw new Error("Could not load reservations. Please try again.");
            }

            const data: unknown = await response.json();

            if (!Array.isArray(data)) {
                throw new Error("The server returned an unexpected response.");
            }

            setReservations(data as Reservation[]);
        } catch (err) {
            if (err instanceof Error && err.name === "AbortError") {
                return;
            }

            setError(
                err instanceof Error ? err.message : "Something went wrong.",
            );
        } finally {
            if (!controller.signal.aborted) {
                setLoading(false);
            }
        }
    }

    void fetchReservations();

    return () => controller.abort();
  }, []);

  const filteredReservations =
    filter === "ALL"
      ? reservations
      : reservations.filter((reservation) => reservation.status === filter);

  return (
    <main className="min-h-screen bg-[#f5f6f3] text-[#24382e]">
      <header className="border-b border-[#e1e6df] bg-white px-5 py-5 sm:px-8">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-4">
          <div>
            <Link
              href="/dashboard"
              className="text-sm text-[#748077] hover:text-[#294638]"
            >
              ← Back to dashboard
            </Link>
            <h1 className="mt-2 font-serif text-2xl sm:text-3xl">
              Reservations
            </h1>
            <p className="mt-1 text-sm text-[#748077]">
              View and manage your property bookings.
            </p>
          </div>

          <button
            type="button"
            onClick={() => void loadReservations()}
            disabled={loading}
            className="rounded-full border border-[#dce2d9] bg-white px-5 py-2.5 text-sm font-medium hover:bg-[#f5f6f3] disabled:opacity-50"
          >
            {loading ? "Refreshing…" : "↻ Refresh"}
          </button>
        </div>
      </header>

      <div className="mx-auto max-w-7xl space-y-6 px-5 py-7 sm:px-8 sm:py-9">
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
                : reservations.filter((item) => item.status === "CONFIRMED").length}
            </p>
          </article>

          <article className="rounded-2xl border border-[#e4e8e0] bg-white p-5">
            <p className="text-sm text-[#748077]">Currently checked in</p>
            <p className="mt-3 font-serif text-3xl">
              {loading
                ? "…"
                : reservations.filter((item) => item.status === "CHECKED_IN").length}
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
                  className={`shrink-0 rounded-full px-4 py-2 text-xs font-medium transition ${
                    filter === item
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
                onClick={() => void loadReservations()}
                className="mt-4 rounded-full bg-[#294638] px-5 py-2.5 text-sm font-medium !text-white"
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
                ▤
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
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#edf0ea]">
                  {filteredReservations.map((reservation) => (
                    <tr key={reservation.id} className="hover:bg-[#fafbf9]">
                      <td className="px-5 py-4">
                        <p className="font-medium">
                          {reservation.externalBookingId || "Direct / PMS booking"}
                        </p>
                        <p className="mt-1 text-xs text-[#89938b]">
                          ID: {reservation.id.slice(0, 8)}…
                        </p>
                      </td>
                      <td className="px-5 py-4 whitespace-nowrap">
                        {formatDate(reservation.checkInDate)}
                      </td>
                      <td className="px-5 py-4 whitespace-nowrap">
                        {formatDate(reservation.checkOutDate)}
                      </td>
                      <td className="px-5 py-4 whitespace-nowrap">
                        {reservation.adults} adults
                        {reservation.children > 0 &&
                          `, ${reservation.children} children`}
                        {reservation.infants > 0 &&
                          `, ${reservation.infants} infants`}
                      </td>
                      <td className="px-5 py-4">
                        <span className={`rounded-full px-3 py-1.5 text-xs font-medium ${statusStyle(reservation.status)}`}>
                          {reservation.status.replaceAll("_", " ")}
                        </span>
                      </td>
                      <td className="px-5 py-4">
                        {reservation.checkoutDue ? "Due" : "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {!loading && !error && reservations.length > 0 && (
            <div className="border-t border-[#e8ebe5] px-5 py-4 text-xs text-[#89938b]">
              Showing {filteredReservations.length} of {reservations.length} reservations
            </div>
          )}
        </section>

        <p className="text-xs leading-5 text-[#89938b]">
          Reservation records are loaded from the PMS backend. Guest names and
          room details will be added when their related records are connected.
        </p>
      </div>
    </main>
  );
}
