import { useEffect, useMemo, useState } from 'react'

import {
  getTransactions,
  type LedgerTransaction,
} from '../api/transactions'

import NewJournalEntryForm from '../components/transactions/NewJournalEntryForm'

import TransactionDetails from '../components/transactions/TransactionDetails'

import './TransactionsPage.css'

// ============================================================
// LEDGERBASE — TRANSACTIONS PAGE
// ============================================================

/**
 * Display and manage financial transactions for a business.
 *
 * This page communicates with our Spring Boot backend
 * and retrieves real transaction data from PostgreSQL.
 *
 * Current functionality:
 *
 * - Retrieve financial transactions
 * - Display transaction statistics
 * - Search by description, journal reference, or internal ID
 * - Filter by posting status
 * - Sort transactions by accounting date
 * - Refresh transaction data
 * - Create draft journal entries
 * - View transaction details and ledger entries
 * - Post draft transactions
 * - Refresh the register after posting
 * - Display success notifications
 * - Handle loading, error, and empty states
 *
 * Future enhancements:
 *
 * - Display transaction amounts in the register
 * - Implement transaction reversal workflows
 * - Support authenticated users and permissions
 */

// ============================================================
// COMPONENT PROPERTIES
// ============================================================

/**
 * TransactionsPage receives the current business ID
 * from its parent component, App.tsx.
 *
 * App.tsx supplies the currently selected business ID.
 */
interface TransactionsPageProps {
  businessId: number
}

// ============================================================
// TRANSACTION FILTER TYPES
// ============================================================

/**
 * Filter values must match the transaction statuses
 * returned by the Spring Boot backend.
 */
type TransactionStatusFilter = 'all' | 'DRAFT' | 'POSTED'

// ============================================================
// DATE FORMATTING
// ============================================================

/**
 * Convert a PostgreSQL accounting date into
 * a human-readable format.
 *
 * Example:
 *
 * 2026-10-13 -> Oct 13, 2026
 *
 * UTC prevents the accounting date from shifting
 * when the browser is in a different timezone.
 */
