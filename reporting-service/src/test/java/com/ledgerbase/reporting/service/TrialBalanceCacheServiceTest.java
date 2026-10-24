package com.ledgerbase.reporting.service;

import com.ledgerbase.reporting.model.TrialBalanceReport;
import com.ledgerbase.reporting.model.TrialBalanceRow;

import com.ledgerbase.reporting.service
    .ReplicaTrialBalanceSnapshotService.VersionedTrialBalanceReport;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — TRIAL BALANCE REDIS CACHE TESTS
// ============================================================

/**
 * Unit tests for revision-aware Redis Cluster cache-aside.
 *
 * TEST OBJECTIVES:
 *
 * - First request creates a cache miss.
 * - Repeated requests use the cached report.
 * - Accounting revision changes invalidate older keys.
 * - Cache entries use the actual snapshot revision.
 * - Redis failures fall back to verified PostgreSQL data.
 * - Malformed cache payloads are rejected.
 * - Insufficient financial revisions fail closed.
 * - Cache entries receive an explicit TTL.
 * - Monetary values preserve decimal precision.
 * - Disabled caching performs no Redis operations.
 *
 * TEST ISOLATION:
 *
 * Redis operations are mocked and backed by an in-memory
 * Map to simulate persisted cache entries.
 *
 * A real Jackson 3 JsonMapper serializes and deserializes
 * the financial report cache envelopes.
 *
 * A real SimpleMeterRegistry collects cache metrics.
 *
 * No PostgreSQL, Redis, Docker, or Spring Boot context
 * is required.
 *
 * Actual Redis Cluster routing and connectivity remain
 * covered by RedisClusterConnectivityIntegrationTest.
 */
class TrialBalanceCacheServiceTest {

    // ========================================================
    // TEST CONSTANTS
    // ========================================================

    private static final Long BUSINESS_ID = 2L;

    private static final LocalDate REPORT_DATE =
        LocalDate.of(2026, 10, 24);

    private static final Duration CACHE_TTL =
        Duration.ofMinutes(5);

    private static final String METRIC_NAME =
        "ledgerbase.trial_balance.cache.operations";

    // ========================================================
    // MOCKED REDIS DEPENDENCIES
    // ========================================================

    private StringRedisTemplate redisTemplate;

    private ValueOperations<String, String> valueOperations;

    // ========================================================
    // REAL SERIALIZATION AND METRICS
    // ========================================================

    private JsonMapper jsonMapper;

    private SimpleMeterRegistry meterRegistry;

    // ========================================================
    // IN-MEMORY REDIS SIMULATION
    // ========================================================

    /**
     * Simulate Redis key-value storage.
     *
     * Values are JSON strings, matching the production
     * use of StringRedisTemplate.
     */
    private Map<String, String> redisValues;

    /**
     * Capture the TTL passed to the Redis SET operation.
     */
    private AtomicReference<Duration> lastWrittenTtl;

    // ========================================================
    // TEST SETUP
    // ========================================================

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {

        // ----------------------------------------------------
        // CONSTRUCT MOCK REDIS OPERATIONS
        // ----------------------------------------------------

        redisTemplate = mock(StringRedisTemplate.class);

        valueOperations =
            mock(ValueOperations.class);

        when(redisTemplate.opsForValue())
            .thenReturn(valueOperations);

        // ----------------------------------------------------
        // INITIALIZE IN-MEMORY CACHE
        // ----------------------------------------------------

        redisValues = new HashMap<>();

        lastWrittenTtl = new AtomicReference<>();

        // ----------------------------------------------------
        // SIMULATE REDIS GET
        // ----------------------------------------------------

        /*
         * Return JSON previously stored under the
         * exact requested Redis key.
         *
         * Missing keys return null, matching Redis
         * GET behavior through StringRedisTemplate.
         */
        when(
            valueOperations.get(anyString())
        ).thenAnswer(invocation -> {

            String key = invocation.getArgument(0);

            return redisValues.get(key);
        });

        // ----------------------------------------------------
        // SIMULATE REDIS SET WITH TTL
        // ----------------------------------------------------

        /*
         * Capture the key, JSON payload, and expiration.
         *
         * This tests the real cache service while avoiding
         * external Redis infrastructure.
         */
        doAnswer(invocation -> {

            String key = invocation.getArgument(0);

            String value = invocation.getArgument(1);

            Duration expiration =
                invocation.getArgument(2);

            redisValues.put(key, value);

            lastWrittenTtl.set(expiration);

            return null;

        }).when(valueOperations).set(
            anyString(),
            anyString(),
            any(Duration.class)
        );

        // ----------------------------------------------------
        // INITIALIZE JACKSON 3
        // ----------------------------------------------------

        /*
         * Use the actual Jackson 3 JsonMapper.
         *
         * The test therefore exercises real cache payload
         * serialization and deserialization.
         */
        jsonMapper = JsonMapper.builder().build();

        // ----------------------------------------------------
        // INITIALIZE MICROMETER METRICS
        // ----------------------------------------------------

        meterRegistry = new SimpleMeterRegistry();
    }

