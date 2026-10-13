import { useState, type FormEvent } from 'react'

import {
  createAccount,
  type Account,
  type AccountType,
} from '../../api/accounts'

import './NewAccountForm.css'

// ============================================================
// LEDGERBASE — NEW ACCOUNT FORM
// ============================================================

/**
 * This component creates general ledger accounts
 * using the existing Spring Boot REST API.
 *
 * It manages:
 * - Controlled form inputs
 * - Client-side validation
 * - API submission
 * - Loading and error states
 * - Success and cancellation callbacks
 */

// ============================================================
// COMPONENT PROPERTIES
// ============================================================

/**
 * The parent component provides the business ID
 * and functions to call after creation or cancellation.
 */
interface NewAccountFormProps {
  businessId: number
  onCreated: (account: Account) => void
  onCancel: () => void
}

// ============================================================
// ACCOUNT TYPE OPTIONS
// ============================================================

/**
 * These values must match our backend classifications.
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

  // ============================================================
  // FORM STATE
  // ============================================================

  /**
   * Controlled inputs are connected to React state.
   *
   * Each time a user types, its corresponding
   * state value updates.
   */
  const [code, setCode] = useState('')
  const [name, setName] = useState('')

  const [accountType, setAccountType] =
    useState<AccountType>('ASSET')

  /**
   * Track API submission progress and errors.
   */
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState('')

  // ============================================================
  // SUBMIT FORM
  // ============================================================

  /**
   * Handle the form submission.
   *
   * React receives a form event rather than
   * automatically submitting an HTML form.
   */
  async function handleSubmit(
    event: FormEvent<HTMLFormElement>,
  ): Promise<void> {

    // Prevent the browser from reloading the page.
    event.preventDefault()

    // Prevent duplicate requests while saving.
    if (isSubmitting) {
      return
    }

    // Remove unnecessary whitespace.
    const trimmedCode = code.trim()
    const trimmedName = name.trim()

    // ==========================================================
    // INPUT VALIDATION
    // ==========================================================

    if (!trimmedCode) {
      setErrorMessage('Account code is required.')
      return
    }

    if (!trimmedName) {
      setErrorMessage('Account name is required.')
      return
    }

    // ==========================================================
    // SEND REQUEST TO SPRING BOOT
    // ==========================================================

    setIsSubmitting(true)
    setErrorMessage('')

    try {

      /**
       * POST /api/v1/businesses/{businessId}/accounts
       *
       * Example request:
       *
       * {
       *   "code": "1020",
       *   "name": "Business Savings",
       *   "accountType": "ASSET"
       * }
       */
      const createdAccount = await createAccount(
        businessId,
        {
          code: trimmedCode,
          name: trimmedName,
          accountType,
        },
      )

      /**
       * Tell AccountsPage that the account was created.
       *
       * The parent will close this form,
       * display a success message,
       * and refresh the account table.
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

  // ============================================================
  // RENDER FORM
  // ============================================================

  return (

    <section
      className="new-account-card"
      aria-labelledby="new-account-heading"
    >

      {/* ====================================================== */}
      {/* FORM HEADING */}
      {/* ====================================================== */}

      <div className="new-account-heading">

        <div>
          <h2 id="new-account-heading">
            New Account
          </h2>

          <p>
            Add a general ledger account to your
            business's Chart of Accounts.
          </p>
        </div>

      </div>

      {/* ====================================================== */}
      {/* CREATE ACCOUNT FORM */}
      {/* ====================================================== */}

      <form
        className="new-account-form"
        onSubmit={handleSubmit}
        aria-busy={isSubmitting}
      >

        <div className="new-account-fields">

          {/* ================================================== */}
          {/* ACCOUNT CODE */}
          {/* ================================================== */}

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

          {/* ================================================== */}
          {/* ACCOUNT NAME */}
          {/* ================================================== */}

          <label className="new-account-field">

            <span>
              Account Name <strong>*</strong>
            </span>

            <input
              type="text"
              value={name}
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

          {/* ================================================== */}
          {/* ACCOUNT TYPE */}
          {/* ================================================== */}

          <label className="new-account-field">

            <span>
              Account Type <strong>*</strong>
            </span>

            <select
              value={accountType}
              disabled={isSubmitting}
              onChange={(event) =>
                setAccountType(
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
              Select the account's financial classification.
            </small>

          </label>

        </div>

        {/* ==================================================== */}
        {/* VALIDATION / API ERROR */}
        {/* ==================================================== */}

        {errorMessage && (

          <p className="new-account-error" role="alert">
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
            {isSubmitting ? 'Creating...' : 'Create Account'}
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
