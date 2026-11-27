/**
 * Structured HTTP error thrown by frontend BFF fetch helpers so mutation
 * handlers can classify outcomes by status code rather than message text alone.
 */
export class ApiRequestError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly retryAfter?: string | null;

  constructor(
    message: string,
    status: number,
    code?: string,
    retryAfter?: string | null
  ) {
    super(message);
    this.name = "ApiRequestError";
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
  }
}

/**
 * Classifies whether an error indicates a concurrent in-progress idempotency operation (HTTP 409).
 * Checks both IDEMPOTENCY_IN_PROGRESS and IDEMPOTENCY_KEY_IN_PROGRESS, HTTP 409 with Retry-After header,
 * or backend message "A request with this idempotency key is currently in progress. Please retry."
 */
export function isIdempotencyInProgress(error: unknown): boolean {
  if (error instanceof ApiRequestError) {
    if (
      error.code === "IDEMPOTENCY_IN_PROGRESS" ||
      error.code === "IDEMPOTENCY_KEY_IN_PROGRESS"
    ) {
      return true;
    }
    if (error.status === 409 && (error.retryAfter != null || error.message.toLowerCase().includes("in progress"))) {
      return true;
    }
  }
  if (error instanceof Error) {
    const msg = error.message.toLowerCase();
    return (
      msg.includes("idempotency_in_progress") ||
      msg.includes("idempotency_key_in_progress") ||
      msg.includes("in progress")
    );
  }
  return false;
}

/**
 * Classifies whether an error indicates an expired idempotency key (HTTP 409).
 * Checks IDEMPOTENCY_KEY_EXPIRED, IDEMPOTENCY_EXPIRED, or backend message "The idempotency key has expired."
 */
export function isIdempotencyExpired(error: unknown): boolean {
  if (error instanceof ApiRequestError) {
    if (
      error.code === "IDEMPOTENCY_KEY_EXPIRED" ||
      error.code === "IDEMPOTENCY_EXPIRED"
    ) {
      return true;
    }
    if (error.status === 409 && error.message.toLowerCase().includes("expired")) {
      return true;
    }
  }
  if (error instanceof Error) {
    const msg = error.message.toLowerCase();
    return (
      msg.includes("idempotency_key_expired") ||
      msg.includes("idempotency_expired") ||
      (msg.includes("idempotency") && msg.includes("expired"))
    );
  }
  return false;
}

/**
 * Classifies whether an error indicates an idempotency payload mismatch (HTTP 422).
 * Checks IDEMPOTENCY_PAYLOAD_MISMATCH, IDEMPOTENCY_CONFLICT, or backend message "The idempotency key is already used with a different request payload."
 */
export function isIdempotencyPayloadMismatch(error: unknown): boolean {
  if (error instanceof ApiRequestError) {
    if (
      error.code === "IDEMPOTENCY_PAYLOAD_MISMATCH" ||
      error.code === "IDEMPOTENCY_CONFLICT"
    ) {
      return true;
    }
    if (
      error.status === 422 &&
      (error.message.toLowerCase().includes("different request payload") ||
        error.message.toLowerCase().includes("payload mismatch"))
    ) {
      return true;
    }
  }
  if (error instanceof Error) {
    const msg = error.message.toLowerCase();
    return (
      msg.includes("idempotency_payload_mismatch") ||
      msg.includes("idempotency_conflict") ||
      msg.includes("different request payload")
    );
  }
  return false;
}

/**
 * Classifies whether a mutation failure has an ambiguous outcome that requires
 * authoritative state reconciliation before any retry is permitted.
 *
 * - 409 (Conflict / serialization failure / lock contention / in-progress) and 503
 *   (deadlock / unavailable) are treated conservatively as uncertain outcomes when
 *   no safe discriminator guarantees the mutation was not committed.
 * - 400, 401, 403, 404, 422 are deterministic rejections and return false.
 * - 409 with IDEMPOTENCY_KEY_EXPIRED is a deterministic rejection (returns false).
 */
export function isUncertainError(error: unknown): boolean {
  if (error instanceof ApiRequestError) {
    if (isIdempotencyExpired(error)) {
      return false;
    }
    if (
      error.status === 409 ||
      error.status === 503 ||
      error.status === 502 ||
      error.status === 504 ||
      error.status === 408 ||
      error.status >= 500
    ) {
      return true;
    }
    if (
      error.status === 400 ||
      error.status === 401 ||
      error.status === 403 ||
      error.status === 404 ||
      error.status === 422
    ) {
      return false;
    }
  }

  const message =
    typeof error === "string"
      ? error
      : error instanceof Error
        ? error.message
        : "";

  if (!message) return false;

  const lower = message.toLowerCase();
  if (lower.includes("idempotency key has expired") || lower.includes("idempotency_key_expired")) {
    return false;
  }
  if (lower.includes("different request payload") || lower.includes("idempotency_payload_mismatch")) {
    return false;
  }

  return (
    lower.includes("cannot connect") ||
    lower.includes("failed to fetch") ||
    lower.includes("network") ||
    lower.includes("failed (409)") ||
    lower.includes("failed (502)") ||
    lower.includes("failed (503)") ||
    lower.includes("504") ||
    lower.includes("gateway") ||
    lower.includes("timeout") ||
    lower.includes("timed out") ||
    lower.includes("aborted") ||
    lower.includes("lock contention") ||
    lower.includes("concurrent modification conflict") ||
    lower.includes("in progress")
  );
}

export interface UncertainAssignmentAttempt {
  reservationId: string;
  reservationRoomId: string;
  roomId: string;
  assignedFrom: string;
  assignedUntil: string;
}

export interface ReconcilableRoomAssignment {
  reservationRoomId: string;
  roomId: string;
  assignedFrom: string;
  assignedUntil: string;
  status: string;
}

/**
 * Matches a refreshed authoritative room assignment against the exact
 * reservationRoomId, roomId, full stay dates, and ASSIGNED status of an
 * uncertain attempt. Released, partial-stay, or different-room records return false.
 */
export function matchesExactAssignment(
  attempt: UncertainAssignmentAttempt,
  assignment: ReconcilableRoomAssignment
): boolean {
  return (
    assignment.reservationRoomId === attempt.reservationRoomId &&
    assignment.roomId === attempt.roomId &&
    assignment.assignedFrom === attempt.assignedFrom &&
    assignment.assignedUntil === attempt.assignedUntil &&
    assignment.status === "ASSIGNED"
  );
}
