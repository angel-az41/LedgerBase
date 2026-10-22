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
 * Manages authenticated browser sessions.
 *
 * AUTHENTICATION:
 *
 * - Login with Spring Boot.
 * - Verify user identity through /auth/me.
 * - Keep access JWTs in JavaScript memory.
 *
 * SESSION RESTORATION:
 *
 * - Restore a session after page reload.
 * - Use the HttpOnly refresh cookie.
 *
 * AUTOMATIC RENEWAL:
 *
 * - Renew shortly before access-token expiration.
 * - Require recent user interaction.
 * - Avoid background renewal in hidden tabs.
 * - Verify the refreshed user identity.
 *
 * LOGOUT:
 *
 * - Revoke the PostgreSQL session.
 * - Clear the HttpOnly refresh cookie.
 * - Remove frontend authentication state.
 *
 * SECURITY:
 *
 * - No JWTs in localStorage or sessionStorage.
 * - No refresh tokens accessible to JavaScript.
 * - Backend session validation remains authoritative.
 */


// ============================================================
// AUTHENTICATION TIMING
// ============================================================

/**
 * Renew access tokens one minute before expiration.
 *
 * The backend currently issues access JWTs with
 * a standard lifetime of 15 minutes.
 */
const ACCESS_TOKEN_RENEWAL_MARGIN_MS =
  60 * 1000

/**
 * An account is considered recently active when
 * the browser has received user interaction within
 * the previous 15 minutes.
 *
 * This is a frontend scheduling policy, not the
 * authoritative server-side inactivity timeout.
 */
const RECENT_ACTIVITY_WINDOW_MS =
  15 * 60 * 1000

/**
 * Do not repeatedly renew access tokens that already
 * have a very short remaining lifetime.
 *
 * This avoids rapid refresh loops near the backend's
 * eight-hour absolute session expiration.
 */
const MINIMUM_LIFETIME_FOR_RENEWAL_MS =
  2 * 60 * 1000

/**
 * Prevent repeated refresh attempts on every click
 * when a previous request has just failed.
 */
