import {
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react'

import {
  LayoutDashboard,
  BookOpen,
  ArrowLeftRight,
  Scale,
  TrendingUp,
  FileSpreadsheet,
  CalendarRange,
  ChevronUp,
  LogOut,
} from 'lucide-react'

import './App.css'

import { useAuth } from './auth/useAuth'

import {
  getBusinesses,
  type Business,
} from './api/businesses'

import OverviewPage from './pages/OverviewPage'
import AccountsPage from './pages/AccountsPage'
import TransactionsPage from './pages/TransactionsPage'
import FiscalYearsPage from './pages/FiscalYearsPage'
import TrialBalancePage from './pages/TrialBalancePage'
import IncomeStatementPage from './pages/IncomeStatementPage'
import BalanceSheetPage from './pages/BalanceSheetPage'

// ============================================================
// LEDGERBASE — MAIN APPLICATION
// ============================================================

/**
 * Application shell for the authenticated accounting workspace.
 *
 * FEATURES:
 *
 * - Business selection.
 * - Grouped sidebar navigation.
 * - Accounting and financial reporting pages.
 * - Authenticated user profile.
 * - Backend-connected Sign out action.
 *
 * SECURITY:
 *
 * User information comes from AuthProvider.
 * It is never hardcoded in the frontend.
 *
 * The backend remains responsible for enforcing
 * business membership and accounting permissions.
 */

// ============================================================
// APPLICATION PAGE TYPES
// ============================================================

type Page =
  | 'overview'
  | 'accounts'
  | 'transactions'
  | 'trial-balance'
  | 'income-statement'
  | 'balance-sheet'
  | 'fiscal-years'

// ============================================================
// SIDEBAR NAVIGATION TYPES
// ============================================================

interface NavigationItem {
  id: Page
  label: string
  icon: ReactNode
}

interface NavigationGroup {
  heading: string
  accessibleLabel: string
  items: NavigationItem[]
}

// ============================================================
// SIDEBAR NAVIGATION CONFIGURATION
// ============================================================

/**
 * Keep the existing LedgerBase navigation structure.
 *
 * Icons use lucide-react.
 * Clicking a navigation button changes React page state.
 */
const navigationGroups: NavigationGroup[] = [
  {
    heading: 'WORKSPACE',
    accessibleLabel: 'Workspace',
    items: [
      {
        id: 'overview',
        label: 'Overview',
        icon: (
          <LayoutDashboard size={18} strokeWidth={1.8} />
        ),
      },
    ],
  },
  {
    heading: 'ACCOUNTING',
    accessibleLabel: 'Accounting',
    items: [
      {
        id: 'accounts',
        label: 'Chart of Accounts',
        icon: (
          <BookOpen size={18} strokeWidth={1.8} />
        ),
      },
      {
        id: 'transactions',
        label: 'Transactions',
        icon: (
          <ArrowLeftRight size={18} strokeWidth={1.8} />
        ),
      },
      {
        id: 'fiscal-years',
        label: 'Fiscal Years & Periods',
        icon: (
          <CalendarRange size={18} strokeWidth={1.8} />
        ),
      },
    ],
  },
  {
    heading: 'FINANCIAL REPORTS',
    accessibleLabel: 'Financial Reports',
    items: [
      {
        id: 'trial-balance',
        label: 'Trial Balance',
        icon: (
          <Scale size={18} strokeWidth={1.8} />
        ),
      },
      {
        id: 'income-statement',
        label: 'Income Statement',
        icon: (
          <TrendingUp size={18} strokeWidth={1.8} />
        ),
      },
      {
        id: 'balance-sheet',
        label: 'Balance Sheet',
        icon: (
          <FileSpreadsheet size={18} strokeWidth={1.8} />
        ),
      },
    ],
  },
]

// ============================================================
// BUSINESS SELECTION CONFIGURATION
// ============================================================

/**
 * Remember the last selected business.
 *
 * Only the business ID is saved.
 *
 * This is a UI preference, NOT an authentication
 * credential or authorization mechanism.
 *
 * Saved selections must be validated against
 * businesses returned by the backend.
 */
const SELECTED_BUSINESS_STORAGE_KEY =
  'ledgerbase.selectedBusinessId'

// ============================================================
// USER PROFILE HELPER
// ============================================================

/**
 * Generate avatar initials using the real user's name.
 *
 * Examples:
 *
 * Development User -> DU
 * Angel Zhou       -> AZ
 *
 * This is display-only information.
 */
function getUserInitials(fullName: string): string {

  const nameParts = fullName
    .trim()
    .split(/\s+/)
    .filter(Boolean)

  if (nameParts.length === 0) {
    return '?'
  }

  return nameParts
    .slice(0, 2)
    .map((part) => part.charAt(0))
    .join('')
    .toUpperCase()
}

// ============================================================
// MAIN LEDGERBASE APPLICATION
// ============================================================

function App() {

  // ==========================================================
  // AUTHENTICATED USER
  // ==========================================================

  /**
   * Access the verified authentication context.
   *
   * currentUser contains the userId, fullName, and email
   * retrieved from Spring Boot.
   *
   * signOut() uses the existing backend logout workflow.
   */
  const {
    currentUser,
    signOut,
  } = useAuth()

  // ==========================================================
  // PROFILE MENU STATE
  // ==========================================================

  /**
   * Controls whether the account details panel is open.
   */
  const [isProfileMenuOpen, setIsProfileMenuOpen] =
    useState(false)

  const profileContainerRef =
    useRef<HTMLDivElement>(null)

  const profileTriggerRef =
    useRef<HTMLButtonElement>(null)

  // ==========================================================
  // CLOSE PROFILE MENU ON OUTSIDE CLICK OR ESCAPE
  // ==========================================================

  /**
   * Improve keyboard and pointer accessibility.
   *
   * - Clicking outside closes the account panel.
   * - Pressing Escape closes the account panel.
   * - Escape returns focus to the profile button.
   */
  useEffect(() => {

    if (!isProfileMenuOpen) {
      return
    }

    function handleOutsidePointerDown(event: PointerEvent) {

      const target = event.target

      if (
        target instanceof Node &&
        !profileContainerRef.current?.contains(target)
      ) {
        setIsProfileMenuOpen(false)
      }
    }

    function handleEscape(event: KeyboardEvent) {

      if (event.key === 'Escape') {

        setIsProfileMenuOpen(false)

        profileTriggerRef.current?.focus()
      }
    }

    document.addEventListener(
      'pointerdown',
      handleOutsidePointerDown,
    )

    document.addEventListener(
      'keydown',
      handleEscape,
    )

    return () => {

      document.removeEventListener(
        'pointerdown',
        handleOutsidePointerDown,
      )

      document.removeEventListener(
        'keydown',
        handleEscape,
      )
    }

  }, [isProfileMenuOpen])

  // ==========================================================
  // SELECTED PAGE STATE
  // ==========================================================

  /**
   * Start at Overview.
   *
   * Sidebar navigation updates this state.
   */
  const [activePage, setActivePage] =
    useState<Page>('overview')

  // ==========================================================
  // BUSINESS STATE
  // ==========================================================

  /**
   * Businesses are loaded from the Spring Boot API.
   *
   * The currently selected ID is passed to financial
   * pages through the businessId prop.
   */
  const [businesses, setBusinesses] =
    useState<Business[]>([])

  const [selectedBusinessId, setSelectedBusinessId] =
    useState<number | null>(null)

  const [businessesLoading, setBusinessesLoading] =
    useState(true)

  const [businessesError, setBusinessesError] =
    useState<string | null>(null)

  // ==========================================================
  // LOAD AVAILABLE BUSINESSES
  // ==========================================================

  /**
   * Retrieve businesses from:
   *
   * GET /api/v1/businesses
   *
   * SELECTION PRIORITY:
   *
   * 1. Keep an existing valid selection.
   * 2. Restore the saved business preference.
   * 3. Select the first available business.
   * 4. Use null when no businesses are available.
   *
   * The API results determine which saved IDs are valid.
   */
  useEffect(() => {

    let active = true

    async function loadBusinesses(): Promise<void> {

      try {

        // ----------------------------------------------------
        // RETRIEVE AVAILABLE BUSINESSES
        // ----------------------------------------------------

        const availableBusinesses =
          await getBusinesses()

        if (!active) {
          return
        }

        setBusinesses(availableBusinesses)

        // ----------------------------------------------------
        // READ SAVED BUSINESS PREFERENCE
        // ----------------------------------------------------

        let rememberedBusinessId: number | null = null

        try {

          const storedId = window.localStorage.getItem(
            SELECTED_BUSINESS_STORAGE_KEY,
          )

          if (storedId !== null) {

            const parsedId = Number(storedId)

            if (Number.isSafeInteger(parsedId)) {
              rememberedBusinessId = parsedId
            }
          }

        } catch {

          /**
           * Browser storage may be restricted.
           *
           * Business selection must still work.
           */
          rememberedBusinessId = null
        }

        // ----------------------------------------------------
        // SELECT A VALID BUSINESS
        // ----------------------------------------------------

        setSelectedBusinessId((previousBusinessId) => {

          const previousBusiness =
            availableBusinesses.find(
              (business) =>
                business.id === previousBusinessId,
            )

          if (previousBusiness) {
            return previousBusiness.id
          }

          const rememberedBusiness =
            availableBusinesses.find(
              (business) =>
                business.id === rememberedBusinessId,
            )

          if (rememberedBusiness) {
            return rememberedBusiness.id
          }

          return availableBusinesses[0]?.id ?? null
        })

        setBusinessesError(null)

      } catch (error) {

        if (!active) {
          return
        }

        setBusinessesError(
          error instanceof Error
            ? error.message
            : 'Unable to load businesses.',
        )

      } finally {

        if (active) {
          setBusinessesLoading(false)
        }
      }
    }

    void loadBusinesses()

    // --------------------------------------------------------
    // PREVENT STATE UPDATES AFTER UNMOUNT
    // --------------------------------------------------------

    return () => {
      active = false
    }

  }, [])

  // ==========================================================
  // HANDLE BUSINESS SWITCHING
  // ==========================================================

  /**
   * Change the active business using the sidebar selector.
   *
   * The newly selected ID must exist in the API results.
   *
   * Changing businesses resets navigation to Overview
   * and updates the browser's saved UI preference.
   */
  function handleBusinessChange(newBusinessId: number): void {

    const selectedBusiness =
      businesses.find(
        (business) =>
          business.id === newBusinessId,
      )

    if (!selectedBusiness) {
      return
    }

    // --------------------------------------------------------
    // UPDATE BUSINESS AND PAGE
    // --------------------------------------------------------

    setSelectedBusinessId(selectedBusiness.id)

    setActivePage('overview')

    // --------------------------------------------------------
    // SAVE UI PREFERENCE
    // --------------------------------------------------------

    try {

      window.localStorage.setItem(
        SELECTED_BUSINESS_STORAGE_KEY,
        String(selectedBusiness.id),
      )

    } catch {

      // Local storage is optional.
    }
  }

  // ==========================================================
  // CURRENT ACCOUNTING BUSINESS
  // ==========================================================

  const businessId = selectedBusinessId

  // ==========================================================
  // PAGE HEADINGS
  // ==========================================================

  /**
   * Preserve the existing titles and descriptions.
   */
  const pageInformation: Record<
    Page,
    { title: string; description: string }
  > = {
    overview: {
      title: 'Overview',
      description: 'Your financial workspace at a glance.',
    },
    accounts: {
      title: 'Chart of Accounts',
      description: 'Manage the accounts used in your general ledger.',
    },
    transactions: {
      title: 'Transactions',
      description: 'Record and manage your accounting transactions.',
    },
    'trial-balance': {
      title: 'Trial Balance',
      description: 'Review account balances and verify your general ledger.',
    },
    'income-statement': {
      title: 'Income Statement',
      description: 'Review revenue, expenses, and net income for a reporting period.',
    },
    'balance-sheet': {
      title: 'Balance Sheet',
      description: 'Review assets, liabilities, and equity as of a reporting date.',
    },
    'fiscal-years': {
      title: 'Fiscal Years & Accounting Periods',
      description: 'Manage your accounting calendar and monthly posting periods.',
    },
  }

  const currentPage =
    pageInformation[activePage]

  // ==========================================================
  // RENDER APPLICATION
  // ==========================================================

  return (
    <div className="app-shell">

      {/* ==================================================== */}
      {/* SIDEBAR */}
      {/* ==================================================== */}

      <aside className="sidebar">

        {/* ================================================== */}
        {/* LEDGERBASE BRANDING */}
        {/* ================================================== */}

        <div className="sidebar-brand">

          <img
            src="/ledgerbase-favicon.png"
            alt=""
            aria-hidden="true"
            className="brand-logo"
          />

          <div className="brand-copy">
            <strong>LedgerBase</strong>
            <span>Financial Workspace</span>
          </div>

        </div>

        {/* ================================================== */}
        {/* BUSINESS SELECTOR */}
        {/* ================================================== */}

        <div className="sidebar-business-selector">

          <label
            htmlFor="active-business"
            className="business-selector-label"
          >
            CURRENT BUSINESS
          </label>

          <select
            id="active-business"
            className="business-selector-input"
            value={businessId ?? ''}
            disabled={
              businessesLoading ||
              businessesError !== null ||
              businesses.length === 0
            }
            onChange={(event) => {

              const newBusinessId =
                Number(event.target.value)

              handleBusinessChange(newBusinessId)
            }}
          >

            {businesses.length === 0 ? (

              <option value="">
                {businessesLoading
                  ? 'Loading businesses...'
                  : 'No businesses available'}
              </option>

            ) : (

              businesses.map((business) => (
                <option
                  key={business.id}
                  value={business.id}
                >
                  {business.name}
                </option>
              ))

            )}

          </select>

        </div>

        {/* ================================================== */}
        {/* GROUPED NAVIGATION */}
        {/* ================================================== */}

        <nav
          className="sidebar-navigation"
          aria-label="Main navigation"
        >

          {navigationGroups.map((group) => (

            <div
              key={group.heading}
              className="navigation-group"
              role="group"
              aria-label={group.accessibleLabel}
            >

              <p
                className="navigation-label"
                aria-hidden="true"
              >
                {group.heading}
              </p>

              {group.items.map((item) => (

                <button
                  key={item.id}
                  type="button"
                  className={
                    activePage === item.id
                      ? 'navigation-item active'
                      : 'navigation-item'
                  }
                  aria-current={
                    activePage === item.id
                      ? 'page'
                      : undefined
                  }
                  onClick={() => setActivePage(item.id)}
                >

                  <span
                    className="navigation-icon"
                    aria-hidden="true"
                  >
                    {item.icon}
                  </span>

                  <span>{item.label}</span>

                </button>

              ))}

            </div>

          ))}

        </nav>

        {/* ================================================== */}
        {/* USER PROFILE AND SIGN OUT */}
        {/* ================================================== */}

        <div className="sidebar-footer">

          {currentUser && (

            <div
              className="sidebar-user-profile"
              ref={profileContainerRef}
            >

              {/* ============================================ */}
              {/* EXPANDABLE PROFILE PANEL */}
              {/* ============================================ */}

              {isProfileMenuOpen && (

                <section
                  id="sidebar-profile-panel"
                  className="sidebar-profile-panel"
                  aria-label="Account details"
                >

                  <span className="sidebar-profile-panel-label">
                    SIGNED IN AS
                  </span>

                  <strong className="sidebar-profile-panel-name">
                    {currentUser.fullName}
                  </strong>

                  <span className="sidebar-profile-panel-email">
                    {currentUser.email}
                  </span>

                  {/* ======================================== */}
                  {/* BACKEND-CONNECTED SIGN OUT */}
                  {/* ======================================== */}

                  <button
                    type="button"
                    className="sidebar-signout-button"
                    onClick={() => {

                      setIsProfileMenuOpen(false)

                      /**
                       * AuthProvider revokes the backend
                       * session before clearing React state.
                       *
                       * A failed logout is handled there.
                       */
                      signOut()
                    }}
                  >

                    <LogOut
                      size={16}
                      strokeWidth={1.8}
                      aria-hidden="true"
                    />

                    <span>Sign out</span>

                  </button>

                </section>

              )}

              {/* ============================================ */}
              {/* PROFILE MENU TRIGGER */}
              {/* ============================================ */}

              <button
                type="button"
                className="sidebar-profile-trigger"
                ref={profileTriggerRef}
                aria-label={
                  `Account options for ${currentUser.fullName}`
                }
                aria-expanded={isProfileMenuOpen}
                aria-controls={
                  isProfileMenuOpen
                    ? 'sidebar-profile-panel'
                    : undefined
                }
                onClick={() => {

                  setIsProfileMenuOpen(
                    (previous) => !previous,
                  )
                }}
              >

                <span
                  className="sidebar-profile-avatar"
                  aria-hidden="true"
                >
                  {getUserInitials(currentUser.fullName)}
                </span>

                <span className="sidebar-profile-identity">

                  <strong>{currentUser.fullName}</strong>

                  <span>{currentUser.email}</span>

                </span>

                <ChevronUp
                  size={17}
                  strokeWidth={1.8}
                  aria-hidden="true"
                  className={
                    isProfileMenuOpen
                      ? 'sidebar-profile-chevron expanded'
                      : 'sidebar-profile-chevron'
                  }
                />

              </button>

            </div>

          )}

          {/* ================================================= */}
          {/* EXISTING LOCAL DEVELOPMENT STATUS */}
          {/* ================================================= */}

          <div className="sidebar-environment">

            <div className="environment-status">

              <span className="environment-dot"></span>

              Local Development

            </div>

            <p>LedgerBase MVP</p>

          </div>

        </div>

      </aside>

      {/* ==================================================== */}
      {/* MAIN CONTENT */}
      {/* ==================================================== */}

      {/*
        The key resets the accounting page subtree when
        switching businesses.

        This clears page-specific forms, filters, and report
        selections belonging to the previous business.
      */}

      <main
        key={businessId ?? 'unselected'}
        className="main-content"
      >

        {/* ================================================== */}
        {/* BUSINESS LOADING */}
        {/* ================================================== */}

        {businessesLoading && (

          <p
            className="page-description"
            role="status"
          >
            Loading your businesses...
          </p>

        )}

        {/* ================================================== */}
        {/* BUSINESS LOADING ERROR */}
        {/* ================================================== */}

        {!businessesLoading && businessesError && (

          <div role="alert">

            <h1>Unable to load businesses</h1>

            <p className="page-description">
              {businessesError}
            </p>

            <p className="page-description">
              Check that the LedgerBase backend is running,
              then refresh the page.
            </p>

          </div>

        )}

        {/* ================================================== */}
        {/* NO BUSINESSES AVAILABLE */}
        {/* ================================================== */}

        {!businessesLoading &&
          !businessesError &&
          businessId === null && (

          <div>

            <h1>No businesses available</h1>

            <p className="page-description">
              Create a business to begin using LedgerBase.
            </p>

          </div>

        )}

        {/* ================================================== */}
        {/* AUTHENTICATED ACCOUNTING WORKSPACE */}
        {/* ================================================== */}

        {businessId !== null &&
          !businessesLoading &&
          !businessesError && (

          <>

            {/* ============================================== */}
            {/* SHARED PAGE HEADER */}
            {/* ============================================== */}

            {activePage !== 'trial-balance' &&
              activePage !== 'income-statement' &&
              activePage !== 'balance-sheet' &&
              activePage !== 'fiscal-years' && (

              <header className="page-header">

                <div>

                  <p className="page-eyebrow">
                    LEDGERBASE / WORKSPACE
                  </p>

                  <h1>{currentPage.title}</h1>

                  <p className="page-description">
                    {currentPage.description}
                  </p>

                </div>

                <div className="preview-label">
                  <span className="preview-dot"></span>
                  UI Preview
                </div>

              </header>

            )}

            {/* ============================================== */}
            {/* OVERVIEW DASHBOARD */}
            {/* ============================================== */}

            {activePage === 'overview' && (

              <OverviewPage
                businessId={businessId}
                onNavigate={(page) => setActivePage(page)}
              />

            )}

            {/* ============================================== */}
            {/* CHART OF ACCOUNTS */}
            {/* ============================================== */}

            {activePage === 'accounts' && (

              <AccountsPage businessId={businessId} />

            )}

            {/* ============================================== */}
            {/* TRANSACTIONS */}
            {/* ============================================== */}

            {activePage === 'transactions' && (

              <TransactionsPage businessId={businessId} />

            )}

            {/* ============================================== */}
            {/* TRIAL BALANCE */}
            {/* ============================================== */}

            {activePage === 'trial-balance' && (

              <TrialBalancePage businessId={businessId} />

            )}

            {/* ============================================== */}
            {/* INCOME STATEMENT */}
            {/* ============================================== */}

            {activePage === 'income-statement' && (

              <IncomeStatementPage businessId={businessId} />

            )}

            {/* ============================================== */}
            {/* BALANCE SHEET */}
            {/* ============================================== */}

            {activePage === 'balance-sheet' && (

              <BalanceSheetPage businessId={businessId} />

            )}

            {/* ============================================== */}
            {/* FISCAL YEARS AND ACCOUNTING PERIODS */}
            {/* ============================================== */}

            {activePage === 'fiscal-years' && currentUser && (

              <FiscalYearsPage
                businessId={businessId}
              />

            )}

          </>

        )}

      </main>

    </div>
  )
}

// ============================================================
// EXPORT APPLICATION
// ============================================================

export default App
