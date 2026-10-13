package com.ledgerbase.backend.model;

import java.math.BigDecimal;

// ============================================================
// LEDGERBASE — ACCOUNT BALANCE MODEL
// ============================================================

/**
 * Represents an account together with its calculated
 * posted debit totals, credit totals, and balance.
 *
 * IMPORTANT:
 *
 * Account balances are NOT stored in the accounts table.
 *
 * They are calculated dynamically from ledger_entries
 * joined with transactions where status = 'POSTED'.
 *
 * This model is populated by MyBatis and will
 * eventually be returned through the REST API.
 *
 * Monetary values use BigDecimal to preserve
 * exact decimal precision.
 */
public class AccountBalance {

    // ========================================================
    // ACCOUNT IDENTIFICATION
    // ========================================================

    /**
     * Unique database ID of the account.
     */
    private Long id;

    /**
     * Business that owns the account.
     *
     * This preserves LedgerBase's
     * multi-business data separation.
     */
    private Long businessId;

    /**
     * Account code in the Chart of Accounts.
     *
     * Example: "1010"
     */
    private String code;

    /**
     * Human-readable account name.
     *
     * Example: "Operating Checking Account"
     */
    private String name;

    // ========================================================
    // ACCOUNT CLASSIFICATION
    // ========================================================

    /**
     * Accounting classification:
     *
     * ASSET
     * LIABILITY
     * EQUITY
     * REVENUE
     * EXPENSE
     *
     * Account type determines the account's
     * normal debit or credit balance direction.
     */
    private String accountType;

    /**
     * Whether the account is currently active.
     *
     * Inactive accounts may still have historical
     * posted entries and nonzero balances.
     */
    private boolean active;

    // ========================================================
    // POSTED DEBIT AND CREDIT TOTALS
    // ========================================================

    /**
     * Sum of debit amounts from POSTED transactions.
     *
     * Example: 500.00
     */
    private BigDecimal postedDebits;

    /**
     * Sum of credit amounts from POSTED transactions.
     *
     * Example: 0.00
     */
    private BigDecimal postedCredits;

    // ========================================================
    // CALCULATED ACCOUNT BALANCE
    // ========================================================

    /**
     * Signed account balance, based on account type.
     *
     * For ASSET and EXPENSE accounts:
     *
     *     balance = postedDebits - postedCredits
     *
     * For LIABILITY, EQUITY, and REVENUE accounts:
     *
     *     balance = postedCredits - postedDebits
     *
     * Negative balances are allowed and preserved.
     *
     * All calculations are performed in PostgreSQL,
     * not by this model.
     */
    private BigDecimal balance;

    // ========================================================
    // DEFAULT CONSTRUCTOR
    // ========================================================

    /**
     * MyBatis requires a way to instantiate the model
     * before populating its properties.
     */
    public AccountBalance() {
    }

    // ========================================================
    // ACCOUNT IDENTIFICATION GETTERS AND SETTERS
    // ========================================================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBusinessId() {
        return businessId;
    }

    public void setBusinessId(Long businessId) {
        this.businessId = businessId;
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
    // ACCOUNT CLASSIFICATION GETTERS AND SETTERS
    // ========================================================

    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    // ========================================================
    // POSTED DEBIT TOTAL GETTERS AND SETTERS
    // ========================================================

    public BigDecimal getPostedDebits() {
        return postedDebits;
    }

    public void setPostedDebits(BigDecimal postedDebits) {
        this.postedDebits = postedDebits;
    }

    // ========================================================
    // POSTED CREDIT TOTAL GETTERS AND SETTERS
    // ========================================================

    public BigDecimal getPostedCredits() {
        return postedCredits;
    }

    public void setPostedCredits(BigDecimal postedCredits) {
        this.postedCredits = postedCredits;
    }

    // ========================================================
    // ACCOUNT BALANCE GETTERS AND SETTERS
    // ========================================================

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }
}
