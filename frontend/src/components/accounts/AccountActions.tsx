import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from 'react'

import {
  deactivateAccount,
  renameAccount,
  type Account,
} from '../../api/accounts'

import './AccountActions.css'

// ============================================================
// LEDGERBASE — ACCOUNT MANAGEMENT ACTIONS
// ============================================================

/**
 * This component provides account management controls
 * for an individual row in the Chart of Accounts.
 *
 * Supported operations:
 *
 * - Rename an active account
 * - Deactivate an active account
 * - Confirm changes before submitting
 * - Display validation and backend errors
 * - Notify the parent after successful updates
 *
 * The existing Spring Boot API performs the
 * actual database modifications.
 */

// ============================================================
// COMPONENT PROPERTIES
// ============================================================

/**
 * The parent AccountsPage provides:
 *
 * businessId:
 *   The business that owns the account.
 *
 * account:
 *   The account displayed in this table row.
 *
 * onUpdated:
 *   A callback that refreshes the account table
 *   and displays a success notification.
 */
interface AccountActionsProps {
  businessId: number
  account: Account
  onUpdated: (message: string) => void
}

// ============================================================
// ACCOUNT ACTION TYPES
// ============================================================

/**
 * Identify which confirmation dialog is active.
 *
 * null:
 *   No dialog is open.
 *
 * rename:
 *   The user is editing the account name.
 *
 * deactivate:
 *   The user is reviewing deactivation.
 */
type AccountActionMode = 'rename' | 'deactivate' | null

// ============================================================
// ACCOUNT ACTIONS COMPONENT
// ============================================================

