package com.ledgerbase.reporting.service;

import com.ledgerbase.reporting.model.TrialBalanceReport;
import com.ledgerbase.reporting.model.TrialBalanceRow;
import com.ledgerbase.reporting.service
    .ReplicaTrialBalanceSnapshotService.VersionedTrialBalanceReport;
import com.ledgerbase.reporting.service
    .TrialBalanceCacheService.CacheEnvelope;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for revision-aware Trial Balance caching.
 *
 * The tests verify that a reporting revision change
 * prevents an older cached financial report from
 * being reused.
 *
 * Redis operations and JSON serialization are mocked.
 * No live Redis Cluster or PostgreSQL database is used.
 *
 * Accounting Core is responsible for advancing the
 * revision when a financial transaction is posted.
 *
 * Reporting Service must use the newly authorized
 * revision when constructing the Redis cache key.
 */
class TrialBalanceCacheRevisionTest {

    private static final Long BUSINESS_ID = 77L;

    private static final LocalDate AS_OF_DATE =
        LocalDate.of(2026, 10, 25);

    private static final long OLD_REVISION = 41L;

    private static final long NEW_REVISION = 42L;

    private StringRedisTemplate redisTemplate;

    private ValueOperations<String, String> valueOperations;

    private JsonMapper jsonMapper;

    private TrialBalanceCacheService cacheService;

    /**
     * Simulated Redis key-value storage.
     */
    private Map<String, String> redisStorage;

    /**
     * Simulated JSON serialization storage.
     *
     * This isolates revision-key behavior from
     * the JSON serialization implementation.
     */
    private Map<String, CacheEnvelope> serializedEnvelopes;

    /**
     * Configure the cache with enabled Redis behavior
     * and in-memory mock dependencies.
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {

        redisTemplate = mock(StringRedisTemplate.class);

        valueOperations = mock(ValueOperations.class);

        jsonMapper = mock(JsonMapper.class);

        redisStorage = new HashMap<>();

        serializedEnvelopes = new HashMap<>();

        when(redisTemplate.opsForValue())
            .thenReturn(valueOperations);

        // Return cached values from simulated Redis.
        when(valueOperations.get(anyString()))
            .thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                return redisStorage.get(key);
            });

        // Simulate Redis SET with expiration.
        doAnswer(invocation -> {

            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);

            redisStorage.put(key, value);

            return null;

        }).when(valueOperations).set(
            anyString(),
            anyString(),
            any(Duration.class)
        );

        // Simulate serializing a cache envelope.
        when(jsonMapper.writeValueAsString(
            any(CacheEnvelope.class)
        )).thenAnswer(invocation -> {

            CacheEnvelope envelope =
                invocation.getArgument(0);

            String serializedId =
                "cached-revision-" + envelope.revision();

            serializedEnvelopes.put(
                serializedId,
                envelope
            );

            return serializedId;
        });

        // Reconstruct the original cache envelope.
        when(jsonMapper.readValue(
            anyString(),
            eq(CacheEnvelope.class)
        )).thenAnswer(invocation -> {

            String serializedId =
                invocation.getArgument(0);

            return serializedEnvelopes.get(
                serializedId
            );
        });

        cacheService = new TrialBalanceCacheService(
            redisTemplate,
            jsonMapper,
            new SimpleMeterRegistry(),
            true,
            Duration.ofMinutes(5)
        );
    }

    /**
     * Verify that changing the required revision
     * prevents an older cache entry from being reused.
     *
     * The previous revision represents the financial
     * state before posting.
     *
     * The new revision represents the financial state
     * after a committed accounting mutation.
     */
    @Test
    void usesNewCacheKeyAfterReportingRevisionAdvances() {

        AtomicInteger snapshotCalls =
            new AtomicInteger();

        // Financial state before posting.
        TrialBalanceReport oldReport =
            createBalancedReport("50.00");

        // Financial state after posting.
        TrialBalanceReport updatedReport =
            createBalancedReport("75.00");

        // ====================================================
        // INITIAL REPORT — CACHE MISS
        // ====================================================

        TrialBalanceReport firstResult =
            cacheService.getOrGenerate(
                BUSINESS_ID,
                AS_OF_DATE,
                OLD_REVISION,
                () -> {
                    snapshotCalls.incrementAndGet();

                    return new VersionedTrialBalanceReport(
                        OLD_REVISION,
                        oldReport
                    );
                }
            );

        assertEquals(
            0,
            firstResult.getTotalDebits()
                .compareTo(new BigDecimal("50.00"))
        );

        assertEquals(1, snapshotCalls.get());

        // ====================================================
        // SAME REVISION — CACHE HIT
        // ====================================================

        TrialBalanceReport cachedResult =
            cacheService.getOrGenerate(
                BUSINESS_ID,
                AS_OF_DATE,
                OLD_REVISION,
                () -> {
                    snapshotCalls.incrementAndGet();

                    return new VersionedTrialBalanceReport(
                        OLD_REVISION,
                        oldReport
                    );
                }
            );

        assertEquals(
            0,
            cachedResult.getTotalDebits()
                .compareTo(new BigDecimal("50.00"))
        );

        // No additional database snapshot was required.
        assertEquals(1, snapshotCalls.get());

        // ====================================================
        // POSTING ADVANCES REPORTING REVISION
        // ====================================================

        /*
         * Simulate the next authorized request after
         * Accounting Core advances the revision.
         *
         * Even though the previous cache entry still
         * exists, its revision is no longer valid
         * for this request.
         */
        TrialBalanceReport refreshedResult =
            cacheService.getOrGenerate(
                BUSINESS_ID,
                AS_OF_DATE,
                NEW_REVISION,
                () -> {
                    snapshotCalls.incrementAndGet();

                    return new VersionedTrialBalanceReport(
                        NEW_REVISION,
                        updatedReport
                    );
                }
            );

        assertEquals(
            0,
            refreshedResult.getTotalDebits()
                .compareTo(new BigDecimal("75.00"))
        );

        // A new snapshot must be generated.
        assertEquals(2, snapshotCalls.get());

        // ====================================================
        // VERIFY SEPARATE REVISION CACHE KEYS
        // ====================================================

        String oldKey =
            "ledgerbase:report:trial-balance:v1:"
                + "{business-77}:2026-10-25:rev-41";

        String newKey =
            "ledgerbase:report:trial-balance:v1:"
                + "{business-77}:2026-10-25:rev-42";

        assertTrue(redisStorage.containsKey(oldKey));

        assertTrue(redisStorage.containsKey(newKey));

        assertNotEquals(oldKey, newKey);

        // ====================================================
        // NEW REVISION — SUBSEQUENT CACHE HIT
        // ====================================================

        TrialBalanceReport newCachedResult =
            cacheService.getOrGenerate(
                BUSINESS_ID,
                AS_OF_DATE,
                NEW_REVISION,
                () -> {
                    snapshotCalls.incrementAndGet();

                    return new VersionedTrialBalanceReport(
                        NEW_REVISION,
                        updatedReport
                    );
                }
            );

        assertEquals(
            0,
            newCachedResult.getTotalDebits()
                .compareTo(new BigDecimal("75.00"))
        );

        // No additional database query should occur.
        assertEquals(2, snapshotCalls.get());

        verify(valueOperations, times(2)).get(oldKey);
        verify(valueOperations, times(2)).get(newKey);
    }

