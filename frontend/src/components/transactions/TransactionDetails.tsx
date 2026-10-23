import {
  useEffect,
  useMemo,
  useState,
} from 'react'

import {
  getTransactionDetails,
  postTransaction,
  type LedgerTransaction,
  type TransactionDetailResponse,
} from '../../api/transactions'

import {
  getAccounts,
  type Account,
} from '../../api/accounts'

import {
  parseMoneyToCents,
  formatUsdCents,
} from '../../utils/money'

import './TransactionDetails.css'

// ============================================================
// LEDGERBASE — TRANSACTION DETAILS
// ============================================================

/**
 * Display a financial transaction and its ledger entries.
 *
 * Features:
 *
 * - Retrieve transaction details from Spring Boot
 * - Display business-specific journal references
 * - Display account codes and names
 * - Display debit and credit amounts
 * - Calculate total debits and credits
 * - Display the transaction posting status
 * - Confirm before posting a draft transaction
 * - Post transactions through the backend API
 *
 * Posting changes a transaction from DRAFT to POSTED.
 *
 * The backend and PostgreSQL enforce the accounting
 * rules and immutability of posted transactions.
 *
 * IMPORTANT:
 *
 * Internal transaction IDs and business-specific
 * journal references serve different purposes.
 *
 * transactionId:
 *   Used to retrieve and post a transaction through
 *   the Spring Boot API.
 *
 * journalReference:
 *   Used to identify a journal entry to the user.
 *
 * Example:
 *
 * Internal database ID: 2
 * Business journal reference: JE-0001
 *
 * We never reconstruct the displayed reference
 * from the internal database ID.
 */

// ============================================================
// COMPONENT PROPERTIES
// ============================================================

interface TransactionDetailsProps {
  businessId: number

  /**
   * Whether the authenticated user has permission
   * to perform accounting write operations.
   *
   * OWNER / ADMIN / ACCOUNTANT: true
   * VIEWER: false
   *
   * This permission comes from App.tsx through
   * TransactionsPage.tsx.
   *
   * Backend RBAC remains authoritative.
   */
  canWrite: boolean

  /**
   * Globally unique internal database transaction ID.
   *
   * The parent passes this ID when opening the panel.
   *
   * All API calls continue using transactionId,
   * not journalReference.
   */
  transactionId: number

  onClose: () => void

  /**
   * The parent page uses this callback to
   * refresh the register after successful posting.
   *
   * Optional to support other potential callers.
   */
  onPosted?: (transaction: LedgerTransaction) => void
}

// ============================================================
// ACCOUNTING DATE FORMATTING
// ============================================================

