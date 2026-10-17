import { NextRequest, NextResponse } from "next/server";

export async function GET(request: NextRequest) {
  const apiBaseUrl =
    process.env.PMS_API_BASE_URL ?? "http://localhost:8080";

  const reservationRoomId =
    request.nextUrl.searchParams.get("reservationRoomId");

  if (!reservationRoomId) {
    return NextResponse.json(
      { error: "reservationRoomId is required." },
      { status: 400 },
    );
  }

  try {
    const response = await fetch(
      `${apiBaseUrl}/api/v1/room-assignments?reservationRoomId=${encodeURIComponent(reservationRoomId)}`,
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
