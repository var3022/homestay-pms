import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";

// Ensure a valid session secret is available before importing session modules
process.env.PMS_SESSION_SECRET = "test-secret-at-least-32-characters-for-aes-256-gcm!";

// Import modules under test
import {
  encryptSession,
  decryptSession,
  isSessionExpiringSoon,
  getSessionSecret,
} from "../src/lib/auth/session.ts";

import {
  DEV_COOKIE_NAME,
  PROD_COOKIE_NAME,
  getSessionCookieName,
  getSessionCookieOptions,
  parseCookies,
  serializeSetCookie,
} from "../src/lib/auth/cookies.ts";

import {
  loginBackend,
  refreshBackend,
  logoutBackend,
  BackendAuthError,
  RefreshCollisionError,
  isRefreshCollision,
  extractCookieFromHeaders,
} from "../src/lib/auth/backend.ts";

import {
  isCsrfValid,
  verifyCsrf,
  sanitizeDashboardRedirect,
} from "../src/lib/auth/csrf.ts";

import { proxyToBackend } from "../src/lib/auth/proxy.ts";
import { POST as loginRoute } from "../src/app/api/auth/login/route.ts";
import { POST as refreshRoute } from "../src/app/api/auth/refresh/route.ts";
import { POST as logoutRoute } from "../src/app/api/auth/logout/route.ts";
import { GET as sessionRoute } from "../src/app/api/auth/session/route.ts";
import { proxy as middleware } from "../src/proxy.ts";
import { NextRequest } from "next/server";

