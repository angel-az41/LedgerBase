package com.ledgerbase.reporting.service;

import com.ledgerbase.reporting.model.TrialBalanceReport;
import com.ledgerbase.reporting.model.TrialBalanceRow;

import com.ledgerbase.reporting.service
    .ReplicaTrialBalanceSnapshotService.VersionedTrialBalanceReport;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

// ============================================================
// LEDGERBASE — TRIAL BALANCE REDIS CACHE SERVICE
// ============================================================

/**
 * Cache Trial Balance reports in Redis Cluster using
 * PostgreSQL reporting revisions.
 *
 * ARCHITECTURE:
 *
 * Accounting Core:
 *   Authenticates JWTs and authorizes business access.
 *   Supplies the authoritative primary revision.
 *
 * Reporting Service:
 *   Looks up reports by authorized revision.
 *   On a miss, verifies replica freshness and generates
 *   a consistent PostgreSQL reporting snapshot.
 *
 * Redis Cluster:
 *   Stores account-level report data using versioned keys.
 *
 * SECURITY:
 *
 * A caller must obtain its reporting revision from the
 * authenticated Accounting Core authorization endpoint.
 *
 * This service must never receive an arbitrary revision
 * directly from a browser query parameter.
 *
 * CONSISTENCY:
 *
 * A cached result is returned only when its revision
 * exactly matches the required authorized revision.
 *
 * On a miss, the snapshot may observe a newer revision
 * than the initial authorization request.
 *
 * In that case, the report is cached under the actual
 * snapshot revision, never the older required revision.
 *
 * FAILURE POLICY:
 *
 * Redis is optional.
 *
 * If Redis is unavailable, the service falls back to
 * the verified PostgreSQL replica snapshot.
 *
 * Failure to verify PostgreSQL replica freshness is
 * NOT ignored.
 *
 * Old cache entries expire through Redis TTL.
 *
 * We never scan Redis keys or delete financial records.
 */
@Service
public class TrialBalanceCacheService {

    private static final Logger log =
        LoggerFactory.getLogger(
            TrialBalanceCacheService.class
        );

    // ========================================================
    // CACHE FORMAT AND KEY NAMESPACE
    // ========================================================

    /**
     * Increment CACHE_SCHEMA_VERSION when the serialized
     * cache payload format changes incompatibly.
     *
     * The corresponding Redis key prefix also includes v1.
     */
    private static final int CACHE_SCHEMA_VERSION = 1;

    private static final String CACHE_KEY_PREFIX =
        "ledgerbase:report:trial-balance:v1:";

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    private final StringRedisTemplate redisTemplate;

    private final JsonMapper jsonMapper;

    private final MeterRegistry meterRegistry;

    // ========================================================
    // CACHE CONFIGURATION
    // ========================================================

    private final boolean enabled;

    private final Duration ttl;

    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public TrialBalanceCacheService(
        StringRedisTemplate redisTemplate,
        JsonMapper jsonMapper,
        MeterRegistry meterRegistry,

        @Value(
            "${ledgerbase.cache.trial-balance.enabled:false}"
        )
        boolean enabled,

        @Value(
            "${ledgerbase.cache.trial-balance.ttl:PT5M}"
        )
        Duration ttl
    ) {

        this.redisTemplate =
            Objects.requireNonNull(redisTemplate);

        this.jsonMapper =
            Objects.requireNonNull(jsonMapper);

        this.meterRegistry =
            Objects.requireNonNull(meterRegistry);

        this.enabled = enabled;

        if (
            ttl == null
            || ttl.isNegative()
            || ttl.isZero()
        ) {
            throw new IllegalArgumentException(
                "Trial Balance cache TTL must be positive"
            );
        }

        this.ttl = ttl;
    }

    // ========================================================
    // CACHE-ASIDE REPORT RETRIEVAL
    // ========================================================

