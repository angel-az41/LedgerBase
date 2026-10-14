package com.ledgerbase.backend.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

// ============================================================
// LEDGERBASE — ACCOUNT BALANCE MODEL
// ============================================================

/**
 * Represents a general ledger account together with
 * its calculated posted debits, credits, and balance.
 *
 * Account balances are calculated from PostgreSQL
 * ledger entries rather than stored in accounts.
 *
 * IMPORTANT:
 *
 * - PostgreSQL uses NUMERIC for monetary amounts.
 * - Java uses BigDecimal for monetary amounts.
 * - JSON sends monetary amounts as decimal strings.
 *
 * Sending decimal strings prevents JavaScript from
 * losing monetary precision when parsing responses.
 */
public class AccountBalance {

    // ========================================================
    // ACCOUNT INFORMATION
    // ========================================================

    private Long id;
    private Long businessId;
    private String code;
    private String name;
    private String accountType;
    private boolean active;

    // ========================================================
    // CALCULATED MONETARY VALUES
    // ========================================================

    /**
     * Sum of debit amounts from POSTED journal entries.
     */
    private BigDecimal postedDebits;

    /**
     * Sum of credit amounts from POSTED journal entries.
     */
    private BigDecimal postedCredits;

    /**
     * Signed balance calculated according to account type.
     *
     * ASSET / EXPENSE:
     *     Debits - Credits
     *
     * LIABILITY / EQUITY / REVENUE:
     *     Credits - Debits
     */
    private BigDecimal balance;

    // ========================================================
    // DEFAULT CONSTRUCTOR
    // ========================================================

    /**
     * MyBatis uses the no-argument constructor
     * when creating account balance objects.
     */
    public AccountBalance() {
    }

    // ========================================================
    // ACCOUNT IDENTIFICATION
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

    // ========================================================
    // ACCOUNT DETAILS
    // ========================================================

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
    // POSTED DEBIT TOTAL
    // ========================================================

    /**
     * Serialize the BigDecimal as a JSON string.
     *
     * Java retains BigDecimal for calculations,
     * while React receives an exact decimal string.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public BigDecimal getPostedDebits() {
        return postedDebits;
    }

    public void setPostedDebits(BigDecimal postedDebits) {
        this.postedDebits = postedDebits;
    }

    // ========================================================
    // POSTED CREDIT TOTAL
    // ========================================================

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public BigDecimal getPostedCredits() {
        return postedCredits;
    }

    public void setPostedCredits(BigDecimal postedCredits) {
        this.postedCredits = postedCredits;
    }

    // ========================================================
    // CALCULATED ACCOUNT BALANCE
    // ========================================================

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }
}
