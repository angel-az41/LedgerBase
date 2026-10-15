import { getJson, postJson } from './client'

// ============================================================
// LEDGERBASE — FISCAL YEARS API
// ============================================================

/**
 * Defines the TypeScript data structures and API
 * functions used for fiscal-year management.
 *
 * This module communicates with the existing
 * Spring Boot FiscalYearController.
 *
 * The backend is responsible for:
 *
 * - Validating the requested business
 * - Retrieving existing fiscal years
 * - Calculating fiscal-year start and end dates
 * - Preventing duplicate or overlapping fiscal years
 * - Creating 12 monthly accounting periods
 *
 * React is responsible for:
 *
 * - Displaying available fiscal years
 * - Allowing users to select a fiscal year
 * - Submitting new fiscal-year requests
 * - Displaying successful results and API errors
 *
 * IMPORTANT:
 *
 * Fiscal-year dates are calculated by Spring Boot.
 *
 * React must not assume that every business
 * has a January-to-December fiscal year.
 */

// ============================================================
// FISCAL YEAR MODEL
// ============================================================

/**
 * Represents one fiscal year belonging to a business.
 *
 * This interface matches FiscalYear.java.
 *
 * Example:
 *
 * {
 *   id: 5,
 *   businessId: 1,
 *   label: "FY2027",
 *   startDate: "2026-04-01",
 *   endDate: "2027-03-31",
 *   createdAt: "2026-10-15T12:00:00Z"
 * }
 *
 * The dates above illustrate an April-starting
 * fiscal year. Actual values come from PostgreSQL.
 */
export interface FiscalYear {

  // Unique fiscal-year identifier.
  id: number

  // Business that owns this fiscal year.
  businessId: number

  // Display label, such as FY2026 or FY2027.
  label: string

  /**
   * First day of the accounting year.
   *
   * ISO date format: YYYY-MM-DD
   */
  startDate: string

  /**
   * Last day of the accounting year.
   *
   * ISO date format: YYYY-MM-DD
   */
  endDate: string

  /**
   * Timestamp when PostgreSQL created the record.
   *
   * Serialized from Java OffsetDateTime.
   */
  createdAt: string
}

// ============================================================
// CREATE FISCAL YEAR REQUEST
// ============================================================

/**
 * Request body for creating a new fiscal year.
 *
 * This interface matches CreateFiscalYearRequest.java.
 *
 * fiscalYearEndYear represents the calendar year
 * in which the fiscal year ENDS.
 *
 * Examples:
 *
 * January-starting business:
 * FY2027 = Jan 1, 2027 - Dec 31, 2027
 *
 * April-starting business:
 * FY2027 = Apr 1, 2026 - Mar 31, 2027
 *
 * Backend validation:
 *
 * - Required
 * - Minimum: 1900
 * - Maximum: 9999
 *
 * The frontend will also validate the user's
 * input when we build the fiscal-year form.
 */
export interface CreateFiscalYearRequest {

  // Ending calendar year of the accounting cycle.
  fiscalYearEndYear: number
}

// ============================================================
// GET ALL FISCAL YEARS
// ============================================================

/**
 * Retrieve all fiscal years belonging to a business.
 *
 * Example:
 *
 * getFiscalYears(1)
 *
 * Sends:
 *
 * GET /api/v1/businesses/1/fiscal-years
 *
 * Returns fiscal years ordered from newest to oldest.
 *
 * An existing business with no fiscal years
 * returns an empty array.
 */
export async function getFiscalYears(
  businessId: number,
): Promise<FiscalYear[]> {

  // ==========================================================
  // BUILD FISCAL YEARS ENDPOINT
  // ==========================================================

  const endpoint =
    `/api/v1/businesses/${businessId}/fiscal-years`

  // ==========================================================
  // REQUEST FISCAL YEARS
  // ==========================================================

  return getJson<FiscalYear[]>(endpoint)
}

// ============================================================
// GET ONE FISCAL YEAR
// ============================================================

/**
 * Retrieve a specific fiscal year.
 *
 * Example:
 *
 * getFiscalYear(1, 5)
 *
 * Sends:
 *
 * GET /api/v1/businesses/1/fiscal-years/5
 *
 * Returns the requested fiscal year.
 *
 * HTTP 404 indicates that the fiscal year
 * does not exist within the specified business.
 */
export async function getFiscalYear(
  businessId: number,
  fiscalYearId: number,
): Promise<FiscalYear> {

  // ==========================================================
  // BUILD SINGLE FISCAL YEAR ENDPOINT
  // ==========================================================

  const endpoint =
    `/api/v1/businesses/${businessId}/fiscal-years/${fiscalYearId}`

  // ==========================================================
  // REQUEST FISCAL YEAR
  // ==========================================================

  return getJson<FiscalYear>(endpoint)
}

// ============================================================
// CREATE FISCAL YEAR
// ============================================================

/**
 * Create a new fiscal year and its 12 monthly
 * accounting periods.
 *
 * Example:
 *
 * createFiscalYear(1, {
 *   fiscalYearEndYear: 2027,
 * })
 *
 * Sends:
 *
 * POST /api/v1/businesses/1/fiscal-years
 *
 * Request body:
 *
 * {
 *   "fiscalYearEndYear": 2027
 * }
 *
 * The backend performs these operations:
 *
 * 1. Validates business existence.
 * 2. Reads the fiscal-year starting month.
 * 3. Calculates the fiscal-year dates.
 * 4. Rejects overlapping fiscal years.
 * 5. Creates the fiscal-year record.
 * 6. Creates all 12 accounting periods.
 *
 * The fiscal year and its periods are created
 * in one PostgreSQL transaction.
 *
 * Returns:
 *
 * HTTP 201 — Created successfully.
 * HTTP 400 — Invalid year or request.
 * HTTP 404 — Business does not exist.
 *
 * Duplicate or overlapping years are rejected
 * according to the backend's business rules.
 *
 * Our shared API client converts HTTP errors
 * into JavaScript Error objects.
 */
export async function createFiscalYear(
  businessId: number,
  request: CreateFiscalYearRequest,
): Promise<FiscalYear> {

  // ==========================================================
  // BUILD FISCAL YEARS ENDPOINT
  // ==========================================================

  const endpoint =
    `/api/v1/businesses/${businessId}/fiscal-years`

  // ==========================================================
  // SUBMIT CREATION REQUEST
  // ==========================================================

  return postJson<
    FiscalYear,
    CreateFiscalYearRequest
  >(
    endpoint,
    request,
  )
}
