import { getJson, postJson } from './client'

// ============================================================
// LEDGERBASE — ACCOUNTING PERIODS API
// ============================================================

/**
 * Defines the TypeScript data structures and API
 * functions for managing accounting periods.
 *
 * This module communicates with the existing
 * Spring Boot AccountingPeriodController.
 *
 * The backend is responsible for:
 *
 * - Validating business and period ownership
 * - Enforcing OPEN/CLOSED status transitions
 * - Preventing posting into CLOSED periods
 * - Recording accounting-period audit events
 *
 * React is responsible for:
 *
 * - Displaying accounting periods
 * - Showing current period statuses
 * - Requesting close/reopen operations
 * - Handling confirmation and error messages
 */

// ============================================================
// ACCOUNTING PERIOD STATUS
// ============================================================

/**
 * Accounting periods support two statuses.
 *
 * OPEN:
 *   Journal transactions can be posted into the period.
 *
 * CLOSED:
 *   Journal posting is blocked until the period reopens.
 *
 * These values match AccountingPeriod.Status in Java.
 */
export type AccountingPeriodStatus =
  | 'OPEN'
  | 'CLOSED'

// ============================================================
// ACCOUNTING PERIOD MODEL
// ============================================================

/**
 * Represents one monthly accounting period.
 *
 * This interface matches AccountingPeriod.java.
 *
 * Dates are returned as ISO-formatted strings.
 *
 * closedAt is null when the period is OPEN.
 */
export interface AccountingPeriod {

  // Unique PostgreSQL accounting-period identifier.
  id: number

  // Business that owns the accounting period.
  businessId: number

  // Fiscal year containing this period.
  fiscalYearId: number

  // Period number within the fiscal year (1–12).
  periodNumber: number

  // First accounting date included in this period.
  startDate: string

  // Last accounting date included in this period.
  endDate: string

  // Whether new journal postings are permitted.
  status: AccountingPeriodStatus

  // Timestamp of the most recent closing, if closed.
  closedAt: string | null

  // Timestamp when the period was created.
  createdAt: string
}

// ============================================================
// ACCOUNTING PERIOD ACTION REQUEST
// ============================================================

/**
 * Request body used for closing and reopening periods.
 *
 * Matches AccountingPeriodActionRequest.java.
 *
 * IMPORTANT:
 *
 * actorId is temporary until Spring Security
 * authentication is implemented.
 *
 * Once users can log in, the backend must identify
 * the actor through the authenticated session.
 */
export interface AccountingPeriodActionRequest {
  actorId: number
}

// ============================================================
// GET PERIODS FOR A FISCAL YEAR
// ============================================================

/**
 * Retrieve all monthly periods belonging to a fiscal year.
 *
 * Example:
 *
 * getAccountingPeriods(1, 5)
 *
 * Sends:
 *
 * GET /api/v1/businesses/1/fiscal-years/5/periods
 *
 * Returns the fiscal year's accounting periods,
 * ordered by period number.
 */
export async function getAccountingPeriods(
  businessId: number,
  fiscalYearId: number,
): Promise<AccountingPeriod[]> {

  return getJson<AccountingPeriod[]>(
    `/api/v1/businesses/${businessId}/fiscal-years/${fiscalYearId}/periods`,
  )
}

// ============================================================
// GET ONE ACCOUNTING PERIOD
// ============================================================

/**
 * Retrieve a particular accounting period.
 *
 * Example:
 *
 * getAccountingPeriod(1, 10)
 *
 * Sends:
 *
 * GET /api/v1/businesses/1/accounting-periods/10
 *
 * Returns HTTP 404 when the period does not exist
 * within the requested business.
 */
export async function getAccountingPeriod(
  businessId: number,
  periodId: number,
): Promise<AccountingPeriod> {

  return getJson<AccountingPeriod>(
    `/api/v1/businesses/${businessId}/accounting-periods/${periodId}`,
  )
}

// ============================================================
// CLOSE ACCOUNTING PERIOD
// ============================================================

/**
 * Close an OPEN accounting period.
 *
 * Example:
 *
 * closeAccountingPeriod(1, 10, { actorId: 1 })
 *
 * Sends:
 *
 * POST /api/v1/businesses/1/accounting-periods/10/close
 *
 * Body:
 * {
 *   "actorId": 1
 * }
 *
 * On success, the backend returns the updated
 * accounting period with status CLOSED.
 *
 * Attempting to close an already CLOSED period
 * returns HTTP 409 Conflict.
 */
export async function closeAccountingPeriod(
  businessId: number,
  periodId: number,
  request: AccountingPeriodActionRequest,
): Promise<AccountingPeriod> {

  return postJson<
    AccountingPeriod,
    AccountingPeriodActionRequest
  >(
    `/api/v1/businesses/${businessId}/accounting-periods/${periodId}/close`,
    request,
  )
}

// ============================================================
// REOPEN ACCOUNTING PERIOD
// ============================================================

/**
 * Reopen a previously CLOSED accounting period.
 *
 * Example:
 *
 * reopenAccountingPeriod(1, 10, { actorId: 1 })
 *
 * Sends:
 *
 * POST /api/v1/businesses/1/accounting-periods/10/reopen
 *
 * On success:
 *
 * - Status becomes OPEN.
 * - closedAt becomes null.
 * - An audit event records the reopening.
 *
 * Attempting to reopen an OPEN period returns
 * HTTP 409 Conflict.
 */
export async function reopenAccountingPeriod(
  businessId: number,
  periodId: number,
  request: AccountingPeriodActionRequest,
): Promise<AccountingPeriod> {

  return postJson<
    AccountingPeriod,
    AccountingPeriodActionRequest
  >(
    `/api/v1/businesses/${businessId}/accounting-periods/${periodId}/reopen`,
    request,
  )
}
