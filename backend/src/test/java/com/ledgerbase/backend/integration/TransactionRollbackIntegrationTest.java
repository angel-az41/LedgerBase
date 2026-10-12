package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.dto.CreateLedgerEntryRequest;
import com.ledgerbase.backend.dto.CreateTransactionRequest;
import com.ledgerbase.backend.service.TransactionService;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests transaction rollback after a real PostgreSQL
 * INSERT failure.
 *
 * Unlike our other integration tests, this class does
 * NOT have @Transactional.
 *
 * We want TransactionService to manage and roll back
 * its own database transaction.
 *
 * Test records are explicitly deleted afterward.
 */
@SpringBootTest
@ActiveProfiles("test")
class TransactionRollbackIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // IDs for temporary test records.
    private Long testUserId;
    private Long testBusinessId;
    private Long cashAccountId;
    private Long revenueAccountId;

    /**
     * Prepare a real user, business, and two accounts.
     *
     * These fixture records are committed independently
     * because this test has no outer transaction.
     */
    @BeforeEach
    void setUp() {

        String email = "rollback-test-"
            + UUID.randomUUID()
            + "@ledgerbase.local";

        testUserId = jdbcTemplate.queryForObject(
            """
            INSERT INTO users (
                email, password_hash, full_name
            )
            VALUES (?, ?, ?)
            RETURNING id
            """,
            Long.class,
            email,
            "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
            "Rollback Test User"
        );

        testBusinessId = jdbcTemplate.queryForObject(
            """
            INSERT INTO businesses (
                owner_id, name, currency
            )
            VALUES (?, ?, ?)
            RETURNING id
            """,
            Long.class,
            testUserId,
            "Rollback Test Business",
            "USD"
        );

        cashAccountId = jdbcTemplate.queryForObject(
            """
            INSERT INTO accounts (
                business_id, code, name, account_type
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

        revenueAccountId = jdbcTemplate.queryForObject(
            """
            INSERT INTO accounts (
                business_id, code, name, account_type
            )
            VALUES (?, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            testBusinessId,
            "4000",
            "Revenue",
            "REVENUE"
        );
    }

    /**
     * Verify that PostgreSQL rolls back all records
     * when a ledger entry INSERT fails midway.
     *
     * The amounts are mathematically balanced:
     *
     * Debit Cash: 1.00
     * Credit Revenue: 10000000000000000.00
     * Debit Cash: 9999999999999999.00
     *
     * Total debits = total credits.
     *
     * However, the second entry's credit exceeds
     * the range of PostgreSQL NUMERIC(18,2).
     *
     * Therefore:
     * 1. Transaction header INSERT succeeds.
     * 2. First ledger entry INSERT succeeds.
     * 3. Second ledger entry INSERT fails.
     * 4. Spring must roll back everything.
     */
    @Test
    void rollsBackWhenLedgerEntryInsertFails() {

        CreateTransactionRequest request =
            new CreateTransactionRequest(
                testUserId,
                "Rollback verification",
                LocalDate.of(2026, 10, 11),
                List.of(
                    new CreateLedgerEntryRequest(
                        cashAccountId,
                        new BigDecimal("1.00"),
                        BigDecimal.ZERO
                    ),
                    new CreateLedgerEntryRequest(
                        revenueAccountId,
                        BigDecimal.ZERO,
                        new BigDecimal(
                            "10000000000000000.00"
                        )
                    ),
                    new CreateLedgerEntryRequest(
                        cashAccountId,
                        new BigDecimal(
                            "9999999999999999.00"
                        ),
                        BigDecimal.ZERO
                    )
                )
            );

        // The service should encounter a database error
        // while inserting the second ledger entry.
        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> transactionService.createDraftTransaction(
                testBusinessId,
                request
            )
        );

        // Check that the error actually originated
        // from PostgreSQL's numeric precision constraint.
        boolean numericOverflowFound = false;
        Throwable cause = exception;

        while (cause != null) {

            if (cause.getMessage() != null
                    && cause.getMessage().toLowerCase()
                        .contains("numeric field overflow")) {

                numericOverflowFound = true;
                break;
            }

            cause = cause.getCause();
        }

        assertTrue(
            numericOverflowFound,
            "Expected a PostgreSQL numeric field overflow"
        );

        // The transaction header must have been rolled back.
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

        // The first ledger entry must also have been rolled back.
        Integer entryCount =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM ledger_entries
                WHERE business_id = ?
                """,
                Integer.class,
                testBusinessId
            );

        assertEquals(0, entryCount);
    }

    /**
     * Clean up our temporary fixtures.
     *
     * Since this test class does not use an outer
     * test transaction, @AfterEach removes its data.
     *
     * Delete child records before parent records
     * to respect PostgreSQL foreign keys.
     */
    @AfterEach
    void cleanUp() {

        if (testBusinessId != null) {

            jdbcTemplate.update(
                "DELETE FROM ledger_entries WHERE business_id = ?",
                testBusinessId
            );

            jdbcTemplate.update(
                "DELETE FROM transactions WHERE business_id = ?",
                testBusinessId
            );

            jdbcTemplate.update(
                "DELETE FROM accounts WHERE business_id = ?",
                testBusinessId
            );

            jdbcTemplate.update(
                "DELETE FROM businesses WHERE id = ?",
                testBusinessId
            );
        }

        if (testUserId != null) {
            jdbcTemplate.update(
                "DELETE FROM users WHERE id = ?",
                testUserId
            );
        }
    }
}
