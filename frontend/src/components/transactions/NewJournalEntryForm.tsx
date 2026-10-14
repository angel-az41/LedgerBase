import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from 'react'

import {
  getAccounts,
  type Account,
} from '../../api/accounts'

import {
  createDraftTransaction,
  type LedgerTransaction,
} from '../../api/transactions'

import {
  parseMoneyToCents,
  centsToDecimalString,
  formatUsdCents,
} from '../../utils/money'

import './NewJournalEntryForm.css'

// ============================================================
// LEDGERBASE — NEW JOURNAL ENTRY FORM
// ============================================================

/**
 * Create double-entry journal transactions in LedgerBase.
 *
 * This form supports:
 *
 * - Transaction description and accounting date
 * - Selection of real active general ledger accounts
 * - Multiple debit and credit lines
 * - Exact monetary calculations using bigint
 * - Live double-entry balance validation
 * - Saving balanced transactions as DRAFT
 *
 * The Spring Boot backend remains responsible
 * for authoritative accounting validation.
 */

// ============================================================
// COMPONENT PROPERTIES
// ============================================================

interface NewJournalEntryFormProps {
  businessId: number
  onCreated: (transaction: LedgerTransaction) => void
  onCancel: () => void
}

// ============================================================
// JOURNAL LINE MODEL
// ============================================================

/**
 * Represents one editable journal entry line.
 *
 * rowId:
 *   Temporary identifier used by React.
 *
 * accountId:
 *   Database account selected by the user.
 *
 * debit / credit:
 *   Decimal strings preserved while the user types.
 */
interface JournalEntryLine {
  rowId: number
  accountId: string
  debit: string
  credit: string
}

// ============================================================
// TEMPORARY DEVELOPMENT USER
// ============================================================

/**
 * Authentication is not implemented yet.
 *
 * This value is for local development only.
 * We must verify that user ID 1 exists before
 * submitting our first real transaction.
 */
const DEMO_CREATOR_ID = 1

// ============================================================
// CREATE AN EMPTY JOURNAL LINE
// ============================================================

function createEmptyLine(rowId: number): JournalEntryLine {
  return {
    rowId,
    accountId: '',
    debit: '',
    credit: '',
  }
}

// ============================================================
// GET THE CURRENT LOCAL DATE
// ============================================================

/**
 * HTML date inputs require YYYY-MM-DD.
 *
 * Use the browser's local calendar date to
 * avoid UTC timezone shifts.
 */
function getTodayDate(): string {
  const today = new Date()

  const year = today.getFullYear()

  const month = String(
    today.getMonth() + 1,
  ).padStart(2, '0')

  const day = String(
    today.getDate(),
  ).padStart(2, '0')

  return `${year}-${month}-${day}`
}

// ============================================================
// PARSE OPTIONAL MONEY INPUT
// ============================================================

/**
 * Empty monetary fields represent zero.
 *
 * Invalid nonempty inputs return null.
 *
 * All valid amounts are converted into bigint
 * cents rather than floating-point numbers.
 */
function parseLineAmount(value: string): bigint | null {
  if (value.trim() === '') {
    return 0n
  }

  return parseMoneyToCents(value)
}

// ============================================================
// NEW JOURNAL ENTRY COMPONENT
// ============================================================

