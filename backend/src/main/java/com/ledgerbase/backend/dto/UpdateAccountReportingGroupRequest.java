package com.ledgerbase.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// ============================================================
// LEDGERBASE — UPDATE ACCOUNT REPORTING GROUP REQUEST
// ============================================================

/**
 * Request DTO for changing an existing account's
 * financial reporting classification.
 *
 * Example:
 *
 * PATCH /api/v1/businesses/1/accounts/5/reporting-group
 *
 * Request body:
 *
 * {
 *   "reportingGroup": "CURRENT_ASSET"
 * }
 *
 * IMPORTANT:
 *
 * This operation changes reporting classification only.
 *
 * It must not modify:
 *
 * - Account ID or business ownership
 * - Account code or fundamental account type
 * - Parent account relationships
 * - Posting eligibility
 * - Existing ledger entries
 *
 * AccountService will verify compatibility with
 * the account's fundamental type.
 *
 * PostgreSQL V7 constraints provide an additional
 * layer of integrity protection.
 */
public record UpdateAccountReportingGroupRequest(

    // ========================================================
    // FINANCIAL REPORTING CLASSIFICATION
    // ========================================================

    /**
     * Reporting group to assign to the account.
     *
     * Must match one of the classifications defined
     * in V7__enhance_chart_of_accounts.sql.
     *
     * This initial endpoint requires a classification.
     * Clearing an existing classification will be
     * handled separately if needed.
     */
    @NotBlank(
        message = "Reporting group is required"
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
    String reportingGroup

) {}
