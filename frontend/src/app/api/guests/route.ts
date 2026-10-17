
import { NextResponse } from "next/server";

const apiBaseUrl =
  process.env.PMS_API_BASE_URL ?? "http://localhost:8080";

export async function GET() {
  try {
    const response = await fetch(`${apiBaseUrl}/api/v1/guests`, {
      cache: "no-store",
    });

    const body = await response.json();

    return NextResponse.json(body, { status: response.status });
  } catch {
    return NextResponse.json(
      { error: "Cannot connect to the PMS backend." },
      { status: 502 },
    );
  }
}

export async function POST(request: Request) {
  try {
    const payload = await request.json();

    const response = await fetch(`${apiBaseUrl}/api/v1/guests`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
      cache: "no-store",
    });

    const body = await response.json();

    return NextResponse.json(body, { status: response.status });
  } catch {
    return NextResponse.json(
      { error: "Cannot connect to the PMS backend." },
      { status: 502 },
    );
  }
}
