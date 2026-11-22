import { NextResponse } from "next/server";
import { getSessionCookieName, getSessionCookieOptions, parseCookies } from "./cookies";
import { decryptSession, encryptSession, isSessionExpiringSoon } from "./session";
import { getBackendBaseUrl, refreshBackend } from "./backend";
import { verifyCsrf } from "./csrf";
import type { StaffSessionData } from "./types";

export interface ProxyOptions {
  requirePropertyId?: boolean;
  injectPropertyId?: boolean;
}

export { verifyCsrf } from "./csrf";

/**
 * Reusable CSRF helper retained for backwards-compatibility.
 */
export function isCsrfValid(request: Request): boolean {
  return verifyCsrf(request).valid;
}

/**
 * Proxies an authenticated client request to the Spring Boot PMS backend.
 *
 * Security & Reliability Guarantees:
 * - Strict CSRF verification on mutating requests (foreign origins rejected unconditionally).
 * - Target path sanitization (must begin with /api/v1/ with no directory traversal).
 * - Independent session verification via AES-256-GCM cookie decryption.
 * - Proactive access token refresh before request dispatch if < 60s remaining.
 * - Non-idempotent mutation safety: Never silently replay POST, PUT, PATCH, DELETE requests.
 *   Only idempotent GET/HEAD requests are retried reactively on 401.
 * - For mutating requests on 401: refreshes session cookie and returns a structured 401
 *   prompting client-visible re-submission.
 * - Cache-Control: no-store applied to all responses to protect staff/guest data.
 * - Cookie update gating: Only sets rotated cookie if session generation advanced.
 */
