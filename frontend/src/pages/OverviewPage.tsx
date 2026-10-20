import { useEffect, useState } from 'react'

import {
  getDashboardActivity,
  type DashboardActivity,
} from '../api/dashboardActivity'

import {
  getDashboardMetrics,
  type DashboardMetrics,
} from '../api/dashboard'

import './OverviewPage.css'

// ============================================================
// LEDGERBASE — OVERVIEW DASHBOARD
// ============================================================

/**
 * Displays the LedgerBase financial workspace dashboard.
 *
 * MAIN RESPONSIBILITIES:
 *
 * 1. Display a welcome section.
 * 2. Fetch real accounting metrics from Spring Boot.
 * 3. Display Total Assets and Total Liabilities.
 * 4. Display fiscal-year-to-date Net Income.
 * 5. Handle loading and API errors.
 * 6. Preserve the existing Quick Access navigation.
 *
 * IMPORTANT:
 *
 * All financial amounts come from the backend.
 *
 * This component never recalculates accounting
 * totals using JavaScript floating-point arithmetic.
 */

// ============================================================
// COMPONENT PROPERTIES
// ============================================================

interface OverviewPageProps {

  /**
   * Current business being viewed.
   *
   * App.tsx currently passes businessId={1}.
   */
  businessId: number

  /**
   * Callback used to navigate to an accounting page.
   *
   * The Overview page does not manage global navigation.
   * App.tsx remains responsible for activePage.
   */
  onNavigate: (page: 'accounts' | 'transactions') => void
}

// ============================================================
// DATE UTILITIES
// ============================================================

/**
 * Return today's local calendar date as YYYY-MM-DD.
 *
 * Avoid toISOString() because UTC conversion can
 * produce a different calendar date.
 */
function getTodayDate(): string {

  const today = new Date()

  const year = today.getFullYear()

  const month = String(
    today.getMonth() + 1,
  ).padStart(2, '0')

  const day = String(
    today.getDate(),
  ).padStart(2, '0')

  return `${year}-${month}-${day}`
}

/**
 * Display an ISO calendar date using a readable format.
 *
 * Example:
 *
 * 2026-10-17 -> October 17, 2026
 *
 * UTC formatting prevents a date-only value from
 * shifting to the previous day in some time zones.
 */
function formatDate(dateString: string): string {

  return new Intl.DateTimeFormat('en-US', {

    year: 'numeric',
    month: 'long',
    day: 'numeric',
    timeZone: 'UTC',

  }).format(
    new Date(`${dateString}T00:00:00Z`),
  )
}

// ============================================================
// PRECISION-SAFE MONEY FORMATTER
// ============================================================

/**
 * Format a decimal string without converting it
 * to a JavaScript Number.
 *
 * Examples:
 *
 * "12500.00" -> "$12,500.00"
 * "-500.00"  -> "−$500.00"
 * "0.00"     -> "$0.00"
 *
 * Spring Boot performs the financial calculations.
 * React performs only string formatting.
 */
function formatMoney(amount: string): string {

  const match =
    /^(-?)(\d+)(?:\.(\d{1,2}))?$/.exec(amount)

  if (!match) {
    return '—'
  }

  const isNegative = match[1] === '-'

  const wholePart = match[2]

  const decimalPart = (
    match[3] ?? ''
  ).padEnd(2, '0')

  const formattedWhole = wholePart.replace(
    /\B(?=(\d{3})+(?!\d))/g,
    ',',
  )

  return (
    `${isNegative ? '−' : ''}` +
    `$${formattedWhole}.${decimalPart}`
  )
}

// ============================================================
// OVERVIEW PAGE COMPONENT
// ============================================================

