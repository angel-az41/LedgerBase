package com.ledgerbase.backend.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;

// ============================================================
// LEDGERBASE — FISCAL YEAR MODEL
// ============================================================

/**
 * Represents one fiscal year belonging to a business.
 *
 * Database table:
 *
 * fiscal_years
 *
 * A fiscal year is a 12-month accounting cycle.
 *
 * Examples:
 *
 * Calendar fiscal year:
 * FY2026: January 1, 2026 - December 31, 2026
 *
 * Non-calendar fiscal year:
 * FY2027: April 1, 2026 - March 31, 2027
 *
 * Each fiscal year will have 12 accounting periods.
 *
 * This class represents stored data.
 *
 * Fiscal-year creation, validation, and accounting-period
 * generation will be handled by the service layer.
 */
public class FiscalYear {

    // ========================================================
    // DATABASE FIELDS
    // ========================================================

    /**
     * Unique primary key for this fiscal year.
     */
    private Long id;

    /**
     * Business that owns this fiscal year.
     *
     * Every fiscal year belongs to exactly one business.
     */
    private Long businessId;

    /**
     * Human-readable fiscal-year label.
     *
     * Examples:
     * FY2026
     * FY2027
     */
    private String label;

    /**
     * First date included in the fiscal year.
     */
    private LocalDate startDate;

    /**
     * Last date included in the fiscal year.
     */
    private LocalDate endDate;

    /**
     * Timestamp when this fiscal year was created.
     *
     * PostgreSQL TIMESTAMPTZ maps to OffsetDateTime.
     */
    private OffsetDateTime createdAt;


    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    /**
     * MyBatis can create an empty FiscalYear object
     * and populate it using its setter methods.
     */
    public FiscalYear() {
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

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
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

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
