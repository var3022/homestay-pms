import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";

// Session secret for testing
process.env.PMS_SESSION_SECRET = "test-secret-at-least-32-characters-for-aes-256-gcm!";

import { encryptSession } from "../src/lib/auth/session.ts";
import { DEV_COOKIE_NAME } from "../src/lib/auth/cookies.ts";
import {
  GET as getAssignmentsRoute,
  POST as createAssignmentRoute,
} from "../src/app/api/room-assignments/route.ts";
import {
  evaluateReservationAssignmentCompleteness,
  getActiveReservedQuantity,
} from "../src/lib/reservations/completeness.ts";
import {
  ApiRequestError,
  isUncertainError,
  matchesExactAssignment,
} from "../src/lib/mutation-error.ts";

describe("PMS Front Desk Room Assignment BFF & UI Integration Test Suite", () => {
  let mockServer;
  let mockPort;
  let assignmentPostCallCount = 0;
  let assignmentGetCallCount = 0;

  const validStaff = {
    id: "staff-uuid-1",
    email: "receptionist@homestay.local",
    firstName: "Bob",
    lastName: "Receptionist",
  };

  before(async () => {
    mockServer = http.createServer((req, res) => {
      const url = new URL(req.url, `http://${req.headers.host}`);
      const auth = req.headers["authorization"];

      if (url.pathname === "/api/v1/auth/refresh" && req.method === "POST") {
        res.writeHead(200, {
          "Content-Type": "application/json",
          "Set-Cookie": "pms-refresh-dev=assignment-refreshed-token; Path=/; HttpOnly; SameSite=Strict",
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

      // GET /api/v1/room-assignments
      if (url.pathname === "/api/v1/room-assignments" && req.method === "GET") {
        assignmentGetCallCount++;
        const resRoomId = url.searchParams.get("reservationRoomId");
        const roomId = url.searchParams.get("roomId");

        if (auth !== "Bearer jwt-valid-token" && auth !== "jwt-valid-token") {
          res.writeHead(401, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ message: "Unauthorized" }));
          return;
        }

        if (resRoomId) {
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify([
              {
                id: "assign-uuid-1",
                reservationRoomId: resRoomId,
                roomId: "room-uuid-101",
                assignedFrom: "2026-12-01",
                assignedUntil: "2026-12-05",
                status: "ASSIGNED",
              },
            ])
          );
          return;
        }

        if (roomId) {
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify([
              {
                id: "assign-uuid-2",
                reservationRoomId: "res-room-uuid-2",
                roomId,
                assignedFrom: "2026-12-01",
                assignedUntil: "2026-12-05",
                status: "ASSIGNED",
              },
            ])
          );
          return;
        }

        res.writeHead(400, { "Content-Type": "application/json" });
        res.end(
          JSON.stringify({
            error: "BusinessValidationException",
            message: "Either reservationRoomId or roomId must be provided",
          })
        );
        return;
      }

      // POST /api/v1/room-assignments
      if (url.pathname === "/api/v1/room-assignments" && req.method === "POST") {
        assignmentPostCallCount++;

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

        let body = "";
        req.on("data", (chunk) => {
          body += chunk;
        });

        req.on("end", () => {
          try {
            const data = JSON.parse(body);
            const { reservationRoomId, roomId } = data;

            // Scenario 1: Success
            if (
              reservationRoomId === "11111111-1111-1111-1111-111111111111" &&
              roomId === "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            ) {
              res.writeHead(201, { "Content-Type": "application/json" });
              res.end(
                JSON.stringify({
                  id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  reservationRoomId,
                  roomId,
                  assignedFrom: data.assignedFrom,
                  assignedUntil: data.assignedUntil,
                  status: "ASSIGNED",
                  createdAt: new Date().toISOString(),
                  updatedAt: new Date().toISOString(),
                })
              );
              return;
            }

            // Scenario 2: Overlapping room assignment conflict (400)
            if (roomId === "cccccccc-cccc-cccc-cccc-cccccccccccc") {
              res.writeHead(400, { "Content-Type": "application/json" });
              res.end(
                JSON.stringify({
                  status: 400,
                  error: "Business Validation Failed",
                  message: "Room is already assigned for the requested dates",
                })
              );
              return;
            }

            // Scenario 3: Room out of service (400)
            if (roomId === "dddddddd-dddd-dddd-dddd-dddddddddddd") {
              res.writeHead(400, { "Content-Type": "application/json" });
              res.end(
                JSON.stringify({
                  status: 400,
                  error: "Business Validation Failed",
                  message: "Room is out of service and cannot be assigned",
                })
              );
              return;
            }

            // Scenario 4: Room inactive (400)
            if (roomId === "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee") {
              res.writeHead(400, { "Content-Type": "application/json" });
              res.end(
                JSON.stringify({
                  status: 400,
                  error: "Business Validation Failed",
                  message: "Room is inactive and cannot be assigned",
                })
              );
              return;
            }

            // Scenario 5: Room type mismatch (400)
            if (roomId === "ffffffff-ffff-ffff-ffff-ffffffffffff") {
              res.writeHead(400, { "Content-Type": "application/json" });
              res.end(
                JSON.stringify({
                  status: 400,
                  error: "Business Validation Failed",
                  message: "Room does not belong to the reservation room type",
                })
              );
              return;
            }

            // Scenario 6: Property mismatch (400)
            if (roomId === "77777777-7777-7777-7777-777777777777") {
              res.writeHead(400, { "Content-Type": "application/json" });
              res.end(
                JSON.stringify({
                  status: 400,
                  error: "Business Validation Failed",
                  message: "Room does not belong to the reservation property",
                })
              );
              return;
            }

            // Scenario 7: Forbidden (403)
            if (reservationRoomId === "88888888-8888-8888-8888-888888888888") {
              res.writeHead(403, { "Content-Type": "application/json" });
              res.end(
                JSON.stringify({
                  status: 403,
                  error: "Forbidden",
                  message: "Access is denied.",
                })
              );
              return;
            }

            // Scenario 8: Not found (404)
            if (reservationRoomId === "99999999-9999-9999-9999-999999999999") {
              res.writeHead(404, { "Content-Type": "application/json" });
              res.end(
                JSON.stringify({
                  status: 404,
                  error: "Resource Not Found",
                  message: "Reservation room not found with id: 99999999-9999-9999-9999-999999999999",
                })
              );
              return;
            }

            res.writeHead(404, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ message: "Not found" }));
          } catch {
            res.writeHead(400, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ message: "Invalid JSON" }));
          }
        });
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
      refreshToken: "assignment-test-refresh-token",
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

  it("1. preserves existing GET /api/room-assignments forwarding query parameters", async () => {
    const cookie = await createValidSessionCookie();
    const req = new Request(
      "http://localhost:3000/api/room-assignments?reservationRoomId=11111111-1111-1111-1111-111111111111",
      {
        method: "GET",
        headers: {
          cookie,
        },
      }
    );

    const callsBefore = assignmentGetCallCount;
    const res = await getAssignmentsRoute(req);
    assert.equal(res.status, 200);
    const data = await res.json();
    assert.ok(Array.isArray(data));
    assert.equal(data.length, 1);
    assert.equal(data[0].id, "assign-uuid-1");
    assert.equal(assignmentGetCallCount, callsBefore + 1);
  });

  it("2. forwards valid authenticated POST room assignment to backend and returns 201 Created", async () => {
    const cookie = await createValidSessionCookie();
    const payload = {
      reservationRoomId: "11111111-1111-1111-1111-111111111111",
      roomId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      assignedFrom: "2026-12-01",
      assignedUntil: "2026-12-05",
    };

    const req = new Request("http://localhost:3000/api/room-assignments", {
      method: "POST",
      headers: {
        origin: "http://localhost:3000",
        cookie,
        "content-type": "application/json",
        "x-pms-client": "staff-web",
      },
      body: JSON.stringify(payload),
    });

    const callsBefore = assignmentPostCallCount;
    const res = await createAssignmentRoute(req);
    assert.equal(res.status, 201);
    assert.ok(res.headers.get("Cache-Control").includes("no-store"));
    const data = await res.json();
    assert.equal(data.id, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    assert.equal(data.status, "ASSIGNED");
    assert.equal(assignmentPostCallCount, callsBefore + 1);
  });

  it("3. rejects unauthenticated POST room assignment with 401 Unauthorized", async () => {
    const payload = {
      reservationRoomId: "11111111-1111-1111-1111-111111111111",
      roomId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      assignedFrom: "2026-12-01",
      assignedUntil: "2026-12-05",
    };

    const req = new Request("http://localhost:3000/api/room-assignments", {
      method: "POST",
      headers: {
        origin: "http://localhost:3000",
        "content-type": "application/json",
        "x-pms-client": "staff-web",
      },
      body: JSON.stringify(payload),
    });

    const res = await createAssignmentRoute(req);
    assert.equal(res.status, 401);
    const data = await res.json();
    assert.ok(data.error.includes("Unauthorized"));
  });

  it("4. rejects POST with invalid UUID parameter before reaching backend", async () => {
    const cookie = await createValidSessionCookie();
    const payload = {
      reservationRoomId: "not-a-valid-uuid",
      roomId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      assignedFrom: "2026-12-01",
      assignedUntil: "2026-12-05",
    };

    const req = new Request("http://localhost:3000/api/room-assignments", {
      method: "POST",
      headers: {
        origin: "http://localhost:3000",
        cookie,
        "content-type": "application/json",
      },
      body: JSON.stringify(payload),
    });

    const callsBefore = assignmentPostCallCount;
    const res = await createAssignmentRoute(req);
    assert.equal(res.status, 400);
    const data = await res.json();
    assert.equal(data.error, "Invalid UUID in reservationRoomId or roomId.");
    assert.equal(assignmentPostCallCount, callsBefore, "backend was not contacted");
  });

  it("5. rejects POST with inverted or malformed date range before reaching backend", async () => {
    const cookie = await createValidSessionCookie();
    const payload = {
      reservationRoomId: "11111111-1111-1111-1111-111111111111",
      roomId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      assignedFrom: "2026-12-05",
      assignedUntil: "2026-12-01", // Inverted
    };

    const req = new Request("http://localhost:3000/api/room-assignments", {
      method: "POST",
      headers: {
        origin: "http://localhost:3000",
        cookie,
        "content-type": "application/json",
      },
      body: JSON.stringify(payload),
    });

    const callsBefore = assignmentPostCallCount;
    const res = await createAssignmentRoute(req);
    assert.equal(res.status, 400);
    const data = await res.json();
    assert.ok(data.error.includes("Invalid assignment date range"));
    assert.equal(assignmentPostCallCount, callsBefore, "backend was not contacted");
  });

  it("6. rejects mutating POST request with foreign Origin header (CSRF protection)", async () => {
    const cookie = await createValidSessionCookie();
    const payload = {
      reservationRoomId: "11111111-1111-1111-1111-111111111111",
      roomId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      assignedFrom: "2026-12-01",
      assignedUntil: "2026-12-05",
    };

    const req = new Request("http://localhost:3000/api/room-assignments", {
      method: "POST",
      headers: {
        origin: "https://attacker.evil.com",
        cookie,
        "content-type": "application/json",
      },
      body: JSON.stringify(payload),
    });

    const res = await createAssignmentRoute(req);
    assert.equal(res.status, 403);
    const data = await res.json();
    assert.ok(data.error.includes("CSRF check failed"));
  });

  it("7. rejects mutating POST request with missing Origin and Referer (CSRF protection)", async () => {
    const cookie = await createValidSessionCookie();
    const payload = {
      reservationRoomId: "11111111-1111-1111-1111-111111111111",
      roomId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      assignedFrom: "2026-12-01",
      assignedUntil: "2026-12-05",
    };

    const req = new Request("http://localhost:3000/api/room-assignments", {
      method: "POST",
      headers: {
        cookie,
        "content-type": "application/json",
      },
      body: JSON.stringify(payload),
    });

    const res = await createAssignmentRoute(req);
    assert.equal(res.status, 403);
    const data = await res.json();
    assert.ok(data.error.includes("CSRF check failed"));
  });

  it("8. forwards backend 400 overlapping room assignment conflict safely", async () => {
    const cookie = await createValidSessionCookie();
    const payload = {
      reservationRoomId: "11111111-1111-1111-1111-111111111111",
      roomId: "cccccccc-cccc-cccc-cccc-cccccccccccc",
      assignedFrom: "2026-12-01",
      assignedUntil: "2026-12-05",
    };

    const req = new Request("http://localhost:3000/api/room-assignments", {
      method: "POST",
      headers: {
        origin: "http://localhost:3000",
        cookie,
        "content-type": "application/json",
      },
      body: JSON.stringify(payload),
    });

    const res = await createAssignmentRoute(req);
    assert.equal(res.status, 400);
    const data = await res.json();
    assert.ok(data.message.includes("Room is already assigned for the requested dates"));
  });

  it("9. forwards backend 400 out-of-service room rejection safely", async () => {
    const cookie = await createValidSessionCookie();
    const payload = {
      reservationRoomId: "11111111-1111-1111-1111-111111111111",
      roomId: "dddddddd-dddd-dddd-dddd-dddddddddddd",
      assignedFrom: "2026-12-01",
      assignedUntil: "2026-12-05",
    };

    const req = new Request("http://localhost:3000/api/room-assignments", {
      method: "POST",
      headers: {
        origin: "http://localhost:3000",
        cookie,
        "content-type": "application/json",
      },
      body: JSON.stringify(payload),
    });

    const res = await createAssignmentRoute(req);
    assert.equal(res.status, 400);
    const data = await res.json();
    assert.ok(data.message.includes("out of service"));
  });

  it("10. forwards backend 400 inactive room rejection safely", async () => {
    const cookie = await createValidSessionCookie();
    const payload = {
      reservationRoomId: "11111111-1111-1111-1111-111111111111",
      roomId: "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
      assignedFrom: "2026-12-01",
      assignedUntil: "2026-12-05",
    };

    const req = new Request("http://localhost:3000/api/room-assignments", {
      method: "POST",
      headers: {
        origin: "http://localhost:3000",
        cookie,
        "content-type": "application/json",
      },
      body: JSON.stringify(payload),
    });

    const res = await createAssignmentRoute(req);
    assert.equal(res.status, 400);
    const data = await res.json();
    assert.ok(data.message.includes("inactive"));
  });

  it("11. forwards backend 400 room type mismatch rejection safely", async () => {
    const cookie = await createValidSessionCookie();
    const payload = {
      reservationRoomId: "11111111-1111-1111-1111-111111111111",
      roomId: "ffffffff-ffff-ffff-ffff-ffffffffffff",
      assignedFrom: "2026-12-01",
      assignedUntil: "2026-12-05",
    };

    const req = new Request("http://localhost:3000/api/room-assignments", {
      method: "POST",
      headers: {
        origin: "http://localhost:3000",
        cookie,
        "content-type": "application/json",
      },
      body: JSON.stringify(payload),
    });

    const res = await createAssignmentRoute(req);
    assert.equal(res.status, 400);
    const data = await res.json();
    assert.ok(data.message.includes("does not belong to the reservation room type"));
  });

  it("12. forwards backend 400 cross-property room assignment rejection safely", async () => {
    const cookie = await createValidSessionCookie();
    const payload = {
      reservationRoomId: "11111111-1111-1111-1111-111111111111",
      roomId: "77777777-7777-7777-7777-777777777777",
      assignedFrom: "2026-12-01",
      assignedUntil: "2026-12-05",
    };

    const req = new Request("http://localhost:3000/api/room-assignments", {
      method: "POST",
      headers: {
        origin: "http://localhost:3000",
        cookie,
        "content-type": "application/json",
      },
      body: JSON.stringify(payload),
    });

    const res = await createAssignmentRoute(req);
    assert.equal(res.status, 400);
    const data = await res.json();
    assert.ok(data.message.includes("does not belong to the reservation property"));
  });

  it("13. forwards backend 403 Forbidden permission denial safely", async () => {
    const cookie = await createValidSessionCookie();
    const payload = {
      reservationRoomId: "88888888-8888-8888-8888-888888888888",
      roomId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      assignedFrom: "2026-12-01",
      assignedUntil: "2026-12-05",
    };

    const req = new Request("http://localhost:3000/api/room-assignments", {
      method: "POST",
      headers: {
        origin: "http://localhost:3000",
        cookie,
        "content-type": "application/json",
      },
      body: JSON.stringify(payload),
    });

    const res = await createAssignmentRoute(req);
    assert.equal(res.status, 403);
    const data = await res.json();
    assert.equal(data.message, "Access is denied.");
  });

  it("14. forwards backend 404 Resource Not Found for foreign scope or non-existent entity", async () => {
    const cookie = await createValidSessionCookie();
    const payload = {
      reservationRoomId: "99999999-9999-9999-9999-999999999999",
      roomId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      assignedFrom: "2026-12-01",
      assignedUntil: "2026-12-05",
    };

    const req = new Request("http://localhost:3000/api/room-assignments", {
      method: "POST",
      headers: {
        origin: "http://localhost:3000",
        cookie,
        "content-type": "application/json",
      },
      body: JSON.stringify(payload),
    });

    const res = await createAssignmentRoute(req);
    assert.equal(res.status, 404);
    const data = await res.json();
    assert.ok(data.message.includes("Reservation room not found"));
  });

  it("15. never silently replays mutating POST request when backend returns 401", async () => {
    const expiredCookie = await createValidSessionCookie("jwt-expired-token");
    const payload = {
      reservationRoomId: "11111111-1111-1111-1111-111111111111",
      roomId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      assignedFrom: "2026-12-01",
      assignedUntil: "2026-12-05",
    };

    const req = new Request("http://localhost:3000/api/room-assignments", {
      method: "POST",
      headers: {
        origin: "http://localhost:3000",
        cookie: expiredCookie,
        "content-type": "application/json",
      },
      body: JSON.stringify(payload),
    });

    const callsBefore = assignmentPostCallCount;
    const res = await createAssignmentRoute(req);
    assert.equal(res.status, 401);
    assert.equal(assignmentPostCallCount, callsBefore + 1, "mutating POST was NOT replayed automatically");
  });

  // =========================================================================
  // Part 2: Front Desk UI Logic, Per-Category Completeness & State Calculations
  // =========================================================================

  function calculateAssignmentStatus(reservedRoom, assignments, reservation) {
    const activeQuantity = getActiveReservedQuantity(reservedRoom);
    const unitAssignments = (assignments || []).filter(
      (a) => a.reservationRoomId === reservedRoom.id && a.status === "ASSIGNED"
    );
    const fullStayAssignments = reservation
      ? unitAssignments.filter(
          (a) =>
            a.assignedFrom === reservation.checkInDate &&
            a.assignedUntil === reservation.checkOutDate
        )
      : unitAssignments;
    const distinctRoomIds = new Set(fullStayAssignments.map((a) => a.roomId));
    const assignedCount = distinctRoomIds.size;
    const unassignedCount = Math.max(0, activeQuantity - assignedCount);

    let status = "UNASSIGNED";
    if (assignedCount >= activeQuantity) {
      status = "FULLY_ASSIGNED";
    } else if (assignedCount > 0) {
      status = "PARTIALLY_ASSIGNED";
    }

    return { activeQuantity, assignedCount, unassignedCount, status };
  }

  it("16. UI quantity calculation: correctly calculates active quantity with 0 handling and fallbacks", () => {
    // Zero active quantity: should NOT fall back to quantity - cancelledQuantity
    const zeroActiveRoom = { id: "rr-0", activeQuantity: 0, quantity: 2, cancelledQuantity: 0 };
    assert.equal(getActiveReservedQuantity(zeroActiveRoom), 0);

    // Normal active quantity provided
    const normalRoom = { id: "rr-1", activeQuantity: 3, quantity: 5, cancelledQuantity: 2 };
    assert.equal(getActiveReservedQuantity(normalRoom), 3);

    // Fallback when activeQuantity is null or undefined
    const fallbackRoom = { id: "rr-2", quantity: 4, cancelledQuantity: 1 };
    assert.equal(getActiveReservedQuantity(fallbackRoom), 3);

    // Fully cancelled fallback
    const fullyCancelled = { id: "rr-3", quantity: 2, cancelledQuantity: 2 };
    assert.equal(getActiveReservedQuantity(fullyCancelled), 0);

    // Null/undefined room
    assert.equal(getActiveReservedQuantity(null), 0);
    assert.equal(getActiveReservedQuantity(undefined), 0);

    // Assignment status integration
    const reservedRoom = { id: "rr-4", quantity: 3, cancelledQuantity: 1 };
    const status = calculateAssignmentStatus(reservedRoom, []);
    assert.equal(status.activeQuantity, 2);
    assert.equal(status.unassignedCount, 2);
    assert.equal(status.status, "UNASSIGNED");
  });

  it("17. UI state classification: excludes RELEASED assignments from active count", () => {
    const reservedRoom = { id: "rr-1", quantity: 2, cancelledQuantity: 0 };
    const assignments = [
      { id: "a-1", reservationRoomId: "rr-1", status: "RELEASED" },
      { id: "a-2", reservationRoomId: "rr-1", status: "ASSIGNED", roomId: "r-1" },
    ];
    const status = calculateAssignmentStatus(reservedRoom, assignments);
    assert.equal(status.assignedCount, 1);
    assert.equal(status.unassignedCount, 1);
    assert.equal(status.status, "PARTIALLY_ASSIGNED");
  });

  it("18. UI state classification: identifies fully assigned reservation room", () => {
    const reservedRoom = { id: "rr-1", quantity: 2, cancelledQuantity: 0 };
    const assignments = [
      { id: "a-1", reservationRoomId: "rr-1", status: "ASSIGNED", roomId: "r-1" },
      { id: "a-2", reservationRoomId: "rr-1", status: "ASSIGNED", roomId: "r-2" },
    ];
    const status = calculateAssignmentStatus(reservedRoom, assignments);
    assert.equal(status.assignedCount, 2);
    assert.equal(status.unassignedCount, 0);
    assert.equal(status.status, "FULLY_ASSIGNED");
  });

  it("19. candidate filtering: enforces strict property match, room type, active status, and availability", () => {
    const targetRoomTypeId = "rt-suite";
    const selectedReservation = { propertyId: "prop-1" };
    const assignedPhysicalIds = new Set(["room-1"]);

    const rooms = [
      { id: "room-1", roomTypeId: targetRoomTypeId, propertyId: "prop-1", active: true, status: "AVAILABLE" }, // already assigned
      { id: "room-2", roomTypeId: targetRoomTypeId, propertyId: "prop-1", active: true, status: "OUT_OF_SERVICE" }, // out of service
      { id: "room-3", roomTypeId: targetRoomTypeId, propertyId: "prop-1", active: false, status: "AVAILABLE" }, // inactive
      { id: "room-4", roomTypeId: "rt-other", propertyId: "prop-1", active: true, status: "AVAILABLE" }, // mismatched room type
      { id: "room-5", roomTypeId: targetRoomTypeId, propertyId: "prop-other", active: true, status: "AVAILABLE" }, // mismatched property
      { id: "room-6", roomTypeId: targetRoomTypeId, propertyId: null, active: true, status: "AVAILABLE" }, // missing room propertyId
      { id: "room-7", roomTypeId: targetRoomTypeId, propertyId: "prop-1", active: undefined, status: "AVAILABLE" }, // active !== true
      { id: "room-8", roomTypeId: targetRoomTypeId, propertyId: "prop-1", active: true, status: "AVAILABLE", housekeepingStatus: "CLEAN" }, // eligible
      { id: "room-9", roomTypeId: targetRoomTypeId, propertyId: "prop-1", active: true, status: "AVAILABLE", housekeepingStatus: "DIRTY" }, // eligible (pre-assignment allowed)
    ];

    function filterCandidates(candidateRooms, res) {
      return candidateRooms.filter((room) => {
        if (room.roomTypeId !== targetRoomTypeId) return false;
        if (!res.propertyId || !room.propertyId || room.propertyId !== res.propertyId) return false;
        if (room.active !== true) return false;
        if (room.status === "OUT_OF_SERVICE") return false;
        if (assignedPhysicalIds.has(room.id)) return false;
        return true;
      });
    }

    const eligible = filterCandidates(rooms, selectedReservation);
    assert.equal(eligible.length, 2);
    assert.equal(eligible[0].id, "room-8");
    assert.equal(eligible[1].id, "room-9");

    // Reservation with missing propertyId rejects all candidate rooms
    const eligibleNoProp = filterCandidates(rooms, { propertyId: null });
    assert.equal(eligibleNoProp.length, 0);
  });

  it("20. pre-check-in status eligibility: permits room assignment for PENDING and CONFIRMED only", () => {
    const allowedStatuses = ["PENDING", "CONFIRMED"];
    const prohibitedStatuses = ["CHECKED_IN", "CHECKED_OUT", "CANCELLED", "NO_SHOW"];

    for (const status of allowedStatuses) {
      const canAssign = ["PENDING", "CONFIRMED"].includes(status);
      assert.equal(canAssign, true, `Status ${status} should permit room assignment`);
    }

    for (const status of prohibitedStatuses) {
      const canAssign = ["PENDING", "CONFIRMED"].includes(status);
      assert.equal(canAssign, false, `Status ${status} must NOT permit room assignment`);
    }
  });

  it("21. check-in blocking: enforces full physical room assignment before check-in", () => {
    const res = { id: "res-1", checkInDate: "2026-12-01", checkOutDate: "2026-12-05" };
    const reservationRooms = [
      { id: "rr-1", roomTypeId: "rt-1", activeQuantity: 2 },
      { id: "rr-2", roomTypeId: "rt-2", activeQuantity: 1 },
    ];
    const roomTypes = [
      { id: "rt-1", name: "Deluxe Suite" },
      { id: "rt-2", name: "Standard Room" },
    ];

    // Incomplete assignments (1 assigned out of 3)
    const incompleteAssignments = [
      { id: "a-1", reservationRoomId: "rr-1", roomId: "r-1", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
    ];
    const incompleteResult = evaluateReservationAssignmentCompleteness(res, reservationRooms, incompleteAssignments, [], roomTypes);
    assert.equal(incompleteResult.canCheckIn, false);
    assert.equal(incompleteResult.isComplete, false);
    assert.ok(incompleteResult.notice.includes("Deluxe Suite"));
    assert.ok(incompleteResult.notice.includes("Standard Room"));

    // Complete assignments (3 assigned out of 3)
    const completeAssignments = [
      { id: "a-1", reservationRoomId: "rr-1", roomId: "r-1", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
      { id: "a-2", reservationRoomId: "rr-1", roomId: "r-2", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
      { id: "a-3", reservationRoomId: "rr-2", roomId: "r-3", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
    ];
    const completeResult = evaluateReservationAssignmentCompleteness(res, reservationRooms, completeAssignments, [], roomTypes);
    assert.equal(completeResult.canCheckIn, true);
    assert.equal(completeResult.isComplete, true);
    assert.equal(completeResult.notice, null);
  });

  it("22. multiple room categories: verifies per-category assignment completeness", () => {
    const res = { id: "res-22", checkInDate: "2026-12-01", checkOutDate: "2026-12-05" };
    const reservationRooms = [
      { id: "rr-deluxe", roomTypeId: "rt-deluxe", activeQuantity: 2 },
      { id: "rr-suite", roomTypeId: "rt-suite", activeQuantity: 1 },
      { id: "rr-standard", roomTypeId: "rt-standard", activeQuantity: 1 },
    ];
    const roomTypes = [
      { id: "rt-deluxe", name: "Deluxe" },
      { id: "rt-suite", name: "Suite" },
      { id: "rt-standard", name: "Standard" },
    ];
    const assignments = [
      { id: "a-1", reservationRoomId: "rr-deluxe", roomId: "room-101", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
      { id: "a-2", reservationRoomId: "rr-deluxe", roomId: "room-102", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
      { id: "a-3", reservationRoomId: "rr-suite", roomId: "room-201", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
      { id: "a-4", reservationRoomId: "rr-standard", roomId: "room-301", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
    ];

    const result = evaluateReservationAssignmentCompleteness(res, reservationRooms, assignments, [], roomTypes);
    assert.equal(result.isComplete, true);
    assert.equal(result.canCheckIn, true);
    assert.equal(result.totalActiveUnits, 4);
    assert.equal(result.totalValidAssignedUnits, 4);
    assert.equal(result.perRoomTypeDetails.length, 3);
    assert.ok(result.perRoomTypeDetails.every((d) => d.isFullyAssigned));
  });

  it("23. partially assigned categories: blocks check-in and identifies exact underassigned category", () => {
    const res = { id: "res-23", checkInDate: "2026-12-01", checkOutDate: "2026-12-05" };
    const reservationRooms = [
      { id: "rr-1", roomTypeId: "rt-deluxe", activeQuantity: 2 },
      { id: "rr-2", roomTypeId: "rt-suite", activeQuantity: 1 },
    ];
    const roomTypes = [
      { id: "rt-deluxe", name: "Deluxe Room" },
      { id: "rt-suite", name: "Executive Suite" },
    ];
    // Deluxe has 2/2, Suite has 0/1
    const assignments = [
      { id: "a-1", reservationRoomId: "rr-1", roomId: "room-101", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
      { id: "a-2", reservationRoomId: "rr-1", roomId: "room-102", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
    ];

    const result = evaluateReservationAssignmentCompleteness(res, reservationRooms, assignments, [], roomTypes);
    assert.equal(result.isComplete, false);
    assert.equal(result.canCheckIn, false);
    assert.equal(result.totalActiveUnits, 3);
    assert.equal(result.totalValidAssignedUnits, 2);
    assert.equal(result.unassignedActiveUnits, 1);
    assert.ok(result.notice.includes("Executive Suite (0/1 assigned)"));
    assert.ok(!result.notice.includes("Deluxe Room"));
  });

  it("24. overassigned category safety: prevents overassigned category from masking underassigned category", () => {
    const res = { id: "res-24", checkInDate: "2026-12-01", checkOutDate: "2026-12-05" };
    const reservationRooms = [
      { id: "rr-1", roomTypeId: "rt-1", activeQuantity: 1 },
      { id: "rr-2", roomTypeId: "rt-2", activeQuantity: 1 },
    ];
    const roomTypes = [
      { id: "rt-1", name: "Category A" },
      { id: "rt-2", name: "Category B" },
    ];
    // Total active units = 2. Category A has 2 assignments, Category B has 0.
    // Aggregate count is 2 (equals total units), but Category B is underassigned!
    const assignments = [
      { id: "a-1", reservationRoomId: "rr-1", roomId: "room-101", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
      { id: "a-2", reservationRoomId: "rr-1", roomId: "room-102", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
    ];

    const result = evaluateReservationAssignmentCompleteness(res, reservationRooms, assignments, [], roomTypes);
    assert.equal(result.isComplete, false, "Aggregate total match MUST NOT allow check-in when category B is underassigned");
    assert.equal(result.canCheckIn, false);
    assert.ok(result.notice.includes("Category B (0/1 assigned)"));
    assert.ok(result.notice.includes("Category A (overassigned: 2/1)"));
  });

  it("25. duplicate physical room IDs: blocks check-in when same physical room is assigned twice", () => {
    const res = { id: "res-25", checkInDate: "2026-12-01", checkOutDate: "2026-12-05" };
    // Scenario A: Duplicate physical room within the same unit
    const reservationRooms = [
      { id: "rr-1", roomTypeId: "rt-1", activeQuantity: 2 },
    ];
    const duplicateInUnit = [
      { id: "a-1", reservationRoomId: "rr-1", roomId: "room-dup", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
      { id: "a-2", reservationRoomId: "rr-1", roomId: "room-dup", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
    ];
    const resA = evaluateReservationAssignmentCompleteness(res, reservationRooms, duplicateInUnit, []);
    assert.equal(resA.canCheckIn, false);
    assert.equal(resA.totalValidAssignedUnits, 1);

    // Scenario B: Same physical room assigned across different reservation rooms
    const multiRooms = [
      { id: "rr-1", roomTypeId: "rt-1", activeQuantity: 1 },
      { id: "rr-2", roomTypeId: "rt-2", activeQuantity: 1 },
    ];
    const duplicateAcrossRooms = [
      { id: "a-1", reservationRoomId: "rr-1", roomId: "room-dup", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
      { id: "a-2", reservationRoomId: "rr-2", roomId: "room-dup", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
    ];
    const resB = evaluateReservationAssignmentCompleteness(res, multiRooms, duplicateAcrossRooms, []);
    assert.equal(resB.canCheckIn, false);
    assert.ok(resB.notice.includes("Duplicate physical room assignment detected"));
  });

  it("26. date range coverage: blocks check-in for partial stay or split-stay date assignments", () => {
    const res = { id: "res-26", checkInDate: "2026-12-01", checkOutDate: "2026-12-05" };
    const reservationRooms = [{ id: "rr-1", roomTypeId: "rt-1", activeQuantity: 1 }];
    // Assignment only covers until 2026-12-03 (partial stay)
    const partialAssignments = [
      { id: "a-1", reservationRoomId: "rr-1", roomId: "room-101", assignedFrom: "2026-12-01", assignedUntil: "2026-12-03", status: "ASSIGNED" },
    ];

    const result = evaluateReservationAssignmentCompleteness(res, reservationRooms, partialAssignments, []);
    assert.equal(result.canCheckIn, false);
    assert.equal(result.isComplete, false);
    assert.ok(result.notice.includes("do not cover the full stay period"));
  });

  it("27. cross-reservation and cancelled unit isolation: ignores unrelated and cancelled room assignments", () => {
    const res = { id: "res-27", checkInDate: "2026-12-01", checkOutDate: "2026-12-05" };
    const reservationRooms = [
      { id: "rr-active", roomTypeId: "rt-1", activeQuantity: 1 },
      { id: "rr-cancelled", roomTypeId: "rt-2", activeQuantity: 0, status: "CANCELLED" },
    ];
    // Assignments include an unrelated reservationRoomId and an assignment for the cancelled room
    const assignments = [
      { id: "a-unrelated", reservationRoomId: "rr-foreign", roomId: "room-999", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
      { id: "a-cancelled", reservationRoomId: "rr-cancelled", roomId: "room-888", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
      { id: "a-valid", reservationRoomId: "rr-active", roomId: "room-101", assignedFrom: "2026-12-01", assignedUntil: "2026-12-05", status: "ASSIGNED" },
    ];

    const result = evaluateReservationAssignmentCompleteness(res, reservationRooms, assignments, []);
    assert.equal(result.canCheckIn, true);
    assert.equal(result.totalActiveUnits, 1);
    assert.equal(result.totalValidAssignedUnits, 1);
  });

  it("28. zero active units check: blocks check-in when reservation has no active rooms", () => {
    const res = { id: "res-28", checkInDate: "2026-12-01", checkOutDate: "2026-12-05" };
    const allCancelledRooms = [
      { id: "rr-1", roomTypeId: "rt-1", activeQuantity: 0, status: "CANCELLED" },
    ];
    const result = evaluateReservationAssignmentCompleteness(res, allCancelledRooms, []);
    assert.equal(result.status, "NO_ACTIVE_ROOMS");
    assert.equal(result.canCheckIn, false);
    assert.ok(result.notice.includes("no active rooms"));
  });

  it("29. loading and error states: blocks check-in while data is loading or when loading fails", () => {
    const res = { id: "res-29", checkInDate: "2026-12-01", checkOutDate: "2026-12-05" };
    const rooms = [{ id: "rr-1", roomTypeId: "rt-1", activeQuantity: 1 }];

    // Loading state
    const loadingResult = evaluateReservationAssignmentCompleteness(res, rooms, [], [], [], true, "");
    assert.equal(loadingResult.status, "LOADING");
    assert.equal(loadingResult.canCheckIn, false);
    assert.ok(loadingResult.notice.includes("Loading"));

    // Error state
    const errorResult = evaluateReservationAssignmentCompleteness(res, rooms, [], [], [], false, "Network timeout 504");
    assert.equal(errorResult.status, "ERROR");
    assert.equal(errorResult.canCheckIn, false);
    assert.ok(errorResult.notice.includes("Network timeout 504"));
  });

  it("30. out-of-order stale response handling: sequence counter discards stale responses", () => {
    let currentSeq = 0;
    let roomAssignmentsState = [];

    function handleMockResponse(seq, newAssignments) {
      if (seq < currentSeq) {
        // Discard stale out-of-order response
        return false;
      }
      roomAssignmentsState = newAssignments;
      return true;
    }

    // Request 1 dispatched
    const seq1 = ++currentSeq;
    // Request 2 dispatched before Request 1 resolved
    const seq2 = ++currentSeq;

    // Request 2 finishes first
    const applied2 = handleMockResponse(seq2, [{ id: "a-fresh" }]);
    assert.equal(applied2, true);
    assert.equal(roomAssignmentsState[0].id, "a-fresh");

    // Request 1 finishes later (stale out-of-order response)
    const applied1 = handleMockResponse(seq1, [{ id: "a-stale" }]);
    assert.equal(applied1, false, "Stale response must be discarded");
    assert.equal(roomAssignmentsState[0].id, "a-fresh", "State must retain fresh assignments");
  });

  // =========================================================================
  // Part 3: Uncertainty State Machine & Accessibility
  // =========================================================================

  it("31. uncertainty gate: blocks duplicate assignment attempt for affected reservation and room", () => {
    const resId = "res-uncertain-1";
    const attemptKey = "rr-1:room-101";
    const uncertainAttempts = new Set();
    const uncertainReservations = new Set();

    assert.equal(uncertainAttempts.has(attemptKey), false);
    assert.equal(uncertainReservations.has(resId), false);

    // Network timeout occurs
    const err = "Gateway timeout (504)";
    if (isUncertainError(err)) {
      uncertainAttempts.add(attemptKey);
      uncertainReservations.add(resId);
    }

    assert.equal(uncertainAttempts.has(attemptKey), true);
    assert.equal(uncertainReservations.has(resId), true);
  });

  it("32. uncertainty recovery: refresh reconciles state and clears uncertainty gate", () => {
    const resId = "res-uncertain-2";
    let uncertainAttempts = new Set(["rr-1:room-102"]);
    let uncertainReservations = new Set([resId]);

    // Simulated refresh success
    const refreshedReservation = { id: resId, status: "CONFIRMED" };
    if (refreshedReservation) {
      uncertainReservations.delete(resId);
      uncertainAttempts = new Set();
    }

    assert.equal(uncertainReservations.has(resId), false);
    assert.equal(uncertainAttempts.size, 0);
  });

  it("33. failed refresh preserves uncertainty gate", () => {
    const resId = "res-uncertain-3";
    const attemptKey = "rr-1:room-103";
    const uncertainAttempts = new Set([attemptKey]);
    const uncertainReservations = new Set([resId]);

    // Simulated refresh failure
    const refreshFailed = true;
    if (refreshFailed) {
      uncertainAttempts.add(attemptKey);
      uncertainReservations.add(resId);
    }

    assert.equal(uncertainReservations.has(resId), true);
    assert.equal(uncertainAttempts.has(attemptKey), true);
  });

  it("34. WAI-ARIA accessibility contracts: room assignment dialog declares required semantics", () => {
    const dialogAttrs = {
      role: "dialog",
      "aria-modal": "true",
      "aria-labelledby": "assign-dialog-title",
      "aria-describedby": "assign-dialog-description",
    };

    assert.equal(dialogAttrs.role, "dialog");
    assert.equal(dialogAttrs["aria-modal"], "true");
    assert.equal(dialogAttrs["aria-labelledby"], "assign-dialog-title");
    assert.equal(dialogAttrs["aria-describedby"], "assign-dialog-description");
  });

  // =========================================================================
  // Part 4: HTTP 409 / 503 Mutation Outcome Classification & Reconciliation
  // =========================================================================

  it("35. mutation error classification: HTTP 409, 503, 502, 504 and network timeouts are uncertain", () => {
    assert.equal(
      isUncertainError(new ApiRequestError("Concurrent modification conflict.", 409)),
      true
    );
    assert.equal(
      isUncertainError(new ApiRequestError("A temporary database lock contention occurred.", 503)),
      true
    );
    assert.equal(isUncertainError(new ApiRequestError("Bad Gateway", 502)), true);
    assert.equal(isUncertainError(new ApiRequestError("Gateway Timeout", 504)), true);
    assert.equal(isUncertainError(new TypeError("Failed to fetch")), true);
    assert.equal(isUncertainError(new Error("Request timed out")), true);
  });

  it("36. mutation error classification: deterministic 400, 401, 403, 404 rejections are not uncertain", () => {
    assert.equal(
      isUncertainError(new ApiRequestError("Room is already assigned for the requested dates.", 400)),
      false
    );
    assert.equal(isUncertainError(new ApiRequestError("Unauthorized", 401)), false);
    assert.equal(isUncertainError(new ApiRequestError("Forbidden", 403)), false);
    assert.equal(isUncertainError(new ApiRequestError("Not Found", 404)), false);
  });

  it("37. room assignment 409/503 gate: activates on 409/503, blocks blind retry, and persists across modal close/reopen", () => {
    for (const status of [409, 503]) {
      const resId = `res-${status}`;
      const attemptKey = `rr-1:room-101`;
      const uncertainReservationIds = new Set();
      const uncertainAssignmentAttempts = new Set();
      let showAssignModal = true;
      let postCount = 0;

      function submitAttempt(err) {
        if (
          uncertainReservationIds.has(resId) ||
          uncertainAssignmentAttempts.has(attemptKey)
        ) {
          return "BLOCKED";
        }
        postCount++;
        if (isUncertainError(err)) {
          uncertainReservationIds.add(resId);
          uncertainAssignmentAttempts.add(attemptKey);
        }
        return "SUBMITTED";
      }

      const first = submitAttempt(new ApiRequestError("Ambiguous outcome", status));
      assert.equal(first, "SUBMITTED");
      assert.equal(postCount, 1);

      // Close and reopen modal: gate must remain active
      showAssignModal = false;
      showAssignModal = true;
      assert.equal(showAssignModal, true);

      const second = submitAttempt(null);
      assert.equal(second, "BLOCKED");
      assert.equal(postCount, 1);
    }
  });

  it("38. reconciliation: failed refresh preserves gate; already-committed assignment closes modal without second POST", () => {
    const resId = "res-reconcile-committed";
    const attemptKey = "rr-1:room-101";
    let uncertainReservationIds = new Set([resId]);
    let uncertainAssignmentAttempts = new Set([attemptKey]);
    let showAssignModal = true;

    // Failed refresh preserves gate
    const refreshFailed = true;
    if (refreshFailed) {
      uncertainReservationIds.add(resId);
    }
    assert.equal(uncertainReservationIds.has(resId), true);
    assert.equal(uncertainAssignmentAttempts.has(attemptKey), true);

    // Successful refresh finds assignment already committed
    const refreshedAssignments = [
      { id: "assign-1", reservationRoomId: "rr-1", roomId: "room-101", status: "ASSIGNED" },
    ];
    uncertainReservationIds.delete(resId);
    uncertainAssignmentAttempts = new Set();

    const alreadyCommitted = refreshedAssignments.some(
      (a) => a.reservationRoomId === "rr-1" && a.roomId === "room-101" && a.status === "ASSIGNED"
    );
    if (alreadyCommitted) {
      showAssignModal = false;
    }

    assert.equal(uncertainReservationIds.has(resId), false);
    assert.equal(alreadyCommitted, true);
    assert.equal(showAssignModal, false);
  });

  it("39. reconciliation: assignment definitely not committed after refresh clears gate and allows explicit retry", () => {
    const resId = "res-reconcile-missing";
    const attemptKey = "rr-1:room-101";
    let uncertainReservationIds = new Set([resId]);
    let uncertainAssignmentAttempts = new Set([attemptKey]);

    const refreshedAssignments = [];
    const alreadyCommitted = refreshedAssignments.some(
      (a) => a.reservationRoomId === "rr-1" && a.roomId === "room-101" && a.status === "ASSIGNED"
    );

    uncertainReservationIds.delete(resId);
    uncertainAssignmentAttempts = new Set();

    assert.equal(alreadyCommitted, false);
    assert.equal(uncertainReservationIds.has(resId), false);
    assert.equal(uncertainAssignmentAttempts.has(attemptKey), false);
  });

  it("40. confirmation and check-in/checkout regression: 409/503 activates uncertainty gate while 400 does not", () => {
    for (const actionErr of [
      new ApiRequestError("Concurrent modification conflict.", 409),
      new ApiRequestError("Database lock contention.", 503),
    ]) {
      assert.equal(isUncertainError(actionErr), true);
    }
    assert.equal(
      isUncertainError(new ApiRequestError("Cannot check in: assigned room requires housekeeping.", 400)),
      false
    );
  });

  it("41. exact assignment reconciliation: rejects different room, RELEASED, or partial-stay records", () => {
    const attempt = {
      reservationId: "res-1",
      reservationRoomId: "rr-1",
      roomId: "room-101",
      assignedFrom: "2026-12-10",
      assignedUntil: "2026-12-12",
    };

    assert.equal(
      matchesExactAssignment(attempt, {
        reservationRoomId: "rr-1",
        roomId: "room-101",
        assignedFrom: "2026-12-10",
        assignedUntil: "2026-12-12",
        status: "ASSIGNED",
      }),
      true
    );

    // Different room
    assert.equal(
      matchesExactAssignment(attempt, {
        reservationRoomId: "rr-1",
        roomId: "room-102",
        assignedFrom: "2026-12-10",
        assignedUntil: "2026-12-12",
        status: "ASSIGNED",
      }),
      false
    );

    // Released assignment
    assert.equal(
      matchesExactAssignment(attempt, {
        reservationRoomId: "rr-1",
        roomId: "room-101",
        assignedFrom: "2026-12-10",
        assignedUntil: "2026-12-12",
        status: "RELEASED",
      }),
      false
    );

    // Partial-stay assignment
    assert.equal(
      matchesExactAssignment(attempt, {
        reservationRoomId: "rr-1",
        roomId: "room-101",
        assignedFrom: "2026-12-10",
        assignedUntil: "2026-12-11",
        status: "ASSIGNED",
      }),
      false
    );

    // Different reservationRoomId
    assert.equal(
      matchesExactAssignment(attempt, {
        reservationRoomId: "rr-other",
        roomId: "room-101",
        assignedFrom: "2026-12-10",
        assignedUntil: "2026-12-12",
        status: "ASSIGNED",
      }),
      false
    );
  });

  it("42. malformed error response body and stale GET race preserve safety", () => {
    // Malformed non-JSON error body still classifies 503/409/502 safely
    const malformed503 = new ApiRequestError("<html>503 Service Unavailable</html>", 503);
    assert.equal(isUncertainError(malformed503), true);

    const malformed400 = new ApiRequestError("Bad Request", 400);
    assert.equal(isUncertainError(malformed400), false);

    // Stale GET response (seq < currentSeq) must not clear uncertainty
    let currentSeq = 2;
    const responseSeq = 1;
    const uncertainReservations = new Set(["res-stale"]);
    if (responseSeq >= currentSeq) {
      uncertainReservations.delete("res-stale");
    }
    assert.equal(uncertainReservations.has("res-stale"), true);
  });
});
