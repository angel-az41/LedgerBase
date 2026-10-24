package com.ledgerbase.reporting.controller;

import com.ledgerbase.reporting.model.TrialBalanceReport;

import com.ledgerbase.reporting.security.AccountingCoreAuthorizationClient;

import com.ledgerbase.reporting.service.ReplicaReportingRevisionVerifier;
import com.ledgerbase.reporting.service.ReplicaTrialBalanceSnapshotService;
import com.ledgerbase.reporting.service.TrialBalanceCacheService;

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
 * Exposes Trial Balance financial reports through the
 * independent Reporting Service.
 *
 * API:
 *
 * GET /api/v1/businesses/{businessId}/reports/trial-balance
 *
 * Query parameter:
 *
 * asOfDate=YYYY-MM-DD
 *
 * SECURITY:
 *
 * 1. Spring Security verifies the JWT.
 * 2. BusinessAuthorizationManager verifies reporting access.
 * 3. Accounting Core supplies the authoritative revision.
 *
 * CACHING:
 *
 * Cached reports use the current authorized primary
 * reporting revision as part of their Redis key.
 *
 * A cache hit does not need another database read.
 *
 * A cache miss triggers:
 *
 * 1. Replica freshness verification.
 * 2. REPEATABLE READ report snapshot.
 * 3. Redis population under the snapshot revision.
 *
 * Redis unavailability does not bypass authorization
 * or PostgreSQL replica consistency checks.
 *
 * The public TrialBalanceReport JSON contract
 * remains unchanged.
 */
@RestController
@RequestMapping(
    "/api/v1/businesses/{businessId}/reports"
)
public class TrialBalanceController {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    private final AccountingCoreAuthorizationClient
        authorizationClient;

    private final ReplicaReportingRevisionVerifier
        replicaRevisionVerifier;

    private final ReplicaTrialBalanceSnapshotService
        snapshotService;

    private final TrialBalanceCacheService cacheService;

    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public TrialBalanceController(
        AccountingCoreAuthorizationClient authorizationClient,
        ReplicaReportingRevisionVerifier replicaRevisionVerifier,
        ReplicaTrialBalanceSnapshotService snapshotService,
        TrialBalanceCacheService cacheService
    ) {

        this.authorizationClient = authorizationClient;

        this.replicaRevisionVerifier =
            replicaRevisionVerifier;

        this.snapshotService = snapshotService;

        this.cacheService = cacheService;
    }

    // ========================================================
    // GET TRIAL BALANCE
    // ========================================================

    /**
     * Return the Trial Balance for an authorized business.
     *
     * The caller never supplies a reporting revision.
     *
     * The revision is retrieved from Accounting Core
     * using the verified JWT for this request.
     *
     * The cache key therefore reflects the current
     * authorized accounting state.
     *
     * @param businessId requested business
     * @param asOfDate inclusive reporting date
     * @param jwt authenticated JWT principal
     * @return existing TrialBalanceReport response
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
        // STEP 1 — REQUIRE AUTHENTICATED JWT
        // ====================================================

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
        // STEP 2 — FETCH AUTHORIZED PRIMARY REVISION
        // ====================================================

        /*
         * The ReportingBusinessAuthorizationManager
         * already checks access before controller entry.
         *
         * This call obtains the current primary revision
         * through the same authenticated Core endpoint.
         *
         * The revision is never accepted from a browser.
         */
        OptionalLong authorizedRevision =
            authorizationClient.getAuthorizedReportingRevision(
                businessId,
                jwt.getTokenValue()
            );

        // ====================================================
        // STEP 3 — FAIL CLOSED WITHOUT AUTHORIZED REVISION
        // ====================================================

        if (authorizedRevision.isEmpty()) {

            /*
             * A missing revision is not revision zero.
             *
             * Do not consult Redis or read financial data
             * unless Accounting Core has supplied a
             * valid, authorized version.
             */
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Unable to verify authorized reporting revision"
            );
        }

        long requiredRevision =
            authorizedRevision.getAsLong();

        // ====================================================
        // STEP 4 — CACHE-ASIDE REPORT RETRIEVAL
        // ====================================================

        /*
         * The cache service first searches Redis using
         * the current authorized revision.
         *
         * CACHE HIT:
         *
         * Return the previously verified versioned report.
         *
         * CACHE MISS:
         *
         * Execute the supplier below.
         *
         * The supplier verifies replica freshness and
         * generates a REPEATABLE READ snapshot.
         *
         * Snapshot execution must occur through the
         * Spring-managed service bean so that its
         * transactional proxy remains active.
         */
        return cacheService.getOrGenerate(
            businessId,
            asOfDate,
            requiredRevision,

            () -> {

                // --------------------------------------------
                // REQUIRE SUFFICIENT WAL REPLAY
                // --------------------------------------------

                /*
                 * A cache miss must not read a replica
                 * known to be behind the primary revision.
                 */
                replicaRevisionVerifier.awaitRequiredRevision(
                    businessId,
                    requiredRevision
                );

                // --------------------------------------------
                // GENERATE CONSISTENT FINANCIAL SNAPSHOT
                // --------------------------------------------

                /*
                 * The snapshot service independently
                 * verifies its actual revision inside
                 * a REPEATABLE READ transaction.
                 *
                 * The returned revision may be newer
                 * than the initially required revision.
                 */
                return snapshotService.generateSnapshot(
                    businessId,
                    asOfDate,
                    requiredRevision
                );
            }
        );
    }
}
