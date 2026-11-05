import { useState } from 'react'

import App from './App'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'

import { useAuth } from './auth/useAuth'

// ============================================================
// LEDGERBASE — APPLICATION AUTHENTICATION GATE
// ============================================================

/**
 * Controls access to the LedgerBase financial workspace.
 *
 * UNAUTHENTICATED:
 *
 * - Display the login or registration page.
 * - Allow navigation between the two screens.
 * - Return to login after successful registration.
 *
 * AUTHENTICATED:
 *
 * - Display the financial workspace.
 *
 * SECURITY:
 *
 * - Registration never creates a frontend session.
 * - No password is transferred between screens.
 * - AuthProvider remains responsible for JWT sessions.
 * - Backend RBAC independently protects financial APIs.
 */

// ============================================================
// PUBLIC AUTHENTICATION PAGE TYPE
// ============================================================

type PublicAuthPage = 'login' | 'register'


// ============================================================
// APPLICATION GATE
// ============================================================

function AppGate() {

  // ==========================================================
  // READ AUTHENTICATION STATE
  // ==========================================================

  const { isAuthenticated } = useAuth()


  // ==========================================================
  // PUBLIC PAGE STATE
  // ==========================================================

  /**
   * Track which authentication page is visible.
   *
   * React Router is unnecessary for this small
   * unauthenticated registration flow.
   */
  const [publicPage, setPublicPage] =
    useState<PublicAuthPage>('login')


  // ==========================================================
  // REGISTRATION SUCCESS STATE
  // ==========================================================

  /**
   * Preserve only the registered email address.
   *
   * This lets the login screen prefill the email
   * after registration succeeds.
   *
   * Never store or transfer the password.
   */
  const [registeredEmail, setRegisteredEmail] =
    useState<string | null>(null)


  // ==========================================================
  // AUTHENTICATED USER
  // ==========================================================

  /**
   * An authenticated user enters the existing
   * financial workspace without additional routing.
   */
  if (isAuthenticated) {
    return <App />
  }


  // ==========================================================
  // REGISTRATION PAGE
  // ==========================================================

  if (publicPage === 'register') {

    return (
      <RegisterPage

        // Return to login without creating an account.
        onBackToSignIn={() => {
          setPublicPage('login')
        }}

        // Registration succeeded on the backend.
        onRegistrationSuccess={(email) => {

          // Save only the normalized email address.
          setRegisteredEmail(email)

          // Direct the user to sign in.
          setPublicPage('login')

        }}

      />
    )
  }


  // ==========================================================
  // LOGIN PAGE
  // ==========================================================

  return (
    <LoginPage

      // Open the registration screen.
      onCreateAccount={() => {

        // Clear a previous registration notice.
        setRegisteredEmail(null)

        setPublicPage('register')

      }}

      // Prefill email and display a success notice
      // after registration.
      registeredEmail={registeredEmail}

    />
  )
}


// ============================================================
// EXPORT APPLICATION GATE
// ============================================================

export default AppGate
