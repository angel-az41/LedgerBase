package com.ledgerbase.backend.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;

// ============================================================
// LEDGERBASE — ACCOUNTING PERIOD MODEL
// ============================================================

/**
 * Represents one monthly accounting period.
 *
 * Database table:
 *
 * accounting_periods
 *
 * Each accounting period:
 *
 * 1. Belongs to one business.
 * 2. Belongs to one fiscal year.
 * 3. Has a period number between 1 and 12.
 * 4. Covers one calendar month.
 * 5. Has an OPEN or CLOSED status.
 *
 * Example:
 *
 * Business: LedgerBase Demo Business
 * Fiscal Year: FY2026
 * Period: 10
 * Start Date: October 1, 2026
 * End Date: October 31, 2026
 * Status: OPEN
 *
 * This model represents database data.
 *
 * Rules for closing periods and preventing transactions
 * from being posted into closed periods will be handled
 * by the service and database layers.
 */
public class AccountingPeriod {

    // ========================================================
    // PERIOD STATUS
    // ========================================================

    /**
     * The two supported accounting-period statuses.
     *
     * OPEN:
     * New journal transactions may be posted into
     * this period, provided all other validations pass.
     *
     * CLOSED:
     * New postings must be rejected.
     *
     * MyBatis can map PostgreSQL values such as 'OPEN'
     * and 'CLOSED' to this enum using their names.
     */
    public enum Status {
        OPEN,
        CLOSED
    }


    // ========================================================
    // DATABASE FIELDS
    // ========================================================

    /**
     * Unique primary key of the accounting period.
     */
    private Long id;

    /**
     * Business that owns this accounting period.
     *
     * Important for multi-business data isolation.
     */
    private Long businessId;

    /**
     * ID of the fiscal year containing this period.
     */
    private Long fiscalYearId;

    /**
     * Position within the fiscal year.
     *
     * Valid values: 1 through 12.
     *
     * Period 1 is the first month of the fiscal year,
     * which is not necessarily January.
     */
    private Integer periodNumber;

    /**
     * First date included in this accounting period.
     */
    private LocalDate startDate;

    /**
     * Last date included in this accounting period.
     */
    private LocalDate endDate;

    /**
     * Current period status: OPEN or CLOSED.
     */
    private Status status;

    /**
     * Timestamp when the period was closed.
     *
     * NULL when the period has not been closed
     * or has been reopened.
     */
    private OffsetDateTime closedAt;

    /**
     * Timestamp when this accounting period was created.
     */
    private OffsetDateTime createdAt;


    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    /**
     * MyBatis uses the no-argument constructor
     * to create an object before populating its fields.
     */
    public AccountingPeriod() {
    }


    // ========================================================
    // GETTERS AND SETTERS
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

    public Long getFiscalYearId() {
        return fiscalYearId;
    }

    public void setFiscalYearId(Long fiscalYearId) {
        this.fiscalYearId = fiscalYearId;
    }

    public Integer getPeriodNumber() {
        return periodNumber;
    }

    public void setPeriodNumber(Integer periodNumber) {
        this.periodNumber = periodNumber;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public OffsetDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(OffsetDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
