import { useState, type ReactNode } from 'react'

import {
  LayoutDashboard,
  BookOpen,
  ArrowLeftRight,
  Scale,
  CalendarRange,
} from 'lucide-react'

import './App.css'

import AccountsPage from './pages/AccountsPage'
import FiscalYearsPage from './pages/FiscalYearsPage'
import TransactionsPage from './pages/TransactionsPage'
import TrialBalancePage from './pages/TrialBalancePage'

// ============================================================
// LEDGERBASE — APPLICATION TYPES
// ============================================================

/**
 * Define the available pages in our application.
 *
 * TypeScript prevents us from accidentally navigating
 * to a page that does not exist.
 *
 * Trial Balance is our first financial reporting page.
 */
type Page =
  | 'overview'
  | 'accounts'
  | 'transactions'
  | 'trial-balance'
  | 'fiscal-years'

/**
 * Define the structure of a sidebar navigation item.
 */
interface NavigationItem {
  id: Page
  label: string
  icon: ReactNode
}

// ============================================================
// NAVIGATION CONFIGURATION
// ============================================================

/**
 * Define each available LedgerBase page.
 *
 * All icons use:
 *
 * - 18px dimensions
 * - 1.8px stroke width
 * - Consistent Lucide SVG styling
 *
 * Their color is inherited from the surrounding
 * navigation item, allowing our existing CSS to
 * handle hover and active/selected states.
 */

const navigationItems: NavigationItem[] = [

  // ==========================================================
  // OVERVIEW
  // ==========================================================

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

  // ==========================================================
  // CHART OF ACCOUNTS
  // ==========================================================

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

  // ==========================================================
  // TRANSACTIONS
  // ==========================================================

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

  // ==========================================================
  // TRIAL BALANCE
  // ==========================================================

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

  // ==========================================================
  // FISCAL YEARS & ACCOUNTING PERIODS
  // ==========================================================

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
]

// ============================================================
// APPLICATION COMPONENT
// ============================================================

/**
 * App is the main React component for LedgerBase.
 *
 * React's useState hook stores the currently selected page.
 *
 * When the page changes, React automatically updates
 * the visible content without reloading the browser.
 *
 * All pages currently operate on our local demo
 * business with business ID 1.
 */
