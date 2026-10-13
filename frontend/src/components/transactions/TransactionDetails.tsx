import { useEffect, useMemo, useState } from 'react'

import {
  getTransactionDetails,
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
 * This component retrieves:
 *
 * - Transaction header information
 * - Individual debit and credit entries
 * - Account names and account codes
 *
 * The data comes from Spring Boot and PostgreSQL.
 *
 * Transaction posting will be added in a later step.
 */

// ============================================================
// COMPONENT PROPERTIES
// ============================================================

interface TransactionDetailsProps {
  businessId: number
  transactionId: number
  onClose: () => void
}

// ============================================================
// ACCOUNTING DATE FORMATTING
// ============================================================

/**
 * Display an accounting date without timezone shifts.
 */
function formatDate(value: string): string {
  return new Intl.DateTimeFormat('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  }).format(new Date(`${value}T00:00:00Z`))
}

// ============================================================
// LEDGER AMOUNT FORMATTING
// ============================================================

/**
 * Convert an amount returned by Spring Boot
 * into integer cents for display.
 *
 * The current API returns BigDecimal as JSON numbers.
 * We avoid additional floating-point arithmetic here.
 *
 * Before production, the read API should return
 * monetary values as decimal strings to guarantee
 * exact precision for the full NUMERIC(18,2) range.
 */
function amountToCents(amount: number): bigint | null {
  if (!Number.isFinite(amount)) {
    return null
  }

  return parseMoneyToCents(String(amount))
}

/**
 * Show zero amounts as a dash, following common
 * accounting table presentation conventions.
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
  transactionId,
  onClose,
}: TransactionDetailsProps) {

  // ============================================================
  // TRANSACTION DATA STATE
  // ============================================================

  const [details, setDetails] =
    useState<TransactionDetailResponse | null>(null)

  const [accounts, setAccounts] = useState<Account[]>([])

  // ============================================================
  // REQUEST STATE
  // ============================================================

  const [isLoading, setIsLoading] = useState(true)

  const [errorMessage, setErrorMessage] = useState('')

  // ============================================================
  // LOAD TRANSACTION DETAILS
  // ============================================================

  /**
   * Retrieve both the transaction and account list.
   *
   * The transaction detail endpoint returns account IDs.
   * The Accounts API provides their human-readable names.
   */
  useEffect(() => {

    let cancelled = false

    async function loadDetails(): Promise<void> {

      setIsLoading(true)
      setErrorMessage('')
      setDetails(null)

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
   * Build a map from account ID to Account.
   *
   * This makes it easy to display:
   *
   * 1020 — Business Savings
   *
   * instead of only:
   *
   * Account #4
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
  // EXACT DEBIT AND CREDIT TOTALS
  // ============================================================

  /**
   * Add the retrieved amounts using integer cents.
   *
   * If a value cannot be represented safely by
   * our current parsing utility, omit totals
   * rather than displaying misleading figures.
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
  // RENDER TRANSACTION DETAILS
  // ============================================================

  return (

    <section
      className="transaction-details-card"
      aria-busy={isLoading}
    >

      {/* ====================================================== */}
      {/* DETAILS HEADER */}
      {/* ====================================================== */}

      <div className="transaction-details-heading">

        <div>
          <h2>
            Transaction #{transactionId}
          </h2>

          <p>
            Review the transaction and its journal entries.
          </p>
        </div>

        <button
          type="button"
          className="transaction-details-close"
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
      {/* TRANSACTION HEADER INFORMATION */}
      {/* ====================================================== */}

      {!isLoading && !errorMessage && details && (

        <>

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

        </>

      )}

    </section>

  )
}

// ============================================================
// EXPORT COMPONENT
// ============================================================

export default TransactionDetails
