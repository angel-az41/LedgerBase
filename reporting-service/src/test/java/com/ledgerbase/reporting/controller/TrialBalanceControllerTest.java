package com.ledgerbase.reporting.controller;

import com.ledgerbase.reporting.model.TrialBalanceReport;

import com.ledgerbase.reporting.security.AccountingCoreAuthorizationClient;

import com.ledgerbase.reporting.service.ReplicaReportingRevisionVerifier;
import com.ledgerbase.reporting.service.ReplicaTrialBalanceSnapshotService;
import com.ledgerbase.reporting.service.TrialBalanceCacheService;

import com.ledgerbase.reporting.service.ReplicaTrialBalanceSnapshotService.VersionedTrialBalanceReport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — TRIAL BALANCE CONTROLLER TESTS
// ============================================================

/**
 * Verify secure Trial Balance controller orchestration
 * after introducing revision-aware Redis cache-aside.
 *
 * SECURITY:
 *
 * Every request must obtain an authorized primary
 * reporting revision before accessing the cache.
 *
 * CACHE HIT:
 *
 * Return a previously verified matching report without
 * querying the PostgreSQL replica.
 *
 * CACHE MISS:
 *
 * 1. Verify replica freshness.
 * 2. Generate a consistent PostgreSQL snapshot.
 * 3. Return the report through the cache service.
 *
 * FAILURES:
 *
 * Missing JWT, unavailable authorized revisions,
 * insufficient replica freshness, and snapshot
 * failures must prevent financial report generation.
 *
 * All dependencies are mocked.
 */
class TrialBalanceControllerTest {

    // ========================================================
    // TEST CONSTANTS
    // ========================================================

    private static final Long BUSINESS_ID = 2L;

    private static final LocalDate REPORT_DATE =
        LocalDate.of(2026, 10, 11);

    private static final String TEST_ACCESS_TOKEN =
        "test-access-token";

    // ========================================================
    // MOCKED DEPENDENCIES
    // ========================================================

    private AccountingCoreAuthorizationClient
        authorizationClient;

    private ReplicaReportingRevisionVerifier
        replicaRevisionVerifier;

    private ReplicaTrialBalanceSnapshotService
        snapshotService;

    private TrialBalanceCacheService cacheService;

    private TrialBalanceController controller;

    private Jwt jwt;

    // ========================================================
    // TEST SETUP
    // ========================================================

    @BeforeEach
    void setUp() {

        authorizationClient =
            mock(AccountingCoreAuthorizationClient.class);

        replicaRevisionVerifier =
            mock(ReplicaReportingRevisionVerifier.class);

        snapshotService =
            mock(ReplicaTrialBalanceSnapshotService.class);

        cacheService =
            mock(TrialBalanceCacheService.class);

        controller = new TrialBalanceController(
            authorizationClient,
            replicaRevisionVerifier,
            snapshotService,
            cacheService
        );

        jwt = Jwt.withTokenValue(TEST_ACCESS_TOKEN)
            .header("alg", "HS256")
            .subject("42")
            .build();
    }

    // ========================================================
    // TEST HELPERS
    // ========================================================

