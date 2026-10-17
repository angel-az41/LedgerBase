package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.IncomeStatementResponse;
import com.ledgerbase.backend.service.IncomeStatementService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

// ============================================================
// LEDGERBASE — INCOME STATEMENT REST CONTROLLER
// ============================================================

/**
 * REST controller for Income Statement reporting.
 *
 * This controller exposes financial statement data
 * calculated by IncomeStatementService.
 *
 * Responsibilities:
 *
 * - Receive reporting requests from React
 * - Read the business ID from the API path
 * - Read start and end dates from query parameters
 * - Delegate reporting calculations to the service
 * - Return the financial statement as JSON
 *
 * IMPORTANT:
 *
 * The controller does not perform financial
 * calculations or access PostgreSQL directly.
 *
 * All accounting calculations are performed through:
 *
 * IncomeStatementService
 *     -> IncomeStatementMapper
 *     -> PostgreSQL
 *
 * This endpoint is READ-ONLY.
 *
 * It does not create or update transactions,
 * accounts, fiscal years, or accounting periods.
 */
@RestController
@RequestMapping(
    "/api/v1/businesses/{businessId}/reports"
)
public class IncomeStatementController {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    private final IncomeStatementService incomeStatementService;


    // ========================================================
    // CONSTRUCTOR DEPENDENCY INJECTION
    // ========================================================

    /**
     * Spring Boot injects the IncomeStatementService.
     *
     * Constructor injection makes the dependency
     * explicit and simplifies automated testing.
     */
    public IncomeStatementController(
        IncomeStatementService incomeStatementService
    ) {

        this.incomeStatementService =
            incomeStatementService;
    }


    // ========================================================
    // GET INCOME STATEMENT
    // ========================================================

    /**
     * Generate an Income Statement for one business
     * over an inclusive reporting period.
     *
     * HTTP METHOD:
     *
     * GET
     *
     * ENDPOINT:
     *
     * /api/v1/businesses/{businessId}/reports/income-statement
     *
     * QUERY PARAMETERS:
     *
     * startDate:
     *   Inclusive reporting start date.
     *
     * endDate:
     *   Inclusive reporting end date.
     *
     * EXAMPLE:
     *
     * GET /api/v1/businesses/1/reports/income-statement
     *     ?startDate=2026-10-01
     *     &endDate=2026-10-31
     *
     * ACCOUNTING RULES:
     *
     * 1. Only POSTED journal transactions affect totals.
     *
     * 2. Only REVENUE and EXPENSE accounts are reported.
     *
     * 3. Inactive accounts with historical activity
     *    remain included.
     *
     * 4. Non-postable grouping accounts are excluded
     *    from direct ledger calculations.
     *
     * 5. Financial totals use exact BigDecimal arithmetic.
     *
     * 6. Unclassified accounts with posted activity
     *    are explicitly identified.
     *
     * DATE VALIDATION:
     *
     * @DateTimeFormat tells Spring Boot to parse dates
     * using the ISO format YYYY-MM-DD.
     *
     * Spring Boot rejects missing or malformed required
     * query parameters.
     *
     * IncomeStatementService additionally validates
     * that startDate is not after endDate.
     *
     * @param businessId Business requesting the report.
     * @param startDate Inclusive first reporting date.
     * @param endDate Inclusive last reporting date.
     *
     * @return Structured Income Statement response.
     */
    @GetMapping("/income-statement")
    public IncomeStatementResponse getIncomeStatement(

        // ====================================================
        // BUSINESS IDENTIFICATION
        // ====================================================

        @PathVariable("businessId")
        Long businessId,


        // ====================================================
        // REPORTING START DATE
        // ====================================================

        @RequestParam("startDate")
        @DateTimeFormat(
            iso = DateTimeFormat.ISO.DATE
        )
        LocalDate startDate,


        // ====================================================
        // REPORTING END DATE
        // ====================================================

        @RequestParam("endDate")
        @DateTimeFormat(
            iso = DateTimeFormat.ISO.DATE
        )
        LocalDate endDate

    ) {

        // ====================================================
        // DELEGATE REPORT GENERATION TO THE SERVICE
        // ====================================================

        /**
         * The service:
         *
         * - Validates the requested business
         * - Validates the reporting dates
         * - Retrieves posted financial activity
         * - Calculates reporting-group subtotals
         * - Calculates total revenue and expenses
         * - Calculates net income
         * - Identifies unclassified financial activity
         *
         * The returned DTO is automatically serialized
         * to JSON by Spring Boot.
         */
        return incomeStatementService.getIncomeStatement(
            businessId,
            startDate,
            endDate
        );
    }
}