function formatDate(value: string): string {

  return new Intl.DateTimeFormat('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  }).format(new Date(`${value}T00:00:00Z`))
}

// ============================================================
// MONETARY AMOUNT PARSING
// ============================================================

/**
 * Convert the backend's current numeric responses
 * into bigint cents for totals and formatting.
 *
 * Production should eventually serialize monetary
 * amounts as decimal strings to preserve precision
 * across the full PostgreSQL NUMERIC(18,2) range.
 */
function amountToCents(amount: number): bigint | null {

  if (!Number.isFinite(amount)) {
    return null
  }

  return parseMoneyToCents(String(amount))
}

// ============================================================
// MONETARY AMOUNT FORMATTING
// ============================================================

/**
 * Show zero amounts as a dash.
 *
 * This is common in accounting reports.
 */
function formatEntryAmount(amount: number): string {

  const cents = amountToCents(amount)

  if (cents === null) {
    return '—'
  }

  return cents === 0n
    ? '—'
    : formatUsdCents(cents)
}

// ============================================================
// TRANSACTION DETAILS COMPONENT
// ============================================================

function TransactionDetails({
  businessId,
  canWrite,
  transactionId,
  onClose,
  onPosted,
}: TransactionDetailsProps) {

  // ============================================================
  // TRANSACTION DATA STATE
  // ============================================================

  const [details, setDetails] =
    useState<TransactionDetailResponse | null>(null)

  const [accounts, setAccounts] = useState<Account[]>([])

  // ============================================================
  // DATA LOADING STATE
  // ============================================================

  const [isLoading, setIsLoading] = useState(true)

  const [errorMessage, setErrorMessage] = useState('')

  // ============================================================
  // POSTING CONFIRMATION STATE
  // ============================================================

  /**
   * Controls whether the posting confirmation
   * is visible inside the details panel.
   */
  const [isConfirmingPost, setIsConfirmingPost] =
    useState(false)

  // ============================================================
  // POST REQUEST STATE
  // ============================================================

  const [isPosting, setIsPosting] = useState(false)

  const [postError, setPostError] = useState('')

  // ============================================================
  // LOAD TRANSACTION DETAILS
  // ============================================================

  /**
   * Load transaction details and the business's
   * general ledger accounts.
   *
   * Account data is needed to display readable
   * codes and names rather than just database IDs.
   */
  useEffect(() => {

    let cancelled = false

    async function loadDetails(): Promise<void> {

      setIsLoading(true)
      setErrorMessage('')
      setDetails(null)
      setPostError('')
      setIsConfirmingPost(false)

      try {

        const [transactionData, accountData] =
          await Promise.all([
            getTransactionDetails(
              businessId,
              transactionId,
            ),
            getAccounts(businessId),
          ])

        if (!cancelled) {
          setDetails(transactionData)
          setAccounts(accountData)
        }

      } catch (error: unknown) {

        if (!cancelled) {
          setErrorMessage(
            error instanceof Error
              ? error.message
              : 'Unable to load transaction details.',
          )
        }

      } finally {

        if (!cancelled) {
          setIsLoading(false)
        }
      }
    }

    void loadDetails()

    return () => {
      cancelled = true
    }

  }, [businessId, transactionId])

  // ============================================================
  // ACCOUNT LOOKUP
  // ============================================================

  /**
   * Build a map for looking up account names.
   *
   * Example:
   *
   * 1020 — Business Savings
   */
  const accountsById = useMemo(() => {

    return new Map(
      accounts.map((account) => [
        account.id,
        account,
      ]),
    )

  }, [accounts])

  // ============================================================
  // CALCULATE DEBIT AND CREDIT TOTALS
  // ============================================================

  /**
   * Sum amounts using bigint cents rather
   * than JavaScript floating-point arithmetic.
   */
  const totals = useMemo(() => {

    if (!details) {
      return null
    }

    let totalDebits = 0n
    let totalCredits = 0n

    for (const entry of details.entries) {

      const debit = amountToCents(entry.debit)
      const credit = amountToCents(entry.credit)

      if (debit === null || credit === null) {
        return null
      }

      totalDebits += debit
      totalCredits += credit
    }

    return {
      debit: totalDebits,
      credit: totalCredits,
      balanced: totalDebits === totalCredits,
    }

  }, [details])

  // ============================================================
  // BUSINESS-SPECIFIC JOURNAL REFERENCE
  // ============================================================

  /**
   * Read the formatted reference returned by Spring Boot.
   *
   * Example:
   *
   * journalNumber: 1
   * journalReference: JE-0001
   *
   * Different businesses may have the same reference,
   * so the reference must not replace transactionId
   * when making backend requests.
   *
   * Before the API finishes loading, this value is null.
   *
   * We display a generic label in that case rather
   * than showing a misleading internal database ID.
   */
  const journalReference =
    details?.transaction.journalReference ?? null

  // ============================================================
  // CHECK WHETHER TRANSACTION CAN BE POSTED
  // ============================================================

  /**
   * A transaction can be posted only when:
   *
   * - The authenticated user has write permission.
   * - Transaction data has finished loading.
   * - No posting request is already in progress.
   * - The transaction is still in DRAFT status.
   * - Debit and credit totals are available.
   * - Total debits equal total credits.
   *
   * VIEWER users must never be able to initiate
   * posting through the React interface.
   *
   * Spring Security independently verifies the
   * membership role when the posting API is called.
   */
  const canPost =
    canWrite &&
    !isLoading &&
    !isPosting &&
    details?.transaction.status === 'DRAFT' &&
    totals !== null &&
    totals.balanced

  // ============================================================
  // OPEN POSTING CONFIRMATION
  // ============================================================

  /**
   * Clicking Post Transaction does not
   * immediately modify PostgreSQL.
   *
   * It opens a confirmation first.
   */
  function handleOpenPostConfirmation(): void {

    if (!canPost) {
      return
    }

    setPostError('')
    setIsConfirmingPost(true)
  }

  // ============================================================
  // CANCEL POSTING CONFIRMATION
  // ============================================================

  /**
   * Canceling does not send any API request.
   */
  function handleCancelPost(): void {

    setIsConfirmingPost(false)
    setPostError('')
  }

  // ============================================================
  // CONFIRM AND POST TRANSACTION
  // ============================================================

  /**
   * Finalize the selected draft transaction.
   *
   * The backend obtains the posting actor from the
   * authenticated JWT instead of trusting a browser ID.
   *
   * PostgreSQL preserves the original journal reference
   * and records the posting event in the audit log.
   */
  async function handleConfirmPost(): Promise<void> {

    if (!canPost || !details) {
      return
    }

    setIsPosting(true)
    setPostError('')

    try {

      // ========================================================
      // CALL SPRING BOOT POST ENDPOINT
      // ========================================================

      const postedTransaction = await postTransaction(
        businessId,
        transactionId,
      )

      // ========================================================
      // UPDATE THE LOCAL TRANSACTION STATUS
      // ========================================================

      /**
       * Immediately show POSTED in this panel
       * after the backend confirms success.
       *
       * The returned transaction also includes
       * the unchanged journal reference.
       */
      setDetails((previousDetails) => {

        if (!previousDetails) {
          return previousDetails
        }

        return {
          ...previousDetails,
          transaction: postedTransaction,
        }
      })

      // ========================================================
      // CLOSE CONFIRMATION
      // ========================================================

      setIsConfirmingPost(false)

      // ========================================================
      // NOTIFY THE PARENT PAGE
      // ========================================================

      /**
       * Notify TransactionsPage so it refreshes
       * the register and displays a success notice.
       */
      onPosted?.(postedTransaction)

    } catch (error: unknown) {

      setPostError(
        error instanceof Error
          ? error.message
          : 'Unable to post transaction.',
      )

    } finally {

      setIsPosting(false)
    }
  }

  // ============================================================
  // RENDER TRANSACTION DETAILS
  // ============================================================

  return (

    <section
      className="transaction-details-card"
      aria-busy={isLoading || isPosting}
    >

      {/* ====================================================== */}
      {/* DETAILS HEADER */}
      {/* ====================================================== */}

      <div className="transaction-details-heading">

        <div>

          {/* ================================================== */}
          {/* BUSINESS-SPECIFIC JOURNAL HEADING */}
          {/* ================================================== */}

          <h2>
            {journalReference
              ? `Journal Entry ${journalReference}`
              : 'Journal Entry'}
          </h2>

          <p>
            Review the transaction and its journal entries.
          </p>

        </div>

        <button
          type="button"
          className="transaction-details-close"
          disabled={isPosting}
          onClick={onClose}
        >
          Close
        </button>

      </div>

      {/* ====================================================== */}
      {/* LOADING STATE */}
      {/* ====================================================== */}

      {isLoading && (

        <p className="transaction-details-message" role="status">
          Loading transaction details...
        </p>

      )}

      {/* ====================================================== */}
      {/* ERROR STATE */}
      {/* ====================================================== */}

      {!isLoading && errorMessage && (

        <div
          className="transaction-details-message"
          role="alert"
        >
          <p>{errorMessage}</p>
        </div>

      )}

      {/* ====================================================== */}
      {/* TRANSACTION DATA */}
      {/* ====================================================== */}

      {!isLoading && !errorMessage && details && (

        <>

          {/* ================================================== */}
          {/* HEADER INFORMATION */}
          {/* ================================================== */}

          <div className="transaction-details-info">

            <div>

              <span>Description</span>

              <strong>
                {details.transaction.description}
              </strong>

            </div>

            <div>

              <span>Accounting Date</span>

              <strong>
                {formatDate(
                  details.transaction.transactionDate,
                )}
              </strong>

            </div>

            <div>

              <span>Status</span>

              <strong>
                {details.transaction.status}
              </strong>

            </div>

          </div>

          {/* ================================================== */}
          {/* JOURNAL ENTRY TABLE */}
          {/* ================================================== */}

          <div className="transaction-details-table-scroll">

            <table className="transaction-details-table">

              <thead>

                <tr>
                  <th scope="col">Account</th>
                  <th scope="col">Debit (USD)</th>
                  <th scope="col">Credit (USD)</th>
                </tr>

              </thead>

              <tbody>

                {details.entries.map((entry) => {

                  const account =
                    accountsById.get(entry.accountId)

                  return (

                    <tr key={entry.id}>

                      <td className="transaction-details-account">

                        {account
                          ? `${account.code} — ${account.name}`
                          : `Account #${entry.accountId}`}

                      </td>

                      <td className="transaction-details-amount">
                        {formatEntryAmount(entry.debit)}
                      </td>

                      <td className="transaction-details-amount">
                        {formatEntryAmount(entry.credit)}
                      </td>

                    </tr>

                  )
                })}

              </tbody>

              {/* ============================================== */}
              {/* TOTAL DEBITS AND CREDITS */}
              {/* ============================================== */}

              <tfoot>

                <tr>

                  <th scope="row">
                    Totals
                  </th>

                  <td className="transaction-details-amount">
                    {totals
                      ? formatUsdCents(totals.debit)
                      : '—'}
                  </td>

                  <td className="transaction-details-amount">
                    {totals
                      ? formatUsdCents(totals.credit)
                      : '—'}
                  </td>

                </tr>

              </tfoot>

            </table>

          </div>

          {/* ================================================== */}
          {/* BALANCE CONFIRMATION */}
          {/* ================================================== */}

          {totals && (

            <p
              className={
                totals.balanced
                  ? 'transaction-details-balance is-balanced'
                  : 'transaction-details-balance'
              }
            >

              {totals.balanced
                ? '✓ Total debits and credits are equal.'
                : 'Total debits and credits do not match.'}

            </p>

          )}

          {/* ================================================== */}
          {/* POSTING ACTIONS */}
          {/* ================================================== */}

          {canWrite && 
            details.transaction.status === 'DRAFT' &&
            !isConfirmingPost && (

              <div className="transaction-details-actions">

                <p>
                  Draft transactions can be reviewed before
                  being finalized in the general ledger.
                </p>

                <button
                  type="button"
                  className="transaction-details-post-button"
                  disabled={!canPost}
                  onClick={handleOpenPostConfirmation}
                >
                  Post Transaction
                </button>

              </div>

            )
          }

          {/* ================================================== */}
          {/* POSTING CONFIRMATION */}
          {/* ================================================== */}

          {details.transaction.status === 'DRAFT' &&
            isConfirmingPost && (

              <div
                className="transaction-post-confirmation"
                role="alertdialog"
                aria-labelledby="transaction-post-confirm-title"
                aria-describedby="transaction-post-confirm-description"
              >

                <div>

                  <h3 id="transaction-post-confirm-title">
                    Confirm Transaction Posting
                  </h3>

                  {/* ========================================== */}
                  {/* JOURNAL REFERENCE IN CONFIRMATION */}
                  {/* ========================================== */}

                  <p id="transaction-post-confirm-description">
                    You are about to post journal entry
                    {' '}
                    {journalReference ?? '(reference unavailable)'}.
                    {' '}
                    Once posted, this journal entry becomes
                    finalized and its financial entries
                    cannot be edited or deleted.
                  </p>

                </div>

                <div className="transaction-post-confirm-actions">

                  <button
                    type="button"
                    className="transaction-post-cancel-button"
                    disabled={isPosting}
                    onClick={handleCancelPost}
                  >
                    Cancel
                  </button>

                  <button
                    type="button"
                    className="transaction-post-confirm-button"
                    disabled={!canPost}
                    onClick={() => void handleConfirmPost()}
                  >
                    {isPosting
                      ? 'Posting...'
                      : 'Confirm & Post'}
                  </button>

                </div>

              </div>

            )
          }

          {/* ================================================== */}
          {/* POSTING ERROR */}
          {/* ================================================== */}

          {postError && (

            <p
              className="transaction-post-error"
              role="alert"
            >
              {postError}
            </p>

          )}

          {/* ================================================== */}
          {/* POSTED CONFIRMATION */}
          {/* ================================================== */}

          {details.transaction.status === 'POSTED' && (

            <p
              className="transaction-posted-notice"
              role="status"
            >
              {journalReference
                ? `✓ Journal entry ${journalReference} is posted and finalized.`
                : '✓ This transaction is posted and finalized.'}
            </p>

          )}

        </>

      )}

    </section>
  )
}

// ============================================================
// EXPORT COMPONENT
// ============================================================

export default TransactionDetails