function AccountActions({
  businessId,
  account,
  onUpdated,
}: AccountActionsProps) {

  // ============================================================
  // DIALOG STATE
  // ============================================================

  /**
   * Track the selected account management operation.
   */
  const [mode, setMode] = useState<AccountActionMode>(null)

  /**
   * Store a reference to the native HTML dialog.
   *
   * useRef allows us to call the browser's
   * showModal() and close() methods.
   */
  const dialogRef = useRef<HTMLDialogElement | null>(null)

  // ============================================================
  // RENAME STATE
  // ============================================================

  /**
   * Store the proposed account name.
   *
   * The original account name remains unchanged
   * until Spring Boot successfully processes
   * the rename request.
   */
  const [editedName, setEditedName] = useState(account.name)

  // ============================================================
  // REQUEST STATE
  // ============================================================

  /**
   * Prevent duplicate submissions while an
   * account modification is in progress.
   */
  const [isSubmitting, setIsSubmitting] = useState(false)

  /**
   * Display validation or backend error messages.
   */
  const [errorMessage, setErrorMessage] = useState('')

  // ============================================================
  // OPEN NATIVE DIALOG
  // ============================================================

  /**
   * Open the dialog after React has rendered it.
   *
   * Changing mode causes the dialog to appear
   * in the React component tree.
   *
   * useEffect then calls showModal() to display
   * it as a native modal dialog.
   */
  useEffect(() => {

    if (
      mode !== null &&
      dialogRef.current &&
      !dialogRef.current.open
    ) {
      dialogRef.current.showModal()
    }

  }, [mode])

  // ============================================================
  // OPEN RENAME DIALOG
  // ============================================================

  /**
   * Prepare the rename form.
   *
   * Only active accounts can be renamed through
   * our current backend API.
   */
  function handleOpenRename(): void {

    if (!account.active || isSubmitting) {
      return
    }

    setEditedName(account.name)
    setErrorMessage('')
    setMode('rename')
  }

  // ============================================================
  // OPEN DEACTIVATION DIALOG
  // ============================================================

  /**
   * Require explicit confirmation before
   * deactivating an account.
   */
  function handleOpenDeactivate(): void {

    if (!account.active || isSubmitting) {
      return
    }

    setErrorMessage('')
    setMode('deactivate')
  }

  // ============================================================
  // CLOSE DIALOG
  // ============================================================

  /**
   * Close the dialog without submitting a change.
   *
   * Closing is disabled while an API request
   * is in progress.
   */
  function handleCloseDialog(): void {

    if (isSubmitting) {
      return
    }

    dialogRef.current?.close()
  }

  /**
   * Reset dialog state after the native dialog closes.
   */
  function handleDialogClosed(): void {

    setMode(null)
    setErrorMessage('')
  }

  // ============================================================
  // SAVE ACCOUNT RENAME
  // ============================================================

  /**
   * Handle the rename form submission.
   *
   * Steps:
   *
   * 1. Prevent browser navigation.
   * 2. Validate the updated name.
   * 3. Send PATCH to Spring Boot.
   * 4. Close the dialog after success.
   * 5. Notify AccountsPage to refresh.
   */
  async function handleRenameSubmit(
    event: FormEvent<HTMLFormElement>,
  ): Promise<void> {

    event.preventDefault()

    if (
      isSubmitting ||
      !account.active ||
      mode !== 'rename'
    ) {
      return
    }

    // Remove leading and trailing spaces.
    const trimmedName = editedName.trim()

    // ==========================================================
    // VALIDATE ACCOUNT NAME
    // ==========================================================

    if (!trimmedName) {
      setErrorMessage('Account name is required.')
      return
    }

    /**
     * Avoid sending an unnecessary PATCH request
     * when the account name has not changed.
     */
    if (trimmedName === account.name) {
      handleCloseDialog()
      return
    }

    // ==========================================================
    // SUBMIT RENAME REQUEST
    // ==========================================================

    setIsSubmitting(true)
    setErrorMessage('')

    try {

      /**
       * PATCH /api/v1/businesses/{businessId}/accounts/{accountId}
       *
       * Request body:
       *
       * {
       *   "name": "Updated Account Name"
       * }
       *
       * Spring Boot returns HTTP 204 No Content.
       */
      await renameAccount(
        businessId,
        account.id,
        {
          name: trimmedName,
        },
      )

      // Close after the backend confirms success.
      dialogRef.current?.close()

      /**
       * Ask AccountsPage to refresh the account list
       * and display a success notification.
       */
      onUpdated(
        `Account ${account.code} renamed to ${trimmedName}.`,
      )

    } catch (error: unknown) {

      /**
       * Keep the dialog open if the backend rejects
       * the operation, allowing the user to retry.
       */
      setErrorMessage(
        error instanceof Error
          ? error.message
          : 'Unable to rename the account.',
      )

    } finally {

      setIsSubmitting(false)
    }
  }

  // ============================================================
  // CONFIRM ACCOUNT DEACTIVATION
  // ============================================================

  /**
   * Deactivate an account after user confirmation.
   *
   * Deactivation preserves existing accounting records.
   *
   * The current backend does not expose a
   * reactivation endpoint.
   */
  async function handleConfirmDeactivate(): Promise<void> {

    if (
      isSubmitting ||
      !account.active ||
      mode !== 'deactivate'
    ) {
      return
    }

    setIsSubmitting(true)
    setErrorMessage('')

    try {

      /**
       * PATCH /api/v1/businesses/{businessId}
       *       /accounts/{accountId}/deactivate
       *
       * No request body is required.
       *
       * Spring Boot returns HTTP 204 No Content.
       */
      await deactivateAccount(
        businessId,
        account.id,
      )

      // Close the dialog after success.
      dialogRef.current?.close()

      // Refresh the account list through the parent.
      onUpdated(
        `Account ${account.code} — ${account.name} deactivated.`,
      )

    } catch (error: unknown) {

      /**
       * Keep the dialog open and display the error.
       */
      setErrorMessage(
        error instanceof Error
          ? error.message
          : 'Unable to deactivate the account.',
      )

    } finally {

      setIsSubmitting(false)
    }
  }

  // ============================================================
  // RENDER ACCOUNT ACTIONS
  // ============================================================

  return (

    <>

      {/* ====================================================== */}
      {/* ACCOUNT ROW ACTION BUTTONS */}
      {/* ====================================================== */}

      {account.active ? (

        <div className="coa-row-actions">

          <button
            type="button"
            className="coa-row-action-button"
            disabled={isSubmitting}
            onClick={handleOpenRename}
          >
            Rename
          </button>

          <button
            type="button"
            className="coa-row-action-button coa-row-action-danger"
            disabled={isSubmitting}
            onClick={handleOpenDeactivate}
          >
            Deactivate
          </button>

        </div>

      ) : (

        <span className="coa-row-action-unavailable">
          No actions
        </span>

      )}

      {/* ====================================================== */}
      {/* ACCOUNT MANAGEMENT DIALOG */}
      {/* ====================================================== */}

      {mode !== null && (

        <dialog
          ref={dialogRef}
          className="coa-action-dialog"
          aria-labelledby={`coa-action-title-${account.id}`}
          onClose={handleDialogClosed}
          onCancel={(event) => {

            /**
             * Do not allow Escape to close the dialog
             * while Spring Boot is processing a request.
             */
            if (isSubmitting) {
              event.preventDefault()
            }
          }}
        >

          {/* ================================================== */}
          {/* RENAME ACCOUNT FORM */}
          {/* ================================================== */}

          {mode === 'rename' && (

            <form
              className="coa-action-form"
              onSubmit={handleRenameSubmit}
              aria-busy={isSubmitting}
            >

              <div className="coa-action-dialog-heading">

                <h2 id={`coa-action-title-${account.id}`}>
                  Rename Account
                </h2>

                <p>
                  Update the display name of this
                  general ledger account.
                </p>

              </div>

              {/* Account being modified */}
              <div className="coa-action-account-summary">

                <span className="coa-action-account-code">
                  {account.code}
                </span>

                <strong>
                  {account.name}
                </strong>

              </div>

              {/* Editable account name */}
              <label className="coa-action-field">

                <span>Account Name</span>

                <input
                  type="text"
                  value={editedName}
                  required
                  autoFocus
                  disabled={isSubmitting}
                  onChange={(event) =>
                    setEditedName(event.target.value)
                  }
                />

              </label>

              {/* Backend or validation error */}
              {errorMessage && (

                <p className="coa-action-error" role="alert">
                  {errorMessage}
                </p>

              )}

              {/* Form buttons */}
              <div className="coa-action-dialog-buttons">

                <button
                  type="button"
                  className="coa-action-cancel-button"
                  disabled={isSubmitting}
                  onClick={handleCloseDialog}
                >
                  Cancel
                </button>

                <button
                  type="submit"
                  className="coa-action-save-button"
                  disabled={isSubmitting}
                >
                  {isSubmitting ? 'Saving...' : 'Save Changes'}
                </button>

              </div>

            </form>

          )}

          {/* ================================================== */}
          {/* DEACTIVATE ACCOUNT CONFIRMATION */}
          {/* ================================================== */}

          {mode === 'deactivate' && (

            <div className="coa-action-form">

              <div className="coa-action-dialog-heading">

                <h2 id={`coa-action-title-${account.id}`}>
                  Deactivate Account?
                </h2>

                <p>
                  Please review this action before continuing.
                </p>

              </div>

              {/* Account awaiting deactivation */}
              <div className="coa-action-account-summary">

                <span className="coa-action-account-code">
                  {account.code}
                </span>

                <strong>
                  {account.name}
                </strong>

              </div>

              {/* Explain the accounting implications */}
              <p className="coa-action-warning">

                This account will become inactive and
                cannot be used for new transactions.
                Existing accounting history will be preserved.

              </p>

              <p className="coa-action-warning">

                LedgerBase does not currently provide
                a way to reactivate this account.

              </p>

              {/* Display backend errors */}
              {errorMessage && (

                <p className="coa-action-error" role="alert">
                  {errorMessage}
                </p>

              )}

              {/* Confirmation buttons */}
              <div className="coa-action-dialog-buttons">

                <button
                  type="button"
                  className="coa-action-cancel-button"
                  disabled={isSubmitting}
                  onClick={handleCloseDialog}
                >
                  Cancel
                </button>

                <button
                  type="button"
                  className="coa-action-deactivate-button"
                  disabled={isSubmitting}
                  onClick={handleConfirmDeactivate}
                >
                  {isSubmitting
                    ? 'Deactivating...'
                    : 'Deactivate Account'}
                </button>

              </div>

            </div>

          )}

        </dialog>

      )}

    </>

  )
}

// ============================================================
// EXPORT COMPONENT
// ============================================================

export default AccountActions
