package com.ledgerbase.backend.dto;

import java.util.List;

// ============================================================
// LEDGERBASE — ACCOUNT IMPORT PREVIEW RESPONSE DTO
// ============================================================

/**
 * Response returned after LedgerBase parses and
 * validates an uploaded Chart of Accounts file.
 *
 * MILESTONE 20 — FINANCIAL DATA IMPORT
 *
 * The frontend receives:
 *
 * 1. The business being imported into.
 * 2. The total number of account rows.
 * 3. The number of valid rows.
 * 4. The number of invalid rows.
 * 5. Whether the import can proceed.
 * 6. The parsed spreadsheet rows.
 * 7. Any validation errors.
 *
 * IMPORTANT:
 *
 * Preview is a read-only operation.
 *
 * No accounts are inserted or updated.
 *
 * Import confirmation must independently
 * revalidate the uploaded file before writing
 * anything to PostgreSQL.
 *
 * A successful preview is not permission to
 * bypass subsequent validation.
 */
public record AccountImportPreviewResponse(

    // ========================================================
    // TARGET BUSINESS
    // ========================================================

    /**
     * ID of the business selected in the request URL.
     *
     * The import service will validate that the
     * business exists.
     *
     * This value is informational for the frontend.
     *
     * It is not an authorization credential.
     */
    Long businessId,


    // ========================================================
    // TOTAL ACCOUNT ROWS
    // ========================================================

    /**
     * Number of account data rows detected.
     *
     * The column header is not counted.
     */
    int totalRows,


    // ========================================================
    // VALID ACCOUNT ROWS
    // ========================================================

    /**
     * Number of data rows with no validation errors.
     */
    int validRows,


    // ========================================================
    // INVALID ACCOUNT ROWS
    // ========================================================

    /**
     * Number of distinct data rows containing
     * one or more validation errors.
     *
     * Multiple errors on the same row count as
     * one invalid row.
     */
    int invalidRows,


    // ========================================================
    // IMPORT READINESS
    // ========================================================

    /**
     * TRUE only when:
     *
     * - At least one account row exists.
     * - Every row passes validation.
     * - There are no file-level errors.
     *
     * FALSE means the frontend should not offer
     * import confirmation.
     *
     * The import service calculates this value.
     */
    boolean readyToImport,


    // ========================================================
    // PARSED ACCOUNT ROWS
    // ========================================================

    /**
     * Original account information extracted
     * from CSV or Excel.
     *
     * Both valid and invalid rows are included,
     * allowing the user to inspect uploaded data.
     *
     * No database identifiers are generated
     * during preview.
     */
    List<AccountImportRow> rows,


    // ========================================================
    // VALIDATION ERRORS
    // ========================================================

    /**
     * Detailed row-level and file-level errors.
     *
     * An empty list means validation found no errors.
     *
     * However, an empty input file still must not
     * be considered importable.
     */
    List<AccountImportError> errors

) {

    // ========================================================
    // DEFENSIVE COPIES
    // ========================================================

    /**
     * Records are immutable, but lists passed into
     * records can otherwise remain mutable.
     *
     * List.copyOf creates immutable snapshots
     * of the parsed rows and validation errors.
     *
     * This prevents a caller from accidentally
     * modifying a preview response after creation.
     */
    public AccountImportPreviewResponse {

        rows = List.copyOf(rows);

        errors = List.copyOf(errors);
    }
}
