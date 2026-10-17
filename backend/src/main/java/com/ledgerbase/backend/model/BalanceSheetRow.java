
package com.ledgerbase.backend.model;

import java.math.BigDecimal;

// ============================================================
// LEDGERBASE — BALANCE SHEET ROW MODEL
// ============================================================

/**
 * Represents one general ledger account's balance
 * as of a selected financial reporting date.
 *
 * This model receives aggregated PostgreSQL results
 * through the BalanceSheetMapper.
 *
 * ACCOUNTING RULES:
 *
 * 1. Include only POSTED transactions.
 *
 * 2. Include transactions dated on or before
 *    the selected reporting date.
 *
 * 3. Preserve historical balances belonging to
 *    inactive accounts.
 *
 * 4. Exclude non-postable grouping accounts.
 *
 * 5. Preserve negative account balances.
 *
 * 6. Use BigDecimal for exact monetary values.
 *
 * 7. Preserve unclassified accounts so their
 *    financial activity is not silently omitted.
 *
 * IMPORTANT:
 *
 * This model supports all five fundamental
 * account types:
 *
 * ASSET
 * LIABILITY
 * EQUITY
 * REVENUE
 * EXPENSE
 *
 * The Balance Sheet displays ASSET, LIABILITY,
 * and EQUITY accounts directly.
 *
 * REVENUE and EXPENSE accounts are used to
 * calculate unclosed earnings.
 */
public class BalanceSheetRow {

    // ========================================================
    // ACCOUNT IDENTIFICATION
    // ========================================================

    /**
     * Unique account identifier.
     */
    private Long accountId;

    /**
     * User-defined general ledger account code.
     *
     * Example: "1010"
     */
    private String code;

    /**
     * Account display name.
     *
     * Example: "Operating Checking"
     */
    private String name;


    // ========================================================
    // ACCOUNT CLASSIFICATION
    // ========================================================

    /**
     * Fundamental account classification.
     *
     * Expected values:
     *
     * ASSET
     * LIABILITY
     * EQUITY
     * REVENUE
     * EXPENSE
     */
    private String accountType;

    /**
     * Optional V7 reporting classification.
     *
     * Examples:
     *
     * CURRENT_ASSET
     * NON_CURRENT_ASSET
     * CURRENT_LIABILITY
     * NON_CURRENT_LIABILITY
     * CONTRIBUTED_CAPITAL
     * RETAINED_EARNINGS
     * OTHER_EQUITY
     *
     * Revenue and expense classifications may
     * also be returned because those accounts
     * contribute to unclosed earnings.
     *
     * NULL means the account is not classified.
     */
    private String reportingGroup;


    // ========================================================
    // ACCOUNT STATUS
    // ========================================================

    /**
     * Whether the account is currently active.
     *
     * Inactive accounts must remain available
     * for historical financial statements.
     */
    private Boolean active;


    // ========================================================
    // FINANCIAL BALANCE
    // ========================================================

    /**
     * Net account balance as of the reporting date.
     *
     * Normal balance direction:
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
     * REVENUE:
     *   Credits - Debits
     *
     * EXPENSE:
     *   Debits - Credits
     *
     * Negative balances are possible.
     *
     * PostgreSQL performs the initial account
     * aggregation using NUMERIC arithmetic.
     */
    private BigDecimal balance;


    // ========================================================
    // POSTED ENTRY COUNT
    // ========================================================

    /**
     * Number of POSTED ledger entries contributing
     * to this account through the reporting date.
     *
     * This distinguishes:
     *
     * - Accounts with no posted activity.
     * - Accounts with offsetting posted entries.
     *
     * For example:
     *
     * Debit:  500.00
     * Credit: 500.00
     *
     * Balance: 0.00
     *
     * Posted entry count: 2
     *
     * An unclassified account with such activity
     * still needs a reporting classification.
     */
    private Long postedEntryCount;


    // ========================================================
    // NO-ARGUMENT CONSTRUCTOR
    // ========================================================

    /**
     * MyBatis uses this constructor to instantiate
     * the model before populating its properties.
     */
    public BalanceSheetRow() {
    }


    // ========================================================
    // ACCOUNT IDENTIFICATION GETTERS AND SETTERS
    // ========================================================

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }


    // ========================================================
    // CLASSIFICATION GETTERS AND SETTERS
    // ========================================================

    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }

    public String getReportingGroup() {
        return reportingGroup;
    }

    public void setReportingGroup(String reportingGroup) {
        this.reportingGroup = reportingGroup;
    }


    // ========================================================
    // STATUS GETTERS AND SETTERS
    // ========================================================

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }


    // ========================================================
    // FINANCIAL BALANCE GETTERS AND SETTERS
    // ========================================================

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }


    // ========================================================
    // POSTED ENTRY COUNT GETTERS AND SETTERS
    // ========================================================

    public Long getPostedEntryCount() {
        return postedEntryCount;
    }

    public void setPostedEntryCount(Long postedEntryCount) {
        this.postedEntryCount = postedEntryCount;
    }
}
