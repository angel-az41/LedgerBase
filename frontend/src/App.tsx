import { useEffect, useState, type ReactNode } from 'react'

import {
  LayoutDashboard,
  BookOpen,
  ArrowLeftRight,
  Scale,
  TrendingUp,
  FileSpreadsheet,
  CalendarRange,
} from 'lucide-react'

import './App.css'

// ============================================================
// LEDGERBASE — BUSINESS MANAGEMENT API
// ============================================================

/**
 * getBusinesses() retrieves businesses from Spring Boot.
 *
 * Business defines the TypeScript structure of a business.
 *
 * The frontend does not hardcode the available businesses.
 */
import {
  getBusinesses,
  type Business,
} from './api/businesses'

// ============================================================
// LEDGERBASE — PAGE COMPONENTS
// ============================================================

/**
 * Each major LedgerBase screen has its own React component.
 *
 * All financial pages receive the currently selected
 * business ID from this application component.
 */
import OverviewPage from './pages/OverviewPage'
import AccountsPage from './pages/AccountsPage'
import TransactionsPage from './pages/TransactionsPage'
import FiscalYearsPage from './pages/FiscalYearsPage'
import TrialBalancePage from './pages/TrialBalancePage'
import IncomeStatementPage from './pages/IncomeStatementPage'
import BalanceSheetPage from './pages/BalanceSheetPage'

// ============================================================
// LEDGERBASE — APPLICATION TYPES
// ============================================================

/**
 * Union of all supported application pages.
 */
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

/**
 * One clickable sidebar navigation item.
 */
interface NavigationItem {
  id: Page
  label: string
  icon: ReactNode
}

/**
 * One group of sidebar navigation items.
 */
interface NavigationGroup {
  heading: string
  accessibleLabel: string
  items: NavigationItem[]
}

// ============================================================
// GROUPED SIDEBAR CONFIGURATION
// ============================================================

/**
 * Preserve LedgerBase's existing navigation groups,
 * labels, and lucide-react icons.
 */
const navigationGroups: NavigationGroup[] = [

  // ==========================================================
  // WORKSPACE
  // ==========================================================

  {
    heading: 'WORKSPACE',
    accessibleLabel: 'Workspace',

    items: [
      {
        id: 'overview',
        label: 'Overview',
        icon: (
          <LayoutDashboard
            size={18}
            strokeWidth={1.8}
          />
        ),
      },
    ],
  },

  // ==========================================================
  // ACCOUNTING
  // ==========================================================

  {
    heading: 'ACCOUNTING',
    accessibleLabel: 'Accounting',

    items: [
      {
        id: 'transactions',
        label: 'Transactions',
        icon: (
          <ArrowLeftRight
            size={18}
            strokeWidth={1.8}
          />
        ),
      },
      {
        id: 'accounts',
        label: 'Chart of Accounts',
        icon: (
          <BookOpen
            size={18}
            strokeWidth={1.8}
          />
        ),
      },
      {
        id: 'fiscal-years',
        label: 'Fiscal Years & Periods',
        icon: (
          <CalendarRange
            size={18}
            strokeWidth={1.8}
          />
        ),
      },
    ],
  },

  // ==========================================================
  // FINANCIAL REPORTS
  // ==========================================================

  {
    heading: 'FINANCIAL REPORTS',
    accessibleLabel: 'Financial Reports',

    items: [
      {
        id: 'trial-balance',
        label: 'Trial Balance',
        icon: (
          <Scale
            size={18}
            strokeWidth={1.8}
          />
        ),
      },
      {
        id: 'income-statement',
        label: 'Income Statement',
        icon: (
          <TrendingUp
            size={18}
            strokeWidth={1.8}
          />
        ),
      },
      {
        id: 'balance-sheet',
        label: 'Balance Sheet',
        icon: (
          <FileSpreadsheet
            size={18}
            strokeWidth={1.8}
          />
        ),
      },
    ],
  },
]

// ============================================================
// BUSINESS SELECTION CONFIGURATION
// ============================================================

/**
 * Browser storage key used to remember which business
 * was selected during the previous session.
 *
 * This stores only a business ID.
 *
 * It is a UI preference, NOT an authentication
 * credential or authorization mechanism.
 *
 * Any stored ID must also exist in the list
 * returned by the backend.
 */
const SELECTED_BUSINESS_STORAGE_KEY =
  'ledgerbase.selectedBusinessId'

// ============================================================
// MAIN LEDGERBASE APPLICATION
// ============================================================

