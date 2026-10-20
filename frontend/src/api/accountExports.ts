import { downloadFile } from './client'

// ============================================================
// LEDGERBASE — ACCOUNT EXPORT API
// ============================================================

/**
 * Export Chart of Accounts data from Spring Boot.
 *
 * Supported formats:
 *
 * CSV:
 *   Comma-separated values.
 *
 * XLSX:
 *   Microsoft Excel spreadsheet.
 *
 * The backend retrieves authoritative account
 * information from PostgreSQL.
 *
 * React does not generate or recalculate the
 * exported accounting records.
 */

// ============================================================
// EXPORT FORMAT
// ============================================================

export type AccountExportFormat = 'csv' | 'xlsx'

// ============================================================
// DOWNLOAD ACCOUNT EXPORT
// ============================================================

/**
 * Download the Chart of Accounts for one business.
 *
 * Endpoint:
 *
 * GET /api/v1/businesses/{businessId}/accounts/export
 *
 * Query parameter:
 *
 * format=csv
 * format=xlsx
 *
 * The frontend supplies a fallback filename.
 *
 * If Spring Boot provides Content-Disposition,
 * the shared download helper uses that filename.
 */
export async function exportAccounts(
  businessId: number,
  format: AccountExportFormat,
): Promise<void> {

  // ==========================================================
  // BUILD EXPORT ENDPOINT
  // ==========================================================

  const path =
    `/api/v1/businesses/${businessId}/accounts/export` +
    `?format=${format}`

  // ==========================================================
  // BUILD FALLBACK FILENAME
  // ==========================================================

  const filename =
    `ledgerbase-accounts-business-${businessId}.${format}`

  // ==========================================================
  // DOWNLOAD FILE
  // ==========================================================

  await downloadFile(path, filename)
}