export async function proxyToBackend(
  request: Request,
  backendPath: string,
  options?: ProxyOptions
): Promise<Response> {
  const method = request.method.toUpperCase();

  // 1. Verify CSRF for mutating requests
  const csrfResult = verifyCsrf(request);
  if (!csrfResult.valid) {
    return NextResponse.json(
      { error: `CSRF check failed: ${csrfResult.reason}` },
      {
        status: 403,
        headers: { "Cache-Control": "no-store, private" },
      }
    );
  }

  // 2. Target path validation & anti-traversal
  if (!backendPath.startsWith("/api/v1/") || backendPath.includes("..")) {
    return NextResponse.json(
      { error: "Invalid backend path specified." },
      { status: 400, headers: { "Cache-Control": "no-store, private" } }
    );
  }

  // 3. Extract and decrypt session
  const cookieHeader = request.headers.get("cookie");
  const cookies = parseCookies(cookieHeader);
  const sessionCookieName = getSessionCookieName();
  const sessionCiphertext = cookies[sessionCookieName];

  let session: StaffSessionData | null = await decryptSession(sessionCiphertext);
  if (!session) {
    return NextResponse.json(
      { error: "Unauthorized: No active staff session." },
      { status: 401, headers: { "Cache-Control": "no-store, private" } }
    );
  }

  const initialSessionVersion = session.sessionVersion || 1;
  let updatedSession: StaffSessionData | null = null;

  // 4. Proactive token refresh if expiring soon (< 60s)
  // This occurs BEFORE any request is sent to the backend, making it safe for all methods
  if (isSessionExpiringSoon(session, 60_000)) {
    try {
      session = await refreshBackend(session);
      updatedSession = session;
    } catch {
      // Proactive refresh failed; proceed with existing token if still valid
    }
  }

  // 5. Construct backend target URL
  const incomingUrl = new URL(request.url);
  const baseUrl = getBackendBaseUrl();
  const targetUrl = new URL(`${baseUrl}${backendPath}`);

  // Forward incoming query parameters
  incomingUrl.searchParams.forEach((val, key) => {
    targetUrl.searchParams.set(key, val);
  });

  // Property ID handling: NEVER silently overwrite an explicit client selection
  if (options?.requirePropertyId) {
    if (!targetUrl.searchParams.has("propertyId")) {
      const propId = process.env.PMS_PROPERTY_ID;
      if (!propId) {
        return NextResponse.json(
          { error: "PMS_PROPERTY_ID is not configured." },
          { status: 500, headers: { "Cache-Control": "no-store, private" } }
        );
      }
      targetUrl.searchParams.set("propertyId", propId);
    }
  }

  // 6. Construct backend headers
  const forwardHeaders = new Headers();
  forwardHeaders.set("Authorization", `Bearer ${session.accessToken}`);
  forwardHeaders.set("Accept", "application/json");

  let requestBody: string | undefined = undefined;
  if (["POST", "PUT", "PATCH"].includes(method)) {
    const contentType = request.headers.get("content-type");
    if (contentType?.includes("application/json")) {
      forwardHeaders.set("Content-Type", "application/json");
      try {
        const rawText = await request.text();
        if (rawText && rawText.trim().length > 0) {
          const json = JSON.parse(rawText);
          // Never override existing propertyId
          if (options?.injectPropertyId && process.env.PMS_PROPERTY_ID) {
            if (!json.propertyId) {
              json.propertyId = process.env.PMS_PROPERTY_ID;
            }
          }
          requestBody = JSON.stringify(json);
        } else {
          requestBody = undefined;
        }
      } catch {
        return NextResponse.json(
          { error: "Invalid JSON in request body." },
          { status: 400, headers: { "Cache-Control": "no-store, private" } }
        );
      }
    } else {
      requestBody = await request.text();
      if (contentType) {
        forwardHeaders.set("Content-Type", contentType);
      }
    }
  }

  // 7. Execute backend request
  let backendResponse: Response;
  try {
    backendResponse = await fetch(targetUrl.toString(), {
      method,
      headers: forwardHeaders,
      body: requestBody,
      cache: "no-store",
    });
  } catch {
    return NextResponse.json(
      { error: "Cannot connect to the PMS backend." },
      { status: 502, headers: { "Cache-Control": "no-store, private" } }
    );
  }

  // 8. Reactive refresh policy
  if (backendResponse.status === 401) {
    // POLICY: Safe automatic retry ONLY for idempotent GET / HEAD
    const isIdempotent = ["GET", "HEAD"].includes(method);

    if (isIdempotent) {
      try {
        session = await refreshBackend(session);
        updatedSession = session;
        forwardHeaders.set("Authorization", `Bearer ${session.accessToken}`);

        backendResponse = await fetch(targetUrl.toString(), {
          method,
          headers: forwardHeaders,
          body: requestBody,
          cache: "no-store",
        });
      } catch {
        const expiredResponse = NextResponse.json(
          { error: "Session expired. Please log in again." },
          { status: 401, headers: { "Cache-Control": "no-store, private" } }
        );
        expiredResponse.cookies.set(
          sessionCookieName,
          "",
          getSessionCookieOptions(0)
        );
        return expiredResponse;
      }
    } else {
      // NON-IDEMPOTENT REQUEST: DO NOT SILENTLY REPLAY!
      // Attempt refresh so session cookie is refreshed for the user's next action
      try {
        session = await refreshBackend(session);
        updatedSession = session;

        const reauthResponse = NextResponse.json(
          {
            error:
              "Session was reauthenticated. Please confirm and resubmit your request.",
            reauthenticated: true,
          },
          { status: 401, headers: { "Cache-Control": "no-store, private" } }
        );

        if (updatedSession.sessionVersion > initialSessionVersion) {
          const encrypted = await encryptSession(updatedSession);
          reauthResponse.cookies.set(
            sessionCookieName,
            encrypted,
            getSessionCookieOptions()
          );
        }
        return reauthResponse;
      } catch {
        const expiredResponse = NextResponse.json(
          { error: "Session expired. Please log in again." },
          { status: 401, headers: { "Cache-Control": "no-store, private" } }
        );
        expiredResponse.cookies.set(
          sessionCookieName,
          "",
          getSessionCookieOptions(0)
        );
        return expiredResponse;
      }
    }
  }

  // 9. Construct outgoing response
  const responseBody = await backendResponse.text();
  const outgoingHeaders = new Headers();
  const backendContentType = backendResponse.headers.get("content-type");
  if (backendContentType) {
    outgoingHeaders.set("Content-Type", backendContentType);
  }

  // Enforce Cache-Control: no-store on all authenticated responses
  outgoingHeaders.set("Cache-Control", "no-store, no-cache, must-revalidate, private");
  outgoingHeaders.set("Pragma", "no-cache");

  const response = new NextResponse(responseBody, {
    status: backendResponse.status,
    headers: outgoingHeaders,
  });

  // Only update session cookie if sessionVersion advanced, preventing stale overwrites
  if (updatedSession && updatedSession.sessionVersion > initialSessionVersion) {
    const encrypted = await encryptSession(updatedSession);
    response.cookies.set(
      sessionCookieName,
      encrypted,
      getSessionCookieOptions()
    );
  }

  return response;
}
