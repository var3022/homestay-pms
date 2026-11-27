/**
 * Structured HTTP error thrown by frontend BFF fetch helpers so mutation
 * handlers can classify outcomes by status code rather than message text alone.
 */
export class ApiRequestError extends Error {
  readonly status: number;
  readonly code?: string;

  constructor(message: string, status: number, code?: string) {
    super(message);
    this.name = "ApiRequestError";
    this.status = status;
    this.code = code;
  }
}

/**
 * Classifies whether a mutation failure has an ambiguous outcome that requires
 * authoritative state reconciliation before any retry is permitted.
 *
 * - 409 (Conflict / serialization failure) and 503 (deadlock / unavailable)
 *   are treated conservatively as uncertain outcomes when no safe discriminator
 *   guarantees the mutation was not committed.
 * - 400, 401, 403, 404, 422 are deterministic rejections and return false.
 */
export function isUncertainError(error: unknown): boolean {
  if (error instanceof ApiRequestError) {
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
    lower.includes("concurrent modification conflict")
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
