package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.AccountingPeriodActionRequest;
import com.ledgerbase.backend.model.AccountingPeriod;
import com.ledgerbase.backend.service.AccountingPeriodService;

import jakarta.validation.Valid;

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
 * 3. Close an OPEN accounting period.
 * 4. Reopen a CLOSED accounting period.
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


    // ========================================================
    // CLOSE ACCOUNTING PERIOD
    // ========================================================

    /**
     * POST /api/v1/businesses/1/accounting-periods/10/close
     *
     * Change an accounting period from OPEN to CLOSED.
     *
     * Example request body:
     *
     * {
     *   "actorId": 1
     * }
     *
     * AccountingPeriodService is responsible for:
     *
     * 1. Checking that the period belongs to the business.
     * 2. Verifying that its current status is OPEN.
     * 3. Updating the status to CLOSED.
     * 4. Recording the closing timestamp.
     * 5. Writing an accounting-period audit event.
     *
     * A CLOSED period cannot accept new journal postings.
     *
     * Returns:
     *
     * HTTP 200 — Period successfully closed.
     * HTTP 400 — Invalid actor ID.
     * HTTP 404 — Period not found for this business.
     * HTTP 409 — Period is already CLOSED or state
     *            changes conflict with the request.
     */
    @PostMapping("/accounting-periods/{periodId}/close")
    public ResponseEntity<AccountingPeriod> closeAccountingPeriod(
            @PathVariable Long businessId,
            @PathVariable Long periodId,
            @Valid @RequestBody AccountingPeriodActionRequest request) {

        // ====================================================
        // DELEGATE TO ACCOUNTING PERIOD SERVICE
        // ====================================================

        AccountingPeriod closedPeriod =
            accountingPeriodService.closeAccountingPeriod(
                businessId,
                periodId,
                request.actorId()
            );

        // ====================================================
        // HANDLE MISSING OR CROSS-BUSINESS PERIOD
        // ====================================================

        /**
         * The service returns null when the period cannot
         * be found within the requested business.
         */
        if (closedPeriod == null) {

            return ResponseEntity.notFound().build();
        }

        // ====================================================
        // RETURN UPDATED ACCOUNTING PERIOD
        // ====================================================

        return ResponseEntity.ok(closedPeriod);
    }

    // ========================================================
    // REOPEN ACCOUNTING PERIOD
    // ========================================================

    /**
     * POST /api/v1/businesses/1/accounting-periods/10/reopen
     *
     * Change an accounting period from CLOSED to OPEN.
     *
     * Example request body:
     *
     * {
     *   "actorId": 1
     * }
     *
     * AccountingPeriodService is responsible for:
     *
     * 1. Checking that the period belongs to the business.
     * 2. Verifying that its current status is CLOSED.
     * 3. Updating the status to OPEN.
     * 4. Clearing the closing timestamp.
     * 5. Writing an accounting-period audit event.
     *
     * Reopening allows new journal postings dated
     * within the accounting period.
     *
     * Returns:
     *
     * HTTP 200 — Period successfully reopened.
     * HTTP 400 — Invalid actor ID.
     * HTTP 404 — Period not found for this business.
     * HTTP 409 — Period is already OPEN or state
     *            changes conflict with the request.
     */
    @PostMapping("/accounting-periods/{periodId}/reopen")
    public ResponseEntity<AccountingPeriod> reopenAccountingPeriod(
            @PathVariable Long businessId,
            @PathVariable Long periodId,
            @Valid @RequestBody AccountingPeriodActionRequest request) {

        // ====================================================
        // DELEGATE TO ACCOUNTING PERIOD SERVICE
        // ====================================================

        AccountingPeriod reopenedPeriod =
            accountingPeriodService.reopenAccountingPeriod(
                businessId,
                periodId,
                request.actorId()
            );

        // ====================================================
        // HANDLE MISSING OR CROSS-BUSINESS PERIOD
        // ====================================================

        if (reopenedPeriod == null) {

            return ResponseEntity.notFound().build();
        }

        // ====================================================
        // RETURN UPDATED ACCOUNTING PERIOD
        // ====================================================

        return ResponseEntity.ok(reopenedPeriod);
    }
}
