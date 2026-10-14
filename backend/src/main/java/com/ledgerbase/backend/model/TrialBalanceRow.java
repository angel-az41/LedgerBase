package com.ledgerbase.backend.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

// ============================================================
// LEDGERBASE — TRIAL BALANCE ROW MODEL
// ============================================================

/**
 * Represents one account row in a Trial Balance report.
 *
 * A Trial Balance summarizes the net debit or credit
 * balance of each general ledger account as of a
 * specified reporting date.
 *
 * The values are calculated from POSTED ledger entries.
 *
 * IMPORTANT ACCOUNTING RULES:
 *
 * 1. Only POSTED journal transactions are included.
 *
 * 2. Only transactions on or before the reporting
 *    date contribute to the balances.
 *
 * 3. Each account's net balance is displayed in
 *    either the Debit or Credit column.
 *
 * 4. Zero-balance and inactive accounts are included.
 *
 * 5. Monetary values are calculated in PostgreSQL
 *    using NUMERIC and stored in Java as BigDecimal.
 *
 * 6. Monetary values are serialized into JSON strings
 *    to preserve decimal precision in React.
 *
 * The reporting service will calculate the overall
 * debit and credit totals separately.
 */
public class TrialBalanceRow {

    // ========================================================
    // ACCOUNT IDENTIFICATION
    // ========================================================

    /**
     * Unique database identifier for the account.
     */
    private Long accountId;

    /**
     * User-defined general ledger account code.
     *
     * Examples:
     * "1010"
     * "1020"
     * "4000"
     */
    private String code;

    /**
     * Human-readable account name.
     */
    private String name;

    // ========================================================
    // ACCOUNT CLASSIFICATION
    // ========================================================

    /**
     * Fundamental accounting classification.
     *
     * Supported types:
     *
     * ASSET
     * LIABILITY
     * EQUITY
     * REVENUE
     * EXPENSE
     */
    private String accountType;

    /**
     * Whether the account is currently active.
     *
     * Inactive accounts remain in the report
     * because they may have historical balances.
     */
    private boolean active;

    // ========================================================
    // CALCULATED TRIAL BALANCE AMOUNTS
    // ========================================================

    /**
     * Positive net debit balance.
     *
     * Calculated as:
     *
     * MAX(total posted debits - total posted credits, 0)
     *
     * Uses BigDecimal to avoid floating-point errors.
     */
    private BigDecimal debitBalance;

    /**
     * Positive net credit balance.
     *
     * Calculated as:
     *
     * MAX(total posted credits - total posted debits, 0)
     *
     * Uses BigDecimal to avoid floating-point errors.
     */
    private BigDecimal creditBalance;

    // ========================================================
    // DEFAULT CONSTRUCTOR
    // ========================================================

    /**
     * MyBatis uses this constructor when creating
     * TrialBalanceRow objects from SQL query results.
     */
    public TrialBalanceRow() {
    }

    // ========================================================
    // ACCOUNT IDENTIFIER
    // ========================================================

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    // ========================================================
    // ACCOUNT CODE
    // ========================================================

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    // ========================================================
    // ACCOUNT NAME
    // ========================================================

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    // ========================================================
    // ACCOUNT TYPE
    // ========================================================

    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }

    // ========================================================
    // ACTIVE STATUS
    // ========================================================

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    // ========================================================
    // DEBIT BALANCE
    // ========================================================

    /**
     * Serialize this BigDecimal as a JSON string.
     *
     * Example:
     *
     * "debitBalance": "500.00"
     *
     * This prevents JavaScript from introducing
     * floating-point precision errors.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public BigDecimal getDebitBalance() {
        return debitBalance;
    }

    public void setDebitBalance(BigDecimal debitBalance) {
        this.debitBalance = debitBalance;
    }

    // ========================================================
    // CREDIT BALANCE
    // ========================================================

    /**
     * Serialize this BigDecimal as a JSON string.
     *
     * Example:
     *
     * "creditBalance": "500.00"
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public BigDecimal getCreditBalance() {
        return creditBalance;
    }

    public void setCreditBalance(BigDecimal creditBalance) {
        this.creditBalance = creditBalance;
    }
}
