package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.service.BusinessReportingRevisionService;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// ============================================================
// LEDGERBASE — REPORTING ACCESS CONTROLLER
// ============================================================

/**
 * Provides a lightweight authorization and reporting
 * revision endpoint for the independent Reporting Service.
 *
 * PURPOSE:
 *
 * The Reporting Service must confirm that a requesting
 * user has an active authentication session and current
 * access to the requested business.
 *
 * After authorization succeeds, Accounting Core also
 * returns the business's authoritative reporting revision.
 *
 * SECURITY:
 *
 * 1. The caller supplies an existing JWT Bearer token.
 *
 * 2. Accounting Core validates the JWT signature,
 *    issuer, audience, expiration, and PostgreSQL
 *    authentication session.
 *
 * 3. BusinessAuthorizationManager checks the user's
 *    current role for the requested business.
 *
 * 4. Only successfully authorized requests reach
 *    this controller.
 *
 * 5. The reporting revision is retrieved from
 *    Accounting Core's authoritative PostgreSQL primary.
 *
 * IMPORTANT:
 *
 * This controller does not:
 *
 * - Issue new access tokens.
 * - Accept browser-supplied user identities.
 * - Modify business memberships.
 * - Query financial ledger entries.
 * - Bypass the existing Spring Security filter chain.
 * - Retrieve reporting revisions from Redis.
 * - Retrieve authoritative revisions from a read replica.
 *
 * A successful authorization returns HTTP 204 with
 * the current reporting revision in a response header.
 */
@RestController
@RequestMapping("/api/v1/businesses")
public class ReportingAccessController {

    // ========================================================
    // RESPONSE HEADER CONSTANT
    // ========================================================

    /**
     * Header carrying the authoritative financial
     * reporting revision for an authorized business.
     *
     * The Reporting Service uses this value to construct
     * revision-aware cache keys.
     *
     * A revision of zero is valid for a new business.
     */
    public static final String REPORTING_REVISION_HEADER =
        "X-LedgerBase-Reporting-Revision";

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    private final BusinessReportingRevisionService
        reportingRevisionService;

    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public ReportingAccessController(
        BusinessReportingRevisionService reportingRevisionService
    ) {

        this.reportingRevisionService =
            reportingRevisionService;
    }

    // ========================================================
    // VERIFY REPORTING READ PERMISSION
    // ========================================================

    /**
     * Confirm that the authenticated user can read
     * financial reports for the requested business.
     *
     * Expected HTTP responses:
     *
     * 204 — Valid session and permitted business access.
     *       Includes authoritative reporting revision.
     *
     * 401 — Missing, invalid, expired, or revoked token.
     *
     * 403 — Authenticated user lacks business access.
     *
     * No response body is required.
     *
     * The businessId path variable is evaluated by
     * BusinessAuthorizationManager before this method
     * executes.
     *
     * REVISION CONSISTENCY:
     *
     * The reporting revision is read from PostgreSQL
     * through BusinessReportingRevisionService.
     *
     * Reporting Service must never derive its authoritative
     * revision from the read replica because replication
     * may lag behind a recently committed write.
     *
     * HTTP CACHING:
     *
     * Authorization results and revision headers must
     * not be cached by HTTP intermediaries.
     *
     * Redis report caching will be handled separately.
     *
     * @param businessId authorized business identifier
     * @return empty HTTP 204 response with revision header
     */
    @GetMapping("/{businessId}/reporting-access")
    public ResponseEntity<Void> verifyReportingAccess(
        @PathVariable("businessId") Long businessId
    ) {

        // ====================================================
        // READ AUTHORITATIVE POSTGRESQL REVISION
        // ====================================================

        /*
         * Spring Security has already authenticated
         * the request and authorized access to businessId.
         *
         * A missing business or unavailable revision must
         * fail rather than returning a fabricated value.
         */
        long reportingRevision =
            reportingRevisionService.getCurrentRevision(
                businessId
            );

        // ====================================================
        // RETURN AUTHORIZATION AND REVISION
        // ====================================================

        /*
         * Keep the existing HTTP 204 response contract.
         *
         * The response body remains empty.
         *
         * Include the authoritative reporting revision
         * for version-aware financial report caching.
         *
         * Cache-Control: no-store prevents intermediaries
         * from reusing potentially stale authorization
         * decisions or reporting revision values.
         */
        return ResponseEntity
            .noContent()
            .header(
                REPORTING_REVISION_HEADER,
                Long.toString(reportingRevision)
            )
            .cacheControl(CacheControl.noStore())
            .build();
    }
}
