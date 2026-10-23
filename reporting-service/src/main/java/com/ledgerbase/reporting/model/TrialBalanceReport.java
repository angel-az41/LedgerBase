package com.ledgerbase.reporting.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

// ============================================================
// LEDGERBASE — TRIAL BALANCE REPORT MODEL
// ============================================================

/**
 * Represents a complete Trial Balance financial report.
 *
 * A Trial Balance contains:
 *
 * - The business being reported
 * - The reporting cutoff date
 * - Individual account debit and credit balances
 * - Total debit balances
 * - Total credit balances
 * - The difference between debit and credit totals
 * - An indication of whether the totals agree
 *
 * ACCOUNTING RULES:
 *
 * 1. Only POSTED journal entries contribute to the report.
 *
 * 2. Transactions after the as-of date are excluded.
 *
 * 3. Individual accounts have either a net debit
 *    balance or a net credit balance.
 *
 * 4. Total debits should equal total credits
 *    when the underlying ledger is balanced.
 *
 * 5. All monetary calculations use BigDecimal.
 *
 * 6. JSON monetary amounts are serialized as strings
 *    to preserve precision in the React frontend.
 *
 * The reporting service will calculate the totals.
 *
 * This model stores the results and provides a
 * consistent response structure for the REST API.
 */
public class TrialBalanceReport {

    // ========================================================
    // REPORT IDENTIFICATION
    // ========================================================

    /**
     * Business whose general ledger is being reported.
     */
    private final Long businessId;

    /**
     * Last accounting date included in the report.
     *
     * Example:
     *
     * 2026-10-13
     *
     * Transactions after this date are excluded.
     */
    private final LocalDate asOfDate;

    // ========================================================
    // REPORT ACCOUNT ROWS
    // ========================================================

    /**
     * One TrialBalanceRow per general ledger account.
     *
     * Rows will be ordered by account code
     * through our MyBatis SQL query.
     *
     * Zero-balance and inactive accounts are included.
     */
    private final List<TrialBalanceRow> rows;

    // ========================================================
    // FINANCIAL REPORT TOTALS
    // ========================================================

    /**
     * Sum of the Debit Balance column.
     *
     * This is NOT the sum of every individual debit
     * posting in the ledger.
     *
     * It is the sum of net debit balances displayed
     * on the Trial Balance report.
     */
    private final BigDecimal totalDebits;

    /**
     * Sum of the Credit Balance column.
     *
     * This is the sum of net credit balances
     * displayed on the Trial Balance report.
     */
    private final BigDecimal totalCredits;

    /**
     * Signed difference between debit and credit totals.
     *
     * Formula:
     *
     * totalDebits - totalCredits
     *
     * A balanced report has a difference of zero.
     */
    private final BigDecimal difference;

    /**
     * Indicates whether total debits equal
     * total credits exactly.
     *
     * true:
     *     The debit and credit totals agree.
     *
     * false:
     *     The totals do not agree.
     *
     * IMPORTANT:
     *
     * A balanced Trial Balance does not guarantee
     * that every accounting entry is correct.
     * It only confirms that the totals agree.
     */
    private final boolean balanced;

    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    /**
     * Create a complete Trial Balance report.
     *
     * The reporting service supplies:
     *
     * - Business ID
     * - Reporting date
     * - Account rows
     * - Debit total
     * - Credit total
     *
     * This constructor automatically determines
     * the difference and balanced status.
     *
     * Calculating these here prevents callers from
     * accidentally supplying inconsistent values.
     *
     * @param businessId Business being reported.
     * @param asOfDate Reporting cutoff date.
     * @param rows Individual account balances.
     * @param totalDebits Total net debit balances.
     * @param totalCredits Total net credit balances.
     */
    public TrialBalanceReport(
            Long businessId,
            LocalDate asOfDate,
            List<TrialBalanceRow> rows,
            BigDecimal totalDebits,
            BigDecimal totalCredits) {

        // ====================================================
        // STORE REPORT IDENTIFICATION
        // ====================================================

        this.businessId = Objects.requireNonNull(
            businessId,
            "Business ID is required"
        );

        this.asOfDate = Objects.requireNonNull(
            asOfDate,
            "Reporting date is required"
        );

        // ====================================================
        // STORE REPORT ROWS
        // ====================================================

        /**
         * List.copyOf creates an unmodifiable copy
         * of the provided list.
         *
         * This prevents other code from adding or
         * removing report rows after construction.
         *
         * The TrialBalanceRow objects themselves
         * remain mutable.
         */
        this.rows = List.copyOf(
            Objects.requireNonNull(
                rows,
                "Trial Balance rows are required"
            )
        );

        // ====================================================
        // STORE FINANCIAL TOTALS
        // ====================================================

        this.totalDebits = Objects.requireNonNull(
            totalDebits,
            "Total debits are required"
        );

        this.totalCredits = Objects.requireNonNull(
            totalCredits,
            "Total credits are required"
        );

        // ====================================================
        // CALCULATE BALANCING DIFFERENCE
        // ====================================================

        /**
         * BigDecimal.subtract() performs exact
         * decimal arithmetic.
         *
         * We never convert monetary amounts
         * into Java double or float.
         */
        this.difference = totalDebits.subtract(
            totalCredits
        );

        // ====================================================
        // DETERMINE WHETHER REPORT BALANCES
        // ====================================================

        /**
         * BigDecimal.compareTo() compares the
         * numerical value, ignoring decimal scale.
         *
         * For example:
         *
         * 0
         * 0.0
         * 0.00
         *
         * All represent the same monetary amount.
         */
        this.balanced =
            this.difference.compareTo(BigDecimal.ZERO) == 0;
    }

    // ========================================================
    // BUSINESS IDENTIFIER
    // ========================================================

    public Long getBusinessId() {
        return businessId;
    }

    // ========================================================
    // REPORTING DATE
    // ========================================================

    /**
     * Jackson serializes LocalDate using
     * the ISO date format:
     *
     * "2026-10-13"
     */
    public LocalDate getAsOfDate() {
        return asOfDate;
    }

    // ========================================================
    // TRIAL BALANCE ROWS
    // ========================================================

    public List<TrialBalanceRow> getRows() {
        return rows;
    }

    // ========================================================
    // TOTAL DEBIT BALANCES
    // ========================================================

    /**
     * Return total debits as a JSON decimal string.
     *
     * Example:
     *
     * "totalDebits": "500.00"
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public BigDecimal getTotalDebits() {
        return totalDebits;
    }

    // ========================================================
    // TOTAL CREDIT BALANCES
    // ========================================================

    /**
     * Return total credits as a JSON decimal string.
     *
     * Example:
     *
     * "totalCredits": "500.00"
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public BigDecimal getTotalCredits() {
        return totalCredits;
    }

    // ========================================================
    // BALANCING DIFFERENCE
    // ========================================================

    /**
     * Return the signed difference as a JSON string.
     *
     * A value of zero means the totals agree.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public BigDecimal getDifference() {
        return difference;
    }

    // ========================================================
    // BALANCED STATUS
    // ========================================================

    /**
     * Jackson serializes this boolean as:
     *
     * "balanced": true
     *
     * or:
     *
     * "balanced": false
     */
    public boolean isBalanced() {
        return balanced;
    }
}
