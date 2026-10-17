import { useEffect, useState } from 'react'

import {
  getIncomeStatement,
  type IncomeStatementReport,
} from '../api/incomeStatement'

import './TrialBalancePage.css'
import './IncomeStatementPage.css'

// ============================================================
// LEDGERBASE — INCOME STATEMENT PAGE
// ============================================================

/**
 * Displays an Income Statement for a selected date range.
 *
 * This component follows the design and interaction patterns
 * established by TrialBalancePage.tsx.
 *
 * Responsibilities:
 *
 * - Select an inclusive reporting date range
 * - Fetch actual financial data from Spring Boot
 * - Display revenue and expense reporting groups
 * - Display account-level financial activity
 * - Display backend-calculated subtotals and net income
 * - Identify accounts missing reporting classifications
 * - Handle loading, error, and empty-activity states
 *
 * IMPORTANT:
 *
 * PostgreSQL and Spring Boot calculate all financial totals.
 *
 * React only formats and displays monetary values.
 *
 * We never calculate accounting totals using JavaScript
 * floating-point numbers.
 */


// ============================================================
// COMPONENT PROPERTIES
// ============================================================

interface IncomeStatementPageProps {

  /**
   * The currently selected LedgerBase business.
   *
   * App.tsx supplies this value, just as it does
   * for the Trial Balance page.
   */
  businessId: number
}


// ============================================================
// REPORTING DATE HELPERS
// ============================================================

/**
 * Return today's local calendar date as YYYY-MM-DD.
 *
 * Avoid toISOString(), because it converts local
 * dates to UTC and can shift the calendar day.
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
 * Default the report to the beginning of the
 * current calendar month.
 *
 * This is only a convenient initial date.
 *
 * Users can select another reporting period,
 * including dates from previous fiscal years.
 */
function getMonthStartDate(): string {

  return `${getTodayDate().slice(0, 7)}-01`
}


/**
 * Convert YYYY-MM-DD into a readable date.
 *
 * Example:
 *
 * 2026-10-01 -> October 1, 2026
 *
 * UTC formatting ensures that the displayed
 * date does not shift with the browser timezone.
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
// PRECISION-SAFE MONEY FORMATTER
// ============================================================

/**
 * Format a decimal string as a USD amount.
 *
 * Examples:
 *
 * "12000.00" -> "$12,000.00"
 * "-125.50"  -> "−$125.50"
 * "0.00"     -> "$0.00"
 *
 * We deliberately avoid Number() and parseFloat().
 *
 * Formatting never changes the underlying
 * financial calculation.
 *
 * USD is the current development currency.
 * Business-specific currency formatting can
 * be introduced in a later milestone.
 */
function formatMoney(
  amount: string,
): string {

  // ==========================================================
  // VALIDATE THE DECIMAL STRING
  // ==========================================================

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
  // ADD THOUSANDS SEPARATORS WITHOUT NUMERICAL CONVERSION
  // ==========================================================

  const formattedWhole = wholePart.replace(
    /\B(?=(\d{3})+(?!\d))/g,
    ',',
  )

  const formattedDecimal =
    decimalPart.padEnd(2, '0')

  const sign = negative ? '−' : ''

  return `${sign}$${formattedWhole}.${formattedDecimal}`
}


// ============================================================
// INCOME STATEMENT COMPONENT
// ============================================================

