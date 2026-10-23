package com.ledgerbase.backend.service;

import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.mapper.TrialBalanceMapper;
import com.ledgerbase.backend.model.TrialBalanceReport;
import com.ledgerbase.backend.reporting.ReplicaTrialBalanceReader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — TRIAL BALANCE DATABASE ROUTING TESTS
// ============================================================

/**
 * Verify explicit primary-replica routing for
 * Trial Balance reporting.
 *
 * These are isolated service unit tests.
 *
 * They do not require a running PostgreSQL server
 * and do not modify financial accounting records.
 *
 * TEST COVERAGE:
 *
 * - Use the primary when replica reporting is disabled.
 * - Use the replica when replica reporting is enabled.
 * - Do not silently fall back when the replica fails.
 * - Reject invalid future reporting dates.
 *
 * Live database routing and WAL propagation
 * require separate integration tests.
 */
@ExtendWith(MockitoExtension.class)
class TrialBalanceRoutingTest {

    // ========================================================
    // TEST DEPENDENCIES
    // ========================================================

    @Mock
    private TrialBalanceMapper primaryMapper;

    @Mock
    private BusinessMapper businessMapper;

    @Mock
    private ReplicaTrialBalanceReader replicaReader;

    private TrialBalanceService trialBalanceService;

    private static final Long BUSINESS_ID = 2L;

    // ========================================================
    // TEST SETUP
    // ========================================================

    @BeforeEach
    void setUp() {

        // Construct the service using its existing
        // primary-backed dependencies.
        trialBalanceService =
            new TrialBalanceService(
                primaryMapper,
                businessMapper
            );

        // Simulate an existing business.
        when(businessMapper.existsById(BUSINESS_ID))
            .thenReturn(true);
    }

    // ========================================================
    // PRIMARY ROUTING
    // ========================================================

    /**
     * When no replica reader is injected, the service
     * must preserve its original primary-backed behavior.
     */
    @Test
    void usesPrimaryWhenReplicaDisabled() {

        LocalDate date =
            LocalDate.now().minusDays(1);

        when(
            primaryMapper.findByBusinessIdAsOfDate(
                BUSINESS_ID,
                date
            )
        ).thenReturn(List.of());

        TrialBalanceReport report =
            trialBalanceService.generateTrialBalance(
                BUSINESS_ID,
                date
            );

        assertNotNull(report);

        verify(primaryMapper).findByBusinessIdAsOfDate(
            BUSINESS_ID,
            date
        );

        verifyNoInteractions(replicaReader);
    }

    // ========================================================
    // REPLICA ROUTING
    // ========================================================

    /**
     * When a replica reader is available, the
     * Trial Balance query must use that reader.
     *
     * The primary TrialBalanceMapper should
     * not execute the financial aggregation.
     */
    @Test
    void usesReplicaWhenEnabled() {

        LocalDate date =
            LocalDate.now().minusDays(1);

        // Enable the optional replica dependency.
        trialBalanceService.setReplicaTrialBalanceReader(
            replicaReader
        );

        when(
            replicaReader.findByBusinessIdAsOfDate(
                BUSINESS_ID,
                date
            )
        ).thenReturn(List.of());

        TrialBalanceReport report =
            trialBalanceService.generateTrialBalance(
                BUSINESS_ID,
                date
            );

        assertNotNull(report);

        verify(replicaReader).findByBusinessIdAsOfDate(
            BUSINESS_ID,
            date
        );

        verify(
            primaryMapper,
            never()
        ).findByBusinessIdAsOfDate(
            BUSINESS_ID,
            date
        );
    }

    // ========================================================
    // REPLICA FAILURE HANDLING
    // ========================================================

    /**
     * The current implementation deliberately
     * fails when an enabled replica is unavailable.
     *
     * This prevents silent routing to the primary.
     *
     * A future controlled fallback mechanism may
     * be added with explicit consistency policies.
     */
    @Test
    void doesNotSilentlyFallbackWhenReplicaFails() {

        LocalDate date =
            LocalDate.now().minusDays(1);

        trialBalanceService.setReplicaTrialBalanceReader(
            replicaReader
        );

        when(
            replicaReader.findByBusinessIdAsOfDate(
                BUSINESS_ID,
                date
            )
        ).thenThrow(
            new IllegalStateException(
                "Simulated replica outage"
            )
        );

        IllegalStateException exception =
            assertThrows(
                IllegalStateException.class,
                () ->
                    trialBalanceService.generateTrialBalance(
                        BUSINESS_ID,
                        date
                    )
            );

        assertEquals(
            "Simulated replica outage",
            exception.getMessage()
        );

        // Financial aggregation must not be silently
        // redirected to the primary.
        verify(
            primaryMapper,
            never()
        ).findByBusinessIdAsOfDate(
            BUSINESS_ID,
            date
        );
    }

    // ========================================================
    // REPORT DATE VALIDATION
    // ========================================================

    /**
     * Invalid future dates must be rejected
     * before executing reporting SQL.
     */
    @Test
    void rejectsFutureDateBeforeQueryExecution() {

        LocalDate futureDate =
            LocalDate.now().plusDays(1);

        assertThrows(
            IllegalArgumentException.class,
            () ->
                trialBalanceService.generateTrialBalance(
                    BUSINESS_ID,
                    futureDate
                )
        );

        verifyNoInteractions(
            primaryMapper,
            replicaReader
        );
    }
}