    /**
     * Retrieve a cached report or generate a fresh,
     * revision-consistent PostgreSQL snapshot.
     *
     * The snapshotLoader is executed only on:
     *
     * - Cache misses.
     * - Cache failures.
     * - Disabled caching.
     *
     * The supplier must:
     *
     * 1. Verify replica freshness.
     * 2. Generate a REPEATABLE READ report snapshot.
     * 3. Return the actual snapshot revision and report.
     *
     * @param businessId authorized business
     * @param asOfDate report cutoff date
     * @param requiredRevision authorized primary revision
     * @param snapshotLoader safe PostgreSQL report loader
     * @return complete TrialBalanceReport
     */
    public TrialBalanceReport getOrGenerate(
        Long businessId,
        LocalDate asOfDate,
        long requiredRevision,
        Supplier<VersionedTrialBalanceReport> snapshotLoader
    ) {

        // ====================================================
        // VALIDATE REQUIRED PARAMETERS
        // ====================================================

        if (
            businessId == null
            || businessId <= 0
        ) {
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

        Objects.requireNonNull(
            snapshotLoader,
            "A PostgreSQL snapshot loader is required"
        );

        // ====================================================
        // CACHE DISABLED — USE EXISTING REPORTING PATH
        // ====================================================

        if (!enabled) {

            count("bypass");

            return loadVerifiedSnapshot(
                businessId,
                asOfDate,
                requiredRevision,
                snapshotLoader
            ).report();
        }

        // ====================================================
        // BUILD AUTHORIZED REVISION KEY
        // ====================================================

        String lookupKey = buildKey(
            businessId,
            asOfDate,
            requiredRevision
        );

        // ====================================================
        // ATTEMPT REDIS CACHE READ
        // ====================================================

        try {

            String cachedJson =
                redisTemplate.opsForValue().get(
                    lookupKey
                );

            if (cachedJson != null) {

                // --------------------------------------------
                // DESERIALIZE CACHE ENVELOPE
                // --------------------------------------------

                CacheEnvelope envelope =
                    jsonMapper.readValue(
                        cachedJson,
                        CacheEnvelope.class
                    );

                // --------------------------------------------
                // VALIDATE CACHE IDENTITY AND VERSION
                // --------------------------------------------

                if (
                    envelope != null
                    && envelope.schemaVersion()
                        == CACHE_SCHEMA_VERSION
                    && envelope.businessId() == businessId
                    && envelope.asOfDate() != null
                    && envelope.asOfDate().equals(asOfDate)
                    && envelope.revision() == requiredRevision
                    && envelope.rows() != null
                ) {

                    // ----------------------------------------
                    // RECONSTRUCT ORIGINAL FINANCIAL REPORT
                    // ----------------------------------------

                    TrialBalanceReport report =
                        reconstructReport(
                            businessId,
                            asOfDate,
                            envelope.rows()
                        );

                    count("hit");

                    return report;
                }

                /*
                 * Invalid cache metadata is treated
                 * as a miss.
                 *
                 * Never return data from the wrong
                 * business, date, or revision.
                 */
                log.warn(
                    "Ignoring invalid Trial Balance cache entry: "
                        + "businessId={}, revision={}",
                    businessId,
                    requiredRevision
                );
            }

            count("miss");

        } catch (
            DataAccessException
            | JacksonException
            | IllegalArgumentException exception
        ) {

            // --------------------------------------------
            // CACHE READ FAILED — USE POSTGRESQL
            // --------------------------------------------

            /*
             * Redis connection errors and invalid cache
             * payloads must not make the financial report
             * endpoint unavailable.
             *
             * The PostgreSQL snapshot still requires
             * authorization and replica freshness.
             */
            count("read_error");

            log.warn(
                "Trial Balance cache read failed: "
                    + "businessId={}, errorType={}",
                businessId,
                exception.getClass().getSimpleName()
            );
        }

        // ====================================================
        // CACHE MISS — GENERATE CONSISTENT SNAPSHOT
        // ====================================================

        VersionedTrialBalanceReport snapshot =
            loadVerifiedSnapshot(
                businessId,
                asOfDate,
                requiredRevision,
                snapshotLoader
            );

        // ====================================================
        // CACHE UNDER THE ACTUAL SNAPSHOT REVISION
        // ====================================================

        /*
         * Important concurrency rule:
         *
         * requiredRevision may be 12, while a new
         * accounting transaction commits and the
         * replica advances to revision 13.
         *
         * The snapshot service may then return 13.
         *
         * Store that report under rev-13, not rev-12.
         */
        String storeKey = buildKey(
            businessId,
            asOfDate,
            snapshot.revision()
        );

        try {

            CacheEnvelope envelope =
                new CacheEnvelope(
                    CACHE_SCHEMA_VERSION,
                    businessId,
                    asOfDate,
                    snapshot.revision(),
                    snapshot.report().getRows()
                );

            String payload =
                jsonMapper.writeValueAsString(
                    envelope
                );

            // --------------------------------------------
            // WRITE WITH EXPIRATION
            // --------------------------------------------

            redisTemplate.opsForValue().set(
                storeKey,
                payload,
                ttl
            );

            count("store");

        } catch (
            DataAccessException
            | JacksonException exception
        ) {

            /*
             * The PostgreSQL report is already valid.
             *
             * Redis write failure must not invalidate
             * the financial result.
             */
            count("write_error");

            log.warn(
                "Trial Balance cache write failed: "
                    + "businessId={}, errorType={}",
                businessId,
                exception.getClass().getSimpleName()
            );
        }

        // ====================================================
        // RETURN VERIFIED POSTGRESQL REPORT
        // ====================================================

        return snapshot.report();
    }

    // ========================================================
    // EXECUTE AND VALIDATE DATABASE SNAPSHOT
    // ========================================================

    /**
     * Ensure the snapshot loader returned a report
     * whose revision satisfies Accounting Core's
     * required authoritative revision.
     *
     * This check is additional defense in depth.
     *
     * The snapshot service must also check the
     * replica revision inside its transaction.
     */
    private VersionedTrialBalanceReport loadVerifiedSnapshot(
        Long businessId,
        LocalDate asOfDate,
        long requiredRevision,
        Supplier<VersionedTrialBalanceReport> snapshotLoader
    ) {

        VersionedTrialBalanceReport snapshot =
            snapshotLoader.get();

        if (
            snapshot == null
            || snapshot.report() == null
            || snapshot.revision() < requiredRevision
        ) {

            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Unable to obtain a sufficiently fresh "
                    + "Trial Balance snapshot"
            );
        }

        return snapshot;
    }

