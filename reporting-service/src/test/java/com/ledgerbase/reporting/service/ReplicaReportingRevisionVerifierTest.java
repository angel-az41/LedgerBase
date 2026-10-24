package com.ledgerbase.reporting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — REPLICA REPORTING REVISION VERIFIER TESTS
// ============================================================

/**
 * Unit tests for PostgreSQL read-replica freshness checks.
 *
 * PURPOSE:
 *
 * Ensure the Reporting Service only proceeds when the
 * read replica has replayed the required reporting revision.
 *
 * SCENARIOS:
 *
 * - Replica is exactly up to date.
 * - Replica is ahead of the required revision.
 * - Revision zero is accepted.
 * - Replica initially lags but catches up.
 * - Replica never catches up.
 * - Replica returns no business revision.
 * - Replica database query fails.
 * - Invalid inputs are rejected.
 *
 * SECURITY AND CONSISTENCY:
 *
 * An unavailable or stale replica must not be treated
 * as a successful freshness verification.
 *
 * TEST ISOLATION:
 *
 * JdbcTemplate is mocked.
 *
 * No PostgreSQL server or Spring Boot application
 * context is required.
 */
class ReplicaReportingRevisionVerifierTest {

    // ========================================================
    // TEST CONSTANTS
    // ========================================================

    private static final Long BUSINESS_ID = 2L;

    // ========================================================
    // TEST DEPENDENCIES
    // ========================================================

    private JdbcTemplate jdbcTemplate;

    private ReplicaReportingRevisionVerifier verifier;

    // ========================================================
    // TEST SETUP
    // ========================================================

    @BeforeEach
    void setUp() {

        jdbcTemplate = mock(JdbcTemplate.class);

        verifier = new ReplicaReportingRevisionVerifier(
            jdbcTemplate
        );
    }

    // ========================================================
    // TEST HELPER — CONFIGURE REPLICA REVISION
    // ========================================================

    /**
     * Configure the mock database to return a sequence
     * of observed reporting revisions.
     *
     * Mockito returns the final value for subsequent
     * calls after the sequence is exhausted.
     *
     * This lets us simulate WAL replay progress.
     */
    private void mockReplicaRevisions(
        Long firstRevision,
        Long... laterRevisions
    ) {

        when(
            jdbcTemplate.queryForObject(
                anyString(),
                eq(Long.class),
                eq(BUSINESS_ID)
            )
        ).thenReturn(
            firstRevision,
            laterRevisions
        );
    }

    // ========================================================
    // TEST 1 — REPLICA EXACTLY UP TO DATE
    // ========================================================

    @Test
    void acceptsReplicaAtRequiredRevision() {

        // Primary revision = 12.
        // Replica revision = 12.
        mockReplicaRevisions(12L);

        assertDoesNotThrow(
            () -> verifier.awaitRequiredRevision(
                BUSINESS_ID,
                12L
            )
        );

        // Only one database check is necessary.
        verify(jdbcTemplate, times(1))
            .queryForObject(
                anyString(),
                eq(Long.class),
                eq(BUSINESS_ID)
            );
    }

    // ========================================================
    // TEST 2 — REPLICA AHEAD OF REQUIRED REVISION
    // ========================================================

    @Test
    void acceptsReplicaAheadOfRequiredRevision() {

        /*
         * A newer transaction may have committed
         * after Accounting Core returned the required
         * revision to Reporting Service.
         *
         * The replica may therefore be ahead of the
         * minimum revision required for the request.
         */
        mockReplicaRevisions(15L);

        assertDoesNotThrow(
            () -> verifier.awaitRequiredRevision(
                BUSINESS_ID,
                12L
            )
        );

        verify(jdbcTemplate, times(1))
            .queryForObject(
                anyString(),
                eq(Long.class),
                eq(BUSINESS_ID)
            );
    }

    // ========================================================
    // TEST 3 — REVISION ZERO IS VALID
    // ========================================================

    @Test
    void acceptsInitialReportingRevisionZero() {

        /*
         * A newly created business may have
         * reporting_revision = 0.
         *
         * Zero is a legitimate version, not an error.
         */
        mockReplicaRevisions(0L);

        assertDoesNotThrow(
            () -> verifier.awaitRequiredRevision(
                BUSINESS_ID,
                0L
            )
        );

        verify(jdbcTemplate, times(1))
            .queryForObject(
                anyString(),
                eq(Long.class),
                eq(BUSINESS_ID)
            );
    }

