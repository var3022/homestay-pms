import { NextResponse } from "next/server";
import { BackendAuthError, refreshBackend } from "@/lib/auth/backend";
import { getSessionCookieName, getSessionCookieOptions, parseCookies } from "@/lib/auth/cookies";
import { decryptSession, encryptSession } from "@/lib/auth/session";
import { verifyCsrf } from "@/lib/auth/csrf";

export async function POST(request: Request) {
  // Enforce CSRF check: prevent cross-site session manipulation
  const csrf = verifyCsrf(request);
  if (!csrf.valid) {
    return NextResponse.json(
      { error: `CSRF check failed: ${csrf.reason}` },
      { status: 403, headers: { "Cache-Control": "no-store, private" } }
    );
  }

  try {
    const cookieHeader = request.headers.get("cookie");
    const cookies = parseCookies(cookieHeader);
    const cookieName = getSessionCookieName();
    const sessionCiphertext = cookies[cookieName];

    const currentSession = await decryptSession(sessionCiphertext);
    if (!currentSession) {
      return NextResponse.json(
        { error: "Unauthorized: No valid session cookie found." },
        { status: 401, headers: { "Cache-Control": "no-store, private" } }
      );
    }

    const initialVersion = currentSession.sessionVersion || 1;
    const updatedSession = await refreshBackend(currentSession);
    const cookieOptions = getSessionCookieOptions();

    const response = NextResponse.json(
      {
        success: true,
        staff: updatedSession.staff,
      },
      {
        status: 200,
        headers: {
          "Cache-Control": "no-store, no-cache, must-revalidate, private",
          Pragma: "no-cache",
        },
      }
    );

    // Only set cookie if version advanced
    if (updatedSession.sessionVersion >= initialVersion) {
      const encryptedSession = await encryptSession(updatedSession);
      response.cookies.set(cookieName, encryptedSession, cookieOptions);
    }

    return response;
  } catch (err) {
    const errorResponse = NextResponse.json(
      {
        error:
          err instanceof BackendAuthError
            ? err.message
            : "Session refresh failed.",
      },
      {
        status:
          err instanceof BackendAuthError && err.status === 429
            ? 429
            : 401,
        headers: { "Cache-Control": "no-store, private" },
      }
    );

    // Clear stale cookie on fatal failure
    errorResponse.cookies.set(
      getSessionCookieName(),
      "",
      getSessionCookieOptions(0)
    );
    return errorResponse;
  }
}
