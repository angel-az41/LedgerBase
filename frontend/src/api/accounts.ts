import {
  getJson,
  postJson,
  patchNoContent,
} from './client'

// ============================================================
// LEDGERBASE — ACCOUNTS API
// ============================================================

/**
 * TypeScript models and API functions for the
 * LedgerBase Chart of Accounts.
 *
 * The frontend communicates with our existing
 * Spring Boot REST API.
 *
 * Responsibilities:
 *
 * - Retrieve financial accounts
 * - Retrieve calculated account balances
 * - Create posting and grouping accounts
 * - Rename accounts
 * - Deactivate accounts
 *
 * V7 introduces:
 *
 * - Financial statement reporting groups
 * - Optional parent-child account relationships
 * - Posting eligibility
 *
 * PostgreSQL and the Spring Boot service remain
 * responsible for accounting integrity.
 */

// ============================================================
// ACCOUNT TYPE DEFINITIONS
// ============================================================

/**
 * The five fundamental accounting types.
 *
 * These determine an account's normal balance
 * direction and broad accounting classification.
 */
export type AccountType =
  | 'ASSET'
  | 'LIABILITY'
  | 'EQUITY'
  | 'REVENUE'
  | 'EXPENSE'


// ============================================================
// V7 — FINANCIAL REPORTING GROUPS
// ============================================================

/**
 * More detailed financial statement classifications.
 *
 * These values match the PostgreSQL V7 CHECK
 * constraint and Spring Boot validation.
 *
 * They will support:
 *
 * - Balance Sheet
 * - Income Statement
 * - Account reporting sections
 *
 * Accounts may temporarily have null reporting groups
 * until they have been classified.
 */
export type ReportingGroup =
  | 'CURRENT_ASSET'
  | 'NON_CURRENT_ASSET'
  | 'CURRENT_LIABILITY'
  | 'NON_CURRENT_LIABILITY'
  | 'CONTRIBUTED_CAPITAL'
  | 'RETAINED_EARNINGS'
  | 'OTHER_EQUITY'
  | 'OPERATING_REVENUE'
  | 'OTHER_INCOME'
  | 'COST_OF_GOODS_SOLD'
  | 'OPERATING_EXPENSE'
  | 'OTHER_EXPENSE'


// ============================================================
// V7 — REPORTING GROUP DROPDOWN OPTIONS
// ============================================================

/**
 * Each account type has its own valid reporting groups.
 *
 * Example:
 *
 * ASSET:
 *   Current Assets
 *   Non-current Assets
 *
 * EXPENSE:
 *   Cost of Goods Sold
 *   Operating Expenses
 *   Other Expenses
 *
 * We will reuse this constant in NewAccountForm.tsx
 * so users cannot accidentally select an unrelated
 * reporting group.
 *
 * The backend validates the selection independently.
 */
export const REPORTING_GROUP_OPTIONS: Record<
  AccountType,
  ReadonlyArray<{
    value: ReportingGroup
    label: string
  }>
> = {
  ASSET: [
    {
      value: 'CURRENT_ASSET',
      label: 'Current Assets',
    },
    {
      value: 'NON_CURRENT_ASSET',
      label: 'Non-current Assets',
    },
  ],

  LIABILITY: [
    {
      value: 'CURRENT_LIABILITY',
      label: 'Current Liabilities',
    },
    {
      value: 'NON_CURRENT_LIABILITY',
      label: 'Non-current Liabilities',
    },
  ],

  EQUITY: [
    {
      value: 'CONTRIBUTED_CAPITAL',
      label: 'Contributed Capital',
    },
    {
      value: 'RETAINED_EARNINGS',
      label: 'Retained Earnings',
    },
    {
      value: 'OTHER_EQUITY',
      label: 'Other Equity',
    },
  ],

  REVENUE: [
    {
      value: 'OPERATING_REVENUE',
      label: 'Operating Revenue',
    },
    {
      value: 'OTHER_INCOME',
      label: 'Other Income',
    },
  ],

  EXPENSE: [
    {
      value: 'COST_OF_GOODS_SOLD',
      label: 'Cost of Goods Sold',
    },
    {
      value: 'OPERATING_EXPENSE',
      label: 'Operating Expenses',
    },
    {
      value: 'OTHER_EXPENSE',
      label: 'Other Expenses',
    },
  ],
}


