package com.ledgerbase.backend.dto;

import java.time.LocalDate;
import java.util.List;

// ============================================================
// LEDGERBASE — BALANCE SHEET API RESPONSE
// ============================================================

/**
 * Represents the complete Balance Sheet returned
 * by the Spring Boot REST API.
 *
 * This DTO is consumed by the React frontend.
 *
 * The report contains:
 *
 * 1. Business identification and reporting date.
 * 2. Seven Balance Sheet reporting sections.
 * 3. Account-level financial balances.
 * 4. Unclassified account warnings.
 * 5. Total Assets.
 * 6. Total Liabilities.
 * 7. Posted Equity.
 * 8. Unclosed Earnings.
 * 9. Total Equity.
 * 10. Total Liabilities and Equity.
 * 11. Accounting equation reconciliation.
 *
 * IMPORTANT:
 *
 * All monetary calculations are performed by
 * BalanceSheetService using BigDecimal.
 *
 * Monetary values are returned as decimal strings.
 *
 * React must not calculate financial totals using
 * JavaScript floating-point arithmetic.
 *
 * The Balance Sheet uses cumulative posted balances
 * through its inclusive as-of reporting date.
 */
public record BalanceSheetResponse(

    // ========================================================
    // REPORT IDENTIFICATION
    // ========================================================

    /**
     * Business whose financial records are reported.
     */
    Long businessId,

    /**
     * Inclusive reporting cutoff date.
     *
     * Example:
     *
     * 2026-10-17
     *
     * Only POSTED transactions on or before this
     * date contribute to the financial report.
     */
    LocalDate asOfDate,


    // ========================================================
    // CLASSIFICATION COMPLETENESS
    // ========================================================

    /**
     * True when every ASSET, LIABILITY, and EQUITY
     * posting account with posted activity through
     * the reporting date has a reporting group.
     *
     * Important:
     *
     * An account can have:
     *
     * balance = "0.00"
     * postedEntryCount = 2
     *
     * It still requires classification.
     *
     * Revenue and Expense accounts are used to
     * calculate unclosed earnings. Their individual
     * reporting groups are not required to calculate
     * this Balance Sheet's earnings figure.
     *
     * This flag does not indicate whether accounting
     * periods have been formally closed.
     */
    boolean classificationComplete,


    // ========================================================
    // FINANCIAL REPORTING SECTIONS
    // ========================================================

    /**
     * Contains the seven V7 Balance Sheet groups:
     *
     * CURRENT_ASSET
     * NON_CURRENT_ASSET
     *
     * CURRENT_LIABILITY
     * NON_CURRENT_LIABILITY
     *
     * CONTRIBUTED_CAPITAL
     * RETAINED_EARNINGS
     * OTHER_EQUITY
     *
     * Every section contains individual accounts
     * and a subtotal calculated using BigDecimal.
     */
    List<Section> sections,


    // ========================================================
    // UNCLASSIFIED BALANCE SHEET ACCOUNTS
    // ========================================================

    /**
     * Asset, Liability, and Equity accounts with
     * posted activity but no reporting group.
     *
     * Their amounts must never silently disappear
     * from the Balance Sheet totals.
     *
     * The frontend must show a warning when
     * this collection is not empty.
     */
    List<AccountLine> unclassifiedAccounts,


    // ========================================================
    // ASSET AND LIABILITY TOTALS
    // ========================================================

    /**
     * Sum of ALL asset posting account balances,
     * including unclassified asset accounts.
     *
     * Normal direction:
     *
     * Debits - Credits
     */
    String totalAssets,

    /**
     * Sum of ALL liability posting account balances,
     * including unclassified liability accounts.
     *
     * Normal direction:
     *
     * Credits - Debits
     */
    String totalLiabilities,


    // ========================================================
    // EQUITY TOTALS
    // ========================================================

    /**
     * Sum of balances actually posted to EQUITY
     * accounts in the general ledger.
     *
     * Includes:
     *
     * - Contributed Capital
     * - Retained Earnings
     * - Other Equity
     * - Unclassified Equity
     *
     * This does not yet include unclosed earnings.
     */
    String postedEquity,


    // ========================================================
    // UNCLOSED EARNINGS
    // ========================================================

    /**
     * Cumulative net balances of Revenue and
     * Expense accounts through the as-of date.
     *
     * Calculation:
     *
     * Unclosed Earnings =
     *     Cumulative Revenue - Cumulative Expenses
     *
     * These are the earnings still represented
     * in the Revenue and Expense ledger accounts,
     * rather than transferred into Equity.
     *
     * IMPORTANT:
     *
     * This is not necessarily current fiscal-year
     * net income.
     *
     * The current MVP does not automatically create
     * year-end closing journal entries.
     *
     * We therefore calculate the remaining cumulative
     * Revenue and Expense balances rather than
     * inventing retained earnings transfers.
     *
     * A future year-end closing workflow will
     * transfer amounts using actual journal entries.
     */
    String unclosedEarnings,


    // ========================================================
    // TOTAL EQUITY
    // ========================================================

    /**
     * Total Equity =
     *
     * Posted Equity + Unclosed Earnings
     *
     * This allows the Balance Sheet to account
     * for revenue and expenses not yet transferred
     * through formal year-end closing entries.
     */
    String totalEquity,


    // ========================================================
    // TOTAL LIABILITIES AND EQUITY
    // ========================================================

    /**
     * Total Liabilities and Equity =
     *
     * Total Liabilities + Total Equity
     */
    String totalLiabilitiesAndEquity,


    // ========================================================
    // ACCOUNTING EQUATION RECONCILIATION
    // ========================================================

    /**
     * Difference =
     *
     * Total Assets - Total LiabilitiesAndEquity
     *
     * An accounting equation that balances
     * has a difference of "0.00".
     *
     * A nonzero difference must be displayed
     * rather than hidden or automatically corrected.
     */
    String difference,

    /**
     * Whether the accounting equation balances.
     *
     * Calculated by the Java service using
     * BigDecimal.compareTo().
     *
     * Balanced:
     *
     * Assets = Liabilities + Equity
     */
    boolean balanced

) {

    // ========================================================
    // NESTED RECORD — REPORTING SECTION
    // ========================================================

    /**
     * Represents one Balance Sheet category.
     *
     * Example:
     *
     * reportingGroup = "CURRENT_ASSET"
     * label = "Current Assets"
     * accountType = "ASSET"
     *
     * subtotal = "12500.00"
     *
     * Section subtotals include only accounts
     * explicitly assigned to the reporting group.
     *
     * Unclassified accounts are displayed separately
     * but remain included in overall account-type totals.
     */
    public record Section(

        // V7 reporting-group identifier.
        String reportingGroup,

        // Human-readable display heading.
        String label,

        // ASSET, LIABILITY, or EQUITY.
        String accountType,

        // Accounts assigned to the reporting group.
        List<AccountLine> accounts,

        // Exact section subtotal as a decimal string.
        String subtotal

    ) {
    }


    // ========================================================
    // NESTED RECORD — ACCOUNT LINE
    // ========================================================

    /**
     * Represents an individual general ledger account
     * inside the Balance Sheet.
     *
     * Example:
     *
     * accountId = 6
     * code = "1010"
     * name = "Operating Checking"
     * accountType = "ASSET"
     * active = true
     * balance = "10000.00"
     * postedEntryCount = 5
     *
     * Balances follow normal accounting directions:
     *
     * ASSET:
     *   Debits - Credits
     *
     * LIABILITY:
     *   Credits - Debits
     *
     * EQUITY:
     *   Credits - Debits
     *
     * Negative balances are preserved.
     */
    public record AccountLine(

        // Unique account identifier.
        Long accountId,

        // User-defined account code.
        String code,

        // Human-readable account name.
        String name,

        // Fundamental accounting classification.
        String accountType,

        // Whether the account is currently active.
        boolean active,

        // Exact account balance as a decimal string.
        String balance,

        // Number of contributing POSTED ledger entries.
        long postedEntryCount

    ) {
    }
}
