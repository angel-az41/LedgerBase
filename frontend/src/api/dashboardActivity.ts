import {
  getTransactions,
  type LedgerTransaction,
} from './transactions'

// ============================================================
// LEDGERBASE — DASHBOARD JOURNAL ACTIVITY API
// ============================================================

/**
 * Provide operational journal information for the
 * LedgerBase Overview dashboard.
 *
 * MILESTONE 21 — DASHBOARD ENHANCEMENTS
 *
 * EXISTING BACKEND ENDPOINT:
 *
 * GET /api/v1/businesses/{businessId}/transactions
 *
 * This module does not create or modify transactions.
 *
 * It converts the existing transaction response into:
 *
 * - Total journal count
 * - Posted journal count
 * - Draft journal count
 * - Five recently created journals
 *
 * IMPORTANT:
 *
 * Journal counts are operational information.
 *
 * They are NOT financial balances.
 *
 * Total Assets, Total Liabilities and Net Income
 * must continue coming from the existing financial
 * reporting endpoints.
 */

// ============================================================
// DASHBOARD ACTIVITY RESPONSE
// ============================================================

/**
 * Define the journal information displayed by
 * the Overview dashboard.
 *
 * All counts relate only to the selected business.
 */
export interface DashboardActivity {

  // Total number of transaction headers.
  totalTransactions: number

  // Number of finalized POSTED transactions.
  postedTransactions: number

  // Number of transactions awaiting posting.
  draftTransactions: number

  /**
   * Five most recently created journal transactions.
   *
   * Each record retains its original backend fields:
   *
   * - id
   * - journalReference
   * - description
   * - transactionDate
   * - status
   * - createdAt
   *
   * The UI will display the journal reference
   * rather than the internal database ID.
   */
  recentTransactions: LedgerTransaction[]
}

// ============================================================
// GET DASHBOARD JOURNAL ACTIVITY
// ============================================================

/**
 * Retrieve the selected business's transactions
 * and prepare dashboard activity information.
 *
 * PARAMETERS:
 *
 * businessId:
 *   The currently selected business in App.tsx.
 *
 * IMPORTANT:
 *
 * We reuse getTransactions() instead of creating
 * another HTTP request implementation.
 *
 * This is appropriate for our current MVP.
 *
 * For large production datasets, we can later
 * replace this with a paginated backend summary
 * endpoint to avoid downloading every transaction.
 */
export async function getDashboardActivity(
  businessId: number,
): Promise<DashboardActivity> {

  // ==========================================================
  // STEP 1 — FETCH BUSINESS TRANSACTIONS
  // ==========================================================

  /**
   * Spring Boot scopes the request to businessId.
   *
   * The returned records originate from PostgreSQL.
   */
  const transactions = await getTransactions(businessId)

  // ==========================================================
  // STEP 2 — COUNT ALL TRANSACTION HEADERS
  // ==========================================================

  /**
   * Total includes both posted and draft journals.
   *
   * It may also include other backend-supported
   * statuses introduced in future versions.
   */
  const totalTransactions = transactions.length

  // ==========================================================
  // STEP 3 — COUNT POSTED TRANSACTIONS
  // ==========================================================

  /**
   * POSTED transactions have been finalized.
   *
   * Their ledger entries contribute to financial
   * reporting according to our accounting rules.
   */
  const postedTransactions = transactions.filter(
    (transaction) => transaction.status === 'POSTED',
  ).length

  // ==========================================================
  // STEP 4 — COUNT DRAFT TRANSACTIONS
  // ==========================================================

  /**
   * DRAFT transactions have not been posted.
   *
   * They must not contribute to posted balances.
   */
  const draftTransactions = transactions.filter(
    (transaction) => transaction.status === 'DRAFT',
  ).length

  // ==========================================================
  // STEP 5 — IDENTIFY RECENT JOURNALS
  // ==========================================================

  /**
   * Sort by the creation timestamp.
   *
   * Why createdAt instead of transactionDate?
   *
   * Accounting dates may be backdated.
   *
   * A journal created today for an earlier period
   * should still appear in the recently created list.
   *
   * Copy the array before sorting to avoid mutating
   * the original API response.
   */
  const recentTransactions = [...transactions]

    .sort((first, second) => {

      // Compare timestamps as actual instants.
      const firstTimestamp = Date.parse(first.createdAt)
      const secondTimestamp = Date.parse(second.createdAt)

      if (
        Number.isFinite(firstTimestamp) &&
        Number.isFinite(secondTimestamp) &&
        firstTimestamp !== secondTimestamp
      ) {
        return secondTimestamp - firstTimestamp
      }

      // Use the internal ID as a stable fallback.
      return second.id - first.id

    })

    // Limit the Overview page to five journals.
    .slice(0, 5)

  // ==========================================================
  // STEP 6 — RETURN DASHBOARD ACTIVITY
  // ==========================================================

  /**
   * Return the prepared information to React.
   *
   * No monetary arithmetic is performed here.
   */
  return {
    totalTransactions,
    postedTransactions,
    draftTransactions,
    recentTransactions,
  }
}
