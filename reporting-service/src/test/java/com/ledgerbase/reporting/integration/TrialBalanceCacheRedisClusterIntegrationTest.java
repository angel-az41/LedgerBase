package com.ledgerbase.reporting.integration;

import com.ledgerbase.reporting.model.TrialBalanceReport;
import com.ledgerbase.reporting.model.TrialBalanceRow;

import com.ledgerbase.reporting.service.TrialBalanceCacheService;
import com.ledgerbase.reporting.service.ReplicaTrialBalanceSnapshotService.VersionedTrialBalanceReport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — REDIS CLUSTER REPORT CACHE INTEGRATION TESTS
// ============================================================

/**
 * Verify real cache-aside behavior against LedgerBase's
 * six-node Redis Cluster.
 *
 * INFRASTRUCTURE:
 *
 * - Spring Boot Reporting Service test context.
 * - Spring Data Redis and Lettuce.
 * - Redis Cluster on local ports 7000-7005.
 * - Existing PostgreSQL read replica for context startup.
 *
 * TEST COVERAGE:
 *
 * - Cache miss and Redis population.
 * - Cache hit without regenerating financial data.
 * - Reporting revision changes.
 * - Correct storage of newer snapshot revisions.
 * - Redis entry expiration.
 * - BigDecimal serialization and deserialization.
 *
 * TEST DATA:
 *
 * Reports contain synthetic accounting information.
 *
 * Business IDs are generated in a high numeric range
 * to avoid overlap with ordinary development records.
 *
 * No real financial data is read or written by the
 * cache operations in these tests.
 *
 * PostgreSQL is not modified.
 *
 * Temporary Redis keys are deleted after every test.
 *
 * SECURITY:
 *
 * These tests target the internal cache service.
 *
 * HTTP authorization is not bypassed in production:
 * the actual controller verifies Accounting Core
 * permissions before invoking this service.
 *
 * EXECUTION:
 *
 * Enable explicitly using:
 *
 * LEDGERBASE_REDIS_IT=true
 *
 * Caching is enabled only in this test application
 * context, not in the ordinary Reporting Service.
 */
@EnabledIfEnvironmentVariable(
    named = "LEDGERBASE_REDIS_IT",
    matches = "true"
)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "ledgerbase.cache.trial-balance.enabled=true",
        "ledgerbase.cache.trial-balance.ttl=PT2M"
    }
)
class TrialBalanceCacheRedisClusterIntegrationTest {

    // ========================================================
    // REAL SPRING BEANS
    // ========================================================

    @Autowired
    private TrialBalanceCacheService cacheService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // ========================================================
    // TEST DATA AND CLEANUP TRACKING
    // ========================================================

    private Long businessId;

    private LocalDate reportDate;

    private Set<String> temporaryKeys;

    // ========================================================
    // TEST SETUP
    // ========================================================

    @BeforeEach
    void setUp() {

        /*
         * Use an isolated synthetic business ID.
         *
         * The cache service itself does not query
         * business membership; HTTP authorization
         * is enforced by the production controller.
         *
         * A high random ID prevents test keys from
         * overlapping with typical development data.
         */
        businessId =
            ThreadLocalRandom.current().nextLong(
                1_000_000_000_000L,
                9_000_000_000_000L
            );

        reportDate = LocalDate.now();

        temporaryKeys = new HashSet<>();

        assertNotNull(cacheService);
        assertNotNull(redisTemplate);
    }

    // ========================================================
    // TEST CLEANUP
    // ========================================================

    @AfterEach
    void tearDown() {

        if (temporaryKeys == null) {
            return;
        }

        /*
         * Delete keys individually.
         *
         * This avoids cross-slot multi-key operations
         * when keys belong to different Redis Cluster
         * hash slots.
         */
        for (String key : temporaryKeys) {
            redisTemplate.delete(key);
        }
    }

    // ========================================================
    // BUILD PRODUCTION CACHE KEY
    // ========================================================

    /**
     * Match the versioned key format used by
     * TrialBalanceCacheService.
     *
     * Redis Cluster hashes the contents of the
     * {business-ID} section to select the slot.
     */
    private String keyFor(long revision) {

        return "ledgerbase:report:trial-balance:v1:"
            + "{business-" + businessId + "}:"
            + reportDate
            + ":rev-" + revision;
    }

    // ========================================================
    // CREATE SYNTHETIC BALANCED FINANCIAL REPORT
    // ========================================================

