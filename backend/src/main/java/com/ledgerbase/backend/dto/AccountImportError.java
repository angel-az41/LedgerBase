package com.ledgerbase.backend.dto;

// ============================================================
// LEDGERBASE — ACCOUNT IMPORT VALIDATION ERROR DTO
// ============================================================

/**
 * Represents one validation error discovered while
 * processing an uploaded Chart of Accounts file.
 *
 * MILESTONE 20 — FINANCIAL DATA IMPORT
 *
 * Each error identifies:
 *
 * 1. The source spreadsheet row.
 * 2. The field containing the problem.
 * 3. A human-readable explanation.
 *
 * EXAMPLE:
 *
 * Row 4:
 *   Account Type = INVALID_TYPE
 *
 * Error:
 *
 * rowNumber = 4
 * field = "accountType"
 * message = "Invalid account type: INVALID_TYPE"
 *
 * IMPORTANT:
 *
 * One spreadsheet row may produce multiple errors.
 *
 * The frontend will display errors alongside
 * the original imported rows.
 *
 * This DTO does not change PostgreSQL data.
 */
public record AccountImportError(

    // ========================================================
    // SOURCE ROW NUMBER
    // ========================================================

    /**
     * Original spreadsheet row containing the error.
     *
     * Row 1 normally contains column headings.
     *
     * Row 2 is normally the first account.
     *
     * Row 0 is reserved for file-level errors,
     * such as an invalid spreadsheet header.
     */
    int rowNumber,


    // ========================================================
    // INVALID FIELD
    // ========================================================

    /**
     * Name of the field containing the error.
     *
     * Examples:
     *
     * code
     * name
     * accountType
     * reportingGroup
     * parentAccountCode
     * active
     * postable
     *
     * "file" may be used for file-level errors.
     */
    String field,


    // ========================================================
    // HUMAN-READABLE ERROR MESSAGE
    // ========================================================

    /**
     * Explanation displayed in the import preview.
     *
     * Examples:
     *
     * "Account code is required"
     *
     * "Account code 1010 already exists"
     *
     * "Reporting group is incompatible with account type"
     *
     * "Parent account 1000 was not found"
     *
     * "Postable must be TRUE or FALSE"
     */
    String message

) {
    // ========================================================
    // END — ACCOUNT IMPORT ERROR DTO
    // ========================================================
}
