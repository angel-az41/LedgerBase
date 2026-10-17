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

import AccountsPage from './pages/AccountsPage'
import FiscalYearsPage from './pages/FiscalYearsPage'
import TransactionsPage from './pages/TransactionsPage'
import TrialBalancePage from './pages/TrialBalancePage'
import IncomeStatementPage from './pages/IncomeStatementPage'
import BalanceSheetPage from './pages/BalanceSheetPage'

// ============================================================
// LEDGERBASE — APPLICATION TYPES
// ============================================================

/**
 * Define every available page in LedgerBase.
 *
 * TypeScript restricts navigation to valid page identifiers.
 *
 * The application currently uses React state for navigation.
 * We do not need React Router for this MVP.
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
// SIDEBAR NAVIGATION ITEM
// ============================================================

/**
 * Represents one clickable sidebar item.
 *
 * id:
 *   Identifies the page to display.
 *
 * label:
 *   Text displayed in the sidebar.
 *
 * icon:
 *   Lucide React icon rendered beside the label.
 */
interface NavigationItem {
  id: Page
  label: string
  icon: ReactNode
}


// ============================================================
// SIDEBAR NAVIGATION GROUP
// ============================================================

/**
 * Represents a group of related navigation items.
 *
 * Previously, all LedgerBase pages appeared under
 * a single WORKSPACE heading.
 *
 * As our application grows, it is better to separate:
 *
 * 1. Workspace
 * 2. Accounting
 * 3. Financial Reports
 *
 * Each group has:
 *
 * - A visible section heading.
 * - An accessible label for screen readers.
 * - Its own collection of navigation items.
 *
 * IMPORTANT:
 *
 * These groups only organize the sidebar.
 *
 * They do not change:
 *
 * - The selected page state.
 * - Our existing page components.
 * - Backend API calls.
 * - Business IDs.
 * - Application routing behavior.
 */
interface NavigationGroup {
  heading: string
  accessibleLabel: string
  items: NavigationItem[]
}


// ============================================================
// GROUPED NAVIGATION CONFIGURATION
// ============================================================

/**
 * Organize LedgerBase's navigation into three sections.
 *
 * Each navigation item continues using:
 *
 * - Icon size: 18
 * - Stroke width: 1.8
 *
 * All active, hover, and normal states still come
 * from the existing .navigation-item CSS classes.
 *
 * No changes to individual icon styling are required.
 */
