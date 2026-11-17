import type {
  BackendLoginResponse,
  BackendRefreshResponse,
  StaffSessionData,
} from "./types";

export class BackendAuthError extends Error {
  status: number;
  data: unknown;

  constructor(status: number, message: string, data?: unknown) {
    super(message);
    this.name = "BackendAuthError";
    this.status = status;
    this.data = data;
  }
}

export function getBackendBaseUrl(): string {
  return process.env.PMS_API_BASE_URL || "http://localhost:8080";
}

export function getFrontendOrigin(): string {
  return process.env.PMS_FRONTEND_ORIGIN || "http://localhost:3000";
}

export function extractCookieFromHeaders(
  headers: Headers,
  preferredNames: string[] = ["pms-refresh-dev", "__Host-pms-refresh"]
): { name: string; value: string } | null {
  const setCookieLines: string[] = [];
  if (typeof headers.getSetCookie === "function") {
    setCookieLines.push(...headers.getSetCookie());
  } else {
    const raw = headers.get("set-cookie");
    if (raw) setCookieLines.push(raw);
  }

  for (const line of setCookieLines) {
    const [cookiePart] = line.split(";");
    const idx = cookiePart.indexOf("=");
    if (idx > 0) {
      const name = cookiePart.substring(0, idx).trim();
      const value = cookiePart.substring(idx + 1).trim();
      if (preferredNames.includes(name) || name.toLowerCase().includes("refresh")) {
        return { name, value };
      }
    }
  }

  // Fallback to first cookie if preferred name not explicitly matched
  for (const line of setCookieLines) {
    const [cookiePart] = line.split(";");
    const idx = cookiePart.indexOf("=");
    if (idx > 0) {
      const name = cookiePart.substring(0, idx).trim();
      const value = cookiePart.substring(idx + 1).trim();
      return { name, value };
    }
  }

  return null;
}

/**
 * Authenticates staff with the backend.
 * Returns a new StaffSessionData object with initial sessionVersion = 1.
 */
export async function loginBackend(credentials: {
  email: string;
  password: string;
}): Promise<StaffSessionData> {
  const baseUrl = getBackendBaseUrl();
  const origin = getFrontendOrigin();

  let response: Response;
  try {
    response = await fetch(`${baseUrl}/api/v1/auth/login`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Origin: origin,
      },
      body: JSON.stringify(credentials),
      cache: "no-store",
    });
  } catch {
    throw new BackendAuthError(
      502,
      "Cannot connect to the PMS authentication service."
    );
  }

  if (!response.ok) {
    let errorData: unknown = null;
    let message = "Authentication failed.";
    try {
      errorData = await response.json();
      if (
        errorData &&
        typeof errorData === "object" &&
        "message" in errorData &&
        typeof (errorData as { message: unknown }).message === "string"
      ) {
        message = (errorData as { message: string }).message;
      }
    } catch {
      // Non-json response
    }

    if (response.status === 401) {
      message = "Invalid email or password.";
    } else if (response.status === 429) {
      message = "Too many login attempts. Please try again later.";
    }

    throw new BackendAuthError(response.status, message, errorData);
  }

  const data = (await response.json()) as BackendLoginResponse;
  const cookieData = extractCookieFromHeaders(response.headers);

  const refreshToken = cookieData?.value || "";
  const refreshTokenCookieName =
    cookieData?.name ||
    (process.env.NODE_ENV === "production" ? "__Host-pms-refresh" : "pms-refresh-dev");

  if (!refreshToken) {
    throw new BackendAuthError(
      500,
      "Backend did not return a refresh token cookie."
    );
  }

  const expiresInMs = (data.expiresInSeconds || 900) * 1000;
  const accessTokenExpiresAt = Date.now() + expiresInMs;

  loggedOutTokens.delete(refreshToken);

  return {
    accessToken: data.accessToken,
    accessTokenExpiresAt,
    refreshToken,
    refreshTokenCookieName,
    staff: data.staff,
    sessionVersion: 1,
  };
}

// ==============================================================================
// Refresh Token Rotation Concurrency Engine
//
// NOTE ON MULTI-INSTANCE LIMITATION:
// The in-memory deduplication and recent rotation cache provide complete safety
// within a single Node process. In multi-instance deployments (multi-pod k8s,
// load-balanced clusters, or serverless functions), competing refresh requests
// hitting distinct instances require shared coordination (e.g. Redis distributed lock)
// or a backend leeway grace period in fn_rotate_refresh_token to avoid accidental
// token family revocation. This is documented as a production deployment requirement.
// ==============================================================================

interface RecentRotationRecord {
  updatedSession: StaffSessionData;
  rotatedAt: number; // Unix epoch ms
}

// In-memory Promise deduplication map keyed by the SPECIFIC refresh token (not staff ID!)
const inFlightRefreshes = new Map<string, Promise<StaffSessionData>>();

// Short-lived cache of recent rotations (10s TTL) to absorb delayed/stale requests from other tabs
const recentRotations = new Map<string, RecentRotationRecord>();

