package com.ledgerbase.reporting.controller;

import com.ledgerbase.reporting.model.TrialBalanceReport;

import com.ledgerbase.reporting.security.AccountingCoreAuthorizationClient;

import com.ledgerbase.reporting.service.ReplicaReportingRevisionVerifier;
import com.ledgerbase.reporting.service.ReplicaTrialBalanceSnapshotService;

import com.ledgerbase.reporting.service
    .ReplicaTrialBalanceSnapshotService.VersionedTrialBalanceReport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — TRIAL BALANCE CONTROLLER TESTS
// ============================================================

/**
 * Verify controller orchestration for secure,
 * snapshot-consistent financial reporting.
 *
 * Expected order:
 *
 * 1. Obtain authorized Accounting Core revision.
 * 2. Verify the replica has caught up.
 * 3. Generate a consistent replica snapshot.
 * 4. Return the existing Trial Balance response.
 *
 * Failure at any earlier stage prevents report generation.
 *
 * All service dependencies are mocked.
 */
class TrialBalanceControllerTest {

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

    private TrialBalanceController controller;

    private Jwt jwt;

    // ========================================================
    // SETUP
    // ========================================================

    @BeforeEach
    void setUp() {

        authorizationClient =
            mock(AccountingCoreAuthorizationClient.class);

        replicaRevisionVerifier =
            mock(ReplicaReportingRevisionVerifier.class);

        snapshotService =
            mock(ReplicaTrialBalanceSnapshotService.class);

        controller = new TrialBalanceController(
            authorizationClient,
            replicaRevisionVerifier,
            snapshotService
        );

        jwt = Jwt.withTokenValue(TEST_ACCESS_TOKEN)
            .header("alg", "HS256")
            .subject("42")
            .build();
    }

    // ========================================================
    // TEST HELPER
    // ========================================================

    private TrialBalanceReport createExpectedReport() {

        return new TrialBalanceReport(
            BUSINESS_ID,
            REPORT_DATE,
            List.of(),
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
    }

    // ========================================================
    // TEST 1 — AUTHORIZED AND CONSISTENT SNAPSHOT
    // ========================================================

    @Test
    void generatesReportAfterSuccessfulRevisionVerification() {

        when(
            authorizationClient.getAuthorizedReportingRevision(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            )
        ).thenReturn(OptionalLong.of(12L));

        TrialBalanceReport expectedReport =
            createExpectedReport();

        when(
            snapshotService.generateSnapshot(
                BUSINESS_ID,
                REPORT_DATE,
                12L
            )
        ).thenReturn(
            new VersionedTrialBalanceReport(
                12L,
                expectedReport
            )
        );

        TrialBalanceReport actualReport =
            controller.getTrialBalance(
                BUSINESS_ID,
                REPORT_DATE,
                jwt
            );

        assertSame(expectedReport, actualReport);

        // Enforce the full verification order.
        var order = inOrder(
            authorizationClient,
            replicaRevisionVerifier,
            snapshotService
        );

        order.verify(authorizationClient)
            .getAuthorizedReportingRevision(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
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

        order.verifyNoMoreInteractions();
    }

    // ========================================================
    // TEST 2 — REVISION ZERO IS VALID
    // ========================================================

    @Test
    void generatesReportWhenAuthorizedRevisionIsZero() {

        when(
            authorizationClient.getAuthorizedReportingRevision(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            )
        ).thenReturn(OptionalLong.of(0L));

        TrialBalanceReport expectedReport =
            createExpectedReport();

        when(
            snapshotService.generateSnapshot(
                BUSINESS_ID,
                REPORT_DATE,
                0L
            )
        ).thenReturn(
            new VersionedTrialBalanceReport(
                0L,
                expectedReport
            )
        );

        TrialBalanceReport result =
            controller.getTrialBalance(
                BUSINESS_ID,
                REPORT_DATE,
                jwt
            );

        assertSame(expectedReport, result);

        verify(replicaRevisionVerifier)
            .awaitRequiredRevision(BUSINESS_ID, 0L);

        verify(snapshotService)
            .generateSnapshot(
                BUSINESS_ID,
                REPORT_DATE,
                0L
            );
    }

    // ========================================================
    // TEST 3 — MISSING JWT
    // ========================================================

    @Test
    void rejectsRequestWithoutAuthenticatedJwt() {

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
            replicaRevisionVerifier,
            snapshotService
        );
    }

    // ========================================================
    // TEST 4 — AUTHORIZED REVISION UNAVAILABLE
    // ========================================================

    @Test
    void rejectsRequestWhenAuthorizedRevisionIsUnavailable() {

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
            HttpStatus.SERVICE_UNAVAILABLE,
            exception.getStatusCode()
        );

        verifyNoInteractions(
            replicaRevisionVerifier,
            snapshotService
        );
    }

    // ========================================================
    // TEST 5 — REPLICA REMAINS BEHIND
    // ========================================================

    @Test
    void doesNotGenerateReportWhenReplicaIsBehind() {

        when(
            authorizationClient.getAuthorizedReportingRevision(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            )
        ).thenReturn(OptionalLong.of(12L));

        doThrow(
            new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Reporting replica has not caught up"
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

        // Never start report generation on a stale replica.
        verifyNoInteractions(snapshotService);
    }

    // ========================================================
    // TEST 6 — REPLICA DATABASE FAILURE
    // ========================================================

    @Test
    void doesNotGenerateReportWhenReplicaCheckFails() {

        when(
            authorizationClient.getAuthorizedReportingRevision(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            )
        ).thenReturn(OptionalLong.of(7L));

        doThrow(
            new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Unable to verify replica freshness"
            )
        ).when(replicaRevisionVerifier)
            .awaitRequiredRevision(BUSINESS_ID, 7L);

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
    // TEST 7 — SNAPSHOT RACE CONDITION PROTECTION
    // ========================================================

    /**
     * Even after the initial freshness check succeeds,
     * the actual reporting snapshot must verify its
     * revision independently.
     *
     * This test simulates snapshot creation failing
     * that final consistency check.
     *
     * The controller must propagate the failure rather
     * than return an unverified financial report.
     */
    @Test
    void rejectsSnapshotThatFailsFinalRevisionCheck() {

        when(
            authorizationClient.getAuthorizedReportingRevision(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            )
        ).thenReturn(OptionalLong.of(12L));

        doThrow(
            new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Reporting snapshot is behind "
                    + "the required financial revision"
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

        verify(replicaRevisionVerifier)
            .awaitRequiredRevision(BUSINESS_ID, 12L);

        verify(snapshotService)
            .generateSnapshot(
                BUSINESS_ID,
                REPORT_DATE,
                12L
            );
    }
}
