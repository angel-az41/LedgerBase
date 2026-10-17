import { useState, type ReactNode } from 'react'

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
// LEDGERBASE — PAGE COMPONENTS
// ============================================================

/**
 * Each major LedgerBase screen has its own React component.
 *
 * OverviewPage now retrieves real financial data instead
 * of displaying hardcoded placeholder metrics.
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
 * Union of all currently supported application pages.
 *
 * TypeScript helps prevent navigation to pages
 * that are not implemented.
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
 * A group of related sidebar navigation items.
 *
 * LedgerBase currently has three groups:
 *
 * - Workspace
 * - Accounting
 * - Financial Reports
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
 * Define the sidebar structure separately from App().
 *
 * This keeps the main component easier to maintain
 * as LedgerBase grows.
 *
 * Every existing icon retains:
 *
 * - size={18}
 * - strokeWidth={1.8}
 *
 * The existing App.css controls the visual design.
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
// MAIN LEDGERBASE APPLICATION
// ============================================================

/**
 * App manages the application's selected page.
 *
 * React state is sufficient for our current navigation
 * because the application does not yet require
 * URL-based routing.
 *
 * IMPORTANT:
 *
 * App is responsible for navigation.
 *
 * Individual page components are responsible
 * for their own data fetching and presentation.
 *
 * This separation makes the application easier
 * to extend and test.
 */
function App() {

  // ==========================================================
  // SELECTED PAGE STATE
  // ==========================================================

  /**
   * Start on the Overview dashboard.
   *
   * Clicking any sidebar item updates activePage.
   */
  const [activePage, setActivePage] =
    useState<Page>('overview')

  // ==========================================================
  // CURRENT DEVELOPMENT BUSINESS
  // ==========================================================

  /**
   * Business ID 1 is our current local demo business.
   *
   * Using a shared constant keeps every page pointed
   * at the same business.
   *
   * Later, authenticated business selection will
   * determine this value dynamically.
   */
  const businessId = 1

  // ==========================================================
  // PAGE INFORMATION
  // ==========================================================

  /**
   * Information displayed in the shared page header.
   *
   * Reporting pages and Fiscal Years already provide
   * their own headings, so the shared header is hidden
   * when those pages are selected.
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
        {/* APPLICATION BRANDING */}
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
        {/* GROUPED SIDEBAR NAVIGATION */}
        {/* ==================================================== */}

        {/*
          Navigation groups are rendered dynamically.

          Outer map:
            Renders Workspace, Accounting, and Reports.

          Inner map:
            Renders the clickable pages inside each group.

          Existing navigation-item CSS styles provide:
          - Hover appearance
          - Active appearance
          - Icon and text layout
        */}

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

              {/* ============================================== */}
              {/* NAVIGATION GROUP HEADING */}
              {/* ============================================== */}

              <p
                className="navigation-label"
                aria-hidden="true"
              >
                {group.heading}
              </p>

              {/* ============================================== */}
              {/* GROUP NAVIGATION ITEMS */}
              {/* ============================================== */}

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
        {/* SIDEBAR FOOTER */}
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

      <main className="main-content">

        {/* ==================================================== */}
        {/* SHARED PAGE HEADER */}
        {/* ==================================================== */}

        {/*
          The shared header is displayed for:

          - Overview
          - Chart of Accounts
          - Transactions

          Other pages already display their own titles.

          OverviewPage deliberately does not provide
          an additional page-level h1 because App
          renders the shared Overview heading.
        */}

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

        {/* ==================================================== */}
        {/* OVERVIEW — REAL FINANCIAL DASHBOARD */}
        {/* ==================================================== */}

        {/*
          MILESTONE 18:

          Previously, this section contained hardcoded
          metric cards with placeholder values.

          We now render the dedicated OverviewPage.

          OverviewPage is responsible for:

          1. Retrieving the Balance Sheet.
          2. Retrieving configured fiscal years.
          3. Finding the fiscal year covering today.
          4. Retrieving fiscal-year-to-date Net Income.
          5. Displaying exact backend financial values.
          6. Showing loading and error states.
          7. Displaying classification warnings.
          8. Refreshing the dashboard.

          Navigation stays in App.

          The onNavigate callback allows Quick Access
          buttons to open Accounts or Transactions
          without duplicating navigation state.
        */}

        {activePage === 'overview' && (

          <OverviewPage
            businessId={businessId}
            onNavigate={(page) => setActivePage(page)}
          />

        )}

        {/* ==================================================== */}
        {/* CHART OF ACCOUNTS */}
        {/* ==================================================== */}

        {activePage === 'accounts' && (

          /*
           * Manage the general ledger account structure.
           *
           * Includes account classifications, parent
           * hierarchy, and posting-account settings.
           */

          <AccountsPage businessId={businessId} />

        )}

        {/* ==================================================== */}
        {/* TRANSACTIONS */}
        {/* ==================================================== */}

        {activePage === 'transactions' && (

          /*
           * Create, review, and post journal entries.
           *
           * All account activity is scoped to
           * the selected business.
           */

          <TransactionsPage businessId={businessId} />

        )}

        {/* ==================================================== */}
        {/* TRIAL BALANCE */}
        {/* ==================================================== */}

        {activePage === 'trial-balance' && (

          /*
           * Review debit and credit balances
           * from posted general ledger entries.
           */

          <TrialBalancePage businessId={businessId} />

        )}

        {/* ==================================================== */}
        {/* INCOME STATEMENT */}
        {/* ==================================================== */}

        {activePage === 'income-statement' && (

          /*
           * Review revenue, expenses, and net income
           * over an inclusive reporting date range.
           *
           * Spring Boot calculates all financial totals.
           */

          <IncomeStatementPage businessId={businessId} />

        )}

        {/* ==================================================== */}
        {/* BALANCE SHEET */}
        {/* ==================================================== */}

        {activePage === 'balance-sheet' && (

          /*
           * Review the business's financial position
           * as of a selected date.
           *
           * Includes:
           *
           * - Assets
           * - Liabilities
           * - Posted Equity
           * - Unclosed Earnings
           * - Accounting equation reconciliation
           */

          <BalanceSheetPage businessId={businessId} />

        )}

        {/* ==================================================== */}
        {/* FISCAL YEARS & ACCOUNTING PERIODS */}
        {/* ==================================================== */}

        {activePage === 'fiscal-years' && (

          /*
           * Manage the business's accounting calendar.
           *
           * actorId=1 is still a development placeholder.
           *
           * Authentication will provide this value
           * in a future milestone.
           */

          <FiscalYearsPage
            businessId={businessId}
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
