package com.ledgerbase.reporting.controller;

import com.ledgerbase.reporting.model.TrialBalanceReport;

import com.ledgerbase.reporting.security.AccountingCoreAuthorizationClient;

import com.ledgerbase.reporting.service.ReplicaReportingRevisionVerifier;
import com.ledgerbase.reporting.service.ReplicaTrialBalanceSnapshotService;

import com.ledgerbase.reporting.service
    .ReplicaTrialBalanceSnapshotService.VersionedTrialBalanceReport;

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
 * REPLICA CONSISTENCY:
 *
 * 1. Obtain the authorized revision from Accounting Core.
 * 2. Wait for the replica to reach that revision.
 * 3. Generate a consistent REPEATABLE READ snapshot.
 * 4. Recheck the required revision inside that snapshot.
 * 5. Return the financial report.
 *
 * The snapshot's exact revision is also available
 * for future Redis version-aware caching.
 *
 * The public JSON response remains unchanged.
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

    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public TrialBalanceController(
        AccountingCoreAuthorizationClient authorizationClient,
        ReplicaReportingRevisionVerifier replicaRevisionVerifier,
        ReplicaTrialBalanceSnapshotService snapshotService
    ) {

        this.authorizationClient = authorizationClient;

        this.replicaRevisionVerifier =
            replicaRevisionVerifier;

        this.snapshotService = snapshotService;
    }

    // ========================================================
    // GET TRIAL BALANCE
    // ========================================================

    /**
     * Return a Trial Balance for an authorized business.
     *
     * The returned JSON is still TrialBalanceReport,
     * so no React frontend changes are required.
     *
     * @param businessId requested business
     * @param asOfDate inclusive reporting cutoff
     * @param jwt authenticated JWT principal
     * @return existing Trial Balance report structure
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
        // STEP 2 — OBTAIN AUTHORIZED PRIMARY REVISION
        // ====================================================

        /*
         * This request uses the authenticated JWT.
         *
         * Accounting Core performs its existing
         * session and business membership checks.
         */
        OptionalLong authorizedRevision =
            authorizationClient.getAuthorizedReportingRevision(
                businessId,
                jwt.getTokenValue()
            );

        if (authorizedRevision.isEmpty()) {

            /*
             * Never assume a missing revision is zero.
             *
             * Failed upstream authorization and
             * communication errors must not lead
             * to financial report generation.
             */
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Unable to verify authorized reporting revision"
            );
        }

        long requiredRevision =
            authorizedRevision.getAsLong();

        // ====================================================
        // STEP 3 — WAIT FOR REPLICA WAL REPLAY
        // ====================================================

        /*
         * Poll outside the snapshot transaction.
         *
         * This allows each poll to see newer
         * PostgreSQL WAL replay progress.
         */
        replicaRevisionVerifier.awaitRequiredRevision(
            businessId,
            requiredRevision
        );

        // ====================================================
        // STEP 4 — GENERATE CONSISTENT REPORT SNAPSHOT
        // ====================================================

        /*
         * The snapshot service creates a separate
         * REPEATABLE READ transaction.
         *
         * It reads the replica revision and the
         * financial report from the same MVCC snapshot.
         *
         * It also independently verifies that the
         * snapshot satisfies requiredRevision.
         */
        VersionedTrialBalanceReport snapshot =
            snapshotService.generateSnapshot(
                businessId,
                asOfDate,
                requiredRevision
            );

        // ====================================================
        // STEP 5 — PRESERVE ORIGINAL HTTP RESPONSE
        // ====================================================

        /*
         * Redis caching will later use snapshot.revision().
         *
         * For now, return only the original financial report.
         *
         * React continues receiving the same JSON schema.
         */
        return snapshot.report();
    }
}