    // ========================================================
    // TEST CLEANUP
    // ========================================================

    @AfterEach
    void tearDown() {

        if (meterRegistry != null) {
            meterRegistry.close();
        }
    }

    // ========================================================
    // HELPER — CONSTRUCT CACHE SERVICE
    // ========================================================

    /**
     * Instantiate the actual production cache service.
     *
     * enabled = true:
     *   Read and write Redis cache entries.
     *
     * enabled = false:
     *   Bypass Redis and use PostgreSQL snapshot loader.
     */
    private TrialBalanceCacheService createCacheService(
        boolean enabled
    ) {

        return new TrialBalanceCacheService(
            redisTemplate,
            jsonMapper,
            meterRegistry,
            enabled,
            CACHE_TTL
        );
    }

    // ========================================================
    // HELPER — CREATE A REAL FINANCIAL REPORT
    // ========================================================

    /**
     * Create a Trial Balance using one Cash account
     * with an exact debit balance.
     *
     * Using BigDecimal constructed from a string
     * prevents floating-point rounding errors.
     */
    private TrialBalanceReport createReport() {

        TrialBalanceRow cash = new TrialBalanceRow();

        cash.setAccountId(101L);
        cash.setCode("1000");
        cash.setName("Cash");
        cash.setAccountType("ASSET");
        cash.setActive(true);

        cash.setDebitBalance(
            new BigDecimal("123.45")
        );

        cash.setCreditBalance(
            new BigDecimal("0.00")
        );

        return new TrialBalanceReport(
            BUSINESS_ID,
            REPORT_DATE,
            List.of(cash),
            new BigDecimal("123.45"),
            new BigDecimal("0.00")
        );
    }

    // ========================================================
    // HELPER — BUILD VERSIONED SNAPSHOT
    // ========================================================

    /**
     * Associate the real financial report with a
     * consistent PostgreSQL snapshot revision.
     */
    private VersionedTrialBalanceReport snapshot(
        long revision
    ) {

        return new VersionedTrialBalanceReport(
            revision,
            createReport()
        );
    }

    // ========================================================
    // HELPER — CONSTRUCT REDIS KEY
    // ========================================================

    /**
     * Match the production cache key format.
     *
     * Redis Cluster hashes {business-2} as the
     * hash tag for business-scoped key placement.
     */
    private String keyFor(long revision) {

        return "ledgerbase:report:trial-balance:v1:"
            + "{business-2}:"
            + REPORT_DATE
            + ":rev-"
            + revision;
    }

    // ========================================================
    // HELPER — READ CACHE METRIC
    // ========================================================

    /**
     * Return the number of recorded cache operations
     * for a specific low-cardinality outcome.
     */
    private double metricCount(String result) {

        return meterRegistry.counter(
            METRIC_NAME,
            "result",
            result
        ).count();
    }

    // ========================================================
    // TEST 1 — MISS, STORE, THEN HIT
    // ========================================================

