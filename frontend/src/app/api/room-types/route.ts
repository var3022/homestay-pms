
import { NextResponse } from "next/server";

export async function GET() {
  const apiBaseUrl =
    process.env.PMS_API_BASE_URL ?? "http://localhost:8080";

  const propertyId = process.env.PMS_PROPERTY_ID;

  if (!propertyId) {
    return NextResponse.json(
      { error: "PMS_PROPERTY_ID is not configured." },
      { status: 500 },
    );
  }

  try {
    const url =
      `${apiBaseUrl}/api/v1/room-types?propertyId=` +
      encodeURIComponent(propertyId);

    const response = await fetch(url, { cache: "no-store" });
    const body = await response.json();

    return NextResponse.json(body, { status: response.status });
  } catch {
    return NextResponse.json(
      { error: "Cannot connect to the PMS backend." },
      { status: 502 },
    );
  }
}
