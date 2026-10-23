package com.ledgerbase.reporting.controller;

import com.ledgerbase.reporting.model.TrialBalanceReport;
import com.ledgerbase.reporting.service.TrialBalanceService;

import org.springframework.format.annotation.DateTimeFormat;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

// ============================================================
// LEDGERBASE — TRIAL BALANCE REPORTING CONTROLLER
// ============================================================

/**
 * Exposes the Trial Balance REST API through the
 * independent financial Reporting Service.
 *
 * API CONTRACT:
 *
 * GET /api/v1/businesses/{businessId}/reports/trial-balance
 *
 * Query parameter:
 *
 * asOfDate=YYYY-MM-DD
 *
 * SECURITY:
 *
 * Spring Security must verify:
 *
 * 1. Valid LedgerBase JWT.
 * 2. Active Accounting Core authentication session.
 * 3. Current business membership.
 * 4. Reporting read permission.
 *
 * Only authorized requests reach this controller.
 *
 * This controller performs no database writes
 * or monetary calculations.
 */
@RestController
@RequestMapping(
    "/api/v1/businesses/{businessId}/reports"
)
public class TrialBalanceController {

    // ========================================================
    // REPORTING SERVICE DEPENDENCY
    // ========================================================

    private final TrialBalanceService trialBalanceService;

    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public TrialBalanceController(
        TrialBalanceService trialBalanceService
    ) {

        this.trialBalanceService = trialBalanceService;
    }

    // ========================================================
    // GET TRIAL BALANCE REPORT
    // ========================================================

    /**
     * Generate a Trial Balance for an authorized business.
     *
     * Example:
     *
     * GET /api/v1/businesses/2/reports/trial-balance
     *     ?asOfDate=2026-10-23
     *
     * The report includes:
     *
     * - Business ID and reporting date.
     * - Individual account balances.
     * - Total debit and credit balances.
     * - Balancing difference.
     * - Balanced status.
     *
     * Monetary values are serialized as JSON strings
     * to preserve decimal precision.
     *
     * @param businessId authorized business identifier
     * @param asOfDate reporting cutoff date
     * @return complete Trial Balance report
     */
    @GetMapping("/trial-balance")
    public TrialBalanceReport getTrialBalance(

        @PathVariable
        Long businessId,

        @RequestParam("asOfDate")
        @DateTimeFormat(
            iso = DateTimeFormat.ISO.DATE
        )
        LocalDate asOfDate
    ) {

        return trialBalanceService.generateTrialBalance(
            businessId,
            asOfDate
        );
    }
}
