import {
  useEffect,
  useState,
  type FormEvent,
} from 'react'

import {
  createAccount,
  getAccounts,
  REPORTING_GROUP_OPTIONS,
  type Account,
  type AccountType,
  type ReportingGroup,
} from '../../api/accounts'

import './NewAccountForm.css'

// ============================================================
// LEDGERBASE — NEW ACCOUNT FORM
// ============================================================

/**
 * Creates general ledger accounts through the
 * Spring Boot REST API.
 *
 * Existing capabilities:
 *
 * - Account code and name
 * - Fundamental account type
 * - Client-side validation
 * - Loading and error states
 * - Creation and cancellation callbacks
 *
 * V7 capabilities:
 *
 * - Financial reporting classification
 * - Posting versus grouping accounts
 * - Optional parent-account hierarchy
 *
 * The backend and PostgreSQL remain responsible
 * for validating accounting integrity.
 */

// ============================================================
// COMPONENT PROPERTIES
// ============================================================

interface NewAccountFormProps {
  businessId: number
  onCreated: (account: Account) => void
  onCancel: () => void
}

// ============================================================
// ACCOUNT TYPE OPTIONS
// ============================================================

/**
 * These values match the five fundamental
 * account types defined by PostgreSQL.
 */
const accountTypeOptions: {
  value: AccountType
  label: string
}[] = [
  { value: 'ASSET', label: 'Asset' },
  { value: 'LIABILITY', label: 'Liability' },
  { value: 'EQUITY', label: 'Equity' },
  { value: 'REVENUE', label: 'Revenue' },
  { value: 'EXPENSE', label: 'Expense' },
]

// ============================================================
// NEW ACCOUNT FORM COMPONENT
// ============================================================

