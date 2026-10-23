import { getJson, postJson } from './client'

// ============================================================
// LEDGERBASE — ACCOUNTING PERIODS API
// ============================================================

/**
 * TypeScript models and API functions for managing
 * LedgerBase accounting periods.
 *
 * The backend is responsible for:
 *
 * - Authenticating requests through JWT.
 * - Enforcing business-scoped RBAC.
 * - Validating business and period ownership.
 * - Enforcing OPEN/CLOSED status transitions.
 * - Preventing posting into CLOSED periods.
 * - Coordinating PostgreSQL row-level locks.
 * - Recording accounting-period audit events.
 *
 * SECURITY:
 *
 * React never supplies the acting user's ID.
 *
 * Spring Security authenticates the JWT, and
 * AccountingPeriodController extracts the trusted
 * actor ID from the verified JWT subject.
 *
 * React only initiates the requested operation
 * and displays the authoritative backend response.
 */

// ============================================================
// ACCOUNTING PERIOD STATUS
// ============================================================

/**
 * Accounting periods support two statuses.
 *
 * OPEN:
 *   Eligible transactions may be posted into the period.
 *
 * CLOSED:
 *   Journal posting is blocked until reopening.
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
 * Matches AccountingPeriod.java.
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

  // Fiscal year containing the accounting period.
  fiscalYearId: number

  // Period number within the fiscal year.
  periodNumber: number

  // First accounting date included in the period.
  startDate: string

  // Last accounting date included in the period.
  endDate: string

  // Current OPEN/CLOSED status.
  status: AccountingPeriodStatus

  // Closing timestamp, or null when OPEN.
  closedAt: string | null

  // Database-generated creation timestamp.
  createdAt: string
}

// ============================================================
// GET PERIODS FOR A FISCAL YEAR
// ============================================================

/**
 * Retrieve the accounting periods of a fiscal year.
 *
 * Example:
 *
 * getAccountingPeriods(1, 5)
 *
 * Sends:
 *
 * GET /api/v1/businesses/1/fiscal-years/5/periods
 *
 * SECURITY:
 *
 * The backend requires authentication and
 * business read permission.
 *
 * Returns accounting periods ordered by period number.
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
 * Retrieve an individual accounting period.
 *
 * Example:
 *
 * getAccountingPeriod(1, 10)
 *
 * Sends:
 *
 * GET /api/v1/businesses/1/accounting-periods/10
 *
 * The service verifies that the period belongs
 * to the requested business.
 *
 * An unavailable period returns HTTP 404.
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
 * closeAccountingPeriod(1, 10)
 *
 * Sends:
 *
 * POST /api/v1/businesses/1/accounting-periods/10/close
 *
 * Request body:
 *
 * {}
 *
 * SECURITY:
 *
 * No actorId is supplied by React.
 *
 * The existing authenticated API client sends
 * the request using the current JWT session.
 *
 * AccountingPeriodController derives the actual
 * user ID from Spring Security's JWT principal.
 *
 * The backend:
 *
 * - Verifies business write permission.
 * - Validates the accounting period.
 * - Coordinates database locking.
 * - Changes OPEN to CLOSED.
 * - Records the closing timestamp.
 * - Writes an audit event with the trusted actor ID.
 *
 * A successful operation returns the updated period.
 *
 * An invalid status transition returns HTTP 409.
 */
export async function closeAccountingPeriod(
  businessId: number,
  periodId: number,
): Promise<AccountingPeriod> {

  // No client-supplied actor identity is included.
  const requestBody: Record<string, never> = {}

  return postJson<
    AccountingPeriod,
    Record<string, never>
  >(
    `/api/v1/businesses/${businessId}/accounting-periods/${periodId}/close`,
    requestBody,
  )
}

// ============================================================
// REOPEN ACCOUNTING PERIOD
// ============================================================

/**
 * Reopen a CLOSED accounting period.
 *
 * Example:
 *
 * reopenAccountingPeriod(1, 10)
 *
 * Sends:
 *
 * POST /api/v1/businesses/1/accounting-periods/10/reopen
 *
 * Request body:
 *
 * {}
 *
 * SECURITY:
 *
 * No actorId is accepted from React.
 *
 * The backend obtains the authenticated actor
 * from the verified JWT subject.
 *
 * On success:
 *
 * - Status becomes OPEN.
 * - closedAt becomes null.
 * - An audit event records the authenticated actor.
 *
 * An invalid status transition returns HTTP 409.
 */
export async function reopenAccountingPeriod(
  businessId: number,
  periodId: number,
): Promise<AccountingPeriod> {

  // The authenticated JWT determines who reopens the period.
  const requestBody: Record<string, never> = {}

  return postJson<
    AccountingPeriod,
    Record<string, never>
  >(
    `/api/v1/businesses/${businessId}/accounting-periods/${periodId}/reopen`,
    requestBody,
  )
}
