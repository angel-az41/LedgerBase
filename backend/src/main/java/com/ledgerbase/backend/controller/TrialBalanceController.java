package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.model.TrialBalanceReport;
import com.ledgerbase.backend.service.TrialBalanceService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

// ============================================================
// LEDGERBASE — TRIAL BALANCE REST CONTROLLER
// ============================================================

/**
 * REST API for LedgerBase Trial Balance reporting.
 *
 * Base URL:
 *
 * /api/v1/businesses/{businessId}/reports
 *
 * Responsibilities:
 *
 * - Receive Trial Balance reporting requests
 * - Read the selected business ID
 * - Parse the requested reporting date
 * - Delegate report generation to TrialBalanceService
 * - Return the complete report as JSON
 *
 * IMPORTANT:
 *
 * This controller does not calculate monetary values.
 *
 * PostgreSQL calculates account-level balances.
 * TrialBalanceService calculates report totals.
 *
 * Authentication and business ownership checks
 * must be implemented before public deployment.
 */
@RestController
@RequestMapping("/api/v1/businesses/{businessId}/reports")
public class TrialBalanceController {

    // ========================================================
    // REPORTING SERVICE
    // ========================================================

    /**
     * Contains the business logic required
     * to generate Trial Balance reports.
     */
    private final TrialBalanceService trialBalanceService;

    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    /**
     * Spring Boot automatically injects
     * TrialBalanceService through this constructor.
     */
    public TrialBalanceController(
            TrialBalanceService trialBalanceService) {

        this.trialBalanceService = trialBalanceService;
    }

    // ========================================================
    // GET TRIAL BALANCE REPORT
    // ========================================================

    /**
     * GET /api/v1/businesses/1/reports/trial-balance
     *
     * Example request:
     *
     * GET /api/v1/businesses/1/reports/trial-balance
     *     ?asOfDate=2026-10-13
     *
     * Request parameters:
     *
     * businessId:
     *     Identifies the business whose report
     *     should be generated.
     *
     * asOfDate:
     *     Last accounting date included in the report.
     *
     * ACCOUNTING RULES:
     *
     * - Only POSTED journal entries are included.
     * - Transactions after asOfDate are excluded.
     * - Both active and inactive accounts are included.
     * - Zero-balance accounts are included.
     * - Debit and credit totals are calculated exactly.
     *
     * HTTP BEHAVIOR:
     *
     * 200 OK:
     *     Report generated successfully.
     *
     * 400 Bad Request:
     *     Required reporting date is missing
     *     or cannot be parsed.
     *
     * 404 Not Found:
     *     The requested business does not exist,
     *     using the existing exception handler.
     *
     * This endpoint is read-only.
     *
     * @param businessId Business being reported.
     * @param asOfDate Inclusive reporting cutoff date.
     *
     * @return Complete Trial Balance report.
     */
    @GetMapping("/trial-balance")
    public TrialBalanceReport getTrialBalance(

            @PathVariable
            Long businessId,

            @RequestParam("asOfDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOfDate) {

        // ====================================================
        // GENERATE THE FINANCIAL REPORT
        // ====================================================

        /**
         * Spring converts the query parameter
         * "2026-10-13" into a Java LocalDate.
         *
         * TrialBalanceService:
         *
         * 1. Validates business existence.
         * 2. Validates the reporting date.
         * 3. Executes the MyBatis report query.
         * 4. Calculates total debits and credits.
         * 5. Determines whether the report balances.
         *
         * Spring automatically serializes the
         * returned TrialBalanceReport into JSON.
         *
         * Monetary BigDecimal values are serialized
         * as strings to preserve decimal precision.
         */
        return trialBalanceService.generateTrialBalance(
            businessId,
            asOfDate
        );
    }
}
