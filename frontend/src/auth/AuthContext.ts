import { createContext } from 'react'

import type {
  CurrentUserResponse,
  LoginRequest,
} from '../api/auth'

// ============================================================
// LEDGERBASE — AUTHENTICATION CONTEXT CONTRACT
// ============================================================

/**
 * Defines authentication data and actions available
 * to React components throughout LedgerBase.
 *
 * SECURITY:
 *
 * - Tokens are held in React memory.
 * - Tokens are not persisted in localStorage.
 * - User identity comes from the backend.
 * - Business authorization is enforced separately.
 */
export interface AuthContextValue {

  // Currently authenticated PostgreSQL user.
  currentUser: CurrentUserResponse | null

  // JWT access token for authenticated API requests.
  accessToken: string | null

  // True when an active login session exists.
  isAuthenticated: boolean

  // True while the login request is processing.
  isSigningIn: boolean

  // Authenticate with the backend.
  signIn: (credentials: LoginRequest) => Promise<void>

  // Clear the frontend authentication session.
  signOut: () => void
}


// ============================================================
// REACT CONTEXT
// ============================================================

/**
 * Undefined means the component has not been wrapped
 * by AuthProvider.
 *
 * The useAuth hook will detect that programming error.
 */
export const AuthContext =
  createContext<AuthContextValue | undefined>(undefined)
