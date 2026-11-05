import { useState, type FormEvent } from 'react'

import {
  ArrowRight,
  Eye,
  EyeOff,
  UserRoundPlus,
  LockKeyhole,
} from 'lucide-react'

import { registerAccount } from '../api/auth'

import './LoginPage.css'
import './RegisterPage.css'

// ============================================================
// LEDGERBASE — USER REGISTRATION PAGE
// ============================================================

/**
 * Allows a new user to create a LedgerBase account.
 *
 * REGISTRATION FLOW:
 *
 * - Collect full name, email, and password.
 * - Confirm the password locally.
 * - Validate inputs before submission.
 * - Submit credentials to the registration API.
 * - Return the user to sign-in after success.
 *
 * SECURITY:
 *
 * - Passwords are never logged or persisted locally.
 * - Confirm password is never sent to the backend.
 * - Registration does not issue JWT credentials.
 * - Backend validation remains authoritative.
 *
 * The visual layout reuses the existing LoginPage
 * branding and form components.
 */

// ============================================================
// COMPONENT PROPS
// ============================================================

interface RegisterPageProps {

  /**
   * Return to the existing login screen without
   * submitting a registration request.
   */
  onBackToSignIn: () => void

  /**
   * Notify AppGate that registration succeeded.
   *
   * The email can be prefilled on the login screen,
   * but the password must not be carried forward.
   */
  onRegistrationSuccess: (email: string) => void
}


// ============================================================
// REGISTRATION VALIDATION CONSTANTS
// ============================================================

const MIN_PASSWORD_LENGTH = 12

const MAX_PASSWORD_LENGTH = 72

const MAX_PASSWORD_BYTES = 72


// ============================================================
// REGISTRATION PAGE COMPONENT
// ============================================================

