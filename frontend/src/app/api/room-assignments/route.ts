import { NextResponse } from "next/server";
import { proxyToBackend } from "@/lib/auth/proxy";

const UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const DATE_REGEX = /^\d{4}-\d{2}-\d{2}$/;

export async function GET(request: Request) {
  return proxyToBackend(request, "/api/v1/room-assignments");
}

export async function POST(request: Request) {
  // Pre-validate JSON body and fields before proxying
  try {
    const cloned = request.clone();
    const body = await cloned.json();

    if (!body || typeof body !== "object") {
      return NextResponse.json(
        { error: "Invalid JSON in request body." },
        {
          status: 400,
          headers: {
            "Cache-Control": "no-store, no-cache, must-revalidate, private",
            Pragma: "no-cache",
          },
        }
      );
    }

    const { reservationRoomId, roomId, assignedFrom, assignedUntil } =
      body as Record<string, unknown>;

    if (
      typeof reservationRoomId !== "string" ||
      !UUID_REGEX.test(reservationRoomId) ||
      typeof roomId !== "string" ||
      !UUID_REGEX.test(roomId)
    ) {
      return NextResponse.json(
        { error: "Invalid UUID in reservationRoomId or roomId." },
        {
          status: 400,
          headers: {
            "Cache-Control": "no-store, no-cache, must-revalidate, private",
            Pragma: "no-cache",
          },
        }
      );
    }

    if (
      typeof assignedFrom !== "string" ||
      !DATE_REGEX.test(assignedFrom) ||
      typeof assignedUntil !== "string" ||
      !DATE_REGEX.test(assignedUntil) ||
      assignedUntil <= assignedFrom
    ) {
      return NextResponse.json(
        {
          error:
            "Invalid assignment date range: assignedUntil must be after assignedFrom (YYYY-MM-DD).",
        },
        {
          status: 400,
          headers: {
            "Cache-Control": "no-store, no-cache, must-revalidate, private",
            Pragma: "no-cache",
          },
        }
      );
    }
  } catch {
    return NextResponse.json(
      { error: "Invalid JSON in request body." },
      {
        status: 400,
        headers: {
          "Cache-Control": "no-store, no-cache, must-revalidate, private",
          Pragma: "no-cache",
        },
      }
    );
  }

  return proxyToBackend(request, "/api/v1/room-assignments");
}
