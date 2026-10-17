import { getJson } from './client'

import type { AccountType } from './accounts'

// ============================================================
// LEDGERBASE — INCOME STATEMENT API
// ============================================================

/**
 * Defines the TypeScript interfaces and API functions
 * used by the React Income Statement interface.
 *
 * This module connects React to our Spring Boot endpoint:
 *
 * GET /api/v1/businesses/{businessId}/reports/income-statement
 *
 * Responsibilities:
 *
 * - Define the financial statement response structure
 * - Represent revenue and expense account activity
 * - Represent financial reporting sections
 * - Preserve exact monetary values as decimal strings
 * - Request Income Statements for selected date ranges
 * - Identify unclassified accounts with posted activity
 *
 * IMPORTANT:
 *
 * The backend is responsible for all financial
 * calculations.
 *
 * React must not recalculate accounting totals
 * using JavaScript floating-point arithmetic.
 *
 * All monetary amounts are returned as strings,
 * such as "1250.00" or "-75.50".
 */


// ============================================================
// INCOME STATEMENT ACCOUNT TYPE
// ============================================================

/**
 * An Income Statement contains only Revenue
 * and Expense accounts.
 *
 * Extract<> narrows our existing AccountType union
 * to the two types applicable to this report.
 *
 * Other account types:
 *
 * ASSET
 * LIABILITY
 * EQUITY
 *
 * belong to the Balance Sheet instead.
 */
export type IncomeStatementAccountType = Extract<
  AccountType,
  'REVENUE' | 'EXPENSE'
>


// ============================================================
// INCOME STATEMENT REPORTING GROUP
// ============================================================

/**
 * Reporting groups determine which section of the
 * Income Statement contains a financial account.
 *
 * These values match:
 *
 * - Our V7 PostgreSQL account classification schema
 * - IncomeStatementService.java
 * - IncomeStatementResponse.java
 *
 * Revenue groups:
 *
 * OPERATING_REVENUE
 * OTHER_INCOME
 *
 * Expense groups:
 *
 * COST_OF_GOODS_SOLD
 * OPERATING_EXPENSE
 * OTHER_EXPENSE
 */
export type IncomeStatementReportingGroup =
  | 'OPERATING_REVENUE'
  | 'OTHER_INCOME'
  | 'COST_OF_GOODS_SOLD'
  | 'OPERATING_EXPENSE'
  | 'OTHER_EXPENSE'


// ============================================================
// INCOME STATEMENT ACCOUNT LINE
// ============================================================

/**
 * Represents one Revenue or Expense account
 * displayed in the Income Statement.
 *
 * Matches:
 *
 * IncomeStatementResponse.AccountLine
 *
 * Example:
 *
 * {
 *   accountId: 12,
 *   code: "4000",
 *   name: "Sales Revenue",
 *   accountType: "REVENUE",
 *   active: true,
 *   amount: "12000.00",
 *   postedEntryCount: 5
 * }
 *
 * ACCOUNTING RULES:
 *
 * Revenue:
 *   Credits - Debits
 *
 * Expenses:
 *   Debits - Credits
 *
 * Negative amounts are valid and must be preserved.
 */
export interface IncomeStatementAccountLine {

  // Unique general ledger account identifier.
  accountId: number

  // User-defined account code.
  code: string

  // Human-readable account name.
  name: string

  // REVENUE or EXPENSE.
  accountType: IncomeStatementAccountType

  /**
   * Whether the account is currently active.
   *
   * Historical financial activity from inactive
   * accounts must still appear in reports.
   */
  active: boolean

  /**
   * Exact net activity for the selected period.
   *
   * Stored as a decimal string.
   *
   * Examples:
   *
   * "12000.00"
   * "0.00"
   * "-150.25"
   */
  amount: string

  /**
   * Number of POSTED ledger entries associated
   * with this account during the selected period.
   *
   * An account may have:
   *
   * amount = "0.00"
   * postedEntryCount = 2
   *
   * This means actual posted activity occurred,
   * even though the entries offset each other.
   */
  postedEntryCount: number
}


// ============================================================
// INCOME STATEMENT SECTION
// ============================================================

/**
 * Represents one reporting section of the
 * Income Statement.
 *
 * Matches:
 *
 * IncomeStatementResponse.Section
 *
 * Example:
 *
 * {
 *   reportingGroup: "OPERATING_REVENUE",
 *   label: "Operating Revenue",
 *   accounts: [...],
 *   subtotal: "15000.00"
 * }
 *
 * Each section contains:
 *
 * - Its reporting-group identifier
 * - A human-readable label
 * - The accounts belonging to that group
 * - An exact financial subtotal
 *
 * Subtotals are calculated by the Java service
 * using BigDecimal.
 */
export interface IncomeStatementSection {

  // Financial reporting-group identifier.
  reportingGroup: IncomeStatementReportingGroup

  // Display heading provided by Spring Boot.
  label: string

  // Individual accounts assigned to this group.
  accounts: IncomeStatementAccountLine[]

  // Exact section subtotal as a decimal string.
  subtotal: string
}


// ============================================================
// COMPLETE INCOME STATEMENT REPORT
// ============================================================

