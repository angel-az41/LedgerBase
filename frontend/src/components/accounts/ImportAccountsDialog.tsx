import {
  useRef,
  useState,
  type ChangeEvent,
} from 'react'

import {
  Upload,
  FileSpreadsheet,
  X,
  AlertCircle,
  CheckCircle2,
} from 'lucide-react'

import {
  previewAccountImport,
  executeAccountImport,
  type AccountImportPreviewResponse,
} from '../../api/accountImports'

import './ImportAccountsDialog.css'

// ============================================================
// LEDGERBASE — IMPORT ACCOUNTS DIALOG
// ============================================================

/**
 * Provide a safe Chart of Accounts import workflow.
 *
 * Workflow:
 *
 * 1. Select a CSV or XLSX file.
 * 2. Request a read-only import preview.
 * 3. Display parsed account rows.
 * 4. Display accounting validation errors.
 * 5. Enable confirmation only for valid files.
 * 6. Execute the atomic database import.
 * 7. Notify AccountsPage to refresh its data.
 *
 * All authoritative validation occurs in Spring Boot.
 */

// ============================================================
// COMPONENT PROPERTIES
// ============================================================

interface ImportAccountsDialogProps {
  businessId: number

  /**
   * Called after the backend confirms success.
   *
   * AccountsPage uses this callback to reload its
   * account list and display a success message.
   */
  onImported: (importedRows: number) => void
}

// ============================================================
// FILE SIZE LIMIT
// ============================================================

/**
 * Match the existing Spring Boot 5 MB limit.
 */
const MAX_FILE_BYTES = 5 * 1024 * 1024

// ============================================================
// COMPONENT
// ============================================================

