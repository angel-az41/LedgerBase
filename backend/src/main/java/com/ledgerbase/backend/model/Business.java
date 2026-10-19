package com.ledgerbase.backend.model;

import java.time.OffsetDateTime;

// ============================================================
// LEDGERBASE — BUSINESS MODEL
// ============================================================

/**
 * Represents one business registered in LedgerBase.
 *
 * DATABASE TABLE:
 *
 * businesses
 *
 * Each business owns its own:
 *
 * - Chart of Accounts
 * - Financial transactions
 * - General ledger entries
 * - Fiscal years
 * - Accounting periods
 * - Financial reports
 *
 * BUSINESS ISOLATION:
 *
 * Most accounting tables contain a business_id column.
 *
 * This allows LedgerBase to support multiple businesses
 * without mixing their financial records.
 *
 * IMPORTANT:
 *
 * This model represents database data.
 * Authentication and authorization will be handled
 * separately in future milestones.
 */
public class Business {

    // ========================================================
    // BUSINESS IDENTIFICATION
    // ========================================================

    /**
     * Unique database-generated business ID.
     *
     * PostgreSQL uses:
     *
     * GENERATED ALWAYS AS IDENTITY
     */
    private Long id;

    /**
     * ID of the user who owns this business.
     *
     * References:
     *
     * users.id
     *
     * Currently, our local development user has ID 1.
     *
     * Later, this value must come from the
     * authenticated user's identity, not from
     * an untrusted client request.
     */
    private Long ownerId;

    /**
     * Human-readable business name.
     *
     * Examples:
     *
     * LedgerBase Demo Business
     * ABC Consulting LLC
     *
     * PostgreSQL maximum length: 200 characters.
     */
    private String name;

    // ========================================================
    // ACCOUNTING CONFIGURATION
    // ========================================================

    /**
     * Three-letter currency code.
     *
     * Examples:
     *
     * USD
     * EUR
     * GBP
     *
     * The current accounting system uses USD
     * for its demonstration data.
     */
    private String currency;

    /**
     * Calendar month in which the fiscal year begins.
     *
     * Valid values:
     *
     * 1  = January
     * 4  = April
     * 7  = July
     * 10 = October
     *
     * PostgreSQL enforces values from 1 through 12.
     *
     * This configuration is used by FiscalYearService
     * when generating fiscal years and periods.
     */
    private Integer fiscalYearStartMonth;

    // ========================================================
    // AUDIT INFORMATION
    // ========================================================

    /**
     * Timestamp when the business was created.
     *
     * PostgreSQL column:
     *
     * created_at TIMESTAMPTZ
     *
     * The database automatically generates this value.
     */
    private OffsetDateTime createdAt;

    // ========================================================
    // GETTERS AND SETTERS
    // ========================================================

    /**
     * MyBatis can populate this Java object using
     * its default constructor and setter methods.
     *
     * No financial calculations belong in this model.
     */

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Integer getFiscalYearStartMonth() {
        return fiscalYearStartMonth;
    }

    public void setFiscalYearStartMonth(Integer fiscalYearStartMonth) {
        this.fiscalYearStartMonth = fiscalYearStartMonth;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
