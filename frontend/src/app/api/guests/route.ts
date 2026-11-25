import { proxyToBackend } from "@/lib/auth/proxy";

export async function GET(request: Request) {
  return proxyToBackend(request, "/api/v1/guests");
}

export async function POST(request: Request) {
  return proxyToBackend(request, "/api/v1/guests", {
    injectPropertyId: true,
  });
}