    @Test
    void firstRequestMissesAndSecondRequestHits() {

        TrialBalanceCacheService cache =
            createCacheService(true);

        AtomicInteger databaseLoads =
            new AtomicInteger(0);

        Supplier<VersionedTrialBalanceReport> loader =
            () -> {

                databaseLoads.incrementAndGet();

                return snapshot(12L);
            };

        // ----------------------------------------------------
        // FIRST REQUEST — REDIS CACHE MISS
        // ----------------------------------------------------

        TrialBalanceReport first =
            cache.getOrGenerate(
                BUSINESS_ID,
                REPORT_DATE,
                12L,
                loader
            );

        assertNotNull(first);

        assertEquals(
            1,
            databaseLoads.get(),
            "A cache miss must execute the snapshot loader"
        );

        assertTrue(
            redisValues.containsKey(keyFor(12L)),
            "A successful miss must populate Redis"
        );

        // ----------------------------------------------------
        // SECOND REQUEST — REDIS CACHE HIT
        // ----------------------------------------------------

        TrialBalanceReport second =
            cache.getOrGenerate(
                BUSINESS_ID,
                REPORT_DATE,
                12L,
                loader
            );

        assertNotNull(second);

        assertEquals(
            1,
            databaseLoads.get(),
            "A cache hit must skip PostgreSQL"
        );

        // ----------------------------------------------------
        // VERIFY ORIGINAL REPORT STRUCTURE
        // ----------------------------------------------------

        assertEquals(
            BUSINESS_ID,
            second.getBusinessId()
        );

        assertEquals(
            1,
            second.getRows().size()
        );

        // ----------------------------------------------------
        // VERIFY EXACT MONETARY VALUE
        // ----------------------------------------------------

        assertEquals(
            0,
            new BigDecimal("123.45").compareTo(
                second.getRows().get(0).getDebitBalance()
            )
        );

        // ----------------------------------------------------
        // VERIFY CACHE METRICS
        // ----------------------------------------------------

        assertEquals(1.0, metricCount("miss"));
        assertEquals(1.0, metricCount("store"));
        assertEquals(1.0, metricCount("hit"));
    }

    // ========================================================
    // TEST 2 — NEW REVISION REQUIRES NEW REPORT
    // ========================================================

    @Test
    void revisionChangeUsesDifferentCacheKey() {

        TrialBalanceCacheService cache =
            createCacheService(true);

        AtomicInteger databaseLoads =
            new AtomicInteger(0);

        Supplier<VersionedTrialBalanceReport> revision12 =
            () -> {

                databaseLoads.incrementAndGet();

                return snapshot(12L);
            };

        Supplier<VersionedTrialBalanceReport> revision13 =
            () -> {

                databaseLoads.incrementAndGet();

                return snapshot(13L);
            };

        // Populate revision 12.
        cache.getOrGenerate(
            BUSINESS_ID,
            REPORT_DATE,
            12L,
            revision12
        );

        // The accounting revision advances to 13.
        cache.getOrGenerate(
            BUSINESS_ID,
            REPORT_DATE,
            13L,
            revision13
        );

        // A new revision requires another snapshot.
        assertEquals(2, databaseLoads.get());

        // Both versions use different Redis keys.
        assertTrue(redisValues.containsKey(keyFor(12L)));
        assertTrue(redisValues.containsKey(keyFor(13L)));

        assertNotEquals(
            keyFor(12L),
            keyFor(13L)
        );

        // Repeat revision 13: should be a cache hit.
        cache.getOrGenerate(
            BUSINESS_ID,
            REPORT_DATE,
            13L,
            revision13
        );

        assertEquals(
            2,
            databaseLoads.get(),
            "Repeated revision 13 must reuse its cache entry"
        );

        assertEquals(2.0, metricCount("miss"));
        assertEquals(1.0, metricCount("hit"));
    }

    // ========================================================
    // TEST 3 — CACHE ACTUAL SNAPSHOT REVISION
    // ========================================================

    @Test
    void newerSnapshotIsStoredUnderActualRevision() {

        TrialBalanceCacheService cache =
            createCacheService(true);

        AtomicInteger databaseLoads =
            new AtomicInteger(0);

        /*
         * Accounting Core initially authorizes revision 12.
         *
         * A concurrent transaction commits before the
         * replica snapshot begins.
         *
         * The snapshot therefore observes revision 13.
         */
        Supplier<VersionedTrialBalanceReport> loader =
            () -> {

                databaseLoads.incrementAndGet();

                return snapshot(13L);
            };

        TrialBalanceReport report =
            cache.getOrGenerate(
                BUSINESS_ID,
                REPORT_DATE,
                12L,
                loader
            );

        assertNotNull(report);

        // ----------------------------------------------------
        // VERIFY ACTUAL VERSION IS USED
        // ----------------------------------------------------

        assertTrue(
            redisValues.containsKey(keyFor(13L))
        );

        assertFalse(
            redisValues.containsKey(keyFor(12L)),
            "Revision 13 data must not be stored as revision 12"
        );

        // ----------------------------------------------------
        // REQUEST ACTUAL REVISION 13
        // ----------------------------------------------------

        cache.getOrGenerate(
            BUSINESS_ID,
            REPORT_DATE,
            13L,
            loader
        );

        assertEquals(
            1,
            databaseLoads.get(),
            "Revision 13 should now be served from Redis"
        );
    }