/**
 * Represents the complete financial statement
 * returned by Spring Boot.
 *
 * Matches:
 *
 * IncomeStatementResponse.java
 *
 * Includes:
 *
 * - Business identification
 * - Inclusive reporting dates
 * - Classification completeness
 * - Five financial reporting sections
 * - Unclassified accounts with posted activity
 * - Total revenue
 * - Total expenses
 * - Net income
 *
 * IMPORTANT:
 *
 * Total Revenue and Total Expenses include
 * financial activity from unclassified accounts.
 *
 * The report must not silently omit that activity.
 *
 * When classificationComplete is false,
 * the frontend should display a clear warning
 * and show the affected accounts separately.
 */
export interface IncomeStatementReport {

  // ==========================================================
  // REPORT IDENTIFICATION
  // ==========================================================

  // Business that owns the financial records.
  businessId: number

  /**
   * Inclusive reporting start date.
   *
   * Format: YYYY-MM-DD
   */
  startDate: string

  /**
   * Inclusive reporting end date.
   *
   * Format: YYYY-MM-DD
   */
  endDate: string


  // ==========================================================
  // CLASSIFICATION STATUS
  // ==========================================================

  /**
   * Whether all Revenue and Expense accounts
   * with POSTED activity during this period
   * have valid reporting classifications.
   *
   * true:
   *   No unclassified posted activity.
   *
   * false:
   *   At least one account requires classification.
   *
   * This does not mean the accounting period
   * has been formally closed.
   */
  classificationComplete: boolean


  // ==========================================================
  // FINANCIAL REPORTING SECTIONS
  // ==========================================================

  /**
   * Organized Income Statement sections.
   *
   * Expected order:
   *
   * 1. Operating Revenue
   * 2. Other Income
   * 3. Cost of Goods Sold
   * 4. Operating Expenses
   * 5. Other Expenses
   */
  sections: IncomeStatementSection[]


  // ==========================================================
  // UNCLASSIFIED FINANCIAL ACCOUNTS
  // ==========================================================

  /**
   * Accounts with POSTED activity but no
   * Income Statement reporting group.
   *
   * These accounts are displayed separately
   * so users can correct their classification
   * in the Chart of Accounts.
   */
  unclassifiedAccounts: IncomeStatementAccountLine[]


  // ==========================================================
  // FINANCIAL TOTALS
  // ==========================================================

  /**
   * Revenue from all REVENUE posting accounts.
   *
   * Includes classified and unclassified activity.
   */
  totalRevenue: string

  /**
   * Expenses from all EXPENSE posting accounts.
   *
   * Includes classified and unclassified activity.
   */
  totalExpenses: string

  /**
   * Net Income = Total Revenue - Total Expenses.
   *
   * A negative value indicates a net loss.
   *
   * All calculations are performed in Java
   * using BigDecimal.
   */
  netIncome: string
}


// ============================================================
// INCOME STATEMENT API — READ REPORT
// ============================================================

/**
 * Request an Income Statement for one business
 * during a selected reporting period.
 *
 * Example:
 *
 * getIncomeStatement(
 *   1,
 *   '2026-10-01',
 *   '2026-10-31',
 * )
 *
 * Sends:
 *
 * GET /api/v1/businesses/1/reports/income-statement
 *     ?startDate=2026-10-01
 *     &endDate=2026-10-31
 *
 * PARAMETERS:
 *
 * businessId:
 *   Business whose financial records are reported.
 *
 * startDate:
 *   Inclusive first reporting date.
 *
 * endDate:
 *   Inclusive last reporting date.
 *
 * RETURNS:
 *
 * A complete IncomeStatementReport containing
 * actual accounting data from PostgreSQL.
 *
 * ERROR HANDLING:
 *
 * Our shared getJson() helper automatically:
 *
 * - Sends the GET request
 * - Verifies the HTTP response
 * - Throws an error when the request fails
 * - Parses successful JSON responses
 *
 * React components can catch these errors
 * and display appropriate error messages.
 */
export async function getIncomeStatement(
  businessId: number,
  startDate: string,
  endDate: string,
): Promise<IncomeStatementReport> {

  // ==========================================================
  // BUILD THE INCOME STATEMENT ENDPOINT
  // ==========================================================

  const endpoint =
    `/api/v1/businesses/${businessId}/reports/income-statement`


  // ==========================================================
  // ENCODE THE REPORTING DATES
  // ==========================================================

  /**
   * encodeURIComponent ensures that date values
   * can safely appear inside the request URL.
   *
   * Both dates use ISO format YYYY-MM-DD.
   */
  const queryString = [
    `startDate=${encodeURIComponent(startDate)}`,
    `endDate=${encodeURIComponent(endDate)}`,
  ].join('&')


  // ==========================================================
  // REQUEST THE FINANCIAL REPORT
  // ==========================================================

  /**
   * getJson() uses our shared frontend API client.
   *
   * During development:
   *
   * React -> Vite proxy -> Spring Boot -> PostgreSQL
   *
   * Spring Boot generates the report and returns
   * its financial data as JSON.
   *
   * React does not calculate financial totals.
   */
  return getJson<IncomeStatementReport>(
    `${endpoint}?${queryString}`,
  )
}
