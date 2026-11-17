import { proxyToBackend } from "@/lib/auth/proxy";

export async function GET(request: Request) {
  return proxyToBackend(request, "/api/v1/room-types", {
    requirePropertyId: true,
  });
}
