
import { NextResponse } from "next/server";

export async function GET() {
  const apiBaseUrl =
    process.env.PMS_API_BASE_URL ?? "http://localhost:8080";

  try {
    const response = await fetch(
      `${apiBaseUrl}/api/v1/booking-sources`,
      { cache: "no-store" },
    );

    const body = await response.json();

    return NextResponse.json(body, { status: response.status });
  } catch {
    return NextResponse.json(
      { error: "Cannot connect to the PMS backend." },
      { status: 502 },
    );
  }
}
