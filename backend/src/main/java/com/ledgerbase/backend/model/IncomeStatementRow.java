package com.ledgerbase.backend.model;

import java.math.BigDecimal;

// ============================================================
// LEDGERBASE — INCOME STATEMENT ROW MODEL
// ============================================================

/**
 * Represents the financial activity of one general ledger
 * account during a selected reporting period.
 *
 * This model receives aggregated results from PostgreSQL
 * through MyBatis.
 *
 * IMPORTANT ACCOUNTING RULES:
 *
 * 1. Only REVENUE and EXPENSE accounts are included.
 *
 * 2. Only POSTED transactions affect financial amounts.
 *
 * 3. The reporting period includes both startDate and endDate.
 *
 * 4. Inactive accounts remain included.
 *
 * 5. All money uses BigDecimal for exact precision.
 *
 * 6. Grouping accounts are not treated as posting accounts.
 *
 * 7. Unclassified accounts retain their financial activity,
 *    allowing the service to flag them explicitly.
 */
public class IncomeStatementRow {

    // ========================================================
    // ACCOUNT IDENTIFICATION
    // ========================================================

    private Long accountId;

    private String code;

    private String name;


    // ========================================================
    // ACCOUNT CLASSIFICATION
    // ========================================================

    /**
     * Fundamental accounting classification.
     *
     * Expected values:
     *
     * REVENUE
     * EXPENSE
     */
    private String accountType;

    /**
     * Financial reporting classification.
     *
     * Revenue examples:
     *
     * OPERATING_REVENUE
     * OTHER_INCOME
     *
     * Expense examples:
     *
     * COST_OF_GOODS_SOLD
     * OPERATING_EXPENSE
     * OTHER_EXPENSE
     *
     * May be null for accounts not yet classified.
     */
    private String reportingGroup;


    // ========================================================
    // ACCOUNT STATUS
    // ========================================================

    /**
     * Inactive accounts must remain visible for
     * historical financial reporting.
     */
    private Boolean active;


    // ========================================================
    // CALCULATED FINANCIAL ACTIVITY
    // ========================================================

    /**
     * Net activity during the selected reporting period.
     *
     * For REVENUE:
     *
     * amount = posted credits - posted debits
     *
     * For EXPENSE:
     *
     * amount = posted debits - posted credits
     *
     * The amount may be negative.
     *
     * A zero amount does not necessarily mean that
     * there were no journal entries.
     */
    private BigDecimal amount;


    // ========================================================
    // POSTED ENTRY COUNT
    // ========================================================

    /**
     * Number of posted ledger entries contributing
     * to this account during the reporting period.
     *
     * This helps identify unclassified accounts
     * with actual financial activity.
     *
     * For example:
     *
     * A revenue account could have:
     *
     * Credits = 500
     * Debits  = 500
     * Amount  = 0
     *
     * It still had activity and may need classification.
     *
     * We therefore should not rely only on amount != 0
     * when checking for unclassified accounts.
     */
    private Long postedEntryCount;


    // ========================================================
    // NO-ARGUMENT CONSTRUCTOR
    // ========================================================

    /**
     * MyBatis creates an instance using this constructor,
     * then populates its fields through setter methods.
     */
    public IncomeStatementRow() {
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
    // ACCOUNT STATUS GETTERS AND SETTERS
    // ========================================================

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }


    // ========================================================
    // FINANCIAL AMOUNT GETTERS AND SETTERS
    // ========================================================

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
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
