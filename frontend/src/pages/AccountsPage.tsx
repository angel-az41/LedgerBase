import { useEffect, useMemo, useState } from 'react'

import {
  getAccounts,
  type Account,
} from '../api/accounts'

import NewAccountForm from '../components/accounts/NewAccountForm'
import AccountActions from '../components/accounts/AccountActions'

import './AccountsPage.css'

// ============================================================
// LEDGERBASE — CHART OF ACCOUNTS PAGE
// ============================================================

/**
 * This React component manages the Chart of Accounts
 * interface for the selected business.
 *
 * It communicates with our existing Spring Boot backend
 * and displays real financial accounts from PostgreSQL.
 *
 * Supported functionality:
 *
 * - Retrieve accounts from Spring Boot
 * - Display account statistics
 * - Search accounts by code, name, or type
 * - Filter active and inactive accounts
 * - Refresh account data
 * - Create new general ledger accounts
 * - Rename existing active accounts
 * - Deactivate accounts with confirmation
 * - Display success notifications
 * - Handle loading, error, and empty states
 *
 * Future enhancements:
 *
 * - Account reporting groups
 * - Account hierarchy
 * - Financial statement mapping
 */

// ============================================================
// COMPONENT PROPERTIES
// ============================================================

/**
 * AccountsPage receives the selected business ID
 * from its parent component, App.tsx.
 *
 * During local development, businessId is currently 1.
 *
 * When authentication is implemented, this value
 * will come from the user's selected business.
 */
interface AccountsPageProps {
  businessId: number
}

// ============================================================
// ACCOUNT FILTER TYPES
// ============================================================

/**
 * Define the supported account status filters.
 *
 * TypeScript prevents unsupported filter values
 * from being assigned accidentally.
 */
type StatusFilter = 'all' | 'active' | 'inactive'

// ============================================================
// ACCOUNT FORMATTING HELPERS
// ============================================================

/**
 * Convert backend account classifications into
 * human-readable labels.
 *
 * Examples:
 *
 * "ASSET" -> "Asset"
 * "LIABILITY" -> "Liability"
 * "REVENUE" -> "Revenue"
 */
function formatAccountType(
  accountType: string,
): string {

  const lowercaseType = accountType.toLowerCase()

  return lowercaseType.charAt(0).toUpperCase()
    + lowercaseType.slice(1).replaceAll('_', ' ')
}

/**
 * Convert an ISO timestamp returned by Spring Boot
 * into a readable account creation date.
 *
 * Example:
 *
 * "2026-10-12T20:05:02.433124Z"
 *
 * becomes:
 *
 * "Oct 12, 2026"
 *
 * UTC ensures the display does not shift to
 * another calendar date due to local time zones.
 */
