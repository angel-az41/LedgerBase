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
  logout,
  refreshSession,
  getCurrentUser,
  type LoginRequest,
  type CurrentUserResponse,
} from '../api/auth'

import {
  setApiAccessToken,
  clearApiAccessTokenIfCurrent,
} from '../api/client'

import {
  AuthContext,
  type AuthContextValue,
} from './AuthContext'

// ============================================================
// LEDGERBASE — AUTHENTICATION PROVIDER
// ============================================================

/**
 * Manages authenticated React application state.
 *
 * LOGIN:
 *
 * - Authenticate with Spring Boot.
 * - Verify identity through /auth/me.
 * - Store the access JWT in memory.
 *
 * SESSION RESTORATION:
 *
 * - On page reload, call /auth/refresh.
 * - Use the HttpOnly cookie to restore authentication.
 * - Verify the refreshed JWT through /auth/me.
 * - Publish the restored user to React.
 *
 * LOGOUT:
 *
 * - Revoke the PostgreSQL authentication session.
 * - Clear the HttpOnly refresh cookie.
 * - Remove in-memory authentication after success.
 *
 * SECURITY:
 *
 * - Never persist access JWTs in browser storage.
 * - Never read or store refresh tokens in JavaScript.
 * - Backend authorization remains authoritative.
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
// AUTHENTICATION PROVIDER
// ============================================================

export function AuthProvider({
  children,
}: AuthProviderProps) {

  // ==========================================================
  // AUTHENTICATION STATE
  // ==========================================================

  const [session, setSession] =
    useState<AuthSession | null>(null)

  const [isSigningIn, setIsSigningIn] =
    useState(false)

  /**
   * Prevent the app from displaying the login screen
   * until its initial refresh-cookie check finishes.
   */
  const [isRestoringSession, setIsRestoringSession] =
    useState(true)


  // ==========================================================
  // ASYNCHRONOUS REQUEST COORDINATION
  // ==========================================================

  /**
   * Prevent outdated authentication requests from
   * overwriting more recent login or logout decisions.
   */
  const requestVersion = useRef(0)

  /**
   * Avoid submitting multiple logout requests
   * in response to repeated button clicks.
   */
  const signOutInProgress = useRef(false)


  // ==========================================================
  // RESTORE SESSION ON APPLICATION STARTUP
  // ==========================================================

  useEffect(() => {

    let active = true

    const currentRequest =
      ++requestVersion.current


    // --------------------------------------------------------
    // RESTORE AUTHENTICATION FROM REFRESH COOKIE
    // --------------------------------------------------------

    const restoreSession = async (): Promise<void> => {

      try {

        // ----------------------------------------------------
        // EXCHANGE COOKIE FOR A NEW ACCESS JWT
        // ----------------------------------------------------

        /**
         * The refresh token is automatically included
         * by the browser, not read by JavaScript.
         *
         * refreshSession() deduplicates simultaneous
         * requests in this browser tab.
         */
        const refreshed =
          await refreshSession()


        // ----------------------------------------------------
        // RECORD THE NEW ACCESS TOKEN DEADLINE
        // ----------------------------------------------------

        const expiresAt =
          Date.now() +
          refreshed.expiresInSeconds * 1000


        // ----------------------------------------------------
        // VERIFY USER IDENTITY
        // ----------------------------------------------------

        const currentUser =
          await getCurrentUser(
            refreshed.accessToken,
          )


        // ----------------------------------------------------
        // IGNORE OBSOLETE RESTORATION
        // ----------------------------------------------------

        if (
          !active ||
          currentRequest !== requestVersion.current
        ) {
          return
        }


        // ----------------------------------------------------
        // REJECT ALREADY EXPIRED ACCESS JWT
        // ----------------------------------------------------

        if (Date.now() >= expiresAt) {
          return
        }


        // ----------------------------------------------------
        // REGISTER SHARED API ACCESS TOKEN
        // ----------------------------------------------------

        setApiAccessToken(
          refreshed.accessToken,
        )


        // ----------------------------------------------------
        // RESTORE AUTHENTICATED REACT STATE
        // ----------------------------------------------------

        setSession({
          user: currentUser,
          accessToken: refreshed.accessToken,
          expiresAt,
        })

      } catch {

        // ----------------------------------------------------
        // HANDLE UNAVAILABLE REFRESH SESSION
        // ----------------------------------------------------

        /**
         * A new visitor normally has no refresh cookie,
         * so /auth/refresh returns HTTP 401.
         *
         * Invalid or expired sessions also remain
         * unauthenticated.
         *
         * Do not invent or restore an unverified user.
         */
        if (
          active &&
          currentRequest === requestVersion.current
        ) {

          setApiAccessToken(null)
          setSession(null)
        }

      } finally {

        // ----------------------------------------------------
        // FINISH APPLICATION INITIALIZATION
        // ----------------------------------------------------

        if (
          active &&
          currentRequest === requestVersion.current
        ) {
          setIsRestoringSession(false)
        }
      }
    }

    void restoreSession()


    // --------------------------------------------------------
    // IGNORE RESULTS AFTER EFFECT CLEANUP
    // --------------------------------------------------------

    /**
     * React development Strict Mode may perform an
     * additional effect setup/cleanup cycle.
     *
     * The single-flight refresh helper prevents
     * duplicate refresh-token consumption.
     *
     * This flag prevents stale effect instances
     * from publishing authentication state.
     */
    return () => {
      active = false
    }

  }, [])


  // ==========================================================
  // LOGIN ACTION
  // ==========================================================

  const signIn = useCallback(
    async (credentials: LoginRequest): Promise<void> => {

      const currentRequest =
        ++requestVersion.current


      // ------------------------------------------------------
      // CLEAR PREVIOUS IN-MEMORY AUTHENTICATION
      // ------------------------------------------------------

      setApiAccessToken(null)
      setSession(null)
      setIsSigningIn(true)


      try {

        // ----------------------------------------------------
        // AUTHENTICATE AND CREATE BACKEND SESSION
        // ----------------------------------------------------

        const loginResponse =
          await login(credentials)


        // ----------------------------------------------------
        // VALIDATE ACCESS TOKEN RESPONSE
        // ----------------------------------------------------

        if (
          loginResponse.tokenType !== 'Bearer' ||
          !loginResponse.accessToken ||
          !Number.isFinite(
            loginResponse.expiresInSeconds,
          ) ||
          loginResponse.expiresInSeconds <= 0
        ) {

          throw new Error(
            'The authentication server returned an invalid session.',
          )
        }


        // ----------------------------------------------------
        // RECORD ACCESS TOKEN EXPIRATION
        // ----------------------------------------------------

        const expiresAt =
          Date.now() +
          loginResponse.expiresInSeconds * 1000


        // ----------------------------------------------------
        // VERIFY SIGNED USER IDENTITY
        // ----------------------------------------------------

        const currentUser =
          await getCurrentUser(
            loginResponse.accessToken,
          )

        if (
          currentUser.userId !==
          loginResponse.userId
        ) {

          throw new Error(
            'The authenticated user could not be verified.',
          )
        }


        // ----------------------------------------------------
        // IGNORE OBSOLETE LOGIN REQUESTS
        // ----------------------------------------------------

        if (
          currentRequest !== requestVersion.current
        ) {
          return
        }


        // ----------------------------------------------------
        // REJECT EXPIRED ACCESS TOKEN
        // ----------------------------------------------------

        if (Date.now() >= expiresAt) {

          throw new Error(
            'The authentication session has expired.',
          )
        }


        // ----------------------------------------------------
        // REGISTER JWT WITH SHARED API CLIENT
        // ----------------------------------------------------

        setApiAccessToken(
          loginResponse.accessToken,
        )


        // ----------------------------------------------------
        // PUBLISH AUTHENTICATED SESSION
        // ----------------------------------------------------

        setSession({
          user: currentUser,
          accessToken: loginResponse.accessToken,
          expiresAt,
        })

      } finally {

        if (
          currentRequest === requestVersion.current
        ) {
          setIsSigningIn(false)
        }
      }
    },
    [],
  )


  // ==========================================================
  // LOGOUT ACTION
  // ==========================================================

  /**
   * Revoke the backend session before reporting
   * the user as signed out.
   *
   * If the server cannot confirm revocation, keep
   * the existing authenticated state and show an
   * error rather than pretending logout succeeded.
   *
   * AuthContext retains its existing void callback
   * signature for compatibility with current UI.
   */
  const signOut = useCallback((): void => {

    // --------------------------------------------------------
    // IGNORE REPEATED LOGOUT CLICKS
    // --------------------------------------------------------

    if (signOutInProgress.current) {
      return
    }

    signOutInProgress.current = true

    const currentRequest =
      ++requestVersion.current


    // --------------------------------------------------------
    // REVOKE SESSION ON SPRING BOOT
    // --------------------------------------------------------

    void logout()

      .then(() => {

        if (
          currentRequest !== requestVersion.current
        ) {
          return
        }


        // ----------------------------------------------------
        // CLEAR SHARED BEARER TOKEN
        // ----------------------------------------------------

        setApiAccessToken(null)


        // ----------------------------------------------------
        // CLEAR REACT SESSION
        // ----------------------------------------------------

        setSession(null)
        setIsSigningIn(false)
      })

      .catch(() => {

        // ----------------------------------------------------
        // REPORT LOGOUT FAILURE
        // ----------------------------------------------------

        if (
          currentRequest === requestVersion.current
        ) {

          window.alert(
            'Unable to sign out. Please check your connection and try again.',
          )
        }
      })

      .finally(() => {

        signOutInProgress.current = false
      })

  }, [])


  // ==========================================================
  // ACCESS TOKEN EXPIRATION
  // ==========================================================

  /**
   * Until automatic in-page refresh is implemented,
   * remove the expired access JWT from memory.
   *
   * A full page reload can still restore the session
   * using the refresh cookie, if it remains valid.
   */
  useEffect(() => {

    if (session === null) {
      return
    }


    // --------------------------------------------------------
    // CALCULATE REMAINING ACCESS TOKEN LIFETIME
    // --------------------------------------------------------

    const remainingMilliseconds =
      Math.max(
        0,
        session.expiresAt - Date.now(),
      )


    // --------------------------------------------------------
    // SCHEDULE SESSION CLEANUP
    // --------------------------------------------------------

    const timeoutId =
      window.setTimeout(() => {

        clearApiAccessTokenIfCurrent(
          session.accessToken,
        )

        setSession((currentSession) =>
          currentSession === session
            ? null
            : currentSession
        )

      }, remainingMilliseconds)


    // --------------------------------------------------------
    // CANCEL OBSOLETE TIMEOUT
    // --------------------------------------------------------

    return () => {
      window.clearTimeout(timeoutId)
    }

  }, [session])


  // ==========================================================
  // CLEAN UP SHARED TOKEN ON PROVIDER UNMOUNT
  // ==========================================================

  useEffect(() => {

    return () => {
      setApiAccessToken(null)
    }

  }, [])


  // ==========================================================
  // AUTHENTICATION CONTEXT VALUE
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
  // WAIT FOR INITIAL SESSION RESTORATION
  // ==========================================================

  /**
   * Do not display the login screen until the
   * initial cookie-backed restoration finishes.
   *
   * This avoids a brief flash of the login form
   * before the dashboard is restored.
   */
  if (isRestoringSession) {

    return (
      <div
        role="status"
        aria-live="polite"
        style={{
          minHeight: '100vh',
          display: 'grid',
          placeItems: 'center',
          background: '#f6f8fb',
          color: '#23364a',
          fontFamily: 'system-ui, sans-serif',
        }}
      >
        Restoring your session…
      </div>
    )
  }


  // ==========================================================
  // PROVIDE AUTHENTICATION TO REACT COMPONENTS
  // ==========================================================

  return (
    <AuthContext.Provider value={contextValue}>
      {children}
    </AuthContext.Provider>
  )
}
