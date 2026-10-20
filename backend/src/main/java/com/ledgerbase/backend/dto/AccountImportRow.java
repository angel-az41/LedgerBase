package com.ledgerbase.backend.dto;

// ============================================================
// LEDGERBASE — ACCOUNT IMPORT ROW DTO
// ============================================================

/**
 * Represents one row from an uploaded Chart of Accounts
 * CSV or Excel spreadsheet.
 *
 * MILESTONE 20 — FINANCIAL DATA IMPORT
 *
 * Each row contains the same seven columns used
 * by the existing Chart of Accounts exporter.
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. All imported values are initially Strings.
 *
 *    This preserves the original spreadsheet contents
 *    for validation and error reporting.
 *
 * 2. Account codes remain text.
 *
 *    Example:
 *
 *    "0010" must not become "10".
 *
 * 3. Parent relationships use account codes.
 *
 *    We do not expect users to know PostgreSQL IDs.
 *
 * 4. Row numbers correspond to spreadsheet rows.
 *
 *    Row 1 contains column headings.
 *    The first account appears on row 2.
 *
 * 5. Business ownership is not supplied here.
 *
 *    The business ID comes from the API URL.
 *
 * 6. No database operation occurs in this DTO.
 *
 *    This record only holds imported data.
 */
public record AccountImportRow(

    // ========================================================
    // SOURCE ROW NUMBER
    // ========================================================

    /**
     * Original row number in the uploaded file.
     *
     * Example:
     *
     * Row 5 contains an invalid account type.
     *
     * This number allows the frontend to display
     * a precise validation message.
     */
    int rowNumber,


    // ========================================================
    // ACCOUNT CODE
    // ========================================================

    /**
     * Human-readable account identifier.
     *
     * Examples:
     *
     * 1000
     * 1010
     * 0010
     *
     * Must remain a String to preserve leading zeros.
     */
    String code,


    // ========================================================
    // ACCOUNT NAME
    // ========================================================

    /**
     * Descriptive account name.
     *
     * Examples:
     *
     * Cash
     * Accounts Receivable
     * Operating Revenue
     */
    String name,


    // ========================================================
    // FUNDAMENTAL ACCOUNT TYPE
    // ========================================================

    /**
     * Expected accounting classifications:
     *
     * ASSET
     * LIABILITY
     * EQUITY
     * REVENUE
     * EXPENSE
     *
     * The validation service will reject
     * unsupported values.
     */
    String accountType,


    // ========================================================
    // FINANCIAL REPORTING GROUP
    // ========================================================

    /**
     * Optional financial statement classification.
     *
     * Examples:
     *
     * CURRENT_ASSET
     * NON_CURRENT_ASSET
     * OPERATING_REVENUE
     * OPERATING_EXPENSE
     *
     * Must be compatible with accountType.
     *
     * Blank values are allowed initially.
     */
    String reportingGroup,


    // ========================================================
    // PARENT ACCOUNT CODE
    // ========================================================

    /**
     * Optional code of the parent grouping account.
     *
     * Example:
     *
     * 1000 — Current Assets
     * 1010 — Cash, parent code 1000
     *
     * During confirmation, the import service
     * will resolve this code to a database ID.
     *
     * Parent accounts must belong to the
     * same business and be non-postable.
     */
    String parentAccountCode,


    // ========================================================
    // ACTIVE STATUS
    // ========================================================

    /**
     * Original spreadsheet value for account status.
     *
     * Expected values:
     *
     * TRUE
     * FALSE
     *
     * We retain the raw String so invalid values
     * can produce row-specific validation errors.
     */
    String active,


    // ========================================================
    // POSTING ELIGIBILITY
    // ========================================================

    /**
     * Original spreadsheet value for posting eligibility.
     *
     * TRUE:
     *   Normal posting account.
     *
     * FALSE:
     *   Non-postable grouping account.
     *
     * The import validator will convert valid
     * values into Java Boolean values.
     */
    String postable

) {
    // ========================================================
    // END — ACCOUNT IMPORT ROW DTO
    // ========================================================
}