    private TrialBalanceReport expectedReport() {

        return new TrialBalanceReport(
            BUSINESS_ID,
            REPORT_DATE,
            List.of(),
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
    }

    /**
     * Simulate a cache miss by making the mocked cache
     * invoke the supplied PostgreSQL snapshot loader.
     *
     * The actual cache implementation is tested
     * separately.
     */
    private void simulateCacheMiss(long revision) {

        when(
            cacheService.getOrGenerate(
                eq(BUSINESS_ID),
                eq(REPORT_DATE),
                eq(revision),
                any()
            )
        ).thenAnswer(invocation -> {

            Supplier<?> loader =
                invocation.getArgument(3);

            VersionedTrialBalanceReport snapshot =
                (VersionedTrialBalanceReport) loader.get();

            return snapshot.report();
        });
    }

    private void authorizeRevision(long revision) {

        when(
            authorizationClient.getAuthorizedReportingRevision(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            )
        ).thenReturn(
            OptionalLong.of(revision)
        );
    }

    // ========================================================
    // TEST 1 — CACHE MISS GENERATES VERIFIED SNAPSHOT
    // ========================================================

    @Test
    void generatesReportAfterSuccessfulRevisionVerification() {

        authorizeRevision(12L);
        simulateCacheMiss(12L);

        TrialBalanceReport expected =
            expectedReport();

        when(
            snapshotService.generateSnapshot(
                BUSINESS_ID,
                REPORT_DATE,
                12L
            )
        ).thenReturn(
            new VersionedTrialBalanceReport(
                12L,
                expected
            )
        );

        TrialBalanceReport actual =
            controller.getTrialBalance(
                BUSINESS_ID,
                REPORT_DATE,
                jwt
            );

        assertSame(expected, actual);

        // Verify authorization, caching, and replica
        // generation occurred in the correct order.
        var order = inOrder(
            authorizationClient,
            cacheService,
            replicaRevisionVerifier,
            snapshotService
        );

        order.verify(authorizationClient)
            .getAuthorizedReportingRevision(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            );

        order.verify(cacheService)
            .getOrGenerate(
                eq(BUSINESS_ID),
                eq(REPORT_DATE),
                eq(12L),
                any()
            );

        order.verify(replicaRevisionVerifier)
            .awaitRequiredRevision(
                BUSINESS_ID,
                12L
            );

        order.verify(snapshotService)
            .generateSnapshot(
                BUSINESS_ID,
                REPORT_DATE,
                12L
            );
    }

    // ========================================================
    // TEST 2 — CACHE HIT SKIPS POSTGRESQL
    // ========================================================

    @Test
    void cacheHitReturnsReportWithoutReplicaRead() {

        authorizeRevision(12L);

        TrialBalanceReport expected =
            expectedReport();

        // The cache already contains a report associated
        // with the authorized revision.
        when(
            cacheService.getOrGenerate(
                eq(BUSINESS_ID),
                eq(REPORT_DATE),
                eq(12L),
                any()
            )
        ).thenReturn(expected);

        TrialBalanceReport actual =
            controller.getTrialBalance(
                BUSINESS_ID,
                REPORT_DATE,
                jwt
            );

        assertSame(expected, actual);

        // Authorization must still occur.
        verify(authorizationClient)
            .getAuthorizedReportingRevision(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            );

        // A cache hit must avoid both replica operations.
        verifyNoInteractions(
            replicaRevisionVerifier,
            snapshotService
        );
    }

    // ========================================================
    // TEST 3 — REVISION ZERO REMAINS VALID
    // ========================================================

    @Test
    void acceptsAuthorizedRevisionZero() {

        authorizeRevision(0L);
        simulateCacheMiss(0L);

        TrialBalanceReport expected =
            expectedReport();

        when(
            snapshotService.generateSnapshot(
                BUSINESS_ID,
                REPORT_DATE,
                0L
            )
        ).thenReturn(
            new VersionedTrialBalanceReport(
                0L,
                expected
            )
        );

        assertSame(
            expected,
            controller.getTrialBalance(
                BUSINESS_ID,
                REPORT_DATE,
                jwt
            )
        );

        verify(replicaRevisionVerifier)
            .awaitRequiredRevision(BUSINESS_ID, 0L);
    }

    // ========================================================
    // TEST 4 — MISSING JWT
    // ========================================================

    @Test
    void missingJwtPreventsCacheAndDatabaseAccess() {

        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> controller.getTrialBalance(
                    BUSINESS_ID,
                    REPORT_DATE,
                    null
                )
            );

        assertEquals(
            HttpStatus.UNAUTHORIZED,
            exception.getStatusCode()
        );

        verifyNoInteractions(
            authorizationClient,
            cacheService,
            replicaRevisionVerifier,
            snapshotService
        );
    }

    // ========================================================
    // TEST 5 — AUTHORIZED REVISION UNAVAILABLE
    // ========================================================

    @Test
    void unavailableAuthorizedRevisionPreventsCacheAccess() {

        when(
            authorizationClient.getAuthorizedReportingRevision(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            )
        ).thenReturn(OptionalLong.empty());

        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> controller.getTrialBalance(
                    BUSINESS_ID,
                    REPORT_DATE,
                    jwt
                )
            );

        assertEquals(
            503,
            exception.getStatusCode().value()
        );

        // Never return Redis data when current
        // authorization cannot be verified.
        verifyNoInteractions(
            cacheService,
            replicaRevisionVerifier,
            snapshotService
        );
    }

    // ========================================================
    // TEST 6 — REPLICA REMAINS STALE
    // ========================================================

    @Test
    void staleReplicaPreventsSnapshotGeneration() {

        authorizeRevision(12L);
        simulateCacheMiss(12L);

        doThrow(
            new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Replica remains behind"
            )
        ).when(replicaRevisionVerifier)
            .awaitRequiredRevision(BUSINESS_ID, 12L);

        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> controller.getTrialBalance(
                    BUSINESS_ID,
                    REPORT_DATE,
                    jwt
                )
            );

        assertEquals(
            503,
            exception.getStatusCode().value()
        );

        verifyNoInteractions(snapshotService);
    }

    // ========================================================
    // TEST 7 — FINAL SNAPSHOT VERIFICATION FAILURE
    // ========================================================

    @Test
    void invalidSnapshotDoesNotProduceReport() {

        authorizeRevision(12L);
        simulateCacheMiss(12L);

        doThrow(
            new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Snapshot revision is insufficient"
            )
        ).when(snapshotService)
            .generateSnapshot(
                BUSINESS_ID,
                REPORT_DATE,
                12L
            );

        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> controller.getTrialBalance(
                    BUSINESS_ID,
                    REPORT_DATE,
                    jwt
                )
            );

        assertEquals(
            503,
            exception.getStatusCode().value()
        );

        verify(snapshotService)
            .generateSnapshot(
                BUSINESS_ID,
                REPORT_DATE,
                12L
            );
    }
}
