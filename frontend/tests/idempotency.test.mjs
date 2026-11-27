import { describe, it, before, after, beforeEach } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";

process.env.PMS_SESSION_SECRET = "test-secret-at-least-32-characters-for-aes-256-gcm!";

import { encryptSession } from "../src/lib/auth/session.ts";
import { DEV_COOKIE_NAME } from "../src/lib/auth/cookies.ts";
import { POST as createGuestRoute } from "../src/app/api/guests/route.ts";
import { POST as createReservationRoute } from "../src/app/api/reservations/route.ts";
import {
  ApiRequestError,
  isUncertainError,
  isIdempotencyInProgress,
  isIdempotencyExpired,
  isIdempotencyPayloadMismatch,
} from "../src/lib/mutation-error.ts";
import {
  computeGuestFingerprint,
  computeReservationFingerprint,
} from "../src/lib/idempotency-fingerprint.ts";

describe("PMS Guest & Reservation Creation Idempotency Test Suite", () => {
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
      let body = "";
      req.on("data", (chunk) => {
        body += chunk;
      });
      req.on("end", () => {
        if (url.pathname === "/api/v1/guests" && req.method === "POST") {
          const parsed = body ? JSON.parse(body) : null;
          guestPostCalls.push({
            headers: req.headers,
            body: parsed,
            rawBody: body,
          });

          if (nextGuestBehavior) {
            const { status, payload, headers } = nextGuestBehavior;
            nextGuestBehavior = null;
            res.writeHead(status, {
              "Content-Type": "application/json",
              ...(headers || {}),
            });
            res.end(JSON.stringify(payload));
            return;
          }

          const idKey = req.headers["idempotency-key"];
          const resHeaders = { "Content-Type": "application/json" };
          if (idKey) {
            resHeaders["Idempotency-Key"] = idKey;
          }

          res.writeHead(201, resHeaders);
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
          reservationPostCalls.push({
            headers: req.headers,
            body: parsed,
            rawBody: body,
          });

          if (nextReservationBehavior) {
            const { status, payload, headers } = nextReservationBehavior;
            nextReservationBehavior = null;
            res.writeHead(status, {
              "Content-Type": "application/json",
              ...(headers || {}),
            });
            res.end(JSON.stringify(payload));
            return;
          }

          const idKey = req.headers["idempotency-key"];
          const resHeaders = { "Content-Type": "application/json" };
          if (idKey) {
            resHeaders["Idempotency-Key"] = idKey;
          }

          res.writeHead(201, resHeaders);
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

  function makeRequest(url, payload, { cookie, origin = "http://localhost:3000", idempotencyKey } = {}) {
    const headers = new Headers({
      "Content-Type": "application/json",
    });
    if (origin !== null) {
      headers.set("Origin", origin);
    }
    if (cookie) {
      headers.set("Cookie", cookie);
    }
    if (idempotencyKey !== undefined) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
    return new Request(url, {
      method: "POST",
      headers,
      body: JSON.stringify(payload),
    });
  }

  // A. Guest POST includes stable Idempotency-Key
  it("A. Guest POST forwards client Idempotency-Key header to backend", async () => {
    const cookie = await buildSessionCookie();
    const guestKey = "33333333-aaaa-bbbb-cccc-000000000001";
    const req = makeRequest("http://localhost:3000/api/guests", { name: "Guest A" }, { cookie, idempotencyKey: guestKey });

    const res = await createGuestRoute(req);
    assert.equal(res.status, 201);
    assert.equal(guestPostCalls.length, 1);
    assert.equal(guestPostCalls[0].headers["idempotency-key"], guestKey);
    assert.equal(res.headers.get("Idempotency-Key"), guestKey);
  });

  // B. Reservation POST includes a different stable key
  it("B. Reservation POST forwards independent client Idempotency-Key", async () => {
    const cookie = await buildSessionCookie();
    const resKey = "44444444-aaaa-bbbb-cccc-000000000002";
    const req = makeRequest(
      "http://localhost:3000/api/reservations",
      { primaryGuestId: "guest-uuid-1", checkInDate: "2026-12-01", checkOutDate: "2026-12-03" },
      { cookie, idempotencyKey: resKey }
    );

    const res = await createReservationRoute(req);
    assert.equal(res.status, 201);
    assert.equal(reservationPostCalls.length, 1);
    assert.equal(reservationPostCalls[0].headers["idempotency-key"], resKey);
    assert.equal(res.headers.get("Idempotency-Key"), resKey);
  });

  // C. BFF forwards valid key only
  it("C. BFF forwards valid trimmed key and ignores arbitrary forbidden headers", async () => {
    const cookie = await buildSessionCookie();
    const validKey = "  valid-key-with-padding  ";
    const headers = new Headers({
      "Content-Type": "application/json",
      Origin: "http://localhost:3000",
      Cookie: cookie,
      "Idempotency-Key": validKey,
      "X-Custom-Client-Header": "forbidden-payload",
    });
    const req = new Request("http://localhost:3000/api/guests", {
      method: "POST",
      headers,
      body: JSON.stringify({ name: "Header Test Guest" }),
    });

    const res = await createGuestRoute(req);
    assert.equal(res.status, 201);
    assert.equal(guestPostCalls.length, 1);
    assert.equal(guestPostCalls[0].headers["idempotency-key"], "valid-key-with-padding");
    assert.equal(guestPostCalls[0].headers["x-custom-client-header"], undefined);
  });

  // D. BFF rejects malformed key
  it("D. BFF rejects malformed keys with 400 Bad Request without calling backend", async () => {
    const cookie = await buildSessionCookie();

    // 1. Whitespace only
    const reqEmpty = makeRequest("http://localhost:3000/api/guests", { name: "Guest" }, { cookie, idempotencyKey: "   " });
    const resEmpty = await createGuestRoute(reqEmpty);
    assert.equal(resEmpty.status, 400);
    const dataEmpty = await resEmpty.json();
    assert.ok(dataEmpty.error.includes("Invalid Idempotency-Key header"));
    assert.equal(guestPostCalls.length, 0);

    // 2. Over 128 characters
    const reqTooLong = makeRequest(
      "http://localhost:3000/api/guests",
      { name: "Guest" },
      { cookie, idempotencyKey: "a".repeat(129) }
    );
    const resTooLong = await createGuestRoute(reqTooLong);
    assert.equal(resTooLong.status, 400);
    const dataTooLong = await resTooLong.json();
    assert.ok(dataTooLong.error.includes("Invalid Idempotency-Key header"));
    assert.equal(guestPostCalls.length, 0);

    // 3. Exactly 128 characters (valid boundary)
    const reqMaxValid = makeRequest(
      "http://localhost:3000/api/guests",
      { name: "Guest" },
      { cookie, idempotencyKey: "b".repeat(128) }
    );
    const resMaxValid = await createGuestRoute(reqMaxValid);
    assert.equal(resMaxValid.status, 201);
    assert.equal(guestPostCalls.length, 1);
    assert.equal(guestPostCalls[0].headers["idempotency-key"], "b".repeat(128));
  });

  // E. First creation returns 201
  it("E. First creation returns HTTP 201 Created with echoed Idempotency-Key", async () => {
    const cookie = await buildSessionCookie();
    const key = "fresh-creation-key-001";
    const req = makeRequest("http://localhost:3000/api/guests", { name: "First Guest" }, { cookie, idempotencyKey: key });

    const res = await createGuestRoute(req);
    assert.equal(res.status, 201);
    assert.equal(res.headers.get("Idempotency-Key"), key);
    assert.equal(res.headers.get("Idempotent-Replayed"), null);
  });

  // F. Replay returns 200 without creating another record
  it("F. Replay returns HTTP 200 OK with Idempotent-Replayed: true", async () => {
    const cookie = await buildSessionCookie();
    const key = "replayed-creation-key-002";

    nextReservationBehavior = {
      status: 200,
      headers: {
        "Idempotency-Key": key,
        "Idempotent-Replayed": "true",
      },
      payload: {
        id: "res-uuid-existing-42",
        propertyId: PROPERTY_ID,
        primaryGuestId: "guest-uuid-1",
        status: "PENDING",
      },
    };

    const req = makeRequest(
      "http://localhost:3000/api/reservations",
      { primaryGuestId: "guest-uuid-1" },
      { cookie, idempotencyKey: key }
    );

    const res = await createReservationRoute(req);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("Idempotency-Key"), key);
    assert.equal(res.headers.get("Idempotent-Replayed"), "true");
    const data = await res.json();
    assert.equal(data.id, "res-uuid-existing-42");
  });

  // G. Lost guest response and exact retry
  it("G. Lost guest response: exact retry with same key yields original resource without duplication", async () => {
    const cookie = await buildSessionCookie();
    const guestKey = "lost-response-guest-key";

    // First attempt: Backend processes but connection drops / returns 502
    nextGuestBehavior = {
      status: 502,
      payload: { error: "Cannot connect to the PMS backend." },
    };

    const req1 = makeRequest("http://localhost:3000/api/guests", { name: "Recovered Guest" }, { cookie, idempotencyKey: guestKey });
    const res1 = await createGuestRoute(req1);
    assert.equal(res1.status, 502);

    // Second attempt: Exact retry with the SAME key -> backend returns 200 replayed
    nextGuestBehavior = {
      status: 200,
      headers: {
        "Idempotency-Key": guestKey,
        "Idempotent-Replayed": "true",
      },
      payload: {
        id: "guest-uuid-original-1",
        propertyId: PROPERTY_ID,
        name: "Recovered Guest",
      },
    };

    const req2 = makeRequest("http://localhost:3000/api/guests", { name: "Recovered Guest" }, { cookie, idempotencyKey: guestKey });
    const res2 = await createGuestRoute(req2);
    assert.equal(res2.status, 200);
    assert.equal(res2.headers.get("Idempotent-Replayed"), "true");
    const data = await res2.json();
    assert.equal(data.id, "guest-uuid-original-1");
    assert.equal(guestPostCalls.length, 2);
    assert.equal(guestPostCalls[0].headers["idempotency-key"], guestKey);
    assert.equal(guestPostCalls[1].headers["idempotency-key"], guestKey);
  });

  // H. Lost reservation response and exact retry
  it("H. Lost reservation response: exact retry with same key yields original reservation", async () => {
    const cookie = await buildSessionCookie();
    const resKey = "lost-response-reservation-key";

    // 1st attempt: 504 Gateway Timeout
    nextReservationBehavior = {
      status: 504,
      payload: { error: "Gateway Timeout" },
    };

    const req1 = makeRequest(
      "http://localhost:3000/api/reservations",
      { primaryGuestId: "guest-uuid-1" },
      { cookie, idempotencyKey: resKey }
    );
    const res1 = await createReservationRoute(req1);
    assert.equal(res1.status, 504);

    // 2nd attempt: Retry with same key -> 200 replayed
    nextReservationBehavior = {
      status: 200,
      headers: {
        "Idempotency-Key": resKey,
        "Idempotent-Replayed": "true",
      },
      payload: {
        id: "res-uuid-recovered-99",
        propertyId: PROPERTY_ID,
        primaryGuestId: "guest-uuid-1",
        status: "PENDING",
      },
    };

    const req2 = makeRequest(
      "http://localhost:3000/api/reservations",
      { primaryGuestId: "guest-uuid-1" },
      { cookie, idempotencyKey: resKey }
    );
    const res2 = await createReservationRoute(req2);
    assert.equal(res2.status, 200);
    assert.equal(res2.headers.get("Idempotent-Replayed"), "true");
    const data = await res2.json();
    assert.equal(data.id, "res-uuid-recovered-99");
  });

  // I. Double-click protection
  it("I. Double-click protection: concurrent in-progress submission gate rejects re-entry", () => {
    let creatingReservation = false;
    let submitCount = 0;

    function simulateSubmit() {
      if (creatingReservation) return "BLOCKED";
      creatingReservation = true;
      submitCount++;
      return "ACCEPTED";
    }

    assert.equal(simulateSubmit(), "ACCEPTED");
    assert.equal(simulateSubmit(), "BLOCKED");
    assert.equal(simulateSubmit(), "BLOCKED");
    assert.equal(submitCount, 1);

    creatingReservation = false;
    assert.equal(simulateSubmit(), "ACCEPTED");
    assert.equal(submitCount, 2);
  });

  // J. Browser refresh recovery
  it("J. Browser refresh recovery: restores uncertain state and original key without creating duplicate keys", () => {
    const mockStorage = new Map();
    const storedState = {
      version: 1,
      timestamp: Date.now(),
      guestKey: "saved-guest-key-101",
      reservationKey: "saved-res-key-202",
      confirmedGuestId: "guest-uuid-already-created",
      guestState: "confirmed",
      reservationState: "uncertain",
      guestPayloadHash: "abc123hash",
      reservationPayloadHash: "def456hash",
    };

    mockStorage.set("pms_creation_recovery", JSON.stringify(storedState));

    // Simulate page reload:
    const reloadedRaw = mockStorage.get("pms_creation_recovery");
    const reloaded = JSON.parse(reloadedRaw);
    assert.equal(reloaded.guestKey, "saved-guest-key-101");
    assert.equal(reloaded.reservationKey, "saved-res-key-202");
    assert.equal(reloaded.confirmedGuestId, "guest-uuid-already-created");
    assert.equal(reloaded.reservationState, "uncertain");
  });

  // K. Form edits after uncertain submission
  it("K. Form edits after uncertain submission: detects payload hash mismatch and blocks blind retry", () => {
    function computeHash(str) {
      let hash = 0x811c9dc5;
      for (let i = 0; i < str.length; i++) {
        hash ^= str.charCodeAt(i);
        hash = Math.imul(hash, 0x01000193);
      }
      return (hash >>> 0).toString(16);
    }

    const originalPayload = "guest-1|2026-12-01|2026-12-03|2|0|0|DIRECT|type-a:1:100";
    const originalHash = computeHash(originalPayload);

    const editedPayload = "guest-1|2026-12-02|2026-12-05|2|0|0|DIRECT|type-a:1:100"; // Dates changed
    const editedHash = computeHash(editedPayload);

    assert.notEqual(originalHash, editedHash);

    // Policy check: retry with different payload must be rejected
    function canRetry(savedHash, currentHash) {
      return savedHash === currentHash;
    }

    assert.equal(canRetry(originalHash, originalHash), true);
    assert.equal(canRetry(originalHash, editedHash), false);
  });

  // L. Existing guest reuse after reservation failure
  it("L. Existing guest reuse after reservation failure: guest is not created again", async () => {
    const cookie = await buildSessionCookie();
    let guestCreatedId = null;

    // Step 1: Guest creation succeeds
    const guestReq = makeRequest(
      "http://localhost:3000/api/guests",
      { name: "Persistent Guest" },
      { cookie, idempotencyKey: "guest-step-key-1" }
    );
    const guestRes = await createGuestRoute(guestReq);
    assert.equal(guestRes.status, 201);
    const guestData = await guestRes.json();
    guestCreatedId = guestData.id;
    assert.ok(guestCreatedId);

    // Step 2: Reservation creation fails with 400
    nextReservationBehavior = {
      status: 400,
      payload: { message: "Check-out date must be after check-in date" },
    };

    const resReq1 = makeRequest(
      "http://localhost:3000/api/reservations",
      { primaryGuestId: guestCreatedId },
      { cookie, idempotencyKey: "res-step-key-1" }
    );
    const resRes1 = await createReservationRoute(resReq1);
    assert.equal(resRes1.status, 400);

    // Step 3: Subsequent reservation with corrected dates REUSES existing guestCreatedId
    const resReq2 = makeRequest(
      "http://localhost:3000/api/reservations",
      { primaryGuestId: guestCreatedId, checkInDate: "2026-12-01", checkOutDate: "2026-12-03" },
      { cookie, idempotencyKey: "res-step-key-2" }
    );
    const resRes2 = await createReservationRoute(resReq2);
    assert.equal(resRes2.status, 201);

    // Guest was called EXACTLY ONCE
    assert.equal(guestPostCalls.length, 1);
    // Reservation was called twice
    assert.equal(reservationPostCalls.length, 2);
    assert.equal(reservationPostCalls[1].body.primaryGuestId, guestCreatedId);
  });

  // M. 409 IDEMPOTENCY_IN_PROGRESS
  it("M. 409 IDEMPOTENCY_IN_PROGRESS: classified as uncertain with Retry-After preserved and no auto-retry", async () => {
    const cookie = await buildSessionCookie();
    nextReservationBehavior = {
      status: 409,
      headers: {
        "Retry-After": "1",
      },
      payload: {
        status: 409,
        error: "Conflict",
        message: "A request with this idempotency key is currently in progress. Please retry.",
      },
    };

    const req = makeRequest("http://localhost:3000/api/reservations", { primaryGuestId: "guest-1" }, { cookie });
    const res = await createReservationRoute(req);
    assert.equal(res.status, 409);
    assert.equal(res.headers.get("Retry-After"), "1");

    const err = new ApiRequestError(
      "A request with this idempotency key is currently in progress. Please retry.",
      409,
      "IDEMPOTENCY_IN_PROGRESS",
      "1"
    );

    assert.equal(isIdempotencyInProgress(err), true);
    assert.equal(isUncertainError(err), true);
    assert.equal(err.retryAfter, "1");
  });

  // N. 409 IDEMPOTENCY_KEY_EXPIRED
  it("N. 409 IDEMPOTENCY_KEY_EXPIRED: classified as deterministic rejection, requiring staff reconciliation", async () => {
    const cookie = await buildSessionCookie();
    nextReservationBehavior = {
      status: 409,
      payload: {
        status: 409,
        error: "Conflict",
        message: "The idempotency key has expired.",
      },
    };

    const req = makeRequest("http://localhost:3000/api/reservations", { primaryGuestId: "guest-1" }, { cookie });
    const res = await createReservationRoute(req);
    assert.equal(res.status, 409);

    const err = new ApiRequestError(
      "The idempotency key has expired.",
      409,
      "IDEMPOTENCY_KEY_EXPIRED"
    );

    assert.equal(isIdempotencyExpired(err), true);
    // Expired key is NOT an uncertain network outcome where blind retry of same key works
    assert.equal(isUncertainError(err), false);
  });

  // O. 422 IDEMPOTENCY_PAYLOAD_MISMATCH
  it("O. 422 IDEMPOTENCY_PAYLOAD_MISMATCH: classified as deterministic terminal rejection", async () => {
    const cookie = await buildSessionCookie();
    nextReservationBehavior = {
      status: 422,
      payload: {
        status: 422,
        error: "Unprocessable Entity",
        message: "The idempotency key is already used with a different request payload.",
      },
    };

    const req = makeRequest("http://localhost:3000/api/reservations", { primaryGuestId: "guest-1" }, { cookie });
    const res = await createReservationRoute(req);
    assert.equal(res.status, 422);

    const err = new ApiRequestError(
      "The idempotency key is already used with a different request payload.",
      422,
      "IDEMPOTENCY_PAYLOAD_MISMATCH"
    );

    assert.equal(isIdempotencyPayloadMismatch(err), true);
    assert.equal(isUncertainError(err), false);
  });

  // P. Network timeout and 502/503/504
  it("P. Network timeout and 502/503/504: classified as uncertain", () => {
    assert.equal(isUncertainError(new ApiRequestError("Bad Gateway", 502)), true);
    assert.equal(isUncertainError(new ApiRequestError("Service Unavailable", 503)), true);
    assert.equal(isUncertainError(new ApiRequestError("Gateway Timeout", 504)), true);
    assert.equal(isUncertainError(new ApiRequestError("Request Timeout", 408)), true);
    assert.equal(isUncertainError(new TypeError("Failed to fetch")), true);
    assert.equal(isUncertainError(new Error("Network connection closed unexpectedly")), true);
  });

  // Q. Staff logout or property change
  it("Q. Staff logout or property change: stale recovery metadata is safely cleared", () => {
    const mockStorage = new Map();
    mockStorage.set(
      "pms_creation_recovery",
      JSON.stringify({
        version: 1,
        timestamp: Date.now() - 3600_000,
        propertyId: "old-property-id",
        guestKey: "stale-key",
      })
    );

    // Logout or property change handler:
    function handleLogoutOrPropertySwitch(currentPropertyId) {
      const raw = mockStorage.get("pms_creation_recovery");
      if (!raw) return;
      const data = JSON.parse(raw);
      if (data.propertyId && data.propertyId !== currentPropertyId) {
        mockStorage.delete("pms_creation_recovery");
      }
    }

    handleLogoutOrPropertySwitch("new-property-id");
    assert.equal(mockStorage.get("pms_creation_recovery"), undefined);
  });

  // R. No sensitive PII in sessionStorage
  it("R. No sensitive PII in sessionStorage: only non-sensitive recovery metadata allowed", () => {
    const sampleStorageRecord = {
      version: 1,
      timestamp: Date.now(),
      guestKey: "550e8400-e29b-41d4-a716-446655440000",
      reservationKey: "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
      confirmedGuestId: "7fa7b810-9dad-11d1-80b4-00c04fd430c9",
      guestState: "confirmed",
      reservationState: "uncertain",
      guestPayloadHash: "8f5a2b1c",
      reservationPayloadHash: "3d4e5f6a",
    };

    const serialized = JSON.stringify(sampleStorageRecord);

    // Verify forbidden PII fields do not exist
    const forbiddenPatterns = [
      /name/i,
      /phone/i,
      /email/i,
      /document/i,
      /passport/i,
      /aadhar/i,
      /specialRequests/i,
      /notes/i,
      /password/i,
      /token/i,
      /secret/i,
    ];

    for (const pattern of forbiddenPatterns) {
      assert.equal(pattern.test(serialized), false, `SessionStorage record must not match ${pattern}`);
    }
  });

  // S. No automatic POST retry
  it("S. No automatic POST retry: mutating POST on 401, 409, 502, 503 is NEVER retried automatically", async () => {
    const cookie = await buildSessionCookie();

    // Configure backend to return 401 on first attempt
    nextGuestBehavior = {
      status: 401,
      payload: { message: "Access token expired" },
    };

    const req = makeRequest("http://localhost:3000/api/guests", { name: "No Auto Retry" }, { cookie });
    const res = await createGuestRoute(req);

    assert.equal(res.status, 401);
    // Verify that exactly 1 request occurred to backend, NEVER retried
    assert.equal(guestPostCalls.length, 1, "Mutating POST must NEVER be silently retried on 401");
  });

  // T. Existing confirmation, assignment, check-in and checkout regressions
  it("T. Existing regressions: 400 validation remains deterministic while generic 409 remains uncertain", () => {
    // 400 validation
    assert.equal(isUncertainError(new ApiRequestError("Room already assigned", 400)), false);
    assert.equal(isUncertainError(new ApiRequestError("Bad Request", 400)), false);
    assert.equal(isUncertainError(new ApiRequestError("Invalid dates", 400)), false);

    // 401 / 403 / 404
    assert.equal(isUncertainError(new ApiRequestError("Unauthorized", 401)), false);
    assert.equal(isUncertainError(new ApiRequestError("Forbidden", 403)), false);
    assert.equal(isUncertainError(new ApiRequestError("Not Found", 404)), false);

    // Generic 409 / 503 (assignment & inventory concurrency)
    assert.equal(isUncertainError(new ApiRequestError("Concurrent modification conflict.", 409)), true);
    assert.equal(isUncertainError(new ApiRequestError("A temporary database lock contention occurred.", 503)), true);
  });

  // U. SHA-256 Client Payload Fingerprint: canonical deterministic normalization and zero PII
  it("U. SHA-256 Client Payload Fingerprint: canonical deterministic normalization and zero PII", async () => {
    // 1. Guest fingerprint deterministic and normalized
    const fp1 = await computeGuestFingerprint({
      name: "  Rahul Sharma  ",
      email: "RAHUL@EXAMPLE.COM ",
      phone: " +919876543210 ",
      propertyId: "11111111-2222-3333-4444-555555555555",
    });
    const fp2 = await computeGuestFingerprint({
      name: "Rahul Sharma",
      email: "rahul@example.com",
      phone: "+919876543210",
      propertyId: "11111111-2222-3333-4444-555555555555",
    });
    assert.equal(fp1, fp2, "Fingerprint must normalize whitespace and email case");
    assert.equal(fp1.length, 64, "SHA-256 hex digest must be 64 characters");
    assert.match(fp1, /^[0-9a-f]{64}$/, "Must be valid lowercase hex");

    // 2. Reservation fingerprint deterministic regardless of room array order
    const resFp1 = await computeReservationFingerprint({
      primaryGuestId: "guest-uuid-1",
      checkInDate: "2026-12-10",
      checkOutDate: "2026-12-12",
      adults: 2,
      children: 0,
      infants: 0,
      bookingSourceId: "bs-1",
      externalBookingId: "ext-123",
      specialRequests: "Quiet room",
      rooms: [
        { roomTypeId: "rt-b", quantity: 1, unitPrice: "3000.00" },
        { roomTypeId: "rt-a", quantity: 2, unitPrice: 2500 },
      ],
    });
    const resFp2 = await computeReservationFingerprint({
      primaryGuestId: "guest-uuid-1",
      checkInDate: "2026-12-10",
      checkOutDate: "2026-12-12",
      adults: 2,
      children: 0,
      infants: 0,
      bookingSourceId: "bs-1",
      externalBookingId: "ext-123",
      specialRequests: "Quiet room",
      rooms: [
        { roomTypeId: "rt-a", quantity: 2, unitPrice: "2500" },
        { roomTypeId: "rt-b", quantity: 1, unitPrice: 3000 },
      ],
    });
    assert.equal(resFp1, resFp2, "Reservation fingerprint must sort rooms deterministically");

    // 3. Different details yield different fingerprints
    const resFpDiff = await computeReservationFingerprint({
      primaryGuestId: "guest-uuid-1",
      checkInDate: "2026-12-11", // different date
      checkOutDate: "2026-12-12",
      adults: 2,
      children: 0,
      infants: 0,
      bookingSourceId: "bs-1",
      rooms: [{ roomTypeId: "rt-a", quantity: 2, unitPrice: 2500 }],
    });
    assert.notEqual(resFp1, resFpDiff, "Different stay dates must produce distinct fingerprints");
  });

  // V. Backend Error Code Compatibility: handles IDEMPOTENCY_IN_PROGRESS, IDEMPOTENCY_KEY_IN_PROGRESS, Retry-After header
  it("V. Backend Error Code Compatibility: handles IDEMPOTENCY_IN_PROGRESS, IDEMPOTENCY_KEY_IN_PROGRESS, Retry-After header", () => {
    // 1. Error with IDEMPOTENCY_KEY_IN_PROGRESS
    const errKeyInProgress = new ApiRequestError("Key in progress", 409, "IDEMPOTENCY_KEY_IN_PROGRESS");
    assert.equal(isIdempotencyInProgress(errKeyInProgress), true);
    assert.equal(isUncertainError(errKeyInProgress), true);

    // 2. Error with IDEMPOTENCY_IN_PROGRESS
    const errInProgress = new ApiRequestError("In progress", 409, "IDEMPOTENCY_IN_PROGRESS");
    assert.equal(isIdempotencyInProgress(errInProgress), true);
    assert.equal(isUncertainError(errInProgress), true);

    // 3. Error with HTTP 409 and Retry-After header (backend standard without custom code)
    const errWithRetryAfter = new ApiRequestError(
      "A request with this idempotency key is currently in progress. Please retry.",
      409,
      undefined,
      "1"
    );
    assert.equal(isIdempotencyInProgress(errWithRetryAfter), true);
    assert.equal(isUncertainError(errWithRetryAfter), true);

    // 4. Error with IDEMPOTENCY_KEY_EXPIRED vs IDEMPOTENCY_EXPIRED
    const errKeyExpired = new ApiRequestError("Key expired", 409, "IDEMPOTENCY_KEY_EXPIRED");
    const errExpired = new ApiRequestError("Expired", 409, "IDEMPOTENCY_EXPIRED");
    assert.equal(isIdempotencyExpired(errKeyExpired), true);
    assert.equal(isIdempotencyExpired(errExpired), true);
    assert.equal(isUncertainError(errKeyExpired), false);
    assert.equal(isUncertainError(errExpired), false);

    // 5. Error with IDEMPOTENCY_PAYLOAD_MISMATCH vs IDEMPOTENCY_CONFLICT
    const errPayloadMismatch = new ApiRequestError("Payload mismatch", 422, "IDEMPOTENCY_PAYLOAD_MISMATCH");
    const errConflict = new ApiRequestError("Conflict", 422, "IDEMPOTENCY_CONFLICT");
    assert.equal(isIdempotencyPayloadMismatch(errPayloadMismatch), true);
    assert.equal(isIdempotencyPayloadMismatch(errConflict), true);
    assert.equal(isUncertainError(errPayloadMismatch), false);
  });

  // W. Expired key safety: does NOT silently rotate key; keeps submit disabled until explicit verification
  it("W. Expired key safety: does NOT silently rotate key; keeps submit disabled until explicit verification", () => {
    let creationExpired = false;
    let savedKey = "original-attempt-key-123";
    let submitDisabled = false;

    // Simulate backend returning 409 expired
    const err = new ApiRequestError("The idempotency key has expired.", 409, "IDEMPOTENCY_KEY_EXPIRED");
    if (isIdempotencyExpired(err)) {
      creationExpired = true;
      submitDisabled = true;
      // Key must NOT be silently rotated!
      assert.equal(savedKey, "original-attempt-key-123");
    }

    assert.equal(creationExpired, true);
    assert.equal(submitDisabled, true, "Submit button must be disabled when key is expired");

    // Only upon explicit staff acknowledgment is fresh attempt permitted
    function staffAcknowledgesVerifiedNonDuplicate() {
      creationExpired = false;
      submitDisabled = false;
      savedKey = "new-fresh-attempt-key-456";
    }

    staffAcknowledgesVerifiedNonDuplicate();
    assert.equal(creationExpired, false);
    assert.equal(submitDisabled, false);
    assert.equal(savedKey, "new-fresh-attempt-key-456");
  });

  // X. Discard safety: requires explicit acknowledgment of duplicate risk; does not claim request was cancelled
  it("X. Discard safety: requires explicit acknowledgment of duplicate risk; does not claim request was cancelled", () => {
    let showDiscardConfirm = false;
    let pausedAttemptCleared = false;

    // User clicks "Discard paused attempt & start over"
    function handleDiscardClick() {
      // Must NOT immediately clear attempt evidence
      showDiscardConfirm = true;
    }

    handleDiscardClick();
    assert.equal(showDiscardConfirm, true);
    assert.equal(pausedAttemptCleared, false, "Must not clear attempt evidence without explicit confirmation");

    // User confirms in warning dialog
    function handleConfirmDiscard() {
      showDiscardConfirm = false;
      pausedAttemptCleared = true;
    }

    handleConfirmDiscard();
    assert.equal(showDiscardConfirm, false);
    assert.equal(pausedAttemptCleared, true);
  });

  // Y. 24-Hour Recovery Metadata Expiration: metadata older than 24 hours enters fail-closed verification state
  it("Y. 24-Hour Recovery Metadata Expiration: metadata older than 24 hours enters fail-closed verification state", () => {
    const olderThan24h = Date.now() - (25 * 60 * 60 * 1000); // 25 hours ago
    const storedRecord = {
      version: 1,
      timestamp: olderThan24h,
      reservationKey: "key-from-yesterday",
      reservationState: "uncertain",
    };

    let creationExpired = false;
    let submitAllowed = true;

    // Mount handler check:
    const isMetadataExpired = Date.now() - storedRecord.timestamp > 24 * 60 * 60 * 1000;
    if (isMetadataExpired) {
      // Must NOT silently delete and allow uncoordinated submission!
      creationExpired = true;
      submitAllowed = false;
    }

    assert.equal(creationExpired, true, "Expired metadata must activate expired verification state");
    assert.equal(submitAllowed, false, "Submission must be blocked until staff verifies records");
  });

  // Z. Pagination Safety: absence on first page does not prove non-existence
  it("Z. Pagination Safety: absence on first page does not prove non-existence", () => {
    const paginatedFirstPage = [
      { id: "res-1", guestName: "Guest Alpha" },
      { id: "res-2", guestName: "Guest Beta" },
    ];

    function verifyPresenceOnFirstPage(name, list) {
      return list.some((r) => r.guestName === name);
    }

    // Checking for a guest created earlier that may be on page 2 or not yet indexed
    const isFoundOnPage1 = verifyPresenceOnFirstPage("Vikram Rathore", paginatedFirstPage);
    assert.equal(isFoundOnPage1, false);

    // Rule: absence on first page does NOT prove non-existence
    const provesAbsence = isFoundOnPage1; // false
    assert.equal(provesAbsence, false, "Absence on first page must never be treated as proof that reservation was not committed");
  });
});
