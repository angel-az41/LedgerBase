import { useEffect, useMemo, useState } from 'react'

import {
  getAccounts,
  getAccountBalances,
  type Account,
  type AccountBalance,
} from '../api/accounts'

import NewAccountForm from '../components/accounts/NewAccountForm'
import AccountActions from '../components/accounts/AccountActions'

import './AccountsPage.css'

// ============================================================
// LEDGERBASE — CHART OF ACCOUNTS PAGE
// ============================================================

/**
 * Display and manage a business's Chart of Accounts.
 *
 * Account information and calculated balances are
 * retrieved from our existing Spring Boot backend.
 *
 * Supported functionality:
 *
 * - Retrieve accounts from PostgreSQL
 * - Display real posted account balances
 * - Display account statistics
 * - Search accounts by code, name, or type
 * - Filter active and inactive accounts
 * - Refresh account data and balances
 * - Create new accounts
 * - Rename active accounts
 * - Deactivate accounts with confirmation
 * - Display success notifications
 * - Handle loading, error, and empty states
 *
 * All existing account management components and
 * CSS class names are preserved.
 */

// ============================================================
// COMPONENT PROPERTIES
// ============================================================

/**
 * The selected business ID comes from App.tsx.
 *
 * During local development, we use business 1.
 * Business selection will become dynamic later.
 */
interface AccountsPageProps {
  businessId: number
}

// ============================================================
// ACCOUNT FILTER TYPES
// ============================================================

/**
 * Supported status filters for the Chart of Accounts.
 */
type StatusFilter = 'all' | 'active' | 'inactive'

// ============================================================
// ACCOUNT FORMATTING HELPERS
// ============================================================

/**
 * Convert backend account types to readable labels.
 *
 * Examples:
 *
 * "ASSET" -> "Asset"
 * "LIABILITY" -> "Liability"
 */
function formatAccountType(
  accountType: string,
): string {

  const lowercaseType = accountType.toLowerCase()

  return lowercaseType.charAt(0).toUpperCase()
    + lowercaseType.slice(1).replaceAll('_', ' ')
}

/**
 * Format the account creation date returned by Spring Boot.
 *
 * UTC prevents date changes due to the browser's
 * local time zone.
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
// PRECISION-SAFE ACCOUNT BALANCE FORMATTING
// ============================================================

/**
 * Format a decimal string as a USD monetary amount.
 *
 * Examples:
 *
 * "500.00"    -> "$500.00"
 * "-500.00"   -> "−$500.00"
 * "0"         -> "$0.00"
 * "12345.60"  -> "$12,345.60"
 *
 * IMPORTANT:
 *
 * We do not use:
 *
 * - parseFloat()
 * - Number()
 * - JavaScript floating-point calculations
 *
 * The backend calculates the balance using
 * PostgreSQL NUMERIC and Java BigDecimal.
 *
 * The frontend only formats the resulting string.
 *
 * Our current development business uses USD.
 * Multi-currency display will be added when
 * business currency configuration reaches the UI.
 *
 * If the balance is missing or malformed,
 * display an em dash instead of inventing $0.00.
 */
function formatAccountBalance(
  balance: string | undefined,
): string {

  // ==========================================================
  // CHECK THAT A BALANCE WAS RETURNED
  // ==========================================================

  if (balance === undefined) {
    return '—'
  }

  // ==========================================================
  // VALIDATE THE DECIMAL STRING
  // ==========================================================

  /**
   * Accept:
   *
   * - Optional negative sign
   * - One or more whole-number digits
   * - Optional decimal point and one or two digits
   *
   * Our PostgreSQL money columns use two
   * decimal places, so additional precision
   * would indicate an unexpected API response.
   */
  const match = /^(-?)(\d+)(?:\.(\d{1,2}))?$/.exec(balance)

  if (!match) {
    return '—'
  }

  // ==========================================================
  // EXTRACT DECIMAL COMPONENTS
  // ==========================================================

  const negativeSign = match[1]
  const wholePart = match[2]
  const decimalPart = match[3] ?? ''

  // ==========================================================
  // ADD THOUSANDS SEPARATORS
  // ==========================================================

  /**
   * Add commas using string manipulation only.
   *
   * No floating-point conversion occurs.
   */
  const formattedWholePart = wholePart.replace(
    /\B(?=(\d{3})+(?!\d))/g,
    ',',
  )

  // ==========================================================
  // ENSURE TWO DECIMAL PLACES
  // ==========================================================

  /**
   * PostgreSQL may return "0" or "0.0".
   * The interface should consistently show "$0.00".
   */
  const formattedDecimalPart = decimalPart.padEnd(2, '0')

  // ==========================================================
  // RETURN FORMATTED USD VALUE
  // ==========================================================

  const sign = negativeSign ? '−' : ''

  return `${sign}$${formattedWholePart}.${formattedDecimalPart}`
}

