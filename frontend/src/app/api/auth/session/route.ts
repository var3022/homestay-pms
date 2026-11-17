import { NextResponse } from "next/server";
import { getSessionCookieName, parseCookies } from "@/lib/auth/cookies";
import { decryptSession } from "@/lib/auth/session";

export async function GET(request: Request) {
  const cookieHeader = request.headers.get("cookie");
  const cookies = parseCookies(cookieHeader);
  const cookieName = getSessionCookieName();
  const sessionCiphertext = cookies[cookieName];

  const currentSession = await decryptSession(sessionCiphertext);
  if (!currentSession) {
    return NextResponse.json(
      { authenticated: false, user: null },
      {
        status: 200,
        headers: {
          "Cache-Control": "no-store, no-cache, must-revalidate, private",
          Pragma: "no-cache",
        },
      }
    );
  }

  return NextResponse.json(
    {
      authenticated: true,
      user: currentSession.staff,
    },
    {
      status: 200,
      headers: {
        "Cache-Control": "no-store, no-cache, must-revalidate, private",
        Pragma: "no-cache",
      },
    }
  );
}
