import { useContext } from 'react'

import { AuthContext } from './AuthContext'

// ============================================================
// LEDGERBASE — AUTHENTICATION HOOK
// ============================================================

/**
 * Provides easy access to authentication state
 * from any component inside AuthProvider.
 *
 * USAGE:
 *
 * const {
 *   currentUser,
 *   isAuthenticated,
 *   signIn,
 *   signOut,
 * } = useAuth()
 *
 * Components outside the provider produce a clear
 * programming error instead of silently receiving
 * an undefined authentication context.
 */
export function useAuth() {

  const context = useContext(AuthContext)

  if (context === undefined) {
    throw new Error(
      'useAuth must be used within AuthProvider',
    )
  }

  return context
}
