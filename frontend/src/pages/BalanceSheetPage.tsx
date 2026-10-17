
import { useEffect, useState } from 'react'

import {
  getBalanceSheet,
  type BalanceSheetReport,
  type BalanceSheetSection,
} from '../api/balanceSheet'

import './TrialBalancePage.css'
import './BalanceSheetPage.css'

// ============================================================
// LEDGERBASE — BALANCE SHEET PAGE
// ============================================================

/**
 * Displays the business's financial position as of one date.
 *
 * We deliberately reuse the existing Trial Balance layout:
 *
 * - tb-page, tb-page-header, tb-controls-card
 * - tb-report-card, tb-report-heading, tb-table
 * - tb-message, tb-spinner, tb-report-footer
 *
 * Balance Sheet-specific appearance is scoped under bsr-.
 * This keeps the Trial Balance and Income Statement unchanged.
 *
 * ACCOUNTING RULES:
 *
 * 1. All balances come from POSTED journal entries.
 * 2. The selected as-of date is inclusive.
 * 3. All monetary totals are calculated by Spring Boot.
 * 4. The frontend NEVER uses Number/parseFloat for money.
 * 5. Unclassified accounts still contribute to overall totals.
 * 6. A balanced equation is not the same as a complete
 *    reporting classification.
 */

// ============================================================
// COMPONENT PROPERTIES
// ============================================================

interface BalanceSheetPageProps {
  /** The business currently being viewed in LedgerBase. */
  businessId: number
}

// ============================================================
// DATE HELPERS
// ============================================================

/**
 * Return today's local calendar date in YYYY-MM-DD format.
 *
 * Avoid toISOString(): converting to UTC can move the date
 * forward or backward for users in some time zones.
 */
function getTodayDate(): string {
  const today = new Date()
  const year = today.getFullYear()
  const month = String(today.getMonth() + 1).padStart(2, '0')
  const day = String(today.getDate()).padStart(2, '0')

  return `${year}-${month}-${day}`
}

/**
 * Render an ISO calendar date without a time-zone shift.
 *
 * Example: 2026-10-17 -> October 17, 2026
 */
function formatReportingDate(dateString: string): string {
  return new Intl.DateTimeFormat('en-US', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    timeZone: 'UTC',
  }).format(new Date(`${dateString}T00:00:00Z`))
}

// ============================================================
// PRECISION-SAFE MONEY FORMATTER
// ============================================================

/**
 * Format the exact decimal strings supplied by the backend.
 *
 * "12345.60" -> "$12,345.60"
 * "-500.00" -> "−$500.00"
 * "0.00" -> "$0.00"
 *
 * Using string operations avoids JavaScript floating-point
 * rounding in financial reporting.
 *
 * USD is the project's current development currency.
 */
function formatMoney(amount: string): string {
  const match = /^(-?)(\d+)(?:\.(\d{1,2}))?$/.exec(amount)

  if (!match) {
    return '—'
  }

  const negative = match[1] === '-'
  const wholePart = match[2]
  const decimalPart = (match[3] ?? '').padEnd(2, '0')
  const formattedWhole = wholePart.replace(/\B(?=(\d{3})+(?!\d))/g, ',')

  return `${negative ? '−' : ''}$${formattedWhole}.${decimalPart}`
}

// ============================================================
// BALANCE SHEET COMPONENT
// ============================================================