const navigationGroups: NavigationGroup[] = [

  // ==========================================================
  // GROUP 1 — WORKSPACE
  // ==========================================================

  /**
   * The Workspace group contains the application's
   * main landing page.
   *
   * Future additions might include:
   *
   * - Dashboard
   * - Notifications
   * - Recent Activity
   *
   * For now, Overview is the only page in this group.
   */
  {
    heading: 'WORKSPACE',
    accessibleLabel: 'Workspace',

    items: [

      // ======================================================
      // OVERVIEW
      // ======================================================

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
  // GROUP 2 — ACCOUNTING
  // ==========================================================

  /**
   * Accounting contains day-to-day financial operations.
   *
   * Recommended order:
   *
   * 1. Transactions
   * 2. Chart of Accounts
   * 3. Fiscal Years & Periods
   *
   * Transactions appears first because creating,
   * reviewing, and posting financial entries is
   * a common accounting workflow.
   *
   * Chart of Accounts provides the account structure
   * used by those transactions.
   *
   * Fiscal Years & Periods controls the accounting
   * calendar and when entries may be posted.
   */
  {
    heading: 'ACCOUNTING',
    accessibleLabel: 'Accounting',

    items: [

      // ======================================================
      // TRANSACTIONS
      // ======================================================

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


      // ======================================================
      // CHART OF ACCOUNTS
      // ======================================================

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


      // ======================================================
      // FISCAL YEARS AND ACCOUNTING PERIODS
      // ======================================================

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
  // GROUP 3 — FINANCIAL REPORTS
  // ==========================================================

  /**
   * Financial Reports contains read-only reports
   * generated from posted general ledger entries.
   *
   * Recommended order:
   *
   * 1. Trial Balance
   * 2. Income Statement
   * 3. Balance Sheet
   *
   * Trial Balance helps verify account balances.
   *
   * Income Statement explains revenue, expenses,
   * and net income over a reporting period.
   *
   * Balance Sheet shows assets, liabilities,
   * and equity as of a selected date.
   *
   * All monetary calculations remain in PostgreSQL
   * and Spring Boot, not in the sidebar.
   */
  {
    heading: 'FINANCIAL REPORTS',
    accessibleLabel: 'Financial Reports',

    items: [

      // ======================================================
      // TRIAL BALANCE
      // ======================================================

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


      // ======================================================
      // INCOME STATEMENT
      // ======================================================

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


      // ======================================================
      // BALANCE SHEET
      // ======================================================

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
// APPLICATION COMPONENT
// ============================================================

/**
 * App is the main component for LedgerBase.
 *
 * React's useState hook stores the currently selected page.
 *
 * Clicking a navigation item updates activePage.
 *
 * React then displays the corresponding component
 * without reloading the browser.
 *
 * IMPORTANT:
 *
 * Sidebar grouping changes only how the navigation
 * items are organized visually.
 *
 * All page selection and rendering logic remains
 * the same as before.
 *
 * Business ID 1 is our current local development
 * business until authenticated business selection
 * is introduced.
 */
function App() {

  // ============================================================
  // CURRENTLY SELECTED PAGE
  // ============================================================

  /**
   * Start LedgerBase on its Overview page.
   *
   * activePage:
   *   The currently selected navigation page.
   *
   * setActivePage:
   *   Updates that selected page.
   */
  const [activePage, setActivePage] =
    useState<Page>('overview')


  // ============================================================
  // PAGE INFORMATION
  // ============================================================

  /**
   * Store each page's title and description.
   *
   * Record<Page, ...> requires every Page identifier
   * to have a corresponding definition.
   *
   * Some pages display the shared application header.
   *
   * Financial reports and Fiscal Years have their own
   * headings and therefore hide the shared header.
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
        {/* GROUPED SIDEBAR NAVIGATION */}
        {/* ==================================================== */}

        {/*
          Previously, we rendered one flat array:

          navigationItems.map(...)

          That meant all pages appeared beneath the
          same WORKSPACE heading.

          Now we render two levels:

          navigationGroups.map(...)
              |
              v
          group.items.map(...)

          Each group renders:

          1. Its category heading.
          2. Its corresponding navigation buttons.

          Existing navigation-item styling is preserved.

          The new navigation-group class only controls
          grouping and spacing.
        */}

        <nav
          className="sidebar-navigation"
          aria-label="Main navigation"
        >

          {/* ================================================== */}
          {/* RENDER NAVIGATION GROUPS */}
          {/* ================================================== */}

          {navigationGroups.map((group) => (

            <div
              key={group.heading}
              className="navigation-group"
              role="group"
              aria-label={group.accessibleLabel}
            >

              {/* ============================================== */}
              {/* GROUP HEADING */}
              {/* ============================================== */}

              {/*
                Reuse the existing navigation-label class.

                This keeps the headings consistent with
                the original WORKSPACE label.
              */}

              <p
                className="navigation-label"
                aria-hidden="true"
              >
                {group.heading}
              </p>


              {/* ============================================== */}
              {/* NAVIGATION ITEMS INSIDE THIS GROUP */}
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

                  {/* ========================================== */}
                  {/* NAVIGATION ICON */}
                  {/* ========================================== */}

                  <span
                    className="navigation-icon"
                    aria-hidden="true"
                  >
                    {item.icon}
                  </span>


                  {/* ========================================== */}
                  {/* NAVIGATION LABEL */}
                  {/* ========================================== */}

                  <span>{item.label}</span>

                </button>

              ))}

            </div>

          ))}

        </nav>


        {/* ==================================================== */}
        {/* DEVELOPMENT ENVIRONMENT INFORMATION */}
        {/* ==================================================== */}

        {/*
          Preserve the existing development indicator.

          Later, this footer can be extended with
          authenticated user and business information.
        */}

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
          The shared page header appears for:

          - Overview
          - Chart of Accounts
          - Transactions

          These pages use the application-level heading.

          The following pages render their own heading:

          - Trial Balance
          - Income Statement
          - Balance Sheet
          - Fiscal Years & Periods

          We hide the shared header for those pages
          to prevent duplicate titles.

          Sidebar grouping does not affect this behavior.
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
           * Display the Chart of Accounts.
           *
           * Includes:
           *
           * - Account types
           * - Reporting classifications
           * - Parent-child account hierarchy
           * - Postable and grouping accounts
           *
           * Business ID 1 is the local demo business.
           */

          <AccountsPage businessId={1} />

        )}


        {/* ==================================================== */}
        {/* TRANSACTIONS PAGE */}
        {/* ==================================================== */}

        {activePage === 'transactions' && (

          /*
           * Display the Transaction Register.
           *
           * Users can create, review, and post
           * accounting transactions.
           *
           * Business ID 1 is the local demo business.
           */

          <TransactionsPage businessId={1} />

        )}


        {/* ==================================================== */}
        {/* TRIAL BALANCE REPORT PAGE */}
        {/* ==================================================== */}

        {activePage === 'trial-balance' && (

          /*
           * Display the Trial Balance.
           *
           * Features:
           *
           * - As-of reporting date
           * - Actual posted account balances
           * - Debit and credit columns
           * - Accounting totals
           * - Balanced/unbalanced indicator
           *
           * Calculations are performed by Spring Boot.
           */

          <TrialBalancePage businessId={1} />

        )}


        {/* ==================================================== */}
        {/* INCOME STATEMENT REPORT PAGE */}
        {/* ==================================================== */}

        {activePage === 'income-statement' && (

          /*
           * Display the Income Statement.
           *
           * Features:
           *
           * - Start and end dates
           * - Revenue and expense reporting groups
           * - Account-level financial activity
           * - Reporting group subtotals
           * - Net income
           * - Classification warnings
           *
           * Calculations are performed by Spring Boot.
           */

          <IncomeStatementPage businessId={1} />

        )}


        {/* ==================================================== */}
        {/* BALANCE SHEET REPORT PAGE */}
        {/* ==================================================== */}

        {activePage === 'balance-sheet' && (

          /*
           * Display the Balance Sheet.
           *
           * Features:
           *
           * - As-of reporting date
           * - Current and non-current assets
           * - Current and non-current liabilities
           * - Contributed capital
           * - Retained earnings
           * - Other equity
           * - Unclosed earnings
           * - Accounting equation reconciliation
           * - Classification warnings
           *
           * Assets = Liabilities + Equity
           *
           * All amounts come from the Spring Boot API.
           *
           * The page never calculates financial totals
           * using JavaScript floating-point arithmetic.
           */

          <BalanceSheetPage businessId={1} />

        )}


        {/* ==================================================== */}
        {/* FISCAL YEARS & ACCOUNTING PERIODS PAGE */}
        {/* ==================================================== */}

        {activePage === 'fiscal-years' && (

          /*
           * Display fiscal-year management.
           *
           * Features:
           *
           * - Fiscal year configuration
           * - Monthly accounting periods
           * - Period closing and reopening
           * - Accounting period audit records
           *
           * Business ID 1 and Actor ID 1 are temporary
           * local-development values.
           *
           * Authentication and authorization will
           * eventually supply these values securely.
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