// ============================================================
// ACCOUNT MODEL
// ============================================================

/**
 * Represents a general ledger account returned
 * by the Spring Boot backend.
 *
 * V7 example:
 *
 * {
 *   "id": 6,
 *   "businessId": 1,
 *   "code": "1010",
 *   "name": "Cash",
 *   "accountType": "ASSET",
 *   "reportingGroup": "CURRENT_ASSET",
 *   "parentAccountId": null,
 *   "active": true,
 *   "postable": true,
 *   "createdAt": "2026-10-12T20:05:02Z"
 * }
 */
export interface Account {

  // ==========================================================
  // ACCOUNT IDENTITY
  // ==========================================================

  id: number

  businessId: number


  // ==========================================================
  // BASIC ACCOUNT INFORMATION
  // ==========================================================

  code: string

  name: string

  accountType: AccountType


  // ==========================================================
  // V7 — FINANCIAL REPORTING CLASSIFICATION
  // ==========================================================

  /**
   * Determines where the account belongs in
   * financial statements.
   *
   * Null means the account is not yet classified.
   */
  reportingGroup: ReportingGroup | null


  // ==========================================================
  // V7 — ACCOUNT HIERARCHY
  // ==========================================================

  /**
   * ID of the account's optional parent.
   *
   * Example:
   *
   * Current Assets (id: 10)
   *   Cash (parentAccountId: 10)
   *
   * Null represents a top-level account.
   */
  parentAccountId: number | null


  // ==========================================================
  // ACCOUNT STATUS
  // ==========================================================

  /**
   * Whether the account is active.
   *
   * Inactive accounts remain available for
   * historical financial reporting.
   */
  active: boolean

  /**
   * Whether new ledger entries may reference
   * this account.
   *
   * true:
   *   Normal posting account.
   *
   * false:
   *   Non-postable organizational heading.
   *
   * This is different from active.
   */
  postable: boolean


  // ==========================================================
  // AUDIT INFORMATION
  // ==========================================================

  createdAt: string
}


// ============================================================
// ACCOUNT BALANCE MODEL
// ============================================================

/**
 * Calculated balance information returned by:
 *
 * GET /api/v1/businesses/{businessId}/accounts/balances
 *
 * IMPORTANT:
 *
 * Balances come from POSTED journal entries only.
 *
 * - DRAFT transactions are excluded.
 * - Inactive accounts remain included.
 * - Accounts without posted activity have zero balances.
 * - Negative balances are preserved.
 *
 * All monetary calculations are performed by
 * PostgreSQL and Java using exact decimals.
 *
 * Monetary fields are serialized as strings.
 */
export interface AccountBalance {

  // ==========================================================
  // ACCOUNT INFORMATION
  // ==========================================================

  id: number

  businessId: number

  code: string

  name: string

  accountType: AccountType

  active: boolean


  // ==========================================================
  // EXACT MONETARY VALUES
  // ==========================================================

  /**
   * Decimal strings preserve the exact values
   * calculated by PostgreSQL.
   *
   * Examples:
   *
   * "500.00"
   * "-500.00"
   * "0"
   *
   * Do not use JavaScript floating-point arithmetic
   * to calculate accounting totals.
   */

  postedDebits: string

  postedCredits: string

  balance: string
}


// ============================================================
// CREATE ACCOUNT REQUEST
// ============================================================

/**
 * Request body for creating an account.
 *
 * Required fields:
 *
 * - code
 * - name
 * - accountType
 *
 * V7 optional fields:
 *
 * - reportingGroup
 * - parentAccountId
 * - postable
 *
 * If the optional properties are omitted:
 *
 * reportingGroup -> null
 * parentAccountId -> null
 * postable -> true
 *
 * This keeps the original account creation form
 * backward-compatible.
 */
export interface CreateAccountRequest {

  code: string

  name: string

  accountType: AccountType

  reportingGroup?: ReportingGroup | null

  parentAccountId?: number | null

  postable?: boolean
}

// ============================================================
// UPDATE ACCOUNT REPORTING GROUP REQUEST
// ============================================================

/**
 * Request body for updating an existing account's
 * financial reporting classification.
 *
 * Example:
 *
 * {
 *   reportingGroup: 'CURRENT_ASSET'
 * }
 *
 * This endpoint requires a valid reporting group.
 * Clearing an existing classification is not
 * supported by the current backend endpoint.
 */