describe("PMS Staff Authentication & Next.js API Bridge Test Suite", () => {
  let mockServer;
  let mockPort;
  let backendRefreshCallCount = 0;
  let backendLoginCallCount = 0;
  let backendLogoutCallCount = 0;
  let backendMutationCallCount = 0;
  let backendPatchCallCount = 0;
  let backendDeleteCallCount = 0;

  const validStaff = {
    id: "staff-uuid-1",
    email: "manager@homestay.local",
    firstName: "Alice",
    lastName: "Manager",
  };

  before(async () => {
    // Spin up mock backend HTTP server
    mockServer = http.createServer((req, res) => {
      const url = new URL(req.url, `http://${req.headers.host}`);

      if (url.pathname === "/api/v1/auth/login" && req.method === "POST") {
        backendLoginCallCount++;
        let body = "";
        req.on("data", (chunk) => (body += chunk));
        req.on("end", () => {
          const payload = JSON.parse(body);
          if (payload.email === "manager@homestay.local" && payload.password === "ValidPass123!") {
            res.writeHead(200, {
              "Content-Type": "application/json",
              "Set-Cookie": "pms-refresh-dev=valid-refresh-token-uuid-123; Path=/; HttpOnly; SameSite=Strict",
            });
            res.end(
              JSON.stringify({
                accessToken: "jwt-access-token-abc-123",
                tokenType: "Bearer",
                expiresInSeconds: 900,
                staff: validStaff,
              })
            );
          } else if (payload.email === "rate-limited@homestay.local") {
            res.writeHead(429, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ message: "Too many login attempts. Please try again later." }));
          } else {
            res.writeHead(401, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ message: "Invalid email or password." }));
          }
        });
      } else if (url.pathname === "/api/v1/auth/refresh" && req.method === "POST") {
        backendRefreshCallCount++;
        const cookie = req.headers["cookie"] || "";
        if (
          cookie.includes("pms-refresh-dev=valid-refresh-token-uuid-123") ||
          cookie.includes("pms-refresh-dev=refreshed-uuid-456")
        ) {
          setTimeout(() => {
            res.writeHead(200, {
              "Content-Type": "application/json",
              "Set-Cookie": "pms-refresh-dev=refreshed-uuid-456; Path=/; HttpOnly; SameSite=Strict",
            });
            res.end(
              JSON.stringify({
                accessToken: "jwt-access-token-new-456",
                tokenType: "Bearer",
                expiresInSeconds: 900,
                staff: validStaff,
              })
            );
          }, 25);
        } else if (cookie.includes("pms-refresh-dev=session-1-token")) {
          res.writeHead(200, {
            "Content-Type": "application/json",
            "Set-Cookie": "pms-refresh-dev=session-1-refreshed; Path=/; HttpOnly; SameSite=Strict",
          });
          res.end(
            JSON.stringify({
              accessToken: "jwt-session-1-token",
              tokenType: "Bearer",
              expiresInSeconds: 900,
              staff: validStaff,
            })
          );
        } else if (cookie.includes("pms-refresh-dev=reactive-get-token")) {
          res.writeHead(200, {
            "Content-Type": "application/json",
            "Set-Cookie": "pms-refresh-dev=reactive-get-refreshed; Path=/; HttpOnly; SameSite=Strict",
          });
          res.end(
            JSON.stringify({
              accessToken: "jwt-access-token-abc-123",
              tokenType: "Bearer",
              expiresInSeconds: 900,
              staff: validStaff,
            })
          );
        } else if (cookie.includes("pms-refresh-dev=reactive-post-token")) {
          res.writeHead(200, {
            "Content-Type": "application/json",
            "Set-Cookie": "pms-refresh-dev=reactive-post-refreshed; Path=/; HttpOnly; SameSite=Strict",
          });
          res.end(
            JSON.stringify({
              accessToken: "jwt-access-token-abc-123",
              tokenType: "Bearer",
              expiresInSeconds: 900,
              staff: validStaff,
            })
          );
        } else if (cookie.includes("pms-refresh-dev=session-2-token")) {
          res.writeHead(200, {
            "Content-Type": "application/json",
            "Set-Cookie": "pms-refresh-dev=session-2-refreshed; Path=/; HttpOnly; SameSite=Strict",
          });
          res.end(
            JSON.stringify({
              accessToken: "jwt-session-2-token",
              tokenType: "Bearer",
              expiresInSeconds: 900,
              staff: validStaff,
            })
          );
        } else if (cookie.includes("pms-refresh-dev=logout-race-token-999")) {
          setTimeout(() => {
            res.writeHead(200, {
              "Content-Type": "application/json",
              "Set-Cookie": "pms-refresh-dev=logout-race-refreshed; Path=/; HttpOnly; SameSite=Strict",
            });
            res.end(
              JSON.stringify({
                accessToken: "jwt-logout-race-token",
                tokenType: "Bearer",
                expiresInSeconds: 900,
                staff: validStaff,
              })
            );
          }, 30);
        } else if (cookie.includes("pms-refresh-dev=independent-session-token-789")) {
          // Independent session for Alice (e.g. tablet vs laptop)
          res.writeHead(200, {
            "Content-Type": "application/json",
            "Set-Cookie": "pms-refresh-dev=independent-refreshed-999; Path=/; HttpOnly; SameSite=Strict",
          });
          res.end(
            JSON.stringify({
              accessToken: "jwt-independent-access-token",
              tokenType: "Bearer",
              expiresInSeconds: 900,
              staff: validStaff,
            })
          );
        } else if (cookie.includes("pms-refresh-dev=collision-token-uuid-409")) {
          res.writeHead(409, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              status: 409,
              error: "Conflict",
              code: "REFRESH_CONCURRENCY_COLLISION",
              message: "Concurrent refresh token rotation collision: A concurrent refresh attempt rotated this session within the grace window",
              path: "/api/v1/auth/refresh",
            })
          );
        } else if (cookie.includes("pms-refresh-dev=generic-409-token")) {
          res.writeHead(409, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              status: 409,
              error: "Conflict",
              message: "State conflict occurred",
              path: "/api/v1/auth/refresh",
            })
          );
        } else if (cookie.includes("pms-refresh-dev=transient-502-token")) {
          res.writeHead(502, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ message: "Bad Gateway" }));
        } else if (cookie.includes("pms-refresh-dev=transient-503-token")) {
          res.writeHead(503, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ message: "Database temporarily unavailable" }));
        } else if (cookie.includes("pms-refresh-dev=reactive-get-collision-token")) {
          res.writeHead(409, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              status: 409,
              error: "Conflict",
              code: "REFRESH_CONCURRENCY_COLLISION",
              message: "Concurrent refresh token rotation collision",
              path: "/api/v1/auth/refresh",
            })
          );
        } else if (cookie.includes("pms-refresh-dev=reactive-post-collision-token")) {
          res.writeHead(409, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              status: 409,
              error: "Conflict",
              code: "REFRESH_CONCURRENCY_COLLISION",
              message: "Concurrent refresh token rotation collision",
              path: "/api/v1/auth/refresh",
            })
          );
        } else {
          res.writeHead(401, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ message: "Refresh token revoked or expired." }));
        }
      } else if (url.pathname === "/api/v1/auth/logout" && req.method === "POST") {
        backendLogoutCallCount++;
        res.writeHead(200, {
          "Content-Type": "application/json",
          "Set-Cookie": "pms-refresh-dev=; Path=/; Max-Age=0",
        });
        res.end(JSON.stringify({ message: "Logged out successfully" }));
      } else if (url.pathname === "/api/v1/rooms" && req.method === "GET") {
        const auth = req.headers["authorization"];
        if (auth === "Bearer jwt-access-token-abc-123" || auth === "Bearer jwt-access-token-new-456") {
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(JSON.stringify([{ id: "room-1", roomNumber: "101", propertyId: url.searchParams.get("propertyId") }]));
        } else if (auth === "Bearer jwt-expired-token" || auth === "Bearer jwt-reactive-get-collision") {
          res.writeHead(401, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ message: "Access token expired" }));
        } else {
          res.writeHead(401, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ message: "Unauthorized" }));
        }
      } else if (url.pathname === "/api/v1/reservations" && req.method === "POST") {
        backendMutationCallCount++;
        const auth = req.headers["authorization"];
        if (auth === "Bearer jwt-expired-token" || auth === "Bearer jwt-reactive-post-collision") {
          // Expired access token on mutation
          res.writeHead(401, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ message: "Access token expired during mutation" }));
          return;
        }
        let body = "";
        req.on("data", (chunk) => (body += chunk));
        req.on("end", () => {
          const parsed = JSON.parse(body);
          res.writeHead(201, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ id: "res-new-1", propertyId: parsed.propertyId }));
        });
      } else if (url.pathname === "/api/v1/rooms/101" && req.method === "PATCH") {
        backendPatchCallCount++;
        const auth = req.headers["authorization"];
        if (auth === "Bearer jwt-expired-token") {
          res.writeHead(401, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ message: "Access token expired during PATCH" }));
          return;
        }
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ success: true, updated: true }));
      } else if (url.pathname === "/api/v1/reservations/res-1" && req.method === "DELETE") {
        backendDeleteCallCount++;
        const auth = req.headers["authorization"];
        if (auth === "Bearer jwt-expired-token") {
          res.writeHead(401, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ message: "Access token expired during DELETE" }));
          return;
        }
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ success: true, deleted: true }));
      } else {
        res.writeHead(404, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ message: "Not found" }));
      }
    });

    await new Promise((resolve) => {
      mockServer.listen(0, "127.0.0.1", () => {
        mockPort = mockServer.address().port;
        process.env.PMS_API_BASE_URL = `http://127.0.0.1:${mockPort}`;
        process.env.PMS_FRONTEND_ORIGIN = "http://localhost:3000";
        process.env.PMS_PROPERTY_ID = "test-prop-uuid-999";
        resolve();
      });
    });
  });

  after(() => {
    if (mockServer) mockServer.close();
  });

  // ==========================================
  // 1. Session Cryptography & Integrity
  // ==========================================
  describe("1. Session Cryptography, Entropy & AES-256-GCM Integrity", () => {
    it("encrypts and decrypts valid session data round-trip", async () => {
      const original = {
        accessToken: "jwt.header.payload.sig",
        accessTokenExpiresAt: Date.now() + 900000,
        refreshToken: "refresh-uuid-abc",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };

      const encrypted = await encryptSession(original);
      assert.ok(typeof encrypted === "string");
      assert.ok(encrypted.length > 50);

      const decrypted = await decryptSession(encrypted);
      assert.ok(decrypted);
      assert.equal(decrypted.accessToken, original.accessToken);
      assert.equal(decrypted.refreshToken, original.refreshToken);
      assert.equal(decrypted.staff.id, original.staff.id);
      assert.equal(decrypted.sessionVersion, 1);
    });

    it("tamper resistance: modified ciphertext fails authentication tag and returns null", async () => {
      const original = {
        accessToken: "jwt.header.payload.sig",
        accessTokenExpiresAt: Date.now() + 900000,
        refreshToken: "refresh-uuid-abc",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };

      const encrypted = await encryptSession(original);
      const chars = encrypted.split("");
      chars[chars.length - 2] = chars[chars.length - 2] === "A" ? "B" : "A";
      const tampered = chars.join("");

      const result = await decryptSession(tampered);
      assert.equal(result, null, "Tampered ciphertext must fail decryption and return null");
    });

    it("tamper resistance: modified IV returns null", async () => {
      const original = {
        accessToken: "jwt.header.payload.sig",
        accessTokenExpiresAt: Date.now() + 900000,
        refreshToken: "refresh-uuid-abc",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };

      const encrypted = await encryptSession(original);
      const chars = encrypted.split("");
      chars[2] = chars[2] === "X" ? "Y" : "X";
      const tampered = chars.join("");

      const result = await decryptSession(tampered);
      assert.equal(result, null, "Tampered IV must fail decryption and return null");
    });

    it("handles null, empty, corrupt or oversized cookies safely without throwing", async () => {
      assert.equal(await decryptSession(null), null);
      assert.equal(await decryptSession(""), null);
      assert.equal(await decryptSession("not-base64-random!"), null);
      assert.equal(await decryptSession("short"), null);
      assert.equal(await decryptSession("a".repeat(5000)), null, "Oversized cookie (>4096) must return null");
    });

    it("entropy check: fails closed and throws error when PMS_SESSION_SECRET is weak or missing", () => {
      const origSecret = process.env.PMS_SESSION_SECRET;
      try {
        delete process.env.PMS_SESSION_SECRET;
        assert.throws(() => getSessionSecret(), /missing or less than 32 characters/);

        process.env.PMS_SESSION_SECRET = "short-secret";
        assert.throws(() => getSessionSecret(), /missing or less than 32 characters/);
      } finally {
        process.env.PMS_SESSION_SECRET = origSecret;
      }
    });

    it("isSessionExpiringSoon identifies tokens within 60s of expiry", () => {
      const now = Date.now();
      const sessionExpiringSoon = {
        accessTokenExpiresAt: now + 30000, // 30s remaining
      };
      const sessionFresh = {
        accessTokenExpiresAt: now + 800000, // ~13 mins remaining
      };
      assert.equal(isSessionExpiringSoon(sessionExpiringSoon, 60000), true);
      assert.equal(isSessionExpiringSoon(sessionFresh, 60000), false);
    });
  });

  // ==========================================
  // 2. Cookie Helpers & Production Flags
  // ==========================================
  describe("2. Cookie Helpers & Production Security Flags", () => {
    it("returns correct cookie names based on environment", () => {
      const originalNodeEnv = process.env.NODE_ENV;
      try {
        process.env.NODE_ENV = "development";
        assert.equal(getSessionCookieName(), DEV_COOKIE_NAME);

        process.env.NODE_ENV = "production";
        assert.equal(getSessionCookieName(), PROD_COOKIE_NAME);
      } finally {
        process.env.NODE_ENV = originalNodeEnv;
      }
    });

    it("returns hardened cookie options: HttpOnly, SameSite=Lax, Path=/", () => {
      const opts = getSessionCookieOptions();
      assert.equal(opts.httpOnly, true);
      assert.equal(opts.sameSite, "lax");
      assert.equal(opts.path, "/");
      assert.ok(opts.maxAge > 0);
    });

    it("parses cookies correctly from header", () => {
      const header = "pms_session_dev=xyz123; other=hello; theme=dark";
      const parsed = parseCookies(header);
      assert.equal(parsed.pms_session_dev, "xyz123");
      assert.equal(parsed.other, "hello");
      assert.equal(parsed.theme, "dark");
    });

    it("serializes Set-Cookie correctly with security attributes", () => {
      const serialized = serializeSetCookie("test-cookie", "val-123", {
        httpOnly: true,
        secure: true,
        sameSite: "strict",
        path: "/",
        maxAge: 3600,
      });
      assert.ok(serialized.includes("test-cookie=val-123"));
      assert.ok(serialized.includes("HttpOnly"));
      assert.ok(serialized.includes("Secure"));
      assert.ok(serialized.includes("SameSite=Strict"));
      assert.ok(serialized.includes("Path=/"));
      assert.ok(serialized.includes("Max-Age=3600"));
    });

    it("extracts refresh token cookie from backend response headers", () => {
      const headers = new Headers();
      headers.append("Set-Cookie", "pms-refresh-dev=token-abc-999; Path=/; HttpOnly");
      const extracted = extractCookieFromHeaders(headers);
      assert.ok(extracted);
      assert.equal(extracted.name, "pms-refresh-dev");
      assert.equal(extracted.value, "token-abc-999");
    });
  });

  // ==========================================
  // 3. Strict CSRF Validation Engine
  // ==========================================
  describe("3. Strict CSRF Validation & Bypass Rejection", () => {
    it("verifyCsrf allows safe GET/HEAD/OPTIONS requests unconditionally", () => {
      const reqGet = new Request("http://localhost:3000/api/rooms", { method: "GET" });
      assert.equal(verifyCsrf(reqGet).valid, true);
      assert.equal(isCsrfValid(reqGet), true);

      const reqHead = new Request("http://localhost:3000/api/rooms", { method: "HEAD" });
      assert.equal(verifyCsrf(reqHead).valid, true);
      assert.equal(isCsrfValid(reqHead), true);
    });

    it("verifyCsrf rejects mutating requests with foreign Origin EVEN WITH X-PMS-Client header", () => {
      const req = new Request("http://localhost:3000/api/reservations", {
        method: "POST",
        headers: {
          origin: "http://attacker-site.com",
          "x-pms-client": "staff-web",
        },
      });
      const res = verifyCsrf(req);
      assert.equal(res.valid, false);
      assert.ok(res.reason.includes("Foreign Origin"));
    });

    it("verifyCsrf rejects mutating requests with foreign Origin EVEN WITH X-Requested-With header", () => {
      const req = new Request("http://localhost:3000/api/reservations", {
        method: "POST",
        headers: {
          origin: "http://evil.com",
          "x-requested-with": "XMLHttpRequest",
        },
      });
      const res = verifyCsrf(req);
      assert.equal(res.valid, false);
      assert.ok(res.reason.includes("Foreign Origin"));
    });

    it("verifyCsrf rejects mutating requests with missing Origin AND missing Referer", () => {
      const req = new Request("http://localhost:3000/api/reservations", {
        method: "POST",
        headers: {
          "x-pms-client": "staff-web",
        },
      });
      const res = verifyCsrf(req);
      assert.equal(res.valid, false);
      assert.ok(res.reason.includes("Missing origin evidence"));
    });

    it("verifyCsrf rejects conflicting Origin and Referer headers", () => {
      const req = new Request("http://localhost:3000/api/reservations", {
        method: "POST",
        headers: {
          origin: "http://localhost:3000",
          referer: "http://attacker-site.com/dashboard",
        },
      });
      const res = verifyCsrf(req);
      assert.equal(res.valid, false);
      assert.ok(res.reason.includes("Foreign Referer") || res.reason.includes("Conflicting"));
    });

    it("verifyCsrf rejects null origins", () => {
      const req = new Request("http://localhost:3000/api/reservations", {
        method: "POST",
        headers: {
          origin: "null",
        },
      });
      const res = verifyCsrf(req);
      assert.equal(res.valid, false);
      assert.ok(res.reason.includes("Null origin"));
    });

    it("verifyCsrf allows legitimate same-origin requests with matching Origin", () => {
      const req = new Request("http://localhost:3000/api/reservations", {
        method: "POST",
        headers: {
          origin: "http://localhost:3000",
        },
      });
      assert.equal(verifyCsrf(req).valid, true);
    });

    it("verifyCsrf allows legitimate same-origin requests with matching Referer when Origin omitted", () => {
      const req = new Request("http://localhost:3000/api/reservations", {
        method: "POST",
        headers: {
          referer: "http://localhost:3000/dashboard",
        },
      });
      assert.equal(verifyCsrf(req).valid, true);
    });
  });

  // ==========================================
  // 4. Login Open-Redirect Validation
  // ==========================================
  describe("4. Login Open-Redirect Prevention", () => {
    it("sanitizeDashboardRedirect permits valid internal dashboard routes", () => {
      assert.equal(sanitizeDashboardRedirect("/dashboard"), "/dashboard");
      assert.equal(sanitizeDashboardRedirect("/dashboard/reservations"), "/dashboard/reservations");
      assert.equal(
        sanitizeDashboardRedirect("/dashboard/reservations?view=calendar&tab=all"),
        "/dashboard/reservations?view=calendar&tab=all"
      );
    });

    it("sanitizeDashboardRedirect rejects external absolute URLs", () => {
      assert.equal(sanitizeDashboardRedirect("https://evil.com"), "/dashboard");
      assert.equal(sanitizeDashboardRedirect("http://attacker.com/dashboard"), "/dashboard");
    });

    it("sanitizeDashboardRedirect rejects protocol-relative URLs (//evil.com)", () => {
      assert.equal(sanitizeDashboardRedirect("//evil.com"), "/dashboard");
      assert.equal(sanitizeDashboardRedirect("//evil.com/dashboard"), "/dashboard");
    });

    it("sanitizeDashboardRedirect rejects backslash bypasses (\\evil.com, /\\evil.com)", () => {
      assert.equal(sanitizeDashboardRedirect("/\\evil.com"), "/dashboard");
      assert.equal(sanitizeDashboardRedirect("\\evil.com"), "/dashboard");
    });

    it("sanitizeDashboardRedirect rejects encoded bypasses (%2F%2Fevil.com)", () => {
      assert.equal(sanitizeDashboardRedirect("%2F%2Fevil.com"), "/dashboard");
      assert.equal(sanitizeDashboardRedirect("/%2Fevil.com"), "/dashboard");
    });

    it("sanitizeDashboardRedirect rejects javascript: and data: schemes", () => {
      assert.equal(sanitizeDashboardRedirect("javascript:alert(1)"), "/dashboard");
      assert.equal(sanitizeDashboardRedirect("data:text/html,<script>alert(1)</script>"), "/dashboard");
    });

    it("sanitizeDashboardRedirect rejects non-dashboard internal routes", () => {
      assert.equal(sanitizeDashboardRedirect("/login"), "/dashboard");
      assert.equal(sanitizeDashboardRedirect("/api/auth/logout"), "/dashboard");
      assert.equal(sanitizeDashboardRedirect("/"), "/dashboard");
    });
  });

  // ==========================================
  // 5. Backend Client & Rotation Concurrency
  // ==========================================
  describe("5. Backend Client & Refresh Rotation Concurrency", () => {
    it("loginBackend succeeds with valid credentials and sets sessionVersion 1", async () => {
      const session = await loginBackend({
        email: "manager@homestay.local",
        password: "ValidPass123!",
      });
      assert.ok(session);
      assert.equal(session.accessToken, "jwt-access-token-abc-123");
      assert.equal(session.refreshToken, "valid-refresh-token-uuid-123");
      assert.equal(session.staff.email, "manager@homestay.local");
      assert.equal(session.sessionVersion, 1);
      assert.ok(backendLoginCallCount > 0);
    });

    it("loginBackend handles 401 invalid credentials cleanly", async () => {
      await assert.rejects(
        () =>
          loginBackend({
            email: "manager@homestay.local",
            password: "WrongPassword!",
          }),
        (err) => {
          assert.ok(err instanceof BackendAuthError);
          assert.equal(err.status, 401);
          assert.equal(err.message, "Invalid email or password.");
          return true;
        }
      );
    });

    it("loginBackend handles 429 rate limit cleanly", async () => {
      await assert.rejects(
        () =>
          loginBackend({
            email: "rate-limited@homestay.local",
            password: "any",
          }),
        (err) => {
          assert.ok(err instanceof BackendAuthError);
          assert.equal(err.status, 429);
          assert.ok(err.message.includes("Too many login attempts"));
          return true;
        }
      );
    });

    it("refreshBackend deduplicates concurrent refresh calls for the same session token", async () => {
      const startCount = backendRefreshCallCount;

      const session = {
        accessToken: "jwt-expired-token",
        accessTokenExpiresAt: Date.now() - 1000,
        refreshToken: "valid-refresh-token-uuid-123",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };

      const results = await Promise.all([
        refreshBackend(session),
        refreshBackend(session),
        refreshBackend(session),
        refreshBackend(session),
      ]);

      const endCount = backendRefreshCallCount;
      assert.equal(
        endCount - startCount,
        1,
        "Exactly 1 backend refresh request must be sent despite 4 concurrent callers for the same token"
      );

      for (const res of results) {
        assert.equal(res.accessToken, "jwt-access-token-new-456");
        assert.equal(res.refreshToken, "refreshed-uuid-456");
        assert.equal(res.sessionVersion, 2);
      }
    });

    it("refreshBackend does not conflate independent sessions belonging to the same staff account", async () => {
      const startCount = backendRefreshCallCount;

      const session1 = {
        accessToken: "jwt-token-1",
        accessTokenExpiresAt: Date.now() - 1000,
        refreshToken: "session-1-token", // Laptop session
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };

      const session2 = {
        accessToken: "jwt-token-2",
        accessTokenExpiresAt: Date.now() - 1000,
        refreshToken: "session-2-token", // Tablet session
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff, // Same staff user!
        sessionVersion: 1,
      };

      const [res1, res2] = await Promise.all([
        refreshBackend(session1),
        refreshBackend(session2),
      ]);

      assert.equal(backendRefreshCallCount - startCount, 2, "Both independent sessions must trigger distinct refresh calls");
      assert.equal(res1.refreshToken, "session-1-refreshed");
      assert.equal(res2.refreshToken, "session-2-refreshed");
    });

    it("refreshBackend serves delayed requests within grace window from recent rotation cache", async () => {
      const startCount = backendRefreshCallCount;

      const session = {
        accessToken: "jwt-expired-token",
        accessTokenExpiresAt: Date.now() - 1000,
        refreshToken: "valid-refresh-token-uuid-123", // Has already been rotated to refreshed-uuid-456
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };

      // Firing a delayed request with the old token should be resolved from the recent rotation cache
      const cachedResult = await refreshBackend(session);
      assert.equal(cachedResult.refreshToken, "refreshed-uuid-456");
      assert.equal(backendRefreshCallCount, startCount, "No new backend call should be made for recently rotated token");
    });

    it("logoutBackend executes gracefully and clears caches", async () => {
      const session = {
        accessToken: "jwt-token",
        accessTokenExpiresAt: Date.now() + 10000,
        refreshToken: "logout-test-token-777",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      await logoutBackend(session);
      assert.ok(backendLogoutCallCount > 0);
    });

    it("logout racing with refresh: in-flight refresh is rejected and cannot resurrect session", async () => {
      const session = {
        accessToken: "jwt-expired-token",
        accessTokenExpiresAt: Date.now() - 1000,
        refreshToken: "logout-race-token-999",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };

      // Start refresh (which takes ~30ms on mock server)
      const refreshPromise = refreshBackend(session);

      // Immediately invoke logout while refresh is in flight
      await logoutBackend(session);

      // Awaiting the refresh promise must reject with 401
      await assert.rejects(
        refreshPromise,
        (err) => {
          assert.ok(err instanceof BackendAuthError);
          assert.equal(err.status, 401);
          assert.ok(err.message.includes("logged out"));
          return true;
        }
      );
    });
  });

  // ==========================================
  // 6. Proxying & Non-Idempotent Mutation Safety
  // ==========================================
  describe("6. Proxying & Non-Idempotent Mutation Safety", () => {
    it("proxyToBackend rejects unauthenticated request with 401", async () => {
      const req = new Request("http://localhost:3000/api/rooms", { method: "GET" });
      const res = await proxyToBackend(req, "/api/v1/rooms");
      assert.equal(res.status, 401);
      assert.equal(res.headers.get("Cache-Control"), "no-store, private");
    });

    it("proxyToBackend rejects mutating request on CSRF failure with 403", async () => {
      const original = {
        accessToken: "jwt-access-token-abc-123",
        accessTokenExpiresAt: Date.now() + 900000,
        refreshToken: "valid-refresh-token-uuid-123",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(original);

      const req = new Request("http://localhost:3000/api/reservations", {
        method: "POST",
        headers: {
          cookie: `${DEV_COOKIE_NAME}=${cookieVal}`,
          origin: "http://attacker-site.com",
          "x-pms-client": "staff-web",
        },
      });

      const res = await proxyToBackend(req, "/api/v1/reservations");
      assert.equal(res.status, 403);
      const data = await res.json();
      assert.ok(data.error.includes("CSRF check failed"));
    });

    it("proxyToBackend rejects path traversal in backendPath with 400", async () => {
      const original = {
        accessToken: "jwt-access-token-abc-123",
        accessTokenExpiresAt: Date.now() + 900000,
        refreshToken: "valid-refresh-token-uuid-123",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(original);

      const req = new Request("http://localhost:3000/api/rooms", {
        method: "GET",
        headers: { cookie: `${DEV_COOKIE_NAME}=${cookieVal}` },
      });

      const res = await proxyToBackend(req, "/api/v1/rooms/../../etc/passwd");
      assert.equal(res.status, 400);
    });

    it("proxyToBackend successfully forwards authenticated GET with Bearer token & Cache-Control: no-store", async () => {
      const original = {
        accessToken: "jwt-access-token-abc-123",
        accessTokenExpiresAt: Date.now() + 900000,
        refreshToken: "valid-refresh-token-uuid-123",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(original);

      const req = new Request("http://localhost:3000/api/rooms?customFilter=active", {
        method: "GET",
        headers: { cookie: `${DEV_COOKIE_NAME}=${cookieVal}` },
      });

      const res = await proxyToBackend(req, "/api/v1/rooms", { requirePropertyId: true });
      assert.equal(res.status, 200);
      assert.ok(res.headers.get("Cache-Control").includes("no-store"));
      const data = await res.json();
      assert.equal(data[0].id, "room-1");
      assert.equal(data[0].propertyId, "test-prop-uuid-999");
    });

    it("proxyToBackend performs reactive retry ONLY for idempotent GET on 401", async () => {
      const sessionExpired = {
        accessToken: "jwt-expired-token",
        accessTokenExpiresAt: Date.now() + 300000,
        refreshToken: "reactive-get-token",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(sessionExpired);

      const req = new Request("http://localhost:3000/api/rooms", {
        method: "GET",
        headers: { cookie: `${DEV_COOKIE_NAME}=${cookieVal}` },
      });

      const res = await proxyToBackend(req, "/api/v1/rooms", { requirePropertyId: true });
      assert.equal(res.status, 200);
      const data = await res.json();
      assert.equal(data[0].id, "room-1");

      const setCookie = res.headers.get("set-cookie");
      assert.ok(setCookie);
      assert.ok(setCookie.includes(DEV_COOKIE_NAME));
    });

    it("NON-IDEMPOTENT SAFETY: proxyToBackend DOES NOT silently replay POST mutations on 401", async () => {
      const initialMutationCount = backendMutationCallCount;

      const sessionExpired = {
        accessToken: "jwt-expired-token", // Will trigger 401 in mock backend
        accessTokenExpiresAt: Date.now() + 300000,
        refreshToken: "reactive-post-token",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(sessionExpired);

      const req = new Request("http://localhost:3000/api/reservations", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          origin: "http://localhost:3000",
          cookie: `${DEV_COOKIE_NAME}=${cookieVal}`,
        },
        body: JSON.stringify({ checkInDate: "2026-12-01", checkOutDate: "2026-12-05" }),
      });

      const res = await proxyToBackend(req, "/api/v1/reservations");

      // Verify that backend mutation was called EXACTLY ONCE and NOT retried!
      assert.equal(backendMutationCallCount - initialMutationCount, 1, "POST must NOT be executed a second time");

      // Verify client receives 401 indicating reauthentication without duplicate mutation
      assert.equal(res.status, 401);
      const data = await res.json();
      assert.equal(data.reauthenticated, true);
      assert.ok(data.error.includes("resubmit"));

      // Verify updated cookie is attached so user's subsequent action succeeds
      const setCookie = res.headers.get("set-cookie");
      assert.ok(setCookie);
    });

    it("NON-IDEMPOTENT SAFETY: proxyToBackend DOES NOT silently replay PATCH or DELETE on 401", async () => {
      const initialPatch = backendPatchCallCount;
      const initialDelete = backendDeleteCallCount;

      const sessionExpired = {
        accessToken: "jwt-expired-token",
        accessTokenExpiresAt: Date.now() + 300000,
        refreshToken: "reactive-post-token",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(sessionExpired);

      // Test PATCH
      const reqPatch = new Request("http://localhost:3000/api/rooms", {
        method: "PATCH",
        headers: { origin: "http://localhost:3000", cookie: `${DEV_COOKIE_NAME}=${cookieVal}` },
      });
      const resPatch = await proxyToBackend(reqPatch, "/api/v1/rooms/101");
      assert.equal(backendPatchCallCount - initialPatch, 1, "PATCH must not be retried");
      assert.equal(resPatch.status, 401);

      // Test DELETE
      const reqDelete = new Request("http://localhost:3000/api/reservations", {
        method: "DELETE",
        headers: { origin: "http://localhost:3000", cookie: `${DEV_COOKIE_NAME}=${cookieVal}` },
      });
      const resDelete = await proxyToBackend(reqDelete, "/api/v1/reservations/res-1");
      assert.equal(backendDeleteCallCount - initialDelete, 1, "DELETE must not be retried");
      assert.equal(resDelete.status, 401);
    });

    it("proxyToBackend preserves explicit client-provided propertyId", async () => {
      const session = {
        accessToken: "jwt-access-token-abc-123",
        accessTokenExpiresAt: Date.now() + 900000,
        refreshToken: "valid-refresh-token-uuid-123",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(session);

      const req = new Request("http://localhost:3000/api/reservations", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          origin: "http://localhost:3000",
          cookie: `${DEV_COOKIE_NAME}=${cookieVal}`,
        },
        body: JSON.stringify({
          checkInDate: "2026-12-01",
          propertyId: "custom-property-uuid-explicit",
        }),
      });

      const res = await proxyToBackend(req, "/api/v1/reservations", { injectPropertyId: true });
      assert.equal(res.status, 201);
      const data = await res.json();
      assert.equal(data.propertyId, "custom-property-uuid-explicit", "Explicit propertyId must not be overwritten");
    });
  });

  // ==========================================
  // 7. Auth Route Handlers & CSRF Protection
  // ==========================================
  describe("7. Auth Route Handlers & CSRF Protection", () => {
    it("POST /api/auth/login rejects cross-site login CSRF attempt (403)", async () => {
      const req = new Request("http://localhost:3000/api/auth/login", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          origin: "http://attacker.com",
        },
        body: JSON.stringify({ email: "manager@homestay.local", password: "ValidPass123!" }),
      });
      const res = await loginRoute(req);
      assert.equal(res.status, 403);
      assert.ok((await res.json()).error.includes("CSRF"));
    });

    it("POST /api/auth/login succeeds with same-origin and sets encrypted cookie", async () => {
      const req = new Request("http://localhost:3000/api/auth/login", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          origin: "http://localhost:3000",
        },
        body: JSON.stringify({
          email: "manager@homestay.local",
          password: "ValidPass123!",
        }),
      });

      const res = await loginRoute(req);
      assert.equal(res.status, 200);

      const data = await res.json();
      assert.equal(data.success, true);
      assert.equal(data.staff.email, "manager@homestay.local");
      assert.equal(data.accessToken, undefined, "Raw accessToken must NOT be leaked to client");
      assert.equal(data.refreshToken, undefined, "Raw refreshToken must NOT be leaked to client");

      const setCookie = res.headers.get("set-cookie");
      assert.ok(setCookie.includes(DEV_COOKIE_NAME));
      assert.ok(setCookie.includes("HttpOnly"));
      assert.ok(res.headers.get("Cache-Control").includes("no-store"));
    });

    it("POST /api/auth/logout rejects cross-site logout attempt (403)", async () => {
      const req = new Request("http://localhost:3000/api/auth/logout", {
        method: "POST",
        headers: {
          origin: "http://attacker.com",
        },
      });
      const res = await logoutRoute(req);
      assert.equal(res.status, 403);
    });

    it("POST /api/auth/logout succeeds with same-origin and clears cookie", async () => {
      const session = {
        accessToken: "jwt-access-token-abc-123",
        accessTokenExpiresAt: Date.now() + 900000,
        refreshToken: "logout-route-token-888",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(session);

      const req = new Request("http://localhost:3000/api/auth/logout", {
        method: "POST",
        headers: {
          origin: "http://localhost:3000",
          cookie: `${DEV_COOKIE_NAME}=${cookieVal}`,
        },
      });

      const res = await logoutRoute(req);
      assert.equal(res.status, 200);
      const setCookie = res.headers.get("set-cookie");
      assert.ok(setCookie.includes("Max-Age=0") || setCookie.includes("max-age=0"));
    });

    it("POST /api/auth/refresh rejects cross-site refresh attempt (403)", async () => {
      const req = new Request("http://localhost:3000/api/auth/refresh", {
        method: "POST",
        headers: { origin: "http://attacker.com" },
      });
      const res = await refreshRoute(req);
      assert.equal(res.status, 403);
    });

    it("GET /api/auth/session returns authenticated true and Cache-Control: no-store", async () => {
      const session = {
        accessToken: "jwt-access-token-abc-123",
        accessTokenExpiresAt: Date.now() + 900000,
        refreshToken: "valid-refresh-token-uuid-123",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(session);

      const req = new Request("http://localhost:3000/api/auth/session", {
        method: "GET",
        headers: { cookie: `${DEV_COOKIE_NAME}=${cookieVal}` },
      });

      const res = await sessionRoute(req);
      assert.equal(res.status, 200);
      assert.ok(res.headers.get("Cache-Control").includes("no-store"));
      const data = await res.json();
      assert.equal(data.authenticated, true);
      assert.equal(data.user.id, "staff-uuid-1");
    });
  });

  // ==========================================
  // 8. Next.js Proxy Route Protection
  // ==========================================
  describe("8. Next.js Proxy Route Protection", () => {
    it("redirects unauthenticated request for /dashboard to /login", async () => {
      const req = new NextRequest("http://localhost:3000/dashboard");
      const res = await middleware(req);
      assert.equal(res.status, 307);
      assert.ok(res.headers.get("location").includes("/login?redirect=%2Fdashboard"));
    });

    it("redirects unauthenticated request for nested /dashboard/reservations with query string", async () => {
      const req = new NextRequest("http://localhost:3000/dashboard/reservations?view=calendar");
      const res = await middleware(req);
      assert.equal(res.status, 307);
      assert.ok(
        res.headers.get("location").includes("/login?redirect=%2Fdashboard%2Freservations%3Fview%3Dcalendar")
      );
    });

    it("redirects tampered session cookie on /dashboard to /login and clears cookie", async () => {
      const req = new NextRequest("http://localhost:3000/dashboard", {
        headers: { cookie: `${DEV_COOKIE_NAME}=corrupt-tampered-token` },
      });
      const res = await middleware(req);
      assert.equal(res.status, 307);
      assert.ok(res.headers.get("location").includes("/login"));
      const setCookie = res.headers.get("set-cookie");
      assert.ok(setCookie);
    });

    it("allows authenticated request for /dashboard to proceed", async () => {
      const session = {
        accessToken: "jwt-access-token-abc-123",
        accessTokenExpiresAt: Date.now() + 900000,
        refreshToken: "valid-refresh-token-uuid-123",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(session);

      const req = new NextRequest("http://localhost:3000/dashboard", {
        headers: { cookie: `${DEV_COOKIE_NAME}=${cookieVal}` },
      });
      const res = await middleware(req);
      assert.equal(res.status, 200);
    });

    it("redirects already authenticated staff visiting /login to /dashboard", async () => {
      const session = {
        accessToken: "jwt-access-token-abc-123",
        accessTokenExpiresAt: Date.now() + 900000,
        refreshToken: "valid-refresh-token-uuid-123",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(session);

      const req = new NextRequest("http://localhost:3000/login", {
        headers: { cookie: `${DEV_COOKIE_NAME}=${cookieVal}` },
      });
      const res = await middleware(req);
      assert.equal(res.status, 307);
      assert.ok(res.headers.get("location").includes("/dashboard"));
    });
  });

  // ==========================================
  // 9. Zero Token Leakage Verification
  // ==========================================
  describe("9. Zero Token Leakage Verification", () => {
    it("ensures raw JWT access token and refresh token are never exposed to browser in body", async () => {
      const req = new Request("http://localhost:3000/api/auth/login", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          origin: "http://localhost:3000",
        },
        body: JSON.stringify({
          email: "manager@homestay.local",
          password: "ValidPass123!",
        }),
      });

      const res = await loginRoute(req);
      const text = await res.text();

      assert.equal(text.includes("jwt-access-token-abc-123"), false);
      assert.equal(text.includes("valid-refresh-token-uuid-123"), false);
      assert.equal(text.includes(process.env.PMS_SESSION_SECRET), false);
    });
  });

  // ==========================================
  // 10. V14 Structured Refresh Collision & Session Cookie Safety
  // ==========================================
  describe("10. V14 Structured Refresh Collision & Session Cookie Safety", () => {
    it("A. recognizes exact HTTP 409 REFRESH_CONCURRENCY_COLLISION as RefreshCollisionError", async () => {
      const session = {
        accessToken: "jwt-expired-token",
        accessTokenExpiresAt: Date.now() - 1000,
        refreshToken: "collision-token-uuid-409",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };

      await assert.rejects(
        async () => {
          await refreshBackend(session);
        },
        (err) => {
          assert.ok(err instanceof RefreshCollisionError);
          assert.ok(isRefreshCollision(err));
          assert.equal(err.status, 409);
          assert.equal(err.code, "REFRESH_CONCURRENCY_COLLISION");
          assert.ok(err.message.includes("grace window"));
          return true;
        }
      );
    });

    it("B. does NOT classify generic HTTP 409 without machine code as a benign collision", async () => {
      const session = {
        accessToken: "jwt-expired-token",
        accessTokenExpiresAt: Date.now() - 1000,
        refreshToken: "generic-409-token",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };

      await assert.rejects(
        async () => {
          await refreshBackend(session);
        },
        (err) => {
          assert.equal(isRefreshCollision(err), false);
          assert.ok(err instanceof BackendAuthError);
          assert.equal(err.status, 409);
          assert.notEqual(err.code, "REFRESH_CONCURRENCY_COLLISION");
          return true;
        }
      );
    });

    it("C. explicit /api/auth/refresh returns 409 and does NOT clear session cookie on benign collision", async () => {
      const session = {
        accessToken: "jwt-expired-token",
        accessTokenExpiresAt: Date.now() - 1000,
        refreshToken: "collision-token-uuid-409",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(session);

      const req = new Request("http://localhost:3000/api/auth/refresh", {
        method: "POST",
        headers: {
          origin: "http://localhost:3000",
          cookie: `${DEV_COOKIE_NAME}=${cookieVal}`,
        },
      });

      const res = await refreshRoute(req);
      assert.equal(res.status, 409);
      const data = await res.json();
      assert.equal(data.code, "REFRESH_CONCURRENCY_COLLISION");
      assert.equal(data.collision, true);

      // CRITICAL: Must NOT contain Set-Cookie header deleting or replacing session
      const setCookie = res.headers.get("set-cookie");
      assert.equal(setCookie, null);
    });

    it("D. explicit /api/auth/refresh returns 401 and clears session cookie with Max-Age=0 on terminal 401", async () => {
      const session = {
        accessToken: "jwt-expired-token",
        accessTokenExpiresAt: Date.now() - 1000,
        refreshToken: "revoked-or-unknown-token-999",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(session);

      const req = new Request("http://localhost:3000/api/auth/refresh", {
        method: "POST",
        headers: {
          origin: "http://localhost:3000",
          cookie: `${DEV_COOKIE_NAME}=${cookieVal}`,
        },
      });

      const res = await refreshRoute(req);
      assert.equal(res.status, 401);
      const setCookie = res.headers.get("set-cookie");
      assert.ok(setCookie, "Must emit Set-Cookie header on terminal failure");
      assert.ok(setCookie.includes("Max-Age=0"), "Must clear cookie with Max-Age=0");
    });

    it("E. explicit /api/auth/refresh returns 502/503 and preserves session cookie on transient backend error", async () => {
      const session502 = {
        accessToken: "jwt-expired-token",
        accessTokenExpiresAt: Date.now() - 1000,
        refreshToken: "transient-502-token",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal502 = await encryptSession(session502);

      const req502 = new Request("http://localhost:3000/api/auth/refresh", {
        method: "POST",
        headers: {
          origin: "http://localhost:3000",
          cookie: `${DEV_COOKIE_NAME}=${cookieVal502}`,
        },
      });

      const res502 = await refreshRoute(req502);
      assert.equal(res502.status, 502);
      assert.equal(res502.headers.get("set-cookie"), null, "502 must NOT clear cookie");

      const session503 = {
        accessToken: "jwt-expired-token",
        accessTokenExpiresAt: Date.now() - 1000,
        refreshToken: "transient-503-token",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal503 = await encryptSession(session503);

      const req503 = new Request("http://localhost:3000/api/auth/refresh", {
        method: "POST",
        headers: {
          origin: "http://localhost:3000",
          cookie: `${DEV_COOKIE_NAME}=${cookieVal503}`,
        },
      });

      const res503 = await refreshRoute(req503);
      assert.equal(res503.status, 503);
      assert.equal(res503.headers.get("set-cookie"), null, "503 must NOT clear cookie");
    });

    it("F. proactive refresh collision with still-valid access token allows request to proceed without clearing cookie", async () => {
      // Access token expiring in 30 seconds (triggers proactive refresh), but still valid
      const session = {
        accessToken: "jwt-access-token-abc-123",
        accessTokenExpiresAt: Date.now() + 30_000,
        refreshToken: "collision-token-uuid-409",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(session);

      const req = new Request("http://localhost:3000/api/v1/rooms?propertyId=test-prop-uuid-999", {
        method: "GET",
        headers: {
          cookie: `${DEV_COOKIE_NAME}=${cookieVal}`,
        },
      });

      const res = await proxyToBackend(req, "/api/v1/rooms");
      assert.equal(res.status, 200, "Request should proceed because old JWT is still valid");
      // Cookie is preserved, not overwritten or deleted
      const setCookie = res.headers.get("set-cookie");
      assert.equal(setCookie, null, "Should not emit Set-Cookie when proactive refresh collided");
    });

    it("G. proactive refresh collision with expired access token halts request, returns 409, and does not delete cookie", async () => {
      // Access token already expired in the past
      const session = {
        accessToken: "jwt-expired-token",
        accessTokenExpiresAt: Date.now() - 5000,
        refreshToken: "collision-token-uuid-409",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(session);

      const req = new Request("http://localhost:3000/api/v1/rooms?propertyId=test-prop-uuid-999", {
        method: "GET",
        headers: {
          cookie: `${DEV_COOKIE_NAME}=${cookieVal}`,
        },
      });

      const res = await proxyToBackend(req, "/api/v1/rooms");
      assert.equal(res.status, 409, "Must return 409 when token expired and refresh collided");
      const data = await res.json();
      assert.equal(data.code, "REFRESH_CONCURRENCY_COLLISION");
      assert.equal(data.collision, true);
      assert.equal(res.headers.get("set-cookie"), null, "Must NOT delete cookie on benign collision");
    });

    it("H. reactive GET/HEAD refresh collision returns 409 without deleting cookie and does not retry old token", async () => {
      const session = {
        accessToken: "jwt-reactive-get-collision",
        accessTokenExpiresAt: Date.now() + 600_000, // Not proactively expiring
        refreshToken: "reactive-get-collision-token",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(session);

      const req = new Request("http://localhost:3000/api/v1/rooms?propertyId=test-prop-uuid-999", {
        method: "GET",
        headers: {
          cookie: `${DEV_COOKIE_NAME}=${cookieVal}`,
        },
      });

      const res = await proxyToBackend(req, "/api/v1/rooms");
      assert.equal(res.status, 409);
      const data = await res.json();
      assert.equal(data.code, "REFRESH_CONCURRENCY_COLLISION");
      assert.equal(data.collision, true);
      assert.equal(res.headers.get("set-cookie"), null, "Must NOT delete cookie on reactive GET collision");
    });

    it("I. non-idempotent POST mutation on 401 with refresh collision is NEVER silently replayed and preserves cookie", async () => {
      const initialMutationCount = backendMutationCallCount;

      const session = {
        accessToken: "jwt-reactive-post-collision",
        accessTokenExpiresAt: Date.now() + 600_000,
        refreshToken: "reactive-post-collision-token",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(session);

      const req = new Request("http://localhost:3000/api/v1/reservations", {
        method: "POST",
        headers: {
          origin: "http://localhost:3000",
          "Content-Type": "application/json",
          cookie: `${DEV_COOKIE_NAME}=${cookieVal}`,
        },
        body: JSON.stringify({ guestName: "Bob", propertyId: "test-prop-uuid-999" }),
      });

      const res = await proxyToBackend(req, "/api/v1/reservations");
      assert.equal(res.status, 409);
      const data = await res.json();
      assert.equal(data.code, "REFRESH_CONCURRENCY_COLLISION");
      assert.equal(data.collision, true);

      // Verify mutation was called exactly ONCE (the initial 401 call), NEVER replayed
      assert.equal(backendMutationCallCount, initialMutationCount + 1);

      // Verify cookie was NOT cleared
      assert.equal(res.headers.get("set-cookie"), null);
    });

    it("J. winner (200) and loser (409) response delivery order: both interleavings preserve valid session without stale overwrite", async () => {
      // Interleaving J1: Winner arrives before loser
      const sessionWinner = {
        accessToken: "jwt-expired-token",
        accessTokenExpiresAt: Date.now() - 1000,
        refreshToken: "session-1-token", // Returns 200 with new token
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieValWinner = await encryptSession(sessionWinner);
      const reqWinner = new Request("http://localhost:3000/api/auth/refresh", {
        method: "POST",
        headers: { origin: "http://localhost:3000", cookie: `${DEV_COOKIE_NAME}=${cookieValWinner}` },
      });
      const resWinner = await refreshRoute(reqWinner);
      assert.equal(resWinner.status, 200);
      const winnerSetCookie = resWinner.headers.get("set-cookie");
      assert.ok(winnerSetCookie);
      assert.ok(!winnerSetCookie.includes("Max-Age=0"));

      // Loser arrives after winner:
      const sessionLoser = {
        accessToken: "jwt-expired-token",
        accessTokenExpiresAt: Date.now() - 1000,
        refreshToken: "collision-token-uuid-409",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieValLoser = await encryptSession(sessionLoser);
      const reqLoser = new Request("http://localhost:3000/api/auth/refresh", {
        method: "POST",
        headers: { origin: "http://localhost:3000", cookie: `${DEV_COOKIE_NAME}=${cookieValLoser}` },
      });
      const resLoser = await refreshRoute(reqLoser);
      assert.equal(resLoser.status, 409);
      assert.equal(resLoser.headers.get("set-cookie"), null, "Loser must NOT delete or overwrite session");

      // Interleaving J2: Loser arrives before winner
      const resLoserFirst = await refreshRoute(reqLoser);
      assert.equal(resLoserFirst.status, 409);
      assert.equal(resLoserFirst.headers.get("set-cookie"), null);

      const resWinnerSecond = await refreshRoute(reqWinner);
      assert.equal(resWinnerSecond.status, 200);
      assert.ok(resWinnerSecond.headers.get("set-cookie"));
    });

    it("K. two independent Next.js process instances: winner sets session, loser preserves cookie without deletion", async () => {
      // Simulating instance A (winner) and instance B (loser)
      const sessionA = {
        accessToken: "jwt-expired-token",
        accessTokenExpiresAt: Date.now() - 1000,
        refreshToken: "session-2-token",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const sessionB = {
        accessToken: "jwt-expired-token",
        accessTokenExpiresAt: Date.now() - 1000,
        refreshToken: "collision-token-uuid-409",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };

      const resA = await refreshRoute(
        new Request("http://localhost:3000/api/auth/refresh", {
          method: "POST",
          headers: { origin: "http://localhost:3000", cookie: `${DEV_COOKIE_NAME}=${await encryptSession(sessionA)}` },
        })
      );
      assert.equal(resA.status, 200);
      assert.ok(resA.headers.get("set-cookie"));

      const resB = await refreshRoute(
        new Request("http://localhost:3000/api/auth/refresh", {
          method: "POST",
          headers: { origin: "http://localhost:3000", cookie: `${DEV_COOKIE_NAME}=${await encryptSession(sessionB)}` },
        })
      );
      assert.equal(resB.status, 409);
      assert.equal(resB.headers.get("set-cookie"), null, "Instance B must NOT delete valid cookie");
    });

    it("L. logout versus refresh: logout revokes session family, clears cookie, and prevents resurrection", async () => {
      const session = {
        accessToken: "jwt-access-token-abc-123",
        accessTokenExpiresAt: Date.now() + 900000,
        refreshToken: "logout-race-token-999",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(session);

      // Logout request
      const logoutReq = new Request("http://localhost:3000/api/auth/logout", {
        method: "POST",
        headers: { origin: "http://localhost:3000", cookie: `${DEV_COOKIE_NAME}=${cookieVal}` },
      });
      const logoutRes = await logoutRoute(logoutReq);
      assert.equal(logoutRes.status, 200);
      assert.ok(logoutRes.headers.get("set-cookie").includes("Max-Age=0"));

      // Refresh request after logout is rejected and cannot resurrect session
      const refreshReq = new Request("http://localhost:3000/api/auth/refresh", {
        method: "POST",
        headers: { origin: "http://localhost:3000", cookie: `${DEV_COOKIE_NAME}=${cookieVal}` },
      });
      const refreshRes = await refreshRoute(refreshReq);
      assert.equal(refreshRes.status, 401);
      assert.ok(refreshRes.headers.get("set-cookie").includes("Max-Age=0"));
    });

    it("M. no automatic retry loops for rotated refresh tokens on collision", async () => {
      const initialRefreshCalls = backendRefreshCallCount;
      const session = {
        accessToken: "jwt-expired-token",
        accessTokenExpiresAt: Date.now() - 1000,
        refreshToken: "collision-token-uuid-409",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };

      try {
        await refreshBackend(session);
      } catch (err) {
        assert.ok(isRefreshCollision(err));
      }

      // Must call backend EXACTLY once, never retry or loop
      assert.equal(backendRefreshCallCount, initialRefreshCalls + 1);
    });

    it("N. no automatic replay of business mutations on 401 refresh collision", async () => {
      const initialMutationCalls = backendMutationCallCount;
      const session = {
        accessToken: "jwt-reactive-post-collision",
        accessTokenExpiresAt: Date.now() + 600_000,
        refreshToken: "reactive-post-collision-token",
        refreshTokenCookieName: "pms-refresh-dev",
        staff: validStaff,
        sessionVersion: 1,
      };
      const cookieVal = await encryptSession(session);

      const req = new Request("http://localhost:3000/api/v1/reservations", {
        method: "POST",
        headers: {
          origin: "http://localhost:3000",
          "Content-Type": "application/json",
          cookie: `${DEV_COOKIE_NAME}=${cookieVal}`,
        },
        body: JSON.stringify({ guestName: "Charlie", propertyId: "test-prop-uuid-999" }),
      });

      const res = await proxyToBackend(req, "/api/v1/reservations");
      assert.equal(res.status, 409);
      // Backend mutation call count increased by exactly 1 (the initial failing attempt)
      assert.equal(backendMutationCallCount, initialMutationCalls + 1);
    });
  });
});
