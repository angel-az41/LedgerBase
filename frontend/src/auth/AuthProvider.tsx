import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'

import {
  login,
  getCurrentUser,
  type LoginRequest,
  type CurrentUserResponse,
} from '../api/auth'

import {
  AuthContext,
  type AuthContextValue,
} from './AuthContext'

// ============================================================
// LEDGERBASE — AUTHENTICATION PROVIDER
// ============================================================

/**
 * Owns the current browser authentication session.
 *
 * LOGIN WORKFLOW:
 *
 * 1. Send credentials to Spring Boot.
 * 2. Receive a signed JWT.
 * 3. Verify the session through GET /auth/me.
 * 4. Store the user and token in React memory.
 *
 * LOGOUT:
 *
 * Clear the in-memory session.
 *
 * EXPIRATION:
 *
 * Clear the session when the token lifetime ends.
 *
 * SECURITY:
 *
 * - Never log or persist access tokens.
 * - Never store plaintext passwords.
 * - Never treat frontend state as authorization.
 *
 * Backend RBAC must independently validate access
 * to every protected business resource.
 */


// ============================================================
// INTERNAL SESSION MODEL
// ============================================================

interface AuthSession {
  user: CurrentUserResponse
  accessToken: string
  expiresAt: number
}


// ============================================================
// PROVIDER PROPS
// ============================================================

interface AuthProviderProps {
  children: ReactNode
}


// ============================================================
// AUTHENTICATION PROVIDER COMPONENT
// ============================================================

export function AuthProvider({
  children,
}: AuthProviderProps) {

  // ==========================================================
  // IN-MEMORY SESSION STATE
  // ==========================================================

  /**
   * No session is restored from browser storage.
   *
   * Therefore, a page refresh starts a fresh,
   * unauthenticated browser session.
   */
  const [session, setSession] =
    useState<AuthSession | null>(null)

  const [isSigningIn, setIsSigningIn] =
    useState(false)


  // ==========================================================
  // REQUEST SEQUENCE TRACKING
  // ==========================================================

  /**
   * Prevent an earlier unfinished login request from
   * restoring authentication after the user signs out
   * or starts another login attempt.
   */
  const requestVersion = useRef(0)


  // ==========================================================
  // LOGIN ACTION
  // ==========================================================

  const signIn = useCallback(
    async (credentials: LoginRequest): Promise<void> => {

      const currentRequest = ++requestVersion.current

      setSession(null)
      setIsSigningIn(true)

      try {

        // ----------------------------------------------------
        // AUTHENTICATE WITH SPRING BOOT
        // ----------------------------------------------------

        const loginResponse = await login(credentials)

        // ----------------------------------------------------
        // VALIDATE THE EXPECTED RESPONSE FORMAT
        // ----------------------------------------------------

        if (
          loginResponse.tokenType !== 'Bearer' ||
          !loginResponse.accessToken ||
          !Number.isFinite(loginResponse.expiresInSeconds) ||
          loginResponse.expiresInSeconds <= 0
        ) {
          throw new Error(
            'The authentication server returned an invalid session.',
          )
        }

        // Record expiration as soon as login completes.
        const expiresAt =
          Date.now() +
          loginResponse.expiresInSeconds * 1000

        // ----------------------------------------------------
        // VERIFY IDENTITY USING THE SIGNED TOKEN
        // ----------------------------------------------------

        const currentUser = await getCurrentUser(
          loginResponse.accessToken,
        )

        if (
          currentUser.userId !== loginResponse.userId
        ) {
          throw new Error(
            'The authenticated user could not be verified.',
          )
        }

        // ----------------------------------------------------
        // IGNORE OUTDATED LOGIN REQUESTS
        // ----------------------------------------------------

        if (currentRequest !== requestVersion.current) {
          return
        }

        if (Date.now() >= expiresAt) {
          throw new Error(
            'The authentication session has expired.',
          )
        }

        // ----------------------------------------------------
        // STORE THE VERIFIED SESSION IN MEMORY
        // ----------------------------------------------------

        setSession({
          user: currentUser,
          accessToken: loginResponse.accessToken,
          expiresAt,
        })

      } finally {

        if (currentRequest === requestVersion.current) {
          setIsSigningIn(false)
        }
      }
    },
    [],
  )


  // ==========================================================
  // LOGOUT ACTION
  // ==========================================================

  const signOut = useCallback(() => {

    // Invalidate any unfinished login request.
    requestVersion.current += 1

    // Remove the access token and user identity.
    setSession(null)
    setIsSigningIn(false)

  }, [])


  // ==========================================================
  // AUTOMATIC SESSION EXPIRATION
  // ==========================================================

  /**
   * Schedule in-memory session cleanup when the access
   * token reaches its configured expiration time.
   *
   * Clearing the previous timer prevents an older
   * session from logging out a newly authenticated user.
   */
  useEffect(() => {

    if (session === null) {
      return
    }

    const remainingMilliseconds =
      Math.max(0, session.expiresAt - Date.now())

    const timeoutId = window.setTimeout(() => {

      setSession((currentSession) =>
        currentSession === session
          ? null
          : currentSession
      )

    }, remainingMilliseconds)

    return () => {
      window.clearTimeout(timeoutId)
    }

  }, [session])


  // ==========================================================
  // SHARED AUTHENTICATION CONTEXT VALUE
  // ==========================================================

  const contextValue = useMemo<AuthContextValue>(
    () => ({

      currentUser: session?.user ?? null,

      accessToken: session?.accessToken ?? null,

      isAuthenticated: session !== null,

      isSigningIn,

      signIn,

      signOut,

    }),
    [session, isSigningIn, signIn, signOut],
  )


  // ==========================================================
  // PROVIDE AUTHENTICATION TO REACT COMPONENTS
  // ==========================================================

  return (
    <AuthContext.Provider value={contextValue}>
      {children}
    </AuthContext.Provider>
  )
}