    // ========================================================
    // TEST 4 — REDIS READ FAILURE
    // ========================================================

    @Test
    void redisReadFailureFallsBackToPostgres() {

        TrialBalanceCacheService cache =
            createCacheService(true);

        // Simulate an unavailable Redis primary node.
        when(
            valueOperations.get(anyString())
        ).thenThrow(
            new DataAccessResourceFailureException(
                "Simulated Redis connection failure"
            )
        );

        AtomicInteger databaseLoads =
            new AtomicInteger();

        TrialBalanceReport report =
            cache.getOrGenerate(
                BUSINESS_ID,
                REPORT_DATE,
                12L,
                () -> {

                    databaseLoads.incrementAndGet();

                    return snapshot(12L);
                }
            );

        // Redis must not prevent the verified
        // PostgreSQL report from being returned.
        assertNotNull(report);

        assertEquals(
            1,
            databaseLoads.get()
        );

        assertEquals(
            1.0,
            metricCount("read_error")
        );
    }

    // ========================================================
    // TEST 5 — REDIS WRITE FAILURE
    // ========================================================

    @Test
    void redisWriteFailureDoesNotLoseReport() {

        TrialBalanceCacheService cache =
            createCacheService(true);

        // Simulate Redis rejecting the SET operation.
        doThrow(
            new DataAccessResourceFailureException(
                "Simulated Redis write failure"
            )
        ).when(valueOperations).set(
            anyString(),
            anyString(),
            any(Duration.class)
        );

        TrialBalanceReport report =
            cache.getOrGenerate(
                BUSINESS_ID,
                REPORT_DATE,
                12L,
                () -> snapshot(12L)
            );

        // PostgreSQL result is still valid.
        assertNotNull(report);

        // Failed Redis writes must be observable.
        assertEquals(
            1.0,
            metricCount("write_error")
        );
    }

    // ========================================================
    // TEST 6 — CORRUPTED JSON CACHE ENTRY
    // ========================================================

    @Test
    void malformedJsonIsRejectedAndRegenerated() {

        TrialBalanceCacheService cache =
            createCacheService(true);

        // Simulate corrupted Redis JSON.
        redisValues.put(
            keyFor(12L),
            "{this-is-not-valid-json"
        );

        AtomicInteger databaseLoads =
            new AtomicInteger();

        TrialBalanceReport report =
            cache.getOrGenerate(
                BUSINESS_ID,
                REPORT_DATE,
                12L,
                () -> {

                    databaseLoads.incrementAndGet();

                    return snapshot(12L);
                }
            );

        assertNotNull(report);

        assertEquals(
            1,
            databaseLoads.get(),
            "Malformed JSON must trigger a fresh snapshot"
        );

        // The valid report replaces the corrupted payload.
        assertNotEquals(
            "{this-is-not-valid-json",
            redisValues.get(keyFor(12L))
        );

        assertEquals(
            1.0,
            metricCount("read_error")
        );
    }

    // ========================================================
    // TEST 7 — INCORRECT CACHE IDENTITY
    // ========================================================

    @Test
    void mismatchedBusinessIdentityIsRejected() {

        TrialBalanceCacheService cache =
            createCacheService(true);

        /*
         * Simulate a payload whose business ID does
         * not match the requested Redis key.
         *
         * This entry must never be returned as the
         * current business's financial report.
         */
        TrialBalanceCacheService.CacheEnvelope wrongEnvelope =
            new TrialBalanceCacheService.CacheEnvelope(
                1,
                999L,
                REPORT_DATE,
                12L,
                List.of()
            );

        redisValues.put(
            keyFor(12L),
            jsonMapper.writeValueAsString(wrongEnvelope)
        );

        AtomicInteger databaseLoads =
            new AtomicInteger();

        TrialBalanceReport report =
            cache.getOrGenerate(
                BUSINESS_ID,
                REPORT_DATE,
                12L,
                () -> {

                    databaseLoads.incrementAndGet();

                    return snapshot(12L);
                }
            );

        assertNotNull(report);

        assertEquals(
            1,
            databaseLoads.get(),
            "Wrong-business cache data must not be returned"
        );

        // Fresh snapshot replaces the incorrect entry.
        assertEquals(
            1.0,
            metricCount("miss")
        );
    }

