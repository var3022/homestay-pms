import { NextRequest, NextResponse } from "next/server";
import { getSessionCookieName } from "./lib/auth/cookies";
import { decryptSession } from "./lib/auth/session";
import { sanitizeDashboardRedirect } from "./lib/auth/csrf";

export async function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const sessionCookieName = getSessionCookieName();
  const sessionCookie = request.cookies.get(sessionCookieName);

  const isDashboardRoute = pathname.startsWith("/dashboard");
  const isLoginRoute = pathname === "/login";

  let isAuthenticated = false;
  if (sessionCookie?.value) {
    const session = await decryptSession(sessionCookie.value);
    if (session && session.staff && session.staff.id) {
      isAuthenticated = true;
    }
  }

  // If user is accessing protected dashboard without valid session -> redirect to login
  if (isDashboardRoute && !isAuthenticated) {
    const redirectTarget = pathname + search;
    const loginUrl = new URL("/login", request.url);
    loginUrl.searchParams.set("redirect", redirectTarget);

    const response = NextResponse.redirect(loginUrl);
    // If a corrupted or invalid cookie was present, clear it
    if (sessionCookie) {
      response.cookies.delete(sessionCookieName);
    }
    return response;
  }

  // If already authenticated user visits /login -> redirect to dashboard
  if (isLoginRoute && isAuthenticated) {
    const redirectParam = request.nextUrl.searchParams.get("redirect");
    const target = sanitizeDashboardRedirect(redirectParam);
    return NextResponse.redirect(new URL(target, request.url));
  }

  return NextResponse.next();
}

// Support both naming conventions seamlessly
export const middleware = proxy;

export const config = {
  matcher: ["/dashboard/:path*", "/login"],
};