// Set of refresh tokens that were explicitly logged out to prevent logout-refresh race conditions
const loggedOutTokens = new Set<string>();

export function isTokenLoggedOut(token: string): boolean {
  return loggedOutTokens.has(token);
}

// Clean up stale rotation records older than 30 seconds
function pruneStaleRotations() {
  const cutoff = Date.now() - 30_000;
  for (const [key, record] of recentRotations.entries()) {
    if (record.rotatedAt < cutoff) {
      recentRotations.delete(key);
    }
  }
}

async function executeBackendRefresh(
  currentSession: StaffSessionData
): Promise<StaffSessionData> {
  const baseUrl = getBackendBaseUrl();
  const origin = getFrontendOrigin();

  let response: Response;
  try {
    response = await fetch(`${baseUrl}/api/v1/auth/refresh`, {
      method: "POST",
      headers: {
        Origin: origin,
        Cookie: `${currentSession.refreshTokenCookieName}=${currentSession.refreshToken}`,
      },
      cache: "no-store",
    });
  } catch {
    throw new BackendAuthError(
      502,
      "Cannot connect to the PMS authentication service for refresh."
    );
  }

  if (!response.ok) {
    let message = "Failed to refresh session.";
    if (response.status === 401 || response.status === 403) {
      message = "Session expired or revoked. Please log in again.";
    }
    throw new BackendAuthError(response.status, message);
  }

  const data = (await response.json()) as BackendRefreshResponse;
  const cookieData = extractCookieFromHeaders(response.headers);

  const refreshToken = cookieData?.value || currentSession.refreshToken;
  const refreshTokenCookieName =
    cookieData?.name || currentSession.refreshTokenCookieName;

  const expiresInMs = (data.expiresInSeconds || 900) * 1000;
  const accessTokenExpiresAt = Date.now() + expiresInMs;

  const newSession: StaffSessionData = {
    accessToken: data.accessToken,
    accessTokenExpiresAt,
    refreshToken,
    refreshTokenCookieName,
    staff: data.staff || currentSession.staff,
    sessionVersion: (currentSession.sessionVersion || 1) + 1,
  };

  // If logout occurred while this refresh was awaiting the backend, fail closed to prevent session resurrection
  if (loggedOutTokens.has(currentSession.refreshToken)) {
    throw new BackendAuthError(401, "Session was logged out during token refresh.");
  }

  // Cache this rotation so any delayed concurrent requests bearing the old token within 10s receive this new session
  pruneStaleRotations();
  recentRotations.set(currentSession.refreshToken, {
    updatedSession: newSession,
    rotatedAt: Date.now(),
  });

  return newSession;
}

/**
 * Refreshes an expired or expiring access token with backend refresh token rotation.
 *
 * Concurrency Safety:
 * 1. Keyed strictly by the specific refresh token string (NOT staff ID), preventing conflation
 *    between independent login sessions belonging to the same staff member.
 * 2. Deduplicates simultaneous in-flight requests bearing the same refresh token.
 * 3. Grace period cache (10s): If a request arrives with a refresh token that was just rotated
 *    moments earlier by another tab, returns the rotated session rather than causing token family revocation.
 * 4. Anti-Resurrection Guard: If logout was requested, blocks refresh immediately.
 */
export async function refreshBackend(
  currentSession: StaffSessionData
): Promise<StaffSessionData> {
  const tokenKey = currentSession.refreshToken;

  if (loggedOutTokens.has(tokenKey)) {
    throw new BackendAuthError(401, "Session has been logged out.");
  }

  // 1. Check recent rotation cache first (leeway for delayed requests from other tabs)
  const recent = recentRotations.get(tokenKey);
  if (recent && Date.now() - recent.rotatedAt < 10_000) {
    return recent.updatedSession;
  }

  // 2. Check in-flight deduplication map
  const existing = inFlightRefreshes.get(tokenKey);
  if (existing) {
    return existing;
  }

  // 3. Initiate single refresh call
  const promise = executeBackendRefresh(currentSession).finally(() => {
    inFlightRefreshes.delete(tokenKey);
  });

  inFlightRefreshes.set(tokenKey, promise);
  return promise;
}

/**
 * Revokes the current session family at the backend.
 * Cleans in-memory rotation records and resolves idempotently.
 */
export async function logoutBackend(
  currentSession: StaffSessionData
): Promise<void> {
  const baseUrl = getBackendBaseUrl();
  const origin = getFrontendOrigin();

  loggedOutTokens.add(currentSession.refreshToken);
  recentRotations.delete(currentSession.refreshToken);
  inFlightRefreshes.delete(currentSession.refreshToken);

  try {
    await fetch(`${baseUrl}/api/v1/auth/logout`, {
      method: "POST",
      headers: {
        Origin: origin,
        Cookie: `${currentSession.refreshTokenCookieName}=${currentSession.refreshToken}`,
      },
      cache: "no-store",
    });
  } catch {
    // Best-effort logout: ignore network failures
  }
}