    // ========================================================
    // TEST 8 — INSUFFICIENT POSTGRESQL REVISION
    // ========================================================

    @Test
    void insufficientSnapshotRevisionFailsClosed() {

        TrialBalanceCacheService cache =
            createCacheService(true);

        /*
         * Required primary revision: 12.
         *
         * Simulated replica snapshot revision: 11.
         *
         * Financial reporting must be rejected.
         */
        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> cache.getOrGenerate(
                    BUSINESS_ID,
                    REPORT_DATE,
                    12L,
                    () -> snapshot(11L)
                )
            );

        assertEquals(
            HttpStatus.SERVICE_UNAVAILABLE,
            exception.getStatusCode()
        );

        // No stale result may be cached.
        assertTrue(
            redisValues.isEmpty()
        );

        // Redis SET must never execute.
        verify(valueOperations, never()).set(
            anyString(),
            anyString(),
            any(Duration.class)
        );
    }

    // ========================================================
    // TEST 9 — CACHING DISABLED
    // ========================================================

    @Test
    void disabledCacheAlwaysUsesPostgres() {

        TrialBalanceCacheService cache =
            createCacheService(false);

        AtomicInteger databaseLoads =
            new AtomicInteger();

        Supplier<VersionedTrialBalanceReport> loader =
            () -> {

                databaseLoads.incrementAndGet();

                return snapshot(12L);
            };

        // Two identical requests.
        cache.getOrGenerate(
            BUSINESS_ID,
            REPORT_DATE,
            12L,
            loader
        );

        cache.getOrGenerate(
            BUSINESS_ID,
            REPORT_DATE,
            12L,
            loader
        );

        // PostgreSQL loader must execute twice.
        assertEquals(2, databaseLoads.get());

        // Disabled caching must not touch Redis.
        verifyNoInteractions(redisTemplate);

        assertEquals(
            2.0,
            metricCount("bypass")
        );
    }

    // ========================================================
    // TEST 10 — CACHE TTL
    // ========================================================

    @Test
    void cacheWritesUseConfiguredExpiration() {

        TrialBalanceCacheService cache =
            createCacheService(true);

        cache.getOrGenerate(
            BUSINESS_ID,
            REPORT_DATE,
            12L,
            () -> snapshot(12L)
        );

        // Verify that a Redis SET was performed.
        verify(valueOperations, times(1)).set(
            eq(keyFor(12L)),
            anyString(),
            eq(CACHE_TTL)
        );

        // Verify the configured TTL.
        assertEquals(
            Duration.ofMinutes(5),
            lastWrittenTtl.get()
        );

        // The cached record must exist.
        assertTrue(
            redisValues.containsKey(keyFor(12L))
        );
    }

    // ========================================================
    // TEST 11 — INVALID REQUEST PARAMETERS
    // ========================================================

    @Test
    void invalidParametersNeverAccessRedis() {

        TrialBalanceCacheService cache =
            createCacheService(true);

        Supplier<VersionedTrialBalanceReport> loader =
            () -> snapshot(12L);

        // Invalid business ID.
        assertThrows(
            IllegalArgumentException.class,
            () -> cache.getOrGenerate(
                null,
                REPORT_DATE,
                12L,
                loader
            )
        );

        // Missing report date.
        assertThrows(
            IllegalArgumentException.class,
            () -> cache.getOrGenerate(
                BUSINESS_ID,
                null,
                12L,
                loader
            )
        );

        // Negative revision.
        assertThrows(
            IllegalArgumentException.class,
            () -> cache.getOrGenerate(
                BUSINESS_ID,
                REPORT_DATE,
                -1L,
                loader
            )
        );

        // Missing snapshot loader.
        assertThrows(
            NullPointerException.class,
            () -> cache.getOrGenerate(
                BUSINESS_ID,
                REPORT_DATE,
                12L,
                null
            )
        );

        // No Redis operations for invalid requests.
        verifyNoInteractions(redisTemplate);
    }
}