    // ========================================================
    // RECONSTRUCT EXISTING TRIAL BALANCE REPORT
    // ========================================================

    /**
     * Rebuild the original TrialBalanceReport using
     * cached account-level rows.
     *
     * The report's totals use the same exact BigDecimal
     * aggregation logic as TrialBalanceService.
     *
     * This allows the public API to keep returning
     * the original Java model and JSON response.
     *
     * There is no need to deserialize the immutable
     * TrialBalanceReport directly.
     */
    private TrialBalanceReport reconstructReport(
        Long businessId,
        LocalDate asOfDate,
        List<TrialBalanceRow> rows
    ) {

        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;

        for (TrialBalanceRow row : rows) {

            if (row == null) {
                throw new IllegalArgumentException(
                    "Cached Trial Balance contains a null row"
                );
            }

            if (
                row.getDebitBalance() == null
                || row.getCreditBalance() == null
            ) {
                throw new IllegalArgumentException(
                    "Cached Trial Balance has missing balances"
                );
            }

            BigDecimal debit = row.getDebitBalance();
            BigDecimal credit = row.getCreditBalance();

            if (
                debit.signum() < 0
                || credit.signum() < 0
            ) {
                throw new IllegalArgumentException(
                    "Cached Trial Balance has negative "
                        + "display balances"
                );
            }

            totalDebits =
                totalDebits.add(debit);

            totalCredits =
                totalCredits.add(credit);
        }

        return new TrialBalanceReport(
            businessId,
            asOfDate,
            rows,
            totalDebits,
            totalCredits
        );
    }

    // ========================================================
    // BUILD REDIS CLUSTER CACHE KEY
    // ========================================================

    /**
     * Example:
     *
     * ledgerbase:report:trial-balance:v1:
     * {business-2}:2026-10-24:rev-12
     *
     * Braces form a Redis Cluster hash tag.
     *
     * Including the revision makes old entries
     * automatically unreachable after relevant
     * accounting mutations advance the revision.
     */
    private String buildKey(
        Long businessId,
        LocalDate asOfDate,
        long revision
    ) {

        return CACHE_KEY_PREFIX
            + "{business-" + businessId + "}:"
            + asOfDate
            + ":rev-" + revision;
    }

    // ========================================================
    // RECORD BASIC CACHE METRICS
    // ========================================================

    /**
     * Record fixed, low-cardinality cache outcomes.
     *
     * Do not attach business IDs or JWT values as
     * metric labels.
     */
    private void count(String outcome) {

        meterRegistry.counter(
            "ledgerbase.trial_balance.cache.operations",
            "result",
            outcome
        ).increment();
    }

    // ========================================================
    // IMMUTABLE REDIS CACHE ENVELOPE
    // ========================================================

    /**
     * Serialized JSON structure stored in Redis.
     *
     * The business, date, and revision are included
     * in the payload as well as the key.
     *
     * This provides an additional integrity check
     * against accidental cache key/value mismatches.
     *
     * The actual TrialBalanceReport is reconstructed
     * from the cached rows when retrieved.
     */
    public record CacheEnvelope(
        int schemaVersion,
        long businessId,
        LocalDate asOfDate,
        long revision,
        List<TrialBalanceRow> rows
    ) {
    }
}
