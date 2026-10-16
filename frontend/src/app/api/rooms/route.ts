
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
    const response = await fetch(
      `${apiBaseUrl}/api/v1/rooms?propertyId=${encodeURIComponent(propertyId)}`,
      { cache: "no-store" },
    );

    if (!response.ok) {
      return NextResponse.json(
        { error: "Unable to load rooms from the PMS backend." },
        { status: response.status },
      );
    }

    const rooms = await response.json();

    return NextResponse.json(rooms);
  } catch {
    return NextResponse.json(
      { error: "Cannot connect to the PMS backend." },
      { status: 502 },
    );
  }
}
