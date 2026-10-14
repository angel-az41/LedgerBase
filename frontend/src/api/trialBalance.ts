import { getJson } from './client'

import type { AccountType } from './accounts'

// ============================================================
// LEDGERBASE — TRIAL BALANCE API
// ============================================================

/**
 * Defines the TypeScript data structures and API functions
 * used by the React Trial Balance reporting interface.
 *
 * The backend is responsible for:
 *
 * - Retrieving posted journal transactions
 * - Applying the reporting date
 * - Calculating account debit and credit balances
 * - Calculating report totals
 * - Determining whether the report balances
 *
 * React is responsible for:
 *
 * - Requesting the report
 * - Displaying the report data
 * - Formatting monetary values
 * - Managing the reporting date selector
 * - Handling loading and error states
 *
 * IMPORTANT:
 *
 * Financial amounts are returned as decimal strings.
 *
 * We must not convert these values to JavaScript
 * floating-point numbers for accounting calculations.
 */

// ============================================================
// TRIAL BALANCE ROW
// ============================================================

/**
 * Represents one general ledger account in a
 * Trial Balance financial report.
 *
 * This interface matches TrialBalanceRow.java.
 *
 * Example:
 *
 * {
 *   accountId: 8,
 *   code: "1020",
 *   name: "Business Savings",
 *   accountType: "ASSET",
 *   active: true,
 *   debitBalance: "500.00",
 *   creditBalance: "0"
 * }
 *
 * Only POSTED transactions dated on or before
 * the selected reporting date affect these balances.
 */
export interface TrialBalanceRow {

  // Unique account identifier from PostgreSQL.
  accountId: number

  // User-defined general ledger account code.
  code: string

  // Human-readable account name.
  name: string

  // Fundamental account classification.
  accountType: AccountType

  // Whether the account is currently active.
  active: boolean

  // Net debit balance represented as a decimal string.
  debitBalance: string

  // Net credit balance represented as a decimal string.
  creditBalance: string
}

// ============================================================
// COMPLETE TRIAL BALANCE REPORT
// ============================================================

/**
 * Represents the complete financial report returned
 * by the Spring Boot Trial Balance endpoint.
 *
 * This interface matches TrialBalanceReport.java.
 *
 * The report contains:
 *
 * - Business identification
 * - Reporting cutoff date
 * - Account-level debit and credit balances
 * - Total debit balances
 * - Total credit balances
 * - Difference between totals
 * - Balanced/unbalanced status
 *
 * Example:
 *
 * {
 *   businessId: 1,
 *   asOfDate: "2026-10-13",
 *   rows: [...],
 *   totalDebits: "500.00",
 *   totalCredits: "500.00",
 *   difference: "0.00",
 *   balanced: true
 * }
 */
export interface TrialBalanceReport {

  // Business that owns the general ledger.
  businessId: number

  /**
   * Reporting cutoff date.
   *
   * Format: YYYY-MM-DD
   *
   * Example: "2026-10-13"
   */
  asOfDate: string

  // Individual account balances.
  rows: TrialBalanceRow[]

  // Sum of the report's net debit balances.
  totalDebits: string

  // Sum of the report's net credit balances.
  totalCredits: string

  /**
   * Signed difference:
   *
   * totalDebits - totalCredits
   *
   * A balanced report has a difference of zero.
   */
  difference: string

  /**
   * Whether debit and credit totals agree.
   *
   * The backend calculates this value
   * using Java BigDecimal.
   */
  balanced: boolean
}

// ============================================================
// TRIAL BALANCE API — READ REPORT
// ============================================================

/**
 * Request a Trial Balance report for a selected business
 * and reporting date.
 *
 * Example:
 *
 * getTrialBalance(1, '2026-10-13')
 *
 * Sends:
 *
 * GET /api/v1/businesses/1/reports/trial-balance
 *     ?asOfDate=2026-10-13
 *
 * PARAMETERS:
 *
 * businessId:
 *   Business whose accounts should be reported.
 *
 * asOfDate:
 *   Inclusive reporting cutoff date in YYYY-MM-DD format.
 *
 * RETURNS:
 *
 * A TrialBalanceReport containing real accounting data
 * calculated by PostgreSQL and Spring Boot.
 *
 * IMPORTANT:
 *
 * We do not calculate report balances in React.
 *
 * The backend remains the authoritative source
 * for all accounting calculations.
 *
 * encodeURIComponent safely encodes the date
 * when placing it inside the request URL.
 */
export async function getTrialBalance(
  businessId: number,
  asOfDate: string,
): Promise<TrialBalanceReport> {

  // ==========================================================
  // BUILD THE REPORT ENDPOINT
  // ==========================================================

  const endpoint =
    `/api/v1/businesses/${businessId}/reports/trial-balance`

  // ==========================================================
  // ENCODE THE REPORTING DATE
  // ==========================================================

  const queryString =
    `asOfDate=${encodeURIComponent(asOfDate)}`

  // ==========================================================
  // REQUEST THE FINANCIAL REPORT
  // ==========================================================

  /**
   * getJson() uses our existing shared API client.
   *
   * The Vite development proxy forwards the request
   * to Spring Boot.
   *
   * Spring Boot generates the Trial Balance and
   * returns the response as JSON.
   */
  return getJson<TrialBalanceReport>(
    `${endpoint}?${queryString}`,
  )
}
