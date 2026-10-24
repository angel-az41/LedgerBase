package com.ledgerbase.reporting.integration;

import com.ledgerbase.reporting.model.TrialBalanceReport;

import com.ledgerbase.reporting.service.ReplicaTrialBalanceSnapshotService;
import com.ledgerbase.reporting.service.TrialBalanceService;

import com.ledgerbase.reporting.service
    .ReplicaTrialBalanceSnapshotService.VersionedTrialBalanceReport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

// ============================================================
// LEDGERBASE — REPLICA SNAPSHOT INTEGRATION TESTS
// ============================================================

/**
 * Integration tests for version-consistent Trial Balance
 * reporting through the real PostgreSQL read replica.
 *
 * These tests verify:
 *
 * 1. The snapshot service executes using PostgreSQL.
 * 2. The reporting transaction uses REPEATABLE READ.
 * 3. The transaction is read-only.
 * 4. The database is a PostgreSQL standby.
 * 5. The reporting service runs inside the transaction.
 * 6. The report and observed revision agree.
 * 7. A snapshot below the required revision is rejected.
 *
 * TEST DATABASE:
 *
 * The tests use the Reporting Service's existing
 * replica datasource.
 *
 * No financial records are inserted, updated, or deleted.
 *
 * A business must already exist on the replica.
 *
 * These are opt-in integration tests because they
 * require a running PostgreSQL standby.
 *
 * Do not add @Transactional to this test class.
 *
 * The snapshot service must establish its own transaction
 * through the production Spring transactional proxy.
 */
@SpringBootTest
@EnabledIfSystemProperty(
    named = "ledgerbase.verifyReplicaSnapshot",
    matches = "true"
)
class ReplicaTrialBalanceSnapshotIntegrationTest {

    // ========================================================
    // REAL SPRING SERVICES
    // ========================================================

    @Autowired
    private ReplicaTrialBalanceSnapshotService snapshotService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Spy on the real TrialBalanceService.
     *
     * This allows the test to observe PostgreSQL
     * transaction settings at the moment the financial
     * reporting service executes.
     *
     * The underlying service and MyBatis mapper
     * continue executing real production code.
     */
    @MockitoSpyBean
    private TrialBalanceService trialBalanceService;

    // ========================================================
    // DATABASE SNAPSHOT OBSERVATION
    // ========================================================

    /**
     * Captures transaction state while the Trial Balance
     * service executes inside the snapshot transaction.
     */
    private record SnapshotObservation(
        boolean springTransactionActive,
        String isolation,
        String readOnly,
        boolean standby,
        long reportingRevision
    ) {
    }

    // ========================================================
    // TEST HELPER — FIND EXISTING BUSINESS
    // ========================================================

    /**
     * Select an existing business from the replica.
     *
     * Do not create records on a read-only PostgreSQL
     * standby.
     *
     * An empty replica fixture must fail explicitly
     * rather than producing a misleading passing test.
     */
    private Long findExistingBusinessId() {

        List<Long> businessIds =
            jdbcTemplate.queryForList(
                """
                SELECT id
                FROM public.businesses
                ORDER BY id
                LIMIT 1
                """,
                Long.class
            );

        assertFalse(
            businessIds.isEmpty(),
            "The replica must contain at least one test business"
        );

        return businessIds.get(0);
    }

    // ========================================================
    // TEST 1 — REAL CONSISTENT REPORT SNAPSHOT
    // ========================================================

