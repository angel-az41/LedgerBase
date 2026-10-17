import { getJson } from "./client";

// ============================================================
// LEDGERBASE — BALANCE SHEET API
// ============================================================

/**
 * TypeScript definitions and API request function
 * for LedgerBase Balance Sheet reporting.
 *
 * BACKEND ENDPOINT:
 *
 * GET /api/v1/businesses/{businessId}/reports/balance-sheet
 *     ?asOfDate=YYYY-MM-DD
 *
 * The backend calculates all financial totals
 * using PostgreSQL NUMERIC and Java BigDecimal.
 *
 * IMPORTANT:
 *
 * Monetary amounts arrive as decimal strings.
 *
 * We must not use JavaScript Number arithmetic
 * to calculate accounting balances or totals.
 */


// ============================================================
// FUNDAMENTAL BALANCE SHEET ACCOUNT TYPES
// ============================================================

/**
 * These three account types appear directly
 * in Balance Sheet reporting sections.
 *
 * Revenue and Expense accounts contribute to
 * unclosed earnings instead.
 */
export type BalanceSheetAccountType =
  | "ASSET"
  | "LIABILITY"
  | "EQUITY";


// ============================================================
// REPORTING GROUPS
// ============================================================

/**
 * These values match LedgerBase's existing V7
 * account reporting_group classifications.
 *
 * Do not rename them without coordinating
 * with the backend and database schema.
 */
export type BalanceSheetReportingGroup =
  | "CURRENT_ASSET"
  | "NON_CURRENT_ASSET"
  | "CURRENT_LIABILITY"
  | "NON_CURRENT_LIABILITY"
  | "CONTRIBUTED_CAPITAL"
  | "RETAINED_EARNINGS"
  | "OTHER_EQUITY";


// ============================================================
// INDIVIDUAL ACCOUNT LINE
// ============================================================

/**
 * Represents one general ledger posting account
 * inside a Balance Sheet section.
 *
 * Example:
 *
 * {
 *   accountId: 6,
 *   code: "1010",
 *   name: "Operating Checking Account",
 *   accountType: "ASSET",
 *   active: true,
 *   balance: "-500.00",
 *   postedEntryCount: 1
 * }
 *
 * Negative balances are preserved.
 */
export interface BalanceSheetAccountLine {
  accountId: number;

  code: string;

  name: string;

  accountType: BalanceSheetAccountType;

  active: boolean;

  /**
   * Exact decimal string.
   *
   * Example:
   *
   * "1250.00"
   * "-500.00"
   */
  balance: string;

  /**
   * Number of POSTED ledger entries contributing
   * to this account through the reporting date.
   */
  postedEntryCount: number;
}


// ============================================================
// BALANCE SHEET REPORTING SECTION
// ============================================================

/**
 * Represents one of the seven financial sections.
 *
 * Examples:
 *
 * Current Assets
 * Non-current Assets
 * Current Liabilities
 * Retained Earnings
 *
 * The backend returns the sections in the
 * correct financial reporting order.
 */
export interface BalanceSheetSection {
  reportingGroup: BalanceSheetReportingGroup;

  label: string;

  accountType: BalanceSheetAccountType;

  accounts: BalanceSheetAccountLine[];

  /**
   * Exact subtotal for classified accounts
   * belonging to this reporting group.
   *
   * Unclassified account balances are included
   * in overall financial totals, but not in
   * an individual classified section subtotal.
   */
  subtotal: string;
}


// ============================================================
// COMPLETE BALANCE SHEET RESPONSE
// ============================================================

/**
 * Matches BalanceSheetResponse.java.
 *
 * All monetary amounts are returned as strings.
 *
 * The React frontend is responsible for displaying
 * values, not recalculating financial results.
 */
export interface BalanceSheetReport {

  // ========================================================
  // REPORT IDENTIFICATION
  // ========================================================

  businessId: number;

  /**
   * ISO date:
   *
   * YYYY-MM-DD
   */
  asOfDate: string;


  // ========================================================
  // CLASSIFICATION STATUS
  // ========================================================

  /**
   * False when ASSET, LIABILITY, or EQUITY
   * accounts have posted activity but lack
   * a Balance Sheet reporting classification.
   *
   * A report may be mathematically balanced
   * while classificationComplete is false.
   */
  classificationComplete: boolean;


  // ========================================================
  // FINANCIAL SECTIONS
  // ========================================================

  sections: BalanceSheetSection[];


  // ========================================================
  // UNCLASSIFIED ACCOUNT WARNINGS
  // ========================================================

  /**
   * Accounts with posted financial activity
   * but no V7 reporting group.
   *
   * These balances remain included in totals.
   */
  unclassifiedAccounts: BalanceSheetAccountLine[];


  // ========================================================
  // ASSET AND LIABILITY TOTALS
  // ========================================================

  totalAssets: string;

  totalLiabilities: string;


  // ========================================================
  // EQUITY AND EARNINGS
  // ========================================================

  /**
   * Balance actually posted to Equity accounts.
   */
  postedEquity: string;

  /**
   * Remaining cumulative:
   *
   * Revenue - Expenses
   *
   * These earnings have not yet been fully
   * transferred into posted Equity accounts.
   */
  unclosedEarnings: string;

  /**
   * Posted Equity + Unclosed Earnings.
   */
  totalEquity: string;


  // ========================================================
  // ACCOUNTING EQUATION RECONCILIATION
  // ========================================================

  totalLiabilitiesAndEquity: string;

  /**
   * Assets - (Liabilities + Total Equity).
   *
   * A balanced report has a difference of "0.00".
   */
  difference: string;

  balanced: boolean;
}


// ============================================================
// GET BALANCE SHEET REPORT
// ============================================================

/**
 * Retrieve a Balance Sheet from the Spring Boot API.
 *
 * PARAMETERS:
 *
 * businessId:
 *   Business whose financial records are requested.
 *
 * asOfDate:
 *   Inclusive reporting date in YYYY-MM-DD format.
 *
 * EXAMPLE:
 *
 * const report = await getBalanceSheet(
 *   1,
 *   "2026-10-17"
 * );
 *
 * The shared getJson function handles:
 *
 * - API base URL configuration
 * - HTTP GET requests
 * - JSON parsing
 * - Non-successful HTTP responses
 *
 * IMPORTANT:
 *
 * This function does not calculate or modify
 * financial balances.
 */
export async function getBalanceSheet(
  businessId: number,
  asOfDate: string
): Promise<BalanceSheetReport> {

  // ========================================================
  // BUILD BUSINESS-SCOPED ENDPOINT
  // ========================================================

  const path =
    `/api/v1/businesses/${businessId}` +
    `/reports/balance-sheet` +
    `?asOfDate=${encodeURIComponent(asOfDate)}`;


  // ========================================================
  // REQUEST EXACT BACKEND REPORT
  // ========================================================

  return getJson<BalanceSheetReport>(path);
}
