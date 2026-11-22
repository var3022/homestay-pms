import { NextResponse } from "next/server";
import { proxyToBackend } from "@/lib/auth/proxy";

const UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export async function PATCH(
  request: Request,
  props: { params: Promise<{ id: string }> }
) {
  const { id } = await props.params;

  if (!id || !UUID_REGEX.test(id)) {
    return NextResponse.json(
      { error: "Invalid reservation UUID parameter." },
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
    `/api/v1/reservations/${encodeURIComponent(id)}/confirm`
  );
}