function BalanceSheetPage({ businessId }: BalanceSheetPageProps) {
  // ==========================================================
  // SELECTED VS. APPLIED REPORTING DATE
  // ==========================================================

  /** Editing the input does not immediately fetch a new report. */
  const [selectedAsOfDate, setSelectedAsOfDate] = useState(getTodayDate)

  /** Date currently shown in the generated financial statement. */
  const [appliedAsOfDate, setAppliedAsOfDate] = useState(getTodayDate)

  // ==========================================================
  // REPORT, LOADING, ERROR, AND REFRESH STATE
  // ==========================================================

  const [report, setReport] = useState<BalanceSheetReport | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [errorMessage, setErrorMessage] = useState('')

  /** Allows users to refresh after posting additional journals. */
  const [refreshVersion, setRefreshVersion] = useState(0)

  // ==========================================================
  // FETCH THE REAL REPORT FROM SPRING BOOT
  // ==========================================================

  useEffect(() => {
    let cancelled = false

    async function loadReport(): Promise<void> {
      setIsLoading(true)
      setErrorMessage('')
      setReport(null)

      try {
        const result = await getBalanceSheet(businessId, appliedAsOfDate)

        // A slow, older request must never replace a newer report.
        if (!cancelled) {
          setReport(result)
        }
      } catch (error: unknown) {
        if (!cancelled) {
          setErrorMessage(
            error instanceof Error
              ? error.message
              : 'Unable to generate the Balance Sheet.',
          )
        }
      } finally {
        if (!cancelled) {
          setIsLoading(false)
        }
      }
    }

    void loadReport()

    return () => {
      cancelled = true
    }
  }, [businessId, appliedAsOfDate, refreshVersion])

  // ==========================================================
  // GENERATE / REGENERATE THE REPORT
  // ==========================================================

  function handleGenerateReport(): void {
    const today = getTodayDate()

    // The HTML date input also validates the date, but we keep
    // this defensive check for programmatic or unusual input.
    if (!selectedAsOfDate || selectedAsOfDate > today) {
      setErrorMessage('Select a valid reporting date no later than today.')
      return
    }

    setAppliedAsOfDate(selectedAsOfDate)

    // Refresh even if the requested date has not changed.
    setRefreshVersion((previousVersion) => previousVersion + 1)
  }

  // ==========================================================
  // FACTUAL EMPTY-ACCOUNT ACTIVITY DETECTION
  // ==========================================================

  /**
   * The API returns postedEntryCount for balance-sheet account
   * lines (Assets, Liabilities, and Equity), but not for the
   * individual Revenue or Expense accounts contributing to
   * unclosedEarnings.
   *
   * Therefore this boolean describes activity in displayed
   * Balance Sheet accounts only. We do not claim there were
   * no journal entries anywhere in the business.
   */
  const hasBalanceSheetAccountActivity = report
    ? (
        report.unclassifiedAccounts.some((line) => line.postedEntryCount > 0) ||
        report.sections.some((section) =>
          section.accounts.some((line) => line.postedEntryCount > 0),
        )
      )
    : false

  // ==========================================================
  // RENDER ONE REPORTING GROUP'S ACCOUNT ROWS
  // ==========================================================

  /**
   * Kept as a local rendering function instead of a component:
   * it uses no state or effects and simply maps API data to
   * the same accounting table structure as Income Statement.
   */
  function renderSection(section: BalanceSheetSection) {
    return (
      <tbody key={section.reportingGroup}>
        {/* Financial reporting group heading. */}
        <tr className="bsr-section-heading">
          <th scope="rowgroup" colSpan={3}>
            {section.label}
          </th>
        </tr>

        {/* Account-level amounts supplied by PostgreSQL/Spring. */}
        {section.accounts.map((line) => (
          <tr
            key={line.accountId}
            className={line.active ? '' : 'tb-row-inactive'}
          >
            <td className="tb-account-code">{line.code}</td>
            <td className="tb-account-name">
              {line.name}
              {!line.active && (
                <span className="tb-inactive-label">Inactive</span>
              )}
            </td>
            <td className="tb-money-column">
              {formatMoney(line.balance)}
            </td>
          </tr>
        ))}

        {/* An empty group remains visible, just like Income Statement. */}
        {section.accounts.length === 0 && (
          <tr>
            <td colSpan={3} className="bsr-empty-group">
              No accounts assigned to this category.
            </td>
          </tr>
        )}

        {/* Classified subtotal excludes unclassified accounts. */}
        <tr className="bsr-subtotal-row">
          <th scope="row" colSpan={2}>
            Total {section.label}
          </th>
          <td className="tb-money-column">
            {formatMoney(section.subtotal)}
          </td>
        </tr>
      </tbody>
    )
  }

  // ==========================================================
  // RENDER BALANCE SHEET PAGE
  // ==========================================================

  return (
    <section className="tb-page bsr-page" aria-busy={isLoading}>
      {/* ====================================================== */}
      {/* PAGE HEADING */}
      {/* ====================================================== */}

      <header className="tb-page-header">
        <h1>Balance Sheet</h1>
        <p>
          Review assets, liabilities, and equity from posted general ledger
          transactions as of a selected reporting date.
        </p>
      </header>

      {/* ====================================================== */}
      {/* REPORT SETTINGS CARD */}
      {/* ====================================================== */}

      <section className="tb-controls-card">
        <div className="tb-controls-heading">
          <h2>Report Settings</h2>
          <p>Select the reporting date for your Balance Sheet.</p>
        </div>

        <form
          className="tb-controls-form"
          onSubmit={(event) => {
            event.preventDefault()
            handleGenerateReport()
          }}
        >
          <label className="tb-date-field">
            <span>As Of Date</span>
            <input
              type="date"
              value={selectedAsOfDate}
              max={getTodayDate()}
              required
              onChange={(event) => setSelectedAsOfDate(event.target.value)}
            />
          </label>

          <button
            type="submit"
            className="tb-generate-button"
            disabled={
              isLoading ||
              !selectedAsOfDate ||
              selectedAsOfDate > getTodayDate()
            }
          >
            {isLoading ? 'Generating...' : 'Generate Report'}
          </button>
        </form>
      </section>

      {/* ====================================================== */}
      {/* FINANCIAL REPORT CARD */}
      {/* ====================================================== */}

      <section className="tb-report-card">
        <div className="tb-report-heading">
          <div>
            <h2>Balance Sheet Report</h2>
            <p>
              {report
                ? `As of ${formatReportingDate(report.asOfDate)}`
                : 'Assets, liabilities, and equity'}
            </p>
          </div>

          {/* Balance and classification are independent conditions. */}
          {report && (
            <div className="bsr-statuses">
              <span
                className={
                  report.balanced
                    ? 'bsr-status bsr-status-complete'
                    : 'bsr-status bsr-status-warning'
                }
                role="status"
              >
                {report.balanced ? '✓ Equation Balanced' : '⚠ Out of Balance'}
              </span>
              <span
                className={
                  report.classificationComplete
                    ? 'bsr-status bsr-status-complete'
                    : 'bsr-status bsr-status-warning'
                }
                role="status"
              >
                {report.classificationComplete
                  ? '✓ Classification Complete'
                  : '⚠ Review Classification'}
              </span>
            </div>
          )}
        </div>

        {/* ==================================================== */}
        {/* LOADING STATE */}
        {/* ==================================================== */}

        {isLoading && (
          <div className="tb-message" role="status">
            <div className="tb-spinner" aria-hidden="true" />
            <p>Generating Balance Sheet from LedgerBase...</p>
          </div>
        )}

        {/* ==================================================== */}
        {/* ERROR STATE AND RETRY */}
        {/* ==================================================== */}

        {!isLoading && errorMessage && (
          <div className="tb-message tb-error-state" role="alert">
            <h3>Unable to generate Balance Sheet</h3>
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
            {/* REPORT-LEVEL WARNINGS */}
            {/* ================================================== */}

            {!report.balanced && (
              <div className="bsr-warning" role="alert">
                <strong>Accounting equation requires attention</strong>
                <p>
                  Total assets differ from total liabilities and equity by{' '}
                  {formatMoney(report.difference)}. The amount is shown as
                  reported by the backend; no balancing adjustment has been
                  inserted automatically.
                </p>
              </div>
            )}

            {!report.classificationComplete && (
              <div className="bsr-warning" role="alert">
                <strong>Reporting classification requires attention</strong>
                <p>
                  {report.unclassifiedAccounts.length} asset, liability, or
                  equity account(s) with posted activity have no reporting
                  group. Their balances are included in the overall totals
                  and listed separately below. Assign groups in the Chart of
                  Accounts to complete the statement.
                </p>
              </div>
            )}

            {!hasBalanceSheetAccountActivity &&
              report.unclosedEarnings === '0.00' && (
                <div className="bsr-empty-note">
                  <strong>No posted Balance Sheet account activity</strong>
                  <p>
                    No posted entries affect the displayed asset, liability,
                    or equity accounts through this date. The report still
                    shows every reporting section and its actual totals.
                  </p>
                </div>
              )}

            {/* ================================================== */}
            {/* BALANCE SHEET TABLE */}
            {/* ================================================== */}

            <div className="tb-table-scroll">
              <table className="tb-table bsr-table">
                <caption className="tb-visually-hidden">
                  Balance Sheet as of {formatReportingDate(report.asOfDate)}
                </caption>
                <thead>
                  <tr>
                    <th scope="col">Account Code</th>
                    <th scope="col">Account / Reporting Group</th>
                    <th scope="col" className="tb-money-column">
                      As Of Balance
                    </th>
                  </tr>
                </thead>

                {/* ============================================ */}
                {/* ASSETS */}
                {/* ============================================ */}

                <tbody className="bsr-major-section">
                  <tr className="bsr-major-heading">
                    <th scope="rowgroup" colSpan={3}>Assets</th>
                  </tr>
                </tbody>

                {report.sections
                  .filter((section) => section.accountType === 'ASSET')
                  .map(renderSection)}

                <tbody>
                  <tr className="bsr-major-total-row">
                    <th scope="row" colSpan={2}>Total Assets</th>
                    <td className="tb-money-column">
                      {formatMoney(report.totalAssets)}
                    </td>
                  </tr>
                </tbody>

                {/* ============================================ */}
                {/* LIABILITIES */}
                {/* ============================================ */}

                <tbody className="bsr-major-section">
                  <tr className="bsr-major-heading">
                    <th scope="rowgroup" colSpan={3}>Liabilities</th>
                  </tr>
                </tbody>

                {report.sections
                  .filter((section) => section.accountType === 'LIABILITY')
                  .map(renderSection)}

                <tbody>
                  <tr className="bsr-major-total-row">
                    <th scope="row" colSpan={2}>Total Liabilities</th>
                    <td className="tb-money-column">
                      {formatMoney(report.totalLiabilities)}
                    </td>
                  </tr>
                </tbody>

                {/* ============================================ */}
                {/* EQUITY */}
                {/* ============================================ */}

                <tbody className="bsr-major-section">
                  <tr className="bsr-major-heading">
                    <th scope="rowgroup" colSpan={3}>Equity</th>
                  </tr>
                </tbody>

                {report.sections
                  .filter((section) => section.accountType === 'EQUITY')
                  .map(renderSection)}

                <tbody>
                  {/* Posted equity is actual EQUITY-account activity. */}
                  <tr className="bsr-total-row">
                    <th scope="row" colSpan={2}>Posted Equity</th>
                    <td className="tb-money-column">
                      {formatMoney(report.postedEquity)}
                    </td>
                  </tr>

                  {/* Unclosed earnings = cumulative revenue - expenses. */}
                  <tr className="bsr-total-row">
                    <th scope="row" colSpan={2}>
                      Unclosed Earnings
                      <span className="bsr-total-note">
                        Revenue less expenses not yet transferred to equity
                      </span>
                    </th>
                    <td className="tb-money-column">
                      {formatMoney(report.unclosedEarnings)}
                    </td>
                  </tr>

                  <tr className="bsr-major-total-row">
                    <th scope="row" colSpan={2}>Total Equity</th>
                    <td className="tb-money-column">
                      {formatMoney(report.totalEquity)}
                    </td>
                  </tr>
                </tbody>

                {/* ============================================ */}
                {/* UNCLASSIFIED FINANCIAL ACTIVITY */}
                {/* ============================================ */}

                {report.unclassifiedAccounts.length > 0 && (
                  <tbody>
                    <tr className="bsr-section-heading bsr-unclassified-heading">
                      <th scope="rowgroup" colSpan={3}>
                        Unclassified Activity — Needs Review
                      </th>
                    </tr>
                    {report.unclassifiedAccounts.map((line) => (
                      <tr
                        key={line.accountId}
                        className={line.active ? '' : 'tb-row-inactive'}
                      >
                        <td className="tb-account-code">{line.code}</td>
                        <td className="tb-account-name">
                          {line.name}
                          <span className="bsr-account-type">
                            {line.accountType === 'ASSET'
                              ? 'Asset'
                              : line.accountType === 'LIABILITY'
                                ? 'Liability'
                                : 'Equity'}
                          </span>
                          {!line.active && (
                            <span className="tb-inactive-label">Inactive</span>
                          )}
                        </td>
                        <td className="tb-money-column">
                          {formatMoney(line.balance)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                )}

                {/* ============================================ */}
                {/* FINAL ACCOUNTING EQUATION */}
                {/* ============================================ */}

                <tfoot>
                  <tr className="bsr-final-total-row">
                    <th scope="row" colSpan={2}>
                      Total Liabilities &amp; Equity
                    </th>
                    <td className="tb-money-column">
                      {formatMoney(report.totalLiabilitiesAndEquity)}
                    </td>
                  </tr>
                  <tr className="bsr-difference-row">
                    <th scope="row" colSpan={2}>Balancing Difference</th>
                    <td className="tb-money-column">
                      {formatMoney(report.difference)}
                    </td>
                  </tr>
                  <tr
                    className={
                      report.balanced
                        ? 'bsr-equation-row bsr-equation-success'
                        : 'bsr-equation-row bsr-equation-warning'
                    }
                  >
                    <th scope="row" colSpan={3}>
                      {report.balanced
                        ? '✓ Assets = Liabilities + Equity'
                        : '⚠ Assets do not equal Liabilities + Equity'}
                    </th>
                  </tr>
                </tfoot>
              </table>
            </div>

            {/* ================================================== */}
            {/* REPORT FOOTER */}
            {/* ================================================== */}

            <div className="tb-report-footer">
              <span>Balance Sheet · USD</span>
              <span>
                POSTED transactions dated on or before{' '}
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

export default BalanceSheetPage
