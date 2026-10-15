package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.dto.CreateLedgerEntryRequest;
import com.ledgerbase.backend.dto.CreateTransactionRequest;
import com.ledgerbase.backend.exception.TransactionConflictException;
import com.ledgerbase.backend.model.LedgerTransaction;
import com.ledgerbase.backend.service.AccountingPeriodService;
import com.ledgerbase.backend.service.TransactionService;
import com.ledgerbase.backend.service.FiscalYearService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for concurrent transaction posting.
 *
 * These tests verify that PostgreSQL row locking
 * prevents a transaction from being posted twice
 * when two requests arrive at nearly the same time.
 *
 * IMPORTANT:
 * This class deliberately does not use @Transactional.
 *
 * The worker threads must access committed records
 * through separate database transactions.
 */
@SpringBootTest
@ActiveProfiles("test")
class TransactionConcurrencyIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FiscalYearService fiscalYearService;

    @Autowired
    private AccountingPeriodService accountingPeriodService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // IDs of committed test records.
    private Long testUserId;
    private Long testBusinessId;
    private Long cashAccountId;
    private Long revenueAccountId;

    // ============================================================
    // TEST SETUP
    // ============================================================

    /**
     * Create the database records needed for posting.
     *
     * Because this class is not @Transactional,
     * these setup records become visible to the
     * worker threads before the test begins.
     */
    @BeforeEach
    void setUpTestData() {

        // Create a unique test user.
        testUserId = jdbcTemplate.queryForObject(
            """
            INSERT INTO users (
                email,
                password_hash,
                full_name
            )
            VALUES (?, ?, ?)
            RETURNING id
            """,
            Long.class,
            "concurrency-test-" + UUID.randomUUID()
                + "@ledgerbase.local",
            "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
            "Concurrency Test User"
        );

        // Create a business owned by the test user.
        testBusinessId = jdbcTemplate.queryForObject(
            """
            INSERT INTO businesses (
                owner_id,
                name,
                currency
            )
            VALUES (?, ?, ?)
            RETURNING id
            """,
            Long.class,
            testUserId,
            "Concurrency Test Business",
            "USD"
        );

        fiscalYearService.createFiscalYear(
            testBusinessId,
            2026
        );

        // Cash account for the debit.
        cashAccountId = jdbcTemplate.queryForObject(
            """
            INSERT INTO accounts (
                business_id,
                code,
                name,
                account_type
            )
            VALUES (?, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            testBusinessId,
            "1000",
            "Cash",
            "ASSET"
        );

        // Revenue account for the credit.
        revenueAccountId = jdbcTemplate.queryForObject(
            """
            INSERT INTO accounts (
                business_id,
                code,
                name,
                account_type
            )
            VALUES (?, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            testBusinessId,
            "4000",
            "Consulting Revenue",
            "REVENUE"
        );
    }

    // ============================================================
    // TEST CLEANUP
    // ============================================================

    /**
     * Remove committed test fixtures after testing.
     *
     * Posted transactions are protected from ordinary
     * DELETE operations by our V4 triggers.
     *
     * TRUNCATE is used strictly for resetting the
     * dedicated ledgerbase_test database.
     *
     * WARNING:
     * This clears test users and all dependent tables.
     * It must never run against the development
     * or production database.
     *
     * This test suite assumes tests are not running
     * concurrently against the same test database.
     */
    @AfterEach
    void cleanUpTestData() {

        // Confirm the current database before cleanup.
        String databaseName = jdbcTemplate.queryForObject(
            "SELECT current_database()",
            String.class
        );

        if (!"ledgerbase_test".equals(databaseName)) {
            throw new IllegalStateException(
                "Refusing cleanup outside ledgerbase_test"
            );
        }

        // Reset the isolated test database's user data.
        // Foreign-key dependent tables are also truncated.
        jdbcTemplate.execute(
            "TRUNCATE TABLE users RESTART IDENTITY CASCADE"
        );
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================

    /**
     * Create a balanced DRAFT transaction.
     *
     * Debit Cash:       $500.00
     * Credit Revenue:   $500.00
     *
     * The service commits the transaction before
     * our worker threads attempt to post it.
     */
    private LedgerTransaction createDraft() {

        CreateTransactionRequest request =
            new CreateTransactionRequest(
                testUserId,
                "Concurrent posting test",
                LocalDate.of(2026, 10, 12),
                List.of(
                    new CreateLedgerEntryRequest(
                        cashAccountId,
                        new BigDecimal("500.00"),
                        BigDecimal.ZERO
                    ),
                    new CreateLedgerEntryRequest(
                        revenueAccountId,
                        BigDecimal.ZERO,
                        new BigDecimal("500.00")
                    )
                )
            );

        return transactionService.createDraftTransaction(
            testBusinessId,
            request
        );
    }

    // ============================================================
    // TEST 1 — PREVENT CONCURRENT DOUBLE POSTING
    // ============================================================

    /**
     * Two worker threads attempt to post the
     * same DRAFT transaction concurrently.
     *
     * Expected results:
     * 1. Exactly one posting succeeds.
     * 2. Exactly one posting returns a conflict.
     * 3. The final status is POSTED.
     * 4. Exactly one posting audit event exists.
     *
     * CountDownLatch helps both worker threads
     * begin their posting attempts together.
     *
     * This tests overlapping requests but does not
     * force a particular database lock acquisition order.
     */
    @Test
    void allowsOnlyOneConcurrentPosting() throws Exception {

        // Create a committed balanced DRAFT.
        LedgerTransaction draft = createDraft();

        assertEquals("DRAFT", draft.getStatus());

        // Create two worker threads.
        ExecutorService executor =
            Executors.newFixedThreadPool(2);

        // Count how many workers are ready.
        CountDownLatch ready = new CountDownLatch(2);

        // Release both workers at the same time.
        CountDownLatch start = new CountDownLatch(1);

        /*
         * Define a posting task.
         *
         * Each worker waits at the same starting gate.
         *
         * On success, return "POSTED".
         * On expected business conflict, return "CONFLICT".
         */
        Callable<String> postingTask = () -> {

            // Tell the test that this worker is ready.
            ready.countDown();

            // Wait for permission to begin.
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                    "Timed out waiting to start posting"
                );
            }

            try {

                transactionService.postTransaction(
                    testBusinessId,
                    draft.getId(),
                    testUserId
                );

                return "POSTED";

            } catch (TransactionConflictException exception) {

                // One request should find it already posted.
                return "CONFLICT";
            }
        };

        try {

            // Submit both posting requests.
            Future<String> first =
                executor.submit(postingTask);

            Future<String> second =
                executor.submit(postingTask);

            // Wait until both workers are ready.
            assertTrue(
                ready.await(5, TimeUnit.SECONDS),
                "Both workers should be ready"
            );

            // Allow both posting operations to begin.
            start.countDown();

            // Collect results from both threads.
            String firstResult =
                first.get(10, TimeUnit.SECONDS);

            String secondResult =
                second.get(10, TimeUnit.SECONDS);

            List<String> results = List.of(
                firstResult,
                secondResult
            );

            /*
             * Exactly one operation must succeed.
             */
            assertEquals(
                1,
                results.stream()
                    .filter("POSTED"::equals)
                    .count()
            );

            /*
             * Exactly one operation must be rejected.
             */
            assertEquals(
                1,
                results.stream()
                    .filter("CONFLICT"::equals)
                    .count()
            );

        } finally {

            // Release any worker still waiting to start.
            start.countDown();

            // Stop the worker pool.
            executor.shutdownNow();
        }

        // Verify the final database status.
        String databaseStatus = jdbcTemplate.queryForObject(
            """
            SELECT status
            FROM transactions
            WHERE id = ?
              AND business_id = ?
            """,
            String.class,
            draft.getId(),
            testBusinessId
        );

        assertEquals("POSTED", databaseStatus);

        /*
         * Verify exactly one TRANSACTION_POSTED event.
         *
         * This ensures that two concurrent requests
         * cannot generate two successful posting records.
         */
        Integer postingAuditCount =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_events
                WHERE business_id = ?
                  AND entity_type = 'TRANSACTION'
                  AND entity_id = ?
                  AND action = 'TRANSACTION_POSTED'
                """,
                Integer.class,
                testBusinessId,
                draft.getId()
            );

        assertEquals(1, postingAuditCount);

        // Verify posting preserved the two ledger entries.
        Integer entryCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM ledger_entries
            WHERE business_id = ?
              AND transaction_id = ?
            """,
            Integer.class,
            testBusinessId,
            draft.getId()
        );

        assertEquals(2, entryCount);
    }


    // ============================================================
    // TEST 2 — CLOSING FIRST PREVENTS CONCURRENT POSTING
    // ============================================================

    /**
     * Verify that a journal transaction cannot be posted
     * while its accounting period is being closed.
     *
     * Thread A:
     *
     * 1. Begins a PostgreSQL transaction.
     * 2. Closes the October accounting period.
     * 3. Holds the database transaction open.
     *
     * Thread B:
     *
     * 1. Attempts to post an October journal transaction.
     * 2. Must wait for the accounting-period row lock.
     *
     * After Thread A commits:
     *
     * - The accounting period is CLOSED.
     * - The posting attempt is rejected.
     * - The journal transaction remains DRAFT.
     * - No TRANSACTION_POSTED audit event is created.
     *
     * IMPORTANT:
     *
     * This test must not use @Transactional at class
     * or test-method level.
     *
     * The workers need independent database transactions.
     */
    @Test
    void closingPeriodFirstPreventsConcurrentPosting()
            throws Exception {

        // ====================================================
        // CREATE COMMITTED DRAFT TRANSACTION
        // ====================================================

        LedgerTransaction draft = createDraft();

        assertEquals("DRAFT", draft.getStatus());

        // ====================================================
        // FIND THE PERIOD CONTAINING THE TRANSACTION DATE
        // ====================================================

        Long periodId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM accounting_periods
            WHERE business_id = ?
              AND ? BETWEEN start_date AND end_date
            """,
            Long.class,
            testBusinessId,
            LocalDate.of(2026, 10, 12)
        );

        assertNotNull(periodId);

        // ====================================================
        // PREPARE INDEPENDENT WORKER THREADS
        // ====================================================

        ExecutorService executor =
            Executors.newFixedThreadPool(2);

        /*
         * Signals that closing has updated the period
         * inside a transaction that is not yet committed.
         */
        CountDownLatch periodClosedButUncommitted =
            new CountDownLatch(1);

        /*
         * Controls when the closing transaction
         * may finally commit.
         */
        CountDownLatch allowClosingCommit =
            new CountDownLatch(1);

        /*
         * Signals that the posting worker has reached
         * the point where it will call the service.
         */
        CountDownLatch postingStarted =
            new CountDownLatch(1);

        // ====================================================
        // THREAD A — CLOSE PERIOD AND HOLD TRANSACTION OPEN
        // ====================================================

        Future<?> closingFuture = executor.submit(() -> {

            /*
             * TransactionTemplate allows this test to
             * control the transaction's commit timing.
             *
             * The service joins this transaction through
             * its existing @Transactional annotation.
             */
            TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

            transactionTemplate.executeWithoutResult(status -> {

                // Close October and write its audit event.
                accountingPeriodService.closeAccountingPeriod(
                    testBusinessId,
                    periodId,
                    testUserId
                );

                /*
                 * The period is now CLOSED inside Thread A,
                 * but the database transaction has not
                 * committed yet.
                 *
                 * PostgreSQL still holds the row lock.
                 */
                periodClosedButUncommitted.countDown();

                try {

                    if (!allowClosingCommit.await(
                            10,
                            TimeUnit.SECONDS)) {

                        throw new IllegalStateException(
                            "Timed out waiting to commit closing"
                        );
                    }

                } catch (InterruptedException exception) {

                    Thread.currentThread().interrupt();

                    throw new IllegalStateException(
                        "Closing worker interrupted",
                        exception
                    );
                }
            });
        });

        // ====================================================
        // THREAD B — ATTEMPT POSTING WHILE CLOSING HOLDS LOCK
        // ====================================================

        try {

            /*
             * Ensure that the closing operation has
             * already updated the period before posting
             * begins.
             */
            assertTrue(
                periodClosedButUncommitted.await(
                    5,
                    TimeUnit.SECONDS
                ),
                "Closing worker should hold the period lock"
            );

            Future<String> postingFuture = executor.submit(
                () -> {

                    postingStarted.countDown();

                    try {

                        transactionService.postTransaction(
                            testBusinessId,
                            draft.getId(),
                            testUserId
                        );

                        return "POSTED";

                    } catch (
                        TransactionConflictException exception
                    ) {

                        return "CONFLICT";
                    }
                }
            );

            assertTrue(
                postingStarted.await(
                    5,
                    TimeUnit.SECONDS
                ),
                "Posting worker should start"
            );

            // ====================================================
            // VERIFY POSTING CANNOT FINISH BEFORE CLOSING COMMITS
            // ====================================================

            /*
             * Posting should remain unfinished while
             * Thread A holds the accounting-period lock.
             *
             * TimeoutException is expected here.
             *
             * This is a bounded wait, not a sleep used
             * to decide which worker should win.
             */
            assertThrows(
                TimeoutException.class,
                () -> postingFuture.get(
                    250,
                    TimeUnit.MILLISECONDS
                ),
                "Posting should wait while closing holds the lock"
            );

            // ====================================================
            // COMMIT THE CLOSING TRANSACTION
            // ====================================================

            allowClosingCommit.countDown();

            closingFuture.get(
                10,
                TimeUnit.SECONDS
            );

            // ====================================================
            // VERIFY POSTING IS REJECTED
            // ====================================================

            assertEquals(
                "CONFLICT",
                postingFuture.get(
                    10,
                    TimeUnit.SECONDS
                )
            );

        } finally {

            /*
             * Always release the closing worker, even
             * when an assertion fails.
             *
             * This helps avoid leaving a database
             * transaction blocked during test cleanup.
             */
            allowClosingCommit.countDown();

            executor.shutdown();

            if (!executor.awaitTermination(
                    15,
                    TimeUnit.SECONDS)) {

                executor.shutdownNow();

                throw new IllegalStateException(
                    "Concurrency workers did not terminate"
                );
            }
        }

        // ====================================================
        // VERIFY FINAL ACCOUNTING PERIOD STATUS
        // ====================================================

        String periodStatus = jdbcTemplate.queryForObject(
            """
            SELECT status
            FROM accounting_periods
            WHERE business_id = ?
              AND id = ?
            """,
            String.class,
            testBusinessId,
            periodId
        );

        assertEquals("CLOSED", periodStatus);

        // ====================================================
        // VERIFY TRANSACTION REMAINS DRAFT
        // ====================================================

        String transactionStatus = jdbcTemplate.queryForObject(
            """
            SELECT status
            FROM transactions
            WHERE business_id = ?
              AND id = ?
            """,
            String.class,
            testBusinessId,
            draft.getId()
        );

        assertEquals("DRAFT", transactionStatus);

        // ====================================================
        // VERIFY CLOSING AUDIT EVENT EXISTS
        // ====================================================

        Integer closingAuditCount =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_events
                WHERE business_id = ?
                  AND entity_type = 'ACCOUNTING_PERIOD'
                  AND entity_id = ?
                  AND action = 'ACCOUNTING_PERIOD_CLOSED'
                """,
                Integer.class,
                testBusinessId,
                periodId
            );

        assertEquals(1, closingAuditCount);

        // ====================================================
        // VERIFY NO POSTING AUDIT EVENT WAS CREATED
        // ====================================================

        Integer postingAuditCount =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_events
                WHERE business_id = ?
                  AND entity_type = 'TRANSACTION'
                  AND entity_id = ?
                  AND action = 'TRANSACTION_POSTED'
                """,
                Integer.class,
                testBusinessId,
                draft.getId()
            );

        assertEquals(0, postingAuditCount);
    }


    // ============================================================
    // TEST 3 — POSTING FIRST ALLOWS POSTING BEFORE PERIOD CLOSE
    // ============================================================

    /**
     * Verify that posting and period closing are serialized
     * correctly when journal posting obtains the period lock first.
     *
     * Thread A:
     *
     * 1. Begins a database transaction.
     * 2. Posts a DRAFT journal transaction.
     * 3. Holds the accounting-period lock without committing.
     *
     * Thread B:
     *
     * 1. Attempts to close the same accounting period.
     * 2. Must wait for Thread A's transaction to finish.
     *
     * After Thread A commits:
     *
     * - The transaction remains POSTED.
     * - The accounting period becomes CLOSED.
     * - Both operations have exactly one audit event.
     *
     * IMPORTANT:
     *
     * This test does not use @Transactional at the
     * class or method level.
     *
     * Worker threads must use separate database
     * connections and independent transactions.
     */
    @Test
    void postingFirstAllowsPostingBeforePeriodCloses()
            throws Exception {

        // ====================================================
        // CREATE COMMITTED DRAFT TRANSACTION
        // ====================================================

        LedgerTransaction draft = createDraft();

        assertEquals("DRAFT", draft.getStatus());

        // ====================================================
        // FIND OCTOBER ACCOUNTING PERIOD
        // ====================================================

        /**
         * createDraft() uses October 12, 2026.
         *
         * Find the accounting period containing
         * that transaction date.
         */
        Long periodId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM accounting_periods
            WHERE business_id = ?
              AND ? BETWEEN start_date AND end_date
            """,
            Long.class,
            testBusinessId,
            LocalDate.of(2026, 10, 12)
        );

        assertNotNull(periodId);

        // ====================================================
        // PREPARE WORKER THREADS AND SYNCHRONIZATION
        // ====================================================

        ExecutorService executor =
            Executors.newFixedThreadPool(2);

        /*
         * Thread A signals when posting has succeeded
         * inside an uncommitted database transaction.
         *
         * At that point, PostgreSQL still holds the
         * accounting-period row lock.
         */
        CountDownLatch postingCompletedButUncommitted =
            new CountDownLatch(1);

        /*
         * The main test thread controls when
         * Thread A may commit its posting.
         */
        CountDownLatch allowPostingCommit =
            new CountDownLatch(1);

        /*
         * Thread B signals immediately before
         * attempting to close the period.
         */
        CountDownLatch closingStarted =
            new CountDownLatch(1);

        // ====================================================
        // THREAD A — POST TRANSACTION AND HOLD LOCK
        // ====================================================

        Future<?> postingFuture = executor.submit(() -> {

            /*
             * TransactionTemplate creates an outer
             * transaction around the posting service.
             *
             * TransactionService.postTransaction()
             * joins this transaction.
             *
             * Even after the service returns, the
             * database transaction remains uncommitted
             * until this callback finishes.
             */
            TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

            transactionTemplate.executeWithoutResult(status -> {

                // ============================================
                // POST THE JOURNAL TRANSACTION
                // ============================================

                LedgerTransaction postedTransaction =
                    transactionService.postTransaction(
                        testBusinessId,
                        draft.getId(),
                        testUserId
                    );

                assertEquals(
                    "POSTED",
                    postedTransaction.getStatus()
                );

                // ============================================
                // SIGNAL THAT POSTING HOLDS THE PERIOD LOCK
                // ============================================

                postingCompletedButUncommitted.countDown();

                // ============================================
                // WAIT BEFORE COMMITTING
                // ============================================

                try {

                    if (!allowPostingCommit.await(
                            10,
                            TimeUnit.SECONDS)) {

                        throw new IllegalStateException(
                            "Timed out waiting to commit posting"
                        );
                    }

                } catch (InterruptedException exception) {

                    Thread.currentThread().interrupt();

                    throw new IllegalStateException(
                        "Posting worker interrupted",
                        exception
                    );
                }
            });
        });

        // ====================================================
        // THREAD B — ATTEMPT TO CLOSE THE PERIOD
        // ====================================================

        try {

            // ================================================
            // WAIT UNTIL POSTING HOLDS THE PERIOD LOCK
            // ================================================

            assertTrue(
                postingCompletedButUncommitted.await(
                    5,
                    TimeUnit.SECONDS
                ),
                "Posting should complete before closing starts"
            );

            // ================================================
            // SUBMIT THE CLOSING OPERATION
            // ================================================

            Future<?> closingFuture = executor.submit(() -> {

                closingStarted.countDown();

                accountingPeriodService.closeAccountingPeriod(
                    testBusinessId,
                    periodId,
                    testUserId
                );
            });

            // ================================================
            // CONFIRM CLOSING HAS STARTED
            // ================================================

            assertTrue(
                closingStarted.await(
                    5,
                    TimeUnit.SECONDS
                ),
                "Closing worker should start"
            );

            // ================================================
            // CHECK THAT CLOSING HAS NOT FINISHED
            // ================================================

            /**
             * Because posting still holds the row lock,
             * closing should not complete yet.
             *
             * A timeout is expected while posting
             * remains uncommitted.
             *
             * This checks that closing stays pending
             * during the controlled overlap; the latch
             * alone does not prove that PostgreSQL has
             * already reached its lock-wait state.
             */
            assertThrows(
                TimeoutException.class,
                () -> closingFuture.get(
                    250,
                    TimeUnit.MILLISECONDS
                ),
                "Closing should wait until posting commits"
            );

            // ================================================
            // ALLOW POSTING TO COMMIT
            // ================================================

            allowPostingCommit.countDown();

            postingFuture.get(
                10,
                TimeUnit.SECONDS
            );

            // ================================================
            // CLOSING SHOULD NOW SUCCEED
            // ================================================

            closingFuture.get(
                10,
                TimeUnit.SECONDS
            );

        } finally {

            /*
             * Always release the posting worker,
             * including when a test assertion fails.
             *
             * This prevents the test from leaving
             * a database transaction waiting forever.
             */
            allowPostingCommit.countDown();

            executor.shutdown();

            if (!executor.awaitTermination(
                    15,
                    TimeUnit.SECONDS)) {

                executor.shutdownNow();

                throw new IllegalStateException(
                    "Concurrency workers did not terminate"
                );
            }
        }

        // ====================================================
        // VERIFY TRANSACTION WAS POSTED
        // ====================================================

        String transactionStatus = jdbcTemplate.queryForObject(
            """
            SELECT status
            FROM transactions
            WHERE business_id = ?
              AND id = ?
            """,
            String.class,
            testBusinessId,
            draft.getId()
        );

        assertEquals(
            "POSTED",
            transactionStatus
        );

        // ====================================================
        // VERIFY ACCOUNTING PERIOD IS CLOSED
        // ====================================================

        String periodStatus = jdbcTemplate.queryForObject(
            """
            SELECT status
            FROM accounting_periods
            WHERE business_id = ?
              AND id = ?
            """,
            String.class,
            testBusinessId,
            periodId
        );

        assertEquals(
            "CLOSED",
            periodStatus
        );

        // ====================================================
        // VERIFY TRANSACTION POSTING AUDIT EVENT
        // ====================================================

        Integer postingAuditCount =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_events
                WHERE business_id = ?
                  AND entity_type = 'TRANSACTION'
                  AND entity_id = ?
                  AND action = 'TRANSACTION_POSTED'
                """,
                Integer.class,
                testBusinessId,
                draft.getId()
            );

        assertEquals(
            1,
            postingAuditCount
        );

        // ====================================================
        // VERIFY ACCOUNTING PERIOD CLOSING AUDIT EVENT
        // ====================================================

        Integer closingAuditCount =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_events
                WHERE business_id = ?
                  AND entity_type = 'ACCOUNTING_PERIOD'
                  AND entity_id = ?
                  AND action = 'ACCOUNTING_PERIOD_CLOSED'
                """,
                Integer.class,
                testBusinessId,
                periodId
            );

        assertEquals(
            1,
            closingAuditCount
        );

        // ====================================================
        // VERIFY LEDGER ENTRIES REMAIN INTACT
        // ====================================================

        Integer entryCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM ledger_entries
            WHERE business_id = ?
              AND transaction_id = ?
            """,
            Integer.class,
            testBusinessId,
            draft.getId()
        );

        assertEquals(
            2,
            entryCount
        );
    }
}
