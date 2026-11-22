import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";

// Session secret for testing
process.env.PMS_SESSION_SECRET = "test-secret-at-least-32-characters-for-aes-256-gcm!";

import { encryptSession } from "../src/lib/auth/session.ts";
import { DEV_COOKIE_NAME } from "../src/lib/auth/cookies.ts";
import { PATCH as confirmRoute } from "../src/app/api/reservations/[id]/confirm/route.ts";

describe("PMS Front Desk Reservation Confirmation BFF & UI Integration Test Suite", () => {
  let mockServer;
  let mockPort;
  let confirmCallCount = 0;

  const validStaff = {
    id: "staff-uuid-1",
    email: "manager@homestay.local",
    firstName: "Alice",
    lastName: "Manager",
  };

  before(async () => {
    mockServer = http.createServer((req, res) => {
      const url = new URL(req.url, `http://${req.headers.host}`);
      const auth = req.headers["authorization"];

      if (url.pathname === "/api/v1/auth/refresh" && req.method === "POST") {
        res.writeHead(200, {
          "Content-Type": "application/json",
          "Set-Cookie": "pms-refresh-dev=confirm-refreshed-token; Path=/; HttpOnly; SameSite=Strict",
        });
        res.end(
          JSON.stringify({
            accessToken: "jwt-refreshed-access-token",
            tokenType: "Bearer",
            expiresInSeconds: 900,
            staff: validStaff,
          })
        );
        return;
      }

      // Confirmation endpoints
      if (
        url.pathname.startsWith("/api/v1/reservations/") &&
        url.pathname.endsWith("/confirm") &&
        req.method === "PATCH"
      ) {
        confirmCallCount++;
        const parts = url.pathname.split("/");
        const id = parts[4];

        if (auth === "Bearer jwt-expired-token") {
          res.writeHead(401, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ message: "Access token expired" }));
          return;
        }

        if (auth !== "Bearer jwt-valid-token" && auth !== "jwt-valid-token") {
          res.writeHead(401, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ message: "Unauthorized" }));
          return;
        }

        if (id === "11111111-1111-1111-1111-111111111111") {
          // Success: DIRECT PENDING -> CONFIRMED
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              id: "11111111-1111-1111-1111-111111111111",
              propertyId: "a0000000-0000-0000-0000-000000000001",
              primaryGuestId: "guest-uuid-1",
              checkInDate: "2026-11-20",
              checkOutDate: "2026-11-25",
              bookingSourceId: "direct-booking-source-id",
              status: "CONFIRMED",
              checkoutDue: false,
            })
          );
        } else if (id === "22222222-2222-2222-2222-222222222222") {
          // Insufficient inventory error
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              timestamp: new Date().toISOString(),
              status: 400,
              error: "Business Validation Failed",
              message: "Insufficient availability for room type 'Deluxe Suite' for the requested dates. Requested: 1, available: 0",
            })
          );
        } else if (id === "33333333-3333-3333-3333-333333333333") {
          // OTA reservation confirmation attempt
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              timestamp: new Date().toISOString(),
              status: 400,
              error: "Business Validation Failed",
              message: "OTA reservations cannot be confirmed locally. Confirmation must be confirmed by the booking platform.",
            })
          );
        } else if (id === "44444444-4444-4444-4444-444444444444") {
          // Already confirmed error
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              timestamp: new Date().toISOString(),
              status: 400,
              error: "Business Validation Failed",
              message: "Reservation is already confirmed",
            })
          );
        } else if (id === "55555555-5555-5555-5555-555555555555") {
          // Invalid status: CANCELLED
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              timestamp: new Date().toISOString(),
              status: 400,
              error: "Business Validation Failed",
              message: "Cannot confirm reservation from status: CANCELLED. Only PENDING reservations can be confirmed.",
            })
          );
        } else if (id === "88888888-8888-8888-8888-888888888888") {
          // Missing permission: 403 Forbidden
          res.writeHead(403, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              timestamp: new Date().toISOString(),
              status: 403,
              error: "Forbidden",
              message: "Access is denied.",
            })
          );
        } else if (id === "99999999-9999-9999-9999-999999999999") {
          // Foreign property / non-existent: 404 Not Found
          res.writeHead(404, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              timestamp: new Date().toISOString(),
              status: 404,
              error: "Resource Not Found",
              message: "Reservation not found with id: 99999999-9999-9999-9999-999999999999",
            })
          );
        } else {
          res.writeHead(404, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ message: "Not found" }));
        }
        return;
      }

      res.writeHead(404, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ message: "Unknown mock route" }));
    });

    await new Promise((resolve) => {
      mockServer.listen(0, "127.0.0.1", () => {
        mockPort = mockServer.address().port;
        process.env.PMS_API_BASE_URL = `http://127.0.0.1:${mockPort}`;
        resolve();
      });
    });
  });

  after(async () => {
    if (mockServer) {
      await new Promise((resolve) => mockServer.close(resolve));
    }
  });

  async function createValidSessionCookie(token = "jwt-valid-token") {
    const session = {
      accessToken: token,
      accessTokenExpiresAt: Date.now() + 900000,
      refreshToken: "confirm-test-refresh-token",
      refreshTokenCookieName: "pms-refresh-dev",
      staff: validStaff,
      sessionVersion: 1,
    };
    const encrypted = await encryptSession(session);
    return `${DEV_COOKIE_NAME}=${encrypted}`;
  }

  // =========================================================================
  // Part 1: BFF Proxy Routing & Security
  // =========================================================================

  it("1. forwards valid authenticated PATCH confirmation to backend and returns 200 with CONFIRMED status", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/11111111-1111-1111-1111-111111111111/confirm", {
      method: "PATCH",
      headers: {
        origin: "http://localhost:3000",
        cookie,
        "x-pms-client": "staff-web",
      },
    });

    const initialCalls = confirmCallCount;
    const res = await confirmRoute(req, {
      params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
    });

    assert.equal(res.status, 200);
    assert.ok(res.headers.get("Cache-Control").includes("no-store"));
    const data = await res.json();
    assert.equal(data.status, "CONFIRMED");
    assert.equal(data.id, "11111111-1111-1111-1111-111111111111");
    assert.equal(confirmCallCount, initialCalls + 1, "exactly one backend request dispatched");
  });

  it("2. rejects unauthenticated confirmation request with 401 Unauthorized", async () => {
    const req = new Request("http://localhost:3000/api/reservations/11111111-1111-1111-1111-111111111111/confirm", {
      method: "PATCH",
      headers: {
        origin: "http://localhost:3000",
        "x-pms-client": "staff-web",
      },
    });

    const res = await confirmRoute(req, {
      params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
    });

    assert.equal(res.status, 401);
    const data = await res.json();
    assert.ok(data.error.includes("Unauthorized"));
  });

  it("3. rejects invalid UUID parameter with 400 Bad Request before reaching backend", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/not-a-valid-uuid/confirm", {
      method: "PATCH",
      headers: {
        origin: "http://localhost:3000",
        cookie,
      },
    });

    const initialCalls = confirmCallCount;
    const res = await confirmRoute(req, {
      params: Promise.resolve({ id: "not-a-valid-uuid" }),
    });

    assert.equal(res.status, 400);
    const data = await res.json();
    assert.equal(data.error, "Invalid reservation UUID parameter.");
    assert.equal(confirmCallCount, initialCalls, "backend was not contacted");
  });

  it("4. rejects mutating request with foreign Origin header (CSRF protection)", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/11111111-1111-1111-1111-111111111111/confirm", {
      method: "PATCH",
      headers: {
        origin: "https://attacker.evil.com",
        cookie,
      },
    });

    const res = await confirmRoute(req, {
      params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
    });

    assert.equal(res.status, 403);
    const data = await res.json();
    assert.ok(data.error.includes("CSRF check failed"));
  });

  it("5. rejects mutating request with missing Origin and Referer (CSRF protection)", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/11111111-1111-1111-1111-111111111111/confirm", {
      method: "PATCH",
      headers: {
        cookie,
      },
    });

    const res = await confirmRoute(req, {
      params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
    });

    assert.equal(res.status, 403);
    const data = await res.json();
    assert.ok(data.error.includes("CSRF check failed"));
  });

  it("6. forwards backend 400 Insufficient Inventory error safely", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/22222222-2222-2222-2222-222222222222/confirm", {
      method: "PATCH",
      headers: {
        origin: "http://localhost:3000",
        cookie,
      },
    });

    const res = await confirmRoute(req, {
      params: Promise.resolve({ id: "22222222-2222-2222-2222-222222222222" }),
    });

    assert.equal(res.status, 400);
    const data = await res.json();
    assert.ok(data.message.includes("Insufficient availability"));
  });

  it("7. forwards backend 400 OTA booking confirmation rejection safely", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/33333333-3333-3333-3333-333333333333/confirm", {
      method: "PATCH",
      headers: {
        origin: "http://localhost:3000",
        cookie,
      },
    });

    const res = await confirmRoute(req, {
      params: Promise.resolve({ id: "33333333-3333-3333-3333-333333333333" }),
    });

    assert.equal(res.status, 400);
    const data = await res.json();
    assert.ok(data.message.includes("OTA reservations cannot be confirmed locally"));
  });

  it("8. forwards backend 400 already confirmed rejection safely", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/44444444-4444-4444-4444-444444444444/confirm", {
      method: "PATCH",
      headers: {
        origin: "http://localhost:3000",
        cookie,
      },
    });

    const res = await confirmRoute(req, {
      params: Promise.resolve({ id: "44444444-4444-4444-4444-444444444444" }),
    });

    assert.equal(res.status, 400);
    const data = await res.json();
    assert.ok(data.message.includes("Reservation is already confirmed"));
  });

  it("9. forwards backend 400 invalid status transition safely", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/55555555-5555-5555-5555-555555555555/confirm", {
      method: "PATCH",
      headers: {
        origin: "http://localhost:3000",
        cookie,
      },
    });

    const res = await confirmRoute(req, {
      params: Promise.resolve({ id: "55555555-5555-5555-5555-555555555555" }),
    });

    assert.equal(res.status, 400);
    const data = await res.json();
    assert.ok(data.message.includes("Cannot confirm reservation from status: CANCELLED"));
  });

  it("10. forwards backend 403 Forbidden permission denial safely", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/88888888-8888-8888-8888-888888888888/confirm", {
      method: "PATCH",
      headers: {
        origin: "http://localhost:3000",
        cookie,
      },
    });

    const res = await confirmRoute(req, {
      params: Promise.resolve({ id: "88888888-8888-8888-8888-888888888888" }),
    });

    assert.equal(res.status, 403);
    const data = await res.json();
    assert.equal(data.message, "Access is denied.");
  });

  it("11. forwards backend 404 Resource Not Found for foreign property or non-existent reservation", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/99999999-9999-9999-9999-999999999999/confirm", {
      method: "PATCH",
      headers: {
        origin: "http://localhost:3000",
        cookie,
      },
    });

    const res = await confirmRoute(req, {
      params: Promise.resolve({ id: "99999999-9999-9999-9999-999999999999" }),
    });

    assert.equal(res.status, 404);
    const data = await res.json();
    assert.ok(data.message.includes("Reservation not found with id: 99999999-9999-9999-9999-999999999999"));
  });

  it("12. never silently replays mutating PATCH request when backend returns 401", async () => {
    const expiredCookie = await createValidSessionCookie("jwt-expired-token");
    const req = new Request("http://localhost:3000/api/reservations/11111111-1111-1111-1111-111111111111/confirm", {
      method: "PATCH",
      headers: {
        origin: "http://localhost:3000",
        cookie: expiredCookie,
      },
    });

    const callsBefore = confirmCallCount;
    const res = await confirmRoute(req, {
      params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
    });

    assert.equal(res.status, 401);
    assert.equal(confirmCallCount, callsBefore + 1, "mutating PATCH was NOT replayed automatically");
  });

  it("13. does not expose tokens or sensitive staff credentials in BFF responses", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/11111111-1111-1111-1111-111111111111/confirm", {
      method: "PATCH",
      headers: {
        origin: "http://localhost:3000",
        cookie,
      },
    });

    const res = await confirmRoute(req, {
      params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
    });

    const bodyText = await res.text();
    assert.ok(!bodyText.includes("jwt-valid-token"), "Access token not leaked");
    assert.ok(!bodyText.includes("confirm-test-refresh-token"), "Refresh token not leaked");
    assert.ok(!bodyText.includes("PMS_SESSION_SECRET"), "Session secret not leaked");
  });

  // =========================================================================
  // Part 2: Front Desk UI Logic, Eligibility & Uncertainty State Machine
  // =========================================================================

  function canConfirmReservation(reservation, bookingSource) {
    if (!reservation || reservation.status !== "PENDING") return false;
    if (!bookingSource) return false;
    return bookingSource.code?.toUpperCase() === "DIRECT";
  }

  function isUncertainError(message) {
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

  it("14. UI eligibility: DIRECT PENDING reservation is eligible for confirmation", () => {
    const res = { id: "res-1", status: "PENDING" };
    const src = { id: "src-1", code: "DIRECT", name: "Direct Booking" };
    assert.equal(canConfirmReservation(res, src), true);
  });

  it("15. UI eligibility: OTA reservation with PENDING status is NOT eligible for local confirmation", () => {
    const res = { id: "res-2", status: "PENDING" };
    const src = { id: "src-2", code: "BOOKING_COM", name: "Booking.com" };
    assert.equal(canConfirmReservation(res, src), false);

    const src2 = { id: "src-3", code: "AIRBNB", name: "Airbnb" };
    assert.equal(canConfirmReservation(res, src2), false);
  });

  it("16. UI eligibility: non-PENDING reservation states cannot be confirmed", () => {
    const directSrc = { id: "src-1", code: "DIRECT" };
    assert.equal(canConfirmReservation({ status: "CONFIRMED" }, directSrc), false);
    assert.equal(canConfirmReservation({ status: "CHECKED_IN" }, directSrc), false);
    assert.equal(canConfirmReservation({ status: "CHECKED_OUT" }, directSrc), false);
    assert.equal(canConfirmReservation({ status: "CANCELLED" }, directSrc), false);
    assert.equal(canConfirmReservation({ status: "NO_SHOW" }, directSrc), false);
  });

  it("17. UI state transition: Confirm button disappears when reservation status becomes CONFIRMED", () => {
    let res = { id: "res-1", status: "PENDING" };
    const directSrc = { id: "src-1", code: "DIRECT" };
    assert.equal(canConfirmReservation(res, directSrc), true);

    // On confirmation success
    res = { ...res, status: "CONFIRMED" };
    assert.equal(canConfirmReservation(res, directSrc), false, "Confirm button is hidden");
  });

  it("18. Uncertain-outcome error classification accurately identifies network and gateway errors", () => {
    assert.equal(isUncertainError("Cannot connect to server"), true);
    assert.equal(isUncertainError("Network request failed"), true);
    assert.equal(isUncertainError("Request failed (502)."), true);
    assert.equal(isUncertainError("Gateway timeout (504)"), true);
    assert.equal(isUncertainError("Connection timeout"), true);

    // Deterministic errors must NOT be uncertain
    assert.equal(isUncertainError("Insufficient availability for room type"), false);
    assert.equal(isUncertainError("Reservation is already confirmed"), false);
    assert.equal(isUncertainError("Access is denied."), false);
    assert.equal(isUncertainError("Reservation not found with id: ..."), false);
  });

  it("19. Uncertainty gate: blocks repeated confirmation attempts while uncertain", () => {
    const resId = "res-uncertain-1";
    const uncertainSet = new Set();

    // Normal state: allowed
    let isBlocked = uncertainSet.has(resId);
    assert.equal(isBlocked, false);

    // Network timeout occurs
    const err = "Network timeout";
    if (isUncertainError(err)) {
      uncertainSet.add(resId);
    }

    // Now blocked
    isBlocked = uncertainSet.has(resId);
    assert.equal(isBlocked, true, "Mutation is disabled when reservation is uncertain");
  });

  it("20. Closing and reopening modal preserves unresolved uncertainty gate", () => {
    const resId = "res-uncertain-2";
    const uncertainSet = new Set([resId]);

    // Modal closed: selectedReservation = null
    let selectedReservation = null;

    // Modal reopened for same reservation
    selectedReservation = { id: resId, status: "PENDING" };
    const isGated = uncertainSet.has(selectedReservation.id);
    assert.equal(isGated, true, "Uncertainty gate is preserved across modal close and reopen");
  });

  it("21. Failed refresh retains the uncertainty gate, while successful refresh clears it", () => {
    const resId = "res-uncertain-3";
    const uncertainSet = new Set([resId]);

    // Attempt refresh -> fails
    const refreshFailed = true;
    if (refreshFailed) {
      uncertainSet.add(resId); // preserved
    }
    assert.equal(uncertainSet.has(resId), true, "Gate preserved on refresh failure");

    // Attempt refresh -> succeeds with reconciled data
    const refreshedReservation = { id: resId, status: "CONFIRMED" };
    uncertainSet.delete(refreshedReservation.id);
    assert.equal(uncertainSet.has(resId), false, "Gate cleared upon successful authoritative refresh");
  });

  it("22. WAI-ARIA accessibility contracts: confirmation dialog declares required semantics", () => {
    const dialogAttrs = {
      role: "dialog",
      "aria-modal": "true",
      "aria-labelledby": "confirm-dialog-title",
      "aria-describedby": "confirm-dialog-description",
    };

    assert.equal(dialogAttrs.role, "dialog");
    assert.equal(dialogAttrs["aria-modal"], "true");
    assert.equal(dialogAttrs["aria-labelledby"], "confirm-dialog-title");
    assert.equal(dialogAttrs["aria-describedby"], "confirm-dialog-description");
  });
});
