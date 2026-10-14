package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.model.AccountingPeriod;
import com.ledgerbase.backend.service.AccountingPeriodService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// ============================================================
// LEDGERBASE — ACCOUNTING PERIOD REST CONTROLLER
// ============================================================

/**
 * REST API for accounting-period management.
 *
 * Base URL:
 *
 * /api/v1/businesses/{businessId}
 *
 * Responsibilities:
 *
 * 1. Retrieve the accounting periods of a fiscal year.
 * 2. Retrieve one accounting period.
 *
 * Each fiscal year normally has 12 monthly periods.
 *
 * Example:
 *
 * FY2026:
 *
 * Period 1  = January 2026
 * Period 2  = February 2026
 * ...
 * Period 12 = December 2026
 *
 * This controller currently provides READ-ONLY access.
 *
 * Closing and reopening endpoints will be introduced
 * after we implement the journal-posting restrictions.
 *
 * The controller handles HTTP requests and responses.
 * AccountingPeriodService handles business logic.
 *
 * IMPORTANT:
 *
 * Authentication and business ownership authorization
 * must be implemented before public deployment.
 */
@RestController
@RequestMapping("/api/v1/businesses/{businessId}")
public class AccountingPeriodController {

    // ========================================================
    // ACCOUNTING PERIOD SERVICE
    // ========================================================

    /**
     * Service responsible for retrieving accounting periods
     * and validating business ownership of fiscal years.
     */
    private final AccountingPeriodService accountingPeriodService;


    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    /**
     * Spring automatically injects the service
     * through constructor injection.
     */
    public AccountingPeriodController(
            AccountingPeriodService accountingPeriodService) {

        this.accountingPeriodService = accountingPeriodService;
    }


    // ========================================================
    // GET ALL PERIODS FOR A FISCAL YEAR
    // ========================================================

    /**
     * GET /api/v1/businesses/1/fiscal-years/5/periods
     *
     * Retrieve the 12 monthly accounting periods
     * belonging to a specific fiscal year.
     *
     * Results are ordered by period number.
     *
     * Example response:
     *
     * [
     *   {
     *     "id": 1,
     *     "businessId": 1,
     *     "fiscalYearId": 5,
     *     "periodNumber": 1,
     *     "startDate": "2026-01-01",
     *     "endDate": "2026-01-31",
     *     "status": "OPEN",
     *     "closedAt": null
     *   }
     * ]
     *
     * Returns:
     *
     * HTTP 200 — Fiscal year exists; returns its periods.
     * HTTP 404 — Fiscal year does not exist or belongs
     *            to another business.
     *
     * A missing business also produces HTTP 404 through
     * the existing BusinessNotFoundException handler.
     */
    @GetMapping("/fiscal-years/{fiscalYearId}/periods")
    public ResponseEntity<List<AccountingPeriod>> getPeriodsForFiscalYear(
            @PathVariable Long businessId,
            @PathVariable Long fiscalYearId) {

        // ====================================================
        // LOAD ACCOUNTING PERIODS
        // ====================================================

        List<AccountingPeriod> periods =
            accountingPeriodService.getPeriodsForFiscalYear(
                businessId,
                fiscalYearId
            );


        // ====================================================
        // HANDLE MISSING FISCAL YEAR
        // ====================================================

        /**
         * The service returns null when no fiscal year
         * matches both IDs.
         *
         * This is different from an existing fiscal year
         * containing zero periods, which returns [].
         */
        if (periods == null) {

            return ResponseEntity.notFound().build();
        }


        // ====================================================
        // RETURN ACCOUNTING PERIODS
        // ====================================================

        return ResponseEntity.ok(periods);
    }


    // ========================================================
    // GET ONE ACCOUNTING PERIOD
    // ========================================================

    /**
     * GET /api/v1/businesses/1/accounting-periods/10
     *
     * Retrieve a particular accounting period.
     *
     * The service and MyBatis mapper enforce
     * business-scoped retrieval.
     *
     * Returns:
     *
     * HTTP 200 — Accounting period found.
     * HTTP 404 — Period missing or belongs to
     *            another business.
     */
    @GetMapping("/accounting-periods/{periodId}")
    public ResponseEntity<AccountingPeriod> getAccountingPeriod(
            @PathVariable Long businessId,
            @PathVariable Long periodId) {

        // ====================================================
        // RETRIEVE BUSINESS-SCOPED PERIOD
        // ====================================================

        AccountingPeriod period =
            accountingPeriodService.getAccountingPeriod(
                businessId,
                periodId
            );


        // ====================================================
        // HANDLE MISSING PERIOD
        // ====================================================

        if (period == null) {

            return ResponseEntity.notFound().build();
        }


        // ====================================================
        // RETURN ACCOUNTING PERIOD
        // ====================================================

        return ResponseEntity.ok(period);
    }
}