function formatDate(
  dateString: string,
): string {

  return new Intl.DateTimeFormat('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  }).format(new Date(dateString))
}

// ============================================================
// CHART OF ACCOUNTS COMPONENT
// ============================================================

/**
 * Display and manage general ledger accounts.
 *
 * React hooks are used to manage:
 *
 * - Account data from Spring Boot
 * - Loading and error states
 * - Search and filtering
 * - Account creation form visibility
 * - Success notifications
 * - Account table refreshes
 *
 * Individual rename and deactivate workflows
 * are handled by the AccountActions component.
 */
function AccountsPage({
  businessId,
}: AccountsPageProps) {

  // ============================================================
  // ACCOUNT DATA STATE
  // ============================================================

  /**
   * Store accounts retrieved from Spring Boot.
   *
   * useState<Account[]> ensures that every
   * account matches the Account interface
   * defined in src/api/accounts.ts.
   */
  const [accounts, setAccounts] = useState<Account[]>([])

  // ============================================================
  // API REQUEST STATE
  // ============================================================

  /**
   * Indicate when account data is loading.
   *
   * Start with true because accounts are
   * fetched when the page first appears.
   */
  const [isLoading, setIsLoading] = useState(true)

  /**
   * Store errors from account listing requests.
   *
   * An empty string indicates no current error.
   */
  const [errorMessage, setErrorMessage] = useState('')

  // ============================================================
  // ACCOUNT SEARCH AND FILTER STATE
  // ============================================================

  /**
   * Store the user's account search text.
   *
   * This is a controlled React input.
   */
  const [searchQuery, setSearchQuery] = useState('')

  /**
   * Store the selected account status filter.
   *
   * By default, display all accounts.
   */
  const [statusFilter, setStatusFilter] =
    useState<StatusFilter>('all')

  // ============================================================
  // ACCOUNT REFRESH STATE
  // ============================================================

  /**
   * Track when the account list should refresh.
   *
   * Increasing refreshVersion triggers useEffect
   * to fetch updated accounts from Spring Boot.
   *
   * This avoids duplicating our account loading
   * logic across different event handlers.
   */
  const [refreshVersion, setRefreshVersion] = useState(0)

  // ============================================================
  // CREATE ACCOUNT FORM STATE
  // ============================================================

  /**
   * Control whether NewAccountForm is visible.
   *
   * false: Hide the form.
   * true: Display the form above the account table.
   */
  const [isCreateFormOpen, setIsCreateFormOpen] =
    useState(false)

  // ============================================================
  // SUCCESS NOTIFICATION STATE
  // ============================================================

  /**
   * Display confirmation messages after
   * successful account operations.
   *
   * These include:
   *
   * - Account creation
   * - Account renaming
   * - Account deactivation
   */
  const [successMessage, setSuccessMessage] = useState('')

  // ============================================================
  // LOAD ACCOUNTS FROM SPRING BOOT
  // ============================================================

  /**
   * React's useEffect runs after rendering.
   *
   * This effect retrieves account data when:
   *
   * 1. The component first mounts.
   * 2. The selected business changes.
   * 3. refreshVersion increases.
   *
   * The cleanup function prevents outdated requests
   * from updating state after the component unmounts
   * or its dependencies change.
   */
  useEffect(() => {

    let cancelled = false

    // ==========================================================
    // ASYNCHRONOUS ACCOUNT LOADER
    // ==========================================================

    async function loadAccounts(): Promise<void> {

      // Indicate that the request has started.
      setIsLoading(true)

      // Clear the previous loading error.
      setErrorMessage('')

      try {

        /**
         * Send:
         *
         * GET /api/v1/businesses/{businessId}/accounts
         *
         * Vite forwards this request to Spring Boot.
         *
         * Spring Boot retrieves account records
         * from PostgreSQL using MyBatis.
         */
        const accountData = await getAccounts(businessId)

        /**
         * Only update React state if the request
         * still belongs to the current effect.
         */
        if (!cancelled) {
          setAccounts(accountData)
        }

      } catch (error: unknown) {

        /**
         * Display an error if the backend request
         * fails or the connection is unavailable.
         */
        if (!cancelled) {

          setErrorMessage(
            error instanceof Error
              ? error.message
              : 'Unable to load accounts.',
          )
        }

      } finally {

        // Stop displaying the loading indicator.
        if (!cancelled) {
          setIsLoading(false)
        }
      }
    }

    // Execute the asynchronous account request.
    void loadAccounts()

    /**
     * Cleanup:
     *
     * Ignore outdated responses.
     *
     * React Strict Mode may run effects multiple
     * times during development.
     */
    return () => {
      cancelled = true
    }

  }, [businessId, refreshVersion])

  // ============================================================
  // ACCOUNT STATISTICS
  // ============================================================

  /**
   * Calculate summary metrics from actual account data.
   *
   * These values are never hardcoded.
   */
  const totalAccounts = accounts.length

  /**
   * Count active accounts.
   */
  const activeAccounts = accounts.filter(
    (account) => account.active,
  ).length

  /**
   * Count inactive accounts.
   */
  const inactiveAccounts = totalAccounts - activeAccounts

  /**
   * Avoid displaying misleading account totals
   * while loading or when an API error occurs.
   */
  const showStatistics = !isLoading && !errorMessage

  // ============================================================
  // SEARCH AND FILTER ACCOUNTS
  // ============================================================

  /**
   * useMemo calculates the visible account list.
   *
   * React recalculates it when:
   *
   * - Accounts change
   * - Search text changes
   * - Status filter changes
   */
  const filteredAccounts = useMemo(() => {

    /**
     * Normalize the search query.
     *
     * This allows case-insensitive searching.
     */
    const query = searchQuery.trim().toLowerCase()

    return accounts

      // ========================================================
      // FILTER ACCOUNTS
      // ========================================================

      .filter((account) => {

        /**
         * Match account code, name, or type.
         *
         * Example:
         *
         * Searching "cash" matches
         * "Main Cash Account".
         */
        const matchesSearch =
          account.code.toLowerCase().includes(query) ||
          account.name.toLowerCase().includes(query) ||
          account.accountType.toLowerCase().includes(query)

        /**
         * Match the selected active status.
         */
        const matchesStatus =
          statusFilter === 'all' ||
          (
            statusFilter === 'active' &&
            account.active
          ) ||
          (
            statusFilter === 'inactive' &&
            !account.active
          )

        return matchesSearch && matchesStatus
      })

      // ========================================================
      // SORT ACCOUNTS
      // ========================================================

      /**
       * Sort by account code.
       *
       * Numeric sorting makes codes such as
       * 1000, 1010, and 1020 appear in order.
       */
      .sort((first, second) =>
        first.code.localeCompare(
          second.code,
          undefined,
          { numeric: true },
        ),
      )

  }, [accounts, searchQuery, statusFilter])

  // ============================================================
  // CREATE ACCOUNT — OPEN FORM
  // ============================================================

  /**
   * Open the New Account form.
   *
   * Clear an earlier success message before
   * beginning another creation workflow.
   */
  function handleOpenCreateForm(): void {

    setSuccessMessage('')
    setIsCreateFormOpen(true)
  }

  // ============================================================
  // CREATE ACCOUNT — CANCEL FORM
  // ============================================================

  /**
   * Close the New Account form without submitting.
   *
   * No backend request is sent.
   */
  function handleCancelCreateForm(): void {

    setIsCreateFormOpen(false)
  }

  // ============================================================
  // CREATE ACCOUNT — SUCCESS HANDLER
  // ============================================================

  /**
   * Run after NewAccountForm successfully creates
   * a general ledger account.
   *
   * NewAccountForm calls the onCreated callback
   * and provides the account returned by Spring Boot.
   */
  function handleAccountCreated(
    account: Account,
  ): void {

    // ==========================================================
    // CLOSE CREATE ACCOUNT FORM
    // ==========================================================

    setIsCreateFormOpen(false)

    // ==========================================================
    // DISPLAY SUCCESS NOTIFICATION
    // ==========================================================

    /**
     * Include the actual code and account name
     * returned by the backend.
     */
    setSuccessMessage(
      `Account ${account.code} — ${account.name} created successfully.`,
    )

    // ==========================================================
    // REFRESH ACCOUNT LIST
    // ==========================================================

    /**
     * Trigger useEffect to fetch the updated
     * account records from PostgreSQL.
     */
    setRefreshVersion(
      (previousVersion) => previousVersion + 1,
    )
  }

  // ============================================================
  // ACCOUNT MANAGEMENT — SUCCESS HANDLER
  // ============================================================

  /**
   * Run after AccountActions successfully renames
   * or deactivates an account.
   *
   * AccountActions handles the PATCH request.
   *
   * AccountsPage is responsible for:
   *
   * 1. Displaying confirmation.
   * 2. Refreshing the account list.
   *
   * This keeps account management logic
   * separated from table rendering.
   */
  function handleAccountUpdated(
    message: string,
  ): void {

    // ==========================================================
    // DISPLAY SUCCESS NOTIFICATION
    // ==========================================================

    setSuccessMessage(message)

    // ==========================================================
    // REFRESH ACCOUNT LIST
    // ==========================================================

    /**
     * Retrieve updated account names and statuses
     * from Spring Boot after the PATCH succeeds.
     */
    setRefreshVersion(
      (previousVersion) => previousVersion + 1,
    )
  }

  // ============================================================
  // REFRESH ACCOUNT DATA
  // ============================================================

  /**
   * Request fresh accounts without reloading
   * the entire browser page.
   */
  function handleRefresh(): void {

    setRefreshVersion(
      (previousVersion) => previousVersion + 1,
    )
  }

  // ============================================================
  // RENDER CHART OF ACCOUNTS
  // ============================================================

  return (

    <section
      className="coa-page"
      aria-busy={isLoading}
    >

      {/* ====================================================== */}
      {/* ACCOUNT SUMMARY CARDS */}
      {/* ====================================================== */}

      <div className="coa-summary-grid">

        {/* ==================================================== */}
        {/* TOTAL ACCOUNTS */}
        {/* ==================================================== */}

        <article className="coa-summary-card">

          <p>Total Accounts</p>

          <strong>
            {showStatistics ? totalAccounts : '—'}
          </strong>

        </article>

        {/* ==================================================== */}
        {/* ACTIVE ACCOUNTS */}
        {/* ==================================================== */}

        <article className="coa-summary-card">

          <p>Active Accounts</p>

          <strong>
            {showStatistics ? activeAccounts : '—'}
          </strong>

        </article>

        {/* ==================================================== */}
        {/* INACTIVE ACCOUNTS */}
        {/* ==================================================== */}

        <article className="coa-summary-card">

          <p>Inactive Accounts</p>

          <strong>
            {showStatistics ? inactiveAccounts : '—'}
          </strong>

        </article>

      </div>

      {/* ====================================================== */}
      {/* ACCOUNT OPERATION SUCCESS MESSAGE */}
      {/* ====================================================== */}

      {successMessage && (

        <p
          className="coa-success-notice"
          role="status"
        >
          {successMessage}
        </p>

      )}

      {/* ====================================================== */}
      {/* NEW ACCOUNT FORM */}
      {/* ====================================================== */}

      {isCreateFormOpen && (

        <NewAccountForm
          businessId={businessId}
          onCreated={handleAccountCreated}
          onCancel={handleCancelCreateForm}
        />

      )}

      {/* ====================================================== */}
      {/* ACCOUNTS TABLE CONTAINER */}
      {/* ====================================================== */}

      <section className="coa-table-card">

        {/* ==================================================== */}
        {/* TABLE HEADING */}
        {/* ==================================================== */}

        <div className="coa-table-heading">

          {/* Table title and description */}
          <div>

            <h2>All Accounts</h2>

            <p>
              View and organize your business's
              general ledger accounts.
            </p>

          </div>

          {/* ================================================== */}
          {/* TABLE ACTION BUTTONS */}
          {/* ================================================== */}

          <div className="coa-table-actions">

            {/* ================================================ */}
            {/* CREATE ACCOUNT BUTTON */}
            {/* ================================================ */}

            {!isCreateFormOpen && (

              <button
                type="button"
                className="coa-create-button"
                disabled={isLoading}
                onClick={handleOpenCreateForm}
              >
                + New Account
              </button>

            )}

            {/* ================================================ */}
            {/* REFRESH ACCOUNTS BUTTON */}
            {/* ================================================ */}

            <button
              type="button"
              className="coa-refresh-button"
              disabled={isLoading}
              onClick={handleRefresh}
            >
              {isLoading ? 'Refreshing...' : '↻ Refresh'}
            </button>

          </div>

        </div>

        {/* ==================================================== */}
        {/* ACCOUNT SEARCH AND FILTER CONTROLS */}
        {/* ==================================================== */}

        <div className="coa-toolbar">

          {/* ================================================== */}
          {/* SEARCH FIELD */}
          {/* ================================================== */}

          <label className="coa-search">

            <span className="coa-visually-hidden">
              Search accounts
            </span>

            <span aria-hidden="true">
              ⌕
            </span>

            <input
              type="search"
              value={searchQuery}
              placeholder="Search by code, name, or type..."
              onChange={(event) =>
                setSearchQuery(event.target.value)
              }
            />

          </label>

          {/* ================================================== */}
          {/* STATUS FILTER */}
          {/* ================================================== */}

          <label className="coa-status-filter">

            <span>Status</span>

            <select
              value={statusFilter}
              onChange={(event) =>
                setStatusFilter(
                  event.target.value as StatusFilter,
                )
              }
            >

              <option value="all">
                All statuses
              </option>

              <option value="active">
                Active
              </option>

              <option value="inactive">
                Inactive
              </option>

            </select>

          </label>

        </div>

        {/* ==================================================== */}
        {/* ERROR STATE */}
        {/* ==================================================== */}

        {errorMessage && (

          <div
            className="coa-message coa-error-state"
            role="alert"
          >

            <h3>
              Unable to load accounts
            </h3>

            <p>
              {errorMessage}
            </p>

            <button
              type="button"
              className="coa-refresh-button"
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
            className="coa-message"
            role="status"
          >

            <div
              className="coa-spinner"
              aria-hidden="true"
            />

            <p>
              Loading accounts from LedgerBase...
            </p>

          </div>

        )}

        {/* ==================================================== */}
        {/* EMPTY DATABASE STATE */}
        {/* ==================================================== */}

        {!errorMessage &&
          !isLoading &&
          accounts.length === 0 && (

            <div className="coa-message">

              <h3>
                No accounts yet
              </h3>

              <p>
                Create your first general ledger
                account using the New Account button.
              </p>

            </div>

          )
        }

        {/* ==================================================== */}
        {/* NO MATCHING SEARCH RESULTS */}
        {/* ==================================================== */}

        {!errorMessage &&
          !isLoading &&
          accounts.length > 0 &&
          filteredAccounts.length === 0 && (

            <div className="coa-message">

              <h3>
                No matching accounts
              </h3>

              <p>
                Try adjusting your search or
                account status filter.
              </p>

            </div>

          )
        }

        {/* ==================================================== */}
        {/* ACCOUNTS DATA TABLE */}
        {/* ==================================================== */}

        {!errorMessage &&
          !isLoading &&
          filteredAccounts.length > 0 && (

            <div className="coa-table-scroll">

              <table className="coa-table">

                {/* ============================================== */}
                {/* TABLE COLUMN HEADINGS */}
                {/* ============================================== */}

                <thead>

                  <tr>

                    <th scope="col">
                      Account Code
                    </th>

                    <th scope="col">
                      Account Name
                    </th>

                    <th scope="col">
                      Account Type
                    </th>

                    <th scope="col">
                      Status
                    </th>

                    <th scope="col">
                      Created
                    </th>

                    {/* ======================================== */}
                    {/* ACCOUNT MANAGEMENT COLUMN */}
                    {/* ======================================== */}

                    <th
                      scope="col"
                      className="coa-actions-column"
                    >
                      Actions
                    </th>

                  </tr>

                </thead>

                {/* ============================================== */}
                {/* ACCOUNT TABLE ROWS */}
                {/* ============================================== */}

                <tbody>

                  {filteredAccounts.map((account) => (

                    <tr key={account.id}>

                      {/* ======================================== */}
                      {/* ACCOUNT CODE */}
                      {/* ======================================== */}

                      <td className="coa-account-code">
                        {account.code}
                      </td>

                      {/* ======================================== */}
                      {/* ACCOUNT NAME */}
                      {/* ======================================== */}

                      <td className="coa-account-name">
                        {account.name}
                      </td>

                      {/* ======================================== */}
                      {/* ACCOUNT TYPE */}
                      {/* ======================================== */}

                      <td>
                        {formatAccountType(account.accountType)}
                      </td>

                      {/* ======================================== */}
                      {/* ACCOUNT STATUS */}
                      {/* ======================================== */}

                      <td>

                        <span
                          className={
                            account.active
                              ? 'coa-status-badge is-active'
                              : 'coa-status-badge is-inactive'
                          }
                        >

                          <span className="coa-status-dot" />

                          {account.active
                            ? 'Active'
                            : 'Inactive'
                          }

                        </span>

                      </td>

                      {/* ======================================== */}
                      {/* ACCOUNT CREATION DATE */}
                      {/* ======================================== */}

                      <td className="coa-date">
                        {formatDate(account.createdAt)}
                      </td>

                      {/* ======================================== */}
                      {/* ACCOUNT MANAGEMENT ACTIONS */}
                      {/* ======================================== */}

                      <td className="coa-actions-column">

                        {/*
                          AccountActions is responsible
                          for managing an individual account.

                          It receives:

                          businessId:
                            The account's business.

                          account:
                            The selected account record.

                          onUpdated:
                            Callback after a successful
                            rename or deactivation.

                          The parent refreshes the account
                          list when a modification succeeds.
                        */}
                        <AccountActions
                          businessId={businessId}
                          account={account}
                          onUpdated={handleAccountUpdated}
                        />

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

          <div className="coa-table-footer">

            Showing {filteredAccounts.length}
            {' '}of{' '}
            {totalAccounts} accounts

          </div>

        )}

      </section>

    </section>

  )
}

// ============================================================
// EXPORT COMPONENT
// ============================================================

export default AccountsPage
