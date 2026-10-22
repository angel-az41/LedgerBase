package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.CreateBusinessRequest;
import com.ledgerbase.backend.model.Business;
import com.ledgerbase.backend.service.BusinessService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
 * AUTHENTICATION:
 *
 * Spring Security requires a valid Bearer JWT
 * before allowing requests to this controller.
 *
 * The authenticated JWT subject contains the
 * PostgreSQL user ID.
 *
 * SECURITY:
 *
 * - Never trust ownerId from a request body.
 * - Never use a hardcoded development user ID.
 * - Never use a browser-supplied user ID for access.
 * - List businesses through membership records.
 * - Retrieve businesses through membership records.
 * - Assign OWNER membership to business creators.
 *
 * DATABASE:
 *
 * BusinessService and BusinessMapper enforce
 * business membership conditions.
 *
 * More granular financial operation permissions
 * will be implemented separately.
 */
@RestController
@RequestMapping("/api/v1/businesses")
public class BusinessController {

    // ========================================================
    // BUSINESS SERVICE DEPENDENCY
    // ========================================================

    private final BusinessService businessService;


    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public BusinessController(
        BusinessService businessService
    ) {

        this.businessService = businessService;
    }


    // ========================================================
    // GET AUTHENTICATED USER'S BUSINESSES
    // ========================================================

    /**
     * GET /api/v1/businesses
     *
     * Return only businesses for which the
     * authenticated user has a membership.
     *
     * Supported roles:
     *
     * - OWNER
     * - ADMIN
     * - ACCOUNTANT
     * - VIEWER
     *
     * HTTP 200:
     * Returns an accessible business list.
     *
     * An authenticated user with no memberships
     * receives an empty JSON array.
     *
     * HTTP 401:
     * Missing or invalid authentication.
     *
     * @param jwt validated JWT principal
     * @return accessible businesses
     */
    @GetMapping
    public List<Business> getBusinesses(
        @AuthenticationPrincipal Jwt jwt
    ) {

        // ----------------------------------------------------
        // GET TRUSTED AUTHENTICATED USER ID
        // ----------------------------------------------------

        Long userId =
            getAuthenticatedUserId(jwt);


        // ----------------------------------------------------
        // QUERY MEMBERSHIP-SCOPED BUSINESS LIST
        // ----------------------------------------------------

        return businessService.getBusinessesForUser(
            userId
        );
    }


    // ========================================================
    // GET ONE AUTHORIZED BUSINESS
    // ========================================================

    /**
     * GET /api/v1/businesses/{businessId}
     *
     * Return the requested business only if the
     * authenticated user has membership access.
     *
     * HTTP 200:
     * Business is accessible.
     *
     * HTTP 400:
     * Invalid business ID.
     *
     * HTTP 401:
     * Missing or invalid authentication.
     *
     * HTTP 404:
     * Business does not exist or is inaccessible.
     *
     * Returning 404 for nonmembers prevents
     * revealing the existence of another
     * user's business through this endpoint.
     *
     * @param businessId requested business ID
     * @param jwt validated JWT principal
     * @return accessible business
     */
    @GetMapping("/{businessId}")
    public Business getBusiness(
        @PathVariable Long businessId,
        @AuthenticationPrincipal Jwt jwt
    ) {

        // ----------------------------------------------------
        // GET TRUSTED AUTHENTICATED USER ID
        // ----------------------------------------------------

        Long userId =
            getAuthenticatedUserId(jwt);


        // ----------------------------------------------------
        // QUERY BUSINESS AND MEMBERSHIP
        // ----------------------------------------------------

        return businessService.getBusinessForUser(
            businessId,
            userId
        );
    }


    // ========================================================
    // CREATE BUSINESS FOR AUTHENTICATED USER
    // ========================================================

    /**
     * POST /api/v1/businesses
     *
     * Example request:
     *
     * {
     *     "name": "ABC Consulting LLC",
     *     "currency": "USD",
     *     "fiscalYearStartMonth": 1
     * }
     *
     * AUTHENTICATED OWNERSHIP:
     *
     * The user ID comes exclusively from the
     * validated JWT subject.
     *
     * The browser cannot choose an ownerId.
     *
     * DATABASE:
     *
     * BusinessService inserts the business and
     * its OWNER membership atomically.
     *
     * HTTP 201:
     * Business successfully created.
     *
     * HTTP 400:
     * Invalid input.
     *
     * HTTP 401:
     * Missing or invalid authentication.
     *
     * @param request validated business input
     * @param jwt validated JWT principal
     * @return created business
     */
    @PostMapping
    public ResponseEntity<Business> createBusiness(
        @Valid @RequestBody CreateBusinessRequest request,
        @AuthenticationPrincipal Jwt jwt
    ) {

        // ----------------------------------------------------
        // GET AUTHENTICATED OWNER ID
        // ----------------------------------------------------

        Long ownerId =
            getAuthenticatedUserId(jwt);


        // ----------------------------------------------------
        // CREATE BUSINESS AND OWNER MEMBERSHIP
        // ----------------------------------------------------

        /**
         * Only validated business properties are
         * taken from the request body.
         *
         * The trusted ownerId is supplied separately.
         */
        Business createdBusiness =
            businessService.createBusiness(
                ownerId,
                request.name(),
                request.currency(),
                request.fiscalYearStartMonth()
            );


        // ----------------------------------------------------
        // RETURN CREATED BUSINESS
        // ----------------------------------------------------

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(createdBusiness);
    }


    // ========================================================
    // RESOLVE AUTHENTICATED USER ID
    // ========================================================

    /**
     * Extract the database user ID from Spring
     * Security's validated JWT principal.
     *
     * This is NOT a user ID submitted by the browser.
     *
     * The JWT has already passed the configured:
     *
     * - Signature validation.
     * - Issuer and audience validation.
     * - Expiration validation.
     * - PostgreSQL session validation.
     *
     * The subject must be a positive numeric ID.
     *
     * A malformed identity is rejected with HTTP 401.
     *
     * @param jwt verified JWT principal
     * @return trusted PostgreSQL user ID
     */
    private Long getAuthenticatedUserId(
        Jwt jwt
    ) {

        // ----------------------------------------------------
        // REQUIRE A JWT PRINCIPAL
        // ----------------------------------------------------

        if (jwt == null) {

            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Authentication required"
            );
        }


        // ----------------------------------------------------
        // PARSE POSITIVE USER ID
        // ----------------------------------------------------

        try {

            Long userId =
                Long.parseLong(
                    jwt.getSubject()
                );

            if (userId <= 0) {

                throw new NumberFormatException(
                    "Nonpositive JWT subject"
                );
            }

            return userId;

        } catch (
            NumberFormatException
            | NullPointerException exception
        ) {

            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Invalid authenticated identity"
            );
        }
    }
}