const REFRESH_RETRY_COOLDOWN_MS =
  15 * 1000


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

  /**
   * The current user and access JWT exist only
   * in application memory.
   */
  const [session, setSession] =
    useState<AuthSession | null>(null)

  const [isSigningIn, setIsSigningIn] =
    useState(false)

  /**
   * Hide the application until the initial
   * refresh-cookie restoration has completed.
   */
  const [isRestoringSession, setIsRestoringSession] =
    useState(true)


  // ==========================================================
  // ASYNCHRONOUS REQUEST COORDINATION
  // ==========================================================

  /**
   * Prevent older login or refresh operations
   * from replacing newer authentication decisions.
   */
  const requestVersion =
    useRef(0)

  /**
   * Prevent repeated logout requests.
   */
  const signOutInProgress =
    useRef(false)


  // ==========================================================
  // USER ACTIVITY TRACKING
  // ==========================================================

  /**
   * Record recent user interaction without
   * triggering unnecessary React renders.
   *
   * Activity is updated by pointer, keyboard,
   * scrolling, and browser-focus events.
   *
   * This information only determines when the
   * frontend should attempt automatic renewal.
   *
   * It does not replace database session validation.
   */
  const lastUserActivityAt =
    useRef(Date.now())


  // ==========================================================
  // RESTORE SESSION ON APPLICATION STARTUP
  // ==========================================================

  useEffect(() => {

    let active = true

    const currentRequest =
      ++requestVersion.current


    // --------------------------------------------------------
    // RESTORE FROM HTTPONLY COOKIE
    // --------------------------------------------------------

    const restoreSession = async (): Promise<void> => {

      try {

        // ----------------------------------------------------
        // EXCHANGE REFRESH COOKIE FOR ACCESS JWT
        // ----------------------------------------------------

        /**
         * refreshSession() already implements
         * single-flight request deduplication
         * within this browser tab.
         */
        const refreshed =
          await refreshSession()


        // ----------------------------------------------------
        // RECORD ACCESS TOKEN EXPIRATION
        // ----------------------------------------------------

        const expiresAt =
          Date.now() +
          refreshed.expiresInSeconds * 1000


        // ----------------------------------------------------
        // VERIFY AUTHENTICATED USER
        // ----------------------------------------------------

        const currentUser =
          await getCurrentUser(
            refreshed.accessToken,
          )


        // ----------------------------------------------------
        // IGNORE OUTDATED RESTORATION
        // ----------------------------------------------------

        if (
          !active ||
          currentRequest !== requestVersion.current
        ) {
          return
        }


        // ----------------------------------------------------
        // REJECT EXPIRED ACCESS JWT
        // ----------------------------------------------------

        if (Date.now() >= expiresAt) {
          return
        }


        // ----------------------------------------------------
        // REGISTER RESTORED TOKEN
        // ----------------------------------------------------

        setApiAccessToken(
          refreshed.accessToken,
        )


        // ----------------------------------------------------
        // INITIALIZE USER ACTIVITY
        // ----------------------------------------------------

        lastUserActivityAt.current =
          Date.now()


        // ----------------------------------------------------
        // PUBLISH RESTORED SESSION
        // ----------------------------------------------------

        setSession({
          user: currentUser,
          accessToken: refreshed.accessToken,
          expiresAt,
        })

      } catch {

        // ----------------------------------------------------
        // RESTORATION MAY FAIL FOR NEW VISITORS
        // ----------------------------------------------------

        /**
         * No refresh cookie, an expired session,
         * or a temporary network failure may
         * prevent restoration.
         *
         * Never invent an authenticated identity.
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
        // COMPLETE APPLICATION INITIALIZATION
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
    // CLEAN UP OBSOLETE EFFECT INSTANCE
    // --------------------------------------------------------

    /**
     * React Strict Mode may repeat effect setup
     * during development.
     *
     * The shared refresh promise prevents two
     * simultaneous refresh-token rotations.
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
      // CLEAR PREVIOUS FRONTEND AUTHENTICATION
      // ------------------------------------------------------

      setApiAccessToken(null)

      setSession(null)

      setIsSigningIn(true)


      try {

        // ----------------------------------------------------
        // AUTHENTICATE WITH SPRING BOOT
        // ----------------------------------------------------

        const loginResponse =
          await login(credentials)


        // ----------------------------------------------------
        // VALIDATE LOGIN RESPONSE
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
        // CALCULATE ACCESS TOKEN EXPIRATION
        // ----------------------------------------------------

        const expiresAt =
          Date.now() +
          loginResponse.expiresInSeconds * 1000


        // ----------------------------------------------------
        // VERIFY IDENTITY USING SIGNED JWT
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
        // IGNORE OBSOLETE LOGIN RESPONSE
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
        // REGISTER ACCESS TOKEN
        // ----------------------------------------------------

        setApiAccessToken(
          loginResponse.accessToken,
        )


        // ----------------------------------------------------
        // RESET LAST USER ACTIVITY
        // ----------------------------------------------------

        lastUserActivityAt.current =
          Date.now()


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
   * Revoke the PostgreSQL-backed session.
   *
   * Only report the user as signed out once
   * the backend confirms successful logout.
   */
  const signOut = useCallback((): void => {

    // --------------------------------------------------------
    // IGNORE REPEATED LOGOUT CLICKS
    // --------------------------------------------------------

    if (signOutInProgress.current) {
      return
    }

    signOutInProgress.current =
      true

    const currentRequest =
      ++requestVersion.current


    // --------------------------------------------------------
    // REVOKE BACKEND SESSION
    // --------------------------------------------------------

    void logout()

      .then(() => {

        // ----------------------------------------------------
        // IGNORE OBSOLETE LOGOUT COMPLETION
        // ----------------------------------------------------

        if (
          currentRequest !== requestVersion.current
        ) {
          return
        }


        // ----------------------------------------------------
        // REMOVE ACCESS TOKEN
        // ----------------------------------------------------

        setApiAccessToken(null)


        // ----------------------------------------------------
        // REMOVE AUTHENTICATED REACT STATE
        // ----------------------------------------------------

        setSession(null)

        setIsSigningIn(false)
      })

      .catch(() => {

        // ----------------------------------------------------
        // REPORT BACKEND LOGOUT FAILURE
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

        signOutInProgress.current =
          false
      })

  }, [])


  // ==========================================================
  // AUTOMATIC ACCESS TOKEN RENEWAL
  // ==========================================================

  /**
   * Schedule a refresh before the current JWT expires.
   *
   * The refresh request runs only when:
   *
   * - The session still belongs to this effect.
   * - The tab is visible.
   * - There has been recent user interaction.
   * - Logout is not in progress.
   *
   * The refreshed identity must match the existing
   * authenticated user's ID.
   *
   * A failed refresh does not immediately discard a
   * still-valid access JWT. The expiration handler
   * remains responsible for clearing expired tokens.
   *
   * Refreshing an actively used page also updates the
   * backend's last_activity_at session timestamp.
   */
  useEffect(() => {

    if (session === null) {
      return
    }


    // --------------------------------------------------------
    // CHECK REMAINING JWT LIFETIME
    // --------------------------------------------------------

    const remainingLifetimeMs =
      session.expiresAt - Date.now()


    // --------------------------------------------------------
    // AVOID SHORT-TOKEN RENEWAL LOOPS
    // --------------------------------------------------------

    /**
     * Near the absolute session deadline, the backend
     * may issue a token with less than 15 minutes
     * remaining.
     *
     * Avoid immediately rotating refresh tokens
     * over and over in this situation.
     */
    if (
      remainingLifetimeMs <=
      MINIMUM_LIFETIME_FOR_RENEWAL_MS
    ) {
      return
    }


    // --------------------------------------------------------
    // TRACK THIS PARTICULAR SESSION EFFECT
    // --------------------------------------------------------

    let cancelled = false

    let refreshInProgress = false

    let lastRefreshAttemptAt = 0

    const currentRequestVersion =
      requestVersion.current


    // --------------------------------------------------------
    // RENEW THE CURRENT ACCESS TOKEN
    // --------------------------------------------------------

    const renewAccessToken = async (): Promise<void> => {

      // ------------------------------------------------------
      // SKIP CANCELLED OR CONCURRENT OPERATIONS
      // ------------------------------------------------------

      if (
        cancelled ||
        refreshInProgress ||
        signOutInProgress.current ||
        currentRequestVersion !== requestVersion.current
      ) {
        return
      }


      // ------------------------------------------------------
      // REQUIRE VISIBLE BROWSER TAB
      // ------------------------------------------------------

      if (
        document.visibilityState !== 'visible'
      ) {
        return
      }


      // ------------------------------------------------------
      // REQUIRE RECENT USER ACTIVITY
      // ------------------------------------------------------

      const inactivityMs =
        Date.now() - lastUserActivityAt.current

      if (
        inactivityMs >
        RECENT_ACTIVITY_WINDOW_MS
      ) {
        return
      }


      // ------------------------------------------------------
      // AVOID RAPID RETRIES
      // ------------------------------------------------------

      const now = Date.now()

      if (
        now - lastRefreshAttemptAt <
        REFRESH_RETRY_COOLDOWN_MS
      ) {
        return
      }

      lastRefreshAttemptAt = now

      refreshInProgress = true


      try {

        // ----------------------------------------------------
        // REQUEST NEW ACCESS JWT
        // ----------------------------------------------------

        /**
         * Capture the time before the HTTP request.
         *
         * Using this earlier timestamp provides
         * a conservative local expiration deadline.
         */
        const refreshStartedAt =
          Date.now()

        const refreshed =
          await refreshSession()


        // ----------------------------------------------------
        // CALCULATE REPLACEMENT JWT EXPIRATION
        // ----------------------------------------------------

        const nextExpiresAt =
          refreshStartedAt +
          refreshed.expiresInSeconds * 1000


        // ----------------------------------------------------
        // VERIFY IDENTITY WITH THE REPLACEMENT JWT
        // ----------------------------------------------------

        const refreshedUser =
          await getCurrentUser(
            refreshed.accessToken,
          )


        // ----------------------------------------------------
        // REQUIRE SAME AUTHENTICATED USER
        // ----------------------------------------------------

        if (
          refreshedUser.userId !==
          session.user.userId
        ) {
          return
        }


        // ----------------------------------------------------
        // IGNORE OUTDATED ASYNCHRONOUS RESULTS
        // ----------------------------------------------------

        if (
          cancelled ||
          signOutInProgress.current ||
          currentRequestVersion !== requestVersion.current
        ) {
          return
        }


        // ----------------------------------------------------
        // REJECT ALREADY EXPIRED REPLACEMENT TOKEN
        // ----------------------------------------------------

        if (Date.now() >= nextExpiresAt) {
          return
        }


        // ----------------------------------------------------
        // REGISTER RENEWED BEARER TOKEN
        // ----------------------------------------------------

        setApiAccessToken(
          refreshed.accessToken,
        )


        // ----------------------------------------------------
        // PUBLISH UPDATED AUTHENTICATION SESSION
        // ----------------------------------------------------

        /**
         * Updating session changes the dependency
         * of this effect.
         *
         * React cancels the old timers and creates
         * the next renewal schedule automatically.
         */
        setSession({
          user: refreshedUser,
          accessToken: refreshed.accessToken,
          expiresAt: nextExpiresAt,
        })

      } catch {

        // ----------------------------------------------------
        // PRESERVE CURRENT JWT UNTIL ITS EXPIRATION
        // ----------------------------------------------------

        /**
         * A temporary network error should not
         * instantly remove an unexpired JWT.
         *
         * A later user interaction may retry renewal
         * after the cooldown period.
         *
         * If no renewal succeeds, the separate
         * expiration timer clears the session.
         */

      } finally {

        refreshInProgress = false
      }
    }


    // --------------------------------------------------------
    // CALCULATE RENEWAL SCHEDULE
    // --------------------------------------------------------

    const renewalDelayMs =
      Math.max(
        0,
        session.expiresAt -
          Date.now() -
          ACCESS_TOKEN_RENEWAL_MARGIN_MS,
      )


    // --------------------------------------------------------
    // SCHEDULE THE RENEWAL REQUEST
    // --------------------------------------------------------

    const renewalTimeoutId =
      window.setTimeout(() => {

        void renewAccessToken()

      }, renewalDelayMs)


    // --------------------------------------------------------
    // TRACK INTERACTION AND LATE ACTIVITY
    // --------------------------------------------------------

    /**
     * A user may return after a scheduled renewal
     * was skipped due to inactivity.
     *
     * When interaction resumes near expiration,
     * try renewal again instead of permanently
     * abandoning this access token.
     */
    const handleUserActivity = (): void => {

      lastUserActivityAt.current =
        Date.now()

      if (
        Date.now() >=
        session.expiresAt -
          ACCESS_TOKEN_RENEWAL_MARGIN_MS
      ) {

        void renewAccessToken()
      }
    }


    // --------------------------------------------------------
    // HANDLE RETURNING TO A VISIBLE TAB
    // --------------------------------------------------------

    const handleVisibilityChange = (): void => {

      if (
        document.visibilityState === 'visible'
      ) {

        handleUserActivity()
      }
    }


    // --------------------------------------------------------
    // REGISTER BROWSER ACTIVITY LISTENERS
    // --------------------------------------------------------

    window.addEventListener(
      'pointerdown',
      handleUserActivity,
    )

    window.addEventListener(
      'keydown',
      handleUserActivity,
    )

    window.addEventListener(
      'wheel',
      handleUserActivity,
      { passive: true },
    )

    window.addEventListener(
      'focus',
      handleUserActivity,
    )

    document.addEventListener(
      'visibilitychange',
      handleVisibilityChange,
    )


    // --------------------------------------------------------
    // CANCEL OLD SCHEDULE AND EVENT LISTENERS
    // --------------------------------------------------------

    return () => {

      cancelled = true

      window.clearTimeout(
        renewalTimeoutId,
      )

      window.removeEventListener(
        'pointerdown',
        handleUserActivity,
      )

      window.removeEventListener(
        'keydown',
        handleUserActivity,
      )

      window.removeEventListener(
        'wheel',
        handleUserActivity,
      )

      window.removeEventListener(
        'focus',
        handleUserActivity,
      )

      document.removeEventListener(
        'visibilitychange',
        handleVisibilityChange,
      )
    }

  }, [session])


  // ==========================================================
  // AUTOMATIC ACCESS TOKEN EXPIRATION
  // ==========================================================

  /**
   * Remove authentication after the JWT expires
   * unless renewal has successfully replaced
   * the current session.
   *
   * A fresh JWT causes this effect to restart
   * with the new expiration time.
   */
  useEffect(() => {

    if (session === null) {
      return
    }


    // --------------------------------------------------------
    // CALCULATE TIME REMAINING
    // --------------------------------------------------------

    const remainingMilliseconds =
      Math.max(
        0,
        session.expiresAt - Date.now(),
      )


    // --------------------------------------------------------
    // SCHEDULE EXPIRATION CLEANUP
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
    // CANCEL OBSOLETE EXPIRATION TIMER
    // --------------------------------------------------------

    return () => {

      window.clearTimeout(timeoutId)
    }

  }, [session])


  // ==========================================================
  // CLEAN UP TOKEN ON PROVIDER UNMOUNT
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

      currentUser:
        session?.user ?? null,

      accessToken:
        session?.accessToken ?? null,

      isAuthenticated:
        session !== null,

      isSigningIn,

      signIn,

      signOut,

    }),
    [
      session,
      isSigningIn,
      signIn,
      signOut,
    ],
  )


  // ==========================================================
  // SESSION RESTORATION LOADING SCREEN
  // ==========================================================

  /**
   * Avoid showing the login screen while the
   * browser checks for an existing refresh cookie.
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
