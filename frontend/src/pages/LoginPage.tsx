import { useState, type FormEvent } from 'react'

import {
  ArrowRight,
  Eye,
  EyeOff,
  LockKeyhole,
} from 'lucide-react'

import { useAuth } from '../auth/useAuth'

import './LoginPage.css'

// ============================================================
// LEDGERBASE — LOGIN PAGE
// ============================================================

/**
 * Provides the sign-in interface for LedgerBase.
 *
 * AUTHENTICATION FLOW:
 *
 * 1. User enters email and password.
 * 2. signIn() sends credentials to Spring Boot.
 * 3. AuthProvider verifies the returned JWT through
 *    GET /api/v1/auth/me.
 * 4. AuthProvider stores the verified session in memory.
 * 5. AppGate displays the financial workspace.
 *
 * SECURITY:
 *
 * - Passwords are never logged.
 * - JWTs are never rendered.
 * - Credentials are not persisted in browser storage.
 * - Login errors do not reveal whether an email exists.
 *
 * Backend authorization is responsible for protecting
 * financial APIs independently of this UI.
 */
function LoginPage() {

  // ==========================================================
  // AUTHENTICATION ACTIONS
  // ==========================================================

  const { signIn, isSigningIn } = useAuth()


  // ==========================================================
  // FORM STATE
  // ==========================================================

  const [email, setEmail] = useState('')

  const [password, setPassword] = useState('')

  const [showPassword, setShowPassword] =
    useState(false)

  const [errorMessage, setErrorMessage] =
    useState<string | null>(null)


  // ==========================================================
  // HANDLE LOGIN SUBMISSION
  // ==========================================================

  /**
   * Submit credentials through AuthProvider.
   *
   * No component-level navigation is necessary.
   *
   * When authentication succeeds, the provider updates
   * its session state and AppGate displays the workspace.
   */
  async function handleSubmit(
    event: FormEvent<HTMLFormElement>,
  ): Promise<void> {

    event.preventDefault()

    // Prevent duplicate login submissions.
    if (isSigningIn) {
      return
    }

    // Clear an earlier login error.
    setErrorMessage(null)

    try {

      // ------------------------------------------------------
      // AUTHENTICATE WITH THE BACKEND
      // ------------------------------------------------------

      await signIn({
        email: email.trim(),
        password,
      })

      // AppGate automatically responds to the new
      // authenticated session.

    } catch (error) {

      // ------------------------------------------------------
      // DISPLAY A SAFE ERROR MESSAGE
      // ------------------------------------------------------

      /**
       * An incorrect email and incorrect password
       * receive the same generic message.
       *
       * Other errors may represent a connection,
       * configuration, or server problem.
       *
       * Never display passwords or JWTs in errors.
       */

      if (
        error instanceof Error &&
        error.message === 'Invalid email or password'
      ) {

        setErrorMessage(
          'Invalid email or password. Please try again.',
        )

      } else {

        setErrorMessage(
          'Unable to sign in. Check your connection and try again.',
        )
      }
    }
  }


  // ==========================================================
  // RENDER LOGIN PAGE
  // ==========================================================

  return (
    <div className="lb-login-page">

      <div className="lb-login-layout">

        {/* ==================================================== */}
        {/* BRAND PANEL */}
        {/* ==================================================== */}

        <aside className="lb-login-brand-panel">

          <div className="lb-login-brand">

            <img
              src="/ledgerbase-favicon.png"
              alt=""
              aria-hidden="true"
              className="lb-login-logo"
            />

            <div className="lb-login-brand-name">
              <strong>LedgerBase</strong>
              <span>Financial Workspace</span>
            </div>

          </div>


          {/* ================================================== */}
          {/* BRAND MESSAGING */}
          {/* ================================================== */}

          <div className="lb-login-brand-content">

            <div className="lb-login-eyebrow">
              FINANCIAL CLARITY
            </div>

            <h1>
              A clearer view
              <br />
              of your finances.
            </h1>

            <p>
              Manage your accounts, transactions,
              and financial reporting in one
              organized workspace.
            </p>

            <div className="lb-login-brand-features">

              <span>General Ledger</span>
              <span>Financial Reporting</span>
              <span>Accounting Periods</span>

            </div>

          </div>


          {/* ================================================== */}
          {/* BRAND FOOTER */}
          {/* ================================================== */}

          <div className="lb-login-brand-footer">
            LEDGERBASE / FINANCIAL WORKSPACE
          </div>

        </aside>


        {/* ==================================================== */}
        {/* LOGIN FORM PANEL */}
        {/* ==================================================== */}

        <main className="lb-login-form-panel">

          <div className="lb-login-form-content">

            {/* Mobile branding is displayed when the
                larger left brand panel is hidden. */}

            <div className="lb-login-mobile-brand">
              LedgerBase
            </div>


            {/* ================================================ */}
            {/* FORM HEADING */}
            {/* ================================================ */}

            <div className="lb-login-form-heading">

              <div className="lb-login-icon">
                <LockKeyhole
                  size={22}
                  strokeWidth={1.8}
                  aria-hidden="true"
                />
              </div>

              <h2>Welcome back</h2>

              <p>
                Sign in to continue to your
                financial workspace.
              </p>

            </div>


            {/* ================================================ */}
            {/* AUTHENTICATION FORM */}
            {/* ================================================ */}

            <form
              className="lb-login-form"
              onSubmit={(event) => {
                void handleSubmit(event)
              }}
            >

              {/* ============================================== */}
              {/* EMAIL ADDRESS */}
              {/* ============================================== */}

              <div className="lb-login-field">

                <label htmlFor="lb-login-email">
                  Email address
                </label>

                <input
                  id="lb-login-email"
                  name="email"
                  type="email"
                  placeholder="you@company.com"
                  autoComplete="username"
                  autoCapitalize="none"
                  spellCheck={false}
                  required
                  value={email}
                  disabled={isSigningIn}
                  onChange={(event) => {
                    setEmail(event.target.value)
                    setErrorMessage(null)
                  }}
                />

              </div>


              {/* ============================================== */}
              {/* PASSWORD */}
              {/* ============================================== */}

              <div className="lb-login-field">

                <label htmlFor="lb-login-password">
                  Password
                </label>

                <div className="lb-login-password-wrapper">

                  <input
                    id="lb-login-password"
                    name="password"
                    type={showPassword ? 'text' : 'password'}
                    placeholder="Enter your password"
                    autoComplete="current-password"
                    required
                    value={password}
                    disabled={isSigningIn}
                    onChange={(event) => {
                      setPassword(event.target.value)
                      setErrorMessage(null)
                    }}
                  />

                  <button
                    type="button"
                    className="lb-login-password-toggle"
                    aria-label={
                      showPassword
                        ? 'Hide password'
                        : 'Show password'
                    }
                    aria-pressed={showPassword}
                    disabled={isSigningIn}
                    onClick={() => {
                      setShowPassword(
                        previous => !previous,
                      )
                    }}
                  >

                    {showPassword ? (
                      <EyeOff size={18} aria-hidden="true" />
                    ) : (
                      <Eye size={18} aria-hidden="true" />
                    )}

                  </button>

                </div>

              </div>


              {/* ============================================== */}
              {/* AUTHENTICATION ERROR */}
              {/* ============================================== */}

              {errorMessage && (

                <p
                  className="lb-login-error"
                  role="alert"
                >
                  {errorMessage}
                </p>

              )}


              {/* ============================================== */}
              {/* SUBMIT BUTTON */}
              {/* ============================================== */}

              <button
                type="submit"
                className="lb-login-submit"
                disabled={isSigningIn}
              >

                <span>
                  {isSigningIn
                    ? 'Signing in...'
                    : 'Sign in'}
                </span>

                {!isSigningIn && (
                  <ArrowRight
                    size={18}
                    aria-hidden="true"
                  />
                )}

              </button>

            </form>


            {/* ================================================ */}
            {/* SESSION INFORMATION */}
            {/* ================================================ */}

            <div className="lb-login-session-note">

              <LockKeyhole
                size={15}
                aria-hidden="true"
              />

              <p>
                Development sessions use a
                15-minute access token and are
                cleared when the page is refreshed.
              </p>

            </div>

          </div>

        </main>

      </div>

    </div>
  )
}

// ============================================================
// EXPORT LOGIN PAGE
// ============================================================

export default LoginPage
