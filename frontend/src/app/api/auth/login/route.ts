import { NextResponse } from "next/server";
import { BackendAuthError, loginBackend } from "@/lib/auth/backend";
import { getSessionCookieName, getSessionCookieOptions } from "@/lib/auth/cookies";
import { encryptSession } from "@/lib/auth/session";
import { verifyCsrf } from "@/lib/auth/csrf";

export async function POST(request: Request) {
  // Enforce CSRF check: prevent cross-site login CSRF
  const csrf = verifyCsrf(request);
  if (!csrf.valid) {
    return NextResponse.json(
      { error: `CSRF check failed: ${csrf.reason}` },
      { status: 403, headers: { "Cache-Control": "no-store, private" } }
    );
  }

  try {
    let body: unknown;
    try {
      body = await request.json();
    } catch {
      return NextResponse.json(
        { error: "Invalid JSON request body." },
        { status: 400, headers: { "Cache-Control": "no-store, private" } }
      );
    }

    if (
      !body ||
      typeof body !== "object" ||
      !("email" in body) ||
      !("password" in body)
    ) {
      return NextResponse.json(
        { error: "Email and password are required." },
        { status: 400, headers: { "Cache-Control": "no-store, private" } }
      );
    }

    const { email, password } = body as { email: unknown; password: unknown };
    if (
      typeof email !== "string" ||
      !email.trim() ||
      typeof password !== "string" ||
      !password
    ) {
      return NextResponse.json(
        { error: "Email and password must be non-empty strings." },
        { status: 400, headers: { "Cache-Control": "no-store, private" } }
      );
    }

    const session = await loginBackend({
      email: email.trim(),
      password,
    });

    const encryptedSession = await encryptSession(session);
    const cookieName = getSessionCookieName();
    const cookieOptions = getSessionCookieOptions();

    const response = NextResponse.json(
      {
        success: true,
        staff: session.staff,
      },
      {
        status: 200,
        headers: {
          "Cache-Control": "no-store, no-cache, must-revalidate, private",
          Pragma: "no-cache",
        },
      }
    );

    response.cookies.set(cookieName, encryptedSession, cookieOptions);
    return response;
  } catch (err) {
    if (err instanceof BackendAuthError) {
      return NextResponse.json(
        { error: err.message },
        { status: err.status, headers: { "Cache-Control": "no-store, private" } }
      );
    }
    return NextResponse.json(
      { error: "An unexpected error occurred during login." },
      { status: 500, headers: { "Cache-Control": "no-store, private" } }
    );
  }
}
