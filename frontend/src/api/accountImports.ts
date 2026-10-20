import { postMultipart } from './client'

// ============================================================
// LEDGERBASE — ACCOUNT IMPORT API
// ============================================================

/**
 * Typed API integration for Chart of Accounts imports.
 *
 * This module supports:
 *
 * 1. CSV/XLSX import preview.
 * 2. Row-level validation errors.
 * 3. Transactional account import execution.
 *
 * The React interface is responsible for displaying
 * results and obtaining user confirmation.
 *
 * Spring Boot remains responsible for authoritative
 * validation and PostgreSQL transactions.
 */

// ============================================================
// IMPORT ROW
// ============================================================

/**
 * An individual account parsed from an uploaded file.
 *
 * Values are preserved as strings because the parser
 * retains the original spreadsheet field contents.
 *
 * rowNumber:
 *   Original spreadsheet row number.
 *
 * parentAccountCode:
 *   Account code identifying the parent account.
 *
 * active / postable:
 *   Original TRUE/FALSE values from the spreadsheet.
 */
export interface AccountImportRow {

  rowNumber: number

  code: string

  name: string

  accountType: string

  reportingGroup: string

  parentAccountCode: string

  active: string

  postable: string
}

// ============================================================
// IMPORT VALIDATION ERROR
// ============================================================

/**
 * An accounting validation error.
 *
 * rowNumber = 0 indicates a file-level error.
 *
 * Examples:
 *
 * - Unsupported file extension
 * - Duplicate account code
 * - Invalid reporting group
 * - Missing parent account
 * - Circular account hierarchy
 */
export interface AccountImportError {

  rowNumber: number

  field: string

  message: string
}

// ============================================================
// IMPORT PREVIEW RESPONSE
// ============================================================

/**
 * Matches AccountImportPreviewResponse.java.
 *
 * Preview operations never modify PostgreSQL.
 *
 * readyToImport becomes true only when the backend
 * accepts all imported rows.
 */
export interface AccountImportPreviewResponse {

  businessId: number

  totalRows: number

  validRows: number

  invalidRows: number

  readyToImport: boolean

  rows: AccountImportRow[]

  errors: AccountImportError[]
}

// ============================================================
// IMPORT EXECUTION RESPONSE
// ============================================================

/**
 * Matches AccountImportResultResponse.java.
 *
 * This response is returned after a successful
 * transactional import.
 */
export interface AccountImportResultResponse {

  businessId: number

  importedRows: number

  success: boolean
}

// ============================================================
// CREATE MULTIPART FORM DATA
// ============================================================

/**
 * Construct the upload request body.
 *
 * The field name must be "file" because
 * AccountImportController uses:
 *
 * @RequestParam("file")
 *
 * FormData preserves the actual uploaded file
 * without converting it into JSON.
 */
function createAccountImportFormData(
  file: File,
): FormData {

  const formData = new FormData()

  formData.append('file', file)

  return formData
}

// ============================================================
// PREVIEW ACCOUNT IMPORT
// ============================================================

/**
 * Upload a file for read-only validation.
 *
 * Endpoint:
 *
 * POST
 * /api/v1/businesses/{businessId}/accounts/import/preview
 *
 * Returns parsed rows, validation errors,
 * and the readyToImport flag.
 *
 * The caller must inspect readyToImport rather
 * than assuming every HTTP 200 response is valid.
 */
export async function previewAccountImport(
  businessId: number,
  file: File,
): Promise<AccountImportPreviewResponse> {

  return postMultipart<AccountImportPreviewResponse>(

    `/api/v1/businesses/${businessId}/accounts/import/preview`,

    createAccountImportFormData(file),

  )
}

// ============================================================
// EXECUTE ACCOUNT IMPORT
// ============================================================

/**
 * Confirm and execute an account import.
 *
 * Endpoint:
 *
 * POST
 * /api/v1/businesses/{businessId}/accounts/import
 *
 * The backend performs:
 *
 * - File parsing
 * - Revalidation
 * - Duplicate prevention
 * - Parent-child relationship resolution
 * - Atomic PostgreSQL insertion
 *
 * This function must only be called after
 * explicit confirmation from the user.
 */
export async function executeAccountImport(
  businessId: number,
  file: File,
): Promise<AccountImportResultResponse> {

  return postMultipart<AccountImportResultResponse>(

    `/api/v1/businesses/${businessId}/accounts/import`,

    createAccountImportFormData(file),

  )
}
