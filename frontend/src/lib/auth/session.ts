import type { StaffSessionData } from "./types";

/**
 * Derives session secret with strict entropy enforcement.
 * Rejects missing, empty, or sub-32-character secrets in all environments (fails closed).
 */
export function getSessionSecret(): string {
  const secret = process.env.PMS_SESSION_SECRET;
  if (!secret || secret.trim().length < 32) {
    throw new Error(
      "FATAL: PMS_SESSION_SECRET environment variable is missing or less than 32 characters. Session cryptographic engine cannot start."
    );
  }
  return secret.trim();
}

let cachedKeyPromise: Promise<CryptoKey> | null = null;
let cachedSecret = "";

async function getCryptoKey(): Promise<CryptoKey> {
  const secret = getSessionSecret();
  if (cachedKeyPromise && cachedSecret === secret) {
    return cachedKeyPromise;
  }
  cachedSecret = secret;
  cachedKeyPromise = (async () => {
    const enc = new TextEncoder();
    const keyDigest = await crypto.subtle.digest("SHA-256", enc.encode(secret));
    return crypto.subtle.importKey(
      "raw",
      keyDigest,
      { name: "AES-GCM" },
      false,
      ["encrypt", "decrypt"]
    );
  })();
  return cachedKeyPromise;
}

export function toBase64Url(bytes: Uint8Array): string {
  if (typeof Buffer !== "undefined") {
    return Buffer.from(bytes).toString("base64url");
  }
  let bin = "";
  for (let i = 0; i < bytes.length; i++) {
    bin += String.fromCharCode(bytes[i]);
  }
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function fromBase64Url(base64url: string): Uint8Array {
  if (typeof Buffer !== "undefined") {
    return new Uint8Array(Buffer.from(base64url, "base64url"));
  }
  let base64 = base64url.replace(/-/g, "+").replace(/_/g, "/");
  while (base64.length % 4 !== 0) {
    base64 += "=";
  }
  const bin = atob(base64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) {
    bytes[i] = bin.charCodeAt(i);
  }
  return bytes;
}

/**
 * Encrypts session data into an AES-256-GCM authenticated ciphertext.
 * Prepends a 12-byte random IV and returns base64url-encoded string.
 */
export async function encryptSession(sessionData: StaffSessionData): Promise<string> {
  const key = await getCryptoKey();
  const iv = crypto.getRandomValues(new Uint8Array(12));

  // Ensure monotonic sessionVersion
  const sanitizedData: StaffSessionData = {
    ...sessionData,
    sessionVersion:
      typeof sessionData.sessionVersion === "number" && sessionData.sessionVersion >= 1
        ? sessionData.sessionVersion
        : 1,
  };

  const payload = new TextEncoder().encode(JSON.stringify(sanitizedData));

  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv },
    key,
    payload
  );

  const combined = new Uint8Array(iv.length + ciphertext.byteLength);
  combined.set(iv, 0);
  combined.set(new Uint8Array(ciphertext), iv.length);

  return toBase64Url(combined);
}

/**
 * Decrypts and verifies session data.
 * Returns null if cookie is oversized, tampering occurs, signature fails, or data is invalid.
 */
export async function decryptSession(
  cookieValue: string | null | undefined
): Promise<StaffSessionData | null> {
  if (!cookieValue || typeof cookieValue !== "string") {
    return null;
  }

  // Reject oversized cookies immediately (standard browser limit protection)
  if (cookieValue.length > 4096) {
    return null;
  }

  try {
    const raw = fromBase64Url(cookieValue);
    if (raw.length < 12 + 16) {
      // 12 bytes IV + at least 16 bytes auth tag
      return null;
    }

    const iv = new Uint8Array(raw.subarray(0, 12));
    const ciphertext = new Uint8Array(raw.subarray(12));
    const key = await getCryptoKey();

    const decrypted = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv },
      key,
      ciphertext
    );

    const decoded = new TextDecoder().decode(decrypted);
    const parsed = JSON.parse(decoded) as StaffSessionData;

    if (
      !parsed.accessToken ||
      !parsed.refreshToken ||
      !parsed.staff ||
      !parsed.staff.id ||
      !parsed.accessTokenExpiresAt
    ) {
      return null;
    }

    if (typeof parsed.sessionVersion !== "number" || parsed.sessionVersion < 1) {
      parsed.sessionVersion = 1;
    }

    return parsed;
  } catch {
    // Decryption failed or tampered - fail closed
    return null;
  }
}

/**
 * Returns true if the access token has expired or is expiring within thresholdMs (default 60s).
 */
export function isSessionExpiringSoon(
  session: StaffSessionData,
  thresholdMs: number = 60_000
): boolean {
  return Date.now() >= session.accessTokenExpiresAt - thresholdMs;
}
