import { getBalanceSheet } from './balanceSheet'
import { getIncomeStatement } from './incomeStatement'
import { getFiscalYears, type FiscalYear } from './fiscalYears'

// ============================================================
// LEDGERBASE — DASHBOARD FINANCIAL DATA
// ============================================================

/**
 * Provides the financial metrics displayed on LedgerBase's
 * Overview dashboard.
 *
 * EXISTING BACKEND APIS:
 *
 * 1. Balance Sheet
 *    GET /api/v1/businesses/{businessId}/reports/balance-sheet
 *
 * 2. Income Statement
 *    GET /api/v1/businesses/{businessId}/reports/income-statement
 *
 * 3. Fiscal Years
 *    GET /api/v1/businesses/{businessId}/fiscal-years
 *
 * This module coordinates those existing APIs.
 *
 * No additional accounting calculations are performed
 * in React.
 *
 * All monetary values remain exact decimal strings
 * calculated by Java BigDecimal and PostgreSQL NUMERIC.
 */

// ============================================================
// DASHBOARD METRICS RESPONSE
// ============================================================

/**
 * Defines the financial data required by the dashboard.
 *
 * The dashboard displays:
 *
 * - Total Assets
 * - Total Liabilities
 * - Fiscal-Year-to-Date Net Income
 *
 * IMPORTANT:
 *
 * Net Income may be null when no fiscal year contains
 * the selected reporting date.
 *
 * In that situation, we still return the Balance Sheet
 * metrics rather than inventing a reporting period.
 */
export interface DashboardMetrics {

  // ========================================================
  // REPORTING DATE
  // ========================================================

  /**
   * The date of the Balance Sheet snapshot.
   *
   * Example:
   *
   * "2026-10-17"
   */
  asOfDate: string

  // ========================================================
  // BALANCE SHEET METRICS
  // ========================================================

  /**
   * Total Assets as of the reporting date.
   *
   * Exact decimal string.
   */
  totalAssets: string

  /**
   * Total Liabilities as of the reporting date.
   *
   * Exact decimal string.
   */
  totalLiabilities: string

  // ========================================================
  // INCOME STATEMENT METRIC
  // ========================================================

  /**
   * Net Income for the current fiscal year through
   * the selected reporting date.
   *
   * Formula performed by the backend:
   *
   * Net Income = Revenue - Expenses
   *
   * null means no fiscal year covers the date.
   *
   * This is different from "0.00".
   *
   * null:
   *   A valid reporting period is unavailable.
   *
   * "0.00":
   *   A valid report was generated with zero net income.
   */
  netIncome: string | null

  // ========================================================
  // FISCAL YEAR INFORMATION
  // ========================================================

  /**
   * Fiscal year label supplied by the backend.
   *
   * Example:
   *
   * "FY2027"
   */
  fiscalYearLabel: string | null

  /**
   * First day of the fiscal year.
   *
   * This comes from PostgreSQL rather than
   * assuming the year begins on January 1.
   */
  fiscalYearStartDate: string | null

  /**
   * Last day of the fiscal year.
   *
   * The dashboard calculates year-to-date results
   * through asOfDate, not necessarily this end date.
   */
  fiscalYearEndDate: string | null

  // ========================================================
  // REPORT QUALITY INDICATORS
  // ========================================================

  /**
   * True when:
   *
   * Assets = Liabilities + Equity
   *
   * This is independent of reporting classification.
   */
  balanceSheetBalanced: boolean

  /**
   * True when posted Balance Sheet account activity
   * has complete reporting-group classifications.
   */
  balanceSheetClassificationComplete: boolean

  /**
   * True when posted Income Statement activity has
   * complete reporting-group classifications.
   *
   * null means the Income Statement could not be
   * generated because no matching fiscal year exists.
   */
  incomeStatementClassificationComplete: boolean | null
}


// ============================================================
// FIND THE FISCAL YEAR CONTAINING A REPORTING DATE
// ============================================================

/**
 * Find the fiscal year that includes asOfDate.
 *
 * Both boundaries are inclusive.
 *
 * Example fiscal year:
 *
 * FY2027
 * Start: 2026-04-01
 * End:   2027-03-31
 *
 * For asOfDate = "2026-10-17", this fiscal year matches.
 *
 * We compare ISO date strings because dates formatted
 * as YYYY-MM-DD preserve chronological ordering.
 *
 * IMPORTANT:
 *
 * Do not simply select fiscalYears[0].
 *
 * The API returns fiscal years newest first, but
 * the newest year might be in the future.
 *
 * We must find the year containing our actual
 * reporting date.
 */
function findFiscalYearForDate(
  fiscalYears: FiscalYear[],
  asOfDate: string,
): FiscalYear | null {

  // ========================================================
  // IDENTIFY MATCHING FISCAL YEARS
  // ========================================================

  const matchingYears = fiscalYears.filter((fiscalYear) => {

    const startsOnOrBeforeDate =
      fiscalYear.startDate <= asOfDate

    const endsOnOrAfterDate =
      fiscalYear.endDate >= asOfDate

    return startsOnOrBeforeDate && endsOnOrAfterDate

  })

  // ========================================================
  // DEFENSIVE VALIDATION
  // ========================================================

  /**
   * Our backend already prevents overlapping fiscal years.
   *
   * If overlapping records somehow appear, we should
   * not arbitrarily choose one and display misleading
   * financial results.
   */
  if (matchingYears.length > 1) {
    throw new Error(
      'Multiple fiscal years cover the selected reporting date.',
    )
  }

  // ========================================================
  // RETURN MATCHING YEAR OR NULL
  // ========================================================

  return matchingYears[0] ?? null
}