    /**
     * Create two account rows representing a balanced
     * accounting report:
     *
     * Cash:    Debit amount
     * Revenue: Credit amount
     *
     * BigDecimal is constructed from a String to
     * preserve decimal precision.
     */
    private TrialBalanceReport createReport(
        String amount
    ) {

        BigDecimal balance =
            new BigDecimal(amount);

        // ----------------------------------------------------
        // CASH ACCOUNT — DEBIT BALANCE
        // ----------------------------------------------------

        TrialBalanceRow cash =
            new TrialBalanceRow();

        cash.setAccountId(101L);
        cash.setCode("1000");
        cash.setName("Test Cash");
        cash.setAccountType("ASSET");
        cash.setActive(true);

        cash.setDebitBalance(balance);
        cash.setCreditBalance(BigDecimal.ZERO);

        // ----------------------------------------------------
        // REVENUE ACCOUNT — CREDIT BALANCE
        // ----------------------------------------------------

        TrialBalanceRow revenue =
            new TrialBalanceRow();

        revenue.setAccountId(401L);
        revenue.setCode("4000");
        revenue.setName("Test Revenue");
        revenue.setAccountType("REVENUE");
        revenue.setActive(true);

        revenue.setDebitBalance(BigDecimal.ZERO);
        revenue.setCreditBalance(balance);

        // ----------------------------------------------------
        // COMPLETE FINANCIAL REPORT
        // ----------------------------------------------------

        return new TrialBalanceReport(
            businessId,
            reportDate,
            List.of(cash, revenue),
            balance,
            balance
        );
    }

    // ========================================================
    // CREATE VERSIONED DATABASE SNAPSHOT
    // ========================================================

    /**
     * Simulate the result of a previously verified
     * PostgreSQL REPEATABLE READ snapshot.
     *
     * The cache service receives the snapshot through
     * the same supplier contract used in production.
     *
     * The actual PostgreSQL transaction behavior is
     * tested separately by the snapshot integration
     * tests.
     */
    private VersionedTrialBalanceReport snapshot(
        long revision,
        String amount
    ) {

        return new VersionedTrialBalanceReport(
            revision,
            createReport(amount)
        );
    }

    // ========================================================
    // TEST — FIRST REQUEST MISSES, SECOND REQUEST HITS
    // ========================================================

    @Test
    void redisClusterStoresAndServesCachedReport() {

        long revision = 30L;

        String key = keyFor(revision);

        temporaryKeys.add(key);

        AtomicInteger databaseLoads =
            new AtomicInteger();

        // ----------------------------------------------------
        // CREATE MOCK DATABASE SNAPSHOT LOADER
        // ----------------------------------------------------

        Supplier<VersionedTrialBalanceReport> loader =
            () -> {

                databaseLoads.incrementAndGet();

                return snapshot(
                    revision,
                    "123.45"
                );
            };

        // ----------------------------------------------------
        // FIRST REQUEST — CACHE MISS
        // ----------------------------------------------------

        TrialBalanceReport first =
            cacheService.getOrGenerate(
                businessId,
                reportDate,
                revision,
                loader
            );

        assertNotNull(first);

        assertEquals(
            1,
            databaseLoads.get(),
            "First request must load the report"
        );

        // ----------------------------------------------------
        // VERIFY REAL REDIS KEY EXISTS
        // ----------------------------------------------------

        Boolean keyExists =
            redisTemplate.hasKey(key);

        assertEquals(
            Boolean.TRUE,
            keyExists
        );

        String storedJson =
            redisTemplate.opsForValue().get(key);

        assertNotNull(storedJson);

        assertFalse(storedJson.isBlank());

        // ----------------------------------------------------
        // SECOND REQUEST — CACHE HIT
        // ----------------------------------------------------

        TrialBalanceReport second =
            cacheService.getOrGenerate(
                businessId,
                reportDate,
                revision,
                loader
            );

        assertNotNull(second);

        assertEquals(
            1,
            databaseLoads.get(),
            "Redis cache hit must skip the snapshot loader"
        );

        // ----------------------------------------------------
        // VERIFY FINANCIAL REPORT ROWS
        // ----------------------------------------------------

        assertEquals(
            businessId,
            second.getBusinessId()
        );

        assertEquals(
            2,
            second.getRows().size()
        );

        // ----------------------------------------------------
        // VERIFY FINANCIAL PRECISION
        // ----------------------------------------------------

        assertEquals(
            0,
            new BigDecimal("123.45").compareTo(
                second.getRows()
                    .get(0)
                    .getDebitBalance()
            )
        );

        assertEquals(
            0,
            new BigDecimal("123.45").compareTo(
                second.getRows()
                    .get(1)
                    .getCreditBalance()
            )
        );

        // ----------------------------------------------------
        // VERIFY REDIS TTL
        // ----------------------------------------------------

        Long remainingSeconds =
            redisTemplate.getExpire(
                key,
                TimeUnit.SECONDS
            );

        assertNotNull(remainingSeconds);

        assertTrue(
            remainingSeconds > 0,
            "The cached report must have an expiration"
        );

        assertTrue(
            remainingSeconds <= 120,
            "The configured TTL must not exceed two minutes"
        );
    }

    // ========================================================
    // TEST — REPORTING REVISION CHANGES CACHE KEY
    // ========================================================

