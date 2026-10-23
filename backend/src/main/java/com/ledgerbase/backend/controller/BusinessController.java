package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.BusinessRoleResponse;
import com.ledgerbase.backend.dto.CreateBusinessRequest;

import com.ledgerbase.backend.mapper.BusinessMembershipMapper;

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
 * for all business management endpoints.
 *
 * The verified JWT subject identifies the
 * authenticated PostgreSQL user.
 *
 * SECURITY:
 *
 * - Never trust ownerId submitted by the browser.
 * - Never use a hardcoded development user ID.
 * - List businesses through membership records.
 * - Retrieve businesses through membership records.
 * - Derive membership roles from PostgreSQL.
 * - Assign OWNER membership to business creators.
 *
 * BUSINESS ROLE ENDPOINT:
 *
 * GET /api/v1/businesses/{businessId}/my-role
 *
 * React uses this endpoint to display accounting
 * interfaces appropriate to the current role.
 *
 * The existing BusinessAuthorizationManager remains
 * responsible for enforcing backend permissions.
 */
@RestController
@RequestMapping("/api/v1/businesses")
public class BusinessController {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    private final BusinessService businessService;

    /**
     * Retrieve the current membership role
     * for one authenticated user and business.
     */
    private final BusinessMembershipMapper
        businessMembershipMapper;


    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public BusinessController(
        BusinessService businessService,
        BusinessMembershipMapper businessMembershipMapper
    ) {

        this.businessService = businessService;

        this.businessMembershipMapper =
            businessMembershipMapper;
    }


    // ========================================================
    // GET AUTHENTICATED USER'S BUSINESSES
    // ========================================================

    /**
     * GET /api/v1/businesses
     *
     * Return businesses for which the authenticated
     * user has a PostgreSQL membership.
     *
     * Supported membership roles:
     *
     * OWNER, ADMIN, ACCOUNTANT, VIEWER.
     *
     * Users without memberships receive
     * an empty JSON array.
     *
     * @param jwt verified JWT principal
     * @return accessible businesses
     */
    @GetMapping
    public List<Business> getBusinesses(
        @AuthenticationPrincipal Jwt jwt
    ) {

        Long userId =
            getAuthenticatedUserId(jwt);

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
     * Retrieve business details only when the
     * authenticated user has membership access.
     *
     * HTTP 200:
     * Accessible business.
     *
     * HTTP 404:
     * Missing or inaccessible business.
     *
     * @param businessId requested business
     * @param jwt verified JWT principal
     * @return accessible business information
     */
    @GetMapping("/{businessId}")
    public Business getBusiness(
        @PathVariable Long businessId,
        @AuthenticationPrincipal Jwt jwt
    ) {

        Long userId =
            getAuthenticatedUserId(jwt);

        return businessService.getBusinessForUser(
            businessId,
            userId
        );
    }


    // ========================================================
    // GET CURRENT USER'S BUSINESS ROLE
    // ========================================================

    /**
     * GET /api/v1/businesses/{businessId}/my-role
     *
     * Return the authenticated user's membership
     * role for the requested business.
     *
     * The user ID comes from the verified JWT.
     *
     * The role comes from the current PostgreSQL
     * business_memberships record.
     *
     * React cannot choose either value.
     *
     * SECURITY:
     *
     * SecurityConfig routes this nested business
     * endpoint through BusinessAuthorizationManager.
     *
     * Therefore, a caller must already have
     * recognized membership access to the business.
     *
     * This method also handles a missing membership
     * defensively.
     *
     * HTTP 200:
     * Returns the user's current business role.
     *
     * HTTP 401:
     * Missing or invalid authentication.
     *
     * HTTP 403:
     * No authorized membership.
     *
     * @param businessId selected business identifier
     * @param jwt verified JWT principal
     * @return authenticated user's business role
     */
    @GetMapping("/{businessId}/my-role")
    public BusinessRoleResponse getMyBusinessRole(
        @PathVariable Long businessId,
        @AuthenticationPrincipal Jwt jwt
    ) {

        // ----------------------------------------------------
        // RESOLVE TRUSTED USER ID
        // ----------------------------------------------------

        Long userId =
            getAuthenticatedUserId(jwt);


        // ----------------------------------------------------
        // VALIDATE BUSINESS IDENTIFIER
        // ----------------------------------------------------

        if (businessId == null || businessId <= 0) {

            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid business ID"
            );
        }


        // ----------------------------------------------------
        // RETRIEVE CURRENT DATABASE MEMBERSHIP
        // ----------------------------------------------------

        String role =
            businessMembershipMapper.findRole(
                businessId,
                userId
            );


        // ----------------------------------------------------
        // REQUIRE AN EXISTING MEMBERSHIP
        // ----------------------------------------------------

        if (role == null) {

            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Business membership required"
            );
        }


        // ----------------------------------------------------
        // RETURN CURRENT MEMBERSHIP ROLE
        // ----------------------------------------------------

        return new BusinessRoleResponse(role);
    }


    // ========================================================
    // CREATE BUSINESS FOR AUTHENTICATED USER
    // ========================================================

    /**
     * POST /api/v1/businesses
     *
     * Create a business owned by the authenticated user.
     *
     * The browser supplies business attributes,
     * but cannot select the owner ID.
     *
     * BusinessService creates the business and
     * its OWNER membership atomically.
     *
     * HTTP 201:
     * Business successfully created.
     *
     * @param request validated business attributes
     * @param jwt verified JWT principal
     * @return newly created business
     */
    @PostMapping
    public ResponseEntity<Business> createBusiness(
        @Valid @RequestBody CreateBusinessRequest request,
        @AuthenticationPrincipal Jwt jwt
    ) {

        // ----------------------------------------------------
        // RESOLVE AUTHENTICATED OWNER
        // ----------------------------------------------------

        Long ownerId =
            getAuthenticatedUserId(jwt);


        // ----------------------------------------------------
        // CREATE BUSINESS
        // ----------------------------------------------------

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
     * Extract the PostgreSQL user ID from the
     * JWT principal verified by Spring Security.
     *
     * Do not accept a browser-provided user ID.
     *
     * Invalid or missing identities are rejected.
     *
     * @param jwt verified JWT principal
     * @return positive authenticated user ID
     */
    private Long getAuthenticatedUserId(
        Jwt jwt
    ) {

        // ----------------------------------------------------
        // REQUIRE AUTHENTICATED JWT
        // ----------------------------------------------------

        if (jwt == null) {

            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Authentication required"
            );
        }


        // ----------------------------------------------------
        // PARSE JWT SUBJECT
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