    // ========================================================
    // TEST 4 — REPLICA CATCHES UP AFTER INITIAL LAG
    // ========================================================

    @Test
    void waitsUntilReplicaCatchesUp() {

        /*
         * Simulate three successive PostgreSQL reads:
         *
         * Required revision: 12
         *
         * Attempt 1: replica revision 9
         * Attempt 2: replica revision 10
         * Attempt 3: replica revision 12
         *
         * The verifier should stop once revision 12
         * is observed.
         */
        mockReplicaRevisions(
            9L,
            10L,
            12L
        );

        assertDoesNotThrow(
            () -> verifier.awaitRequiredRevision(
                BUSINESS_ID,
                12L
            )
        );

        // Three observations, then successful return.
        verify(jdbcTemplate, times(3))
            .queryForObject(
                anyString(),
                eq(Long.class),
                eq(BUSINESS_ID)
            );
    }

    // ========================================================
    // TEST 5 — REPLICA NEVER CATCHES UP
    // ========================================================

    @Test
    void rejectsReplicaThatRemainsBehind() {

        /*
         * Primary revision: 12
         * Replica revision: 11
         *
         * The replica remains behind on every attempt.
         *
         * Our production verifier currently allows
         * eight observations before failing.
         */
        mockReplicaRevisions(11L);

        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> verifier.awaitRequiredRevision(
                    BUSINESS_ID,
                    12L
                )
            );

        // Financial reports must fail safely.
        assertEquals(
            503,
            exception.getStatusCode().value()
        );

        // Verify the bounded retry policy.
        verify(jdbcTemplate, times(8))
            .queryForObject(
                anyString(),
                eq(Long.class),
                eq(BUSINESS_ID)
            );
    }

    // ========================================================
    // TEST 6 — REPLICA CANNOT FIND BUSINESS REVISION
    // ========================================================

    @Test
    void rejectsMissingReplicaRevision() {

        /*
         * A missing business row must never be
         * interpreted as reporting revision zero.
         *
         * Querying an existing table for a missing row
         * normally raises EmptyResultDataAccessException.
         *
         * Here we simulate a null result to verify
         * that even an unexpected nullable result
         * cannot pass the freshness check.
         */
        mockReplicaRevisions((Long) null);

        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> verifier.awaitRequiredRevision(
                    BUSINESS_ID,
                    0L
                )
            );

        assertEquals(
            503,
            exception.getStatusCode().value()
        );

        verify(jdbcTemplate, times(8))
            .queryForObject(
                anyString(),
                eq(Long.class),
                eq(BUSINESS_ID)
            );
    }

    // ========================================================
    // TEST 7 — REPLICA DATABASE FAILURE
    // ========================================================

    @Test
    void rejectsReplicaDatabaseFailure() {

        /*
         * Simulate the read replica being unavailable.
         *
         * Reporting Service must return HTTP 503
         * instead of assuming that the replica is current.
         */
        when(
            jdbcTemplate.queryForObject(
                anyString(),
                eq(Long.class),
                eq(BUSINESS_ID)
            )
        ).thenThrow(
            new DataAccessResourceFailureException(
                "Simulated replica connection failure"
            )
        );

        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> verifier.awaitRequiredRevision(
                    BUSINESS_ID,
                    12L
                )
            );

        assertEquals(
            503,
            exception.getStatusCode().value()
        );

        // The current implementation fails immediately
        // on database access errors.
        verify(jdbcTemplate, times(1))
            .queryForObject(
                anyString(),
                eq(Long.class),
                eq(BUSINESS_ID)
            );
    }

    // ========================================================
    // TEST 8 — INVALID INPUTS
    // ========================================================

    @Test
    void rejectsInvalidInputsWithoutDatabaseAccess() {

        // Null business ID.
        assertThrows(
            IllegalArgumentException.class,
            () -> verifier.awaitRequiredRevision(
                null,
                12L
            )
        );

        // Zero business ID.
        assertThrows(
            IllegalArgumentException.class,
            () -> verifier.awaitRequiredRevision(
                0L,
                12L
            )
        );

        // Negative business ID.
        assertThrows(
            IllegalArgumentException.class,
            () -> verifier.awaitRequiredRevision(
                -1L,
                12L
            )
        );

        // Negative reporting revision.
        assertThrows(
            IllegalArgumentException.class,
            () -> verifier.awaitRequiredRevision(
                BUSINESS_ID,
                -1L
            )
        );

        // No database access should occur for
        // any of these invalid inputs.
        verifyNoInteractions(jdbcTemplate);
    }
}
