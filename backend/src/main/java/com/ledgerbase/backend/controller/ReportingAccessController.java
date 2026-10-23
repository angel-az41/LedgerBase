package com.ledgerbase.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// ============================================================
// LEDGERBASE — REPORTING ACCESS CONTROLLER
// ============================================================

/**
 * Provides a lightweight authorization-check endpoint
 * for the independent financial Reporting Service.
 *
 * PURPOSE:
 *
 * The Reporting Service must confirm that a requesting
 * user has an active authentication session and current
 * access to the requested business.
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
 * IMPORTANT:
 *
 * This controller does not:
 *
 * - Issue new access tokens.
 * - Accept browser-supplied user identities.
 * - Modify business memberships.
 * - Query financial ledger entries.
 * - Bypass the existing Spring Security filter chain.
 *
 * Authorization is enforced by Accounting Core's
 * existing business-scoped security configuration.
 *
 * A successful authorization returns HTTP 204.
 */
@RestController
@RequestMapping("/api/v1/businesses")
public class ReportingAccessController {

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
     * @return empty HTTP 204 response
     */
    @GetMapping("/{businessId}/reporting-access")
    public ResponseEntity<Void> verifyReportingAccess() {

        return ResponseEntity.noContent().build();
    }
}
