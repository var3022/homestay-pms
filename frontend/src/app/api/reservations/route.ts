
import { NextResponse } from "next/server";

export async function GET() {
  const apiBaseUrl =
    process.env.PMS_API_BASE_URL ?? "http://localhost:8080";

  try {
    const response = await fetch(
      `${apiBaseUrl}/api/v1/reservations`,
      { cache: "no-store" },
    );

    if (!response.ok) {
      return NextResponse.json(
        { error: "Unable to load reservations from the PMS backend." },
        { status: response.status },
      );
    }

    const data: unknown = await response.json();

    if (!Array.isArray(data)) {
      return NextResponse.json(
        { error: "Unexpected reservations API response." },
        { status: 502 },
      );
    }

    return NextResponse.json(data);
  } catch {
    return NextResponse.json(
      { error: "PMS backend is unavailable." },
      { status: 502 },
    );
  }
}