    @Test
    void newReportingRevisionCreatesNewCacheEntry() {

        long originalRevision = 40L;
        long updatedRevision = 41L;

        String originalKey =
            keyFor(originalRevision);

        String updatedKey =
            keyFor(updatedRevision);

        temporaryKeys.add(originalKey);
        temporaryKeys.add(updatedKey);

        AtomicInteger databaseLoads =
            new AtomicInteger();

        // ----------------------------------------------------
        // ORIGINAL REVISION
        // ----------------------------------------------------

        cacheService.getOrGenerate(
            businessId,
            reportDate,
            originalRevision,
            () -> {

                databaseLoads.incrementAndGet();

                return snapshot(
                    originalRevision,
                    "100.00"
                );
            }
        );

        // ----------------------------------------------------
        // NEW REVISION
        // ----------------------------------------------------

        /*
         * Simulate Accounting Core returning a newer
         * authorized revision after a financial change.
         *
         * The cache service must not reuse the older
         * report from revision 40.
         */
        TrialBalanceReport updatedReport =
            cacheService.getOrGenerate(
                businessId,
                reportDate,
                updatedRevision,
                () -> {

                    databaseLoads.incrementAndGet();

                    return snapshot(
                        updatedRevision,
                        "200.00"
                    );
                }
            );

        // ----------------------------------------------------
        // VERIFY TWO SEPARATE SNAPSHOT LOADS
        // ----------------------------------------------------

        assertEquals(
            2,
            databaseLoads.get()
        );

        // ----------------------------------------------------
        // VERIFY DISTINCT REDIS ENTRIES
        // ----------------------------------------------------

        assertEquals(
            Boolean.TRUE,
            redisTemplate.hasKey(originalKey)
        );

        assertEquals(
            Boolean.TRUE,
            redisTemplate.hasKey(updatedKey)
        );

        assertNotEquals(
            originalKey,
            updatedKey
        );

        // ----------------------------------------------------
        // VERIFY NEW FINANCIAL AMOUNT
        // ----------------------------------------------------

        assertEquals(
            0,
            new BigDecimal("200.00").compareTo(
                updatedReport.getRows()
                    .get(0)
                    .getDebitBalance()
            )
        );

        // ----------------------------------------------------
        // REQUEST UPDATED REVISION AGAIN
        // ----------------------------------------------------

        /*
         * A third request at revision 41 must hit
         * the updated cache entry.
         *
         * If the loader executes again, this supplier
         * will fail the test.
         */
        TrialBalanceReport cachedUpdatedReport =
            cacheService.getOrGenerate(
                businessId,
                reportDate,
                updatedRevision,
                () -> {
                    throw new AssertionError(
                        "Revision 41 should be served from Redis"
                    );
                }
            );

        assertNotNull(cachedUpdatedReport);

        assertEquals(
            2,
            databaseLoads.get()
        );
    }

    // ========================================================
    // TEST — STORE UNDER ACTUAL SNAPSHOT REVISION
    // ========================================================

    @Test
    void newerReplicaSnapshotUsesActualRevisionKey() {

        long requiredRevision = 50L;
        long snapshotRevision = 51L;

        String requiredKey =
            keyFor(requiredRevision);

        String actualKey =
            keyFor(snapshotRevision);

        temporaryKeys.add(requiredKey);
        temporaryKeys.add(actualKey);

        AtomicInteger databaseLoads =
            new AtomicInteger();

        // ----------------------------------------------------
        // REQUIRED REVISION IS OLDER
        // ----------------------------------------------------

        /*
         * Accounting Core authorizes revision 50.
         *
         * Before the replica snapshot begins,
         * a concurrent accounting transaction commits.
         *
         * The snapshot observes revision 51.
         */
        TrialBalanceReport report =
            cacheService.getOrGenerate(
                businessId,
                reportDate,
                requiredRevision,
                () -> {

                    databaseLoads.incrementAndGet();

                    return snapshot(
                        snapshotRevision,
                        "500.00"
                    );
                }
            );

        assertNotNull(report);

        // ----------------------------------------------------
        // VERIFY ACTUAL REVISION KEY EXISTS
        // ----------------------------------------------------

        assertEquals(
            Boolean.TRUE,
            redisTemplate.hasKey(actualKey)
        );

        // ----------------------------------------------------
        // VERIFY OLDER KEY WAS NOT POPULATED
        // ----------------------------------------------------

        assertEquals(
            Boolean.FALSE,
            redisTemplate.hasKey(requiredKey)
        );

        // ----------------------------------------------------
        // REQUEST REVISION 51
        // ----------------------------------------------------

        /*
         * The newer report should now be available
         * directly from Redis under revision 51.
         */
        TrialBalanceReport cachedReport =
            cacheService.getOrGenerate(
                businessId,
                reportDate,
                snapshotRevision,
                () -> {
                    throw new AssertionError(
                        "Revision 51 should be cached"
                    );
                }
            );

        assertNotNull(cachedReport);

        assertEquals(
            1,
            databaseLoads.get()
        );

        assertEquals(
            0,
            new BigDecimal("500.00").compareTo(
                cachedReport.getRows()
                    .get(0)
                    .getDebitBalance()
            )
        );
    }
}
