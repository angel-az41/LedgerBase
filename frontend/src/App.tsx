import { useState } from 'react'
import './App.css'
import AccountsPage from './pages/AccountsPage'
import TransactionsPage from './pages/TransactionsPage'

// ============================================================
// LEDGERBASE — APPLICATION TYPES
// ============================================================

/**
 * Define the available pages in our application.
 *
 * TypeScript prevents us from accidentally navigating
 * to a page that does not exist.
 */
type Page = 'overview' | 'accounts' | 'transactions'

/**
 * Define the structure of a sidebar navigation item.
 */
interface NavigationItem {
  id: Page
  label: string
  icon: string
}

// ============================================================
// NAVIGATION CONFIGURATION
// ============================================================

/**
 * These items appear in the LedgerBase sidebar.
 *
 * Later, we can introduce React Router to support
 * individual URLs for each application page.
 */
const navigationItems: NavigationItem[] = [
  {
    id: 'overview',
    label: 'Overview',
    icon: '▦',
  },
  {
    id: 'accounts',
    label: 'Chart of Accounts',
    icon: '▤',
  },
  {
    id: 'transactions',
    label: 'Transactions',
    icon: '⇄',
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
 */
function App() {

  // ============================================================
  // REACT STATE
  // ============================================================

  /**
   * Start on the Overview page.
   *
   * activePage: current state value
   * setActivePage: function used to update the value
   */
  const [activePage, setActivePage] =
    useState<Page>('overview')

  // ============================================================
  // PAGE INFORMATION
  // ============================================================

  /**
   * Display different headings depending on the
   * selected navigation item.
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

        {/* Application branding */}
        <div className="sidebar-brand">

          {/* ====================================================== */}
          {/* LEDGERBASE BRAND LOGO */}
          {/* ====================================================== */}

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

        {/* Navigation items */}
        <nav className="sidebar-navigation" aria-label="Main navigation">

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

              <span className="navigation-icon" aria-hidden="true">
                {item.icon}
              </span>

              <span>{item.label}</span>

            </button>

          ))}

        </nav>

        {/* Development environment information */}
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
        {/* PAGE HEADER */}
        {/* ==================================================== */}

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

        {/* ==================================================== */}
        {/* OVERVIEW PAGE */}
        {/* ==================================================== */}

        {activePage === 'overview' && (

          <div className="overview-page">

            {/* Welcome section */}
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

            {/* Financial metrics */}
            <section className="overview-metrics">

              <article className="metric-card">
                <p>Total Assets</p>
                <strong>—</strong>
                <span>Available after financial reporting</span>
              </article>

              <article className="metric-card">
                <p>Total Liabilities</p>
                <strong>—</strong>
                <span>Available after financial reporting</span>
              </article>

              <article className="metric-card">
                <p>Net Income</p>
                <strong>—</strong>
                <span>Available after financial reporting</span>
              </article>

            </section>

            {/* Quick navigation */}
            <section className="quick-access">

              <div className="section-heading">
                <h2>Quick Access</h2>
                <p>Continue working in your accounting workspace.</p>
              </div>

              <div className="quick-access-grid">

                <button
                  type="button"
                  className="quick-access-card"
                  onClick={() => setActivePage('accounts')}
                >

                  <span className="quick-access-icon">▤</span>

                  <strong>Chart of Accounts</strong>

                  <span>
                    View and organize your general ledger accounts.
                  </span>

                  <span className="quick-access-link">
                    Open accounts →
                  </span>

                </button>

                <button
                  type="button"
                  className="quick-access-card"
                  onClick={() => setActivePage('transactions')}
                >

                  <span className="quick-access-icon">⇄</span>

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

          /**
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

          /**
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

      </main>

    </div>

  )
}

// ============================================================
// EXPORT APPLICATION
// ============================================================

export default App