export interface UpdateAccountReportingGroupRequest {
  reportingGroup: ReportingGroup
}


// ============================================================
// RENAME ACCOUNT REQUEST
// ============================================================

/**
 * Existing rename operation.
 *
 * Only the account name changes.
 *
 * Reporting classifications and hierarchy will use
 * separate validated update operations later.
 */
export interface RenameAccountRequest {

  name: string
}


// ============================================================
// ACCOUNTS API — READ
// ============================================================

/**
 * Retrieve all accounts belonging to a business.
 *
 * GET /api/v1/businesses/{businessId}/accounts
 *
 * Includes:
 *
 * - Active and inactive accounts
 * - Postable and non-postable accounts
 * - V7 reporting groups
 * - V7 parent account IDs
 */
export async function getAccounts(
  businessId: number,
): Promise<Account[]> {

  return getJson<Account[]>(
    `/api/v1/businesses/${businessId}/accounts`,
  )
}


// ============================================================
// ACCOUNTS API — READ BALANCES
// ============================================================

/**
 * Retrieve calculated account balances.
 *
 * GET /api/v1/businesses/{businessId}/accounts/balances
 *
 * The backend returns actual accounting data.
 *
 * No balances are hardcoded in React.
 */
export async function getAccountBalances(
  businessId: number,
): Promise<AccountBalance[]> {

  return getJson<AccountBalance[]>(
    `/api/v1/businesses/${businessId}/accounts/balances`,
  )
}


// ============================================================
// ACCOUNTS API — CREATE
// ============================================================

/**
 * Create a normal posting account or a
 * non-postable grouping account.
 *
 * POST /api/v1/businesses/{businessId}/accounts
 *
 * Example:
 *
 * createAccount(1, {
 *   code: '1010',
 *   name: 'Cash',
 *   accountType: 'ASSET',
 *   reportingGroup: 'CURRENT_ASSET',
 *   parentAccountId: null,
 *   postable: true,
 * })
 *
 * Existing callers may omit the optional V7 fields.
 */
export async function createAccount(
  businessId: number,
  request: CreateAccountRequest,
): Promise<Account> {

  return postJson<Account, CreateAccountRequest>(
    `/api/v1/businesses/${businessId}/accounts`,
    request,
  )
}

// ============================================================
// UPDATE ACCOUNT REPORTING GROUP
// ============================================================

/**
 * Update an existing account's reporting classification.
 *
 * PATCH
 * /api/v1/businesses/{businessId}/accounts/{accountId}/reporting-group
 *
 * The Spring Boot backend validates:
 *
 * - Business ownership
 * - Account existence
 * - Reporting group compatibility
 *
 * This operation does not change journal entries,
 * account balances, hierarchy, or posting eligibility.
 *
 * We do not need to use the returned account object
 * because AccountsPage refreshes its data after success.
 */
export async function updateAccountReportingGroup(
  businessId: number,
  accountId: number,
  request: UpdateAccountReportingGroupRequest,
): Promise<void> {

  await patchNoContent(
    `/api/v1/businesses/${businessId}/accounts/${accountId}/reporting-group`,
    request,
  )
}


// ============================================================
// ACCOUNTS API — RENAME
// ============================================================

/**
 * Rename an active account.
 *
 * PATCH /api/v1/businesses/{businessId}/accounts/{accountId}
 *
 * Spring Boot returns HTTP 204 No Content.
 */
export async function renameAccount(
  businessId: number,
  accountId: number,
  request: RenameAccountRequest,
): Promise<void> {

  await patchNoContent(
    `/api/v1/businesses/${businessId}/accounts/${accountId}`,
    request,
  )
}


// ============================================================
// ACCOUNTS API — DEACTIVATE
// ============================================================

/**
 * Deactivate an account without deleting it.
 *
 * PATCH
 * /api/v1/businesses/{businessId}/accounts/{accountId}/deactivate
 *
 * Historical ledger entries remain intact.
 *
 * This updates active, not postable.
 *
 * The backend does not yet support reactivation.
 */
export async function deactivateAccount(
  businessId: number,
  accountId: number,
): Promise<void> {

  await patchNoContent(
    `/api/v1/businesses/${businessId}/accounts/${accountId}/deactivate`,
  )
}
