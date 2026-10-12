import {
  getJson,
  postJson,
  patchNoContent,
} from './client'

// ============================================================
// ACCOUNT TYPE DEFINITIONS
// ============================================================

/**
 * LedgerBase's supported account classifications.
 *
 * These values match our Spring Boot backend.
 */
export type AccountType =
  | 'ASSET'
  | 'LIABILITY'
  | 'EQUITY'
  | 'REVENUE'
  | 'EXPENSE'

/**
 * Represent an account returned by Spring Boot.
 *
 * These fields match the JSON returned
 * by our Accounts REST API.
 */
export interface Account {

  // Unique account identifier.
  id: number

  // Business that owns the account.
  businessId: number

  // Customizable account code.
  code: string

  // Human-readable account name.
  name: string

  // Financial account classification.
  accountType: AccountType

  // Indicates whether the account is active.
  active: boolean

  // Database creation timestamp.
  createdAt: string
}

// ============================================================
// CREATE ACCOUNT REQUEST DTO
// ============================================================

/**
 * Information required to create an account.
 *
 * The backend generates:
 * - Account ID
 * - Creation timestamp
 * - Initial active status
 */
export interface CreateAccountRequest {
  code: string
  name: string
  accountType: AccountType
}

// ============================================================
// RENAME ACCOUNT REQUEST DTO
// ============================================================

/**
 * Information required to rename an account.
 *
 * This matches the backend RenameAccountRequest DTO.
 *
 * Only the name changes.
 *
 * The account code and type remain unchanged.
 */
export interface RenameAccountRequest {
  name: string
}

// ============================================================
// ACCOUNTS API — READ
// ============================================================

/**
 * Retrieve all accounts belonging to one business.
 *
 * GET /api/v1/businesses/{businessId}/accounts
 */
export async function getAccounts(
  businessId: number,
): Promise<Account[]> {

  return getJson<Account[]>(
    `/api/v1/businesses/${businessId}/accounts`,
  )
}

// ============================================================
// ACCOUNTS API — CREATE
// ============================================================

/**
 * Create a new general ledger account.
 *
 * POST /api/v1/businesses/{businessId}/accounts
 *
 * Returns the newly created Account object.
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
// ACCOUNTS API — RENAME
// ============================================================

/**
 * Change the name of an existing account.
 *
 * PATCH /api/v1/businesses/{businessId}/accounts/{accountId}
 *
 * Example:
 *
 * renameAccount(1, 2, {
 *   name: 'Operating Checking Account',
 * })
 *
 * The backend currently allows renaming
 * existing active accounts.
 *
 * On success, Spring Boot returns HTTP 204.
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
 * Deactivate an existing account without deleting it.
 *
 * PATCH /api/v1/businesses/{businessId}/accounts/{accountId}/deactivate
 *
 * Example:
 *
 * deactivateAccount(1, 2)
 *
 * This operation:
 * - Sets the account to inactive.
 * - Preserves the account's accounting history.
 * - Prevents it from being used in new transactions
 *   through the current service validation.
 *
 * The endpoint requires no JSON request body.
 *
 * On success, Spring Boot returns HTTP 204.
 */
export async function deactivateAccount(
  businessId: number,
  accountId: number,
): Promise<void> {

  await patchNoContent(
    `/api/v1/businesses/${businessId}/accounts/${accountId}/deactivate`,
  )
}
