package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.dto.CreateLedgerEntryRequest;
import com.ledgerbase.backend.dto.CreateTransactionRequest;
import com.ledgerbase.backend.model.LedgerTransaction;
import com.ledgerbase.backend.service.FiscalYearService;
import com.ledgerbase.backend.service.TransactionService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — COMMITTED POSTING REVISION INTEGRATION TEST
// ============================================================

/**
 * Verify that a successfully posted financial transaction
 * and its reporting revision survive an actual database commit.
 *
 * Unlike TransactionIntegrationTest, this test deliberately
 * does not use @Transactional.
 *
 * TransactionService therefore controls the commit boundary.
 *
 * After postTransaction() returns, independent JDBC reads
 * verify the committed PostgreSQL state.
 *
 * IMPORTANT TEST-DATA POLICY:
 *
 * LedgerBase deliberately protects posted financial records
 * against deletion.
 *
 * This test therefore retains one isolated, immutable posting
 * in ledgerbase_test when explicitly executed.
 *
 * It is opt-in and does not run during ordinary test suites.
 *
 * Never execute this test against a development or production
 * database.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(
    named = "ledgerbase.verifyCommittedPosting",
    matches = "true"
)
class CommittedPostingRevisionIntegrationTest {

    // ========================================================
    // SERVICE DEPENDENCIES
    // ========================================================

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private FiscalYearService fiscalYearService;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    // ========================================================
    // TEST — VERIFY REAL POSTING COMMIT
    // ========================================================

    /**
     * Verify that:
     *
     * 1. A business starts with revision zero.
     * 2. Creating a DRAFT does not increase the revision.
     * 3. Posting the DRAFT increases the revision by one.
     * 4. Both changes remain committed after the service returns.
     * 5. The posting audit event is also committed.
     */
    @Test
    void persistsReportingRevisionAfterCommittedPosting() {

        // ====================================================
        // VERIFY ISOLATED TEST DATABASE
        // ====================================================

        String databaseName = jdbcTemplate.queryForObject(
            "SELECT current_database()",
            String.class
        );

        assertEquals(
            "ledgerbase_test",
            databaseName,
            "Committed posting tests must use ledgerbase_test"
        );

        // ====================================================
        // CREATE AN ISOLATED TEST USER
        // ====================================================

        String uniqueId = UUID.randomUUID().toString();

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
            "committed-posting-" + uniqueId
                + "@ledgerbase.local",
            "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
            "Committed Posting Test User"
        );

        assertNotNull(userId);

        // ====================================================
        // CREATE TEST BUSINESS
        // ====================================================

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
            "Committed Posting Test " + uniqueId,
            "USD"
        );

        assertNotNull(businessId);

        // ====================================================
        // CREATE OWNER MEMBERSHIP
        // ====================================================

        /*
         * The test business must have an explicit OWNER
         * membership under LedgerBase's RBAC schema.
         */
        int membershipCount = jdbcTemplate.update(
            """
            INSERT INTO business_memberships (
                business_id,
                user_id,
                role
            )
            VALUES (?, ?, 'OWNER')
            """,
            businessId,
            userId
        );

        assertEquals(1, membershipCount);

        // ====================================================
        // CREATE FISCAL YEAR AND ACCOUNTING PERIODS
        // ====================================================

        /*
         * Posting requires an open accounting period.
         *
         * The production FiscalYearService creates the
         * corresponding accounting calendar for 2026.
         */
        fiscalYearService.createFiscalYear(
            businessId,
            2026
        );

        // ====================================================
        // CREATE CASH ACCOUNT
        // ====================================================

        Long cashAccountId = jdbcTemplate.queryForObject(
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
            "1000",
            "Cash",
            "ASSET"
        );

        // ====================================================
        // CREATE REVENUE ACCOUNT
        // ====================================================

        Long revenueAccountId = jdbcTemplate.queryForObject(
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
            "4000",
            "Consulting Revenue",
            "REVENUE"
        );

        assertNotNull(cashAccountId);
        assertNotNull(revenueAccountId);

        // ====================================================
        // VERIFY INITIAL REPORTING REVISION
        // ====================================================

        Long initialRevision = jdbcTemplate.queryForObject(
            """
            SELECT reporting_revision
            FROM businesses
            WHERE id = ?
            """,
            Long.class,
            businessId
        );

        assertNotNull(initialRevision);
        assertEquals(0L, initialRevision.longValue());

        // ====================================================
        // CREATE BALANCED DRAFT TRANSACTION
        // ====================================================

        CreateTransactionRequest request =
            new CreateTransactionRequest(
                userId,
                "Committed posting verification",
                LocalDate.of(2026, 10, 11),
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

        LedgerTransaction draft =
            transactionService.createDraftTransaction(
                businessId,
                request
            );

        assertNotNull(draft.getId());
        assertEquals("DRAFT", draft.getStatus());

        // ====================================================
        // VERIFY DRAFT CREATION DOES NOT ADVANCE REVISION
        // ====================================================

        Long revisionAfterDraft =
            jdbcTemplate.queryForObject(
                """
                SELECT reporting_revision
                FROM businesses
                WHERE id = ?
                """,
                Long.class,
                businessId
            );

        assertEquals(
            initialRevision,
            revisionAfterDraft,
            "Draft creation must not advance reporting revision"
        );

        // ====================================================
        // POST THE TRANSACTION
        // ====================================================

        /*
         * This call executes outside any test-managed transaction.
         *
         * TransactionService's @Transactional method therefore
         * starts and commits its own PostgreSQL transaction.
         */
        LedgerTransaction posted =
            transactionService.postTransaction(
                businessId,
                draft.getId(),
                userId
            );

        assertEquals("POSTED", posted.getStatus());

        // ====================================================
        // VERIFY COMMITTED TRANSACTION STATUS
        // ====================================================

        /*
         * There is no surrounding Spring test transaction.
         *
         * This JDBC query executes after the posting service
         * has returned from its commit boundary.
         */
        String committedStatus = jdbcTemplate.queryForObject(
            """
            SELECT status
            FROM transactions
            WHERE business_id = ?
              AND id = ?
            """,
            String.class,
            businessId,
            draft.getId()
        );

        assertEquals(
            "POSTED",
            committedStatus,
            "PostgreSQL must persist the POSTED status"
        );

        // ====================================================
        // VERIFY COMMITTED REPORTING REVISION
        // ====================================================

        Long committedRevision =
            jdbcTemplate.queryForObject(
                """
                SELECT reporting_revision
                FROM businesses
                WHERE id = ?
                """,
                Long.class,
                businessId
            );

        assertNotNull(committedRevision);

        assertEquals(
            initialRevision + 1,
            committedRevision,
            "PostgreSQL must commit the reporting revision"
        );

        // ====================================================
        // VERIFY COMMITTED POSTING AUDIT
        // ====================================================

        Integer auditCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM audit_events
            WHERE business_id = ?
              AND entity_type = 'TRANSACTION'
              AND entity_id = ?
              AND action = 'TRANSACTION_POSTED'
            """,
            Integer.class,
            businessId,
            draft.getId()
        );

        assertEquals(
            1,
            auditCount,
            "The posting audit event must be committed"
        );
    }
}
