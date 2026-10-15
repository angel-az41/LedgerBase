package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.CreateFiscalYearRequest;
import com.ledgerbase.backend.model.FiscalYear;
import com.ledgerbase.backend.service.FiscalYearService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// ============================================================
// LEDGERBASE — FISCAL YEAR REST CONTROLLER
// ============================================================

/**
 * REST API for fiscal-year management.
 *
 * Base URL:
 *
 * /api/v1/businesses/{businessId}/fiscal-years
 *
 * Responsibilities:
 *
 * 1. Retrieve all fiscal years for a business.
 * 2. Retrieve a specific fiscal year.
 * 3. Create a new fiscal year and 12 monthly periods.
 *
 * The controller handles HTTP requests and responses.
 *
 * FiscalYearService handles business rules,
 * date calculations, database transactions,
 * and accounting-period generation.
 *
 * IMPORTANT:
 *
 * These endpoints currently support local development.
 *
 * Authentication and business ownership authorization
 * must be implemented before public deployment.
 */
@RestController
@RequestMapping("/api/v1/businesses/{businessId}/fiscal-years")
public class FiscalYearController {

    // ========================================================
    // FISCAL YEAR SERVICE
    // ========================================================

    private final FiscalYearService fiscalYearService;


    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    /**
     * Spring injects the service through
     * constructor injection.
     */
    public FiscalYearController(
            FiscalYearService fiscalYearService) {

        this.fiscalYearService = fiscalYearService;
    }


    // ========================================================
    // GET ALL FISCAL YEARS
    // ========================================================

    /**
     * GET /api/v1/businesses/1/fiscal-years
     *
     * Retrieve all fiscal years belonging to a business.
     *
     * Results are ordered from newest to oldest.
     *
     * Returns HTTP 200 with a JSON array.
     *
     * Returns HTTP 404 if the business does not exist,
     * using our existing exception handling.
     */
    @GetMapping
    public List<FiscalYear> getFiscalYears(
            @PathVariable Long businessId) {

        return fiscalYearService.getFiscalYears(
            businessId
        );
    }


    // ========================================================
    // GET ONE FISCAL YEAR
    // ========================================================

    /**
     * GET /api/v1/businesses/1/fiscal-years/5
     *
     * Retrieve one fiscal year belonging to a business.
     *
     * Returns:
     *
     * HTTP 200 — Fiscal year found.
     * HTTP 404 — Fiscal year not found.
     *
     * The service enforces business-scoped retrieval.
     */
    @GetMapping("/{fiscalYearId}")
    public ResponseEntity<FiscalYear> getFiscalYear(
            @PathVariable Long businessId,
            @PathVariable Long fiscalYearId) {

        FiscalYear fiscalYear =
            fiscalYearService.getFiscalYear(
                businessId,
                fiscalYearId
            );

        if (fiscalYear == null) {

            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(fiscalYear);
    }


    // ========================================================
    // CREATE FISCAL YEAR
    // ========================================================

    /**
     * POST /api/v1/businesses/1/fiscal-years
     *
     * Example request body:
     *
     * {
     *     "fiscalYearEndYear": 2027
     * }
     *
     * @Valid runs Jakarta Bean Validation against
     * CreateFiscalYearRequest.
     *
     * The service:
     *
     * 1. Validates the business.
     * 2. Reads its fiscal-year starting month.
     * 3. Calculates fiscal-year dates.
     * 4. Rejects duplicate or overlapping years.
     * 5. Inserts the new fiscal year.
     * 6. Generates all 12 monthly periods.
     *
     * Steps 5 and 6 run in one database transaction.
     *
     * Returns HTTP 201 with the created fiscal year.
     */
    @PostMapping
    public ResponseEntity<FiscalYear> createFiscalYear(
            @PathVariable Long businessId,
            @Valid @RequestBody CreateFiscalYearRequest request) {

        FiscalYear created =
            fiscalYearService.createFiscalYear(
                businessId,
                request.fiscalYearEndYear()
            );

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(created);
    }
}
