import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from 'react'

import {
  deactivateAccount,
  renameAccount,
  updateAccountReportingGroup,
  REPORTING_GROUP_OPTIONS,
  type Account,
  type ReportingGroup,
} from '../../api/accounts'

import './AccountActions.css'

// ============================================================
// LEDGERBASE — ACCOUNT MANAGEMENT ACTIONS
// ============================================================

/**
 * This component provides account management actions
 * for an individual Chart of Accounts table row.
 *
 * Existing operations:
 *
 * - Rename an active account
 * - Deactivate an active account
 * - Confirm changes through native HTML dialogs
 * - Display validation and backend errors
 * - Notify AccountsPage after successful updates
 *
 * V7 addition:
 *
 * - Edit financial reporting classification
 *
 * IMPORTANT:
 *
 * Both active and inactive accounts may have their
 * reporting classifications updated.
 *
 * Rename and Deactivate remain available only
 * for active accounts.
 */

// ============================================================
// COMPONENT PROPERTIES
// ============================================================

interface AccountActionsProps {

  // Business that owns the account.
  businessId: number

  // Account displayed in the current table row.
  account: Account

  // Refresh AccountsPage after successful modifications.
  onUpdated: (message: string) => void
}

// ============================================================
// ACCOUNT ACTION MODES
// ============================================================

/**
 * Identify which modal operation is currently active.
 *
 * null:
 *   No dialog is open.
 *
 * classification:
 *   Edit the account's financial reporting group.
 *
 * rename:
 *   Edit the account's display name.
 *
 * deactivate:
 *   Confirm account deactivation.
 */
type AccountActionMode =
  | 'classification'
  | 'rename'
  | 'deactivate'
  | null

// ============================================================
// ACCOUNT ACTIONS COMPONENT
// ============================================================

