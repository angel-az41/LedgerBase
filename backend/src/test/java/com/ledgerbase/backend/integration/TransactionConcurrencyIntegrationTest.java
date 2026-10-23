package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.dto.CreateLedgerEntryRequest;
import com.ledgerbase.backend.dto.CreateTransactionRequest;
import com.ledgerbase.backend.exception.TransactionConflictException;
import com.ledgerbase.backend.model.LedgerTransaction;
import com.ledgerbase.backend.service.AccountingPeriodService;
import com.ledgerbase.backend.service.FiscalYearService;
import com.ledgerbase.backend.service.TransactionService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — POSTGRESQL CONCURRENCY INTEGRATION TESTS
// ============================================================

/**
 * Verify financial transaction integrity when multiple
 * operations execute concurrently against PostgreSQL.
 *
 * These tests exercise:
 *
 * - Real Spring Boot services.
 * - MyBatis database operations.
 * - PostgreSQL row-level locking.
 * - Independent database transactions.
 * - Double-posting prevention.
 * - Accounting-period closing coordination.
 * - Financial posting audit records.
 *
 * CONCURRENCY SCENARIOS:
 *
 * - Two requests posting the same DRAFT transaction.
 * - Period closing before a journal can be posted.
 * - Journal posting before its accounting period closes.
 *
 * IMPORTANT:
 *
 * Do not use @Transactional on this test class.
 *
 * Worker threads require independent database
 * transactions and access to committed fixture data.
 *
 * A disposable PostgreSQL database is used so that
 * posted financial records do not require deletion.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class TransactionConcurrencyIntegrationTest {

    // ========================================================
    // ISOLATED POSTGRESQL TEST DATABASE
    // ========================================================

    /**
     * Start PostgreSQL 17 inside a disposable Docker
     * container for this test class.
     *
     * Testcontainers assigns an available host port.
     *
     * This database is completely separate from:
     *
     * - ledgerbase (development)
     * - ledgerbase_test (existing integration tests)
     *
     * Testcontainers manages the container lifecycle.
     */
    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("ledgerbase_concurrency_test")
            .withUsername("ledgerbase_test")
            .withPassword("ledgerbase_test");


    // ========================================================
    // SPRING DATABASE CONFIGURATION
    // ========================================================

    /**
     * Register connection properties before Spring
     * initializes the application context.
     *
     * Both the application DataSource and Flyway
     * migrations must target the same container.
     *
     * A sufficiently large connection pool allows
     * worker threads to use separate connections.
     */
    @DynamicPropertySource
    static void configureDatabase(
            DynamicPropertyRegistry registry) {

        registry.add(
            "spring.datasource.url",
            POSTGRES::getJdbcUrl
        );

        registry.add(
            "spring.datasource.username",
            POSTGRES::getUsername
        );

        registry.add(
            "spring.datasource.password",
            POSTGRES::getPassword
        );

        registry.add(
            "spring.flyway.url",
            POSTGRES::getJdbcUrl
        );

        registry.add(
            "spring.flyway.user",
            POSTGRES::getUsername
        );

        registry.add(
            "spring.flyway.password",
            POSTGRES::getPassword
        );

        registry.add(
            "spring.datasource.hikari.maximum-pool-size",
            () -> "8"
        );
    }


    // ========================================================
    // SPRING DEPENDENCIES
    // ========================================================

    /**
     * Actual service implementations are injected
     * rather than mocked.
     *
     * @Transactional behavior is therefore exercised
     * through Spring-managed service proxies.
     */
    @Autowired
    private TransactionService transactionService;

    @Autowired
    private FiscalYearService fiscalYearService;

    @Autowired
    private AccountingPeriodService accountingPeriodService;

    /**
     * Execute SQL directly for test fixture creation
     * and independent database assertions.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Allows tests to control when outer database
     * transactions commit.
     */
    @Autowired
    private PlatformTransactionManager transactionManager;


    // ========================================================
    // TEST FIXTURE IDENTIFIERS
    // ========================================================

    private Long testUserId;
    private Long testBusinessId;
    private Long cashAccountId;
    private Long revenueAccountId;

    /**
     * Fixed accounting date used throughout the tests.
     *
     * It belongs to the October accounting period
     * of the 2026 fiscal year.
     */
    private static final LocalDate JOURNAL_DATE =
        LocalDate.of(2026, 10, 12);


    // ========================================================
    // TEST DATA SETUP
    // ========================================================

    /**
     * Create an independent test business with:
     *
     * - One user.
     * - One business.
     * - One fiscal year.
     * - Twelve accounting periods.
     * - One cash account.
     * - One revenue account.
     *
     * Because this method is not @Transactional,
     * JDBC inserts commit before worker threads begin.
     *
     * Every test uses a unique business, preventing
     * its records from colliding with other tests.
     */
    @BeforeEach
    void setUpTestData() {

        // ----------------------------------------------------
        // CREATE TEST USER
        // ----------------------------------------------------

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

        // ----------------------------------------------------
        // CREATE TEST BUSINESS
        // ----------------------------------------------------

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

        // ----------------------------------------------------
        // CREATE FISCAL YEAR AND ACCOUNTING PERIODS
        // ----------------------------------------------------

        fiscalYearService.createFiscalYear(
            testBusinessId,
            2026
        );

        // ----------------------------------------------------
        // CREATE CASH ACCOUNT
        // ----------------------------------------------------

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

        // ----------------------------------------------------
        // CREATE REVENUE ACCOUNT
        // ----------------------------------------------------

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


    // ========================================================
    // HELPER — CREATE BALANCED DRAFT TRANSACTION
    // ========================================================

    /**
     * Create a balanced journal entry:
     *
     * Debit Cash:               $500.00
     * Credit Consulting Revenue: $500.00
     *
     * This helper is used by every concurrency test.
     *
     * TransactionService creates the DRAFT record
     * and its ledger entries in one database
     * transaction, committing before it returns.
     *
     * The returned transaction can then be accessed
     * by independent worker threads.
     */
    private LedgerTransaction createDraft() {

        CreateTransactionRequest request =
            new CreateTransactionRequest(
                testUserId,
                "Concurrent posting test",
                JOURNAL_DATE,
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


    // ========================================================
    // HELPER — FIND ACCOUNTING PERIOD
    // ========================================================

    /**
     * Retrieve the accounting period that contains
     * the date of the test journal transaction.
     */
    private Long findAccountingPeriodId() {

        Long periodId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM accounting_periods
            WHERE business_id = ?
              AND ? BETWEEN start_date AND end_date
            """,
            Long.class,
            testBusinessId,
            JOURNAL_DATE
        );

        assertNotNull(periodId);

        return periodId;
    }


    // ========================================================
    // HELPER — READ TRANSACTION STATUS
    // ========================================================

    /**
     * Retrieve the actual transaction status
     * from PostgreSQL instead of relying on an
     * in-memory Java model.
     */
    private String findTransactionStatus(
            Long transactionId) {

        return jdbcTemplate.queryForObject(
            """
            SELECT status
            FROM transactions
            WHERE business_id = ?
              AND id = ?
            """,
            String.class,
            testBusinessId,
            transactionId
        );
    }


    // ========================================================
    // HELPER — READ ACCOUNTING PERIOD STATUS
    // ========================================================

    /**
     * Retrieve the persisted accounting-period status.
     */
    private String findAccountingPeriodStatus(
            Long periodId) {

        return jdbcTemplate.queryForObject(
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
    }


    // ========================================================
    // HELPER — COUNT AUDIT EVENTS
    // ========================================================

    /**
     * Count persisted audit events for a specific
     * business, entity, and action.
     *
     * Examples:
     *
     * TRANSACTION / TRANSACTION_POSTED
     *
     * ACCOUNTING_PERIOD / ACCOUNTING_PERIOD_CLOSED
     */
    private int countAuditEvents(
            String entityType,
            Long entityId,
            String action) {

        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM audit_events
            WHERE business_id = ?
              AND entity_type = ?
              AND entity_id = ?
              AND action = ?
            """,
            Integer.class,
            testBusinessId,
            entityType,
            entityId,
            action
        );

        assertNotNull(count);

        return count;
    }


    // ========================================================
    // HELPER — VERIFY LEDGER ENTRIES
    // ========================================================

    /**
     * Verify that posting did not insert or remove
     * any ledger entries.
     *
     * The original journal should still contain
     * exactly two lines.
     */
    private void assertLedgerEntriesUnchanged(
            Long transactionId) {

        Integer entryCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM ledger_entries
            WHERE business_id = ?
              AND transaction_id = ?
            """,
            Integer.class,
            testBusinessId,
            transactionId
        );

        assertEquals(
            2,
            entryCount,
            "The journal must retain its two entries"
        );

        // ----------------------------------------------------
        // VERIFY SAVED DEBIT AND CREDIT TOTALS
        // ----------------------------------------------------

        BigDecimal debits = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(SUM(debit), 0)
            FROM ledger_entries
            WHERE business_id = ?
              AND transaction_id = ?
            """,
            BigDecimal.class,
            testBusinessId,
            transactionId
        );

        BigDecimal credits = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(SUM(credit), 0)
            FROM ledger_entries
            WHERE business_id = ?
              AND transaction_id = ?
            """,
            BigDecimal.class,
            testBusinessId,
            transactionId
        );

        assertNotNull(debits);
        assertNotNull(credits);

        assertEquals(
            0,
            new BigDecimal("500.00").compareTo(debits)
        );

        assertEquals(
            0,
            new BigDecimal("500.00").compareTo(credits)
        );
    }


    // ========================================================
    // HELPER — WAIT FOR TRANSACTION RELEASE
    // ========================================================

    /**
     * Wait for the test thread to permit an outer
     * transaction to commit.
     *
     * Converts checked interruption into a runtime
     * exception suitable for TransactionTemplate.
     *
     * Always restore the thread's interrupt flag.
     */
    private static void awaitCommitPermission(
            CountDownLatch latch) {

        try {

            if (!latch.await(15, TimeUnit.SECONDS)) {

                throw new IllegalStateException(
                    "Timed out waiting for commit permission"
                );
            }

        } catch (InterruptedException exception) {

            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                "Transaction worker was interrupted",
                exception
            );
        }
    }


    // ========================================================
    // HELPER — SHUT DOWN WORKER THREADS
    // ========================================================

    /**
     * Shut down the executor and wait for its threads
     * to terminate before the test finishes.
     *
     * Do not leave database operations running when
     * the next test begins.
     */
    private static void shutdownExecutor(
            ExecutorService executor) throws InterruptedException {

        executor.shutdown();

        if (!executor.awaitTermination(
                15,
                TimeUnit.SECONDS)) {

            executor.shutdownNow();

            assertTrue(
                executor.awaitTermination(
                    5,
                    TimeUnit.SECONDS
                ),
                "Concurrency workers must terminate"
            );
        }
    }


    // ========================================================
    // TEST — PREVENT CONCURRENT DOUBLE POSTING
    // ========================================================

    /**
     * Two independent workers attempt to post
     * the same DRAFT financial transaction.
     *
     * Expected:
     *
     * - One request succeeds.
     * - One request receives a posting conflict.
     * - Final status is POSTED.
     * - Exactly one posting audit event exists.
     * - Ledger entries remain unchanged.
     *
     * Either worker may obtain the row lock first.
     */
    @Test
    void allowsOnlyOneConcurrentPosting() throws Exception {

        // ----------------------------------------------------
        // CREATE COMMITTED DRAFT
        // ----------------------------------------------------

        LedgerTransaction draft = createDraft();

        assertEquals("DRAFT", draft.getStatus());

        // ----------------------------------------------------
        // PREPARE SYNCHRONIZATION
        // ----------------------------------------------------

        ExecutorService executor =
            Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        // ----------------------------------------------------
        // DEFINE INDEPENDENT POSTING TASK
        // ----------------------------------------------------

        Callable<String> postingTask = () -> {

            ready.countDown();

            if (!start.await(10, TimeUnit.SECONDS)) {

                throw new IllegalStateException(
                    "Timed out waiting to start posting"
                );
            }

            try {

                LedgerTransaction posted =
                    transactionService.postTransaction(
                        testBusinessId,
                        draft.getId(),
                        testUserId
                    );

                assertEquals(
                    "POSTED",
                    posted.getStatus()
                );

                return "POSTED";

            } catch (TransactionConflictException exception) {

                return "CONFLICT";
            }
        };

        try {

            // Submit both independent requests.
            Future<String> first =
                executor.submit(postingTask);

            Future<String> second =
                executor.submit(postingTask);

            // Wait until both workers are ready.
            assertTrue(
                ready.await(10, TimeUnit.SECONDS),
                "Both workers must become ready"
            );

            // Release both posting requests.
            start.countDown();

            // Collect both results.
            String firstResult =
                first.get(20, TimeUnit.SECONDS);

            String secondResult =
                second.get(20, TimeUnit.SECONDS);

            List<String> results =
                List.of(firstResult, secondResult);

            // Exactly one request must succeed.
            assertEquals(
                1L,
                results.stream()
                    .filter("POSTED"::equals)
                    .count()
            );

            // Exactly one request must conflict.
            assertEquals(
                1L,
                results.stream()
                    .filter("CONFLICT"::equals)
                    .count()
            );

        } finally {

            // Ensure workers cannot remain at the gate.
            start.countDown();

            shutdownExecutor(executor);
        }

        // ----------------------------------------------------
        // VERIFY PERSISTED DATABASE STATE
        // ----------------------------------------------------

        assertEquals(
            "POSTED",
            findTransactionStatus(draft.getId())
        );

        assertEquals(
            1,
            countAuditEvents(
                "TRANSACTION",
                draft.getId(),
                "TRANSACTION_POSTED"
            )
        );

        assertLedgerEntriesUnchanged(draft.getId());
    }


    // ========================================================
    // TEST — CLOSING FIRST PREVENTS POSTING
    // ========================================================

    /**
     * Verify that an accounting period closing operation
     * prevents a concurrent posting from succeeding.
     *
     * Thread A:
     *
     * - Closes the accounting period.
     * - Holds the PostgreSQL transaction open.
     *
     * Thread B:
     *
     * - Attempts to post into that same period.
     * - Waits for the period lock.
     *
     * After closing commits:
     *
     * - Period status is CLOSED.
     * - Journal remains DRAFT.
     * - No posting audit event is created.
     */
    @Test
    void closingPeriodFirstPreventsConcurrentPosting()
            throws Exception {

        // ----------------------------------------------------
        // CREATE DRAFT AND FIND ITS ACCOUNTING PERIOD
        // ----------------------------------------------------

        LedgerTransaction draft = createDraft();

        Long periodId = findAccountingPeriodId();

        assertEquals("DRAFT", draft.getStatus());

        // ----------------------------------------------------
        // PREPARE INDEPENDENT WORKERS
        // ----------------------------------------------------

        ExecutorService executor =
            Executors.newFixedThreadPool(2);

        CountDownLatch closedButUncommitted =
            new CountDownLatch(1);

        CountDownLatch allowClosingCommit =
            new CountDownLatch(1);

        CountDownLatch postingStarted =
            new CountDownLatch(1);

        // ----------------------------------------------------
        // THREAD A — CLOSE PERIOD AND HOLD LOCK
        // ----------------------------------------------------

        Future<?> closingFuture = executor.submit(() -> {

            TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

            transactionTemplate.executeWithoutResult(
                status -> {

                    /**
                     * The service joins the surrounding
                     * Spring-managed transaction.
                     *
                     * Its database row lock remains held
                     * after the service method returns.
                     */
                    accountingPeriodService.closeAccountingPeriod(
                        testBusinessId,
                        periodId,
                        testUserId
                    );

                    // Signal that the period is now CLOSED,
                    // but the transaction has not committed.
                    closedButUncommitted.countDown();

                    // Keep the lock until the test allows commit.
                    awaitCommitPermission(allowClosingCommit);
                }
            );
        });

        try {

            // ------------------------------------------------
            // WAIT FOR THE CLOSING TRANSACTION
            // ------------------------------------------------

            assertTrue(
                closedButUncommitted.await(
                    10,
                    TimeUnit.SECONDS
                ),
                "Closing must update the period first"
            );

            // ------------------------------------------------
            // THREAD B — ATTEMPT POSTING
            // ------------------------------------------------

            Future<String> postingFuture =
                executor.submit(() -> {

                    postingStarted.countDown();

                    try {

                        transactionService.postTransaction(
                            testBusinessId,
                            draft.getId(),
                            testUserId
                        );

                        return "POSTED";

                    } catch (TransactionConflictException exception) {

                        return "CONFLICT";
                    }
                });

            assertTrue(
                postingStarted.await(
                    10,
                    TimeUnit.SECONDS
                ),
                "Posting worker must start"
            );

            // ------------------------------------------------
            // VERIFY POSTING REMAINS UNFINISHED
            // ------------------------------------------------

            /**
             * The posting operation should be unable to
             * complete while closing holds the period lock.
             *
             * This short timeout checks observed blocking.
             * It is not definitive proof that the posting
             * connection has reached PostgreSQL's lock wait.
             */
            assertThrows(
                TimeoutException.class,
                () -> postingFuture.get(
                    250,
                    TimeUnit.MILLISECONDS
                ),
                "Posting should wait for the period lock"
            );

            // ------------------------------------------------
            // ALLOW CLOSING TRANSACTION TO COMMIT
            // ------------------------------------------------

            allowClosingCommit.countDown();

            closingFuture.get(
                20,
                TimeUnit.SECONDS
            );

            // ------------------------------------------------
            // VERIFY POSTING IS REJECTED
            // ------------------------------------------------

            assertEquals(
                "CONFLICT",
                postingFuture.get(
                    20,
                    TimeUnit.SECONDS
                )
            );

        } finally {

            // Release the closing worker even if a test fails.
            allowClosingCommit.countDown();

            shutdownExecutor(executor);
        }

        // ----------------------------------------------------
        // VERIFY PERIOD AND JOURNAL STATUS
        // ----------------------------------------------------

        assertEquals(
            "CLOSED",
            findAccountingPeriodStatus(periodId)
        );

        assertEquals(
            "DRAFT",
            findTransactionStatus(draft.getId())
        );

        // ----------------------------------------------------
        // VERIFY AUDIT RECORDS
        // ----------------------------------------------------

        assertEquals(
            1,
            countAuditEvents(
                "ACCOUNTING_PERIOD",
                periodId,
                "ACCOUNTING_PERIOD_CLOSED"
            )
        );

        assertEquals(
            0,
            countAuditEvents(
                "TRANSACTION",
                draft.getId(),
                "TRANSACTION_POSTED"
            )
        );

        // The rejected posting cannot modify ledger entries.
        assertLedgerEntriesUnchanged(draft.getId());
    }


    // ========================================================
    // TEST — POSTING FIRST ALLOWS PERIOD CLOSING
    // ========================================================

    /**
     * Verify the opposite transaction ordering.
     *
     * Thread A:
     *
     * - Posts the journal entry successfully.
     * - Keeps the outer database transaction open.
     * - Holds the accounting-period lock.
     *
     * Thread B:
     *
     * - Attempts to close the accounting period.
     * - Waits until the posting transaction commits.
     *
     * Expected final state:
     *
     * - Journal is POSTED.
     * - Accounting period is CLOSED.
     * - Each operation creates one audit event.
     * - Ledger entries remain balanced and unchanged.
     */
    @Test
    void postingFirstAllowsPostingBeforePeriodCloses()
            throws Exception {

        // ----------------------------------------------------
        // CREATE DRAFT AND FIND ACCOUNTING PERIOD
        // ----------------------------------------------------

        LedgerTransaction draft = createDraft();

        Long periodId = findAccountingPeriodId();

        assertEquals("DRAFT", draft.getStatus());

        // ----------------------------------------------------
        // PREPARE WORKERS AND SYNCHRONIZATION
        // ----------------------------------------------------

        ExecutorService executor =
            Executors.newFixedThreadPool(2);

        CountDownLatch postedButUncommitted =
            new CountDownLatch(1);

        CountDownLatch allowPostingCommit =
            new CountDownLatch(1);

        CountDownLatch closingStarted =
            new CountDownLatch(1);

        // ----------------------------------------------------
        // THREAD A — POST JOURNAL AND HOLD PERIOD LOCK
        // ----------------------------------------------------

        Future<?> postingFuture = executor.submit(() -> {

            TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

            transactionTemplate.executeWithoutResult(
                status -> {

                    /**
                     * The posting service joins the outer
                     * transaction created by TransactionTemplate.
                     *
                     * Although the service returns POSTED,
                     * the database transaction has not
                     * committed yet.
                     */
                    LedgerTransaction posted =
                        transactionService.postTransaction(
                            testBusinessId,
                            draft.getId(),
                            testUserId
                        );

                    assertEquals(
                        "POSTED",
                        posted.getStatus()
                    );

                    // Signal that posting has finished but
                    // still holds its database row locks.
                    postedButUncommitted.countDown();

                    // Keep the lock until the test allows commit.
                    awaitCommitPermission(allowPostingCommit);
                }
            );
        });

        try {

            // ------------------------------------------------
            // WAIT UNTIL POSTING HOLDS THE LOCK
            // ------------------------------------------------

            assertTrue(
                postedButUncommitted.await(
                    10,
                    TimeUnit.SECONDS
                ),
                "Posting must finish before closing starts"
            );

            // ------------------------------------------------
            // THREAD B — ATTEMPT TO CLOSE PERIOD
            // ------------------------------------------------

            Future<?> closingFuture = executor.submit(() -> {

                closingStarted.countDown();

                accountingPeriodService.closeAccountingPeriod(
                    testBusinessId,
                    periodId,
                    testUserId
                );
            });

            assertTrue(
                closingStarted.await(
                    10,
                    TimeUnit.SECONDS
                ),
                "Closing worker must start"
            );

            // ------------------------------------------------
            // VERIFY CLOSING REMAINS UNFINISHED
            // ------------------------------------------------

            /**
             * Closing should remain pending while the
             * posting transaction holds the period lock.
             *
             * This bounded wait checks the observed ordering.
             */
            assertThrows(
                TimeoutException.class,
                () -> closingFuture.get(
                    250,
                    TimeUnit.MILLISECONDS
                ),
                "Closing should wait for the posting lock"
            );

            // ------------------------------------------------
            // ALLOW POSTING TO COMMIT
            // ------------------------------------------------

            allowPostingCommit.countDown();

            postingFuture.get(
                20,
                TimeUnit.SECONDS
            );

            // ------------------------------------------------
            // VERIFY CLOSING SUCCEEDS AFTER POSTING
            // ------------------------------------------------

            closingFuture.get(
                20,
                TimeUnit.SECONDS
            );

        } finally {

            // Always release the posting transaction.
            allowPostingCommit.countDown();

            shutdownExecutor(executor);
        }

        // ----------------------------------------------------
        // VERIFY POSTED JOURNAL
        // ----------------------------------------------------

        assertEquals(
            "POSTED",
            findTransactionStatus(draft.getId())
        );

        // ----------------------------------------------------
        // VERIFY CLOSED ACCOUNTING PERIOD
        // ----------------------------------------------------

        assertEquals(
            "CLOSED",
            findAccountingPeriodStatus(periodId)
        );

        // ----------------------------------------------------
        // VERIFY POSTING AUDIT EVENT
        // ----------------------------------------------------

        assertEquals(
            1,
            countAuditEvents(
                "TRANSACTION",
                draft.getId(),
                "TRANSACTION_POSTED"
            )
        );

        // ----------------------------------------------------
        // VERIFY ACCOUNTING PERIOD CLOSING AUDIT EVENT
        // ----------------------------------------------------

        assertEquals(
            1,
            countAuditEvents(
                "ACCOUNTING_PERIOD",
                periodId,
                "ACCOUNTING_PERIOD_CLOSED"
            )
        );

        // ----------------------------------------------------
        // VERIFY LEDGER ENTRY INTEGRITY
        // ----------------------------------------------------

        assertLedgerEntriesUnchanged(draft.getId());
    }

    // ========================================================
    // TEST — CONCURRENT JOURNAL-NUMBER GENERATION
    // ========================================================

    /**
     * Verify that multiple concurrent journal creations
     * receive unique, sequential, business-specific
     * journal numbers.
     *
     * TEST SCENARIO:
     *
     * Six independent worker threads attempt to create
     * DRAFT financial transactions for the same business.
     *
     * All requests start from a shared synchronization
     * point using CountDownLatch.
     *
     * EXPECTED RESULTS:
     *
     * - All six transactions are created successfully.
     * - Each transaction has a unique global ID.
     * - Every transaction remains DRAFT.
     * - Journal numbers are 1 through 6.
     * - No duplicate journal numbers exist.
     * - The business counter advances to 6.
     * - All six creation audit events are recorded.
     *
     * The specific worker that receives each journal
     * number is intentionally unspecified.
     *
     * This test reuses the isolated PostgreSQL
     * Testcontainer configured for this class.
     */
    @Test
    void assignsUniqueSequentialJournalNumbersUnderConcurrentCreation()
            throws Exception {

        // ----------------------------------------------------
        // CONFIGURE THE CONCURRENT WORKLOAD
        // ----------------------------------------------------

        final int journalCount = 6;

        ExecutorService executor =
            Executors.newFixedThreadPool(journalCount);

        /**
         * Every worker announces that it is ready
         * before attempting to create a transaction.
         */
        CountDownLatch ready =
            new CountDownLatch(journalCount);

        /**
         * The main test thread releases all workers
         * from the same starting gate.
         */
        CountDownLatch start =
            new CountDownLatch(1);

        // Capture submitted tasks.
        List<Future<LedgerTransaction>> futures =
            new ArrayList<>();

        // Capture successfully created transactions.
        List<LedgerTransaction> createdTransactions =
            new ArrayList<>();


        // ----------------------------------------------------
        // EXECUTE CONCURRENT CREATION REQUESTS
        // ----------------------------------------------------

        try {

            /**
             * Submit multiple independent requests.
             *
             * Each worker calls the real TransactionService
             * and therefore executes its own Spring-managed
             * database transaction.
             */
            for (int i = 0; i < journalCount; i++) {

                Future<LedgerTransaction> future =
                    executor.submit(() -> {

                        // Signal that this worker is ready.
                        ready.countDown();

                        // Wait until all workers may start.
                        if (!start.await(
                                10,
                                TimeUnit.SECONDS)) {

                            throw new IllegalStateException(
                                "Timed out waiting to create journal"
                            );
                        }

                        /**
                         * createDraft() uses the existing
                         * transaction service and a balanced
                         * debit/credit request.
                         *
                         * PostgreSQL assigns the journal
                         * number through the V8 trigger.
                         */
                        return createDraft();
                    });

                futures.add(future);
            }


            // ------------------------------------------------
            // ENSURE ALL WORKERS ARE READY
            // ------------------------------------------------

            assertTrue(
                ready.await(
                    10,
                    TimeUnit.SECONDS
                ),
                "All journal-creation workers must be ready"
            );


            // ------------------------------------------------
            // RELEASE ALL WORKERS
            // ------------------------------------------------

            start.countDown();


            // ------------------------------------------------
            // COLLECT ALL RESULTS
            // ------------------------------------------------

            /**
             * If any worker encounters a database error,
             * Future.get() propagates the failure.
             *
             * We do not silently ignore deadlocks,
             * duplicate-key errors, or other exceptions.
             */
            for (Future<LedgerTransaction> future : futures) {

                LedgerTransaction transaction =
                    future.get(
                        30,
                        TimeUnit.SECONDS
                    );

                assertNotNull(transaction);

                createdTransactions.add(transaction);
            }

        } finally {

            // Always release workers waiting at the gate.
            start.countDown();

            // Stop all workers before querying final results.
            shutdownExecutor(executor);
        }


        // ====================================================
        // ASSERT — ALL SIX TRANSACTIONS WERE CREATED
        // ====================================================

        assertEquals(
            journalCount,
            createdTransactions.size()
        );

        for (LedgerTransaction transaction
                : createdTransactions) {

            assertNotNull(
                transaction.getId(),
                "Every transaction must have a database ID"
            );

            assertEquals(
                "DRAFT",
                transaction.getStatus()
            );

            assertNotNull(
                transaction.getJournalNumber(),
                "Every transaction must have a journal number"
            );

            assertNotNull(
                transaction.getJournalReference(),
                "Every transaction must have a journal reference"
            );
        }


        // ====================================================
        // ASSERT — UNIQUE GLOBAL TRANSACTION IDS
        // ====================================================

        long distinctTransactionIds =
            createdTransactions.stream()
                .map(LedgerTransaction::getId)
                .distinct()
                .count();

        assertEquals(
            journalCount,
            distinctTransactionIds,
            "Each transaction must have a unique internal ID"
        );


        // ====================================================
        // ASSERT — UNIQUE JOURNAL NUMBERS
        // ====================================================

        long distinctJournalNumbers =
            createdTransactions.stream()
                .map(LedgerTransaction::getJournalNumber)
                .distinct()
                .count();

        assertEquals(
            journalCount,
            distinctJournalNumbers,
            "Concurrent requests must not receive duplicate journal numbers"
        );


        // ====================================================
        // ASSERT — JOURNAL NUMBERS ARE SEQUENTIAL
        // ====================================================

        /**
         * Query the database directly rather than
         * relying only on returned Java objects.
         *
         * Because this business is new and all six
         * journal creations succeeded, the numbers
         * should form the sequence:
         *
         * 1, 2, 3, 4, 5, 6
         */
        List<Long> storedJournalNumbers =
            jdbcTemplate.queryForList(
                """
                SELECT journal_number
                FROM transaction_journal_numbers
                WHERE business_id = ?
                ORDER BY journal_number ASC
                """,
                Long.class,
                testBusinessId
            );

        assertEquals(
            journalCount,
            storedJournalNumbers.size()
        );

        for (int i = 0; i < journalCount; i++) {

            long expectedNumber = i + 1L;

            assertEquals(
                expectedNumber,
                storedJournalNumbers.get(i),
                "Journal numbers must be sequential"
            );
        }


        // ====================================================
        // ASSERT — BUSINESS JOURNAL COUNTER
        // ====================================================

        /**
         * Verify that the PostgreSQL business counter
         * advanced exactly once for each successful
         * transaction creation.
         */
        Long lastJournalNumber =
            jdbcTemplate.queryForObject(
                """
                SELECT last_journal_number
                FROM business_journal_counters
                WHERE business_id = ?
                """,
                Long.class,
                testBusinessId
            );

        assertNotNull(lastJournalNumber);

        assertEquals(
            journalCount,
            lastJournalNumber.longValue(),
            "The business counter must equal successful creations"
        );


        // ====================================================
        // ASSERT — DATABASE TRANSACTION COUNT
        // ====================================================

        Integer transactionCount =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM transactions
                WHERE business_id = ?
                  AND status = 'DRAFT'
                """,
                Integer.class,
                testBusinessId
            );

        assertEquals(
            journalCount,
            transactionCount,
            "All six draft transactions must be persisted"
        );


        // ====================================================
        // ASSERT — CREATION AUDIT EVENTS
        // ====================================================

        Integer auditCount =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_events
                WHERE business_id = ?
                  AND entity_type = 'TRANSACTION'
                  AND action = 'TRANSACTION_CREATED'
                """,
                Integer.class,
                testBusinessId
            );

        assertEquals(
            journalCount,
            auditCount,
            "Every successful creation must have an audit event"
        );


        // ====================================================
        // ASSERT — JOURNAL ENTRIES REMAIN BALANCED
        // ====================================================

        /**
         * Every successful transaction must preserve
         * its original double-entry accounting lines.
         */
        for (LedgerTransaction transaction
                : createdTransactions) {

            assertLedgerEntriesUnchanged(
                transaction.getId()
            );
        }
    }

    // ========================================================
    // TEST — JOURNAL NUMBERING ROLLBACK SAFETY
    // ========================================================

    /**
     * Verify that a failed transaction does not
     * permanently consume a journal number.
     *
     * The counter update, journal mapping, ledger
     * entries, and audit event must all roll back.
     *
     * The next successful creation should then
     * receive JE-0001.
     */
    @Test
    void rollsBackJournalNumberWhenCreationTransactionFails() {

        TransactionTemplate transactionTemplate =
            new TransactionTemplate(transactionManager);

        // ----------------------------------------------------
        // CREATE A JOURNAL AND FORCE ROLLBACK
        // ----------------------------------------------------

        assertThrows(
            IllegalStateException.class,
            () -> transactionTemplate.execute(status -> {

                LedgerTransaction draft =
                    createDraft();

                assertEquals(
                    1L,
                    draft.getJournalNumber().longValue()
                );

                // Force the surrounding transaction to
                // roll back after journal allocation.
                throw new IllegalStateException(
                    "Intentional journal rollback"
                );
            })
        );

        // ----------------------------------------------------
        // VERIFY THE COUNTER WAS ROLLED BACK
        // ----------------------------------------------------

        Integer counterCount =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM business_journal_counters
                WHERE business_id = ?
                """,
                Integer.class,
                testBusinessId
            );

        assertEquals(0, counterCount);

        // ----------------------------------------------------
        // VERIFY NO TRANSACTION WAS COMMITTED
        // ----------------------------------------------------

        Integer transactionCount =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM transactions
                WHERE business_id = ?
                """,
                Integer.class,
                testBusinessId
            );

        assertEquals(0, transactionCount);

        // ----------------------------------------------------
        // VERIFY NO JOURNAL MAPPING REMAINS
        // ----------------------------------------------------

        Integer mappingCount =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM transaction_journal_numbers
                WHERE business_id = ?
                """,
                Integer.class,
                testBusinessId
            );

        assertEquals(0, mappingCount);

        // ----------------------------------------------------
        // VERIFY CREATION AUDIT WAS ALSO ROLLED BACK
        // ----------------------------------------------------

        Integer auditCount =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_events
                WHERE business_id = ?
                  AND action = 'TRANSACTION_CREATED'
                """,
                Integer.class,
                testBusinessId
            );

        assertEquals(0, auditCount);

        // ----------------------------------------------------
        // CREATE A NEW SUCCESSFUL JOURNAL
        // ----------------------------------------------------

        LedgerTransaction successfulDraft =
            createDraft();

        assertNotNull(successfulDraft.getId());

        // The failed creation must not consume JE-0001.
        assertEquals(
            1L,
            successfulDraft.getJournalNumber().longValue()
        );

        assertEquals(
            "JE-0001",
            successfulDraft.getJournalReference()
        );

        // Confirm the successfully committed counter.
        Long counter =
            jdbcTemplate.queryForObject(
                """
                SELECT last_journal_number
                FROM business_journal_counters
                WHERE business_id = ?
                """,
                Long.class,
                testBusinessId
            );

        assertEquals(1L, counter);
    }

    // ========================================================
    // TEST — INDEPENDENT CONCURRENT BUSINESS NUMBERING
    // ========================================================

    /**
     * Verify that journal-number allocation for one
     * business does not block another business.
     *
     * Business A allocates a journal number but keeps
     * its database transaction uncommitted.
     *
     * While A holds its counter lock, Business B must
     * still be able to create and commit a journal.
     *
     * Both businesses must receive JE-0001 initially
     * and advance their own counters independently.
     */
    @Test
    void allocatesJournalNumbersIndependentlyAcrossBusinesses()
            throws Exception {

        // ----------------------------------------------------
        // CREATE A SECOND BUSINESS
        // ----------------------------------------------------

        Long businessBId = jdbcTemplate.queryForObject(
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
            "Concurrent Business B",
            "USD"
        );

        assertNotNull(businessBId);

        fiscalYearService.createFiscalYear(
            businessBId,
            2026
        );

        Long cashBId = jdbcTemplate.queryForObject(
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
            businessBId,
            "1000",
            "Cash",
            "ASSET"
        );

        Long revenueBId = jdbcTemplate.queryForObject(
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
            businessBId,
            "4000",
            "Revenue",
            "REVENUE"
        );

        assertNotNull(cashBId);
        assertNotNull(revenueBId);

        // ----------------------------------------------------
        // DEFINE BUSINESS B'S JOURNAL REQUEST
        // ----------------------------------------------------

        CreateTransactionRequest requestB =
            new CreateTransactionRequest(
                testUserId,
                "Concurrent Business B journal",
                LocalDate.of(2026, 10, 12),
                List.of(
                    new CreateLedgerEntryRequest(
                        cashBId,
                        new BigDecimal("500.00"),
                        BigDecimal.ZERO
                    ),
                    new CreateLedgerEntryRequest(
                        revenueBId,
                        BigDecimal.ZERO,
                        new BigDecimal("500.00")
                    )
                )
            );

        // ----------------------------------------------------
        // PREPARE CONCURRENT WORKERS
        // ----------------------------------------------------

        ExecutorService executor =
            Executors.newFixedThreadPool(2);

        CountDownLatch businessAAllocated =
            new CountDownLatch(1);

        CountDownLatch allowBusinessACommit =
            new CountDownLatch(1);

        LedgerTransaction firstA = null;
        LedgerTransaction firstB = null;

        try {

            // ------------------------------------------------
            // WORKER A — ALLOCATE NUMBER AND HOLD TRANSACTION
            // ------------------------------------------------

            Future<LedgerTransaction> firstFuture =
                executor.submit(() -> {

                    TransactionTemplate template =
                        new TransactionTemplate(
                            transactionManager
                        );

                    return template.execute(status -> {

                        /**
                         * createDraft() joins the existing
                         * transaction and allocates JE-0001
                         * through the PostgreSQL trigger.
                         */
                        LedgerTransaction draft =
                            createDraft();

                        // The journal counter is updated,
                        // but this transaction has not committed.
                        businessAAllocated.countDown();

                        try {

                            if (!allowBusinessACommit.await(
                                    30,
                                    TimeUnit.SECONDS)) {

                                throw new IllegalStateException(
                                    "Timed out waiting to commit Business A"
                                );
                            }

                        } catch (InterruptedException exception) {

                            Thread.currentThread().interrupt();

                            throw new IllegalStateException(
                                "Business A worker interrupted",
                                exception
                            );
                        }

                        return draft;
                    });
                });

            // ------------------------------------------------
            // WAIT UNTIL BUSINESS A HOLDS ITS COUNTER LOCK
            // ------------------------------------------------

            assertTrue(
                businessAAllocated.await(
                    10,
                    TimeUnit.SECONDS
                ),
                "Business A must allocate before B starts"
            );

            // ------------------------------------------------
            // WORKER B — CREATE WHILE A IS UNCOMMITTED
            // ------------------------------------------------

            Future<LedgerTransaction> secondFuture =
                executor.submit(() ->
                    transactionService.createDraftTransaction(
                        businessBId,
                        requestB
                    )
                );

            /**
             * Business B must commit successfully while
             * Business A still holds its counter lock.
             *
             * A global numbering lock would incorrectly
             * serialize these unrelated businesses.
             */
            firstB = secondFuture.get(
                10,
                TimeUnit.SECONDS
            );

            assertNotNull(firstB);

            assertEquals(
                1L,
                firstB.getJournalNumber().longValue()
            );

            assertEquals(
                "JE-0001",
                firstB.getJournalReference()
            );

            // Business A must still be waiting to commit.
            assertFalse(
                firstFuture.isDone(),
                "Business A should remain uncommitted"
            );

            // ------------------------------------------------
            // RELEASE BUSINESS A AND COMPLETE ITS TRANSACTION
            // ------------------------------------------------

            allowBusinessACommit.countDown();

            firstA = firstFuture.get(
                10,
                TimeUnit.SECONDS
            );

        } finally {

            // Release A even when an assertion fails.
            allowBusinessACommit.countDown();

            // Reuse the existing worker-shutdown helper.
            shutdownExecutor(executor);
        }

        // ====================================================
        // VERIFY BOTH BUSINESSES RECEIVED JE-0001
        // ====================================================

        assertNotNull(firstA);
        assertNotNull(firstB);

        assertEquals(
            1L,
            firstA.getJournalNumber().longValue()
        );

        assertEquals(
            "JE-0001",
            firstA.getJournalReference()
        );

        assertNotEquals(
            firstA.getId(),
            firstB.getId()
        );

        // ====================================================
        // VERIFY INDEPENDENT POSTGRESQL COUNTERS
        // ====================================================

        Long counterA = jdbcTemplate.queryForObject(
            """
            SELECT last_journal_number
            FROM business_journal_counters
            WHERE business_id = ?
            """,
            Long.class,
            testBusinessId
        );

        Long counterB = jdbcTemplate.queryForObject(
            """
            SELECT last_journal_number
            FROM business_journal_counters
            WHERE business_id = ?
            """,
            Long.class,
            businessBId
        );

        assertEquals(1L, counterA);
        assertEquals(1L, counterB);

        // ====================================================
        // VERIFY SUBSEQUENT NUMBERING IS INDEPENDENT
        // ====================================================

        LedgerTransaction secondA = createDraft();

        LedgerTransaction secondB =
            transactionService.createDraftTransaction(
                businessBId,
                requestB
            );

        assertEquals(
            "JE-0002",
            secondA.getJournalReference()
        );

        assertEquals(
            "JE-0002",
            secondB.getJournalReference()
        );
    }
}
