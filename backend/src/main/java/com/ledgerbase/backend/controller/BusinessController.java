package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.CreateBusinessRequest;
import com.ledgerbase.backend.model.Business;
import com.ledgerbase.backend.service.BusinessService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// ============================================================
// LEDGERBASE — BUSINESS REST CONTROLLER
// ============================================================

/**
 * REST API for managing LedgerBase businesses.
 *
 * BASE URL:
 *
 * /api/v1/businesses
 *
 * RESPONSIBILITIES:
 *
 * 1. List businesses belonging to the development owner.
 * 2. Retrieve a specific business.
 * 3. Create a new business.
 *
 * ARCHITECTURE:
 *
 * React Frontend
 *       |
 *       v
 * BusinessController
 *       |
 *       v
 * BusinessService
 *       |
 *       v
 * BusinessMapper
 *       |
 *       v
 * PostgreSQL
 *
 * The controller handles HTTP requests and responses.
 *
 * BusinessService handles validation, normalization,
 * business rules, and database transactions.
 *
 * IMPORTANT — LOCAL DEVELOPMENT ONLY:
 *
 * Authentication and authorization have not yet
 * been implemented.
 *
 * We temporarily use the existing development
 * owner's user ID (1).
 *
 * This does NOT authenticate callers.
 *
 * Do not expose these business-management endpoints
 * to untrusted users or deploy them publicly until
 * authentication and ownership authorization exist.
 *
 * Later, the owner ID must be obtained from the
 * authenticated user's security context.
 */
@RestController
@RequestMapping("/api/v1/businesses")
public class BusinessController {

    // ========================================================
    // DEVELOPMENT OWNER CONFIGURATION
    // ========================================================

    /**
     * Temporary owner for local development.
     *
     * Our existing development business belongs
     * to user ID 1.
     *
     * Both the existing LedgerBase Demo Business
     * and the future ABC Consulting LLC demo business
     * will belong to this user.
     *
     * This constant must be replaced when we
     * implement authentication and authorization.
     */
    private static final Long DEVELOPMENT_OWNER_ID = 1L;


    // ========================================================
    // BUSINESS SERVICE
    // ========================================================

    /**
     * BusinessService is responsible for:
     *
     * - Business creation
     * - Input validation
     * - Business retrieval
     * - Owner-scoped database operations
     */
    private final BusinessService businessService;


    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    /**
     * Spring Boot automatically supplies BusinessService.
     *
     * Constructor injection keeps dependencies explicit
     * and makes the controller easier to test.
     */
    public BusinessController(
        BusinessService businessService
    ) {

        this.businessService = businessService;
    }


    // ========================================================
    // 1. GET ALL BUSINESSES
    // ========================================================

    /**
     * GET /api/v1/businesses
     *
     * Retrieve businesses belonging to the
     * development owner.
     *
     * EXAMPLE RESPONSE:
     *
     * [
     *     {
     *         "id": 1,
     *         "ownerId": 1,
     *         "name": "LedgerBase Demo Business",
     *         "currency": "USD",
     *         "fiscalYearStartMonth": 1,
     *         "createdAt": "..."
     *     }
     * ]
     *
     * RETURNS:
     *
     * HTTP 200 — List of businesses.
     *
     * If the owner has no businesses, the API
     * returns an empty JSON array.
     *
     * Later, this method will retrieve the owner
     * identity from the authenticated user.
     */
    @GetMapping
    public List<Business> getBusinesses() {

        // ====================================================
        // RETRIEVE OWNER'S BUSINESSES
        // ====================================================

        return businessService.getBusinessesByOwner(
            DEVELOPMENT_OWNER_ID
        );
    }


    // ========================================================
    // 2. GET ONE BUSINESS
    // ========================================================

    /**
     * GET /api/v1/businesses/{businessId}
     *
     * EXAMPLE:
     *
     * GET /api/v1/businesses/1
     *
     * Retrieves the requested business only if it
     * belongs to the development owner.
     *
     * RETURNS:
     *
     * HTTP 200 — Business found.
     *
     * HTTP 400 — Invalid business ID.
     *
     * HTTP 404 — Business not found or owner mismatch.
     *
     * Missing businesses are handled through our
     * existing BusinessNotFoundException and
     * GlobalExceptionHandler.
     */
    @GetMapping("/{businessId}")
    public Business getBusiness(
        @PathVariable Long businessId
    ) {

        // ====================================================
        // RETRIEVE BUSINESS THROUGH SERVICE
        // ====================================================

        return businessService.getBusinessById(
            businessId,
            DEVELOPMENT_OWNER_ID
        );
    }


    // ========================================================
    // 3. CREATE A NEW BUSINESS
    // ========================================================

    /**
     * POST /api/v1/businesses
     *
     * EXAMPLE REQUEST:
     *
     * {
     *     "name": "ABC Consulting LLC",
     *     "currency": "USD",
     *     "fiscalYearStartMonth": 1
     * }
     *
     * REQUEST VALIDATION:
     *
     * @Valid activates the Jakarta Validation
     * annotations in CreateBusinessRequest.
     *
     * BusinessService performs additional validation
     * and normalization.
     *
     * OWNERSHIP:
     *
     * The request body does not contain ownerId.
     *
     * During local development, ownerId is supplied
     * by DEVELOPMENT_OWNER_ID.
     *
     * After authentication is implemented, it must
     * come from a trusted authenticated identity.
     *
     * RETURNS:
     *
     * HTTP 201 — Business created successfully.
     *
     * HTTP 400 — Invalid request data.
     *
     * HTTP 409 — Database constraint conflict.
     *
     * The response includes the created business,
     * including its generated ID and createdAt.
     */
    @PostMapping
    public ResponseEntity<Business> createBusiness(
        @Valid @RequestBody CreateBusinessRequest request
    ) {

        // ====================================================
        // CREATE BUSINESS THROUGH SERVICE
        // ====================================================

        /**
         * We pass only the permitted request fields.
         *
         * The client cannot choose:
         *
         * - The owner ID
         * - The generated database ID
         * - The creation timestamp
         */
        Business createdBusiness =
            businessService.createBusiness(
                DEVELOPMENT_OWNER_ID,
                request.name(),
                request.currency(),
                request.fiscalYearStartMonth()
            );


        // ====================================================
        // RETURN HTTP 201 CREATED
        // ====================================================

        /**
         * HTTP 201 indicates that a new resource
         * was created successfully.
         *
         * The response body contains the complete
         * created business.
         */
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(createdBusiness);
    }
}
