import { useEffect, useState } from 'react'

import {
  getTrialBalance,
  type TrialBalanceReport,
} from '../api/trialBalance'

import './TrialBalancePage.css'

// ============================================================
// LEDGERBASE — TRIAL BALANCE PAGE
// ============================================================

/**
 * Display a date-filtered Trial Balance financial report.
 *
 * This component communicates with the existing
 * Spring Boot reporting endpoint.
 *
 * Responsibilities:
 *
 * - Select a reporting date
 * - Request the Trial Balance
 * - Display account debit and credit balances
 * - Display report totals
 * - Display balanced/unbalanced status
 * - Handle loading, error, and empty states
 *
 * IMPORTANT:
 *
 * PostgreSQL and Spring Boot perform all accounting
 * calculations.
 *
 * React only displays and formats the results.
 *
 * We do not calculate monetary totals in JavaScript.
 */

// ============================================================
// COMPONENT PROPERTIES
// ============================================================

/**
 * The selected business comes from App.tsx.
 *
 * During local development, businessId is 1.
 *
 * Eventually, this will be supplied by
 * our authenticated business selection workflow.
 */
interface TrialBalancePageProps {
  businessId: number
}

// ============================================================
// REPORTING DATE HELPER
// ============================================================

/**
 * Return today's local calendar date as YYYY-MM-DD.
 *
 * We construct the date from local components
 * instead of using toISOString().
 *
 * toISOString() converts to UTC, which could
 * accidentally select yesterday or tomorrow
 * depending on the user's time zone.
 *
 * This date is only the initial selection.
 * Users can choose any other reporting date.
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

// ============================================================
// REPORTING DATE DISPLAY FORMATTER
// ============================================================

/**
 * Convert an ISO calendar date into a readable label.
 *
 * Example:
 *
 * "2026-10-13"
 *
 * becomes:
 *
 * "October 13, 2026"
 *
 * UTC formatting prevents date shifts caused
 * by the user's browser time zone.
 */
