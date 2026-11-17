export function getAllowedOrigins(): Set<string> {
  const allowed = new Set<string>();

  const envOrigin = process.env.PMS_FRONTEND_ORIGIN;
  if (envOrigin) {
    try {
      const parsed = new URL(envOrigin);
      allowed.add(parsed.origin.toLowerCase());
    } catch {
      // Invalid URL in env - ignored
    }
  }

  // Development defaults (strictly when NODE_ENV is not production)
  if (process.env.NODE_ENV !== "production") {
    allowed.add("http://localhost:3000");
    allowed.add("http://127.0.0.1:3000");
  }

  return allowed;
}

export interface CsrfVerificationResult {
  valid: boolean;
  reason?: string;
}

/**
 * Validates request origin evidence against allowed origins.
 *
 * Rules:
 * - Safe methods (GET, HEAD, OPTIONS) pass unconditionally.
 * - Mutating methods (POST, PUT, PATCH, DELETE) MUST provide Origin or Referer.
 * - Foreign origins are REJECTED immediately, regardless of custom client headers (X-PMS-Client, etc.).
 * - Missing origin evidence is REJECTED.
 * - Conflicting Origin and Referer are REJECTED.
 * - Disallowed schemes (javascript:, file:, null, data:) are REJECTED.
 */
export function verifyCsrf(request: Request): CsrfVerificationResult {
  const method = request.method.toUpperCase();
  if (["GET", "HEAD", "OPTIONS"].includes(method)) {
    return { valid: true };
  }

  const originHeader = request.headers.get("origin");
  const refererHeader = request.headers.get("referer");

  // Rule 1: Must provide origin evidence
  if (!originHeader && !refererHeader) {
    return {
      valid: false,
      reason: "Missing origin evidence: neither Origin nor Referer header provided for mutating request.",
    };
  }

  const allowedOrigins = getAllowedOrigins();

  let parsedOrigin: string | null = null;
  let parsedRefererOrigin: string | null = null;

  // Rule 2: Origin header validation
  if (originHeader) {
    if (originHeader.toLowerCase() === "null") {
      return { valid: false, reason: "Null origin is rejected." };
    }
    try {
      const u = new URL(originHeader);
      if (u.protocol !== "http:" && u.protocol !== "https:") {
        return { valid: false, reason: `Invalid protocol scheme: ${u.protocol}` };
      }
      parsedOrigin = u.origin.toLowerCase();
    } catch {
      return { valid: false, reason: "Malformed Origin header." };
    }

    if (!allowedOrigins.has(parsedOrigin)) {
      return {
        valid: false,
        reason: `Foreign Origin '${parsedOrigin}' is rejected.`,
      };
    }
  }

  // Rule 3: Referer header validation
  if (refererHeader) {
    try {
      const u = new URL(refererHeader);
      if (u.protocol !== "http:" && u.protocol !== "https:") {
        return { valid: false, reason: `Invalid Referer protocol: ${u.protocol}` };
      }
      parsedRefererOrigin = u.origin.toLowerCase();
    } catch {
      return { valid: false, reason: "Malformed Referer header." };
    }

    if (!allowedOrigins.has(parsedRefererOrigin)) {
      return {
        valid: false,
        reason: `Foreign Referer '${parsedRefererOrigin}' is rejected.`,
      };
    }
  }

  // Rule 4: Conflicting Origin and Referer
  if (parsedOrigin && parsedRefererOrigin && parsedOrigin !== parsedRefererOrigin) {
    return {
      valid: false,
      reason: `Conflicting Origin ('${parsedOrigin}') and Referer ('${parsedRefererOrigin}').`,
    };
  }

  return { valid: true };
}

export function isCsrfValid(request: Request): boolean {
  return verifyCsrf(request).valid;
}

/**
 * Validates that redirect paths point strictly to internal dashboard paths.
 * Rejects protocol-relative, encoded, backslash, external, and non-dashboard URLs.
 */
export function sanitizeDashboardRedirect(redirectParam: string | null | undefined): string {
  if (!redirectParam || typeof redirectParam !== "string") {
    return "/dashboard";
  }

  const trimmed = redirectParam.trim();
  if (!trimmed) {
    return "/dashboard";
  }

  // Reject protocol-relative (//evil.com), backslash bypasses (/\evil.com, \evil.com)
  if (
    trimmed.startsWith("//") ||
    trimmed.startsWith("/\\") ||
    trimmed.startsWith("\\") ||
    trimmed.startsWith("/%")
  ) {
    return "/dashboard";
  }

  // Check decoded representation for bypasses
  let decoded: string;
  try {
    decoded = decodeURIComponent(trimmed);
  } catch {
    return "/dashboard";
  }

  if (
    decoded.startsWith("//") ||
    decoded.startsWith("/\\") ||
    decoded.startsWith("\\")
  ) {
    return "/dashboard";
  }

  // Reject URLs containing a scheme (javascript:, data:, http:, https:)
  const colonIndex = decoded.indexOf(":");
  const slashIndex = decoded.indexOf("/");
  if (colonIndex !== -1 && (slashIndex === -1 || colonIndex < slashIndex)) {
    return "/dashboard";
  }

  try {
    // Parse against a dummy local base
    const parsed = new URL(trimmed, "http://localhost");
    const pathname = parsed.pathname;

    // Must strictly start with /dashboard or be /dashboard
    if (pathname !== "/dashboard" && !pathname.startsWith("/dashboard/")) {
      return "/dashboard";
    }

    // Prohibit redirection to auth or internal API proxy endpoints
    if (pathname.includes("/api/") || pathname.includes("/auth/")) {
      return "/dashboard";
    }

    return parsed.pathname + parsed.search;
  } catch {
    return "/dashboard";
  }
}
