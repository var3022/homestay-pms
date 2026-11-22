import { NextResponse } from "next/server";
import { proxyToBackend } from "@/lib/auth/proxy";

const UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const ALLOWED_HOUSEKEEPING_STATUSES = new Set([
  "CLEAN",
  "DIRTY",
  "CLEANING_IN_PROGRESS",
]);

export async function PATCH(
  request: Request,
  props: { params: Promise<{ id: string }> }
) {
  const { id } = await props.params;

  if (!id || !UUID_REGEX.test(id)) {
    return NextResponse.json(
      { error: "Invalid room UUID parameter." },
      {
        status: 400,
        headers: {
          "Cache-Control": "no-store, no-cache, must-revalidate, private",
          Pragma: "no-cache",
        },
      }
    );
  }

  // Pre-validate JSON body and housekeepingStatus
  try {
    const cloned = request.clone();
    const body = await cloned.json();
    if (
      !body ||
      typeof body !== "object" ||
      !body.housekeepingStatus ||
      !ALLOWED_HOUSEKEEPING_STATUSES.has(body.housekeepingStatus)
    ) {
      return NextResponse.json(
        {
          error:
            "Invalid housekeepingStatus. Allowed values: CLEAN, DIRTY, CLEANING_IN_PROGRESS.",
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

  return proxyToBackend(
    request,
    `/api/v1/rooms/${encodeURIComponent(id)}/housekeeping-status`
  );
}