function formatReportingDate(
  dateString: string,
): string {

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
// ACCOUNT TYPE FORMATTER
// ============================================================

/**
 * Convert backend classifications into
 * human-readable accounting labels.
 *
 * Examples:
 *
 * "ASSET"   -> "Asset"
 * "REVENUE" -> "Revenue"
 */
function formatAccountType(
  accountType: string,
): string {

  const lowercaseType = accountType.toLowerCase()

  return lowercaseType.charAt(0).toUpperCase()
    + lowercaseType.slice(1).replaceAll('_', ' ')
}

// ============================================================
// PRECISION-SAFE MONEY FORMATTER
// ============================================================

/**
 * Format a decimal string as a USD amount.
 *
 * Examples:
 *
 * "500.00"     -> "$500.00"
 * "0"          -> "$0.00"
 * "-500.00"    -> "−$500.00"
 * "1234567.89" -> "$1,234,567.89"
 *
 * IMPORTANT:
 *
 * We never convert monetary strings to
 * JavaScript Number or parseFloat.
 *
 * This avoids introducing floating-point
 * precision errors into financial reporting.
 *
 * The current development business uses USD.
 * We will support business-specific currencies
 * in a later milestone.
 */
function formatMoney(
  amount: string,
): string {

  // ==========================================================
  // VALIDATE THE DECIMAL STRING
  // ==========================================================

  /**
   * Accept an optional negative sign,
   * an integer part, and up to two decimal digits.
   *
   * Reject malformed amounts rather than
   * silently displaying an incorrect value.
   */
  const match =
    /^(-?)(\d+)(?:\.(\d{1,2}))?$/.exec(amount)

  if (!match) {
    return '—'
  }

  // ==========================================================
  // EXTRACT THE DECIMAL COMPONENTS
  // ==========================================================

  const negative = match[1] === '-'

  const wholePart = match[2]

  const decimalPart = match[3] ?? ''

  // ==========================================================
  // FORMAT THOUSANDS SEPARATORS
  // ==========================================================

  /**
   * String replacement adds commas without
   * performing numerical conversion.
   */
  const formattedWhole = wholePart.replace(
    /\B(?=(\d{3})+(?!\d))/g,
    ',',
  )

  // ==========================================================
  // ENSURE EXACTLY TWO DISPLAY DECIMALS
  // ==========================================================

  const formattedDecimal =
    decimalPart.padEnd(2, '0')

  // ==========================================================
  // RETURN THE CURRENCY LABEL
  // ==========================================================

  const sign = negative ? '−' : ''

  return `${sign}$${formattedWhole}.${formattedDecimal}`
}

// ============================================================
// TRIAL BALANCE COMPONENT
// ============================================================

function TrialBalancePage({
  businessId,
}: TrialBalancePageProps) {

  // ============================================================
  // REPORTING DATE STATE
  // ============================================================

  /**
   * selectedDate:
   *
   * The date currently selected in the input.
   *
   * appliedDate:
   *
   * The date used to generate the displayed report.
   *
   * Keeping them separate means the report does not
   * change until the user clicks Generate Report.
   */
  const [selectedDate, setSelectedDate] =
    useState(getTodayDate)

  const [appliedDate, setAppliedDate] =
    useState(getTodayDate)

  // ============================================================
  // FINANCIAL REPORT STATE
  // ============================================================

  /**
   * Store the complete report returned by Spring Boot.
   *
   * null means that no report is currently available.
   */
  const [report, setReport] =
    useState<TrialBalanceReport | null>(null)

  // ============================================================
  // LOADING AND ERROR STATE
  // ============================================================

  const [isLoading, setIsLoading] = useState(true)

  const [errorMessage, setErrorMessage] = useState('')

  // ============================================================
  // REPORT REFRESH STATE
  // ============================================================

  /**
   * Increasing refreshVersion allows users
   * to regenerate a report for the same date.
   *
   * This is useful after new journal entries
   * have been posted.
   */
  const [refreshVersion, setRefreshVersion] = useState(0)

  // ============================================================
  // LOAD TRIAL BALANCE FROM SPRING BOOT
  // ============================================================

  /**
   * Request the financial report when:
   *
   * 1. The page first mounts.
   * 2. The selected business changes.
   * 3. The user generates a report.
   * 4. The report is refreshed for the same date.
   *
   * Cleanup prevents outdated requests from
   * updating state after the component changes.
   */
  useEffect(() => {

    let cancelled = false

    // ==========================================================
    // ASYNCHRONOUS REPORT LOADER
    // ==========================================================

    async function loadReport(): Promise<void> {

      // Begin loading a fresh report.
      setIsLoading(true)

      // Remove any previous request error.
      setErrorMessage('')

      // Do not display outdated balances while
      // a different report is being generated.
      setReport(null)

      try {

        // ======================================================
        // REQUEST THE REPORT FROM SPRING BOOT
        // ======================================================

        /**
         * Example:
         *
         * getTrialBalance(1, '2026-10-13')
         *
         * Calls:
         *
         * GET /api/v1/businesses/1/reports/trial-balance
         *     ?asOfDate=2026-10-13
         */
        const reportData = await getTrialBalance(
          businessId,
          appliedDate,
        )

        // ======================================================
        // UPDATE STATE ONLY FOR THE CURRENT REQUEST
        // ======================================================

        if (!cancelled) {

          setReport(reportData)
        }

      } catch (error: unknown) {

        // ======================================================
        // HANDLE REPORT REQUEST ERRORS
        // ======================================================

        if (!cancelled) {

          setErrorMessage(
            error instanceof Error
              ? error.message
              : 'Unable to generate the Trial Balance.',
          )
        }

      } finally {

        // ======================================================
        // FINISH LOADING
        // ======================================================

        if (!cancelled) {

          setIsLoading(false)
        }
      }
    }

    void loadReport()

    // ==========================================================
    // IGNORE OUTDATED REQUESTS
    // ==========================================================

    return () => {
      cancelled = true
    }

  }, [businessId, appliedDate, refreshVersion])

  // ============================================================
  // GENERATE REPORT HANDLER
  // ============================================================

  /**
   * Called when the user submits the date selector.
   *
   * Apply the selected date and trigger a fresh
   * report request.
   *
   * The refresh counter also allows the user to
   * regenerate a report for the same date.
   */
  function handleGenerateReport(): void {

    const today = getTodayDate()

    if (!selectedDate || selectedDate > today) {
      return
    }

    setAppliedDate(selectedDate)

    setRefreshVersion(
      (previousVersion) => previousVersion + 1,
    )
  }

  // ============================================================
  // RENDER TRIAL BALANCE REPORT
  // ============================================================

  return (

    <section
      className="tb-page"
      aria-busy={isLoading}
    >

      {/* ====================================================== */}
      {/* PAGE HEADING */}
      {/* ====================================================== */}

      <header className="tb-page-header">

        <div>

          <h1>Trial Balance</h1>

          <p>
            Review your general ledger balances
            and verify that total debits equal
            total credits.
          </p>

        </div>

      </header>

      {/* ====================================================== */}
      {/* REPORT CONTROLS */}
      {/* ====================================================== */}

      <section className="tb-controls-card">

        <div className="tb-controls-heading">

          <h2>Report Settings</h2>

          <p>
            Select a reporting date to generate
            an as-of Trial Balance.
          </p>

        </div>

        {/* ==================================================== */}
        {/* REPORTING DATE FORM */}
        {/* ==================================================== */}

        <form
          className="tb-controls-form"
          onSubmit={(event) => {

            event.preventDefault()

            handleGenerateReport()
          }}
        >

          <label className="tb-date-field">

            <span>As-of Date</span>

            <input
              type="date"
              value={selectedDate}
              max={getTodayDate()}
              required
              onChange={(event) =>
                setSelectedDate(event.target.value)
              }
            />

          </label>

          <button
            type="submit"
            className="tb-generate-button"
            disabled={isLoading || !selectedDate}
          >

            {isLoading
              ? 'Generating...'
              : 'Generate Report'
            }

          </button>

        </form>

      </section>

      {/* ====================================================== */}
      {/* REPORT TABLE CARD */}
      {/* ====================================================== */}

      <section className="tb-report-card">

        {/* ==================================================== */}
        {/* REPORT HEADING */}
        {/* ==================================================== */}

        <div className="tb-report-heading">

          <div>

            <h2>Trial Balance Report</h2>

            <p>
              {report
                ? `As of ${formatReportingDate(report.asOfDate)}`
                : 'General ledger account balances'
              }
            </p>

          </div>

          {/* ================================================== */}
          {/* BALANCED / UNBALANCED STATUS */}
          {/* ================================================== */}

          {report && (

            <span
              className={
                report.balanced
                  ? 'tb-balance-status is-balanced'
                  : 'tb-balance-status is-unbalanced'
              }
              role="status"
            >

              {report.balanced
                ? '✓ Balanced'
                : '⚠ Unbalanced'
              }

            </span>

          )}

        </div>

        {/* ==================================================== */}
        {/* LOADING STATE */}
        {/* ==================================================== */}

        {isLoading && (

          <div
            className="tb-message"
            role="status"
          >

            <div
              className="tb-spinner"
              aria-hidden="true"
            />

            <p>
              Generating Trial Balance from LedgerBase...
            </p>

          </div>

        )}

        {/* ==================================================== */}
        {/* ERROR STATE */}
        {/* ==================================================== */}

        {!isLoading && errorMessage && (

          <div
            className="tb-message tb-error-state"
            role="alert"
          >

            <h3>
              Unable to generate Trial Balance
            </h3>

            <p>
              {errorMessage}
            </p>

            <button
              type="button"
              className="tb-retry-button"
              onClick={handleGenerateReport}
            >
              Try Again
            </button>

          </div>

        )}

        {/* ==================================================== */}
        {/* REPORT RESULTS */}
        {/* ==================================================== */}

        {!isLoading && !errorMessage && report && (

          <>

            {/* ================================================== */}
            {/* EMPTY CHART OF ACCOUNTS */}
            {/* ================================================== */}

            {report.rows.length === 0 && (

              <div className="tb-message">

                <h3>No accounts to report</h3>

                <p>
                  Create your first general ledger
                  account to begin reporting.
                </p>

              </div>

            )}

            {/* ================================================== */}
            {/* TRIAL BALANCE TABLE */}
            {/* ================================================== */}

            {report.rows.length > 0 && (

              <div className="tb-table-scroll">

                <table className="tb-table">

                  <caption className="tb-visually-hidden">
                    Trial Balance as of{' '}
                    {formatReportingDate(report.asOfDate)}
                  </caption>

                  {/* ============================================ */}
                  {/* TABLE HEADINGS */}
                  {/* ============================================ */}

                  <thead>

                    <tr>

                      <th scope="col">
                        Account Code
                      </th>

                      <th scope="col">
                        Account Name
                      </th>

                      <th scope="col">
                        Account Type
                      </th>

                      <th
                        scope="col"
                        className="tb-money-column"
                      >
                        Debit
                      </th>

                      <th
                        scope="col"
                        className="tb-money-column"
                      >
                        Credit
                      </th>

                    </tr>

                  </thead>

                  {/* ============================================ */}
                  {/* ACCOUNT REPORT ROWS */}
                  {/* ============================================ */}

                  <tbody>

                    {report.rows.map((row) => (

                      <tr
                        key={row.accountId}
                        className={
                          row.active
                            ? ''
                            : 'tb-row-inactive'
                        }
                      >

                        {/* ====================================== */}
                        {/* ACCOUNT CODE */}
                        {/* ====================================== */}

                        <td className="tb-account-code">
                          {row.code}
                        </td>

                        {/* ====================================== */}
                        {/* ACCOUNT NAME */}
                        {/* ====================================== */}

                        <td className="tb-account-name">

                          {row.name}

                          {!row.active && (

                            <span className="tb-inactive-label">
                              Inactive
                            </span>

                          )}

                        </td>

                        {/* ====================================== */}
                        {/* ACCOUNT TYPE */}
                        {/* ====================================== */}

                        <td>
                          {formatAccountType(row.accountType)}
                        </td>

                        {/* ====================================== */}
                        {/* DEBIT BALANCE */}
                        {/* ====================================== */}

                        <td className="tb-money-column">
                          {formatMoney(row.debitBalance)}
                        </td>

                        {/* ====================================== */}
                        {/* CREDIT BALANCE */}
                        {/* ====================================== */}

                        <td className="tb-money-column">
                          {formatMoney(row.creditBalance)}
                        </td>

                      </tr>

                    ))}

                  </tbody>

                  {/* ============================================ */}
                  {/* REPORT TOTALS */}
                  {/* ============================================ */}

                  <tfoot>

                    <tr className="tb-totals-row">

                      <th
                        scope="row"
                        colSpan={3}
                      >
                        Total
                      </th>

                      <td className="tb-money-column">
                        {formatMoney(report.totalDebits)}
                      </td>

                      <td className="tb-money-column">
                        {formatMoney(report.totalCredits)}
                      </td>

                    </tr>

                  </tfoot>

                </table>

              </div>

            )}

            {/* ================================================== */}
            {/* REPORT BALANCING SUMMARY */}
            {/* ================================================== */}

            <div className="tb-report-summary">

              <div className="tb-summary-detail">

                <span>Balancing Difference</span>

                <strong>
                  {formatMoney(report.difference)}
                </strong>

              </div>

              <p className="tb-summary-note">

                {report.balanced
                  ? 'Total debit balances equal total credit balances.'
                  : 'Debit and credit totals do not agree. Review the underlying ledger entries.'
                }

              </p>

            </div>

            {/* ================================================== */}
            {/* REPORT FOOTER */}
            {/* ================================================== */}

            <div className="tb-report-footer">

              <span>
                {report.rows.length} accounts included
              </span>

              <span>
                Only posted transactions through{' '}
                {formatReportingDate(report.asOfDate)}
              </span>

            </div>

          </>

        )}

      </section>

    </section>

  )
}

// ============================================================
// EXPORT COMPONENT
// ============================================================

export default TrialBalancePage
