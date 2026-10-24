package com.ledgerbase.reporting.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

// ============================================================
// LEDGERBASE — REPLICA REPORTING REVISION VERIFIER
// ============================================================

/**
 * Verify that the PostgreSQL read replica has replayed
 * the financial reporting changes required by a request.
 *
 * CONTEXT:
 *
 * Accounting Core reads the authoritative reporting
 * revision from the PostgreSQL primary.
 *
 * Reporting Service receives that revision only after
 * Accounting Core verifies the requesting user's JWT,
 * authentication session, and business membership.
 *
 * PostgreSQL WAL streaming replication is asynchronous.
 *
 * A recently committed accounting change may therefore
 * be visible on the primary before it reaches the replica.
 *
 * RESPONSIBILITIES:
 *
 * - Read the replica's reporting_revision.
 * - Compare it with the authorized primary revision.
 * - Retry briefly if the replica is behind.
 * - Reject requests if the replica cannot catch up.
 * - Never switch to the writable primary.
 *
 * CONSISTENCY REQUIREMENT:
 *
 * replicaRevision >= requiredPrimaryRevision
 *
 * The reporting_revision column is updated inside the
 * same PostgreSQL transaction as accounting changes.
 *
 * Once the corresponding transaction is visible on the
 * replica, its ledger changes have been replayed as well.
 *
 * IMPORTANT:
 *
 * Revision checks must not be performed inside a
 * long-lived repeatable-read transaction while polling.
 *
 * Each poll executes a separate JDBC statement so
 * subsequent attempts can observe WAL replay progress.
 *
 * This verifier protects the uncached reporting read path.
 *
 * Exact snapshot versioning for Redis cache entries
 * will be implemented in the caching checkpoint.
 */
@Service
public class ReplicaReportingRevisionVerifier {

    private static final Logger log =
        LoggerFactory.getLogger(
            ReplicaReportingRevisionVerifier.class
        );

    // ========================================================
    // REPLICA POLLING CONFIGURATION
    // ========================================================

    /**
     * Maximum number of database revision observations
     * before reporting that the replica is unavailable
     * for a sufficiently fresh read.
     */
    private static final int MAX_ATTEMPTS = 8;

    /**
     * Delay between attempts in milliseconds.
     *
     * Eight attempts produce at most seven delays,
     * totaling approximately 525 milliseconds
     * excluding database query execution time.
     */
    private static final long RETRY_DELAY_MS = 75L;

    // ========================================================
    // REPLICA DATABASE CONNECTION
    // ========================================================

    /**
     * Reporting Service's JdbcTemplate is configured
     * to connect to the PostgreSQL read-only replica.
     *
     * This is the same datasource verified during
     * application startup by ReplicaConnectionVerifier.
     */
    private final JdbcTemplate jdbcTemplate;

    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public ReplicaReportingRevisionVerifier(
        JdbcTemplate jdbcTemplate
    ) {

        this.jdbcTemplate = jdbcTemplate;
    }

    // ========================================================
    // WAIT FOR REQUIRED REPLICA REVISION
    // ========================================================

    /**
     * Wait until the replica has replayed at least the
     * reporting revision authorized by Accounting Core.
     *
     * A revision of zero is valid.
     *
     * If the replica is behind, retry for a bounded
     * number of attempts.
     *
     * A database error, interrupted wait, or exhausted
     * retry budget results in HTTP 503.
     *
     * No financial report may be generated when this
     * verification fails.
     *
     * @param businessId authorized business identifier
     * @param requiredRevision authoritative primary revision
     */
    public void awaitRequiredRevision(
        Long businessId,
        long requiredRevision
    ) {

        // ====================================================
        // VALIDATE INPUTS
        // ====================================================

        if (
            businessId == null
            || businessId <= 0
        ) {
            throw new IllegalArgumentException(
                "A valid business ID is required"
            );
        }

        if (requiredRevision < 0) {
            throw new IllegalArgumentException(
                "Reporting revision cannot be negative"
            );
        }

        // ====================================================
        // POLL REPLICA REVISION
        // ====================================================

        Long observedRevision = null;

        for (
            int attempt = 1;
            attempt <= MAX_ATTEMPTS;
            attempt++
        ) {

            // ------------------------------------------------
            // READ THE REPLICA'S CURRENT REVISION
            // ------------------------------------------------

            try {

                /*
                 * JdbcTemplate uses Reporting Service's
                 * replica datasource.
                 *
                 * Each query obtains a fresh statement-level
                 * snapshot under PostgreSQL's default
                 * READ COMMITTED isolation.
                 *
                 * Do not wrap this polling loop in a
                 * repeatable-read transaction.
                 */
                observedRevision =
                    jdbcTemplate.queryForObject(
                        """
                        SELECT reporting_revision
                        FROM businesses
                        WHERE id = ?
                        """,
                        Long.class,
                        businessId
                    );

            } catch (DataAccessException exception) {

                // --------------------------------------------
                // REPLICA DATABASE QUERY FAILED
                // --------------------------------------------

                /*
                 * This includes:
                 *
                 * - Database connection failures.
                 * - Missing SELECT privileges.
                 * - Missing business records.
                 * - Other PostgreSQL query failures.
                 *
                 * Never fall back to an older revision.
                 */
                log.warn(
                    "Replica revision lookup failed: "
                        + "businessId={}, errorType={}",
                    businessId,
                    exception.getClass().getSimpleName()
                );

                throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Unable to verify reporting replica freshness",
                    exception
                );
            }

            // ------------------------------------------------
            // CHECK WHETHER THE REPLICA HAS CAUGHT UP
            // ------------------------------------------------

            if (
                observedRevision != null
                && observedRevision >= requiredRevision
            ) {

                // Successful verification.
                if (attempt > 1) {

                    log.info(
                        "Reporting replica caught up: "
                            + "businessId={}, requiredRevision={}, "
                            + "observedRevision={}, attempts={}",
                        businessId,
                        requiredRevision,
                        observedRevision,
                        attempt
                    );
                }

                return;
            }

            // ------------------------------------------------
            // STOP AFTER FINAL ATTEMPT
            // ------------------------------------------------

            if (attempt == MAX_ATTEMPTS) {
                break;
            }

            // ------------------------------------------------
            // WAIT BRIEFLY FOR ADDITIONAL WAL REPLAY
            // ------------------------------------------------

            try {

                Thread.sleep(RETRY_DELAY_MS);

            } catch (InterruptedException exception) {

                /*
                 * Preserve the thread's interrupt status.
                 *
                 * An interrupted request must not continue
                 * processing or generate a financial report.
                 */
                Thread.currentThread().interrupt();

                throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Reporting replica freshness check interrupted",
                    exception
                );
            }
        }

        // ====================================================
        // REPLICA DID NOT CATCH UP
        // ====================================================

        log.warn(
            "Reporting replica remains behind: "
                + "businessId={}, requiredRevision={}, "
                + "observedRevision={}, attempts={}",
            businessId,
            requiredRevision,
            observedRevision,
            MAX_ATTEMPTS
        );

        // ====================================================
        // FAIL CLOSED
        // ====================================================

        /*
         * Financial reporting must not silently return
         * an older replica snapshot when the requested
         * revision has not been replayed.
         *
         * HTTP 503 indicates temporary unavailability.
         *
         * The client may retry the request later.
         */
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Reporting replica has not reached "
                + "the required financial revision"
        );
    }
}