function AccountActions({
  businessId,
  account,
  onUpdated,
}: AccountActionsProps) {

  // ==========================================================
  // DIALOG STATE
  // ==========================================================

  const [mode, setMode] =
    useState<AccountActionMode>(null)

  /**
   * Reference to the native HTML dialog.
   *
   * React renders the dialog, then useEffect
   * opens it using showModal().
   */
  const dialogRef =
    useRef<HTMLDialogElement | null>(null)

  // ==========================================================
  // RENAME STATE
  // ==========================================================

  /**
   * Store the proposed account name.
   *
   * Changes are only persisted after the
   * backend confirms a successful PATCH request.
   */
  const [editedName, setEditedName] =
    useState(account.name)

  // ==========================================================
  // V7 — REPORTING GROUP STATE
  // ==========================================================

  /**
   * The current reporting group may be null.
   *
   * An empty string represents an account that
   * has not yet been classified.
   *
   * The new backend endpoint requires a valid
   * reporting group when saving.
   */
  const [editedReportingGroup, setEditedReportingGroup] =
    useState<ReportingGroup | ''>(
      account.reportingGroup ?? '',
    )

  /**
   * Reporting groups are filtered by the account's
   * fundamental accounting type.
   *
   * Example:
   *
   * ASSET:
   *   Current Assets
   *   Non-current Assets
   *
   * REVENUE:
   *   Operating Revenue
   *   Other Income
   *
   * This prevents invalid options being offered
   * in the UI.
   *
   * The backend validates compatibility separately.
   */
  const reportingGroupOptions =
    REPORTING_GROUP_OPTIONS[account.accountType]

  // ==========================================================
  // API REQUEST STATE
  // ==========================================================

  const [isSubmitting, setIsSubmitting] =
    useState(false)

  const [errorMessage, setErrorMessage] =
    useState('')

  // ==========================================================
  // OPEN NATIVE DIALOG
  // ==========================================================

  /**
   * Open the dialog after React renders it.
   *
   * Only one action dialog may be active at a time.
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

  // ==========================================================
  // V7 — OPEN CLASSIFICATION DIALOG
  // ==========================================================

  /**
   * Prepare the reporting-group editor.
   *
   * IMPORTANT:
   *
   * Inactive accounts are allowed.
   *
   * Historical accounts may still contribute to
   * Balance Sheets and Income Statements.
   */
  function handleOpenClassification(): void {

    if (isSubmitting) {
      return
    }

    // Use the account's current saved classification.
    setEditedReportingGroup(
      account.reportingGroup ?? '',
    )

    setErrorMessage('')

    setMode('classification')
  }

  // ==========================================================
  // OPEN RENAME DIALOG
  // ==========================================================

  /**
   * Only active accounts can be renamed through
   * our existing backend operation.
   */
  function handleOpenRename(): void {

    if (!account.active || isSubmitting) {
      return
    }

    setEditedName(account.name)

    setErrorMessage('')

    setMode('rename')
  }

  // ==========================================================
  // OPEN DEACTIVATION DIALOG
  // ==========================================================

  /**
   * Require confirmation before changing an
   * active account's status.
   */
  function handleOpenDeactivate(): void {

    if (!account.active || isSubmitting) {
      return
    }

    setErrorMessage('')

    setMode('deactivate')
  }

  // ==========================================================
  // CLOSE DIALOG
  // ==========================================================

  /**
   * Prevent users from closing a dialog while
   * an API request is being processed.
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

  // ==========================================================
  // V7 — SAVE REPORTING GROUP
  // ==========================================================

  /**
   * Submit a reporting-group update to Spring Boot.
   *
   * Workflow:
   *
   * 1. Validate that a classification was selected.
   * 2. Avoid unnecessary updates.
   * 3. Send PATCH to the backend.
   * 4. Close the dialog after success.
   * 5. Refresh AccountsPage.
   *
   * This operation does not modify:
   *
   * - Account code
   * - Account name
   * - Fundamental account type
   * - Parent account
   * - Posting eligibility
   * - Existing journal entries
   */
  async function handleClassificationSubmit(
    event: FormEvent<HTMLFormElement>,
  ): Promise<void> {

    event.preventDefault()

    if (
      isSubmitting ||
      mode !== 'classification'
    ) {
      return
    }

    // ========================================================
    // VALIDATE SELECTED REPORTING GROUP
    // ========================================================

    const selectedGroup = editedReportingGroup

    if (!selectedGroup) {

      setErrorMessage(
        'Please select a reporting group.',
      )

      return
    }

    // ========================================================
    // AVOID UNNECESSARY API REQUESTS
    // ========================================================

    /**
     * If the user selected the same classification,
     * simply close the dialog.
     *
     * No database modification is needed.
     */
    if (
      selectedGroup === account.reportingGroup
    ) {

      handleCloseDialog()

      return
    }

    // ========================================================
    // SEND REQUEST TO SPRING BOOT
    // ========================================================

    setIsSubmitting(true)

    setErrorMessage('')

    try {

      /**
       * PATCH
       * /api/v1/businesses/{businessId}
       * /accounts/{accountId}/reporting-group
       *
       * Request body:
       *
       * {
       *   "reportingGroup": "CURRENT_ASSET"
       * }
       *
       * Spring Boot returns HTTP 200 OK after
       * successfully updating the account.
       */
      await updateAccountReportingGroup(
        businessId,
        account.id,
        {
          reportingGroup: selectedGroup,
        },
      )

      // ======================================================
      // CLOSE DIALOG AFTER SUCCESS
      // ======================================================

      dialogRef.current?.close()

      // ======================================================
      // NOTIFY PARENT COMPONENT
      // ======================================================

      /**
       * Find the user-facing label corresponding
       * to the newly saved reporting group.
       */
      const selectedOption =
        reportingGroupOptions.find(
          (option) => option.value === selectedGroup,
        )

      const reportingGroupLabel =
        selectedOption?.label ?? selectedGroup

      onUpdated(
        `Account ${account.code} classified as ${reportingGroupLabel}.`,
      )

    } catch (error: unknown) {

      // ======================================================
      // HANDLE VALIDATION OR API ERRORS
      // ======================================================

      /**
       * Keep the dialog open after a failed request.
       *
       * This allows the user to review the error
       * and retry without losing the selection.
       */
      setErrorMessage(
        error instanceof Error
          ? error.message
          : 'Unable to update account classification.',
      )

    } finally {

      setIsSubmitting(false)
    }
  }

  // ==========================================================
  // SAVE ACCOUNT RENAME
  // ==========================================================

  /**
   * Preserve the existing account rename workflow.
   *
   * Only active accounts may be renamed.
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

    const trimmedName = editedName.trim()

    // ========================================================
    // VALIDATE ACCOUNT NAME
    // ========================================================

    if (!trimmedName) {

      setErrorMessage(
        'Account name is required.',
      )

      return
    }

    if (trimmedName.length > 150) {

      setErrorMessage(
        'Account name cannot exceed 150 characters.',
      )

      return
    }

    // ========================================================
    // AVOID UNNECESSARY UPDATE
    // ========================================================

    if (trimmedName === account.name) {

      handleCloseDialog()

      return
    }

    // ========================================================
    // SUBMIT RENAME REQUEST
    // ========================================================

    setIsSubmitting(true)

    setErrorMessage('')

    try {

      /**
       * Existing endpoint:
       *
       * PATCH
       * /api/v1/businesses/{businessId}/accounts/{accountId}
       *
       * Successful response: HTTP 204 No Content.
       */
      await renameAccount(
        businessId,
        account.id,
        {
          name: trimmedName,
        },
      )

      dialogRef.current?.close()

      onUpdated(
        `Account ${account.code} renamed to ${trimmedName}.`,
      )

    } catch (error: unknown) {

      setErrorMessage(
        error instanceof Error
          ? error.message
          : 'Unable to rename the account.',
      )

    } finally {

      setIsSubmitting(false)
    }
  }

  // ==========================================================
  // CONFIRM ACCOUNT DEACTIVATION
  // ==========================================================

  /**
   * Preserve the existing deactivation workflow.
   *
   * Deactivation does not delete the account or
   * remove historical accounting records.
   *
   * The backend does not yet support reactivation.
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
       * Existing endpoint:
       *
       * PATCH
       * /api/v1/businesses/{businessId}
       * /accounts/{accountId}/deactivate
       *
       * Successful response: HTTP 204 No Content.
       */
      await deactivateAccount(
        businessId,
        account.id,
      )

      dialogRef.current?.close()

      onUpdated(
        `Account ${account.code} — ${account.name} deactivated.`,
      )

    } catch (error: unknown) {

      setErrorMessage(
        error instanceof Error
          ? error.message
          : 'Unable to deactivate the account.',
      )

    } finally {

      setIsSubmitting(false)
    }
  }

  // ==========================================================
  // RENDER ACCOUNT ACTIONS
  // ==========================================================

  return (

    <>

      {/* ====================================================== */}
      {/* ACCOUNT ROW ACTION BUTTONS */}
      {/* ====================================================== */}

      <div className="coa-row-actions">

        {/* ==================================================== */}
        {/* V7 — EDIT CLASSIFICATION */}
        {/* ==================================================== */}

        {/*
          Classification remains available for
          active and inactive accounts.
        */}
        <button
          type="button"
          className="coa-row-action-button"
          disabled={isSubmitting}
          onClick={handleOpenClassification}
        >
          Edit Classification
        </button>

        {/* ==================================================== */}
        {/* RENAME — ACTIVE ACCOUNTS ONLY */}
        {/* ==================================================== */}

        {account.active && (

          <button
            type="button"
            className="coa-row-action-button"
            disabled={isSubmitting}
            onClick={handleOpenRename}
          >
            Rename
          </button>

        )}

        {/* ==================================================== */}
        {/* DEACTIVATE — ACTIVE ACCOUNTS ONLY */}
        {/* ==================================================== */}

        {account.active && (

          <button
            type="button"
            className="coa-row-action-button coa-row-action-danger"
            disabled={isSubmitting}
            onClick={handleOpenDeactivate}
          >
            Deactivate
          </button>

        )}

      </div>

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
             * Prevent Escape from closing the dialog
             * while a request is in progress.
             */
            if (isSubmitting) {
              event.preventDefault()
            }
          }}
        >

          {/* ================================================== */}
          {/* V7 — EDIT REPORTING CLASSIFICATION */}
          {/* ================================================== */}

          {mode === 'classification' && (

            <form
              className="coa-action-form"
              onSubmit={handleClassificationSubmit}
              aria-busy={isSubmitting}
            >

              <div className="coa-action-dialog-heading">

                <h2 id={`coa-action-title-${account.id}`}>
                  Edit Classification
                </h2>

                <p>
                  Assign this account to the appropriate
                  financial reporting group.
                </p>

              </div>

              {/* ============================================== */}
              {/* SELECTED ACCOUNT SUMMARY */}
              {/* ============================================== */}

              <div className="coa-action-account-summary">

                <span className="coa-action-account-code">
                  {account.code}
                </span>

                <strong>
                  {account.name}
                </strong>

              </div>

              {/* ============================================== */}
              {/* REPORTING GROUP SELECT */}
              {/* ============================================== */}

              <label className="coa-action-field">

                <span>
                  Reporting Group
                </span>

                <select
                  value={editedReportingGroup}
                  required
                  autoFocus
                  disabled={isSubmitting}
                  onChange={(event) =>
                    setEditedReportingGroup(
                      event.target.value as ReportingGroup | '',
                    )
                  }
                >

                  <option value="" disabled>
                    Select a reporting group
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
                  Available classifications are based
                  on this account&apos;s accounting type:
                  {' '}
                  {account.accountType.toLowerCase()}.
                </small>

              </label>

              {/* ============================================== */}
              {/* BACKEND OR VALIDATION ERROR */}
              {/* ============================================== */}

              {errorMessage && (

                <p
                  className="coa-action-error"
                  role="alert"
                >
                  {errorMessage}
                </p>

              )}

              {/* ============================================== */}
              {/* FORM ACTIONS */}
              {/* ============================================== */}

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
                  {isSubmitting
                    ? 'Saving...'
                    : 'Save Classification'}
                </button>

              </div>

            </form>

          )}

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

              {/* ============================================== */}
              {/* SELECTED ACCOUNT SUMMARY */}
              {/* ============================================== */}

              <div className="coa-action-account-summary">

                <span className="coa-action-account-code">
                  {account.code}
                </span>

                <strong>
                  {account.name}
                </strong>

              </div>

              {/* ============================================== */}
              {/* EDIT ACCOUNT NAME */}
              {/* ============================================== */}

              <label className="coa-action-field">

                <span>
                  Account Name
                </span>

                <input
                  type="text"
                  value={editedName}
                  maxLength={150}
                  required
                  autoFocus
                  disabled={isSubmitting}
                  onChange={(event) =>
                    setEditedName(event.target.value)
                  }
                />

              </label>

              {/* ============================================== */}
              {/* BACKEND OR VALIDATION ERROR */}
              {/* ============================================== */}

              {errorMessage && (

                <p
                  className="coa-action-error"
                  role="alert"
                >
                  {errorMessage}
                </p>

              )}

              {/* ============================================== */}
              {/* FORM ACTIONS */}
              {/* ============================================== */}

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
                  {isSubmitting
                    ? 'Saving...'
                    : 'Save Changes'}
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

              {/* ============================================== */}
              {/* SELECTED ACCOUNT SUMMARY */}
              {/* ============================================== */}

              <div className="coa-action-account-summary">

                <span className="coa-action-account-code">
                  {account.code}
                </span>

                <strong>
                  {account.name}
                </strong>

              </div>

              {/* ============================================== */}
              {/* ACCOUNTING HISTORY WARNING */}
              {/* ============================================== */}

              <p className="coa-action-warning">

                This account will become inactive and
                cannot be used for new transactions.
                Existing accounting history will be preserved.

              </p>

              <p className="coa-action-warning">

                LedgerBase does not currently provide
                a way to reactivate this account.

              </p>

              {/* ============================================== */}
              {/* BACKEND ERROR */}
              {/* ============================================== */}

              {errorMessage && (

                <p
                  className="coa-action-error"
                  role="alert"
                >
                  {errorMessage}
                </p>

              )}

              {/* ============================================== */}
              {/* CONFIRMATION BUTTONS */}
              {/* ============================================== */}

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
