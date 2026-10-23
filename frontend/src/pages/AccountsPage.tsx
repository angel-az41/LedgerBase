import { useEffect, useMemo, useState } from 'react'

import { ChevronDown, Download } from 'lucide-react'

import {
  getAccounts,
  getAccountBalances,
  REPORTING_GROUP_OPTIONS,
  type Account,
  type AccountBalance,
} from '../api/accounts'

import {
  exportAccounts,
  type AccountExportFormat,
} from '../api/accountExports'

import AccountActions from '../components/accounts/AccountActions'
import NewAccountForm from '../components/accounts/NewAccountForm'
import ImportAccountsDialog from '../components/accounts/ImportAccountsDialog'

import './AccountsPage.css'

// ============================================================
// LEDGERBASE — CHART OF ACCOUNTS PAGE
// ============================================================

/**
 * Display and manage a business's Chart of Accounts.
 *
 * Account information and calculated balances are
 * retrieved from the Spring Boot backend.
 *
 * Supported functionality:
 *
 * - Retrieve accounts from PostgreSQL
 * - Display real posted account balances
 * - Display account statistics
 * - Search by code, name, or account type
 * - Filter active and inactive accounts
 * - Refresh account data and balances
 * - Create new accounts
 * - Rename active accounts
 * - Deactivate accounts with confirmation
 * - Import CSV/XLSX accounts
 * - Export CSV/XLSX accounts
 * - Display success notifications
 * - Handle loading, error, and empty states
 *
 * V7 enhancements:
 *
 * - Display financial reporting groups
 * - Identify posting and grouping accounts
 * - Display parent-child account relationships
 * - Distinguish grouping accounts from ledger balances
 *
 * IMPORTANT:
 *
 * Existing CSS class names and table columns are
 * preserved to maintain the current enterprise UI.
 *
 * Grouping accounts never receive direct journal
 * entries. Hierarchical subtotals are handled
 * separately from underlying ledger balances.
 */

// ============================================================
// COMPONENT PROPERTIES
// ============================================================

/**
 * The selected business ID comes from App.tsx.
 *
 * All account operations must use this business ID.
 */
interface AccountsPageProps {
  businessId: number

  /**
   * Whether the authenticated user can modify accounts
   * belonging to the currently selected business.
   *
   * OWNER / ADMIN / ACCOUNTANT: true
   * VIEWER: false
   *
   * App.tsx obtains this permission from the backend.
   * Spring Security independently enforces authorization.
   */
  canWrite: boolean
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
 * Convert account types into readable labels.
 *
 * Examples:
 *
 * ASSET -> Asset
 * LIABILITY -> Liability
 * REVENUE -> Revenue
 */
function formatAccountType(
  accountType: string,
): string {

  const lowercaseType = accountType.toLowerCase()

  return (
    lowercaseType.charAt(0).toUpperCase() +
    lowercaseType.slice(1).replaceAll('_', ' ')
  )
}

// ============================================================
// V7 — FINANCIAL REPORTING GROUP FORMATTING
// ============================================================

/**
 * Convert a PostgreSQL reporting group into a readable
 * financial statement classification.
 *
 * Examples:
 *
 * CURRENT_ASSET -> Current Assets
 * NON_CURRENT_LIABILITY -> Non-current Liabilities
 * OPERATING_REVENUE -> Operating Revenue
 *
 * Reuse the reporting-group options in accounts.ts
 * so labels match the New Account form.
 *
 * Accounts with null reportingGroup display:
 *
 * Not classified
 */
function formatReportingGroup(
  account: Account,
): string {

  const options =
    REPORTING_GROUP_OPTIONS[account.accountType]

  const matchingOption = options.find(
    (option) =>
      option.value === account.reportingGroup,
  )

  return matchingOption?.label ?? 'Not classified'
}

// ============================================================
// ACCOUNT CREATION DATE FORMATTING
// ============================================================

/**
 * Format the account creation date returned by Spring Boot.
 *
 * UTC prevents calendar-date differences caused
 * by the browser's local time zone.
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
 * "500.00" -> "$500.00"
 * "-500.00" -> "−$500.00"
 * "0" -> "$0.00"
 * "12345.60" -> "$12,345.60"
 *
 * IMPORTANT:
 *
 * We do not use parseFloat(), Number(), or JavaScript
 * floating-point calculations to format balances.
 *
 * PostgreSQL NUMERIC and Java BigDecimal remain
 * responsible for financial calculations.
 *
 * The frontend only formats the resulting string.
 *
 * Our current development business uses USD.
 *
 * If a balance is missing or malformed, show an
 * em dash instead of inventing a zero balance.
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
   */
  const match = /^(-?)(\d+)(?:\.(\d{1,2}))?$/.exec(
    balance,
  )

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
   * Use string manipulation only.
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