// ============================================================
// CHART OF ACCOUNTS COMPONENT
// ============================================================

function AccountsPage({
  businessId,
}: AccountsPageProps) {

  // ============================================================
  // ACCOUNT DATA STATE
  // ============================================================

  /**
   * Store general ledger accounts returned by:
   *
   * GET /api/v1/businesses/{businessId}/accounts
   *
   * These records contain createdAt and are also
   * passed to our existing AccountActions component.
   */
  const [accounts, setAccounts] = useState<Account[]>([])

  // ============================================================
  // ACCOUNT BALANCE DATA STATE
  // ============================================================

  /**
   * Store calculated balances returned by:
   *
   * GET /api/v1/businesses/{businessId}/accounts/balances
   *
   * Monetary properties are decimal strings.
   */
  const [accountBalances, setAccountBalances] =
    useState<AccountBalance[]>([])

  // ============================================================
  // API REQUEST STATE
  // ============================================================

  const [isLoading, setIsLoading] = useState(true)

  const [errorMessage, setErrorMessage] = useState('')

  // ============================================================
  // ACCOUNT SEARCH AND FILTER STATE
  // ============================================================

  const [searchQuery, setSearchQuery] = useState('')

  const [statusFilter, setStatusFilter] =
    useState<StatusFilter>('all')

  // ============================================================
  // ACCOUNT REFRESH STATE
  // ============================================================

  /**
   * Increasing refreshVersion reloads both
   * account information and account balances.
   */
  const [refreshVersion, setRefreshVersion] = useState(0)

  // ============================================================
  // CREATE ACCOUNT FORM STATE
  // ============================================================

  const [isCreateFormOpen, setIsCreateFormOpen] =
    useState(false)

  // ============================================================
  // SUCCESS NOTIFICATION STATE
  // ============================================================

  const [successMessage, setSuccessMessage] = useState('')

  // ============================================================
  // LOAD ACCOUNTS AND BALANCES FROM SPRING BOOT
  // ============================================================

  /**
   * Fetch account details and calculated balances when:
   *
   * 1. The page mounts.
   * 2. The selected business changes.
   * 3. An account is created, renamed, or deactivated.
   * 4. The user clicks Refresh.
   *
   * We use Promise.all() so both requests are sent
   * without unnecessarily waiting for one another.
   *
   * If either request fails, the page displays
   * an error instead of showing misleading balances.
   */
  useEffect(() => {

    let cancelled = false

    // ==========================================================
    // ASYNCHRONOUS ACCOUNT DATA LOADER
    // ==========================================================

    async function loadAccounts(): Promise<void> {

      setIsLoading(true)
      setErrorMessage('')

      try {

        // ======================================================
        // REQUEST ACCOUNT INFORMATION AND POSTED BALANCES
        // ======================================================

        /**
         * The first endpoint returns Account[].
         *
         * The second endpoint returns AccountBalance[].
         *
         * Both are scoped to the same business.
         */
        const [accountData, balanceData] = await Promise.all([
          getAccounts(businessId),
          getAccountBalances(businessId),
        ])

        // ======================================================
        // UPDATE REACT STATE
        // ======================================================

        /**
         * Ignore stale responses when the business
         * changes or the component unmounts.
         */
        if (!cancelled) {
          setAccounts(accountData)
          setAccountBalances(balanceData)
        }

      } catch (error: unknown) {

        // ======================================================
        // HANDLE API ERRORS
        // ======================================================

        if (!cancelled) {

          setErrorMessage(
            error instanceof Error
              ? error.message
              : 'Unable to load account data.',
          )
        }

      } finally {

        if (!cancelled) {
          setIsLoading(false)
        }
      }
    }

    void loadAccounts()

    // ==========================================================
    // CLEAN UP OUTDATED REQUESTS
    // ==========================================================

    return () => {
      cancelled = true
    }

  }, [businessId, refreshVersion])

  // ============================================================
  // MATCH ACCOUNT BALANCES BY ACCOUNT ID
  // ============================================================

  /**
   * Accounts and balances come from separate endpoints.
   *
   * We match them using the unique account ID,
   * not the account code or name.
   *
   * Example:
   *
   * Account:
   *   id: 8
   *   name: "Business Savings"
   *
   * AccountBalance:
   *   id: 8
   *   balance: "500.00"
   *
   * Map result:
   *   8 -> "500.00"
   *
   * This avoids confusion if multiple businesses
   * use the same account code.
   *
   * useMemo recalculates the map only when
   * accountBalances changes.
   */
  const balancesByAccountId = useMemo(() => {

    return new Map(
      accountBalances.map(
        (accountBalance) => [
          accountBalance.id,
          accountBalance.balance,
        ],
      ),
    )

  }, [accountBalances])

  // ============================================================
  // ACCOUNT STATISTICS
  // ============================================================

  /**
   * These summary metrics come from real account data.
   * They are not hardcoded.
   */
  const totalAccounts = accounts.length

  const activeAccounts = accounts.filter(
    (account) => account.active,
  ).length

  const inactiveAccounts = totalAccounts - activeAccounts

  /**
   * Hide statistics while loading or when an
   * API request has failed.
   */
  const showStatistics = !isLoading && !errorMessage

  // ============================================================
  // SEARCH AND FILTER ACCOUNTS
  // ============================================================

  /**
   * Preserve our existing search and filtering behavior.
   *
   * Searching and filtering operate on account details.
   * The matching balance is displayed alongside
   * each visible account.
   */
  const filteredAccounts = useMemo(() => {

    const query = searchQuery.trim().toLowerCase()

    return accounts

      // ========================================================
      // FILTER ACCOUNTS
      // ========================================================

      .filter((account) => {

        const matchesSearch =
          account.code.toLowerCase().includes(query) ||
          account.name.toLowerCase().includes(query) ||
          account.accountType.toLowerCase().includes(query)

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

  function handleOpenCreateForm(): void {
    setSuccessMessage('')
    setIsCreateFormOpen(true)
  }

  // ============================================================
  // CREATE ACCOUNT — CANCEL FORM
  // ============================================================

  function handleCancelCreateForm(): void {
    setIsCreateFormOpen(false)
  }

  // ============================================================
  // CREATE ACCOUNT — SUCCESS HANDLER
  // ============================================================

  /**
   * Preserve the existing account creation workflow.
   *
   * After creation:
   *
   * - Close the form
   * - Show the success message
   * - Refresh accounts and balances
   */
  function handleAccountCreated(
    account: Account,
  ): void {

    setIsCreateFormOpen(false)

    setSuccessMessage(
      `Account ${account.code} — ${account.name} created successfully.`,
    )

    setRefreshVersion(
      (previousVersion) => previousVersion + 1,
    )
  }

  // ============================================================
  // ACCOUNT MANAGEMENT — SUCCESS HANDLER
  // ============================================================

  /**
   * AccountActions remains responsible for
   * renaming and deactivating accounts.
   *
   * Refresh the account table after a successful
   * management operation.
   */
  function handleAccountUpdated(
    message: string,
  ): void {

    setSuccessMessage(message)

    setRefreshVersion(
      (previousVersion) => previousVersion + 1,
    )
  }

  // ============================================================
  // REFRESH ACCOUNT DATA
  // ============================================================

  /**
   * Reload both account details and balances.
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
              Unable to load account data
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

              <h3>No accounts yet</h3>

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

              <h3>No matching accounts</h3>

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

                    {/* ======================================== */}
                    {/* NEW — CALCULATED ACCOUNT BALANCE */}
                    {/* ======================================== */}

                    <th
                      scope="col"
                      className="coa-balance-column"
                    >
                      Balance
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
                      {/* NEW — ACCOUNT BALANCE */}
                      {/* ======================================== */}

                      <td className="coa-balance-column">

                        {formatAccountBalance(
                          balancesByAccountId.get(account.id),
                        )}

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
                          Preserve our existing AccountActions
                          component without modifications.

                          It handles account renaming and
                          deactivation, then requests a refresh
                          through handleAccountUpdated().
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