// ============================================================
// GET DASHBOARD METRICS
// ============================================================

/**
 * Retrieve real financial metrics for one business.
 *
 * PARAMETERS:
 *
 * businessId:
 *   The business currently being viewed.
 *
 * asOfDate:
 *   Inclusive reporting date in YYYY-MM-DD format.
 *
 * EXAMPLE:
 *
 * const metrics = await getDashboardMetrics(
 *   1,
 *   '2026-10-17',
 * )
 *
 * REQUEST SEQUENCE:
 *
 * Step 1:
 *   Request Balance Sheet and Fiscal Years concurrently.
 *
 * Step 2:
 *   Find the fiscal year containing asOfDate.
 *
 * Step 3:
 *   If found, request an Income Statement from the
 *   fiscal-year start date through asOfDate.
 *
 * Step 4:
 *   Return the three financial dashboard metrics.
 *
 * WHY TWO STAGES?
 *
 * We cannot request fiscal-year-to-date Net Income
 * until we know the actual fiscal-year start date.
 */
export async function getDashboardMetrics(
  businessId: number,
  asOfDate: string,
): Promise<DashboardMetrics> {

  // ========================================================
  // VALIDATE THE REPORTING DATE FORMAT
  // ========================================================

  /**
   * The page normally supplies a date from an HTML
   * date input or a local calendar-date helper.
   *
   * This check protects our API module against
   * incorrectly formatted input.
   */
  if (!/^\d{4}-\d{2}-\d{2}$/.test(asOfDate)) {
    throw new Error(
      'Reporting date must use YYYY-MM-DD format.',
    )
  }

  // ========================================================
  // STEP 1 — LOAD BALANCE SHEET AND FISCAL YEARS
  // ========================================================

  /**
   * These two requests are independent, so Promise.all
   * executes them concurrently.
   *
   * Balance Sheet provides:
   *
   * - Total Assets
   * - Total Liabilities
   * - Accounting equation status
   * - Balance Sheet classification status
   *
   * Fiscal Years provides:
   *
   * - Fiscal year labels
   * - Start dates
   * - End dates
   */
  const [balanceSheet, fiscalYears] = await Promise.all([

    getBalanceSheet(
      businessId,
      asOfDate,
    ),

    getFiscalYears(
      businessId,
    ),

  ])

  // ========================================================
  // STEP 2 — FIND THE MATCHING FISCAL YEAR
  // ========================================================

  /**
   * Locate the fiscal year containing the reporting
   * date rather than assuming a calendar year.
   */
  const fiscalYear = findFiscalYearForDate(
    fiscalYears,
    asOfDate,
  )

  // ========================================================
  // STEP 3A — NO FISCAL YEAR FOUND
  // ========================================================

  /**
   * A business might not have created the fiscal year
   * covering the reporting date.
   *
   * Example:
   *
   * The only configured fiscal year is FY2025,
   * but the reporting date is in October 2026.
   *
   * In this situation:
   *
   * - Total Assets remains available.
   * - Total Liabilities remains available.
   * - Net Income is unavailable.
   *
   * We return null for Net Income rather than
   * assuming January 1 or displaying a fake zero.
   *
   * The Overview page can show:
   *
   * "No fiscal year configured"
   *
   * while still displaying the two valid
   * Balance Sheet metrics.
   */
  if (!fiscalYear) {

    return {

      asOfDate: balanceSheet.asOfDate,

      totalAssets: balanceSheet.totalAssets,

      totalLiabilities: balanceSheet.totalLiabilities,

      netIncome: null,

      fiscalYearLabel: null,

      fiscalYearStartDate: null,

      fiscalYearEndDate: null,

      balanceSheetBalanced: balanceSheet.balanced,

      balanceSheetClassificationComplete:
        balanceSheet.classificationComplete,

      incomeStatementClassificationComplete: null,

    }
  }

  // ========================================================
  // STEP 3B — REQUEST FISCAL-YEAR-TO-DATE NET INCOME
  // ========================================================

  /**
   * We now know the correct fiscal-year start date.
   *
   * Example:
   *
   * FY2027: 2026-04-01 through 2027-03-31
   *
   * Reporting date: 2026-10-17
   *
   * Request Income Statement:
   *
   * startDate = 2026-04-01
   * endDate   = 2026-10-17
   *
   * The backend calculates Net Income from POSTED
   * revenue and expense activity during this range.
   */
  const incomeStatement = await getIncomeStatement(
    businessId,
    fiscalYear.startDate,
    asOfDate,
  )

  // ========================================================
  // STEP 4 — RETURN FINANCIAL DASHBOARD METRICS
  // ========================================================

  /**
   * All money remains in its original decimal-string
   * representation.
   *
   * No JavaScript Number arithmetic is performed.
   *
   * The page can display these values directly using
   * our existing precision-safe money formatter.
   */
  return {

    asOfDate: balanceSheet.asOfDate,

    totalAssets: balanceSheet.totalAssets,

    totalLiabilities: balanceSheet.totalLiabilities,

    netIncome: incomeStatement.netIncome,

    fiscalYearLabel: fiscalYear.label,

    fiscalYearStartDate: fiscalYear.startDate,

    fiscalYearEndDate: fiscalYear.endDate,

    balanceSheetBalanced: balanceSheet.balanced,

    balanceSheetClassificationComplete:
      balanceSheet.classificationComplete,

    incomeStatementClassificationComplete:
      incomeStatement.classificationComplete,

  }
}