  const formattedDecimalPart =
    decimalPart.padEnd(2, '0')

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
  canWrite,
}: AccountsPageProps) {

  // ==========================================================
  // ACCOUNT DATA STATE
  // ==========================================================

  /**
   * Store accounts returned by:
   *
   * GET /api/v1/businesses/{businessId}/accounts
   *
   * These records include V7 fields:
   *
   * reportingGroup
   * parentAccountId
   * postable
   */
  const [accounts, setAccounts] =
    useState<Account[]>([])

  // ==========================================================
  // ACCOUNT BALANCE DATA STATE
  // ==========================================================

  /**
   * Store balances returned by:
   *
   * GET /api/v1/businesses/{businessId}/accounts/balances
   *
   * All monetary properties are decimal strings.
   */
  const [accountBalances, setAccountBalances] =
    useState<AccountBalance[]>([])

  // ==========================================================
  // API REQUEST STATE
  // ==========================================================

  const [isLoading, setIsLoading] = useState(true)

  const [errorMessage, setErrorMessage] = useState('')

  // ==========================================================
  // ACCOUNT SEARCH AND FILTER STATE
  // ==========================================================

  const [searchQuery, setSearchQuery] = useState('')

  const [statusFilter, setStatusFilter] =
    useState<StatusFilter>('all')

  // ==========================================================
  // ACCOUNT REFRESH STATE
  // ==========================================================

  /**
   * Increasing refreshVersion reloads both account
   * information and calculated balances.
   */
  const [refreshVersion, setRefreshVersion] = useState(0)

  // ==========================================================
  // CREATE ACCOUNT FORM STATE
  // ==========================================================

  const [isCreateFormOpen, setIsCreateFormOpen] =
    useState(false)

  // ==========================================================
  // SUCCESS NOTIFICATION STATE
  // ==========================================================

  const [successMessage, setSuccessMessage] = useState('')

  // ==========================================================
  // ACCOUNT EXPORT STATE
  // ==========================================================

  /**
   * Prevent duplicate export requests while a
   * download is being processed.
   */
  const [isExporting, setIsExporting] = useState(false)

  /**
   * Export errors are kept separate from account
   * loading errors.
   *
   * A failed export should not hide the account table
   * or imply that the database was modified.
   */
  const [exportError, setExportError] = useState('')

  // ==========================================================
  // LOAD ACCOUNTS AND BALANCES FROM SPRING BOOT
  // ==========================================================

  /**
   * Fetch accounts and balances when:
   *
   * 1. The page mounts.
   * 2. The selected business changes.
   * 3. An account is created, renamed, or deactivated.
   * 4. An account import succeeds.
   * 5. The user clicks Refresh.
   *
   * Promise.all() runs both requests concurrently.
   *
   * If either request fails, display an error rather
   * than potentially misleading account data.
   */
  useEffect(() => {

    let cancelled = false

    // ========================================================
    // ASYNCHRONOUS ACCOUNT DATA LOADER
    // ========================================================

    async function loadAccounts(): Promise<void> {

      setIsLoading(true)
      setErrorMessage('')

      try {

        // ====================================================
        // FETCH ACCOUNT DETAILS AND POSTED BALANCES
        // ====================================================

        const [accountData, balanceData] =
          await Promise.all([

            getAccounts(businessId),

            getAccountBalances(businessId),

          ])

        // ====================================================
        // UPDATE REACT STATE
        // ====================================================

        /**
         * Ignore outdated requests if the selected
         * business changes or the component unmounts.
         */
        if (!cancelled) {

          setAccounts(accountData)

          setAccountBalances(balanceData)
        }

      } catch (error: unknown) {

        // ====================================================
        // HANDLE API ERRORS
        // ====================================================

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

    // ========================================================
    // CLEAN UP OUTDATED REQUESTS
    // ========================================================

    return () => {
      cancelled = true
    }

  }, [businessId, refreshVersion])

  // ==========================================================
  // MATCH ACCOUNT BALANCES BY ACCOUNT ID
  // ==========================================================

  /**
   * Accounts and balances come from separate endpoints.
   *
   * We match them using account IDs rather than codes.
   *
   * Example:
   *
   * Account ID: 8
   * Posted Balance: "500.00"
   *
   * Map:
   *
   * 8 -> "500.00"
   *
   * useMemo recalculates this lookup only when
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

  // ==========================================================
  // V7 — ACCOUNT HIERARCHY LOOKUP
  // ==========================================================

  /**
   * Create a lookup of accounts by their database IDs.
   *
   * This lets us display the parent account name
   * beneath each child account.
   *
   * Account IDs are more reliable than account codes
   * when representing database relationships.
   *
   * The backend scopes returned accounts
   * to the requested business.
   */
  const accountsById = useMemo(() => {

    return new Map(

      accounts.map((account) => [

        account.id,

        account,

      ]),

    )

  }, [accounts])

  // ==========================================================
  // ACCOUNT STATISTICS
  // ==========================================================

  /**
   * Summary metrics use real account records.
   *
   * Grouping accounts count as accounts because they
   * are part of the Chart of Accounts structure.
   *
   * Active and inactive statuses are separate from
   * posting eligibility.
   */
  const totalAccounts = accounts.length

  const activeAccounts = accounts.filter(
    (account) => account.active,
  ).length

  const inactiveAccounts =
    totalAccounts - activeAccounts

  /**
   * Hide statistics while loading or when
   * the account request has failed.
   */
  const showStatistics =
    !isLoading && !errorMessage

  // ==========================================================
  // SEARCH AND FILTER ACCOUNTS
  // ==========================================================

  /**
   * Preserve existing search and filter behavior.
   *
   * Accounts can be searched by:
   *
   * - Account code
   * - Account name
   * - Fundamental account type
   *
   * Preserve numeric-aware account-code ordering.
   *
   * Parent-child relationships are displayed
   * through indentation.
   */
  const filteredAccounts = useMemo(() => {

    const query = searchQuery.trim().toLowerCase()

    return accounts

      // ======================================================
      // FILTER ACCOUNTS
      // ======================================================

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

      // ======================================================
      // SORT ACCOUNTS
      // ======================================================

      .sort((first, second) =>
        first.code.localeCompare(
          second.code,
          undefined,
          { numeric: true },
        ),
      )

  }, [accounts, searchQuery, statusFilter])

  
  // ==========================================================
  // CREATE ACCOUNT — OPEN FORM
  // ==========================================================

  /**
   * Open the account creation form only when the
   * authenticated user has accounting write permission.
   *
   * VIEWER users cannot initiate account creation.
   *
   * The backend independently verifies permissions
   * before inserting any PostgreSQL records.
   */
  function handleOpenCreateForm(): void {

    // --------------------------------------------------------
    // REQUIRE BUSINESS WRITE PERMISSION
    // --------------------------------------------------------

    if (!canWrite) {
      return
    }

    // --------------------------------------------------------
    // OPEN ACCOUNT CREATION WORKFLOW
    // --------------------------------------------------------

    setSuccessMessage('')

    setIsCreateFormOpen(true)
  }

  // ==========================================================
  // CREATE ACCOUNT — CANCEL FORM
  // ==========================================================

  function handleCancelCreateForm(): void {

    setIsCreateFormOpen(false)
  }

  // ==========================================================
  // CREATE ACCOUNT — SUCCESS HANDLER
  // ==========================================================

  /**
   * Preserve the existing account creation workflow.
   *
   * After successful creation:
   *
   * - Close the form.
   * - Show success feedback.
   * - Refresh accounts and balances.
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

  // ==========================================================
  // ACCOUNT MANAGEMENT — SUCCESS HANDLER
  // ==========================================================

  /**
   * AccountActions continues handling:
   *
   * - Rename
   * - Deactivate
   *
   * Refresh data after successful operations.
   */
  function handleAccountUpdated(
    message: string,
  ): void {

    setSuccessMessage(message)

    setRefreshVersion(
      (previousVersion) => previousVersion + 1,
    )
  }

  // ==========================================================
  // ACCOUNT IMPORT — SUCCESS HANDLER
  // ==========================================================

  /**
   * Called after Spring Boot successfully imports accounts.
   *
   * The database transaction has completed.
   *
   * Reuse the existing refreshVersion mechanism
   * to reload both:
   *
   * - Chart of Accounts
   * - Posted account balances
   *
   * No local placeholder account data is created.
   */
  function handleAccountsImported(
    importedRows: number,
  ): void {

    setSuccessMessage(
      `Successfully imported ${importedRows} accounts.`,
    )

    setRefreshVersion(
      (previousVersion) => previousVersion + 1,
    )
  }
  
  // ==========================================================
  // EXPORT CHART OF ACCOUNTS
  // ==========================================================

  /**
   * Export the current business's Chart of Accounts.
   *
   * The selected format is passed directly from the
   * Export dropdown menu.
   *
   * Supported formats:
   * - CSV
   * - XLSX
   *
   * Prevent duplicate requests and display errors
   * without affecting the account table.
   */
  async function handleExportAccounts(
    format: AccountExportFormat,
  ): Promise<void> {

    if (isExporting) {
      return
    }

    setIsExporting(true)
    setExportError('')

    try {

      // ======================================================
      // REQUEST THE SELECTED FILE FORMAT
      // ======================================================

      await exportAccounts(businessId, format)

    } catch (error: unknown) {

      // ======================================================
      // DISPLAY DOWNLOAD ERRORS
      // ======================================================

      setExportError(
        error instanceof Error
          ? error.message
          : 'Unable to export accounts.',
      )

    } finally {

      setIsExporting(false)
    }
  }

  // ==========================================================
  // REFRESH ACCOUNT DATA
  // ==========================================================

  /**
   * Reload account details and posted balances.
   */
  function handleRefresh(): void {

    setRefreshVersion(
      (previousVersion) => previousVersion + 1,
    )
  }

  // ==========================================================
  // RENDER CHART OF ACCOUNTS
  // ==========================================================

  return (

    <section
      className="coa-page"
      aria-busy={isLoading}
    >

      {/* ==================================================== */}
      {/* ACCOUNT SUMMARY CARDS */}
      {/* ==================================================== */}

      <div className="coa-summary-grid">

        {/* ================================================== */}
        {/* TOTAL ACCOUNTS */}
        {/* ================================================== */}

        <article className="coa-summary-card">

          <p>Total Accounts</p>

          <strong>
            {showStatistics ? totalAccounts : '—'}
          </strong>

        </article>

        {/* ================================================== */}
        {/* ACTIVE ACCOUNTS */}
        {/* ================================================== */}

        <article className="coa-summary-card">

          <p>Active Accounts</p>

          <strong>
            {showStatistics ? activeAccounts : '—'}
          </strong>

        </article>

        {/* ================================================== */}
        {/* INACTIVE ACCOUNTS */}
        {/* ================================================== */}

        <article className="coa-summary-card">

          <p>Inactive Accounts</p>

          <strong>
            {showStatistics ? inactiveAccounts : '—'}
          </strong>

        </article>

      </div>

      {/* ==================================================== */}
      {/* ACCOUNT OPERATION SUCCESS MESSAGE */}
      {/* ==================================================== */}

      {successMessage && (

        <p
          className="coa-success-notice"
          role="status"
        >
          {successMessage}
        </p>

      )}

      {/* ==================================================== */}
      {/* ACCOUNT EXPORT ERROR NOTIFICATION */}
      {/* ==================================================== */}

      {exportError && (

        <div
          className="coa-export-error"
          role="alert"
        >
          {exportError}
        </div>

      )}

      {/* ==================================================== */}
      {/* NEW ACCOUNT FORM */}
      {/* ==================================================== */}

      {canWrite && isCreateFormOpen && (

        <NewAccountForm
          businessId={businessId}
          onCreated={handleAccountCreated}
          onCancel={handleCancelCreateForm}
        />

      )}

      {/* ==================================================== */}
      {/* ACCOUNTS TABLE CONTAINER */}
      {/* ==================================================== */}

      <section className="coa-table-card">

        {/* ================================================== */}
        {/* TABLE HEADING */}
        {/* ================================================== */}

        <div className="coa-table-heading">

          <div>

            <h2>All Accounts</h2>

            <p>
              View and organize your business&apos;s
              general ledger accounts.
            </p>

          </div>
          
          {/* ================================================== */}
          {/* CHART OF ACCOUNTS — ACTION TOOLBAR */}
          {/* ================================================== */}

          <div className="coa-table-actions">

            {/* ================================================ */}
            {/* PRIMARY ACTION — CREATE NEW ACCOUNT */}
            {/* ================================================ */}

            {canWrite && !isCreateFormOpen && (

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
            {/* VISUAL SEPARATOR */}
            {/* ================================================ */}

            {canWrite && (
              <span
                className="coa-action-separator"
                aria-hidden="true"
              />
            )}

            {/* ================================================ */}
            {/* IMPORT AND EXPORT ACTIONS */}
            {/* ================================================ */}

            <div className="coa-import-export-actions">

              {/* ================================================== */}
              {/* ACCOUNT IMPORT — WRITE PERMISSION REQUIRED */}
              {/* ================================================== */}

              {canWrite && (

                <ImportAccountsDialog
                  businessId={businessId}
                  onImported={handleAccountsImported}
                />

              )}

              {/* ============================================== */}
              {/* EXPORT FORMAT DROPDOWN */}
              {/* ============================================== */}

              {/*
                Display a single Export control.

                Users choose CSV or Excel from the native
                dropdown instead of using a separate
                format selector.

                The dropdown resets after every selection,
                allowing the same format to be exported
                repeatedly.
              */}

              <div className="coa-export-picker">

                <Download
                  size={15}
                  strokeWidth={1.8}
                  className="coa-export-picker-icon"
                  aria-hidden="true"
                />

                <label
                  htmlFor="coa-export-action"
                  className="coa-visually-hidden"
                >
                  Export Chart of Accounts
                </label>

                <select
                  id="coa-export-action"
                  className="coa-export-format-picker"
                  defaultValue=""
                  disabled={isLoading || isExporting}
                  onChange={(event) => {

                    const format =
                      event.currentTarget.value as AccountExportFormat

                    // Reset to the default Export label.
                    event.currentTarget.value = ''

                    void handleExportAccounts(format)

                  }}
                >

                  <option value="" disabled>
                    {isExporting ? 'Exporting...' : 'Export'}
                  </option>

                  <option value="csv">
                    CSV
                  </option>

                  <option value="xlsx">
                    Excel (.xlsx)
                  </option>

                </select>

                <ChevronDown
                  size={14}
                  strokeWidth={1.8}
                  className="coa-export-picker-chevron"
                  aria-hidden="true"
                />

              </div>

            </div>

            {/* ================================================ */}
            {/* VISUAL SEPARATOR */}
            {/* ================================================ */}

            <span
              className="coa-action-separator"
              aria-hidden="true"
            />

            {/* ================================================ */}
            {/* SECONDARY ACTION — REFRESH */}
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

        {/* ================================================== */}
        {/* ACCOUNT SEARCH AND FILTER CONTROLS */}
        {/* ================================================== */}

        <div className="coa-toolbar">

          {/* ================================================ */}
          {/* SEARCH FIELD */}
          {/* ================================================ */}

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

          {/* ================================================ */}
          {/* STATUS FILTER */}
          {/* ================================================ */}

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

        {/* ================================================== */}
        {/* ERROR STATE */}
        {/* ================================================== */}

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

        {/* ================================================== */}
        {/* LOADING STATE */}
        {/* ================================================== */}

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

        {/* ================================================== */}
        {/* EMPTY DATABASE STATE */}
        {/* ================================================== */}

        {!errorMessage &&
          !isLoading &&
          accounts.length === 0 && (

            <div className="coa-message">

              <h3>No accounts yet</h3>

              <p>
              {canWrite
                ? 'Create your first general ledger account using the New Account button.'
                : 'There are no general ledger accounts to display for this business.'}
            </p>

            </div>

          )
        }

        {/* ================================================== */}
        {/* NO MATCHING SEARCH RESULTS */}
        {/* ================================================== */}

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

        {/* ================================================== */}
        {/* ACCOUNTS DATA TABLE */}
        {/* ================================================== */}

        {!errorMessage &&
          !isLoading &&
          filteredAccounts.length > 0 && (

            <div className="coa-table-scroll">

              <table className="coa-table">

                {/* ========================================== */}
                {/* TABLE COLUMN HEADINGS */}
                {/* ========================================== */}

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

                    {canWrite && (
                      <th
                        scope="col"
                        className="coa-actions-column"
                      >
                        Actions
                      </th>
                    )}

                  </tr>

                </thead>

                {/* ========================================== */}
                {/* ACCOUNT TABLE ROWS */}
                {/* ========================================== */}

                <tbody>

                  {filteredAccounts.map((account) => (

                    <tr key={account.id}>

                      {/* ====================================== */}
                      {/* ACCOUNT CODE */}
                      {/* ====================================== */}

                      <td className="coa-account-code">
                        {account.code}
                      </td>

                      {/* ====================================== */}
                      {/* V7 — ACCOUNT NAME AND HIERARCHY */}
                      {/* ====================================== */}

                      <td className="coa-account-name">

                        <div
                          className={
                            account.parentAccountId !== null
                              ? 'coa-account-name-content is-child'
                              : 'coa-account-name-content'
                          }
                        >

                          {/* ================================ */}
                          {/* ACCOUNT NAME WITH CHILD INDICATOR */}
                          {/* ================================ */}

                          <div className="coa-account-name-title">

                            {account.parentAccountId !== null && (

                              <span
                                className="coa-hierarchy-indicator"
                                aria-hidden="true"
                              >
                                ↳
                              </span>

                            )}

                            <span>
                              {account.name}
                            </span>

                          </div>

                          {/* ================================ */}
                          {/* ACCOUNT PURPOSE AND PARENT */}
                          {/* ================================ */}

                          <small className="coa-account-description">

                            {account.postable
                              ? 'Posting account'
                              : 'Grouping account'}

                            {account.parentAccountId !== null && (

                              <>

                                {' · Under '}

                                {accountsById.get(
                                  account.parentAccountId,
                                )?.name ?? 'Unknown parent'}

                              </>

                            )}

                          </small>

                        </div>

                      </td>

                      {/* ====================================== */}
                      {/* V7 — ACCOUNT TYPE AND REPORTING GROUP */}
                      {/* ====================================== */}

                      <td>

                        <div className="coa-account-type-content">

                          <span>
                            {formatAccountType(
                              account.accountType,
                            )}
                          </span>

                          <small
                            className={
                              account.reportingGroup === null
                                ? 'coa-reporting-group is-unclassified'
                                : 'coa-reporting-group'
                            }
                          >
                            {formatReportingGroup(account)}
                          </small>

                        </div>

                      </td>

                      {/* ====================================== */}
                      {/* V7 — ACCOUNT BALANCE */}
                      {/* ====================================== */}

                      {/*
                        Posting accounts display their actual
                        calculated POSTED ledger balance.

                        Grouping accounts display an em dash
                        because they do not accept journal entries.

                        We never add child subtotals into
                        a grouping account's direct balance.

                        This prevents financial double-counting.
                      */}

                      <td
                        className="coa-balance-column"
                        title={
                          account.postable
                            ? undefined
                            : 'Grouping accounts have no direct ledger balance'
                        }
                      >

                        {account.postable
                          ? formatAccountBalance(
                              balancesByAccountId.get(account.id),
                            )
                          : '—'}

                      </td>

                      {/* ====================================== */}
                      {/* ACCOUNT STATUS */}
                      {/* ====================================== */}

                      {/*
                        Active and postable are different.

                        A non-postable grouping account can
                        still be an active account.

                        Preserve the original status badge.
                      */}

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
                            : 'Inactive'}

                        </span>

                      </td>

                      {/* ====================================== */}
                      {/* ACCOUNT CREATION DATE */}
                      {/* ====================================== */}

                      <td className="coa-date">
                        {formatDate(account.createdAt)}
                      </td>

                      {/* ====================================== */}
                      {/* ACCOUNT MANAGEMENT ACTIONS */}
                      {/* ====================================== */}

                      {/*
                        Preserve the AccountActions component.

                        It handles Rename and Deactivate.

                        V7 account-configuration editing will
                        be introduced separately to avoid
                        accidentally modifying account types
                        or posting eligibility.
                      */}

                      {canWrite && (
                        <td className="coa-actions-column">

                          <AccountActions
                            businessId={businessId}
                            account={account}
                            onUpdated={handleAccountUpdated}
                          />

                        </td>
                      )}

                    </tr>

                  ))}

                </tbody>

              </table>

            </div>

          )
        }

        {/* ================================================== */}
        {/* TABLE FOOTER */}
        {/* ================================================== */}

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
