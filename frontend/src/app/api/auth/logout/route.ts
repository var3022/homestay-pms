import { NextResponse } from "next/server";
import { logoutBackend } from "@/lib/auth/backend";
import { getSessionCookieName, getSessionCookieOptions, parseCookies } from "@/lib/auth/cookies";
import { decryptSession } from "@/lib/auth/session";
import { verifyCsrf } from "@/lib/auth/csrf";

export async function POST(request: Request) {
  // Enforce CSRF check: prevent cross-site forced logout attacks
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
    if (currentSession) {
      await logoutBackend(currentSession);
    }
  } catch {
    // Logout is best-effort and idempotent
  }

  const response = NextResponse.json(
    { success: true, message: "Logged out successfully." },
    {
      status: 200,
      headers: {
        "Cache-Control": "no-store, no-cache, must-revalidate, private",
        Pragma: "no-cache",
      },
    }
  );

  response.cookies.set(
    getSessionCookieName(),
    "",
    getSessionCookieOptions(0)
  );

  return response;
}