    @Test
    void generatesReportInsideRepeatableReadReplicaTransaction()
            throws Exception {

        // ====================================================
        // ARRANGE — EXISTING REPLICA BUSINESS
        // ====================================================

        Long businessId = findExistingBusinessId();

        LocalDate asOfDate = LocalDate.now();

        Long requiredRevision =
            jdbcTemplate.queryForObject(
                """
                SELECT reporting_revision
                FROM public.businesses
                WHERE id = ?
                """,
                Long.class,
                businessId
            );

        assertNotNull(requiredRevision);

        // ====================================================
        // ARRANGE — CAPTURE REPORT TRANSACTION STATE
        // ====================================================

        AtomicReference<SnapshotObservation> observation =
            new AtomicReference<>();

        /*
         * TrialBalanceService is wrapped by Spring's
         * transactional proxy.
         *
         * Obtain its underlying spy target so Mockito
         * stubbing does not trigger transactional advice
         * during test setup.
         */
        TrialBalanceService spyTarget =
            AopTestUtils.getUltimateTargetObject(
                trialBalanceService
            );

        doAnswer(invocation -> {

            // --------------------------------------------
            // OBSERVE SPRING TRANSACTION
            // --------------------------------------------

            boolean active =
                TransactionSynchronizationManager
                    .isActualTransactionActive();

            // --------------------------------------------
            // OBSERVE POSTGRESQL CONNECTION SETTINGS
            // --------------------------------------------

            SnapshotObservation current =
                jdbcTemplate.queryForObject(
                    """
                    SELECT
                        current_setting(
                            'transaction_isolation'
                        ),
                        current_setting(
                            'transaction_read_only'
                        ),
                        pg_is_in_recovery(),
                        reporting_revision
                    FROM public.businesses
                    WHERE id = ?
                    """,
                    (rs, rowNumber) ->
                        new SnapshotObservation(
                            active,
                            rs.getString(1),
                            rs.getString(2),
                            rs.getBoolean(3),
                            rs.getLong(4)
                        ),
                    businessId
                );

            observation.set(current);

            // --------------------------------------------
            // EXECUTE REAL FINANCIAL REPORTING CODE
            // --------------------------------------------

            /*
             * Continue into the original TrialBalanceService.
             *
             * The MyBatis mapper and accounting calculations
             * are not mocked.
             */
            return invocation.callRealMethod();

        }).when(spyTarget)
            .generateTrialBalance(
                eq(businessId),
                eq(asOfDate)
            );

        // ====================================================
        // ACT — EXECUTE THE REAL SNAPSHOT SERVICE
        // ====================================================

        VersionedTrialBalanceReport result =
            snapshotService.generateSnapshot(
                businessId,
                asOfDate,
                requiredRevision
            );

        // ====================================================
        // ASSERT — REPORT WAS GENERATED
        // ====================================================

        assertNotNull(result);

        TrialBalanceReport report = result.report();

        assertNotNull(report);

        assertEquals(
            businessId,
            report.getBusinessId()
        );

        assertNotNull(report.getRows());

        // ====================================================
        // ASSERT — OBSERVATION WAS RECORDED
        // ====================================================

        SnapshotObservation observed =
            observation.get();

        assertNotNull(
            observed,
            "Real TrialBalanceService must execute"
        );

        // ====================================================
        // ASSERT — REAL SPRING TRANSACTION ACTIVE
        // ====================================================

        assertTrue(
            observed.springTransactionActive(),
            "Trial Balance must execute inside a Spring transaction"
        );

        // ====================================================
        // ASSERT — REPEATABLE READ ISOLATION
        // ====================================================

        assertEquals(
            "repeatable read",
            observed.isolation(),
            "Report generation must use REPEATABLE READ"
        );

        // ====================================================
        // ASSERT — READ-ONLY TRANSACTION
        // ====================================================

        assertEquals(
            "on",
            observed.readOnly(),
            "Financial reporting must remain read-only"
        );

        // ====================================================
        // ASSERT — POSTGRESQL STANDBY
        // ====================================================

        assertTrue(
            observed.standby(),
            "Report must execute against PostgreSQL standby"
        );

        // ====================================================
        // ASSERT — CONSISTENT REPORT REVISION
        // ====================================================

        /*
         * The snapshot service reads the revision before
         * invoking TrialBalanceService.
         *
         * TrialBalanceService's intercepted JDBC query
         * observes the revision inside the report transaction.
         *
         * Both must identify the same snapshot revision.
         */
        assertEquals(
            result.revision(),
            observed.reportingRevision(),
            "Report execution must observe the snapshot revision"
        );

        // ====================================================
        // ASSERT — REQUIRED REVISION SATISFIED
        // ====================================================

        assertTrue(
            result.revision() >= requiredRevision,
            "Snapshot must meet the minimum required revision"
        );
    }

    // ========================================================
    // TEST 2 — REJECT AN INSUFFICIENT SNAPSHOT
    // ========================================================

    @Test
    void rejectsSnapshotBelowRequiredRevision() {

        Long businessId = findExistingBusinessId();

        LocalDate asOfDate = LocalDate.now();

        /*
         * Long.MAX_VALUE represents an unreachable
         * future reporting revision for the test fixture.
         *
         * This verifies that the production snapshot
         * service rejects an insufficient replica revision.
         *
         * No financial data or database permissions
         * are modified.
         */
        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> snapshotService.generateSnapshot(
                    businessId,
                    asOfDate,
                    Long.MAX_VALUE
                )
            );

        assertEquals(
            HttpStatus.SERVICE_UNAVAILABLE,
            exception.getStatusCode()
        );
    }
}
