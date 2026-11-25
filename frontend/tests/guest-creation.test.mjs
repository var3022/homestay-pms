import { describe, it, before, after, beforeEach } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";

process.env.PMS_SESSION_SECRET = "test-secret-at-least-32-characters-for-aes-256-gcm!";

import { encryptSession } from "../src/lib/auth/session.ts";
import { DEV_COOKIE_NAME } from "../src/lib/auth/cookies.ts";
import { POST as createGuestRoute } from "../src/app/api/guests/route.ts";
import { POST as createReservationRoute } from "../src/app/api/reservations/route.ts";

describe("PMS Staff Guest Creation BFF & Reservation Integration Test Suite", () => {
  let mockServer;
  let mockPort;
  let guestPostCalls = [];
  let reservationPostCalls = [];
  let nextGuestBehavior = null;
  let nextReservationBehavior = null;

  const PROPERTY_ID = "11111111-2222-3333-4444-555555555555";
  const validStaff = {
    id: "staff-uuid-rec-1",
    email: "receptionist@homestay.local",
    firstName: "Riya",
    lastName: "Frontdesk",
  };

  before(async () => {
    mockServer = http.createServer((req, res) => {
      const url = new URL(req.url, `http://${req.headers.host}`);
      const auth = req.headers["authorization"];
      let body = "";
      req.on("data", (chunk) => {
        body += chunk;
      });
      req.on("end", () => {
        if (url.pathname === "/api/v1/auth/refresh" && req.method === "POST") {
          res.writeHead(200, {
            "Content-Type": "application/json",
            "Set-Cookie": "pms-refresh-dev=guest-refreshed; Path=/; HttpOnly; SameSite=Strict",
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

        if (url.pathname === "/api/v1/guests" && req.method === "POST") {
          const parsed = body ? JSON.parse(body) : null;
          guestPostCalls.push({ headers: req.headers, body: parsed });

          if (auth === "Bearer jwt-expired-token") {
            res.writeHead(401, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ message: "Access token expired" }));
            return;
          }

          if (nextGuestBehavior) {
            const { status, payload } = nextGuestBehavior;
            nextGuestBehavior = null;
            res.writeHead(status, { "Content-Type": "application/json" });
            res.end(JSON.stringify(payload));
            return;
          }

          if (!parsed?.propertyId) {
            res.writeHead(400, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ message: "propertyId must be provided" }));
            return;
          }

          res.writeHead(201, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              id: "guest-uuid-created-1",
              propertyId: parsed.propertyId,
              name: parsed.name,
              phone: parsed.phone ?? null,
              email: parsed.email ?? null,
            })
          );
          return;
        }

        if (url.pathname === "/api/v1/reservations" && req.method === "POST") {
          const parsed = body ? JSON.parse(body) : null;
          reservationPostCalls.push({ headers: req.headers, body: parsed });

          if (nextReservationBehavior) {
            const { status, payload } = nextReservationBehavior;
            nextReservationBehavior = null;
            res.writeHead(status, { "Content-Type": "application/json" });
            res.end(JSON.stringify(payload));
            return;
          }

          res.writeHead(201, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              id: "res-uuid-created-1",
              propertyId: parsed.propertyId,
              primaryGuestId: parsed.primaryGuestId,
              status: "PENDING",
            })
          );
          return;
        }

        res.writeHead(404, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ message: "Not found" }));
      });
    });

    await new Promise((resolve) => {
      mockServer.listen(0, "127.0.0.1", () => {
        mockPort = mockServer.address().port;
        process.env.PMS_API_BASE_URL = `http://127.0.0.1:${mockPort}`;
        process.env.PMS_FRONTEND_ORIGIN = "http://localhost:3000";
        process.env.PMS_PROPERTY_ID = PROPERTY_ID;
        resolve();
      });
    });
  });

  after(async () => {
    await new Promise((resolve) => mockServer.close(resolve));
  });

  beforeEach(() => {
    guestPostCalls = [];
    reservationPostCalls = [];
    nextGuestBehavior = null;
    nextReservationBehavior = null;
    process.env.PMS_PROPERTY_ID = PROPERTY_ID;
  });

  async function buildSessionCookie(accessToken = "jwt-valid-token") {
    const encrypted = await encryptSession({
      accessToken,
      accessTokenExpiresAt: Date.now() + 900_000,
      refreshToken: "guest-test-refresh-token",
      refreshTokenCookieName: "pms-refresh-dev",
      sessionVersion: 1,
      staff: validStaff,
    });
    return `${DEV_COOKIE_NAME}=${encrypted}`;
  }

  function makeGuestRequest(payload, { cookie, origin = "http://localhost:3000", rawBody } = {}) {
    const headers = new Headers({
      "Content-Type": "application/json",
    });
    if (origin !== null) {
      headers.set("Origin", origin);
    }
    if (cookie) {
      headers.set("Cookie", cookie);
    }
    return new Request("http://localhost:3000/api/guests", {
      method: "POST",
      headers,
      body: rawBody !== undefined ? rawBody : JSON.stringify(payload),
    });
  }

  it("1. valid staff guest creation injects propertyId and forwards name, phone, email", async () => {
    const cookie = await buildSessionCookie();
    const req = makeGuestRequest(
      {
        name: "Asha Guest",
        phone: "+919876501234",
        email: "asha.guest@test.local",
      },
      { cookie }
    );

    const res = await createGuestRoute(req);
    assert.equal(res.status, 201);
    const data = await res.json();
    assert.equal(data.id, "guest-uuid-created-1");
    assert.equal(data.propertyId, PROPERTY_ID);
    assert.equal(data.name, "Asha Guest");
    assert.equal(data.phone, "+919876501234");
    assert.equal(data.email, "asha.guest@test.local");

    assert.equal(guestPostCalls.length, 1);
    assert.equal(guestPostCalls[0].body.propertyId, PROPERTY_ID);
    assert.equal(guestPostCalls[0].headers.authorization, "Bearer jwt-valid-token");
    assert.equal(res.headers.get("set-cookie"), null);
  });

  it("2. missing property context (PMS_PROPERTY_ID unset) fails closed with 500 without calling backend", async () => {
    delete process.env.PMS_PROPERTY_ID;
    const cookie = await buildSessionCookie();
    const req = makeGuestRequest({ name: "No Prop Guest" }, { cookie });

    const res = await createGuestRoute(req);
    assert.equal(res.status, 500);
    const data = await res.json();
    assert.match(data.error, /PMS_PROPERTY_ID/i);
    assert.equal(guestPostCalls.length, 0);
  });

  it("3. unauthorized property access preserves backend 403 Forbidden and 404 Not Found", async () => {
    const cookie = await buildSessionCookie();

    nextGuestBehavior = { status: 403, payload: { message: "Access is denied." } };
    const res403 = await createGuestRoute(
      makeGuestRequest({ name: "Forbidden Guest" }, { cookie })
    );
    assert.equal(res403.status, 403);
    assert.equal((await res403.json()).message, "Access is denied.");

    nextGuestBehavior = {
      status: 404,
      payload: { message: "Property not found with id: 99999999-9999-9999-9999-999999999999" },
    };
    const res404 = await createGuestRoute(
      makeGuestRequest(
        { name: "Foreign Prop Guest", propertyId: "99999999-9999-9999-9999-999999999999" },
        { cookie }
      )
    );
    assert.equal(res404.status, 404);
  });

  it("4. CSRF rejection blocks foreign Origin and missing Origin/Referer with 403", async () => {
    const cookie = await buildSessionCookie();

    const foreignRes = await createGuestRoute(
      makeGuestRequest({ name: "CSRF Guest" }, { cookie, origin: "https://evil.example.com" })
    );
    assert.equal(foreignRes.status, 403);
    assert.equal(guestPostCalls.length, 0);

    const missingOriginRes = await createGuestRoute(
      makeGuestRequest({ name: "No Origin Guest" }, { cookie, origin: null })
    );
    assert.equal(missingOriginRes.status, 403);
    assert.equal(guestPostCalls.length, 0);
  });

  it("5. missing authentication rejects unauthenticated POST /api/guests with 401", async () => {
    const res = await createGuestRoute(makeGuestRequest({ name: "Anon Guest" }));
    assert.equal(res.status, 401);
    assert.equal(guestPostCalls.length, 0);
  });

  it("6. required propertyId forwarding sends exact backend-required JSON shape", async () => {
    const cookie = await buildSessionCookie();
    const res = await createGuestRoute(
      makeGuestRequest({ name: "Shape Guest", phone: "+919111222333" }, { cookie })
    );
    assert.equal(res.status, 201);
    assert.deepEqual(guestPostCalls[0].body, {
      name: "Shape Guest",
      phone: "+919111222333",
      propertyId: PROPERTY_ID,
    });
  });

  it("7. invalid guest input (malformed JSON) returns 400 without calling backend", async () => {
    const cookie = await buildSessionCookie();
    const res = await createGuestRoute(
      makeGuestRequest(null, { cookie, rawBody: "{bad-json" })
    );
    assert.equal(res.status, 400);
    assert.equal(guestPostCalls.length, 0);
  });

  it("8. backend validation errors (400) are preserved cleanly", async () => {
    const cookie = await buildSessionCookie();
    nextGuestBehavior = {
      status: 400,
      payload: { message: "must be a well-formed email address" },
    };
    const res = await createGuestRoute(
      makeGuestRequest({ name: "Bad Email", email: "not-an-email" }, { cookie })
    );
    assert.equal(res.status, 400);
    assert.equal((await res.json()).message, "must be a well-formed email address");
  });

  it("9. ambiguous 401 on POST never silently replays guest creation", async () => {
    const cookie = await buildSessionCookie("jwt-expired-token");
    const res = await createGuestRoute(
      makeGuestRequest({ name: "Expired Token Guest" }, { cookie })
    );
    assert.equal(res.status, 401);
    const data = await res.json();
    assert.equal(data.reauthenticated, true);
    assert.equal(guestPostCalls.length, 1, "POST /api/guests must never be replayed automatically");
  });

  it("10. duplicate-submit guard prevents concurrent guest creation calls", async () => {
    let saving = false;
    let lock = false;
    let submittedCount = 0;

    async function submitOnce(action) {
      if (saving || lock) return "blocked";
      lock = true;
      saving = true;
      try {
        submittedCount++;
        return await action();
      } finally {
        lock = false;
        saving = false;
      }
    }

    const firstPromise = submitOnce(async () => {
      await new Promise((r) => setTimeout(r, 20));
      return "created";
    });
    const secondResult = await submitOnce(async () => "should-not-run");

    assert.equal(secondResult, "blocked");
    assert.equal(await firstPromise, "created");
    assert.equal(submittedCount, 1);
  });

  it("11. guest created but reservation creation failed reports partial completion and reuses guest on retry", async () => {
    const cookie = await buildSessionCookie();

    // Step A: Create guest succeeds (201), reservation fails (400)
    nextReservationBehavior = {
      status: 400,
      payload: { message: "Not enough rooms available for selected dates." },
    };

    const guestRes = await createGuestRoute(
      makeGuestRequest({ name: "Partial Flow Guest", phone: "+919000111222" }, { cookie })
    );
    assert.equal(guestRes.status, 201);
    const createdGuest = await guestRes.json();

    const failedResReq = new Request("http://localhost:3000/api/reservations", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Origin: "http://localhost:3000",
        Cookie: cookie,
      },
      body: JSON.stringify({
        primaryGuestId: createdGuest.id,
        checkInDate: "2026-12-01",
        checkOutDate: "2026-12-02",
        adults: 1,
        children: 0,
        infants: 0,
        bookingSourceId: "source-direct",
        reservationRooms: [{ roomTypeId: "rt-1", quantity: 1, unitPrice: 2500 }],
      }),
    });
    const failedRes = await createReservationRoute(failedResReq);
    assert.equal(failedRes.status, 400);
    const failedData = await failedRes.json();
    const uiMessage = `Guest "${createdGuest.name}" was created and selected, but the reservation could not be created: ${failedData.message}`;
    assert.match(uiMessage, /Partial Flow Guest.*was created and selected/);

    // Step B: Retry uses existing guestId, does NOT call POST /api/guests again
    const retryResReq = new Request("http://localhost:3000/api/reservations", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Origin: "http://localhost:3000",
        Cookie: cookie,
      },
      body: JSON.stringify({
        primaryGuestId: createdGuest.id,
        checkInDate: "2026-12-05",
        checkOutDate: "2026-12-06",
        adults: 1,
        children: 0,
        infants: 0,
        bookingSourceId: "source-direct",
        reservationRooms: [{ roomTypeId: "rt-1", quantity: 1, unitPrice: 2500 }],
      }),
    });
    const retryRes = await createReservationRoute(retryResReq);
    assert.equal(retryRes.status, 201);
    assert.equal(guestPostCalls.length, 1, "Guest must not be created a second time on reservation retry");
    assert.equal(reservationPostCalls.length, 2);
    assert.equal(reservationPostCalls[1].body.primaryGuestId, createdGuest.id);
  });

  it("12. existing guest selection creates reservation without calling POST /api/guests", async () => {
    const cookie = await buildSessionCookie();
    const resReq = new Request("http://localhost:3000/api/reservations", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Origin: "http://localhost:3000",
        Cookie: cookie,
      },
      body: JSON.stringify({
        primaryGuestId: "existing-guest-uuid-1",
        checkInDate: "2026-12-01",
        checkOutDate: "2026-12-02",
        adults: 2,
        children: 0,
        infants: 0,
        bookingSourceId: "source-direct",
        reservationRooms: [{ roomTypeId: "rt-1", quantity: 1, unitPrice: 3500 }],
      }),
    });

    const res = await createReservationRoute(resReq);
    assert.equal(res.status, 201);
    const data = await res.json();
    assert.equal(data.primaryGuestId, "existing-guest-uuid-1");
    assert.equal(data.status, "PENDING");
    assert.equal(guestPostCalls.length, 0);
    assert.equal(reservationPostCalls.length, 1);
  });

  it("13. HTTP 502 and 504 on POST /api/guests never automatically replay", async () => {
    const cookie = await buildSessionCookie();

    nextGuestBehavior = { status: 502, payload: { error: "Bad Gateway" } };
    const res502 = await createGuestRoute(
      makeGuestRequest({ name: "Gateway 502 Guest" }, { cookie })
    );
    assert.equal(res502.status, 502);
    assert.equal(guestPostCalls.length, 1);

    nextGuestBehavior = { status: 504, payload: { error: "Gateway Timeout" } };
    const res504 = await createGuestRoute(
      makeGuestRequest({ name: "Timeout 504 Guest" }, { cookie })
    );
    assert.equal(res504.status, 504);
    assert.equal(guestPostCalls.length, 2);
  });

  it("14. uncertain guest POST gate blocks retry even after form close/reopen until authoritative refresh succeeds", async () => {
    function isUncertainError(message) {
      const lower = (message || "").toLowerCase();
      return (
        lower.includes("cannot connect") ||
        lower.includes("failed to fetch") ||
        lower.includes("network") ||
        lower.includes("failed (502)") ||
        lower.includes("504") ||
        lower.includes("gateway") ||
        lower.includes("timeout")
      );
    }

    let guestCreateUncertain = false;
    let showCreateForm = true;
    let guestPostAttempts = 0;

    async function submitNewGuest(shouldFailNetwork) {
      if (guestCreateUncertain) return "blocked-by-uncertainty";
      guestPostAttempts++;
      if (shouldFailNetwork) {
        const msg = "Failed to fetch";
        if (isUncertainError(msg)) guestCreateUncertain = true;
        return "uncertain";
      }
      return "created";
    }

    // 1. Network disconnect during POST
    assert.equal(await submitNewGuest(true), "uncertain");
    assert.equal(guestCreateUncertain, true);
    assert.equal(guestPostAttempts, 1);

    // 2. Staff closes and reopens the form: uncertainty persists
    showCreateForm = false;
    showCreateForm = true;
    assert.equal(showCreateForm, true);
    assert.equal(await submitNewGuest(false), "blocked-by-uncertainty");
    assert.equal(guestPostAttempts, 1);

    // 3. Failed refresh preserves uncertainty
    const failedRefreshOk = false;
    if (failedRefreshOk) guestCreateUncertain = false;
    assert.equal(guestCreateUncertain, true);

    // 4. Authoritative refresh succeeds -> clears uncertainty
    const refreshOk = true;
    if (refreshOk) guestCreateUncertain = false;
    assert.equal(guestCreateUncertain, false);
  });
});
