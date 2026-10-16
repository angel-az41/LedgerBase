package com.ledgerbase.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

// ============================================================
// LEDGERBASE — CREATE ACCOUNT REQUEST DTO
// ============================================================

/**
 * Request data for creating an account.
 *
 * The React frontend sends this DTO as JSON.
 *
 * Business ownership is determined by the businessId
 * in the URL, not by a client-supplied body field.
 *
 * V7 introduces three optional properties:
 *
 * 1. reportingGroup
 * 2. parentAccountId
 * 3. postable
 *
 * BACKWARD COMPATIBILITY:
 *
 * Existing API requests containing only code,
 * name, and accountType remain supported.
 *
 * The AccountService applies the default:
 *
 * postable = true
 *
 * PostgreSQL independently enforces reporting-group,
 * hierarchy, and posting-eligibility constraints.
 */
public record CreateAccountRequest(

    // ========================================================
    // BASIC ACCOUNT INFORMATION
    // ========================================================

    /**
     * Human-readable account code.
     *
     * Examples: "1000", "1010", "4000".
     */
    @NotBlank(message = "Account code is required")
    @Size(
        max = 20,
        message = "Account code cannot exceed 20 characters"
    )
    String code,

    /**
     * Descriptive Chart of Accounts name.
     *
     * Examples: Cash, Current Assets, Sales Revenue.
     */
    @NotBlank(message = "Account name is required")
    @Size(
        max = 150,
        message = "Account name cannot exceed 150 characters"
    )
    String name,

    /**
     * Fundamental accounting classification.
     *
     * This determines the account's normal balance
     * direction and its broad financial statement type.
     */
    @NotNull(message = "Account type is required")
    @Pattern(
        regexp = "ASSET|LIABILITY|EQUITY|REVENUE|EXPENSE",
        message = "Invalid account type"
    )
    String accountType,


    // ========================================================
    // V7 — FINANCIAL REPORTING GROUP
    // ========================================================

    /**
     * Optional financial statement classification.
     *
     * Examples:
     *
     * CURRENT_ASSET
     * NON_CURRENT_ASSET
     * OPERATING_REVENUE
     * COST_OF_GOODS_SOLD
     *
     * Null is allowed for unclassified accounts.
     *
     * The account service and PostgreSQL verify
     * that the group matches the account type.
     */
    @Size(
        max = 40,
        message = "Reporting group cannot exceed 40 characters"
    )
    @Pattern(
        regexp =
            "CURRENT_ASSET"
            + "|NON_CURRENT_ASSET"
            + "|CURRENT_LIABILITY"
            + "|NON_CURRENT_LIABILITY"
            + "|CONTRIBUTED_CAPITAL"
            + "|RETAINED_EARNINGS"
            + "|OTHER_EQUITY"
            + "|OPERATING_REVENUE"
            + "|OTHER_INCOME"
            + "|COST_OF_GOODS_SOLD"
            + "|OPERATING_EXPENSE"
            + "|OTHER_EXPENSE",
        message = "Invalid reporting group"
    )
    String reportingGroup,


    // ========================================================
    // V7 — OPTIONAL PARENT ACCOUNT
    // ========================================================

    /**
     * ID of the parent grouping account.
     *
     * Null means the account has no parent.
     *
     * The parent must:
     *
     * - Belong to the same business.
     * - Have the same fundamental account type.
     * - Be non-postable.
     *
     * PostgreSQL also prevents circular hierarchies.
     */
    @Positive(
        message = "Parent account ID must be positive"
    )
    Long parentAccountId,


    // ========================================================
    // V7 — POSTING ELIGIBILITY
    // ========================================================

    /**
     * Determines whether journal entries may use
     * this account.
     *
     * TRUE:
     *   Normal posting account.
     *
     * FALSE:
     *   Organizational grouping account.
     *
     * NULL:
     *   AccountService defaults to TRUE.
     *
     * Boolean is intentionally nullable rather
     * than using primitive boolean, so existing
     * requests may omit this property.
     */
    Boolean postable

) {

    // ========================================================
    // BACKWARD-COMPATIBLE JAVA CONSTRUCTOR
    // ========================================================

    /**
     * Preserve the original three-argument constructor.
     *
     * Existing Java code can continue using:
     *
     * new CreateAccountRequest(
     *     "1010",
     *     "Cash",
     *     "ASSET"
     * );
     *
     * The optional V7 fields default to null.
     */
    public CreateAccountRequest(
            String code,
            String name,
            String accountType) {

        this(
            code,
            name,
            accountType,
            null,
            null,
            null
        );
    }
}
