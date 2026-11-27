/**
 * Canonical SHA-256 fingerprint computation for guest and reservation creation.
 *
 * Implements deterministic canonicalization matching the semantic scope of the backend
 * IdempotencyFingerprintService. Does NOT store raw PII or raw payloads in client persistence.
 */

export interface GuestFingerprintInput {
  propertyId?: string | null;
  name?: string | null;
  phone?: string | null;
  email?: string | null;
}

export interface ReservationRoomFingerprintInput {
  roomTypeId: string;
  quantity: number;
  unitPrice: number | string;
}

export interface ReservationFingerprintInput {
  propertyId?: string | null;
  primaryGuestId?: string | null;
  checkInDate: string;
  checkOutDate: string;
  adults: number;
  children: number;
  infants: number;
  bookingSourceId: string;
  externalBookingId?: string | null;
  specialRequests?: string | null;
  rooms: ReservationRoomFingerprintInput[];
}

function normalizeString(value?: string | null): string | null {
  if (value == null) return null;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

function normalizeEmail(value?: string | null): string | null {
  if (value == null) return null;
  const trimmed = value.trim().toLowerCase();
  return trimmed.length > 0 ? trimmed : null;
}

function normalizeUuid(value?: string | null): string | null {
  if (value == null) return null;
  const trimmed = value.trim().toLowerCase();
  return trimmed.length > 0 ? trimmed : null;
}

function normalizeDecimal(value: number | string): string {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value.toString() : "0";
  }
  const str = String(value).trim();
  const num = parseFloat(str);
  return Number.isFinite(num) ? num.toString() : "0";
}

function appendField(sb: string[], fieldName: string, value: string | null): void {
  sb.push(fieldName, ":");
  if (value == null) {
    sb.push("-1;");
  } else {
    sb.push(value.length.toString(), ":", value, ";");
  }
}

async function sha256Hex(canonicalString: string): Promise<string> {
  const enc = new TextEncoder();
  const data = enc.encode(canonicalString);

  if (typeof globalThis.crypto !== "undefined" && globalThis.crypto.subtle) {
    const hashBuffer = await globalThis.crypto.subtle.digest("SHA-256", data);
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    return hashArray.map((b) => b.toString(16).padStart(2, "0")).join("");
  }

  // Fallback for Node environments where globalThis.crypto is unavailable
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const nodeCrypto = require("node:crypto");
    return nodeCrypto.createHash("sha256").update(canonicalString, "utf8").digest("hex");
  } catch {
    throw new Error("SHA-256 cryptographic engine unavailable in this environment.");
  }
}

/**
 * Computes canonical SHA-256 fingerprint for guest creation payload.
 */
export async function computeGuestFingerprint(input: GuestFingerprintInput): Promise<string> {
  const parts: string[] = [];
  appendField(parts, "propertyId", normalizeUuid(input.propertyId));
  appendField(parts, "name", normalizeString(input.name));
  appendField(parts, "phone", normalizeString(input.phone));
  appendField(parts, "email", normalizeEmail(input.email));

  return sha256Hex(parts.join(""));
}

/**
 * Computes canonical SHA-256 fingerprint for reservation creation payload.
 */
export async function computeReservationFingerprint(input: ReservationFingerprintInput): Promise<string> {
  const parts: string[] = [];
  appendField(parts, "propertyId", normalizeUuid(input.propertyId));
  appendField(parts, "primaryGuestId", normalizeUuid(input.primaryGuestId));
  appendField(parts, "checkInDate", normalizeString(input.checkInDate));
  appendField(parts, "checkOutDate", normalizeString(input.checkOutDate));
  appendField(parts, "adults", String(input.adults ?? 1));
  appendField(parts, "children", String(input.children ?? 0));
  appendField(parts, "infants", String(input.infants ?? 0));
  appendField(parts, "bookingSourceId", normalizeUuid(input.bookingSourceId));
  appendField(parts, "externalBookingId", normalizeString(input.externalBookingId));
  appendField(parts, "specialRequests", normalizeString(input.specialRequests));

  parts.push("rooms:");
  if (!input.rooms || input.rooms.length === 0) {
    parts.push("-1;");
  } else {
    // Sort deterministically by roomTypeId, then quantity, then unitPrice
    const sortedRooms = [...input.rooms].sort((a, b) => {
      const rtA = (a.roomTypeId || "").toLowerCase();
      const rtB = (b.roomTypeId || "").toLowerCase();
      if (rtA !== rtB) return rtA.localeCompare(rtB);
      if (a.quantity !== b.quantity) return a.quantity - b.quantity;
      return normalizeDecimal(a.unitPrice).localeCompare(normalizeDecimal(b.unitPrice));
    });

    const roomsParts: string[] = [];
    for (const room of sortedRooms) {
      roomsParts.push("[");
      appendField(roomsParts, "roomTypeId", normalizeUuid(room.roomTypeId));
      appendField(roomsParts, "quantity", String(room.quantity));
      appendField(roomsParts, "unitPrice", normalizeDecimal(room.unitPrice));
      roomsParts.push("]");
    }
    const roomsStr = roomsParts.join("");
    parts.push(roomsStr.length.toString(), ":", roomsStr, ";");
  }

  return sha256Hex(parts.join(""));
}