function IncomeStatementPage({
  businessId,
}: IncomeStatementPageProps) {

  // ============================================================
  // SELECTED REPORTING DATES
  // ============================================================

  /**
   * These values reflect the current date inputs.
   *
   * Editing an input does not immediately reload
   * the financial statement.
   */
  const [selectedStartDate, setSelectedStartDate] =
    useState(getMonthStartDate)

  const [selectedEndDate, setSelectedEndDate] =
    useState(getTodayDate)


  // ============================================================
  // APPLIED REPORTING DATES
  // ============================================================

  /**
   * These values determine which report is fetched.
   *
   * They change only when the user generates
   * a new report.
   */
  const [appliedStartDate, setAppliedStartDate] =
    useState(getMonthStartDate)

  const [appliedEndDate, setAppliedEndDate] =
    useState(getTodayDate)


  // ============================================================
  // FINANCIAL REPORT STATE
  // ============================================================

  const [report, setReport] =
    useState<IncomeStatementReport | null>(null)


  // ============================================================
  // LOADING AND ERROR STATE
  // ============================================================

  const [isLoading, setIsLoading] =
    useState(true)

  const [errorMessage, setErrorMessage] =
    useState('')


  // ============================================================
  // REPORT REFRESH STATE
  // ============================================================

  /**
   * Allows the user to fetch fresh data even
   * when the selected dates have not changed.
   *
   * Useful after additional journal entries
   * have been posted.
   */
  const [refreshVersion, setRefreshVersion] =
    useState(0)


  // ============================================================
  // LOAD INCOME STATEMENT FROM SPRING BOOT
  // ============================================================

  useEffect(() => {

    let cancelled = false

    // ==========================================================
    // ASYNCHRONOUS REPORT LOADER
    // ==========================================================

    async function loadReport(): Promise<void> {

      setIsLoading(true)
      setErrorMessage('')
      setReport(null)

      try {

        // ======================================================
        // REQUEST ACTUAL POSTED FINANCIAL ACTIVITY
        // ======================================================

        const reportData = await getIncomeStatement(
          businessId,
          appliedStartDate,
          appliedEndDate,
        )

        // ======================================================
        // PREVENT OUTDATED REQUESTS FROM UPDATING STATE
        // ======================================================

        if (!cancelled) {
          setReport(reportData)
        }

      } catch (error: unknown) {

        // ======================================================
        // DISPLAY REQUEST ERRORS
        // ======================================================

        if (!cancelled) {

          setErrorMessage(
            error instanceof Error
              ? error.message
              : 'Unable to generate the Income Statement.',
          )
        }

      } finally {

        if (!cancelled) {
          setIsLoading(false)
        }
      }
    }

    void loadReport()

    // ==========================================================
    // CLEAN UP WHEN THE REQUEST IS NO LONGER CURRENT
    // ==========================================================

    return () => {
      cancelled = true
    }

  }, [
    businessId,
    appliedStartDate,
    appliedEndDate,
    refreshVersion,
  ])


  // ============================================================
  // GENERATE REPORT HANDLER
  // ============================================================

  function handleGenerateReport(): void {

    const today = getTodayDate()

    // ==========================================================
    // VALIDATE THE USER'S SELECTED DATES
    // ==========================================================

    if (
      !selectedStartDate ||
      !selectedEndDate ||
      selectedStartDate > selectedEndDate ||
      selectedEndDate > today
    ) {

      setErrorMessage(
        'Select a valid reporting period ending no later than today.',
      )

      return
    }

    // ==========================================================
    // APPLY THE DATE RANGE
    // ==========================================================

    setAppliedStartDate(selectedStartDate)
    setAppliedEndDate(selectedEndDate)

    // ==========================================================
    // FORCE REFRESH EVEN WHEN DATES ARE UNCHANGED
    // ==========================================================

    setRefreshVersion(
      (previousVersion) => previousVersion + 1,
    )
  }


  // ============================================================
  // CHECK WHETHER THE REPORT HAS POSTED ACTIVITY
  // ============================================================

  /**
   * Do not use monetary totals to detect activity.
   *
   * A report may contain offsetting posted entries
   * whose net amount equals zero.
   *
   * postedEntryCount accurately distinguishes
   * unused accounts from accounts with activity.
   */
  const hasPostedActivity = report
    ? (
        report.unclassifiedAccounts.some(
          (line) => line.postedEntryCount > 0,
        ) ||
        report.sections.some(
          (section) => section.accounts.some(
            (line) => line.postedEntryCount > 0,
          ),
        )
      )
    : false


  // ============================================================
  // RENDER INCOME STATEMENT PAGE
  // ============================================================

  return (

    <section
      className="tb-page isr-page"
      aria-busy={isLoading}
    >

      {/* ====================================================== */}
      {/* PAGE HEADING */}
      {/* ====================================================== */}

      <header className="tb-page-header">

        <h1>Income Statement</h1>

        <p>
          Review revenue, expenses, and net income
          from posted general ledger transactions
          for a selected reporting period.
        </p>

      </header>


      {/* ====================================================== */}
      {/* REPORT SETTINGS CARD */}
      {/* ====================================================== */}

      <section className="tb-controls-card">

        <div className="tb-controls-heading">

          <h2>Report Settings</h2>

          <p>
            Select the start and end dates for your
            Income Statement.
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

          {/* ================================================== */}
          {/* START DATE */}
          {/* ================================================== */}

          <label className="tb-date-field">

            <span>Start Date</span>

            <input
              type="date"
              value={selectedStartDate}
              max={selectedEndDate || getTodayDate()}
              required
              onChange={(event) =>
                setSelectedStartDate(event.target.value)
              }
            />

          </label>


          {/* ================================================== */}
          {/* END DATE */}
          {/* ================================================== */}

          <label className="tb-date-field">

            <span>End Date</span>

            <input
              type="date"
              value={selectedEndDate}
              min={selectedStartDate || undefined}
              max={getTodayDate()}
              required
              onChange={(event) =>
                setSelectedEndDate(event.target.value)
              }
            />

          </label>


          {/* ================================================== */}
          {/* GENERATE REPORT */}
          {/* ================================================== */}

          <button
            type="submit"
            className="tb-generate-button"
            disabled={
              isLoading ||
              !selectedStartDate ||
              !selectedEndDate ||
              selectedStartDate > selectedEndDate ||
              selectedEndDate > getTodayDate()
            }
          >

            {isLoading
              ? 'Generating...'
              : 'Generate Report'
            }

          </button>

        </form>

      </section>


      {/* ====================================================== */}
      {/* FINANCIAL REPORT CARD */}
      {/* ====================================================== */}

      <section className="tb-report-card">

        {/* ==================================================== */}
        {/* REPORT HEADING */}
        {/* ==================================================== */}

        <div className="tb-report-heading">

          <div>

            <h2>Income Statement Report</h2>

            <p>

              {report
                ? `${formatReportingDate(report.startDate)} – ${formatReportingDate(report.endDate)}`
                : 'Revenue, expenses, and net income'
              }

            </p>

          </div>


          {/* ================================================== */}
          {/* CLASSIFICATION STATUS */}
          {/* ================================================== */}

          {report && (

            <span
              className={
                report.classificationComplete
                  ? 'isr-status isr-status-complete'
                  : 'isr-status isr-status-warning'
              }
              role="status"
            >

              {report.classificationComplete
                ? '✓ Classification Complete'
                : '⚠ Review Classification'
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
              Generating Income Statement from LedgerBase...
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
              Unable to generate Income Statement
            </h3>

            <p>{errorMessage}</p>

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
        {/* SUCCESSFUL REPORT */}
        {/* ==================================================== */}

        {!isLoading && !errorMessage && report && (

          <>

            {/* ================================================== */}
            {/* UNCLASSIFIED ACCOUNT WARNING */}
            {/* ================================================== */}

            {!report.classificationComplete && (

              <div
                className="isr-warning"
                role="alert"
              >

                <strong>
                  Reporting classification requires attention
                </strong>

                <p>
                  {report.unclassifiedAccounts.length}{' '}
                  revenue or expense account(s) with posted
                  activity have no reporting group.

                  Their amounts are included in financial
                  totals and listed separately below.

                  Assign reporting groups in the Chart
                  of Accounts to complete the statement.
                </p>

              </div>

            )}


            {/* ================================================== */}
            {/* NO POSTED ACTIVITY MESSAGE */}
            {/* ================================================== */}

            {!hasPostedActivity && (

              <div className="isr-empty-note">

                <strong>
                  No posted income or expense activity
                </strong>

                <p>
                  There are no posted revenue or expense
                  ledger entries in the selected period.
                  The report below therefore shows
                  zero financial activity.
                </p>

              </div>

            )}


            {/* ================================================== */}
            {/* INCOME STATEMENT TABLE */}
            {/* ================================================== */}

            <div className="tb-table-scroll">

              <table className="tb-table isr-table">

                <caption className="tb-visually-hidden">

                  Income Statement from{' '}
                  {formatReportingDate(report.startDate)}
                  {' '}through{' '}
                  {formatReportingDate(report.endDate)}

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
                      Account / Reporting Group
                    </th>

                    <th
                      scope="col"
                      className="tb-money-column"
                    >
                      Period Amount
                    </th>

                  </tr>

                </thead>


                {/* ============================================ */}
                {/* CLASSIFIED REPORTING GROUPS */}
                {/* ============================================ */}

                {report.sections.map((section) => (

                  <tbody key={section.reportingGroup}>

                    {/* ======================================== */}
                    {/* SECTION HEADING */}
                    {/* ======================================== */}

                    <tr className="isr-section-heading">

                      <th
                        scope="rowgroup"
                        colSpan={3}
                      >

                        {section.label}

                      </th>

                    </tr>


                    {/* ======================================== */}
                    {/* INDIVIDUAL ACCOUNT LINES */}
                    {/* ======================================== */}

                    {section.accounts.map((line) => (

                      <tr
                        key={line.accountId}
                        className={
                          line.active
                            ? ''
                            : 'tb-row-inactive'
                        }
                      >

                        <td className="tb-account-code">

                          {line.code}

                        </td>

                        <td className="tb-account-name">

                          {line.name}

                          {!line.active && (

                            <span className="tb-inactive-label">
                              Inactive
                            </span>

                          )}

                        </td>

                        <td className="tb-money-column">

                          {formatMoney(line.amount)}

                        </td>

                      </tr>

                    ))}


                    {/* ======================================== */}
                    {/* EMPTY REPORTING GROUP */}
                    {/* ======================================== */}

                    {section.accounts.length === 0 && (

                      <tr>

                        <td
                          className="isr-empty-group"
                          colSpan={3}
                        >

                          No accounts assigned to this category.

                        </td>

                      </tr>

                    )}


                    {/* ======================================== */}
                    {/* SECTION SUBTOTAL */}
                    {/* ======================================== */}

                    <tr className="isr-subtotal-row">

                      <th
                        scope="row"
                        colSpan={2}
                      >

                        Total {section.label}

                      </th>

                      <td className="tb-money-column">

                        {formatMoney(section.subtotal)}

                      </td>

                    </tr>

                  </tbody>

                ))}


                {/* ============================================ */}
                {/* UNCLASSIFIED ACCOUNTS */}
                {/* ============================================ */}

                {report.unclassifiedAccounts.length > 0 && (

                  <tbody>

                    <tr className="isr-section-heading isr-unclassified-heading">

                      <th
                        scope="rowgroup"
                        colSpan={3}
                      >

                        Unclassified Activity — Needs Review

                      </th>

                    </tr>

                    {report.unclassifiedAccounts.map((line) => (

                      <tr key={line.accountId}>

                        <td className="tb-account-code">

                          {line.code}

                        </td>

                        <td className="tb-account-name">

                          {line.name}

                          <span className="isr-account-type">

                            {line.accountType === 'REVENUE'
                              ? 'Revenue'
                              : 'Expense'
                            }

                          </span>

                        </td>

                        <td className="tb-money-column">

                          {formatMoney(line.amount)}

                        </td>

                      </tr>

                    ))}

                  </tbody>

                )}


                {/* ============================================ */}
                {/* COMPLETE FINANCIAL STATEMENT TOTALS */}
                {/* ============================================ */}

                <tfoot>

                  <tr className="isr-total-row">

                    <th
                      scope="row"
                      colSpan={2}
                    >

                      Total Revenue

                    </th>

                    <td className="tb-money-column">

                      {formatMoney(report.totalRevenue)}

                    </td>

                  </tr>

                  <tr className="isr-total-row">

                    <th
                      scope="row"
                      colSpan={2}
                    >

                      Total Expenses

                    </th>

                    <td className="tb-money-column">

                      {formatMoney(report.totalExpenses)}

                    </td>

                  </tr>

                  <tr className="tb-totals-row isr-net-income-row">

                    <th
                      scope="row"
                      colSpan={2}
                    >

                      Net Income

                    </th>

                    <td className="tb-money-column">

                      {formatMoney(report.netIncome)}

                    </td>

                  </tr>

                </tfoot>

              </table>

            </div>


            {/* ================================================== */}
            {/* REPORT FOOTER */}
            {/* ================================================== */}

            <div className="tb-report-footer">

              <span>
                Income Statement · USD
              </span>

              <span>
                Only POSTED transactions from{' '}
                {formatReportingDate(report.startDate)}
                {' '}through{' '}
                {formatReportingDate(report.endDate)}
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

export default IncomeStatementPage
