export const DEV_COOKIE_NAME = "pms_session_dev";
export const PROD_COOKIE_NAME = "__Host-pms_session";

export function getSessionCookieName(): string {
  return process.env.NODE_ENV === "production" ? PROD_COOKIE_NAME : DEV_COOKIE_NAME;
}

export interface SessionCookieOptions {
  httpOnly: boolean;
  secure: boolean;
  sameSite: "lax" | "strict" | "none";
  path: string;
  maxAge: number;
}

export function getSessionCookieOptions(maxAge: number = 7 * 24 * 60 * 60): SessionCookieOptions {
  const isProd = process.env.NODE_ENV === "production";
  return {
    httpOnly: true,
    secure: isProd,
    sameSite: "lax",
    path: "/",
    maxAge,
  };
}

export function parseCookies(header: string | null | undefined): Record<string, string> {
  if (!header) return {};
  const cookies: Record<string, string> = {};
  const pairs = header.split(";");
  for (const pair of pairs) {
    const idx = pair.indexOf("=");
    if (idx > 0) {
      const key = pair.substring(0, idx).trim();
      const val = pair.substring(idx + 1).trim();
      cookies[key] = decodeURIComponent(val);
    }
  }
  return cookies;
}

export function serializeSetCookie(
  name: string,
  value: string,
  options: SessionCookieOptions
): string {
  const parts = [`${encodeURIComponent(name)}=${encodeURIComponent(value)}`];
  if (options.path) parts.push(`Path=${options.path}`);
  if (options.maxAge !== undefined) parts.push(`Max-Age=${options.maxAge}`);
  if (options.httpOnly) parts.push("HttpOnly");
  if (options.secure) parts.push("Secure");
  if (options.sameSite) {
    const capitalized =
      options.sameSite.charAt(0).toUpperCase() + options.sameSite.slice(1).toLowerCase();
    parts.push(`SameSite=${capitalized}`);
  }
  return parts.join("; ");
}