function NewAccountForm({
  businessId,
  onCreated,
  onCancel,
}: NewAccountFormProps) {

  // ==========================================================
  // BASIC FORM STATE
  // ==========================================================

  const [code, setCode] = useState('')

  const [name, setName] = useState('')

  const [accountType, setAccountType] =
    useState<AccountType>('ASSET')


  // ==========================================================
  // V7 — REPORTING GROUP STATE
  // ==========================================================

  /**
   * An empty string represents an account that has
   * not yet been classified for financial reporting.
   *
   * The API receives null instead of an empty string.
   */
  const [reportingGroup, setReportingGroup] =
    useState<ReportingGroup | ''>('')


  // ==========================================================
  // V7 — POSTING ELIGIBILITY STATE
  // ==========================================================

  /**
   * TRUE:
   *   Normal account accepting journal entries.
   *
   * FALSE:
   *   Organizational heading that cannot receive
   *   journal entries directly.
   *
   * Default TRUE preserves existing form behavior.
   */
  const [postable, setPostable] = useState(true)


  // ==========================================================
  // V7 — PARENT ACCOUNT STATE
  // ==========================================================

  /**
   * Parent accounts are optional.
   *
   * A parent must:
   *
   * - Belong to the current business.
   * - Have the same fundamental account type.
   * - Be a non-postable grouping account.
   *
   * Null means the new account is at the top level.
   */
  const [parentAccountId, setParentAccountId] =
    useState<number | null>(null)

  /**
   * Existing accounts loaded from Spring Boot.
   *
   * We'll filter these accounts before displaying
   * them as parent-account options.
   */
  const [existingAccounts, setExistingAccounts] =
    useState<Account[]>([])

  const [loadingParents, setLoadingParents] =
    useState(true)

  const [parentLoadError, setParentLoadError] =
    useState('')


  // ==========================================================
  // API SUBMISSION STATE
  // ==========================================================

  const [isSubmitting, setIsSubmitting] = useState(false)

  const [errorMessage, setErrorMessage] = useState('')


  // ==========================================================
  // V7 — LOAD EXISTING ACCOUNTS
  // ==========================================================

  /**
   * Fetch existing accounts when the form opens
   * or the selected business changes.
   *
   * We need these records to populate the optional
   * parent-account dropdown.
   *
   * The cancelled flag prevents a request from
   * updating component state after unmounting.
   *
   * This approach does not require changing
   * AccountsPage.tsx yet.
   */
  useEffect(() => {

    let cancelled = false

    async function loadAccounts(): Promise<void> {

      setLoadingParents(true)
      setParentLoadError('')
      setExistingAccounts([])
      setParentAccountId(null)

      try {

        const accounts = await getAccounts(businessId)

        if (!cancelled) {
          setExistingAccounts(accounts)
        }

      } catch {

        if (!cancelled) {

          setParentLoadError(
            'Unable to load parent accounts. ' +
            'You can still create a top-level account.',
          )
        }

      } finally {

        if (!cancelled) {
          setLoadingParents(false)
        }
      }
    }

    void loadAccounts()

    return () => {
      cancelled = true
    }

  }, [businessId])


  // ==========================================================
  // V7 — FILTER REPORTING GROUPS
  // ==========================================================

  /**
   * Only display reporting groups compatible with
   * the selected fundamental account type.
   *
   * Example:
   *
   * ASSET:
   *   Current Assets
   *   Non-current Assets
   *
   * EXPENSE:
   *   Cost of Goods Sold
   *   Operating Expenses
   *   Other Expenses
   */
  const reportingGroupOptions =
    REPORTING_GROUP_OPTIONS[accountType]


  // ==========================================================
  // V7 — FILTER ELIGIBLE PARENT ACCOUNTS
  // ==========================================================

  /**
   * Only active, non-postable grouping accounts
   * of the same fundamental type are shown.
   *
   * PostgreSQL independently enforces business
   * isolation, type compatibility, and hierarchy rules.
   */
  const eligibleParentAccounts = existingAccounts.filter(
    (account) =>
      account.accountType === accountType &&
      account.postable === false &&
      account.active === true,
  )


  // ==========================================================
  // HANDLE ACCOUNT TYPE CHANGE
  // ==========================================================

  /**
   * Changing the fundamental account type invalidates
   * the previous reporting-group and parent selections.
   *
   * Reset both to avoid submitting incompatible values.
   */
  function handleAccountTypeChange(
    newType: AccountType,
  ): void {

    setAccountType(newType)

    setReportingGroup('')

    setParentAccountId(null)
  }


  // ==========================================================
  // SUBMIT FORM
  // ==========================================================

  async function handleSubmit(
    event: FormEvent<HTMLFormElement>,
  ): Promise<void> {

    event.preventDefault()

    if (isSubmitting) {
      return
    }

    const trimmedCode = code.trim()
    const trimmedName = name.trim()


    // ========================================================
    // VALIDATE REQUIRED INFORMATION
    // ========================================================

    if (!trimmedCode) {

      setErrorMessage('Account code is required.')
      return
    }

    if (!trimmedName) {

      setErrorMessage('Account name is required.')
      return
    }

    if (trimmedName.length > 150) {

      setErrorMessage(
        'Account name cannot exceed 150 characters.',
      )
      return
    }


    // ========================================================
    // VALIDATE OPTIONAL PARENT
    // ========================================================

    /**
     * Reject a parent selection if it is no longer
     * available among the eligible parent accounts.
     *
     * This is a convenience validation only.
     *
     * The backend remains authoritative.
     */
    if (
      parentAccountId !== null &&
      !eligibleParentAccounts.some(
        (account) => account.id === parentAccountId,
      )
    ) {

      setErrorMessage(
        'Please select a valid parent grouping account.',
      )
      return
    }


    // ========================================================
    // SEND CREATE REQUEST TO SPRING BOOT
    // ========================================================

    setIsSubmitting(true)
    setErrorMessage('')

    try {

      /**
       * POST /api/v1/businesses/{businessId}/accounts
       *
       * Example request:
       *
       * {
       *   "code": "1010",
       *   "name": "Cash",
       *   "accountType": "ASSET",
       *   "reportingGroup": "CURRENT_ASSET",
       *   "parentAccountId": 12,
       *   "postable": true
       * }
       *
       * Convert an unselected reporting group to null.
       */
      const createdAccount = await createAccount(
        businessId,
        {
          code: trimmedCode,
          name: trimmedName,
          accountType,

          reportingGroup: reportingGroup || null,

          parentAccountId,

          postable,
        },
      )

      /**
       * AccountsPage will:
       *
       * - Close the form
       * - Refresh the account list
       * - Display its existing success feedback
       */
      onCreated(createdAccount)

    } catch (error: unknown) {

      setErrorMessage(
        error instanceof Error
          ? error.message
          : 'Unable to create the account.',
      )

    } finally {

      setIsSubmitting(false)
    }
  }


  // ==========================================================
  // RENDER FORM
  // ==========================================================

  return (

    <section
      className="new-account-card"
      aria-labelledby="new-account-heading"
    >

      {/* ==================================================== */}
      {/* FORM HEADING */}
      {/* ==================================================== */}

      <div className="new-account-heading">

        <div>

          <h2 id="new-account-heading">
            New Account
          </h2>

          <p>
            Add a general ledger account to your
            business&apos;s Chart of Accounts.
          </p>

        </div>

      </div>


      {/* ==================================================== */}
      {/* CREATE ACCOUNT FORM */}
      {/* ==================================================== */}

      <form
        className="new-account-form"
        onSubmit={handleSubmit}
        aria-busy={isSubmitting}
      >

        <div className="new-account-fields">

          {/* ================================================= */}
          {/* ACCOUNT CODE */}
          {/* ================================================= */}

          <label className="new-account-field">

            <span>
              Account Code <strong>*</strong>
            </span>

            <input
              type="text"
              value={code}
              maxLength={20}
              required
              autoFocus
              autoComplete="off"
              placeholder="e.g. 1020"
              disabled={isSubmitting}
              onChange={(event) =>
                setCode(event.target.value)
              }
            />

            <small>
              Unique code used to identify the account.
            </small>

          </label>


          {/* ================================================= */}
          {/* ACCOUNT NAME */}
          {/* ================================================= */}

          <label className="new-account-field">

            <span>
              Account Name <strong>*</strong>
            </span>

            <input
              type="text"
              value={name}
              maxLength={150}
              required
              placeholder="e.g. Business Savings"
              disabled={isSubmitting}
              onChange={(event) =>
                setName(event.target.value)
              }
            />

            <small>
              Use a clear, descriptive account name.
            </small>

          </label>


          {/* ================================================= */}
          {/* ACCOUNT TYPE */}
          {/* ================================================= */}

          <label className="new-account-field">

            <span>
              Account Type <strong>*</strong>
            </span>

            <select
              value={accountType}
              disabled={isSubmitting}
              onChange={(event) =>
                handleAccountTypeChange(
                  event.target.value as AccountType,
                )
              }
            >

              {accountTypeOptions.map((option) => (

                <option
                  key={option.value}
                  value={option.value}
                >
                  {option.label}
                </option>

              ))}

            </select>

            <small>
              Select the fundamental accounting type.
            </small>

          </label>


          {/* ================================================= */}
          {/* V7 — REPORTING GROUP */}
          {/* ================================================= */}

          <label className="new-account-field">

            <span>
              Reporting Group
            </span>

            <select
              value={reportingGroup}
              disabled={isSubmitting}
              onChange={(event) =>
                setReportingGroup(
                  event.target.value as ReportingGroup | '',
                )
              }
            >

              <option value="">
                Not classified
              </option>

              {reportingGroupOptions.map((option) => (

                <option
                  key={option.value}
                  value={option.value}
                >
                  {option.label}
                </option>

              ))}

            </select>

            <small>
              Determines where this account appears
              in financial statements.
            </small>

          </label>


          {/* ================================================= */}
          {/* V7 — ACCOUNT PURPOSE */}
          {/* ================================================= */}

          <label className="new-account-field">

            <span>
              Account Purpose <strong>*</strong>
            </span>

            <select
              value={postable ? 'posting' : 'grouping'}
              disabled={isSubmitting}
              onChange={(event) =>
                setPostable(
                  event.target.value === 'posting',
                )
              }
            >

              <option value="posting">
                Posting Account
              </option>

              <option value="grouping">
                Grouping Account
              </option>

            </select>

            <small>
              {postable
                ? 'Posting accounts can receive journal entries.'
                : 'Grouping accounts organize other accounts and cannot receive journal entries.'}
            </small>

          </label>


          {/* ================================================= */}
          {/* V7 — OPTIONAL PARENT ACCOUNT */}
          {/* ================================================= */}

          <label className="new-account-field">

            <span>
              Parent Account
            </span>

            <select
              value={parentAccountId ?? ''}
              disabled={
                isSubmitting ||
                loadingParents ||
                Boolean(parentLoadError) ||
                eligibleParentAccounts.length === 0
              }
              onChange={(event) =>
                setParentAccountId(
                  event.target.value === ''
                    ? null
                    : Number(event.target.value),
                )
              }
            >

              <option value="">
                {loadingParents
                  ? 'Loading parent accounts...'
                  : 'No parent (top-level account)'}
              </option>

              {eligibleParentAccounts.map((account) => (

                <option
                  key={account.id}
                  value={account.id}
                >
                  {account.code} — {account.name}
                </option>

              ))}

            </select>

            <small>
              {parentLoadError
                ? parentLoadError
                : eligibleParentAccounts.length === 0 &&
                    !loadingParents
                  ? 'No eligible grouping accounts exist for this account type.'
                  : 'Optional. Select a non-postable grouping account of the same type.'}
            </small>

          </label>

        </div>


        {/* ==================================================== */}
        {/* VALIDATION / API ERROR */}
        {/* ==================================================== */}

        {errorMessage && (

          <p
            className="new-account-error"
            role="alert"
          >
            {errorMessage}
          </p>

        )}


        {/* ==================================================== */}
        {/* FORM ACTIONS */}
        {/* ==================================================== */}

        <div className="new-account-actions">

          <button
            type="button"
            className="new-account-cancel"
            disabled={isSubmitting}
            onClick={onCancel}
          >
            Cancel
          </button>

          <button
            type="submit"
            className="new-account-submit"
            disabled={isSubmitting}
          >
            {isSubmitting
              ? 'Creating...'
              : 'Create Account'}
          </button>

        </div>

      </form>

    </section>

  )
}

// ============================================================
// EXPORT COMPONENT
// ============================================================

export default NewAccountForm