function RegisterPage({
  onBackToSignIn,
  onRegistrationSuccess,
}: RegisterPageProps) {

  // ==========================================================
  // FORM STATE
  // ==========================================================

  const [fullName, setFullName] = useState('')

  const [email, setEmail] = useState('')

  const [password, setPassword] = useState('')

  const [confirmPassword, setConfirmPassword] =
    useState('')


  // ==========================================================
  // PASSWORD VISIBILITY
  // ==========================================================

  const [showPassword, setShowPassword] =
    useState(false)

  const [showConfirmPassword, setShowConfirmPassword] =
    useState(false)


  // ==========================================================
  // SUBMISSION STATE
  // ==========================================================

  const [isSubmitting, setIsSubmitting] =
    useState(false)

  const [errorMessage, setErrorMessage] =
    useState<string | null>(null)


  // ==========================================================
  // HANDLE REGISTRATION SUBMISSION
  // ==========================================================

  async function handleSubmit(
    event: FormEvent<HTMLFormElement>,
  ): Promise<void> {

    event.preventDefault()

    // Prevent repeated requests while submitting.
    if (isSubmitting) {
      return
    }

    setErrorMessage(null)


    // --------------------------------------------------------
    // NORMALIZE USER INFORMATION
    // --------------------------------------------------------

    const normalizedFullName = fullName.trim()

    const normalizedEmail =
      email.trim().toLowerCase()


    // --------------------------------------------------------
    // VALIDATE FULL NAME
    // --------------------------------------------------------

    if (
      normalizedFullName.length < 2 ||
      normalizedFullName.length > 150
    ) {
      setErrorMessage(
        'Full name must contain 2 to 150 characters.',
      )
      return
    }


    // --------------------------------------------------------
    // VALIDATE PASSWORD LENGTH
    // --------------------------------------------------------

    /**
     * These character limits match RegisterRequest
     * validation on the Spring Boot backend.
     */
    if (
      password.length < MIN_PASSWORD_LENGTH ||
      password.length > MAX_PASSWORD_LENGTH
    ) {
      setErrorMessage(
        'Password must contain 12 to 72 characters.',
      )
      return
    }


    // --------------------------------------------------------
    // VALIDATE PASSWORD BYTE LENGTH
    // --------------------------------------------------------

    /**
     * BCrypt accepts a maximum of 72 password bytes.
     *
     * Some Unicode characters occupy multiple UTF-8
     * bytes, so character count alone is insufficient.
     */
    const passwordByteLength =
      new TextEncoder().encode(password).length

    if (passwordByteLength > MAX_PASSWORD_BYTES) {
      setErrorMessage(
        'Password must not exceed 72 UTF-8 bytes.',
      )
      return
    }


    // --------------------------------------------------------
    // VALIDATE PASSWORD CONFIRMATION
    // --------------------------------------------------------

    if (password !== confirmPassword) {
      setErrorMessage(
        'Passwords do not match. Please try again.',
      )
      return
    }


    // --------------------------------------------------------
    // SUBMIT REGISTRATION REQUEST
    // --------------------------------------------------------

    setIsSubmitting(true)

    try {

      const response = await registerAccount({
        fullName: normalizedFullName,
        email: normalizedEmail,
        password,
      })


      // ------------------------------------------------------
      // HANDLE SUCCESSFUL REGISTRATION
      // ------------------------------------------------------

      /**
       * Registration does not authenticate the user.
       *
       * AppGate will display the sign-in screen
       * and optionally prefill the registered email.
       */
      onRegistrationSuccess(response.email)

    } catch (error) {

      // ------------------------------------------------------
      // HANDLE REGISTRATION ERRORS
      // ------------------------------------------------------

      /**
       * The shared API client propagates backend
       * errors through JavaScript Error objects.
       *
       * Avoid displaying raw server details to users.
       */
      const message =
        error instanceof Error ? error.message : ''

      if (
        message.includes('(409)') ||
        message.includes('Unable to register with this email address')
      ) {

        setErrorMessage(
          'Unable to create an account with this email. ' +
          'Try another email or sign in.',
        )

      } else {

        setErrorMessage(
          'Unable to create your account. ' +
          'Please check your information and try again.',
        )

      }

    } finally {

      setIsSubmitting(false)

    }
  }


  // ==========================================================
  // RENDER REGISTRATION PAGE
  // ==========================================================

  return (
    <div className="lb-login-page lb-register-page">

      <div className="lb-login-layout">

        {/* ================================================== */}
        {/* BRAND PANEL */}
        {/* ================================================== */}

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


          {/* ================================================ */}
          {/* BRAND MESSAGING */}
          {/* ================================================ */}

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


          {/* ================================================ */}
          {/* BRAND FOOTER */}
          {/* ================================================ */}

          <div className="lb-login-brand-footer">
            LEDGERBASE / FINANCIAL WORKSPACE
          </div>

        </aside>


        {/* ================================================== */}
        {/* REGISTRATION FORM PANEL */}
        {/* ================================================== */}

        <main className="lb-login-form-panel">

          <div
            className={
              'lb-login-form-content ' +
              'lb-register-form-content'
            }
          >

            <div className="lb-login-mobile-brand">
              LedgerBase
            </div>


            {/* ============================================== */}
            {/* FORM HEADING */}
            {/* ============================================== */}

            <div className="lb-login-form-heading">

              <div className="lb-login-icon">
                <UserRoundPlus
                  size={22}
                  strokeWidth={1.8}
                  aria-hidden="true"
                />
              </div>

              <h2>Create your account</h2>

              <p>
                Get started with your LedgerBase
                financial workspace.
              </p>

            </div>


            {/* ============================================== */}
            {/* REGISTRATION FORM */}
            {/* ============================================== */}

            <form
              className="lb-login-form"
              onSubmit={(event) => {
                void handleSubmit(event)
              }}
            >

              {/* ============================================ */}
              {/* FULL NAME */}
              {/* ============================================ */}

              <div className="lb-login-field">

                <label htmlFor="lb-register-name">
                  Full name
                </label>

                <input
                  id="lb-register-name"
                  name="name"
                  type="text"
                  placeholder="Your full name"
                  autoComplete="name"
                  maxLength={150}
                  required
                  value={fullName}
                  disabled={isSubmitting}
                  onChange={(event) => {
                    setFullName(event.target.value)
                    setErrorMessage(null)
                  }}
                />

              </div>


              {/* ============================================ */}
              {/* EMAIL ADDRESS */}
              {/* ============================================ */}

              <div className="lb-login-field">

                <label htmlFor="lb-register-email">
                  Email address
                </label>

                <input
                  id="lb-register-email"
                  name="email"
                  type="email"
                  placeholder="you@company.com"
                  autoComplete="email"
                  autoCapitalize="none"
                  spellCheck={false}
                  maxLength={255}
                  required
                  value={email}
                  disabled={isSubmitting}
                  onChange={(event) => {
                    setEmail(event.target.value)
                    setErrorMessage(null)
                  }}
                />

              </div>


              {/* ============================================ */}
              {/* PASSWORD */}
              {/* ============================================ */}

              <div className="lb-login-field">

                <label htmlFor="lb-register-password">
                  Password
                </label>

                <div className="lb-login-password-wrapper">

                  <input
                    id="lb-register-password"
                    name="password"
                    type={showPassword ? 'text' : 'password'}
                    placeholder="Create a password"
                    autoComplete="new-password"
                    minLength={MIN_PASSWORD_LENGTH}
                    maxLength={MAX_PASSWORD_LENGTH}
                    required
                    value={password}
                    disabled={isSubmitting}
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
                    disabled={isSubmitting}
                    onClick={() => {
                      setShowPassword(previous => !previous)
                    }}
                  >
                    {showPassword ? (
                      <EyeOff size={18} aria-hidden="true" />
                    ) : (
                      <Eye size={18} aria-hidden="true" />
                    )}
                  </button>

                </div>

                {/* ============================================ */}
                {/* PASSWORD REQUIREMENTS */}
                {/* ============================================ */}

                <p className="lb-register-field-hint">
                At least 12 characters. Use a unique, strong password.
                </p>

              </div>


              {/* ============================================ */}
              {/* CONFIRM PASSWORD */}
              {/* ============================================ */}

              <div className="lb-login-field">

                <label htmlFor="lb-register-confirm">
                  Confirm password
                </label>

                <div className="lb-login-password-wrapper">

                  <input
                    id="lb-register-confirm"
                    name="confirmPassword"
                    type={
                      showConfirmPassword
                        ? 'text'
                        : 'password'
                    }
                    placeholder="Re-enter your password"
                    autoComplete="new-password"
                    required
                    value={confirmPassword}
                    disabled={isSubmitting}
                    onChange={(event) => {
                      setConfirmPassword(event.target.value)
                      setErrorMessage(null)
                    }}
                  />

                  <button
                    type="button"
                    className="lb-login-password-toggle"
                    aria-label={
                      showConfirmPassword
                        ? 'Hide confirmation password'
                        : 'Show confirmation password'
                    }
                    aria-pressed={showConfirmPassword}
                    disabled={isSubmitting}
                    onClick={() => {
                      setShowConfirmPassword(
                        previous => !previous,
                      )
                    }}
                  >
                    {showConfirmPassword ? (
                      <EyeOff size={18} aria-hidden="true" />
                    ) : (
                      <Eye size={18} aria-hidden="true" />
                    )}
                  </button>

                </div>

              </div>


              {/* ============================================ */}
              {/* REGISTRATION ERROR */}
              {/* ============================================ */}

              {errorMessage && (
                <p
                  className="lb-login-error"
                  role="alert"
                >
                  {errorMessage}
                </p>
              )}


              {/* ============================================ */}
              {/* SUBMIT BUTTON */}
              {/* ============================================ */}

              <button
                type="submit"
                className="lb-login-submit"
                disabled={isSubmitting}
              >

                <span>
                  {isSubmitting
                    ? 'Creating account...'
                    : 'Create account'}
                </span>

                {!isSubmitting && (
                  <ArrowRight
                    size={18}
                    aria-hidden="true"
                  />
                )}

              </button>

            </form>


            {/* ============================================== */}
            {/* RETURN TO SIGN-IN */}
            {/* ============================================== */}

            <div className="lb-register-switch">

              <span>Already have an account?</span>

              <button
                type="button"
                disabled={isSubmitting}
                onClick={onBackToSignIn}
              >
                Sign in
              </button>

            </div>


            {/* ============================================== */}
            {/* PASSWORD SECURITY NOTE */}
            {/* ============================================== */}

            <div className="lb-login-session-note">

              <LockKeyhole
                size={15}
                aria-hidden="true"
              />

              <p>
                Your password is securely hashed before
                storage. You will sign in after creating
                your account.
              </p>

            </div>

          </div>

        </main>

      </div>

    </div>
  )
}

// ============================================================
// EXPORT REGISTRATION PAGE
// ============================================================

export default RegisterPage
