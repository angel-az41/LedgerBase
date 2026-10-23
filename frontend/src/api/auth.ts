import { getJson, postJson } from './client'

// ============================================================
// LEDGERBASE — AUTHENTICATION API
// ============================================================

/**
 * Typed authentication requests for Spring Boot.
 *
 * ACCESS TOKEN:
 *
 * - Returned as JSON after login or refresh.
 * - Held in React memory.
 * - Sent through Authorization: Bearer headers.
 *
 * REFRESH TOKEN:
 *
 * - Stored in a backend-issued HttpOnly cookie.
 * - Automatically managed by the browser.
 * - Never exposed to React code.
 *
 * During local development, requests use the
 * same-origin Vite proxy at /api/v1/auth.
 */


// ============================================================
// AUTHENTICATION TYPES
// ============================================================

export interface LoginRequest {
  email: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
  userId: number
  email: string
  fullName: string
}

export interface CurrentUserResponse {
  userId: number
  email: string
  fullName: string
}

/**
 * Refresh returns a new access JWT but deliberately
 * excludes the rotated refresh credential.
 */
export interface RefreshResponse {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
}


// ============================================================
// LOGIN
// ============================================================

/**
 * Authenticate with BCrypt-backed credentials.
 *
 * The backend also creates a PostgreSQL session
 * and issues a refresh cookie.
 */
export async function login(
  credentials: LoginRequest,
): Promise<LoginResponse> {

  return postJson<LoginResponse, LoginRequest>(
    '/api/v1/auth/login',
    credentials,
  )
}


// ============================================================
// CURRENT USER
// ============================================================

/**
 * Verify identity using the supplied access JWT.
 *
 * The token is provided explicitly because login
 * and session restoration must verify identity
 * before publishing authenticated React state.
 */
export async function getCurrentUser(
  token: string,
): Promise<CurrentUserResponse> {

  return getJson<CurrentUserResponse>(
    '/api/v1/auth/me',
    {
      Authorization: `Bearer ${token}`,
    },
  )
}


// ============================================================
// SINGLE-FLIGHT REFRESH REQUEST
// ============================================================

/**
 * Share one ongoing refresh request across callers
 * in this JavaScript tab.
 *
 * This is important because refresh credentials
 * are single-use.
 *
 * Two simultaneous requests presenting the same
 * token would trigger backend replay detection.
 *
 * This protection operates within one tab only.
 * Cross-tab coordination is a separate concern.
 */
let pendingRefresh: Promise<RefreshResponse> | null = null


// ============================================================
// REFRESH ACCESS TOKEN
// ============================================================

/**
 * Request a new access JWT using the HttpOnly cookie.
 *
 * The browser sends the refresh cookie automatically
 * for same-origin requests.
 *
 * The backend:
 *
 * - Validates the session.
 * - Rotates the refresh token.
 * - Replaces the HttpOnly cookie.
 * - Returns a newly signed access JWT.
 *
 * A missing or expired session produces an error.
 */
export function refreshSession(): Promise<RefreshResponse> {

  // ----------------------------------------------------------
  // REUSE EXISTING REFRESH OPERATION
  // ----------------------------------------------------------

  if (pendingRefresh !== null) {
    return pendingRefresh
  }


  // ----------------------------------------------------------
  // START COOKIE-AUTHENTICATED REFRESH
  // ----------------------------------------------------------

  pendingRefresh = (async () => {

    const response = await fetch(
      '/api/v1/auth/refresh',
      {
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        headers: {
          Accept: 'application/json',
        },
      },
    )


    // --------------------------------------------------------
    // REJECT UNAUTHORIZED OR FAILED REFRESH
    // --------------------------------------------------------

    if (!response.ok) {
      throw new Error(
        `Session refresh failed (${response.status})`,
      )
    }


    // --------------------------------------------------------
    // READ NEW ACCESS TOKEN
    // --------------------------------------------------------

    const result =
      (await response.json()) as RefreshResponse


    // --------------------------------------------------------
    // VALIDATE RESPONSE SHAPE
    // --------------------------------------------------------

    if (
      result.tokenType !== 'Bearer' ||
      typeof result.accessToken !== 'string' ||
      result.accessToken.length === 0 ||
      !Number.isFinite(result.expiresInSeconds) ||
      result.expiresInSeconds <= 0
    ) {
      throw new Error(
        'The authentication server returned an invalid refresh response.',
      )
    }

    return result

  })().finally(() => {

    // --------------------------------------------------------
    // ALLOW A FUTURE REFRESH OPERATION
    // --------------------------------------------------------

    pendingRefresh = null
  })

  return pendingRefresh
}


// ============================================================
// LOGOUT
// ============================================================

/**
 * Revoke the PostgreSQL authentication session.
 *
 * The backend identifies the session from the
 * HttpOnly refresh cookie and then clears it.
 *
 * Logout returns HTTP 204 No Content.
 *
 * If refresh is already running, allow it to finish
 * before sending logout so the browser uses the
 * latest rotated cookie.
 *
 * Do not report logout as complete when the backend
 * cannot confirm the request succeeded.
 */
export async function logout(): Promise<void> {

  // ----------------------------------------------------------
  // FINISH ANY PENDING TOKEN ROTATION
  // ----------------------------------------------------------

  if (pendingRefresh !== null) {

    try {
      await pendingRefresh
    } catch {

      // A rejected refresh does not prevent us
      // from attempting to clear the cookie.

    }
  }


  // ----------------------------------------------------------
  // REVOKE SESSION AND CLEAR COOKIE
  // ----------------------------------------------------------

  const response = await fetch(
    '/api/v1/auth/logout',
    {
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
    },
  )


  // ----------------------------------------------------------
  // REQUIRE SUCCESSFUL BACKEND LOGOUT
  // ----------------------------------------------------------

  if (response.status !== 204) {
    throw new Error(
      `Sign out failed (${response.status})`,
    )
  }
}
