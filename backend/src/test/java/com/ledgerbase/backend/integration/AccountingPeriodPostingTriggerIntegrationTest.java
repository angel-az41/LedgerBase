package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.service.FiscalYearService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// V6 ACCOUNTING PERIOD POSTING TRIGGER INTEGRATION TESTS
// ============================================================

/**
 * Verify PostgreSQL's V6 posting protections directly.
 *
 * These tests intentionally bypass TransactionService.
 *
 * Each test runs inside a programmatically managed
 * database transaction that is always rolled back.
 *
 * This is especially important for PostgreSQL:
 * a SQL statement that raises an exception leaves
 * its transaction in an aborted state.
 *
 * We therefore make no additional SQL calls after
 * an expected database exception.
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountingPeriodPostingTriggerIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FiscalYearService fiscalYearService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final LocalDate POSTING_DATE =
        LocalDate.of(2026, 10, 13);


    // ========================================================
    // TEST 1 — ALLOW POSTING IN AN OPEN PERIOD
    // ========================================================

    @Test
    void allowsDirectSqlPostingInOpenPeriod() {

        withRollbackFixture((userId, businessId) -> {

            Long transactionId = createBalancedDraft(
                userId,
                businessId,
                POSTING_DATE
            );

            int updated = postDirectly(
                businessId,
                transactionId
            );

            assertEquals(1, updated);

            String savedStatus = jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM transactions
                WHERE business_id = ?
                  AND id = ?
                """,
                String.class,
                businessId,
                transactionId
            );

            assertEquals("POSTED", savedStatus);
        });
    }


    // ========================================================
    // TEST 2 — REJECT POSTING IN A CLOSED PERIOD
    // ========================================================

    @Test
    void rejectsDirectSqlPostingInClosedPeriod() {

        withRollbackFixture((userId, businessId) -> {

            Long transactionId = createBalancedDraft(
                userId,
                businessId,
                POSTING_DATE
            );

            // Simulate closing October 2026.
            int closed = jdbcTemplate.update(
                """
                UPDATE accounting_periods
                SET status = 'CLOSED',
                    closed_at = NOW()
                WHERE business_id = ?
                  AND ? BETWEEN start_date AND end_date
                  AND status = 'OPEN'
                """,
                businessId,
                Date.valueOf(POSTING_DATE)
            );

            assertEquals(1, closed);

            DataIntegrityViolationException exception =
                assertThrows(
                    DataIntegrityViolationException.class,
                    () -> postDirectly(
                        businessId,
                        transactionId
                    )
                );

            assertTrue(
                exception.getMostSpecificCause()
                    .getMessage()
                    .contains("accounting period is CLOSED")
            );
        });
    }


    // ========================================================
    // TEST 3 — REJECT POSTING WITHOUT A MATCHING PERIOD
    // ========================================================

    @Test
    void rejectsDirectSqlPostingWithoutAccountingPeriod() {

        withRollbackFixture((userId, businessId) -> {

            // Our fixture creates FY2026, not FY2025.
            LocalDate missingPeriodDate =
                LocalDate.of(2025, 10, 13);

            Long transactionId = createBalancedDraft(
                userId,
                businessId,
                missingPeriodDate
            );

            DataIntegrityViolationException exception =
                assertThrows(
                    DataIntegrityViolationException.class,
                    () -> postDirectly(
                        businessId,
                        transactionId
                    )
                );

            assertTrue(
                exception.getMostSpecificCause()
                    .getMessage()
                    .contains("no accounting period exists")
            );
        });
    }


    // ========================================================
    // TEST 4 — REJECT DIRECT INSERT AS POSTED
    // ========================================================

    @Test
    void rejectsDirectInsertOfPostedTransaction() {

        withRollbackFixture((userId, businessId) -> {

            /**
             * Even though October 2026 is OPEN,
             * a new transaction cannot skip DRAFT
             * and be inserted directly as POSTED.
             */
            DataIntegrityViolationException exception =
                assertThrows(
                    DataIntegrityViolationException.class,
                    () -> jdbcTemplate.update(
                        """
                        INSERT INTO transactions (
                            business_id,
                            created_by,
                            description,
                            transaction_date,
                            status
                        )
                        VALUES (?, ?, ?, ?, 'POSTED')
                        """,
                        businessId,
                        userId,
                        "Invalid direct POSTED insert",
                        Date.valueOf(POSTING_DATE)
                    )
                );

            assertTrue(
                exception.getMostSpecificCause()
                    .getMessage()
                    .contains(
                        "Cannot insert a transaction directly as POSTED"
                    )
            );
        });
    }


    // ========================================================
    // HELPER — ISOLATED ROLLBACK FIXTURE
    // ========================================================

    /**
     * Create a temporary user, business, and fiscal year.
     *
     * TransactionTemplate controls the complete test
     * transaction and rolls it back afterward.
     *
     * Unlike TRUNCATE, this does not delete records
     * belonging to other tests.
     */
    private void withRollbackFixture(
            BiConsumer<Long, Long> testAction) {

        TransactionTemplate template =
            new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status -> {

            try {

                Long userId = jdbcTemplate.queryForObject(
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
                    "v6-trigger-" + UUID.randomUUID()
                        + "@ledgerbase.local",
                    "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
                    "V6 Trigger Test User"
                );

                Long businessId = jdbcTemplate.queryForObject(
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
                    userId,
                    "V6 Trigger Test Business",
                    "USD"
                );

                // Create FY2026 and its 12 OPEN periods.
                fiscalYearService.createFiscalYear(
                    businessId,
                    2026
                );

                testAction.accept(userId, businessId);

            } finally {

                // Always roll back test fixtures.
                status.setRollbackOnly();
            }
        });
    }


    // ========================================================
    // HELPER — CREATE A BALANCED DRAFT
    // ========================================================

    /**
     * Build a valid two-entry journal transaction:
     *
     * Debit Cash:       $100.00
     * Credit Revenue:   $100.00
     *
     * The transaction initially remains DRAFT.
     */
    private Long createBalancedDraft(
            Long userId,
            Long businessId,
            LocalDate transactionDate) {

        Long cashAccountId = createAccount(
            businessId,
            "1000",
            "Cash",
            "ASSET"
        );

        Long revenueAccountId = createAccount(
            businessId,
            "4000",
            "Revenue",
            "REVENUE"
        );

        Long transactionId = jdbcTemplate.queryForObject(
            """
            INSERT INTO transactions (
                business_id,
                created_by,
                description,
                transaction_date,
                status
            )
            VALUES (?, ?, ?, ?, 'DRAFT')
            RETURNING id
            """,
            Long.class,
            businessId,
            userId,
            "V6 trigger balanced journal test",
            Date.valueOf(transactionDate)
        );

        BigDecimal amount = new BigDecimal("100.00");

        // Debit Cash.
        jdbcTemplate.update(
            """
            INSERT INTO ledger_entries (
                business_id,
                transaction_id,
                account_id,
                debit,
                credit
            )
            VALUES (?, ?, ?, ?, ?)
            """,
            businessId,
            transactionId,
            cashAccountId,
            amount,
            BigDecimal.ZERO
        );

        // Credit Revenue.
        jdbcTemplate.update(
            """
            INSERT INTO ledger_entries (
                business_id,
                transaction_id,
                account_id,
                debit,
                credit
            )
            VALUES (?, ?, ?, ?, ?)
            """,
            businessId,
            transactionId,
            revenueAccountId,
            BigDecimal.ZERO,
            amount
        );

        return transactionId;
    }


    // ========================================================
    // HELPER — CREATE ACCOUNT
    // ========================================================

    private Long createAccount(
            Long businessId,
            String code,
            String name,
            String accountType) {

        return jdbcTemplate.queryForObject(
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
            businessId,
            code,
            name,
            accountType
        );
    }


    // ========================================================
    // HELPER — POST DIRECTLY THROUGH SQL
    // ========================================================

    /**
     * Deliberately bypass Java posting validation.
     *
     * Only PostgreSQL's constraints and triggers
     * can protect this database operation.
     */
    private int postDirectly(
            Long businessId,
            Long transactionId) {

        return jdbcTemplate.update(
            """
            UPDATE transactions
            SET status = 'POSTED',
                posted_at = NOW()
            WHERE business_id = ?
              AND id = ?
              AND status = 'DRAFT'
            """,
            businessId,
            transactionId
        );
    }
}
