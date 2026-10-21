import { getJson, postJson } from './client'

// ============================================================
// LEDGERBASE — AUTHENTICATION API
// ============================================================

/**
 * Typed HTTP requests for LedgerBase authentication.
 *
 * BACKEND ENDPOINTS:
 *
 * POST /api/v1/auth/login
 * GET  /api/v1/auth/me
 *
 * All requests reuse the shared fetch-based API client.
 *
 * Authentication state and token storage are handled
 * by the React authentication provider, not this file.
 */


// ============================================================
// LOGIN REQUEST
// ============================================================

/**
 * Credentials sent to Spring Boot.
 *
 * Password verification takes place on the backend
 * using BCrypt.
 */
export interface LoginRequest {
  email: string
  password: string
}


// ============================================================
// LOGIN RESPONSE
// ============================================================

/**
 * A successful login returns:
 *
 * - Signed JWT access token
 * - Bearer token type
 * - Token lifetime in seconds
 * - Authenticated user identity
 *
 * The password and password hash are never returned.
 */
export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresInSeconds: number

  userId: number
  email: string
  fullName: string
}


// ============================================================
// CURRENT USER RESPONSE
// ============================================================

/**
 * Identity retrieved using a verified JWT.
 *
 * Business-specific roles and permissions will be
 * added through the backend authorization layer.
 */
export interface CurrentUserResponse {
  userId: number
  email: string
  fullName: string
}


// ============================================================
// LOGIN
// ============================================================

/**
 * Authenticate with email and password.
 *
 * HTTP 200 returns LoginResponse.
 * HTTP 401 indicates invalid credentials.
 *
 * The shared client throws an Error when the
 * backend returns an unsuccessful response.
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
// FETCH CURRENT AUTHENTICATED USER
// ============================================================

/**
 * Retrieve the user associated with a verified JWT.
 *
 * Spring Security validates the token before the
 * controller retrieves the user from PostgreSQL.
 *
 * The access token is passed explicitly because
 * global authentication headers are not yet enabled.
 */
export async function getCurrentUser(
  accessToken: string,
): Promise<CurrentUserResponse> {

  return getJson<CurrentUserResponse>(
    '/api/v1/auth/me',
    {
      Authorization: `Bearer ${accessToken}`,
    },
  )
}