function App() {

  // ============================================================
  // REACT STATE
  // ============================================================

  /**
   * Start on the Overview page.
   *
   * activePage:
   *   Current navigation selection.
   *
   * setActivePage:
   *   Updates the selected page.
   */
  const [activePage, setActivePage] =
    useState<Page>('overview')

  // ============================================================
  // PAGE INFORMATION
  // ============================================================

  /**
   * Display different headings depending on the
   * selected navigation item.
   *
   * Trial Balance is included here so that the
   * application's page metadata remains complete.
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

      'fiscal-years': {
        title: 'Fiscal Years & Accounting Periods',
        description: 'Manage your accounting calendar and monthly posting periods.',
      },
  }

  const currentPage = pageInformation[activePage]

  // ============================================================
  // RENDER APPLICATION
  // ============================================================

  return (

    <div className="app-shell">

      {/* ====================================================== */}
      {/* SIDEBAR NAVIGATION */}
      {/* ====================================================== */}

      <aside className="sidebar">

        {/* ==================================================== */}
        {/* APPLICATION BRANDING */}
        {/* ==================================================== */}

        <div className="sidebar-brand">

          {/* ================================================== */}
          {/* LEDGERBASE BRAND LOGO */}
          {/* ================================================== */}

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
        {/* SIDEBAR NAVIGATION ITEMS */}
        {/* ==================================================== */}

        <nav
          className="sidebar-navigation"
          aria-label="Main navigation"
        >

          <p className="navigation-label">
            WORKSPACE
          </p>

          {navigationItems.map((item) => (

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

        </nav>

        {/* ==================================================== */}
        {/* DEVELOPMENT ENVIRONMENT INFORMATION */}
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
      {/* MAIN APPLICATION CONTENT */}
      {/* ====================================================== */}

      <main className="main-content">

        {/* ==================================================== */}
        {/* SHARED PAGE HEADER */}
        {/* ==================================================== */}

        {/*
          Preserve the existing shared page header
          for Overview, Chart of Accounts, and Transactions.

          TrialBalancePage already includes its own heading.

          Skip the shared heading for Trial Balance so
          that the title is not displayed twice.

          We can standardize page headers across all
          reporting pages during a future UI refinement.
        */}

        {activePage !== 'trial-balance' &&
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

        {/* ==================================================== */}
        {/* OVERVIEW PAGE */}
        {/* ==================================================== */}

        {activePage === 'overview' && (

          <div className="overview-page">

            {/* ================================================== */}
            {/* WELCOME SECTION */}
            {/* ================================================== */}

            <section className="welcome-panel">

              <p className="welcome-eyebrow">
                FINANCIAL WORKSPACE
              </p>

              <h2>Welcome to LedgerBase</h2>

              <p>
                Manage your general ledger, organize accounts,
                and build reliable accounting records
                in one workspace.
              </p>

            </section>

            {/* ================================================== */}
            {/* FINANCIAL METRICS */}
            {/* ================================================== */}

            <section className="overview-metrics">

              <article className="metric-card">

                <p>Total Assets</p>

                <strong>—</strong>

                <span>
                  Available after financial reporting
                </span>

              </article>

              <article className="metric-card">

                <p>Total Liabilities</p>

                <strong>—</strong>

                <span>
                  Available after financial reporting
                </span>

              </article>

              <article className="metric-card">

                <p>Net Income</p>

                <strong>—</strong>

                <span>
                  Available after financial reporting
                </span>

              </article>

            </section>

            {/* ================================================== */}
            {/* QUICK NAVIGATION */}
            {/* ================================================== */}

            <section className="quick-access">

              <div className="section-heading">

                <h2>Quick Access</h2>

                <p>
                  Continue working in your accounting workspace.
                </p>

              </div>

              <div className="quick-access-grid">

                {/* ============================================== */}
                {/* CHART OF ACCOUNTS QUICK ACCESS */}
                {/* ============================================== */}

                <button
                  type="button"
                  className="quick-access-card"
                  onClick={() => setActivePage('accounts')}
                >

                  <span className="quick-access-icon">
                    ▤
                  </span>

                  <strong>Chart of Accounts</strong>

                  <span>
                    View and organize your general ledger accounts.
                  </span>

                  <span className="quick-access-link">
                    Open accounts →
                  </span>

                </button>

                {/* ============================================== */}
                {/* TRANSACTIONS QUICK ACCESS */}
                {/* ============================================== */}

                <button
                  type="button"
                  className="quick-access-card"
                  onClick={() => setActivePage('transactions')}
                >

                  <span className="quick-access-icon">
                    ⇄
                  </span>

                  <strong>Transactions</strong>

                  <span>
                    Create, review, and post accounting entries.
                  </span>

                  <span className="quick-access-link">
                    Open transactions →
                  </span>

                </button>

              </div>

            </section>

          </div>

        )}

        {/* ==================================================== */}
        {/* CHART OF ACCOUNTS PAGE */}
        {/* ==================================================== */}

        {activePage === 'accounts' && (

          /*
           * The business ID is temporarily fixed to 1
           * for our local LedgerBase demo business.
           *
           * Later, this will come from the authenticated
           * user's selected business.
           */

          <AccountsPage businessId={1} />

        )}

        {/* ==================================================== */}
        {/* TRANSACTIONS PAGE */}
        {/* ==================================================== */}

        {activePage === 'transactions' && (

          /*
           * Display the Transaction Register for our
           * local LedgerBase demo business.
           *
           * businessId is temporarily set to 1.
           *
           * Later, authentication and business selection
           * will determine the current business.
           */

          <TransactionsPage businessId={1} />

        )}

        {/* ==================================================== */}
        {/* TRIAL BALANCE REPORT PAGE */}
        {/* ==================================================== */}

        {activePage === 'trial-balance' && (

          /*
           * Display the new Trial Balance reporting interface.
           *
           * TrialBalancePage provides:
           *
           * - An as-of date selector
           * - Real posted account balances
           * - Debit and credit columns
           * - Financial totals
           * - A balanced/unbalanced indicator
           *
           * Business ID 1 is our existing local
           * development business.
           *
           * The report itself is generated through
           * the Spring Boot Trial Balance API.
           */

          <TrialBalancePage businessId={1} />

        )}

        {/* ==================================================== */}
        {/* FISCAL YEARS & ACCOUNTING PERIODS PAGE */}
        {/* ==================================================== */}

        {activePage === 'fiscal-years' && (

          /*
          * Display the fiscal-year management page
          * for our existing local development business.
          *
          * The business ID is temporarily set to 1.
          *
          * Later, authentication and business selection
          * will determine the current business.
          */

          <FiscalYearsPage
            businessId={1}
            actorId={1}
          />

        )}

      </main>

    </div>

  )
}

// ============================================================
// EXPORT APPLICATION
// ============================================================

export default App
