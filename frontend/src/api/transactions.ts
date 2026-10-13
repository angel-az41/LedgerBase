import {
  getJson,
  postJson,
} from './client'

// ============================================================
// LEDGERBASE — TRANSACTIONS API
// ============================================================

/**
 * This module defines the TypeScript data structures
 * and API functions for financial transactions.
 *
 * All requests communicate with our existing
 * Spring Boot transaction endpoints.
 *
 * The backend remains responsible for:
 * - Validating journal entries
 * - Enforcing double-entry bookkeeping
 * - Verifying account ownership and eligibility
 * - Managing transaction lifecycle states
 * - Protecting posted financial records
 *
 * React is responsible for displaying data and
 * collecting user input, not enforcing accounting
 * integrity on its own.
 */

// ============================================================
// TRANSACTION MODEL
// ============================================================

/**
 * Represents a financial transaction header.
 *
 * Matches LedgerTransaction.java in our backend.
 *
 * A transaction contains general information,
 * while its individual debit and credit lines
 * are represented by separate LedgerEntry objects.
 *
 * Java LocalDate and OffsetDateTime values are
 * serialized as strings in JSON.
 */
export interface LedgerTransaction {

  // Unique transaction identifier.
  id: number

  // Business that owns the transaction.
  businessId: number

  // Temporary creator identifier.
  createdBy: number

  // Optional classification and related entities.
  categoryId: number | null
  customerId: number | null
  vendorId: number | null

  // Human-readable transaction description.
  description: string

  // Accounting date in YYYY-MM-DD format.
  transactionDate: string

  // Backend lifecycle state, such as DRAFT or POSTED.
  status: string

  // Timestamp when the transaction was created.
  createdAt: string
}

// ============================================================
// LEDGER ENTRY MODEL
// ============================================================

/**
 * Represents one debit or credit line.
 *
 * Matches LedgerEntry.java in our backend.
 *
 * Example:
 *
 * Account 5000: Debit  250.00, Credit   0.00
 * Account 1010: Debit    0.00, Credit 250.00
 *
 * PostgreSQL and Java use exact decimal types
 * for financial amounts.
 *
 * The current Spring Boot API serializes
 * BigDecimal values as JSON numbers.
 *
 * JavaScript numbers are NOT exact decimal
 * arithmetic types. We must avoid treating
 * frontend floating-point sums as authoritative
 * accounting calculations.
 */
export interface LedgerEntry {

  // Unique ledger entry identifier.
  id: number

  // Owning business.
  businessId: number

  // Parent transaction.
  transactionId: number

  // General ledger account affected.
  accountId: number

  // Debit amount returned by the backend.
  debit: number

  // Credit amount returned by the backend.
  credit: number

  // Entry creation timestamp.
  createdAt: string
}

// ============================================================
// TRANSACTION DETAIL RESPONSE
// ============================================================

/**
 * Matches TransactionDetailResponse.java.
 *
 * The backend combines:
 * - One transaction header
 * - Its associated ledger entries
 */
export interface TransactionDetailResponse {

  transaction: LedgerTransaction

  entries: LedgerEntry[]
}

// ============================================================
// CREATE TRANSACTION REQUEST
// ============================================================

// ============================================================
// CREATE LEDGER ENTRY REQUEST
// ============================================================

/**
 * Monetary amounts are submitted as decimal strings.
 *
 * This preserves the exact decimal representation
 * produced by our money.ts utility.
 *
 * Examples:
 *
 * "250.00"
 * "0.00"
 * "1250.75"
 *
 * Spring Boot uses BigDecimal for these fields.
 *
 * We will verify this JSON representation against
 * the backend when testing journal entry creation.
 */
export interface CreateLedgerEntryRequest {
  accountId: number
  debit: string
  credit: string
}

/**
 * Matches CreateTransactionRequest.java.
 *
 * A transaction requires:
 * - The creator's user ID
 * - A description
 * - An accounting date
 * - At least two ledger entry lines
 *
 * The business ID is supplied in the API URL.
 *
 * createdBy is temporary until authentication
 * is implemented.
 */
export interface CreateTransactionRequest {
  createdBy: number
  description: string
  transactionDate: string
  entries: CreateLedgerEntryRequest[]
}

// ============================================================
// POST TRANSACTION REQUEST
// ============================================================

/**
 * Matches PostTransactionRequest.java.
 *
 * actorId identifies the user posting a draft.
 *
 * This is a temporary development mechanism.
 * Production authentication must determine
 * the trusted user identity on the backend.
 */
export interface PostTransactionRequest {

  actorId: number
}

// ============================================================
// TRANSACTIONS API — LIST
// ============================================================

/**
 * Retrieve all transactions belonging to a business.
 *
 * Example:
 *
 * getTransactions(1)
 *
 * Sends:
 * GET /api/v1/businesses/1/transactions
 *
 * Returns an array of transaction headers.
 */
export async function getTransactions(
  businessId: number,
): Promise<LedgerTransaction[]> {

  return getJson<LedgerTransaction[]>(
    `/api/v1/businesses/${businessId}/transactions`,
  )
}

// ============================================================
// TRANSACTIONS API — DETAILS
// ============================================================

/**
 * Retrieve a transaction and its journal entry lines.
 *
 * Example:
 *
 * getTransactionDetails(1, 10)
 *
 * Sends:
 * GET /api/v1/businesses/1/transactions/10
 *
 * Returns both the transaction header and entries.
 */
export async function getTransactionDetails(
  businessId: number,
  transactionId: number,
): Promise<TransactionDetailResponse> {

  return getJson<TransactionDetailResponse>(
    `/api/v1/businesses/${businessId}/transactions/${transactionId}`,
  )
}

// ============================================================
// TRANSACTIONS API — CREATE DRAFT
// ============================================================

/**
 * Create a new draft financial transaction.
 *
 * Example:
 *
 * createDraftTransaction(1, {
 *   createdBy: 1,
 *   description: 'Office supplies purchase',
 *   transactionDate: '2026-10-13',
 *   entries: [
 *     { accountId: 5, debit: 250, credit: 0 },
 *     { accountId: 2, debit: 0, credit: 250 },
 *   ],
 * })
 *
 * Account IDs above are illustrative only.
 *
 * Sends:
 * POST /api/v1/businesses/1/transactions
 *
 * Returns HTTP 201 and the new draft header.
 *
 * The backend checks accounting requirements
 * and saves the journal entry in PostgreSQL.
 */
export async function createDraftTransaction(
  businessId: number,
  request: CreateTransactionRequest,
): Promise<LedgerTransaction> {

  return postJson<
    LedgerTransaction,
    CreateTransactionRequest
  >(
    `/api/v1/businesses/${businessId}/transactions`,
    request,
  )
}

// ============================================================
// TRANSACTIONS API — POST
// ============================================================

/**
 * Finalize an existing draft transaction.
 *
 * Example:
 *
 * postTransaction(1, 10, { actorId: 1 })
 *
 * Sends:
 * POST /api/v1/businesses/1/transactions/10/post
 *
 * Returns the updated transaction header.
 *
 * Once posted, the transaction is protected
 * against modification by backend and
 * database-level accounting safeguards.
 *
 * The backend may return HTTP 409 if
 * posting conflicts with the current state.
 */
export async function postTransaction(
  businessId: number,
  transactionId: number,
  request: PostTransactionRequest,
): Promise<LedgerTransaction> {

  return postJson<
    LedgerTransaction,
    PostTransactionRequest
  >(
    `/api/v1/businesses/${businessId}/transactions/${transactionId}/post`,
    request,
  )
}
