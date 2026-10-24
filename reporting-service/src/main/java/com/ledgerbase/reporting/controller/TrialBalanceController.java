package com.ledgerbase.reporting.controller;

import com.ledgerbase.reporting.model.TrialBalanceReport;

import com.ledgerbase.reporting.security.AccountingCoreAuthorizationClient;

import com.ledgerbase.reporting.service.ReplicaReportingRevisionVerifier;
import com.ledgerbase.reporting.service.TrialBalanceService;

import org.springframework.format.annotation.DateTimeFormat;

import org.springframework.http.HttpStatus;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.OptionalLong;

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
 * REPLICA CONSISTENCY:
 *
 * Before generating a report, this controller:
 *
 * 1. Requests the authoritative business revision
 *    through Accounting Core's authorized endpoint.
 *
 * 2. Checks that the PostgreSQL read replica has
 *    replayed at least that revision.
 *
 * 3. Generates the Trial Balance only after the
 *    required revision becomes visible.
 *
 * This prevents a known-stale replica from returning
 * outdated financial balances after recent posting.
 *
 * If Accounting Core cannot verify the revision,
 * or the replica cannot catch up, report generation
 * fails safely.
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
    // SERVICE DEPENDENCIES
    // ========================================================

    /**
     * Existing financial reporting implementation.
     *
     * All accounting calculations remain inside
     * TrialBalanceService and the MyBatis mapper.
     */
    private final TrialBalanceService trialBalanceService;

    /**
     * Calls Accounting Core using the requesting user's
     * authenticated JWT.
     *
     * Returns the authoritative PostgreSQL primary
     * revision only after Accounting Core authorizes
     * the request.
     */
    private final AccountingCoreAuthorizationClient
        authorizationClient;

    /**
     * Verifies that Reporting Service's PostgreSQL
     * read replica has replayed the required revision.
     */
    private final ReplicaReportingRevisionVerifier
        replicaRevisionVerifier;

    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public TrialBalanceController(
        TrialBalanceService trialBalanceService,
        AccountingCoreAuthorizationClient authorizationClient,
        ReplicaReportingRevisionVerifier replicaRevisionVerifier
    ) {

        this.trialBalanceService = trialBalanceService;

        this.authorizationClient = authorizationClient;

        this.replicaRevisionVerifier =
            replicaRevisionVerifier;
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
     * SECURITY:
     *
     * The ReportingBusinessAuthorizationManager already
     * checks the JWT and business membership before
     * this controller executes.
     *
     * We perform another Accounting Core request here
     * to retrieve a current authorized reporting revision.
     *
     * This ensures we never accept an untrusted revision
     * supplied through a query parameter or request header.
     *
     * @param businessId authorized business identifier
     * @param asOfDate reporting cutoff date
     * @param jwt verified JWT from Spring Security
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
        LocalDate asOfDate,

        @AuthenticationPrincipal
        Jwt jwt
    ) {

        // ====================================================
        // STEP 1 — REQUIRE AN AUTHENTICATED JWT
        // ====================================================

        /*
         * Spring Security normally guarantees this before
         * the controller is invoked.
         *
         * The additional check prevents accidental
         * authorization bypass if this method is later
         * invoked outside the expected security context.
         */
        if (
            jwt == null
            || jwt.getTokenValue() == null
            || jwt.getTokenValue().isBlank()
        ) {

            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Authenticated JWT is required"
            );
        }

        // ====================================================
        // STEP 2 — RETRIEVE AUTHORIZED PRIMARY REVISION
        // ====================================================

        /*
         * This invokes the existing Accounting Core
         * reporting-access endpoint:
         *
         * GET /api/v1/businesses/{businessId}/reporting-access
         *
         * Accounting Core verifies the session and
         * business membership, then returns HTTP 204
         * with X-LedgerBase-Reporting-Revision.
         *
         * A missing or invalid revision must never
         * be interpreted as revision zero.
         */
        OptionalLong authorizedRevision =
            authorizationClient.getAuthorizedReportingRevision(
                businessId,
                jwt.getTokenValue()
            );

        // ====================================================
        // STEP 3 — FAIL IF REVISION IS UNAVAILABLE
        // ====================================================

        if (authorizedRevision.isEmpty()) {

            /*
             * The revision could not be established.
             *
             * Possible causes include:
             *
             * - Accounting Core is unavailable.
             * - Authentication or membership changed.
             * - The revision response header is missing.
             * - The upstream response is malformed.
             *
             * Do not generate the report.
             *
             * The existing authorization manager also
             * fails closed on denied business access.
             */
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Unable to verify authorized reporting revision"
            );
        }

        long requiredRevision =
            authorizedRevision.getAsLong();

        // ====================================================
        // STEP 4 — WAIT FOR REPLICA FRESHNESS
        // ====================================================

        /*
         * The replica may lag after a newly posted
         * financial transaction.
         *
         * Do not read financial balances until the
         * required committed revision has been replayed.
         *
         * This method throws HTTP 503 if the replica
         * remains behind or becomes unavailable.
         */
        replicaRevisionVerifier.awaitRequiredRevision(
            businessId,
            requiredRevision
        );

        // ====================================================
        // STEP 5 — GENERATE THE ORIGINAL TRIAL BALANCE
        // ====================================================

        /*
         * The freshness verification has succeeded.
         *
         * Delegate to the existing reporting service,
         * preserving:
         *
         * - The original SQL query.
         * - POSTED-only ledger filtering.
         * - As-of-date filtering.
         * - Zero-balance account inclusion.
         * - BigDecimal calculations.
         * - The existing TrialBalanceReport JSON structure.
         *
         * Redis caching is not enabled in this step.
         */
        return trialBalanceService.generateTrialBalance(
            businessId,
            asOfDate
        );
    }
}
