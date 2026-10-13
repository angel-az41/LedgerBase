import {
  getJson,
  postJson,
  patchNoContent,
} from './client'

// ============================================================
// LEDGERBASE — ACCOUNTS API
// ============================================================

/**
 * This module defines the account data structures
 * and API functions used by the React frontend.
 *
 * All account operations communicate with our
 * existing Java Spring Boot REST API.
 *
 * The backend remains responsible for:
 * - Account validation
 * - Business logic
 * - Database operations
 * - Preventing duplicate account codes
 * - Preserving accounting history
 */

// ============================================================
// ACCOUNT TYPE DEFINITIONS
// ============================================================

/**
 * The five account classifications supported
 * by our current LedgerBase backend.
 *
 * These values match the accountType values
 * returned by Spring Boot.
 *
 * Example:
 * "ASSET"
 */
export type AccountType =
  | 'ASSET'
  | 'LIABILITY'
  | 'EQUITY'
  | 'REVENUE'
  | 'EXPENSE'

/**
 * Represent a general ledger account.
 *
 * This interface matches the account JSON
 * returned by the backend.
 *
 * Example:
 *
 * {
 *   "id": 6,
 *   "businessId": 1,
 *   "code": "1010",
 *   "name": "Operating Checking Account",
 *   "accountType": "ASSET",
 *   "active": true,
 *   "createdAt": "2026-10-12T20:05:02.433124Z"
 * }
 */
export interface Account {

  // Unique database identifier.
  id: number

  // Business that owns the account.
  businessId: number

  // Customizable account code.
  code: string

  // Human-readable account name.
  name: string

  // Financial account classification.
  accountType: AccountType

  // Whether the account can be used in new entries.
  active: boolean

  // Timestamp when the account was created.
  createdAt: string
}

// ============================================================
// CREATE ACCOUNT REQUEST
// ============================================================

/**
 * Data required when creating an account.
 *
 * Spring Boot automatically generates the
 * account ID and creation timestamp.
 */
export interface CreateAccountRequest {
  code: string
  name: string
  accountType: AccountType
}

// ============================================================
// RENAME ACCOUNT REQUEST
// ============================================================

/**
 * Data required when renaming an account.
 *
 * Only the account name changes.
 *
 * The account code and account type remain unchanged.
 */
export interface RenameAccountRequest {
  name: string
}

// ============================================================
// ACCOUNTS API — READ
// ============================================================

/**
 * Retrieve all accounts for a business.
 *
 * Example:
 *
 * getAccounts(1)
 *
 * Sends:
 * GET /api/v1/businesses/1/accounts
 *
 * Returns an array of Account objects.
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
 * Example:
 *
 * createAccount(1, {
 *   code: '1020',
 *   name: 'Business Savings',
 *   accountType: 'ASSET',
 * })
 *
 * Sends:
 * POST /api/v1/businesses/1/accounts
 *
 * Returns the newly created account.
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
 * Rename an existing active account.
 *
 * Example:
 *
 * renameAccount(1, 6, {
 *   name: 'Operating Checking Account',
 * })
 *
 * Sends:
 * PATCH /api/v1/businesses/1/accounts/6
 *
 * Spring Boot returns HTTP 204 No Content
 * when the operation succeeds.
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
 * Deactivate an existing account.
 *
 * Example:
 *
 * deactivateAccount(1, 6)
 *
 * Sends:
 * PATCH /api/v1/businesses/1/accounts/6/deactivate
 *
 * The account is marked inactive instead of
 * being deleted from PostgreSQL.
 *
 * Existing financial history is preserved.
 *
 * IMPORTANT:
 * Our backend does not currently provide a
 * reactivation endpoint. The React interface
 * will require confirmation before deactivation.
 *
 * Spring Boot returns HTTP 204 No Content.
 */
export async function deactivateAccount(
  businessId: number,
  accountId: number,
): Promise<void> {

  await patchNoContent(
    `/api/v1/businesses/${businessId}/accounts/${accountId}/deactivate`,
  )
}
