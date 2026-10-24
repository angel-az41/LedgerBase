package com.ledgerbase.reporting.service;

import com.ledgerbase.reporting.model.TrialBalanceReport;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Objects;

// ============================================================
// LEDGERBASE — REPLICA TRIAL BALANCE SNAPSHOT SERVICE
// ============================================================

/**
 * Generate a financial report and capture the reporting
 * revision belonging to the same PostgreSQL snapshot.
 *
 * PURPOSE:
 *
 * Prevent financial reports from being associated with
 * an incorrect Redis cache version.
 *
 * BACKGROUND:
 *
 * PostgreSQL streaming replication is asynchronous.
 *
 * Accounting Core supplies the authoritative revision
 * after verifying authentication and business access.
 *
 * Reporting Service first waits until the replica
 * reaches that minimum revision.
 *
 * However, another transaction might commit during
 * the actual financial report generation.
 *
 * A normal READ COMMITTED transaction could observe
 * different database snapshots across statements.
 *
 * SOLUTION:
 *
 * Read both:
 *
 * 1. businesses.reporting_revision
 * 2. Trial Balance ledger rows
 *
 * inside one REPEATABLE READ transaction.
 *
 * PostgreSQL guarantees that both queries observe
 * one consistent database snapshot.
 *
 * The revision returned with the report therefore
 * identifies the financial data used to generate it,
 * assuming all report-affecting writes advance the
 * business revision transactionally.
 *
 * DATABASE:
 *
 * Both JdbcTemplate and TrialBalanceService's MyBatis
 * mapper must use the same Reporting Service datasource
 * and Spring transaction manager.
 *
 * That datasource connects exclusively to the
 * read-only PostgreSQL replica.
 *
 * No fallback to the writable primary is allowed.
 *
 * SECURITY:
 *
 * Authorization occurs before this service is invoked.
 *
 * The required revision is obtained from Accounting Core
 * using the authenticated user's JWT.
 *
 * This class does not accept browser-supplied revisions.
 */
@Service
public class ReplicaTrialBalanceSnapshotService {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    private final JdbcTemplate jdbcTemplate;

    private final TrialBalanceService trialBalanceService;

    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public ReplicaTrialBalanceSnapshotService(
        JdbcTemplate jdbcTemplate,
        TrialBalanceService trialBalanceService
    ) {

        this.jdbcTemplate = jdbcTemplate;

        this.trialBalanceService = trialBalanceService;
    }

    // ========================================================
    // GENERATE VERSIONED TRIAL BALANCE SNAPSHOT
    // ========================================================

    /**
     * Generate a Trial Balance and its database revision
     * within one consistent PostgreSQL snapshot.
     *
     * REPEATABLE_READ:
     *
     * PostgreSQL uses the same MVCC snapshot for both
     * the revision query and the financial reporting SQL.
     *
     * REQUIRES_NEW:
     *
     * Explicitly establish a new transaction rather than
     * accidentally joining a caller's transaction using
     * a weaker isolation level.
     *
     * IMPORTANT:
     *
     * The method must be called through its Spring
     * service bean for the transactional proxy to apply.
     *
     * If the snapshot revision is still behind the
     * authorized required revision, fail with HTTP 503.
     *
     * @param businessId authorized business identifier
     * @param asOfDate inclusive report cutoff date
     * @param requiredRevision minimum authoritative revision
     * @return report and actual replica snapshot revision
     */
    @Transactional(
        readOnly = true,
        isolation = Isolation.REPEATABLE_READ,
        propagation = Propagation.REQUIRES_NEW
    )
    public VersionedTrialBalanceReport generateSnapshot(
        Long businessId,
        LocalDate asOfDate,
        long requiredRevision
    ) {

        // ====================================================
        // VALIDATE INPUTS
        // ====================================================

        if (businessId == null || businessId <= 0) {

            throw new IllegalArgumentException(
                "A valid business ID is required"
            );
        }

        if (asOfDate == null) {

            throw new IllegalArgumentException(
                "Trial Balance reporting date is required"
            );
        }

        if (asOfDate.isAfter(LocalDate.now())) {

            throw new IllegalArgumentException(
                "Trial Balance reporting date cannot be in the future"
            );
        }

        if (requiredRevision < 0) {

            throw new IllegalArgumentException(
                "Required reporting revision cannot be negative"
            );
        }

        // ====================================================
        // STEP 1 — READ REVISION INSIDE REPEATABLE READ
        // ====================================================

        final Long snapshotRevision;

        try {

            /*
             * This SELECT establishes the transaction's
             * consistent PostgreSQL MVCC snapshot.
             *
             * Subsequent financial queries participating
             * in this same transaction observe the
             * same committed database state.
             */
            snapshotRevision =
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

            /*
             * Replica connection errors and missing
             * business rows must never result in
             * fabricated revision values.
             */
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Unable to read reporting snapshot revision",
                exception
            );
        }

        // ====================================================
        // STEP 2 — VERIFY SNAPSHOT FRESHNESS
        // ====================================================

        /*
         * Recheck freshness INSIDE this transaction.
         *
         * The previous replica polling check runs
         * outside the reporting transaction.
         *
         * This second check guarantees that the
         * snapshot used by the financial report meets
         * the required authoritative revision.
         */
        if (
            snapshotRevision == null
            || snapshotRevision < requiredRevision
        ) {

            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Reporting snapshot is behind "
                    + "the required financial revision"
            );
        }

        // ====================================================
        // STEP 3 — GENERATE REPORT FROM SAME SNAPSHOT
        // ====================================================

        /*
         * TrialBalanceService is another Spring bean.
         *
         * Its existing @Transactional(readOnly = true)
         * method participates in the current transaction
         * because its default propagation is REQUIRED.
         *
         * Therefore the MyBatis financial query observes
         * the same PostgreSQL snapshot as the revision
         * query above.
         *
         * Do not start a separate reporting transaction here.
         */
        TrialBalanceReport report =
            trialBalanceService.generateTrialBalance(
                businessId,
                asOfDate
            );

        // ====================================================
        // STEP 4 — RETURN VERSIONED REPORT
        // ====================================================

        /*
         * The actual replica revision may be HIGHER
         * than the initially authorized minimum.
         *
         * Always associate the report with the revision
         * observed INSIDE the same transaction.
         *
         * Do not label this report using requiredRevision
         * unless both revisions actually match.
         */
        return new VersionedTrialBalanceReport(
            snapshotRevision,
            report
        );
    }

    // ========================================================
    // IMMUTABLE VERSIONED REPORT
    // ========================================================

    /**
     * Internal result for consistent report caching.
     *
     * revision:
     *   Exact business revision observed by the
     *   PostgreSQL reporting snapshot.
     *
     * report:
     *   Financial report produced using that same snapshot.
     *
     * The controller returns only report to preserve
     * the existing external JSON API contract.
     *
     * The Redis implementation will later use revision
     * to construct a versioned cache key.
     */
    public record VersionedTrialBalanceReport(
        long revision,
        TrialBalanceReport report
    ) {

        public VersionedTrialBalanceReport {

            if (revision < 0) {

                throw new IllegalArgumentException(
                    "Snapshot revision cannot be negative"
                );
            }

            Objects.requireNonNull(
                report,
                "Trial Balance report cannot be null"
            );
        }
    }
}