function OverviewPage({
  businessId,
  onNavigate,
}: OverviewPageProps) {

  // ==========================================================
  // FINANCIAL METRICS STATE
  // ==========================================================

  /**
   * Complete dashboard response.
   *
   * null indicates that the metrics have not yet
   * been successfully retrieved.
   */
  const [metrics, setMetrics] =
    useState<DashboardMetrics | null>(null)

  /**
   * Whether the dashboard is currently requesting data.
   */
  const [isLoading, setIsLoading] =
    useState(true)

  /**
   * Error message when the API request fails.
   */
  const [errorMessage, setErrorMessage] =
    useState('')

  /**
   * A refresh counter that triggers a new request.
   *
   * We do not need to modify business data merely
   * to refresh this read-only dashboard.
   */
  const [refreshVersion, setRefreshVersion] =
    useState(0)
  
  // ==========================================================
  // JOURNAL ACTIVITY STATE
  // ==========================================================

  /**
   * Store the operational journal metrics retrieved
   * from the existing Transactions API.
   *
   * null means that activity data is unavailable.
   *
   * This is different from having zero transactions.
   */
  const [activity, setActivity] =
    useState<DashboardActivity | null>(null)

  /**
   * Loading journal activity is independent of
   * loading financial metrics.
   *
   * A transaction API problem must not prevent
   * the financial dashboard from rendering.
   */
  const [activityLoading, setActivityLoading] =
    useState(true)

  /**
   * Store errors from journal activity requests
   * separately from financial reporting errors.
   */
  const [activityError, setActivityError] =
    useState('')

  // ==========================================================
  // LOAD DASHBOARD METRICS
  // ==========================================================

  /**
   * Request the latest financial data when:
   *
   * - The component is first mounted.
   * - The business changes.
   * - The user clicks Refresh.
   *
   * The dashboard uses today's date for its snapshot.
   *
   * getDashboardMetrics automatically:
   *
   * 1. Requests the Balance Sheet.
   * 2. Retrieves configured fiscal years.
   * 3. Finds the year containing today's date.
   * 4. Retrieves fiscal-year-to-date Net Income.
   */
  useEffect(() => {

    let cancelled = false

    async function loadDashboard(): Promise<void> {

      setIsLoading(true)
      setErrorMessage('')
      setMetrics(null)

      try {

        const result = await getDashboardMetrics(
          businessId,
          getTodayDate(),
        )

        /**
         * Do not update the component when an older
         * request has already been cancelled.
         */
        if (!cancelled) {
          setMetrics(result)
        }

      } catch (error: unknown) {

        if (!cancelled) {

          setErrorMessage(
            error instanceof Error
              ? error.message
              : 'Unable to retrieve financial metrics.',
          )

        }

      } finally {

        if (!cancelled) {
          setIsLoading(false)
        }

      }
    }

    void loadDashboard()

    /**
     * Cleanup prevents an outdated request from
     * overwriting the results of a newer request.
     */
    return () => {
      cancelled = true
    }

  }, [businessId, refreshVersion])

  // ==========================================================
  // LOAD JOURNAL ACTIVITY
  // ==========================================================

  /**
   * Retrieve recent journal information when:
   *
   * 1. The Overview page first loads.
   * 2. The selected business changes.
   * 3. The user clicks Refresh.
   *
   * This effect runs independently from the
   * financial reporting request.
   *
   * It uses the same refreshVersion mechanism
   * to keep the dashboard synchronized.
   */
  useEffect(() => {

    // ========================================================
    // TRACK OUTDATED REQUESTS
    // ========================================================

    let cancelled = false

    // ========================================================
    // ASYNCHRONOUS ACTIVITY LOADER
    // ========================================================

    async function loadActivity(): Promise<void> {

      setActivityLoading(true)
      setActivityError('')
      setActivity(null)

      try {

        // ====================================================
        // RETRIEVE REAL JOURNAL ACTIVITY
        // ====================================================

        const result = await getDashboardActivity(
          businessId,
        )

        // ====================================================
        // UPDATE REACT STATE
        // ====================================================

        if (!cancelled) {
          setActivity(result)
        }

      } catch (error: unknown) {

        // ====================================================
        // HANDLE ACTIVITY ERRORS INDEPENDENTLY
        // ====================================================

        if (!cancelled) {

          setActivityError(
            error instanceof Error
              ? error.message
              : 'Unable to retrieve journal activity.',
          )

        }

      } finally {

        // ====================================================
        // FINISH LOADING
        // ====================================================

        if (!cancelled) {
          setActivityLoading(false)
        }

      }
    }

    void loadActivity()

    // ========================================================
    // CLEAN UP OUTDATED REQUESTS
    // ========================================================

    return () => {
      cancelled = true
    }

  }, [businessId, refreshVersion])

  // ==========================================================
  // REFRESH DASHBOARD
  // ==========================================================

  /**
   * Trigger a new read of the latest posted
   * accounting data without reloading the browser.
   */
  function handleRefresh(): void {

    setRefreshVersion(
      (previousVersion) => previousVersion + 1,
    )

  }

  // ==========================================================
  // DERIVED DISPLAY VALUES
  // ==========================================================

  /**
   * Preserve the distinction between:
   *
   * - Loading
   * - Unavailable data
   * - A legitimate $0.00 financial balance
   *
   * Displaying zero for a failed request would be
   * misleading in a financial application.
   */
  const assetsDisplay = isLoading
    ? 'Loading...'
    : metrics
      ? formatMoney(metrics.totalAssets)
      : '—'

  const liabilitiesDisplay = isLoading
    ? 'Loading...'
    : metrics
      ? formatMoney(metrics.totalLiabilities)
      : '—'

  const netIncomeDisplay = isLoading
    ? 'Loading...'
    : metrics?.netIncome !== null && metrics
      ? formatMoney(metrics.netIncome)
      : '—'

  /**
   * Reporting quality is separate from financial totals.
   *
   * A mathematically balanced Balance Sheet may still
   * contain accounts without reporting classifications.
   */
  const hasClassificationWarnings = metrics
    ? (
        !metrics.balanceSheetClassificationComplete ||
        metrics.incomeStatementClassificationComplete === false
      )
    : false

  // ==========================================================
  // RENDER OVERVIEW PAGE
  // ==========================================================

  return (

    <div className="overview-page">

      {/* ====================================================== */}
      {/* WELCOME SECTION */}
      {/* ====================================================== */}

      <section className="welcome-panel">

        <p className="welcome-eyebrow">
          FINANCIAL WORKSPACE
        </p>

        <h2>
          Your accounting workspace
        </h2>

        <p>
          Review financial performance, accounting health,
          and journal activity in one place.
        </p>

      </section>


      {/* ====================================================== */}
      {/* FINANCIAL SNAPSHOT HEADING */}
      {/* ====================================================== */}

      <div className="overview-financial-heading">

        <div>

          <h2>Financial Overview</h2>

          <p>
            {metrics
              ? `Financial position as of ${formatDate(metrics.asOfDate)}`
              : 'Your latest financial position and performance.'}
          </p>

        </div>

        <button
          type="button"
          className="overview-refresh-button"
          onClick={handleRefresh}
          disabled={isLoading}
        >
          {isLoading ? 'Loading...' : 'Refresh'}
        </button>

      </div>


      {/* ====================================================== */}
      {/* FINANCIAL METRICS */}
      {/* ====================================================== */}

      {/*
        Preserve the existing overview-metrics and
        metric-card classes from App.css.

        This keeps the three cards consistent with
        your existing LedgerBase design.
      */}

      <section
        className="overview-metrics"
        aria-label="Financial metrics"
        aria-busy={isLoading}
      >

        {/* ==================================================== */}
        {/* TOTAL ASSETS */}
        {/* ==================================================== */}

        <article className="metric-card">

          <p>Total Assets</p>

          <strong>{assetsDisplay}</strong>

          <span>
            {metrics
              ? `As of ${formatDate(metrics.asOfDate)}`
              : 'As of reporting date'}
          </span>

        </article>


        {/* ==================================================== */}
        {/* TOTAL LIABILITIES */}
        {/* ==================================================== */}

        <article className="metric-card">

          <p>Total Liabilities</p>

          <strong>{liabilitiesDisplay}</strong>

          <span>
            {metrics
              ? `As of ${formatDate(metrics.asOfDate)}`
              : 'As of reporting date'}
          </span>

        </article>


        {/* ==================================================== */}
        {/* NET INCOME */}
        {/* ==================================================== */}

        <article className="metric-card">

          <p>Net Income</p>

          <strong>{netIncomeDisplay}</strong>

          <span>
            {metrics?.fiscalYearLabel &&
            metrics.fiscalYearStartDate
              ? (
                  `${metrics.fiscalYearLabel} · ` +
                  `${formatDate(metrics.fiscalYearStartDate)} to ` +
                  `${formatDate(metrics.asOfDate)}`
                )
              : metrics && metrics.netIncome === null
                ? 'No fiscal year configured for this date'
                : 'Fiscal year to date'}
          </span>

        </article>

      </section>


      {/* ====================================================== */}
      {/* ERROR STATE */}
      {/* ====================================================== */}

      {/*
        Show API errors explicitly.

        Never replace unavailable financial data
        with fabricated or default monetary values.
      */}

      {!isLoading && errorMessage && (

        <div
          className="overview-notice overview-notice-error"
          role="alert"
        >

          <strong>
            Financial metrics unavailable
          </strong>

          <p>{errorMessage}</p>

          <button
            type="button"
            onClick={handleRefresh}
          >
            Try Again
          </button>

        </div>

      )}


      {/* ====================================================== */}
      {/* MISSING FISCAL YEAR NOTICE */}
      {/* ====================================================== */}

      {metrics && metrics.netIncome === null && (

        <div
          className="overview-notice overview-notice-info"
          role="status"
        >

          <strong>
            Fiscal-year reporting is not configured
          </strong>

          <p>
            Total Assets and Total Liabilities are
            available, but Net Income requires a
            fiscal year covering the reporting date.
            Configure a fiscal year under
            Fiscal Years &amp; Periods.
          </p>

        </div>

      )}


      {/* ====================================================== */}
      {/* BALANCE SHEET RECONCILIATION WARNING */}
      {/* ====================================================== */}

      {metrics && !metrics.balanceSheetBalanced && (

        <div
          className="overview-notice overview-notice-warning"
          role="alert"
        >

          <strong>
            Accounting equation requires attention
          </strong>

          <p>
            The Balance Sheet does not currently
            reconcile. Review the Balance Sheet
            report for the exact difference.
          </p>

        </div>

      )}


      {/* ====================================================== */}
      {/* REPORTING CLASSIFICATION WARNING */}
      {/* ====================================================== */}

      {hasClassificationWarnings && (

        <div
          className="overview-notice overview-notice-warning"
          role="status"
        >

          <strong>
            Reporting classification requires attention
          </strong>

          <p>
            Some posted accounts have not been assigned
            financial reporting groups. Financial totals
            include this activity, but the affected
            accounts should be reviewed in the
            Chart of Accounts.
          </p>

        </div>

      )}

      {/* ====================================================== */}
      {/* ACCOUNTING HEALTH */}
      {/* ====================================================== */}

      {/*
        Display accounting quality checks already returned
        by the LedgerBase financial reporting APIs.

        These indicators come directly from Spring Boot.

        We do not independently recalculate:
        - The accounting equation
        - Reporting classification completeness
        - Financial statement balances

        When metrics are unavailable, the section is hidden
        rather than displaying misleading success statuses.
      */}

      {metrics && (

        <section
          className="overview-health"
          aria-label="Accounting health"
        >

          {/* ================================================== */}
          {/* SECTION HEADING */}
          {/* ================================================== */}

          <div className="overview-health-heading">

            <h2>Accounting Health</h2>

            <p>
              Financial reporting checks as of{' '}
              {formatDate(metrics.asOfDate)}.
            </p>

          </div>

          {/* ================================================== */}
          {/* HEALTH CHECK GRID */}
          {/* ================================================== */}

          <div className="overview-health-grid">

            {/* ================================================ */}
            {/* BALANCE SHEET RECONCILIATION */}
            {/* ================================================ */}

            <div className="overview-health-item">

              <div className="overview-health-copy">

                <strong>
                  Balance Sheet Reconciliation
                </strong>

                <span>
                  Assets = Liabilities + Equity
                </span>

              </div>

              <span
                className={
                  metrics.balanceSheetBalanced
                    ? 'overview-health-status is-healthy'
                    : 'overview-health-status is-warning'
                }
              >
                {metrics.balanceSheetBalanced
                  ? 'Balanced'
                  : 'Needs Review'}
              </span>

            </div>

            {/* ================================================ */}
            {/* BALANCE SHEET CLASSIFICATION */}
            {/* ================================================ */}

            <div className="overview-health-item">

              <div className="overview-health-copy">

                <strong>
                  Balance Sheet Classification
                </strong>

                <span>
                  Posted accounts assigned reporting groups
                </span>

              </div>

              <span
                className={
                  metrics.balanceSheetClassificationComplete
                    ? 'overview-health-status is-healthy'
                    : 'overview-health-status is-warning'
                }
              >
                {metrics.balanceSheetClassificationComplete
                  ? 'Complete'
                  : 'Needs Review'}
              </span>

            </div>

            {/* ================================================ */}
            {/* INCOME STATEMENT CLASSIFICATION */}
            {/* ================================================ */}

            <div className="overview-health-item">

              <div className="overview-health-copy">

                <strong>
                  Income Statement Classification
                </strong>

                <span>
                  {metrics.fiscalYearLabel
                    ?? 'No fiscal year configured'}
                </span>

              </div>

              <span
                className={
                  metrics.incomeStatementClassificationComplete === null
                    ? 'overview-health-status is-unavailable'
                    : metrics.incomeStatementClassificationComplete
                      ? 'overview-health-status is-healthy'
                      : 'overview-health-status is-warning'
                }
              >
                {metrics.incomeStatementClassificationComplete === null
                  ? 'Unavailable'
                  : metrics.incomeStatementClassificationComplete
                    ? 'Complete'
                    : 'Needs Review'}
              </span>

            </div>

          </div>

        </section>

      )}

      {/* ====================================================== */}
      {/* JOURNAL ACTIVITY */}
      {/* ====================================================== */}

      <section
        className="overview-activity"
        aria-label="Journal activity"
        aria-busy={activityLoading}
      >

        {/* ==================================================== */}
        {/* SECTION HEADING */}
        {/* ==================================================== */}

        <div className="overview-activity-heading">

          <div>

            <h2>Journal Activity</h2>

            <p>
              Monitor recent journal entries and posting
              activity for the selected business.
            </p>

          </div>

          <button
            type="button"
            className="overview-activity-link"
            onClick={() => onNavigate('transactions')}
          >
            View all transactions →
          </button>

        </div>

        {/* ==================================================== */}
        {/* JOURNAL STATISTICS */}
        {/* ==================================================== */}

        <div className="overview-activity-metrics">

          {/* ================================================== */}
          {/* TOTAL JOURNALS */}
          {/* ================================================== */}

          <article className="overview-activity-card">

            <p>Total Journals</p>

            <strong>
              {activityLoading
                ? 'Loading...'
                : activity
                  ? activity.totalTransactions
                  : '—'}
            </strong>

            <span>
              All journal records
            </span>

          </article>

          {/* ================================================== */}
          {/* POSTED JOURNALS */}
          {/* ================================================== */}

          <article className="overview-activity-card">

            <p>Posted Journals</p>

            <strong>
              {activityLoading
                ? 'Loading...'
                : activity
                  ? activity.postedTransactions
                  : '—'}
            </strong>

            <span>
              Finalized accounting entries
            </span>

          </article>

          {/* ================================================== */}
          {/* DRAFT JOURNALS */}
          {/* ================================================== */}

          <article className="overview-activity-card">

            <p>Draft Journals</p>

            <strong>
              {activityLoading
                ? 'Loading...'
                : activity
                  ? activity.draftTransactions
                  : '—'}
            </strong>

            <span>
              Awaiting posting
            </span>

          </article>

        </div>

        {/* ==================================================== */}
        {/* ACTIVITY ERROR STATE */}
        {/* ==================================================== */}

        {!activityLoading && activityError && (

          <div
            className="overview-notice overview-notice-error"
            role="alert"
          >

            <strong>
              Journal activity unavailable
            </strong>

            <p>{activityError}</p>

            <button
              type="button"
              onClick={handleRefresh}
            >
              Try Again
            </button>

          </div>

        )}

        {/* ==================================================== */}
        {/* RECENT JOURNALS CONTAINER */}
        {/* ==================================================== */}

        <div className="overview-recent-journals">

          <div className="overview-recent-heading">

            <h3>Recent Journal Entries</h3>

            <p>
              The five most recently created journals.
            </p>

          </div>

          {/* ================================================== */}
          {/* LOADING STATE */}
          {/* ================================================== */}

          {activityLoading && (

            <p
              className="overview-activity-message"
              role="status"
            >
              Loading recent journal activity...
            </p>

          )}

          {/* ================================================== */}
          {/* EMPTY STATE */}
          {/* ================================================== */}

          {!activityLoading &&
            activity &&
            activity.recentTransactions.length === 0 && (

              <p className="overview-activity-message">
                No journal entries have been created yet.
              </p>

            )}

          {/* ================================================== */}
          {/* RECENT JOURNAL TABLE */}
          {/* ================================================== */}

          {!activityLoading &&
            activity &&
            activity.recentTransactions.length > 0 && (

              <div className="overview-recent-table-scroll">

                <table className="overview-recent-table">

                  <thead>

                    <tr>
                      <th scope="col">
                        Journal
                      </th>

                      <th scope="col">
                        Description
                      </th>

                      <th scope="col">
                        Accounting Date
                      </th>

                      <th scope="col">
                        Status
                      </th>
                    </tr>

                  </thead>

                  <tbody>

                    {activity.recentTransactions.map(
                      (transaction) => (

                        <tr key={transaction.id}>

                          {/* ================================ */}
                          {/* JOURNAL REFERENCE */}
                          {/* ================================ */}

                          <td className="overview-journal-reference">

                            {transaction.journalReference
                              ?? 'Reference unavailable'}

                          </td>

                          {/* ================================ */}
                          {/* JOURNAL DESCRIPTION */}
                          {/* ================================ */}

                          <td>
                            {transaction.description}
                          </td>

                          {/* ================================ */}
                          {/* ACCOUNTING DATE */}
                          {/* ================================ */}

                          <td>
                            {formatDate(
                              transaction.transactionDate,
                            )}
                          </td>

                          {/* ================================ */}
                          {/* POSTING STATUS */}
                          {/* ================================ */}

                          <td>

                            <span
                              className={
                                transaction.status === 'POSTED'
                                  ? 'overview-journal-status is-posted'
                                  : transaction.status === 'DRAFT'
                                    ? 'overview-journal-status is-draft'
                                    : 'overview-journal-status'
                              }
                            >

                              {transaction.status}

                            </span>

                          </td>

                        </tr>

                      ),
                    )}

                  </tbody>

                </table>

              </div>

            )}

        </div>

      </section>

      {/* ====================================================== */}
      {/* QUICK ACCESS */}
      {/* ====================================================== */}

      {/*
        Preserve the two existing Quick Access cards.

        Navigation is delegated to App.tsx using
        the onNavigate callback.
      */}

      <section className="quick-access">

        <div className="section-heading">

          <h2>Quick Access</h2>

          <p>
            Continue working in your accounting workspace.
          </p>

        </div>


        <div className="quick-access-grid">

          {/* ================================================== */}
          {/* CHART OF ACCOUNTS */}
          {/* ================================================== */}

          <button
            type="button"
            className="quick-access-card"
            onClick={() => onNavigate('accounts')}
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


          {/* ================================================== */}
          {/* TRANSACTIONS */}
          {/* ================================================== */}

          <button
            type="button"
            className="quick-access-card"
            onClick={() => onNavigate('transactions')}
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

  )
}

// ============================================================
// EXPORT PAGE
// ============================================================

export default OverviewPage
