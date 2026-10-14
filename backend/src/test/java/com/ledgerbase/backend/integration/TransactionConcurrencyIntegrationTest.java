package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.dto.CreateLedgerEntryRequest;
import com.ledgerbase.backend.dto.CreateTransactionRequest;
import com.ledgerbase.backend.exception.TransactionConflictException;
import com.ledgerbase.backend.model.LedgerTransaction;
import com.ledgerbase.backend.service.TransactionService;
import com.ledgerbase.backend.service.FiscalYearService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
}
