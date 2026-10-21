import App from './App'
import LoginPage from './pages/LoginPage'
import { useAuth } from './auth/useAuth'

// ============================================================
// LEDGERBASE — APPLICATION AUTHENTICATION GATE
// ============================================================

/**
 * Controls access to the React application interface.
 *
 * UNAUTHENTICATED:
 *
 * Display the LedgerBase login page.
 *
 * AUTHENTICATED:
 *
 * Display the existing financial workspace.
 *
 * The login state comes from AuthProvider.
 *
 * IMPORTANT:
 *
 * This protects frontend navigation only.
 * Backend authorization must independently protect
 * financial data and business operations.
 */
function AppGate() {

  // ==========================================================
  // READ AUTHENTICATION STATE
  // ==========================================================

  const { isAuthenticated } = useAuth()


  // ==========================================================
  // UNAUTHENTICATED USER
  // ==========================================================

  if (!isAuthenticated) {
    return <LoginPage />
  }


  // ==========================================================
  // AUTHENTICATED USER
  // ==========================================================

  return <App />
}

// ============================================================
// EXPORT APPLICATION GATE
// ============================================================

export default AppGate
