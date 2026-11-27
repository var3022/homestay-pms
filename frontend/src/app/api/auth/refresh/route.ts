import { NextResponse } from "next/server";
import { BackendAuthError, RefreshCollisionError, refreshBackend } from "@/lib/auth/backend";
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
    if (err instanceof RefreshCollisionError) {
      // Confirmed V14 concurrency collision:
      // Return 409 Conflict with machine-readable code.
      // CRITICAL: Do NOT emit Set-Cookie deletion! Do NOT emit replacement cookie!
      return NextResponse.json(
        {
          error: err.message,
          code: "REFRESH_CONCURRENCY_COLLISION",
          collision: true,
        },
        {
          status: 409,
          headers: {
            "Cache-Control": "no-store, no-cache, must-revalidate, private",
            Pragma: "no-cache",
          },
        }
      );
    }

    if (err instanceof BackendAuthError) {
      // Transient server/transport errors (500, 502, 503, 504): preserve session cookie
      if (err.status >= 500 && err.status <= 504) {
        return NextResponse.json(
          { error: err.message },
          {
            status: err.status,
            headers: { "Cache-Control": "no-store, private" },
          }
        );
      }

      if (err.status === 429) {
        return NextResponse.json(
          { error: err.message },
          {
            status: 429,
            headers: { "Cache-Control": "no-store, private" },
          }
        );
      }

      // Terminal authentication failure (401, 403, or other non-transient rejection):
      const errorResponse = NextResponse.json(
        { error: err.message },
        {
          status: 401,
          headers: { "Cache-Control": "no-store, private" },
        }
      );
      errorResponse.cookies.set(
        getSessionCookieName(),
        "",
        getSessionCookieOptions(0)
      );
      return errorResponse;
    }

    // Unexpected internal programming error: do not clear session cookie
    return NextResponse.json(
      { error: "Internal server error during session refresh." },
      {
        status: 500,
        headers: { "Cache-Control": "no-store, private" },
      }
    );
  }
}
