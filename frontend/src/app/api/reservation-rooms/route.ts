import { NextRequest, NextResponse } from "next/server";

export async function GET(request: NextRequest) {
  const apiBaseUrl =
    process.env.PMS_API_BASE_URL ?? "http://localhost:8080";

  const reservationId = request.nextUrl.searchParams.get("reservationId");

  if (!reservationId) {
    return NextResponse.json(
      { error: "reservationId is required." },
      { status: 400 },
    );
  }

  try {
    const response = await fetch(
      `${apiBaseUrl}/api/v1/reservation-rooms?reservationId=${encodeURIComponent(reservationId)}`,
      { cache: "no-store" },
    );

    const body = await response.text();

    return new NextResponse(body, {
      status: response.status,
      headers: {
        "Content-Type":
          response.headers.get("Content-Type") ?? "application/json",
      },
    });
  } catch {
    return NextResponse.json(
      { error: "Cannot connect to the PMS backend." },
      { status: 502 },
    );
  }
}
