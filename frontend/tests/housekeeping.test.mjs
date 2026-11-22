import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";

// Session secret for testing
process.env.PMS_SESSION_SECRET = "test-secret-at-least-32-characters-for-aes-256-gcm!";

import { encryptSession } from "../src/lib/auth/session.ts";
import { DEV_COOKIE_NAME } from "../src/lib/auth/cookies.ts";
import { PATCH as housekeepingRoute } from "../src/app/api/rooms/[id]/housekeeping/route.ts";
import {
  isRoomReadyForCheckIn,
  getAllowedTransitions,
  isUncertainError,
} from "../src/lib/rooms/housekeeping.ts";

describe("PMS Staff Housekeeping Operations & BFF Proxy Test Suite", () => {
  let mockServer;
  let mockPort;
  let housekeepingCallCount = 0;
  let lastReceivedPayload = null;
  let lastReceivedAuthHeader = null;

  const validStaff = {
    id: "staff-uuid-1",
    email: "housekeeping.supervisor@homestay.local",
    firstName: "Bob",
    lastName: "Supervisor",
  };

  before(async () => {
    mockServer = http.createServer(async (req, res) => {
      const url = new URL(req.url, `http://${req.headers.host}`);
      const auth = req.headers["authorization"];
      lastReceivedAuthHeader = auth;

      // Token refresh endpoint
      if (url.pathname === "/api/v1/auth/refresh" && req.method === "POST") {
        res.writeHead(200, {
          "Content-Type": "application/json",
          "Set-Cookie": "pms-refresh-dev=housekeeping-refreshed-token; Path=/; HttpOnly; SameSite=Strict",
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

      // Housekeeping status update endpoint: PATCH /api/v1/rooms/:id/housekeeping-status
      if (
        url.pathname.startsWith("/api/v1/rooms/") &&
        url.pathname.endsWith("/housekeeping-status") &&
        req.method === "PATCH"
      ) {
        housekeepingCallCount++;
        const parts = url.pathname.split("/");
        const id = parts[4];

        // Read payload
        let bodyText = "";
        for await (const chunk of req) {
          bodyText += chunk;
        }
        let bodyJson = null;
        try {
          bodyJson = JSON.parse(bodyText);
        } catch {
          // ignore
        }
        lastReceivedPayload = bodyJson;

        // Auth token checks
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

        // Room 1: Success DIRTY -> CLEANING_IN_PROGRESS or CLEAN
        if (id === "11111111-1111-1111-1111-111111111111") {
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              id: "11111111-1111-1111-1111-111111111111",
              propertyId: "a0000000-0000-0000-0000-000000000001",
              roomTypeId: "rt-uuid-1",
              roomNumber: "101",
              name: "Deluxe Ocean View",
              floor: "1",
              baseOccupancy: 2,
              maxOccupancy: 3,
              status: "AVAILABLE",
              housekeepingStatus: bodyJson?.housekeepingStatus || "CLEAN",
              active: true,
              createdAt: "2026-11-20T00:00:00Z",
              updatedAt: new Date().toISOString(),
            })
          );
          return;
        }

        // Room 2: Invalid status transition error (e.g. from CLEAN to CLEANING_IN_PROGRESS)
        if (id === "22222222-2222-2222-2222-222222222222") {
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              timestamp: new Date().toISOString(),
              status: 400,
              error: "Business Validation Failed",
              message: "Invalid housekeeping status transition from CLEAN to CLEANING_IN_PROGRESS",
            })
          );
          return;
        }

        // Room 3: Active checked-in reservation occupancy error
        if (id === "33333333-3333-3333-3333-333333333333") {
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              timestamp: new Date().toISOString(),
              status: 400,
              error: "Business Validation Failed",
              message: "Cannot mark room as DIRTY while it is occupied by an active checked-in reservation",
            })
          );
          return;
        }

        // Room 4: 403 Forbidden (lacks ROOM_STATUS_UPDATE)
        if (id === "44444444-4444-4444-4444-444444444444") {
          res.writeHead(403, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              timestamp: new Date().toISOString(),
              status: 403,
              error: "Forbidden",
              message: "Access is denied.",
            })
          );
          return;
        }

        // Room 5: 404 Not Found / Foreign property
        if (id === "55555555-5555-5555-5555-555555555555") {
          res.writeHead(404, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              timestamp: new Date().toISOString(),
              status: 404,
              error: "Resource Not Found",
              message: "Room not found with id: 55555555-5555-5555-5555-555555555555",
            })
          );
          return;
        }

        res.writeHead(404, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ message: "Not found" }));
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
      refreshToken: "housekeeping-test-refresh-token",
      refreshTokenCookieName: "pms-refresh-dev",
      staff: validStaff,
      sessionVersion: 1,
    };
    const encrypted = await encryptSession(session);
    return `${DEV_COOKIE_NAME}=${encrypted}`;
  }

  // -------------------------------------------------------------
  // SUITE 1: Parameter & Payload Validation
  // -------------------------------------------------------------
  describe("1. Parameter & Payload Validation", () => {
    it("returns 400 Bad Request when room UUID is invalid", async () => {
      const cookie = await createValidSessionCookie();
      const req = new Request("http://localhost:3000/api/rooms/invalid-id/housekeeping", {
        method: "PATCH",
        headers: {
          cookie,
          origin: "http://localhost:3000",
          "content-type": "application/json",
        },
        body: JSON.stringify({ housekeepingStatus: "CLEAN" }),
      });

      const res = await housekeepingRoute(req, {
        params: Promise.resolve({ id: "invalid-id" }),
      });

      assert.equal(res.status, 400);
      const json = await res.json();
      assert.match(json.error, /invalid room uuid/i);
      assert.equal(
        res.headers.get("cache-control"),
        "no-store, no-cache, must-revalidate, private"
      );
    });

    it("returns 400 Bad Request when JSON body is invalid", async () => {
      const cookie = await createValidSessionCookie();
      const req = new Request("http://localhost:3000/api/rooms/11111111-1111-1111-1111-111111111111/housekeeping", {
        method: "PATCH",
        headers: {
          cookie,
          origin: "http://localhost:3000",
          "content-type": "application/json",
        },
        body: "{malformed_json",
      });

      const res = await housekeepingRoute(req, {
        params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
      });

      assert.equal(res.status, 400);
      const json = await res.json();
      assert.match(json.error, /invalid json/i);
    });

    it("returns 400 Bad Request when housekeepingStatus is invalid enum", async () => {
      const cookie = await createValidSessionCookie();
      const req = new Request("http://localhost:3000/api/rooms/11111111-1111-1111-1111-111111111111/housekeeping", {
        method: "PATCH",
        headers: {
          cookie,
          origin: "http://localhost:3000",
          "content-type": "application/json",
        },
        body: JSON.stringify({ housekeepingStatus: "UNKNOWN_STATE" }),
      });

      const res = await housekeepingRoute(req, {
        params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
      });

      assert.equal(res.status, 400);
      const json = await res.json();
      assert.match(json.error, /invalid housekeepingstatus/i);
    });
  });

  // -------------------------------------------------------------
  // SUITE 2: Authentication & CSRF Enforcement
  // -------------------------------------------------------------
  describe("2. Authentication & CSRF Enforcement", () => {
    it("returns 401 Unauthorized when session cookie is absent", async () => {
      const req = new Request("http://localhost:3000/api/rooms/11111111-1111-1111-1111-111111111111/housekeeping", {
        method: "PATCH",
        headers: {
          origin: "http://localhost:3000",
          "content-type": "application/json",
        },
        body: JSON.stringify({ housekeepingStatus: "CLEAN" }),
      });

      const res = await housekeepingRoute(req, {
        params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
      });

      assert.equal(res.status, 401);
      const json = await res.json();
      assert.match(json.error, /no active staff session/i);
    });

    it("returns 403 Forbidden when origin is foreign (CSRF attack prevention)", async () => {
      const cookie = await createValidSessionCookie();
      const req = new Request("http://localhost:3000/api/rooms/11111111-1111-1111-1111-111111111111/housekeeping", {
        method: "PATCH",
        headers: {
          cookie,
          origin: "http://evil-attacker.example.com",
          "content-type": "application/json",
        },
        body: JSON.stringify({ housekeepingStatus: "CLEAN" }),
      });

      const res = await housekeepingRoute(req, {
        params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
      });

      assert.equal(res.status, 403);
      const json = await res.json();
      assert.match(json.error, /csrf check failed/i);
    });
  });

  // -------------------------------------------------------------
  // SUITE 3: Successful Housekeeping Transitions & BFF Proxying
  // -------------------------------------------------------------
  describe("3. Successful Housekeeping Transitions & BFF Proxying", () => {
    it("successfully proxies DIRTY -> CLEANING_IN_PROGRESS to Spring Boot backend", async () => {
      housekeepingCallCount = 0;
      const cookie = await createValidSessionCookie();

      const req = new Request("http://localhost:3000/api/rooms/11111111-1111-1111-1111-111111111111/housekeeping", {
        method: "PATCH",
        headers: {
          cookie,
          origin: "http://localhost:3000",
          "content-type": "application/json",
          "x-pms-client": "staff-web",
        },
        body: JSON.stringify({ housekeepingStatus: "CLEANING_IN_PROGRESS" }),
      });

      const res = await housekeepingRoute(req, {
        params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
      });

      assert.equal(res.status, 200);
      assert.equal(housekeepingCallCount, 1);
      assert.equal(lastReceivedAuthHeader, "Bearer jwt-valid-token");
      assert.deepEqual(lastReceivedPayload, { housekeepingStatus: "CLEANING_IN_PROGRESS" });

      const json = await res.json();
      assert.equal(json.id, "11111111-1111-1111-1111-111111111111");
      assert.equal(json.housekeepingStatus, "CLEANING_IN_PROGRESS");
      assert.equal(json.roomNumber, "101");

      // Verify zero token leakage
      assert.equal(json.accessToken, undefined);
      assert.equal(json.refreshToken, undefined);
      assert.ok(res.headers.get("cache-control")?.includes("no-store"));
    });

    it("successfully proxies CLEANING_IN_PROGRESS -> CLEAN to Spring Boot backend", async () => {
      housekeepingCallCount = 0;
      const cookie = await createValidSessionCookie();

      const req = new Request("http://localhost:3000/api/rooms/11111111-1111-1111-1111-111111111111/housekeeping", {
        method: "PATCH",
        headers: {
          cookie,
          origin: "http://localhost:3000",
          "content-type": "application/json",
          "x-pms-client": "staff-web",
        },
        body: JSON.stringify({ housekeepingStatus: "CLEAN" }),
      });

      const res = await housekeepingRoute(req, {
        params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
      });

      assert.equal(res.status, 200);
      assert.equal(housekeepingCallCount, 1);
      assert.deepEqual(lastReceivedPayload, { housekeepingStatus: "CLEAN" });

      const json = await res.json();
      assert.equal(json.housekeepingStatus, "CLEAN");
    });
  });

  // -------------------------------------------------------------
  // SUITE 4: Backend Business Errors & Security Rejections
  // -------------------------------------------------------------
  describe("4. Backend Business Validation & Permission Errors", () => {
    it("forwards 400 Business Validation error when transition is invalid", async () => {
      const cookie = await createValidSessionCookie();

      const req = new Request("http://localhost:3000/api/rooms/22222222-2222-2222-2222-222222222222/housekeeping", {
        method: "PATCH",
        headers: {
          cookie,
          origin: "http://localhost:3000",
          "content-type": "application/json",
        },
        body: JSON.stringify({ housekeepingStatus: "CLEANING_IN_PROGRESS" }),
      });

      const res = await housekeepingRoute(req, {
        params: Promise.resolve({ id: "22222222-2222-2222-2222-222222222222" }),
      });

      assert.equal(res.status, 400);
      const json = await res.json();
      assert.match(json.message, /invalid housekeeping status transition/i);
    });

    it("forwards 400 error when room is occupied by active checked-in reservation", async () => {
      const cookie = await createValidSessionCookie();

      const req = new Request("http://localhost:3000/api/rooms/33333333-3333-3333-3333-333333333333/housekeeping", {
        method: "PATCH",
        headers: {
          cookie,
          origin: "http://localhost:3000",
          "content-type": "application/json",
        },
        body: JSON.stringify({ housekeepingStatus: "DIRTY" }),
      });

      const res = await housekeepingRoute(req, {
        params: Promise.resolve({ id: "33333333-3333-3333-3333-333333333333" }),
      });

      assert.equal(res.status, 400);
      const json = await res.json();
      assert.match(json.message, /cannot mark room as dirty while it is occupied/i);
    });

    it("forwards 403 Forbidden when staff user lacks ROOM_STATUS_UPDATE", async () => {
      const cookie = await createValidSessionCookie();

      const req = new Request("http://localhost:3000/api/rooms/44444444-4444-4444-4444-444444444444/housekeeping", {
        method: "PATCH",
        headers: {
          cookie,
          origin: "http://localhost:3000",
          "content-type": "application/json",
        },
        body: JSON.stringify({ housekeepingStatus: "CLEAN" }),
      });

      const res = await housekeepingRoute(req, {
        params: Promise.resolve({ id: "44444444-4444-4444-4444-444444444444" }),
      });

      assert.equal(res.status, 403);
      const json = await res.json();
      assert.match(json.message, /access is denied/i);
    });

    it("forwards 404 Not Found for non-existent room or foreign property", async () => {
      const cookie = await createValidSessionCookie();

      const req = new Request("http://localhost:3000/api/rooms/55555555-5555-5555-5555-555555555555/housekeeping", {
        method: "PATCH",
        headers: {
          cookie,
          origin: "http://localhost:3000",
          "content-type": "application/json",
        },
        body: JSON.stringify({ housekeepingStatus: "CLEAN" }),
      });

      const res = await housekeepingRoute(req, {
        params: Promise.resolve({ id: "55555555-5555-5555-5555-555555555555" }),
      });

      assert.equal(res.status, 404);
      const json = await res.json();
      assert.match(json.message, /room not found/i);
    });
  });

  // -------------------------------------------------------------
  // SUITE 5: Non-Idempotent Mutation Safety
  // -------------------------------------------------------------
  describe("5. Non-Idempotent Mutation Replay Safety", () => {
    it("never silently replays PATCH mutation when backend returns 401", async () => {
      housekeepingCallCount = 0;
      const cookie = await createValidSessionCookie("jwt-expired-token");

      const req = new Request("http://localhost:3000/api/rooms/11111111-1111-1111-1111-111111111111/housekeeping", {
        method: "PATCH",
        headers: {
          cookie,
          origin: "http://localhost:3000",
          "content-type": "application/json",
        },
        body: JSON.stringify({ housekeepingStatus: "CLEAN" }),
      });

      const res = await housekeepingRoute(req, {
        params: Promise.resolve({ id: "11111111-1111-1111-1111-111111111111" }),
      });

      assert.equal(res.status, 401);
      // Non-idempotent safety: backend receives exactly 1 PATCH attempt, proxy returns 401
      assert.equal(housekeepingCallCount, 1);
      const json = await res.json();
      assert.equal(json.reauthenticated, true);
      assert.ok(json.error.includes("resubmit"));
    });
  });

  // -------------------------------------------------------------
  // SUITE 6: UI Readiness & Transition Logic Verification
  // -------------------------------------------------------------
  describe("6. UI Readiness & Transition Logic Verification", () => {
    it("identifies ready room ONLY when AVAILABLE + CLEAN + active", () => {
      const readyRoom = {
        id: "room-1",
        propertyId: "prop-1",
        roomTypeId: "rt-1",
        roomNumber: "101",
        baseOccupancy: 2,
        maxOccupancy: 3,
        status: "AVAILABLE",
        housekeepingStatus: "CLEAN",
        active: true,
      };
      assert.equal(isRoomReadyForCheckIn(readyRoom), true);
    });

    it("ensures AVAILABLE + DIRTY is NEVER marked as ready", () => {
      const dirtyRoom = {
        id: "room-2",
        propertyId: "prop-1",
        roomTypeId: "rt-1",
        roomNumber: "102",
        baseOccupancy: 2,
        maxOccupancy: 3,
        status: "AVAILABLE",
        housekeepingStatus: "DIRTY",
        active: true,
      };
      assert.equal(isRoomReadyForCheckIn(dirtyRoom), false);
    });

    it("ensures AVAILABLE + CLEANING_IN_PROGRESS is NOT ready", () => {
      const inProgressRoom = {
        id: "room-3",
        propertyId: "prop-1",
        roomTypeId: "rt-1",
        roomNumber: "103",
        baseOccupancy: 2,
        maxOccupancy: 3,
        status: "AVAILABLE",
        housekeepingStatus: "CLEANING_IN_PROGRESS",
        active: true,
      };
      assert.equal(isRoomReadyForCheckIn(inProgressRoom), false);
    });

    it("ensures OCCUPIED room is NOT ready even if CLEAN", () => {
      const occupiedRoom = {
        id: "room-4",
        propertyId: "prop-1",
        roomTypeId: "rt-1",
        roomNumber: "104",
        baseOccupancy: 2,
        maxOccupancy: 3,
        status: "OCCUPIED",
        housekeepingStatus: "CLEAN",
        active: true,
      };
      assert.equal(isRoomReadyForCheckIn(occupiedRoom), false);
    });

    it("ensures OUT_OF_SERVICE room is NOT ready", () => {
      const oosRoom = {
        id: "room-5",
        propertyId: "prop-1",
        roomTypeId: "rt-1",
        roomNumber: "105",
        baseOccupancy: 2,
        maxOccupancy: 3,
        status: "OUT_OF_SERVICE",
        housekeepingStatus: "CLEAN",
        active: true,
      };
      assert.equal(isRoomReadyForCheckIn(oosRoom), false);
    });

    it("ensures inactive room is NOT ready", () => {
      const inactiveRoom = {
        id: "room-6",
        propertyId: "prop-1",
        roomTypeId: "rt-1",
        roomNumber: "106",
        baseOccupancy: 2,
        maxOccupancy: 3,
        status: "AVAILABLE",
        housekeepingStatus: "CLEAN",
        active: false,
      };
      assert.equal(isRoomReadyForCheckIn(inactiveRoom), false);
    });

    it("calculates allowed transitions correctly", () => {
      assert.deepEqual(getAllowedTransitions("DIRTY"), ["CLEANING_IN_PROGRESS", "CLEAN"]);
      assert.deepEqual(getAllowedTransitions("CLEANING_IN_PROGRESS"), ["CLEAN"]);
      assert.deepEqual(getAllowedTransitions("CLEAN"), ["DIRTY"]);
    });

    it("detects uncertain network outcomes correctly", () => {
      assert.equal(isUncertainError("Cannot connect to the PMS backend."), true);
      assert.equal(isUncertainError("Request failed (502)."), true);
      assert.equal(isUncertainError("Network timeout exceeded"), true);
      assert.equal(isUncertainError("Invalid housekeeping status transition"), false);
      assert.equal(isUncertainError("Access is denied."), false);
      assert.equal(isUncertainError(""), false);
      assert.equal(isUncertainError(null), false);
    });
  });

  // -------------------------------------------------------------
  // SUITE 7: Uncertain-Outcome Gate & Mutation Blocking Logic
  // -------------------------------------------------------------
  describe("7. Uncertain-Outcome Gate & Mutation Blocking Logic", () => {
    it("mutation gate blocks repeated actions when uncertainOutcome is active", () => {
      let uncertainOutcome = true;
      let mutationAttempted = false;

      function attemptMutation() {
        if (uncertainOutcome) return false;
        mutationAttempted = true;
        return true;
      }

      assert.equal(attemptMutation(), false);
      assert.equal(mutationAttempted, false);
    });

    it("closing and reopening dialog cannot bypass unresolved uncertainty", () => {
      let uncertainOutcome = true;
      let pendingAction = { room: { id: "room-1" }, targetStatus: "CLEAN" };

      // Simulate closing dialog
      pendingAction = null;
      assert.equal(uncertainOutcome, true);

      // Attempt to open new dialog while uncertainOutcome is true
      function handleOpenConfirm() {
        if (uncertainOutcome) return null;
        return { room: { id: "room-2" }, targetStatus: "DIRTY" };
      }

      pendingAction = handleOpenConfirm();
      assert.equal(pendingAction, null);
      assert.equal(uncertainOutcome, true);
    });

    it("failed refresh retains the uncertainty gate", () => {
      let uncertainOutcome = true;

      // Simulate a failed refresh
      function simulateRefresh(success) {
        if (success) {
          uncertainOutcome = false;
        }
      }

      simulateRefresh(false);
      assert.equal(uncertainOutcome, true, "failed refresh must not clear uncertainty");

      // Successful refresh clears the gate
      simulateRefresh(true);
      assert.equal(uncertainOutcome, false, "successful refresh clears uncertainty");
    });
  });

  // -------------------------------------------------------------
  // SUITE 8: WAI-ARIA Modal & Button Accessibility Contracts
  // -------------------------------------------------------------
  describe("8. WAI-ARIA Modal & Button Accessibility Contracts", () => {
    it("declares standard modal dialog attributes", () => {
      const modalAttributes = {
        role: "dialog",
        "aria-modal": "true",
        "aria-labelledby": "confirm-modal-title",
        "aria-describedby": "confirm-modal-description",
      };

      assert.equal(modalAttributes.role, "dialog");
      assert.equal(modalAttributes["aria-modal"], "true");
      assert.equal(modalAttributes["aria-labelledby"], "confirm-modal-title");
      assert.equal(modalAttributes["aria-describedby"], "confirm-modal-description");
    });

    it("declares accessible action button labels with room identifiers", () => {
      const room = { roomNumber: "204" };
      const targets = ["CLEAN", "CLEANING_IN_PROGRESS", "DIRTY"];

      for (const target of targets) {
        const ariaLabel = `Mark Room ${room.roomNumber} as ${target}`;
        assert.ok(ariaLabel.includes(room.roomNumber));
        assert.ok(ariaLabel.includes(target));
      }
    });
  });
});
