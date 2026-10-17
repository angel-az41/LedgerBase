package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.BalanceSheetResponse;
import com.ledgerbase.backend.service.BalanceSheetService;

import org.springframework.format.annotation.DateTimeFormat;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

// ============================================================
// LEDGERBASE — BALANCE SHEET REST CONTROLLER
// ============================================================

/**
 * Exposes read-only Balance Sheet reporting through
 * the LedgerBase REST API.
 *
 * ENDPOINT:
 *
 * GET /api/v1/businesses/{businessId}/reports/balance-sheet
 *
 * QUERY PARAMETERS:
 *
 * asOfDate = YYYY-MM-DD
 *
 * EXAMPLE:
 *
 * GET /api/v1/businesses/1/reports/balance-sheet
 *     ?asOfDate=2026-10-17
 *
 * RESPONSIBILITIES:
 *
 * 1. Receive an HTTP GET request.
 *
 * 2. Extract the business ID from the URL.
 *
 * 3. Parse the as-of reporting date.
 *
 * 4. Delegate financial calculations to
 *    BalanceSheetService.
 *
 * 5. Return the complete BalanceSheetResponse
 *    serialized as JSON.
 *
 * IMPORTANT:
 *
 * This controller does not perform accounting
 * calculations or execute SQL directly.
 *
 * All financial calculations belong to the
 * service layer.
 *
 * All monetary values are returned as exact
 * decimal strings.
 *
 * Authentication and business-membership
 * authorization will be added when LedgerBase's
 * security layer is implemented.
 */
@RestController
@RequestMapping(
    "/api/v1/businesses/{businessId}/reports"
)
public class BalanceSheetController {

    // ========================================================
    // SERVICE DEPENDENCY
    // ========================================================

    /**
     * Spring injects the Balance Sheet service.
     *
     * The controller calls this service instead
     * of accessing PostgreSQL directly.
     */
    private final BalanceSheetService balanceSheetService;


    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    /**
     * Constructor injection makes the dependency
     * explicit and simplifies controller testing.
     */
    public BalanceSheetController(
        BalanceSheetService balanceSheetService
    ) {

        this.balanceSheetService = balanceSheetService;
    }


    // ========================================================
    // GET BALANCE SHEET
    // ========================================================

    /**
     * Retrieve a Balance Sheet as of a specified date.
     *
     * HTTP METHOD:
     *
     * GET
     *
     * URL:
     *
     * /api/v1/businesses/{businessId}/reports/balance-sheet
     *
     * QUERY PARAMETER:
     *
     * asOfDate=YYYY-MM-DD
     *
     * EXAMPLE:
     *
     * /api/v1/businesses/1/reports/balance-sheet
     *     ?asOfDate=2026-10-17
     *
     * ========================================================
     * PATH VARIABLE
     * ========================================================
     *
     * businessId identifies the business whose
     * financial information is being requested.
     *
     * The service verifies that the business exists.
     *
     * ========================================================
     * REPORTING DATE
     * ========================================================
     *
     * @DateTimeFormat tells Spring to parse the
     * query parameter as an ISO calendar date.
     *
     * For example:
     *
     * 2026-10-17
     *
     * The reporting cutoff is inclusive.
     *
     * Transactions posted to dates after the
     * requested date are excluded from the report.
     *
     * ========================================================
     * RESPONSE
     * ========================================================
     *
     * Spring serializes BalanceSheetResponse
     * into JSON automatically.
     *
     * The response includes:
     *
     * - Seven financial reporting sections
     * - Account-level balances
     * - Unclassified account warnings
     * - Total Assets
     * - Total Liabilities
     * - Posted Equity
     * - Unclosed Earnings
     * - Total Equity
     * - Total Liabilities and Equity
     * - Accounting equation difference
     * - Balanced status
     *
     * @param businessId Business identifier.
     * @param asOfDate Inclusive financial reporting date.
     *
     * @return Complete Balance Sheet response.
     */
    @GetMapping("/balance-sheet")
    public BalanceSheetResponse getBalanceSheet(

        @PathVariable("businessId")
        Long businessId,

        @RequestParam("asOfDate")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate asOfDate

    ) {

        // ====================================================
        // DELEGATE TO FINANCIAL REPORTING SERVICE
        // ====================================================

        /**
         * The service performs:
         *
         * 1. Business validation.
         * 2. Retrieval of posted account balances.
         * 3. Classification into reporting sections.
         * 4. Calculation of accumulated earnings.
         * 5. Accounting equation reconciliation.
         *
         * The controller simply returns the result.
         */
        return balanceSheetService.getBalanceSheet(
            businessId,
            asOfDate
        );
    }
}
