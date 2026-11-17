export interface StaffUser {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
}

export interface StaffSessionData {
  accessToken: string;
  accessTokenExpiresAt: number; // Unix epoch ms
  refreshToken: string;
  refreshTokenCookieName: string;
  staff: StaffUser;
  sessionVersion: number; // Monotonic generation counter
}

export interface BackendLoginResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  staff: StaffUser;
}

export interface BackendRefreshResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  staff: StaffUser;
}

export interface ClientSessionResponse {
  authenticated: boolean;
  user: StaffUser | null;
}
