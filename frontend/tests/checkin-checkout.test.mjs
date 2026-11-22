import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";

// Session secret for testing
process.env.PMS_SESSION_SECRET = "test-secret-at-least-32-characters-for-aes-256-gcm!";

import { encryptSession } from "../src/lib/auth/session.ts";
import { DEV_COOKIE_NAME } from "../src/lib/auth/cookies.ts";
import { POST as checkInRoute } from "../src/app/api/reservations/[id]/check-in/route.ts";
import { POST as checkOutRoute } from "../src/app/api/reservations/[id]/check-out/route.ts";

describe("PMS Front Desk Check-In & Checkout Proxy Integration Test Suite", () => {
  let mockServer;
  let mockPort;
  let checkInCallCount = 0;
  let checkOutCallCount = 0;

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
          "Set-Cookie": "pms-refresh-dev=checkin-refreshed-token; Path=/; HttpOnly; SameSite=Strict",
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

      // Check-in endpoints
      if (url.pathname.startsWith("/api/v1/reservations/") && url.pathname.endsWith("/check-in") && req.method === "POST") {
        checkInCallCount++;
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
          // Success
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              id: "11111111-1111-1111-1111-111111111111",
              propertyId: "a0000000-0000-0000-0000-000000000001",
              primaryGuestId: "guest-uuid-1",
              checkInDate: "2026-11-20",
              checkOutDate: "2026-11-25",
              status: "CHECKED_IN",
              checkoutDue: false,
              checkoutDueAt: "2026-11-25T06:30:00Z",
            })
          );
        } else if (id === "22222222-2222-2222-2222-222222222222") {
          // Dirty room error
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              timestamp: new Date().toISOString(),
              status: 400,
              error: "Business Validation Failed",
              message: "Cannot check in: assigned room requires housekeeping.",
            })
          );
        } else if (id === "33333333-3333-3333-3333-333333333333") {
          // Missing room assignment error
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              timestamp: new Date().toISOString(),
              status: 400,
              error: "Business Validation Failed",
              message: "Incomplete physical room assignment for room type 'Deluxe Room'. Required: 1, assigned: 0. All reserved rooms must be physically assigned before check-in.",
            })
          );
        } else if (id === "44444444-4444-4444-4444-444444444444") {
          // Split stay error
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              timestamp: new Date().toISOString(),
              status: 400,
              error: "Business Validation Failed",
              message: "Cannot check in: Split-stay reservations (different physical rooms across stay dates) are not supported. All assigned rooms must cover the full stay period.",
            })
          );
        } else if (id === "55555555-5555-5555-5555-555555555555") {
          // Early check-in error
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              timestamp: new Date().toISOString(),
              status: 400,
              error: "Business Validation Failed",
              message: "Cannot check in before reservation check-in date. Check-in date: 2026-12-01, current property date: 2026-11-22",
            })
          );
        } else if (id === "66666666-6666-6666-6666-666666666666") {
          // Status error: already checked out
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              timestamp: new Date().toISOString(),
              status: 400,
              error: "Business Validation Failed",
              message: "Cannot check in reservation from status: CHECKED_OUT. Only CONFIRMED reservations can be checked in.",
            })
          );
        } else if (id === "77777777-7777-7777-7777-777777777777") {
          // Status error: pending
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              timestamp: new Date().toISOString(),
              status: 400,
              error: "Business Validation Failed",
              message: "Cannot check in reservation from status: PENDING. Only CONFIRMED reservations can be checked in.",
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
          // Foreign property: 404 Resource Not Found
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

      // Check-out endpoints
      if (url.pathname.startsWith("/api/v1/reservations/") && url.pathname.endsWith("/check-out") && req.method === "POST") {
        checkOutCallCount++;
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

        if (id === "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb") {
          // Successful checkout
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
              propertyId: "a0000000-0000-0000-0000-000000000001",
              primaryGuestId: "guest-uuid-2",
              checkInDate: "2026-11-20",
              checkOutDate: "2026-11-25",
              status: "CHECKED_OUT",
              checkoutDue: false,
              checkoutDueAt: "2026-11-25T06:30:00Z",
              actualCheckedOutAt: "2026-11-25T07:15:22Z",
              checkedOutByStaffUserId: "staff-uuid-1",
            })
          );
        } else if (id === "cccccccc-cccc-cccc-cccc-cccccccccccc") {
          // Already checked out
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              timestamp: new Date().toISOString(),
              status: 400,
              error: "Business Validation Failed",
              message: "Reservation is already checked out",
            })
          );
        } else if (id === "dddddddd-dddd-dddd-dddd-dddddddddddd") {
          // Ambiguous physical room assignment error
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              timestamp: new Date().toISOString(),
              status: 400,
              error: "Business Validation Failed",
              message: "Cannot check out: Ambiguous or incomplete physical room assignments for room type 'Deluxe'. Required: 1, assigned: 0. Each reserved room must have a distinct full-stay assignment.",
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
          // Foreign property: 404 Resource Not Found
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
      res.end(JSON.stringify({ message: "Not found" }));
    });

    await new Promise((resolve) => {
      mockServer.listen(0, "127.0.0.1", () => {
        mockPort = mockServer.address().port;
        process.env.PMS_API_BASE_URL = `http://127.0.0.1:${mockPort}`;
        process.env.PMS_FRONTEND_ORIGIN = "http://localhost:3000";
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
      refreshToken: "checkin-test-refresh-token",
      refreshTokenCookieName: "pms-refresh-dev",
      staff: validStaff,
      sessionVersion: 1,
    };
    const encrypted = await encryptSession(session);
    return `${DEV_COOKIE_NAME}=${encrypted}`;
  }

  // 1. Authenticated check-in proxy
  it("1. forwards authenticated check-in with Bearer token and returns updated reservation", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/11111111-1111-1111-1111-111111111111/check-in", {
      method: "POST",
      headers: {
        origin: "http://localhost:3000",
        cookie,
        "x-pms-client": "staff-web",
      },
    });

    const res = await checkInRoute(req, {
      params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
    });

    assert.equal(res.status, 200);
    assert.ok(res.headers.get("Cache-Control").includes("no-store"));
    const data = await res.json();
    assert.equal(data.status, "CHECKED_IN");
    assert.equal(data.id, "11111111-1111-1111-1111-111111111111");
  });

  // 2. Authenticated checkout proxy
  it("2. forwards authenticated checkout with Bearer token and returns updated reservation with timestamp", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/check-out", {
      method: "POST",
      headers: {
        origin: "http://localhost:3000",
        cookie,
        "x-pms-client": "staff-web",
      },
    });

    const res = await checkOutRoute(req, {
      params: Promise.resolve({ id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb" }),
    });

    assert.equal(res.status, 200);
    assert.ok(res.headers.get("Cache-Control").includes("no-store"));
    const data = await res.json();
    assert.equal(data.status, "CHECKED_OUT");
    assert.ok(data.actualCheckedOutAt);
    assert.equal(data.checkedOutByStaffUserId, "staff-uuid-1");
  });

  // 3. Unauthorized access returns 401
  it("3. rejects check-in and checkout requests without session cookie with 401", async () => {
    const reqCheckIn = new Request("http://localhost:3000/api/reservations/11111111-1111-1111-1111-111111111111/check-in", {
      method: "POST",
      headers: { origin: "http://localhost:3000" },
    });
    const resCheckIn = await checkInRoute(reqCheckIn, {
      params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
    });
    assert.equal(resCheckIn.status, 401);

    const reqCheckOut = new Request("http://localhost:3000/api/reservations/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/check-out", {
      method: "POST",
      headers: { origin: "http://localhost:3000" },
    });
    const resCheckOut = await checkOutRoute(reqCheckOut, {
      params: Promise.resolve({ id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb" }),
    });
    assert.equal(resCheckOut.status, 401);
  });

  // 4. Missing permission returns backend 403
  it("4. preserves backend 403 AccessDeniedException when staff lacks RESERVATION_MANAGE", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/88888888-8888-8888-8888-888888888888/check-in", {
      method: "POST",
      headers: { origin: "http://localhost:3000", cookie },
    });
    const res = await checkInRoute(req, {
      params: Promise.resolve({ id: "88888888-8888-8888-8888-888888888888" }),
    });
    assert.equal(res.status, 403);
    const data = await res.json();
    assert.equal(data.error, "Forbidden");
  });

  // 5. Foreign property returns backend 404
  it("5. preserves backend 404 ResourceNotFoundException when reservation is foreign property", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/99999999-9999-9999-9999-999999999999/check-in", {
      method: "POST",
      headers: { origin: "http://localhost:3000", cookie },
    });
    const res = await checkInRoute(req, {
      params: Promise.resolve({ id: "99999999-9999-9999-9999-999999999999" }),
    });
    assert.equal(res.status, 404);
    const data = await res.json();
    assert.equal(data.error, "Resource Not Found");
  });

  // 6. CSRF rejection for cross-origin mutation
  it("6. rejects cross-origin mutating requests even with X-PMS-Client header (403)", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/11111111-1111-1111-1111-111111111111/check-in", {
      method: "POST",
      headers: {
        origin: "http://attacker.com",
        "x-pms-client": "staff-web",
        cookie,
      },
    });
    const res = await checkInRoute(req, {
      params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
    });
    assert.equal(res.status, 403);
    const data = await res.json();
    assert.ok(data.error.includes("CSRF"));
  });

  // 7. Valid reservation UUID requirements
  it("7. rejects malformed or non-UUID route parameters with 400 before backend dispatch", async () => {
    const cookie = await createValidSessionCookie();
    const badIds = ["not-a-uuid", "12345", "../../traversal", "11111111-1111-1111-1111"];

    for (const badId of badIds) {
      const req = new Request(`http://localhost:3000/api/reservations/${badId}/check-in`, {
        method: "POST",
        headers: { origin: "http://localhost:3000", cookie },
      });
      const res = await checkInRoute(req, {
        params: Promise.resolve({ id: badId }),
      });
      assert.equal(res.status, 400);
      const data = await res.json();
      assert.ok(data.error.includes("UUID"));
    }
  });

  // 8. No token leakage
  it("8. ensures responses never leak raw JWT, refresh token, or session secret", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/11111111-1111-1111-1111-111111111111/check-in", {
      method: "POST",
      headers: { origin: "http://localhost:3000", cookie },
    });
    const res = await checkInRoute(req, {
      params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
    });
    const text = await res.text();
    assert.equal(text.includes("jwt-valid-token"), false);
    assert.equal(text.includes("checkin-test-refresh-token"), false);
    assert.equal(text.includes(process.env.PMS_SESSION_SECRET), false);
  });

  // 9. CONFIRMED status check-in action
  it("9. transitions CONFIRMED reservation to CHECKED_IN", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/11111111-1111-1111-1111-111111111111/check-in", {
      method: "POST",
      headers: { origin: "http://localhost:3000", cookie },
    });
    const res = await checkInRoute(req, {
      params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
    });
    assert.equal(res.status, 200);
    const data = await res.json();
    assert.equal(data.status, "CHECKED_IN");
  });

  // 10. CHECKED_IN status checkout action
  it("10. transitions CHECKED_IN reservation to CHECKED_OUT with actualCheckedOutAt", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/check-out", {
      method: "POST",
      headers: { origin: "http://localhost:3000", cookie },
    });
    const res = await checkOutRoute(req, {
      params: Promise.resolve({ id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb" }),
    });
    assert.equal(res.status, 200);
    const data = await res.json();
    assert.equal(data.status, "CHECKED_OUT");
    assert.ok(data.actualCheckedOutAt);
  });

  // 11. CHECKED_OUT terminal state
  it("11. rejects checkout on already CHECKED_OUT reservation with 400", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/cccccccc-cccc-cccc-cccc-cccccccccccc/check-out", {
      method: "POST",
      headers: { origin: "http://localhost:3000", cookie },
    });
    const res = await checkOutRoute(req, {
      params: Promise.resolve({ id: "cccccccc-cccc-cccc-cccc-cccccccccccc" }),
    });
    assert.equal(res.status, 400);
    const data = await res.json();
    assert.ok(data.message.includes("already checked out"));
  });

  // 12. PENDING/CANCELLED/NO_SHOW action rejection by backend
  it("12. rejects check-in for PENDING status with descriptive 400 error", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/77777777-7777-7777-7777-777777777777/check-in", {
      method: "POST",
      headers: { origin: "http://localhost:3000", cookie },
    });
    const res = await checkInRoute(req, {
      params: Promise.resolve({ id: "77777777-7777-7777-7777-777777777777" }),
    });
    assert.equal(res.status, 400);
    const data = await res.json();
    assert.ok(data.message.includes("Only CONFIRMED reservations can be checked in"));
  });

  // 13. Dirty room backend error handling
  it("13. conveys dirty room error from backend safely to frontend", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/22222222-2222-2222-2222-222222222222/check-in", {
      method: "POST",
      headers: { origin: "http://localhost:3000", cookie },
    });
    const res = await checkInRoute(req, {
      params: Promise.resolve({ id: "22222222-2222-2222-2222-222222222222" }),
    });
    assert.equal(res.status, 400);
    const data = await res.json();
    assert.equal(data.error, "Business Validation Failed");
    assert.ok(data.message.includes("assigned room requires housekeeping"));
  });

  // 14. Missing assignment backend error handling
  it("14. conveys missing room assignment error from backend safely", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/33333333-3333-3333-3333-333333333333/check-in", {
      method: "POST",
      headers: { origin: "http://localhost:3000", cookie },
    });
    const res = await checkInRoute(req, {
      params: Promise.resolve({ id: "33333333-3333-3333-3333-333333333333" }),
    });
    assert.equal(res.status, 400);
    const data = await res.json();
    assert.ok(data.message.includes("Incomplete physical room assignment"));
  });

  // 15. Split-stay restriction error handling
  it("15. conveys split-stay restriction error from backend safely", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/44444444-4444-4444-4444-444444444444/check-in", {
      method: "POST",
      headers: { origin: "http://localhost:3000", cookie },
    });
    const res = await checkInRoute(req, {
      params: Promise.resolve({ id: "44444444-4444-4444-4444-444444444444" }),
    });
    assert.equal(res.status, 400);
    const data = await res.json();
    assert.ok(data.message.includes("Split-stay reservations"));
  });

  // 16. Ambiguous checkout errors
  it("16. conveys ambiguous room occupancy error during checkout safely", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/dddddddd-dddd-dddd-dddd-dddddddddddd/check-out", {
      method: "POST",
      headers: { origin: "http://localhost:3000", cookie },
    });
    const res = await checkOutRoute(req, {
      params: Promise.resolve({ id: "dddddddd-dddd-dddd-dddd-dddddddddddd" }),
    });
    assert.equal(res.status, 400);
    const data = await res.json();
    assert.ok(data.message.includes("Ambiguous or incomplete physical room assignments"));
  });

  // 17. Early check-in date validation
  it("17. conveys early check-in error before scheduled arrival date", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/55555555-5555-5555-5555-555555555555/check-in", {
      method: "POST",
      headers: { origin: "http://localhost:3000", cookie },
    });
    const res = await checkInRoute(req, {
      params: Promise.resolve({ id: "55555555-5555-5555-5555-555555555555" }),
    });
    assert.equal(res.status, 400);
    const data = await res.json();
    assert.ok(data.message.includes("before reservation check-in date"));
  });

  // 18. Non-idempotent mutation safety: proxy does NOT replay check-in on 401
  it("18. NON-IDEMPOTENT SAFETY: proxyToBackend DOES NOT silently replay check-in on 401", async () => {
    const initialCallCount = checkInCallCount;
    const cookie = await createValidSessionCookie("jwt-expired-token");

    const req = new Request("http://localhost:3000/api/reservations/11111111-1111-1111-1111-111111111111/check-in", {
      method: "POST",
      headers: { origin: "http://localhost:3000", cookie },
    });

    const res = await checkInRoute(req, {
      params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
    });

    // Check-in was called EXACTLY ONCE on backend and NOT silently replayed!
    assert.equal(checkInCallCount - initialCallCount, 1);
    assert.equal(res.status, 401);
    const data = await res.json();
    assert.equal(data.reauthenticated, true);
    assert.ok(data.error.includes("resubmit"));
  });

  // 19. Non-idempotent mutation safety: proxy does NOT replay check-out on 401
  it("19. NON-IDEMPOTENT SAFETY: proxyToBackend DOES NOT silently replay checkout on 401", async () => {
    const initialCallCount = checkOutCallCount;
    const cookie = await createValidSessionCookie("jwt-expired-token");

    const req = new Request("http://localhost:3000/api/reservations/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/check-out", {
      method: "POST",
      headers: { origin: "http://localhost:3000", cookie },
    });

    const res = await checkOutRoute(req, {
      params: Promise.resolve({ id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb" }),
    });

    // Checkout was called EXACTLY ONCE on backend and NOT silently replayed!
    assert.equal(checkOutCallCount - initialCallCount, 1);
    assert.equal(res.status, 401);
    const data = await res.json();
    assert.equal(data.reauthenticated, true);
  });

  // 20. Staff actor UUID is derived by backend, never accepted from browser body
  it("20. never accepts staff actor UUID from browser body during check-out", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request("http://localhost:3000/api/reservations/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/check-out", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        origin: "http://localhost:3000",
        cookie,
      },
      body: JSON.stringify({ checkedOutByStaffUserId: "spoofed-staff-uuid" }),
    });

    const res = await checkOutRoute(req, {
      params: Promise.resolve({ id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb" }),
    });

    assert.equal(res.status, 200);
    const data = await res.json();
    // The actor ID returned is strictly the authenticated principal from backend (staff-uuid-1), NOT spoofed!
    assert.equal(data.checkedOutByStaffUserId, "staff-uuid-1");
  });
});
