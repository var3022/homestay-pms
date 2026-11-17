import { proxyToBackend } from "@/lib/auth/proxy";

export async function GET(request: Request) {
  return proxyToBackend(request, "/api/v1/reservations");
}

export async function POST(request: Request) {
  return proxyToBackend(request, "/api/v1/reservations", {
    injectPropertyId: true,
  });
}