function ImportAccountsDialog({
  businessId,
  onImported,
}: ImportAccountsDialogProps) {

  // ==========================================================
  // NATIVE DIALOG REFERENCE
  // ==========================================================

  /**
   * HTMLDialogElement provides accessible modal
   * behavior, including focus management.
   */
  const dialogRef = useRef<HTMLDialogElement>(null)

  // ==========================================================
  // FILE AND PREVIEW STATE
  // ==========================================================

  const [selectedFile, setSelectedFile] =
    useState<File | null>(null)

  const [preview, setPreview] =
    useState<AccountImportPreviewResponse | null>(null)

  // ==========================================================
  // REQUEST STATE
  // ==========================================================

  const [isPreviewing, setIsPreviewing] = useState(false)

  const [isImporting, setIsImporting] = useState(false)

  const [errorMessage, setErrorMessage] = useState('')

  const isBusy = isPreviewing || isImporting

  // ==========================================================
  // OPEN DIALOG
  // ==========================================================

  function handleOpen(): void {

    setSelectedFile(null)
    setPreview(null)
    setErrorMessage('')

    dialogRef.current?.showModal()
  }

  // ==========================================================
  // CLOSE DIALOG
  // ==========================================================

  /**
   * Prevent users from closing the modal while
   * an import request is still being processed.
   */
  function handleClose(): void {

    if (isBusy) {
      return
    }

    dialogRef.current?.close()
  }

  // ==========================================================
  // SELECT FILE
  // ==========================================================

  function handleFileChange(
    event: ChangeEvent<HTMLInputElement>,
  ): void {

    const file = event.target.files?.[0] ?? null

    // Any new selection invalidates the old preview.
    setSelectedFile(null)
    setPreview(null)
    setErrorMessage('')

    if (!file) {
      return
    }

    // ========================================================
    // VALIDATE FILE EXTENSION
    // ========================================================

    const filename = file.name.toLowerCase()

    if (
      !filename.endsWith('.csv') &&
      !filename.endsWith('.xlsx')
    ) {

      setErrorMessage(
        'Please select a CSV or XLSX file.',
      )

      event.target.value = ''
      return
    }

    // ========================================================
    // VALIDATE FILE SIZE
    // ========================================================

    if (file.size === 0) {

      setErrorMessage('The selected file is empty.')

      event.target.value = ''
      return
    }

    if (file.size > MAX_FILE_BYTES) {

      setErrorMessage(
        'The selected file exceeds the 5 MB limit.',
      )

      event.target.value = ''
      return
    }

    setSelectedFile(file)
  }

  // ==========================================================
  // REQUEST ACCOUNT IMPORT PREVIEW
  // ==========================================================

  async function handlePreview(): Promise<void> {

    if (!selectedFile || isBusy) {
      return
    }

    setIsPreviewing(true)
    setErrorMessage('')
    setPreview(null)

    try {

      // Read-only backend validation.
      const result = await previewAccountImport(
        businessId,
        selectedFile,
      )

      setPreview(result)

    } catch (error: unknown) {

      setErrorMessage(
        error instanceof Error
          ? error.message
          : 'Unable to preview the selected file.',
      )

    } finally {

      setIsPreviewing(false)
    }
  }

  // ==========================================================
  // EXECUTE CONFIRMED ACCOUNT IMPORT
  // ==========================================================

  async function handleImport(): Promise<void> {

    /**
     * An import is allowed only after a successful
     * backend preview of at least one account.
     */
    if (
      !selectedFile ||
      !preview?.readyToImport ||
      preview.totalRows === 0 ||
      isBusy
    ) {
      return
    }

    setIsImporting(true)
    setErrorMessage('')

    try {

      /**
       * The backend revalidates everything.
       *
       * The earlier preview does not bypass
       * server-side checks.
       */
      const result = await executeAccountImport(
        businessId,
        selectedFile,
      )

      if (!result.success) {

        throw new Error(
          'The account import was not completed.',
        )
      }

      // ======================================================
      // NOTIFY PARENT OF SUCCESSFUL IMPORT
      // ======================================================

      onImported(result.importedRows)

      // Close only after the backend confirms success.
      dialogRef.current?.close()

    } catch (error: unknown) {

      setErrorMessage(
        error instanceof Error
          ? error.message
          : 'Unable to import accounts.',
      )

      /**
       * A previous preview may now be stale.
       *
       * For example, another request could have
       * created a duplicate account code.
       *
       * Require another preview before retrying.
       */
      setPreview(null)

    } finally {

      setIsImporting(false)
    }
  }

  // ==========================================================
  // IMPORT ELIGIBILITY
  // ==========================================================

  const canImport =
    selectedFile !== null &&
    preview?.readyToImport === true &&
    preview.totalRows > 0 &&
    !isBusy

  // ==========================================================
  // FILE-LEVEL ERRORS
  // ==========================================================

  const fileErrors = preview?.errors.filter(
    (error) => error.rowNumber === 0,
  ) ?? []

  // ==========================================================
  // RENDER
  // ==========================================================

  return (
    <>
      {/* ==================================================== */}
      {/* IMPORT BUTTON IN CHART OF ACCOUNTS */}
      {/* ==================================================== */}

      <button
        type="button"
        className="coa-refresh-button coa-import-trigger"
        onClick={handleOpen}
      >
        <Upload size={15} strokeWidth={1.8} />
        Import
      </button>

      {/* ==================================================== */}
      {/* IMPORT MODAL */}
      {/* ==================================================== */}

      <dialog
        ref={dialogRef}
        className="coa-import-dialog"
        aria-labelledby="coa-import-title"
        onCancel={(event) => {
          if (isBusy) {
            event.preventDefault()
          }
        }}
        onClose={() => {
          setSelectedFile(null)
          setPreview(null)
          setErrorMessage('')
        }}
      >

        {/* ================================================== */}
        {/* MODAL HEADER */}
        {/* ================================================== */}

        <div className="coa-import-header">

          <div className="coa-import-title-group">

            <FileSpreadsheet
              size={22}
              color="#198f79"
              strokeWidth={1.8}
            />

            <div>
              <h2 id="coa-import-title">
                Import Chart of Accounts
              </h2>

              <p>
                Upload, validate, and import financial accounts.
              </p>
            </div>
          </div>

          <button
            type="button"
            className="coa-import-close"
            aria-label="Close import dialog"
            disabled={isBusy}
            onClick={handleClose}
          >
            <X size={18} />
          </button>
        </div>

        {/* ================================================== */}
        {/* MODAL CONTENT */}
        {/* ================================================== */}

        <div className="coa-import-content">

          {/* ================================================ */}
          {/* FILE SELECTION */}
          {/* ================================================ */}

          <div className="coa-import-section">

            <h3>1. Select a file</h3>

            <p>
              Supported formats: CSV and XLSX.
              Maximum file size: 5 MB.
            </p>

            <label
              className="coa-import-file-picker"
              htmlFor="coa-import-file"
            >
              <Upload size={22} strokeWidth={1.7} />

              <span>
                {selectedFile
                  ? selectedFile.name
                  : 'Choose a CSV or XLSX file'}
              </span>

              <input
                id="coa-import-file"
                type="file"
                accept=".csv,.xlsx"
                disabled={isBusy}
                onChange={handleFileChange}
              />
            </label>

            <button
              type="button"
              className="coa-import-secondary-button"
              disabled={!selectedFile || isBusy}
              onClick={() => void handlePreview()}
            >
              {isPreviewing
                ? 'Validating...'
                : 'Preview and Validate'}
            </button>
          </div>

          {/* ================================================ */}
          {/* REQUEST ERRORS */}
          {/* ================================================ */}

          {errorMessage && (

            <div
              className="coa-import-error-notice"
              role="alert"
            >
              <AlertCircle size={17} />
              <span>{errorMessage}</span>
            </div>

          )}

          {/* ================================================ */}
          {/* IMPORT PREVIEW */}
          {/* ================================================ */}

          {preview && (

            <div className="coa-import-section">

              <h3>2. Review validation results</h3>

              {/* ============================================ */}
              {/* PREVIEW SUMMARY */}
              {/* ============================================ */}

              <div className="coa-import-summary">

                <div>
                  <span>Total rows</span>
                  <strong>{preview.totalRows}</strong>
                </div>

                <div>
                  <span>Valid rows</span>
                  <strong>{preview.validRows}</strong>
                </div>

                <div>
                  <span>Invalid rows</span>
                  <strong>{preview.invalidRows}</strong>
                </div>

              </div>

              {/* ============================================ */}
              {/* VALIDATION STATUS */}
              {/* ============================================ */}

              <div
                className={
                  preview.readyToImport
                    ? 'coa-import-valid-notice'
                    : 'coa-import-error-notice'
                }
                role="status"
              >

                {preview.readyToImport
                  ? <CheckCircle2 size={17} />
                  : <AlertCircle size={17} />
                }

                <span>
                  {preview.readyToImport
                    ? 'Validation passed. Ready to import.'
                    : 'Validation failed. Resolve errors before importing.'}
                </span>

              </div>

              {/* ============================================ */}
              {/* FILE-LEVEL VALIDATION ERRORS */}
              {/* ============================================ */}

              {fileErrors.length > 0 && (

                <ul className="coa-import-file-errors">

                  {fileErrors.map((error, index) => (

                    <li key={`${error.field}-${index}`}>
                      {error.message}
                    </li>

                  ))}

                </ul>
              )}

              {/* ============================================ */}
              {/* ACCOUNT PREVIEW TABLE */}
              {/* ============================================ */}

              {preview.rows.length > 0 && (

                <div className="coa-import-table-scroll">

                  <table className="coa-import-table">

                    <thead>
                      <tr>
                        <th>Row</th>
                        <th>Code</th>
                        <th>Account Name</th>
                        <th>Type</th>
                        <th>Reporting Group</th>
                        <th>Parent</th>
                        <th>Active</th>
                        <th>Postable</th>
                        <th>Validation</th>
                      </tr>
                    </thead>

                    <tbody>

                      {preview.rows.map((row) => {

                        // ====================================
                        // MATCH ERRORS TO ORIGINAL CSV ROW
                        // ====================================

                        const rowErrors =
                          preview.errors.filter(
                            (error) =>
                              error.rowNumber === row.rowNumber,
                          )

                        const isValidRow =
                          rowErrors.length === 0

                        return (

                          <tr key={row.rowNumber}>

                            <td>{row.rowNumber}</td>

                            <td className="coa-import-code">
                              {row.code}
                            </td>

                            <td>{row.name}</td>

                            <td>{row.accountType}</td>

                            <td>
                              {row.reportingGroup || '—'}
                            </td>

                            <td>
                              {row.parentAccountCode || '—'}
                            </td>

                            <td>{row.active || '—'}</td>

                            <td>{row.postable || '—'}</td>

                            <td>

                              <span
                                className={
                                  isValidRow
                                    ? 'coa-import-row-valid'
                                    : 'coa-import-row-invalid'
                                }
                              >
                                {isValidRow ? 'Valid' : 'Invalid'}
                              </span>

                              {rowErrors.length > 0 && (

                                <ul className="coa-import-row-errors">

                                  {rowErrors.map(
                                    (error, index) => (

                                      <li
                                        key={`${error.field}-${index}`}
                                      >
                                        {error.field}: {error.message}
                                      </li>

                                    ),
                                  )}

                                </ul>

                              )}

                            </td>
                          </tr>

                        )
                      })}

                    </tbody>
                  </table>

                </div>
              )}

            </div>
          )}
        </div>

        {/* ================================================== */}
        {/* MODAL FOOTER */}
        {/* ================================================== */}

        <div className="coa-import-footer">

          <button
            type="button"
            className="coa-import-secondary-button"
            disabled={isBusy}
            onClick={handleClose}
          >
            Cancel
          </button>

          <button
            type="button"
            className="coa-import-primary-button"
            disabled={!canImport}
            onClick={() => void handleImport()}
          >
            {isImporting
              ? 'Importing...'
              : `Import ${preview?.validRows ?? 0} Accounts`}
          </button>

        </div>

      </dialog>
    </>
  )
}

export default ImportAccountsDialog