function formatTransactionDate(date: string): string {

  return new Intl.DateTimeFormat('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  }).format(new Date(`${date}T00:00:00Z`))
}

// ============================================================
// TRANSACTIONS COMPONENT
// ============================================================

function TransactionsPage({
  businessId,
}: TransactionsPageProps) {

  // ============================================================
  // TRANSACTION DATA STATE
  // ============================================================

  /**
   * Store transaction headers retrieved from Spring Boot.
   *
   * The data uses the LedgerTransaction interface
   * defined in src/api/transactions.ts.
   */
  const [transactions, setTransactions] =
    useState<LedgerTransaction[]>([])

  // ============================================================
  // API REQUEST STATE
  // ============================================================

  /**
   * Track whether the transaction register is loading.
   */
  const [isLoading, setIsLoading] = useState(true)

  /**
   * Store any backend errors.
   */
  const [errorMessage, setErrorMessage] = useState('')

  // ============================================================
  // SEARCH AND FILTER STATE
  // ============================================================

  /**
   * Store the text used to search transactions.
   */
  const [searchQuery, setSearchQuery] = useState('')

  /**
   * Track the currently selected posting status filter.
   */
  const [statusFilter, setStatusFilter] =
    useState<TransactionStatusFilter>('all')

  // ============================================================
  // TRANSACTION REFRESH STATE
  // ============================================================

  /**
   * Increasing refreshVersion triggers useEffect,
   * retrieving the latest records from Spring Boot.
   *
   * This is used after:
   *
   * - Clicking Refresh
   * - Creating a draft transaction
   * - Posting an existing transaction
   */
  const [refreshVersion, setRefreshVersion] = useState(0)

  // ============================================================
  // JOURNAL ENTRY FORM STATE
  // ============================================================

  /**
   * Control whether the New Journal Entry form
   * appears above the Transaction Register.
   */
  const [isCreateFormOpen, setIsCreateFormOpen] =
    useState(false)

  // ============================================================
  // TRANSACTION DETAILS STATE
  // ============================================================

  /**
   * Store the ID of the transaction being viewed.
   *
   * null means the Transaction Details panel is closed.
   */
  const [selectedTransactionId, setSelectedTransactionId] =
    useState<number | null>(null)

  // ============================================================
  // SUCCESS NOTIFICATION STATE
  // ============================================================

  /**
   * Display success messages after creating
   * or posting a financial transaction.
   */
  const [successMessage, setSuccessMessage] =
    useState('')

  // ============================================================
  // LOAD TRANSACTIONS FROM SPRING BOOT
  // ============================================================

  /**
   * Retrieve transactions when:
   *
   * 1. The page first appears.
   * 2. The business ID changes.
   * 3. The user clicks Refresh.
   * 4. A new draft transaction is created.
   * 5. A draft transaction is successfully posted.
   *
   * The cancelled flag prevents outdated requests
   * from modifying React state.
   */
  useEffect(() => {

    let cancelled = false

    // ==========================================================
    // ASYNCHRONOUS TRANSACTION LOADER
    // ==========================================================

    async function loadTransactions(): Promise<void> {

      setIsLoading(true)
      setErrorMessage('')

      try {

        /**
         * GET /api/v1/businesses/{businessId}/transactions
         *
         * Spring Boot retrieves real records
         * from our PostgreSQL database.
         */
        const transactionData =
          await getTransactions(businessId)

        if (!cancelled) {
          setTransactions(transactionData)
        }

      } catch (error: unknown) {

        if (!cancelled) {
          setErrorMessage(
            error instanceof Error
              ? error.message
              : 'Unable to load transactions.',
          )
        }

      } finally {

        if (!cancelled) {
          setIsLoading(false)
        }
      }
    }

    void loadTransactions()

    // Ignore responses from outdated API requests.
    return () => {
      cancelled = true
    }

  }, [businessId, refreshVersion])

  // ============================================================
  // TRANSACTION STATISTICS
  // ============================================================

  /**
   * Calculate statistics from real database records.
   *
   * These values are not hardcoded.
   */
  const totalTransactions = transactions.length

  const draftTransactions = transactions.filter(
    (transaction) => transaction.status === 'DRAFT',
  ).length

  const postedTransactions = transactions.filter(
    (transaction) => transaction.status === 'POSTED',
  ).length

  /**
   * Hide statistics during loading or API errors.
   *
   * This avoids displaying potentially outdated results.
   */
  const showStatistics = !isLoading && !errorMessage

  // ============================================================
  // SEARCH, FILTER, AND SORT TRANSACTIONS
  // ============================================================

  /**
   * useMemo recalculates the displayed list when:
   *
   * - Transaction data changes
   * - Search text changes
   * - Status filter changes
   */
  const filteredTransactions = useMemo(() => {

    const query = searchQuery.trim().toLowerCase()

    return transactions

      // ========================================================
      // FILTER TRANSACTIONS
      // ========================================================

      .filter((transaction) => {

        /**
         * Search by description or company-specific reference.
         * Retain internal ID searching for diagnostics only;
         * IDs must not become user-facing references.
         */
        const matchesSearch =
          transaction.description.toLowerCase().includes(query) ||
          (transaction.journalReference ?? '')
            .toLowerCase().includes(query) ||
          String(transaction.id).includes(query)

        /**
         * Display only transactions matching
         * the selected posting status.
         */
        const matchesStatus =
          statusFilter === 'all' ||
          transaction.status === statusFilter

        return matchesSearch && matchesStatus
      })

      // ========================================================
      // SORT TRANSACTIONS
      // ========================================================

      /**
       * Display newest accounting dates first.
       *
       * When two transactions have the same date,
       * display the higher transaction ID first.
       */
      .sort((first, second) => {

        const dateComparison =
          second.transactionDate.localeCompare(
            first.transactionDate,
          )

        return dateComparison !== 0
          ? dateComparison
          : second.id - first.id
      })

  }, [transactions, searchQuery, statusFilter])

  // ============================================================
  // REFRESH TRANSACTION DATA
  // ============================================================

  /**
   * Request updated records without reloading
   * the entire React application.
   */
  function handleRefresh(): void {

    setRefreshVersion(
      (previousVersion) => previousVersion + 1,
    )
  }

  // ============================================================
  // OPEN NEW JOURNAL ENTRY FORM
  // ============================================================

  /**
   * Open the transaction creation form.
   *
   * Close the Transaction Details panel so
   * the user is focused on one workflow.
   */
  function handleOpenCreateForm(): void {

    setSuccessMessage('')

    setSelectedTransactionId(null)

    setIsCreateFormOpen(true)
  }

  // ============================================================
  // CANCEL JOURNAL ENTRY CREATION
  // ============================================================

  /**
   * Close the form without submitting
   * a transaction to Spring Boot.
   */
  function handleCancelCreateForm(): void {

    setIsCreateFormOpen(false)
  }

  // ============================================================
  // JOURNAL ENTRY CREATION SUCCESS
  // ============================================================

  /**
   * NewJournalEntryForm calls this function after
   * successfully creating a draft transaction.
   *
   * We:
   *
   * 1. Close the creation form.
   * 2. Display a success notification.
   * 3. Refresh the Transaction Register.
   */
  function handleTransactionCreated(
    transaction: LedgerTransaction,
  ): void {

    // ==========================================================
    // CLOSE JOURNAL ENTRY FORM
    // ==========================================================

    setIsCreateFormOpen(false)

    setSelectedTransactionId(null)

    // ==========================================================
    // DISPLAY SUCCESS NOTIFICATION
    // ==========================================================

    setSuccessMessage(
      transaction.journalReference
        ? `Draft ${transaction.journalReference} created successfully.`
        : 'Draft transaction created successfully.',
    )

    // ==========================================================
    // REFRESH TRANSACTION REGISTER
    // ==========================================================

    handleRefresh()
  }

  // ============================================================
  // OPEN TRANSACTION DETAILS
  // ============================================================

  /**
   * Display the selected transaction's actual
   * journal entries from Spring Boot.
   *
   * Close the creation form to avoid
   * displaying two panels simultaneously.
   */
  function handleViewDetails(transactionId: number): void {

    setIsCreateFormOpen(false)

    setSuccessMessage('')

    setSelectedTransactionId(transactionId)
  }

  // ============================================================
  // CLOSE TRANSACTION DETAILS
  // ============================================================

  /**
   * Closing the details panel does not modify
   * any database records.
   */
  function handleCloseDetails(): void {

    setSelectedTransactionId(null)
  }

  // ============================================================
  // TRANSACTION POSTING SUCCESS
  // ============================================================

  /**
   * TransactionDetails calls this function only
   * after Spring Boot successfully posts a transaction.
   *
   * We:
   *
   * 1. Display a success notification.
   * 2. Refresh the Transaction Register.
   * 3. Update the draft and posted statistics.
   *
   * The Transaction Details panel remains open.
   *
   * TransactionDetails independently updates its
   * local transaction status to POSTED.
   */
  function handleTransactionPosted(
    transaction: LedgerTransaction,
  ): void {

    // ==========================================================
    // DISPLAY SUCCESS NOTIFICATION
    // ==========================================================

    setSuccessMessage(
      transaction.journalReference
        ? `Journal entry ${transaction.journalReference} posted successfully.`
        : 'Transaction posted successfully.',
    )

    // ==========================================================
    // REFRESH TRANSACTION REGISTER
    // ==========================================================

    /**
     * Increasing refreshVersion triggers useEffect.
     *
     * The updated data will reflect:
     *
     * - One fewer draft transaction
     * - One additional posted transaction
     *
     * Total transaction count remains unchanged.
     */
    handleRefresh()
  }

  // ============================================================
  // RENDER TRANSACTIONS PAGE
  // ============================================================

  return (

    <section
      className="transactions-page"
      aria-busy={isLoading}
    >

      {/* ====================================================== */}
      {/* TRANSACTION SUCCESS NOTIFICATION */}
      {/* ====================================================== */}

      {successMessage && (

        <p
          className="transactions-success-notice"
          role="status"
        >
          {successMessage}
        </p>

      )}

      {/* ====================================================== */}
      {/* NEW JOURNAL ENTRY FORM */}
      {/* ====================================================== */}

      {isCreateFormOpen && (

        <NewJournalEntryForm
          businessId={businessId}
          onCreated={handleTransactionCreated}
          onCancel={handleCancelCreateForm}
        />

      )}

      {/* ====================================================== */}
      {/* TRANSACTION DETAILS PANEL */}
      {/* ====================================================== */}

      {selectedTransactionId !== null && (

        <TransactionDetails
          businessId={businessId}
          transactionId={selectedTransactionId}
          onClose={handleCloseDetails}
          onPosted={handleTransactionPosted}
        />

      )}

      {/* ====================================================== */}
      {/* TRANSACTION SUMMARY CARDS */}
      {/* ====================================================== */}

      <div className="transactions-summary-grid">

        {/* ==================================================== */}
        {/* TOTAL TRANSACTIONS */}
        {/* ==================================================== */}

        <article className="transactions-summary-card">

          <p>Total Transactions</p>

          <strong>
            {showStatistics ? totalTransactions : '—'}
          </strong>

        </article>

        {/* ==================================================== */}
        {/* DRAFT TRANSACTIONS */}
        {/* ==================================================== */}

        <article className="transactions-summary-card">

          <p>Draft Transactions</p>

          <strong>
            {showStatistics ? draftTransactions : '—'}
          </strong>

        </article>

        {/* ==================================================== */}
        {/* POSTED TRANSACTIONS */}
        {/* ==================================================== */}

        <article className="transactions-summary-card">

          <p>Posted Transactions</p>

          <strong>
            {showStatistics ? postedTransactions : '—'}
          </strong>

        </article>

      </div>

      {/* ====================================================== */}
      {/* TRANSACTION REGISTER */}
      {/* ====================================================== */}

      <section className="transactions-table-card">

        {/* ==================================================== */}
        {/* TABLE HEADING */}
        {/* ==================================================== */}

        <div className="transactions-table-heading">

          {/* Transaction Register title */}

          <div>

            <h2>Transaction Register</h2>

            <p>
              Review financial transactions and their
              posting statuses.
            </p>

          </div>

          {/* ================================================== */}
          {/* TRANSACTION ACTION BUTTONS */}
          {/* ================================================== */}

          <div className="transactions-table-actions">

            {/* ================================================ */}
            {/* NEW JOURNAL ENTRY BUTTON */}
            {/* ================================================ */}

            {!isCreateFormOpen && (

              <button
                type="button"
                className="transactions-create-button"
                onClick={handleOpenCreateForm}
              >
                + New Journal Entry
              </button>

            )}

            {/* ================================================ */}
            {/* REFRESH TRANSACTIONS BUTTON */}
            {/* ================================================ */}

            <button
              type="button"
              className="transactions-refresh-button"
              disabled={isLoading}
              onClick={handleRefresh}
            >
              {isLoading ? 'Refreshing...' : '↻ Refresh'}
            </button>

          </div>

        </div>

        {/* ==================================================== */}
        {/* SEARCH AND STATUS FILTER */}
        {/* ==================================================== */}

        <div className="transactions-toolbar">

          {/* ================================================ */}
          {/* SEARCH FIELD */}
          {/* ================================================ */}

          <label className="transactions-search">

            <span>Search transactions</span>

            <input
              type="search"
              value={searchQuery}
              placeholder="Search by description or journal reference..."
              onChange={(event) =>
                setSearchQuery(event.target.value)
              }
            />

          </label>

          {/* ================================================ */}
          {/* STATUS FILTER */}
          {/* ================================================ */}

          <label className="transactions-status-filter">

            <span>Status</span>

            <select
              value={statusFilter}
              onChange={(event) =>
                setStatusFilter(
                  event.target.value as TransactionStatusFilter,
                )
              }
            >

              <option value="all">
                All statuses
              </option>

              <option value="DRAFT">
                Draft
              </option>

              <option value="POSTED">
                Posted
              </option>

            </select>

          </label>

        </div>

        {/* ==================================================== */}
        {/* ERROR STATE */}
        {/* ==================================================== */}

        {errorMessage && (

          <div
            className="transactions-message"
            role="alert"
          >

            <h3>Unable to load transactions</h3>

            <p>{errorMessage}</p>

            <button
              type="button"
              onClick={handleRefresh}
            >
              Try Again
            </button>

          </div>

        )}

        {/* ==================================================== */}
        {/* LOADING STATE */}
        {/* ==================================================== */}

        {!errorMessage && isLoading && (

          <div
            className="transactions-message"
            role="status"
          >
            Loading transactions from LedgerBase...
          </div>

        )}

        {/* ==================================================== */}
        {/* EMPTY DATABASE STATE */}
        {/* ==================================================== */}

        {!errorMessage &&
          !isLoading &&
          transactions.length === 0 && (

            <div className="transactions-message">

              <h3>No transactions yet</h3>

              <p>
                Journal entries will appear here once
                you create your first transaction.
              </p>

            </div>

          )
        }

        {/* ==================================================== */}
        {/* NO MATCHING SEARCH RESULTS */}
        {/* ==================================================== */}

        {!errorMessage &&
          !isLoading &&
          transactions.length > 0 &&
          filteredTransactions.length === 0 && (

            <div className="transactions-message">

              <h3>No matching transactions</h3>

              <p>
                Try adjusting your search or status filter.
              </p>

            </div>

          )
        }

        {/* ==================================================== */}
        {/* TRANSACTIONS DATA TABLE */}
        {/* ==================================================== */}

        {!errorMessage &&
          !isLoading &&
          filteredTransactions.length > 0 && (

            <div className="transactions-table-scroll">

              <table className="transactions-table">

                {/* ============================================== */}
                {/* TABLE COLUMN HEADINGS */}
                {/* ============================================== */}

                <thead>

                  <tr>

                    <th scope="col">
                      Journal Reference
                    </th>

                    <th scope="col">
                      Date
                    </th>

                    <th scope="col">
                      Description
                    </th>

                    <th scope="col">
                      Status
                    </th>

                    <th scope="col">
                      Created By
                    </th>

                    {/* ======================================== */}
                    {/* ACTIONS COLUMN */}
                    {/* ======================================== */}

                    <th
                      scope="col"
                      className="transactions-actions-column"
                    >
                      Actions
                    </th>

                  </tr>

                </thead>

                {/* ============================================== */}
                {/* TRANSACTION TABLE ROWS */}
                {/* ============================================== */}

                <tbody>

                  {filteredTransactions.map((transaction) => (

                    <tr key={transaction.id}>

                      {/* ======================================== */}
                      {/* BUSINESS-SPECIFIC JOURNAL REFERENCE */}
                      {/* ======================================== */}

                      <td className="transactions-id">
                        {transaction.journalReference ?? '—'}
                      </td>

                      {/* ======================================== */}
                      {/* ACCOUNTING DATE */}
                      {/* ======================================== */}

                      <td>
                        {formatTransactionDate(
                          transaction.transactionDate,
                        )}
                      </td>

                      {/* ======================================== */}
                      {/* TRANSACTION DESCRIPTION */}
                      {/* ======================================== */}

                      <td className="transactions-description">
                        {transaction.description}
                      </td>

                      {/* ======================================== */}
                      {/* POSTING STATUS */}
                      {/* ======================================== */}

                      <td>

                        <span
                          className={
                            `transactions-status-badge ${
                              transaction.status === 'POSTED'
                                ? 'is-posted'
                                : transaction.status === 'DRAFT'
                                  ? 'is-draft'
                                  : 'is-other'
                            }`
                          }
                        >

                          {transaction.status === 'POSTED'
                            ? 'Posted'
                            : transaction.status === 'DRAFT'
                              ? 'Draft'
                              : transaction.status}

                        </span>

                      </td>

                      {/* ======================================== */}
                      {/* TRANSACTION CREATOR */}
                      {/* ======================================== */}

                      <td>
                        User #{transaction.createdBy}
                      </td>

                      {/* ======================================== */}
                      {/* VIEW TRANSACTION DETAILS */}
                      {/* ======================================== */}

                      <td className="transactions-actions-column">

                        <button
                          type="button"
                          className="transactions-view-button"
                          aria-label={
                            transaction.journalReference
                              ? `View ${transaction.journalReference} details`
                              : 'View transaction details'
                          }
                          onClick={() =>
                            handleViewDetails(transaction.id)
                          }
                        >
                          View Details
                        </button>

                      </td>

                    </tr>

                  ))}

                </tbody>

              </table>

            </div>

          )
        }

        {/* ==================================================== */}
        {/* TABLE FOOTER */}
        {/* ==================================================== */}

        {!isLoading && !errorMessage && (

          <div className="transactions-table-footer">

            Showing {filteredTransactions.length} of{' '}
            {totalTransactions} transactions

          </div>

        )}

      </section>

    </section>
  )
}

// ============================================================
// EXPORT COMPONENT
// ============================================================

export default TransactionsPage
