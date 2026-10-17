package com.ledgerbase.backend.dto;

import java.time.LocalDate;
import java.util.List;

// ============================================================
// LEDGERBASE — INCOME STATEMENT API RESPONSE
// ============================================================

/**
 * Represents a complete Income Statement API response.
 *
 * This DTO is returned by the Spring Boot controller
 * and consumed by the React frontend.
 *
 * It contains:
 *
 * - Business identification
 * - Reporting start and end dates
 * - Financial reporting sections
 * - Revenue and expense totals
 * - Net income
 * - Unclassified account warnings
 *
 * IMPORTANT:
 *
 * All financial calculations happen in the
 * IncomeStatementService using BigDecimal.
 *
 * Monetary values are converted to decimal strings
 * before being sent to React.
 *
 * This avoids JavaScript floating-point precision
 * problems in financial reporting.
 */
public record IncomeStatementResponse(

    // ========================================================
    // REPORT IDENTIFICATION
    // ========================================================

    Long businessId,

    // Inclusive beginning of the reporting period.
    LocalDate startDate,

    // Inclusive ending of the reporting period.
    LocalDate endDate,


    // ========================================================
    // CLASSIFICATION COMPLETENESS
    // ========================================================

    /**
     * True when every revenue or expense account
     * with POSTED activity in the selected period
     * has a valid reporting group.
     *
     * An account with zero net activity can still
     * require classification if its posted entry
     * count is greater than zero.
     *
     * False means the frontend must clearly display
     * an incomplete-classification warning.
     *
     * This does not indicate whether accounting
     * periods have been formally closed.
     */
    boolean classificationComplete,


    // ========================================================
    // FINANCIAL STATEMENT SECTIONS
    // ========================================================

    /**
     * Sections group classified accounts into
     * financial statement categories.
     *
     * Expected categories:
     *
     * OPERATING_REVENUE
     * OTHER_INCOME
     * COST_OF_GOODS_SOLD
     * OPERATING_EXPENSE
     * OTHER_EXPENSE
     */
    List<Section> sections,


    // ========================================================
    // UNCLASSIFIED ACCOUNT ACTIVITY
    // ========================================================

    /**
     * Revenue or expense accounts that have
     * POSTED activity but no reporting group.
     *
     * These accounts must never be silently
     * excluded from the financial statement.
     *
     * They will be displayed separately in React
     * until the user assigns classifications.
     */
    List<AccountLine> unclassifiedAccounts,


    // ========================================================
    // FINANCIAL TOTALS
    // ========================================================

    /**
     * Revenue across ALL revenue posting accounts,
     * including unclassified accounts.
     *
     * Decimal string example: "15000.00"
     */
    String totalRevenue,

    /**
     * Expenses across ALL expense posting accounts,
     * including unclassified accounts.
     *
     * Decimal string example: "7000.00"
     */
    String totalExpenses,

    /**
     * Net income:
     *
     * Total Revenue - Total Expenses
     *
     * Decimal string example: "8000.00"
     *
     * These totals include unclassified activity,
     * so the amounts are not silently understated.
     *
     * However, when classificationComplete is false,
     * the grouped presentation is incomplete.
     */
    String netIncome

) {

    // ========================================================
    // NESTED RECORD — FINANCIAL REPORTING SECTION
    // ========================================================

    /**
     * Represents one Income Statement category.
     *
     * Example:
     *
     * reportingGroup = "OPERATING_REVENUE"
     * label = "Operating Revenue"
     *
     * accounts = [...]
     *
     * subtotal = "15000.00"
     *
     * The subtotal includes only accounts
     * assigned to this reporting group.
     *
     * Unclassified accounts are reported separately.
     */
    public record Section(

        // Database reporting-group identifier.
        String reportingGroup,

        // Human-readable section heading.
        String label,

        // Accounts belonging to this reporting group.
        List<AccountLine> accounts,

        // Exact section subtotal as a decimal string.
        String subtotal

    ) {
    }


    // ========================================================
    // NESTED RECORD — INDIVIDUAL ACCOUNT LINE
    // ========================================================

    /**
     * Represents one revenue or expense account
     * within the Income Statement.
     *
     * Example:
     *
     * accountId = 12
     * code = "4000"
     * name = "Sales Revenue"
     * accountType = "REVENUE"
     * active = true
     * amount = "12000.00"
     * postedEntryCount = 5
     *
     * Amounts follow the normal balance direction:
     *
     * REVENUE:
     *   Credits minus Debits
     *
     * EXPENSE:
     *   Debits minus Credits
     *
     * Negative amounts are valid and preserved.
     */
    public record AccountLine(

        // PostgreSQL account identifier.
        Long accountId,

        // User-defined account code.
        String code,

        // Human-readable account name.
        String name,

        // REVENUE or EXPENSE.
        String accountType,

        // Whether the account is currently active.
        boolean active,

        // Exact net period activity as a decimal string.
        String amount,

        // Number of contributing POSTED ledger entries.
        long postedEntryCount

    ) {
    }
}