function NewJournalEntryForm({
  businessId,
  onCreated,
  onCancel,
}: NewJournalEntryFormProps) {

  // ============================================================
  // TRANSACTION HEADER STATE
  // ============================================================

  const [description, setDescription] = useState('')

  const [transactionDate, setTransactionDate] =
    useState(getTodayDate)

  // ============================================================
  // JOURNAL ENTRY LINE STATE
  // ============================================================

  /**
   * Every journal transaction requires
   * at least two ledger entries.
   */
  const [lines, setLines] = useState<JournalEntryLine[]>([
    createEmptyLine(1),
    createEmptyLine(2),
  ])

  /**
   * Generate stable React keys for new rows.
   *
   * These IDs are never sent to PostgreSQL.
   */
  const nextRowId = useRef(3)

  // ============================================================
  // ACCOUNT LOADING STATE
  // ============================================================

  const [accounts, setAccounts] = useState<Account[]>([])

  const [isLoadingAccounts, setIsLoadingAccounts] =
    useState(true)

  const [accountError, setAccountError] = useState('')

  // ============================================================
  // SUBMISSION STATE
  // ============================================================

  const [isSubmitting, setIsSubmitting] = useState(false)

  const [submitError, setSubmitError] = useState('')

  // ============================================================
  // FETCH GENERAL LEDGER ACCOUNTS
  // ============================================================

  /**
   * Load real accounts for the current business.
   *
   * React ignores responses from an outdated
   * request after the component unmounts.
   */
  useEffect(() => {

    let cancelled = false

    async function loadAccounts(): Promise<void> {
      setIsLoadingAccounts(true)
      setAccountError('')

      try {
        const accountData = await getAccounts(businessId)

        if (!cancelled) {
          setAccounts(accountData)
        }

      } catch (error: unknown) {

        if (!cancelled) {
          setAccountError(
            error instanceof Error
              ? error.message
              : 'Unable to load general ledger accounts.',
          )
        }

      } finally {

        if (!cancelled) {
          setIsLoadingAccounts(false)
        }
      }
    }

    void loadAccounts()

    return () => {
      cancelled = true
    }

  }, [businessId])

  // ============================================================
  // FILTER AND SORT ACTIVE ACCOUNTS
  // ============================================================

  /**
   * Inactive accounts cannot be used to create
   * new transactions.
   *
   * Sort accounts by customizable account code.
   */
  const activeAccounts = useMemo(() => {

    return accounts
      .filter((account) => account.active)
      .sort((first, second) =>
        first.code.localeCompare(
          second.code,
          undefined,
          { numeric: true },
        ),
      )

  }, [accounts])

  // ============================================================
  // UPDATE ONE JOURNAL LINE
  // ============================================================

  /**
   * Update only the selected row and field.
   *
   * Using map() avoids mutating React state.
   */
  function updateLine(
    rowId: number,
    field: 'accountId' | 'debit' | 'credit',
    value: string,
  ): void {

    setLines((previousLines) =>
      previousLines.map((line) =>
        line.rowId === rowId
          ? { ...line, [field]: value }
          : line,
      ),
    )

    setSubmitError('')
  }

  // ============================================================
  // ADD A JOURNAL LINE
  // ============================================================

  function handleAddLine(): void {

    const newLine = createEmptyLine(nextRowId.current)

    nextRowId.current += 1

    setLines((previousLines) => [
      ...previousLines,
      newLine,
    ])

    setSubmitError('')
  }

  // ============================================================
  // REMOVE A JOURNAL LINE
  // ============================================================

  /**
   * Keep at least two journal lines.
   *
   * Removing a row only modifies the unsaved form.
   */
  function handleRemoveLine(rowId: number): void {

    setLines((previousLines) => {

      if (previousLines.length <= 2) {
        return previousLines
      }

      return previousLines.filter(
        (line) => line.rowId !== rowId,
      )
    })

    setSubmitError('')
  }

  // ============================================================
  // PARSE MONETARY AMOUNTS INTO EXACT CENTS
  // ============================================================

  /**
   * Preserve exact monetary values with bigint.
   *
   * For example:
   *
   * "500.00" becomes 50000n.
   */
  const calculatedEntries = useMemo(() => {

    return lines.map((line) => ({
      ...line,
      debitCents: parseLineAmount(line.debit),
      creditCents: parseLineAmount(line.credit),
    }))

  }, [lines])

  // ============================================================
  // VALIDATE INDIVIDUAL DEBIT AND CREDIT LINES
  // ============================================================

  /**
   * Every line must contain exactly one
   * positive debit or positive credit.
   *
   * Examples:
   *
   * Debit 500, Credit 0   — Valid
   * Debit 0, Credit 500   — Valid
   * Debit 500, Credit 500 — Invalid
   * Debit 0, Credit 0     — Invalid
   */
  const hasInvalidLines = calculatedEntries.some((line) => {

    if (
      line.debitCents === null ||
      line.creditCents === null
    ) {
      return true
    }

    const hasDebit = line.debitCents > 0n
    const hasCredit = line.creditCents > 0n

    return hasDebit === hasCredit
  })

  // ============================================================
  // CALCULATE TOTAL DEBITS
  // ============================================================

  const totalDebitCents = calculatedEntries.reduce(
    (total, line) => total + (line.debitCents ?? 0n),
    0n,
  )

  // ============================================================
  // CALCULATE TOTAL CREDITS
  // ============================================================

  const totalCreditCents = calculatedEntries.reduce(
    (total, line) => total + (line.creditCents ?? 0n),
    0n,
  )

  // ============================================================
  // VALIDATE DOUBLE-ENTRY BALANCE
  // ============================================================

  /**
   * A journal entry is balanced only when:
   *
   * - Every line has valid debit/credit amounts
   * - Total debits are positive
   * - Total debits equal total credits
   *
   * An extra empty line prevents the green
   * balance confirmation from appearing.
   */
  const isBalanced =
    !hasInvalidLines &&
    totalDebitCents > 0n &&
    totalDebitCents === totalCreditCents

  // ============================================================
  // VALIDATE ACCOUNT SELECTIONS
  // ============================================================

  /**
   * Every line must reference an existing,
   * active account in the current business.
   */
  const hasValidAccounts = lines.every((line) =>
    activeAccounts.some(
      (account) => String(account.id) === line.accountId,
    ),
  )

  // ============================================================
  // VALIDATE COMPLETE TRANSACTION FORM
  // ============================================================

  /**
   * Save as Draft becomes available only when
   * all required inputs and accounting rules pass.
   *
   * Backend validation will run again when
   * the request is submitted.
   */
  const canSubmit =
    !isLoadingAccounts &&
    !accountError &&
    !isSubmitting &&
    description.trim().length > 0 &&
    transactionDate.length > 0 &&
    transactionDate <= getTodayDate() &&
    lines.length >= 2 &&
    hasValidAccounts &&
    isBalanced

  // ============================================================
  // SUBMIT DRAFT TRANSACTION
  // ============================================================

  async function handleSubmit(
    event: FormEvent<HTMLFormElement>,
  ): Promise<void> {

    event.preventDefault()

    /**
     * Recheck the date immediately before submission.
     *
     * The calendar restriction improves user experience,
     * but we should not rely on the input control alone.
     */
    if (transactionDate > getTodayDate()) {

      setSubmitError(
        'Transaction date cannot be in the future.',
      )

      return
    }

    if (!canSubmit) {
      setSubmitError(
        'Complete all required fields and balance the journal entry.',
      )
      return
    }

    setIsSubmitting(true)
    setSubmitError('')

    try {

      // ========================================================
      // CONVERT FORM LINES INTO API REQUEST ENTRIES
      // ========================================================

      /**
       * Convert exact bigint cents into decimal strings.
       *
       * This avoids floating-point arithmetic and
       * preserves the values submitted to Spring Boot.
       */
      const entries = calculatedEntries.map((line) => ({
        accountId: Number(line.accountId),
        debit: centsToDecimalString(line.debitCents ?? 0n),
        credit: centsToDecimalString(line.creditCents ?? 0n),
      }))

      // ========================================================
      // CREATE DRAFT THROUGH SPRING BOOT
      // ========================================================

      const createdTransaction = await createDraftTransaction(
        businessId,
        {
          createdBy: DEMO_CREATOR_ID,
          description: description.trim(),
          transactionDate,
          entries,
        },
      )

      // ========================================================
      // NOTIFY THE PARENT PAGE
      // ========================================================

      /**
       * TransactionsPage closes the form,
       * displays success feedback, and refreshes
       * the transaction list.
       */
      onCreated(createdTransaction)

    } catch (error: unknown) {

      setSubmitError(
        error instanceof Error
          ? error.message
          : 'Unable to create journal entry.',
      )

    } finally {

      setIsSubmitting(false)
    }
  }

  // ============================================================
  // RENDER JOURNAL ENTRY FORM
  // ============================================================

  return (

    <section className="journal-form-card">

      {/* ====================================================== */}
      {/* FORM HEADING */}
      {/* ====================================================== */}

      <div className="journal-form-heading">

        <div>
          <p className="journal-form-eyebrow">
            GENERAL LEDGER / NEW ENTRY
          </p>

          <h2>New Journal Entry</h2>

          <p>
            Record a balanced financial transaction as a draft.
          </p>
        </div>

        <span className="journal-draft-label">
          Draft
        </span>

      </div>

      <form onSubmit={(event) => void handleSubmit(event)}>

        {/* ==================================================== */}
        {/* TRANSACTION HEADER FIELDS */}
        {/* ==================================================== */}

        <div className="journal-header-fields">

          <label>

            <span>Description</span>

            <input
              type="text"
              value={description}
              placeholder="e.g. Transfer funds to savings"
              maxLength={255}
              required
              disabled={isSubmitting}
              onChange={(event) =>
                setDescription(event.target.value)
              }
            />

          </label>

          <label>

            <span>Transaction Date</span>

            <input
              type="date"
              value={transactionDate}
              max={getTodayDate()}
              required
              disabled={isSubmitting}
              onChange={(event) =>
                setTransactionDate(event.target.value)
              }
            />

          </label>

        </div>

        {/* ==================================================== */}
        {/* ACCOUNT LOADING MESSAGE */}
        {/* ==================================================== */}

        {isLoadingAccounts && (

          <p className="journal-info" role="status">
            Loading general ledger accounts...
          </p>

        )}

        {/* ==================================================== */}
        {/* ACCOUNT LOADING ERROR */}
        {/* ==================================================== */}

        {accountError && (

          <p className="journal-error" role="alert">
            {accountError}
          </p>

        )}

        {/* ==================================================== */}
        {/* NO ACTIVE ACCOUNTS */}
        {/* ==================================================== */}

        {!isLoadingAccounts &&
          !accountError &&
          activeAccounts.length === 0 && (

            <p className="journal-error" role="alert">
              No active accounts are available.
              Create an account in Chart of Accounts first.
            </p>

          )
        }

        {/* ==================================================== */}
        {/* JOURNAL LINES HEADING */}
        {/* ==================================================== */}

        <div className="journal-lines-heading">

          <div>
            <h3>Journal Lines</h3>

            <p>
              Each line must contain a debit or a credit.
            </p>
          </div>

          <span>{lines.length} lines</span>

        </div>

        {/* ==================================================== */}
        {/* JOURNAL ENTRY TABLE */}
        {/* ==================================================== */}

        <div className="journal-lines-scroll">

          <table className="journal-lines-table">

            <thead>

              <tr>
                <th scope="col">Account</th>
                <th scope="col">Debit (USD)</th>
                <th scope="col">Credit (USD)</th>
                <th scope="col">Action</th>
              </tr>

            </thead>

            <tbody>

              {lines.map((line, index) => (

                <tr key={line.rowId}>

                  {/* ========================================== */}
                  {/* ACCOUNT DROPDOWN */}
                  {/* ========================================== */}

                  <td>

                    <select
                      aria-label={`Account for line ${index + 1}`}
                      value={line.accountId}
                      required
                      disabled={isLoadingAccounts || isSubmitting}
                      onChange={(event) =>
                        updateLine(
                          line.rowId,
                          'accountId',
                          event.target.value,
                        )
                      }
                    >

                      <option value="">
                        Select account...
                      </option>

                      {activeAccounts.map((account) => (

                        <option
                          key={account.id}
                          value={account.id}
                        >
                          {account.code} — {account.name}
                        </option>

                      ))}

                    </select>

                  </td>

                  {/* ========================================== */}
                  {/* DEBIT INPUT */}
                  {/* ========================================== */}

                  <td>

                    <input
                      type="text"
                      inputMode="decimal"
                      aria-label={`Debit for line ${index + 1}`}
                      placeholder="0.00"
                      value={line.debit}
                      disabled={isSubmitting}
                      onChange={(event) =>
                        updateLine(
                          line.rowId,
                          'debit',
                          event.target.value,
                        )
                      }
                    />

                  </td>

                  {/* ========================================== */}
                  {/* CREDIT INPUT */}
                  {/* ========================================== */}

                  <td>

                    <input
                      type="text"
                      inputMode="decimal"
                      aria-label={`Credit for line ${index + 1}`}
                      placeholder="0.00"
                      value={line.credit}
                      disabled={isSubmitting}
                      onChange={(event) =>
                        updateLine(
                          line.rowId,
                          'credit',
                          event.target.value,
                        )
                      }
                    />

                  </td>

                  {/* ========================================== */}
                  {/* REMOVE LINE BUTTON */}
                  {/* ========================================== */}

                  <td>

                    <button
                      type="button"
                      className="journal-remove-button"
                      disabled={lines.length <= 2 || isSubmitting}
                      onClick={() =>
                        handleRemoveLine(line.rowId)
                      }
                    >
                      Remove
                    </button>

                  </td>

                </tr>

              ))}

            </tbody>

            {/* ================================================ */}
            {/* DEBIT AND CREDIT TOTALS */}
            {/* ================================================ */}

            <tfoot>

              <tr>

                <th scope="row">
                  Totals
                </th>

                <td>
                  {formatUsdCents(totalDebitCents)}
                </td>

                <td>
                  {formatUsdCents(totalCreditCents)}
                </td>

                <td />

              </tr>

            </tfoot>

          </table>

        </div>

        {/* ==================================================== */}
        {/* ADD ANOTHER JOURNAL LINE */}
        {/* ==================================================== */}

        <button
          type="button"
          className="journal-add-line-button"
          disabled={isSubmitting}
          onClick={handleAddLine}
        >
          + Add Line
        </button>

        {/* ==================================================== */}
        {/* BALANCE VALIDATION MESSAGE */}
        {/* ==================================================== */}

        <p
          className={
            isBalanced
              ? 'journal-balance-status is-balanced'
              : 'journal-balance-status'
          }
          role="status"
        >

          {isBalanced
            ? '✓ Journal entry is balanced. Total debits equal total credits.'
            : 'Complete every line and ensure total debits equal total credits.'}

        </p>

        {/* ==================================================== */}
        {/* SUBMISSION ERROR */}
        {/* ==================================================== */}

        {submitError && (

          <p className="journal-error" role="alert">
            {submitError}
          </p>

        )}

        {/* ==================================================== */}
        {/* FORM ACTION BUTTONS */}
        {/* ==================================================== */}

        <div className="journal-form-actions">

          <button
            type="button"
            className="journal-cancel-button"
            disabled={isSubmitting}
            onClick={onCancel}
          >
            Cancel
          </button>

          <button
            type="submit"
            className="journal-save-button"
            disabled={!canSubmit}
          >
            {isSubmitting
              ? 'Saving Draft...'
              : 'Save as Draft'}
          </button>

        </div>

      </form>

    </section>
  )
}

// ============================================================
// EXPORT COMPONENT
// ============================================================

export default NewJournalEntryForm