function App() {

  // ==========================================================
  // SELECTED PAGE STATE
  // ==========================================================

  /**
   * Start on the Overview dashboard.
   *
   * Sidebar navigation updates this state.
   */
  const [activePage, setActivePage] =
    useState<Page>('overview')

  // ==========================================================
  // AVAILABLE BUSINESSES
  // ==========================================================

  /**
   * Businesses retrieved from:
   *
   * GET /api/v1/businesses
   *
   * Example:
   *
   * [
   *   { id: 1, name: "LedgerBase Demo Business", ... },
   *   { id: 2, name: "ABC Consulting LLC", ... }
   * ]
   */
  const [businesses, setBusinesses] =
    useState<Business[]>([])

  // ==========================================================
  // SELECTED BUSINESS
  // ==========================================================

  /**
   * Previously, LedgerBase always used:
   *
   * const businessId = 1
   *
   * Now the selected business is stored in React state.
   *
   * null means a valid business has not yet been selected.
   */
  const [selectedBusinessId, setSelectedBusinessId] =
    useState<number | null>(null)

  // ==========================================================
  // BUSINESS LOADING AND ERROR STATES
  // ==========================================================

  /**
   * Avoid rendering accounting pages before we know
   * which business should be displayed.
   */
  const [businessesLoading, setBusinessesLoading] =
    useState(true)

  const [businessesError, setBusinessesError] =
    useState<string | null>(null)

  // ==========================================================
  // LOAD AVAILABLE BUSINESSES ON STARTUP
  // ==========================================================

  /**
   * useEffect runs after the application mounts.
   *
   * We retrieve the list of available businesses
   * from the backend instead of hardcoding business IDs.
   *
   * SELECTION PRIORITY:
   *
   * 1. Keep a valid existing React selection.
   * 2. Restore a valid previously saved business.
   * 3. Fall back to the first available business.
   * 4. Use null if no businesses are available.
   *
   * IMPORTANT:
   *
   * A remembered ID is accepted only if it exists
   * in the business list returned by the API.
   */
  useEffect(() => {

    // ========================================================
    // CLEANUP FLAG
    // ========================================================

    /**
     * If the component unmounts before the API finishes,
     * don't update its state afterward.
     */
    let active = true

    // ========================================================
    // FETCH BUSINESSES
    // ========================================================

    async function loadBusinesses() {

      try {

        const availableBusinesses = await getBusinesses()

        if (!active) {
          return
        }

        // ====================================================
        // STORE AVAILABLE BUSINESSES
        // ====================================================

        setBusinesses(availableBusinesses)

        // ====================================================
        // READ PREVIOUS BUSINESS PREFERENCE
        // ====================================================

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
           * Some browser configurations restrict
           * access to localStorage.
           *
           * LedgerBase should still work even if
           * the preference cannot be read.
           */
          rememberedBusinessId = null
        }

        // ====================================================
        // SELECT A VALID BUSINESS
        // ====================================================

        setSelectedBusinessId((previousBusinessId) => {

          /**
           * Preserve the current selection if
           * it still exists in the API results.
           */
          const previousBusiness =
            availableBusinesses.find(
              (business) =>
                business.id === previousBusinessId,
            )

          if (previousBusiness) {
            return previousBusiness.id
          }

          /**
           * Otherwise, restore the remembered business
           * if the backend still returns it.
           */
          const rememberedBusiness =
            availableBusinesses.find(
              (business) =>
                business.id === rememberedBusinessId,
            )

          if (rememberedBusiness) {
            return rememberedBusiness.id
          }

          /**
           * Otherwise, select the first business.
           *
           * An empty list results in null.
           */
          return availableBusinesses[0]?.id ?? null
        })

        setBusinessesError(null)

      } catch (error) {

        if (!active) {
          return
        }

        // ====================================================
        // DISPLAY A USEFUL ERROR
        // ====================================================

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

    // ========================================================
    // START INITIAL REQUEST
    // ========================================================

    void loadBusinesses()

    // ========================================================
    // CLEAN UP WHEN COMPONENT UNMOUNTS
    // ========================================================

    return () => {
      active = false
    }

  }, [])

  // ==========================================================
  // HANDLE BUSINESS SELECTION
  // ==========================================================

  /**
   * This function runs when the user chooses a business
   * from the sidebar dropdown.
   *
   * Example:
   *
   * LedgerBase Demo Business -> ABC Consulting LLC
   *
   * The new business ID is passed to all accounting pages.
   *
   * We also navigate back to Overview so the user
   * begins in a consistent workspace.
   */
  function handleBusinessChange(newBusinessId: number) {

    // ========================================================
    // ENSURE BUSINESS EXISTS IN THE AVAILABLE LIST
    // ========================================================

    const selectedBusiness = businesses.find(
      (business) => business.id === newBusinessId,
    )

    if (!selectedBusiness) {
      return
    }

    // ========================================================
    // UPDATE REACT STATE
    // ========================================================

    setSelectedBusinessId(selectedBusiness.id)

    setActivePage('overview')

    // ========================================================
    // REMEMBER BUSINESS PREFERENCE
    // ========================================================

    /**
     * This affects only the browser's local UI preference.
     *
     * Actual business data remains in PostgreSQL.
     */
    try {

      window.localStorage.setItem(
        SELECTED_BUSINESS_STORAGE_KEY,
        String(selectedBusiness.id),
      )

    } catch {

      /**
       * Browser storage is optional.
       *
       * Business switching still works without it.
       */
    }
  }

  // ==========================================================
  // SELECTED BUSINESS ID FOR ACCOUNTING PAGES
  // ==========================================================

  /**
   * All accounting pages continue to receive
   * the same businessId prop they used previously.
   *
   * The difference is that its value is now dynamic.
   */
  const businessId = selectedBusinessId

  // ==========================================================
  // PAGE INFORMATION
  // ==========================================================

  /**
   * Preserve the existing shared page headings.
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

  const currentPage = pageInformation[activePage]

  // ==========================================================
  // RENDER APPLICATION
  // ==========================================================

  return (

    <div className="app-shell">

      {/* ====================================================== */}
      {/* SIDEBAR */}
      {/* ====================================================== */}

      <aside className="sidebar">

        {/* ==================================================== */}
        {/* APPLICATION BRANDING — UNCHANGED */}
        {/* ==================================================== */}

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

        {/* ==================================================== */}
        {/* BUSINESS SELECTOR — NEW */}
        {/* ==================================================== */}

        {/*
          The business selector sits beneath the LedgerBase
          branding and above the existing navigation groups.

          We use a native HTML select element because it
          supports keyboard navigation and screen readers.

          We'll add matching professional styling in App.css
          after confirming that the functionality works.
        */}

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

              const newBusinessId = Number(
                event.target.value,
              )

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

        {/* ==================================================== */}
        {/* GROUPED SIDEBAR NAVIGATION — UNCHANGED */}
        {/* ==================================================== */}

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

        {/* ==================================================== */}
        {/* SIDEBAR FOOTER — UNCHANGED */}
        {/* ==================================================== */}

        <div className="sidebar-footer">

          <div className="environment-status">

            <span className="environment-dot"></span>

            Local Development

          </div>

          <p>LedgerBase MVP</p>

        </div>

      </aside>

      {/* ====================================================== */}
      {/* MAIN CONTENT */}
      {/* ====================================================== */}

      {/*
        The key is important for business switching.

        When businessId changes, React creates a fresh
        main-content subtree.

        This resets page-specific state such as:
        - Previously loaded accounts
        - Transaction forms and lists
        - Report date selections
        - Dashboard metrics
        - Fiscal-year and period selections

        The new page then loads data for the newly
        selected business.

        The DOM layout and existing CSS classes
        remain unchanged.
      */}

      <main
        key={businessId ?? 'unselected'}
        className="main-content"
      >

        {/* ==================================================== */}
        {/* BUSINESS LOADING STATE */}
        {/* ==================================================== */}

        {businessesLoading && (

          <p
            className="page-description"
            role="status"
          >
            Loading your businesses...
          </p>

        )}

        {/* ==================================================== */}
        {/* BUSINESS LOADING ERROR */}
        {/* ==================================================== */}

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

        {/* ==================================================== */}
        {/* NO BUSINESSES AVAILABLE */}
        {/* ==================================================== */}

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

        {/* ==================================================== */}
        {/* RENDER THE SELECTED BUSINESS WORKSPACE */}
        {/* ==================================================== */}

        {businessId !== null &&
          !businessesLoading &&
          !businessesError && (

          <>

            {/* ================================================= */}
            {/* SHARED PAGE HEADER */}
            {/* ================================================= */}

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

            {/* ================================================= */}
            {/* OVERVIEW — FINANCIAL DASHBOARD */}
            {/* ================================================= */}

            {activePage === 'overview' && (

              <OverviewPage
                businessId={businessId}
                onNavigate={(page) => setActivePage(page)}
              />

            )}

            {/* ================================================= */}
            {/* CHART OF ACCOUNTS */}
            {/* ================================================= */}

            {activePage === 'accounts' && (

              <AccountsPage businessId={businessId} />

            )}

            {/* ================================================= */}
            {/* TRANSACTIONS */}
            {/* ================================================= */}

            {activePage === 'transactions' && (

              <TransactionsPage businessId={businessId} />

            )}

            {/* ================================================= */}
            {/* TRIAL BALANCE */}
            {/* ================================================= */}

            {activePage === 'trial-balance' && (

              <TrialBalancePage businessId={businessId} />

            )}

            {/* ================================================= */}
            {/* INCOME STATEMENT */}
            {/* ================================================= */}

            {activePage === 'income-statement' && (

              <IncomeStatementPage businessId={businessId} />

            )}

            {/* ================================================= */}
            {/* BALANCE SHEET */}
            {/* ================================================= */}

            {activePage === 'balance-sheet' && (

              <BalanceSheetPage businessId={businessId} />

            )}

            {/* ================================================= */}
            {/* FISCAL YEARS & ACCOUNTING PERIODS */}
            {/* ================================================= */}

            {activePage === 'fiscal-years' && (

              <FiscalYearsPage
                businessId={businessId}
                actorId={1}
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