    /**
     * Verify that the reporting cache refuses to
     * return a PostgreSQL snapshot older than the
     * authorized revision.
     *
     * This simulates temporary replication lag.
     */
    @Test
    void rejectsSnapshotOlderThanRequiredRevision() {

        TrialBalanceReport staleReport =
            createBalancedReport("50.00");

        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> cacheService.getOrGenerate(
                    BUSINESS_ID,
                    AS_OF_DATE,
                    NEW_REVISION,
                    () -> new VersionedTrialBalanceReport(
                        OLD_REVISION,
                        staleReport
                    )
                )
            );

        assertEquals(
            503,
            exception.getStatusCode().value()
        );

        String newKey =
            "ledgerbase:report:trial-balance:v1:"
                + "{business-77}:2026-10-25:rev-42";

        // A stale snapshot must never populate
        // the newer revision cache entry.
        assertFalse(redisStorage.containsKey(newKey));
    }

    /**
     * Create a balanced financial report with one
     * debit account and one credit account.
     *
     * The amount is controlled by the test and
     * does not represent actual company data.
     */
    private TrialBalanceReport createBalancedReport(
            String amountText) {

        BigDecimal amount =
            new BigDecimal(amountText);

        TrialBalanceRow debitRow =
            new TrialBalanceRow();

        debitRow.setAccountId(1L);
        debitRow.setCode("1000");
        debitRow.setName("Cash");
        debitRow.setAccountType("ASSET");
        debitRow.setActive(true);
        debitRow.setDebitBalance(amount);
        debitRow.setCreditBalance(BigDecimal.ZERO);

        TrialBalanceRow creditRow =
            new TrialBalanceRow();

        creditRow.setAccountId(2L);
        creditRow.setCode("3000");
        creditRow.setName("Equity");
        creditRow.setAccountType("EQUITY");
        creditRow.setActive(true);
        creditRow.setDebitBalance(BigDecimal.ZERO);
        creditRow.setCreditBalance(amount);

        return new TrialBalanceReport(
            BUSINESS_ID,
            AS_OF_DATE,
            List.of(debitRow, creditRow),
            amount,
            amount
        );
    }
}
