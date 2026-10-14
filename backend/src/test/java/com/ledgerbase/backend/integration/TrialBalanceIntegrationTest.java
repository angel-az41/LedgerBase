package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.model.TrialBalanceReport;
import com.ledgerbase.backend.model.TrialBalanceRow;
import com.ledgerbase.backend.service.TrialBalanceService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — TRIAL BALANCE INTEGRATION TESTS
// ============================================================

/**
 * Integration tests for Trial Balance reporting.
 *
 * These tests exercise the actual application layers:
 *
 * TrialBalanceService
 *       ↓
 * TrialBalanceMapper
 *       ↓
 * PostgreSQL
 *
 * We use JdbcTemplate only to prepare test records.
 *
 * The actual report is always generated through
 * TrialBalanceService and our MyBatis SQL mapper.
 *
 * IMPORTANT:
 *
 * @ActiveProfiles("test") selects our existing
 * LedgerBase test database configuration.
 *
 * @Transactional rolls back fixtures after each
 * test, preserving our posted-ledger safeguards
 * without requiring manual deletion.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TrialBalanceIntegrationTest {

    // ========================================================
    // TEST DEPENDENCIES
    // ========================================================

    @Autowired
    private TrialBalanceService trialBalanceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ========================================================
    // REPORTING DATE
    // ========================================================

    /**
     * Fixed date used throughout our tests.
     *
     * A fixed date makes the results repeatable,
     * regardless of when the tests are executed.
     */
    private static final LocalDate REPORT_DATE =
        LocalDate.of(2026, 10, 13);

    // ========================================================
    // TEST FIXTURE IDENTIFIERS
    // ========================================================

    private Long testUserId;

    private Long testBusinessId;

    private Long checkingAccountId;
    private Long savingsAccountId;
    private Long revenueAccountId;
    private Long inactiveAccountId;

    private Long otherBusinessId;

    private Long otherCheckingAccountId;
    private Long otherSavingsAccountId;

    // ========================================================
    // CREATE TEMPORARY TEST RECORDS
    // ========================================================

    /**
     * Create two independent businesses with accounts.
     *
     * Business A:
     * - Operating Checking
     * - Business Savings
     * - Sales Revenue
     * - Inactive Account
     *
     * Business B:
     * - Other Checking
     * - Other Savings
     *
     * We do not create transactions during setup.
     * Each test controls its own journal activity.
     */
    @BeforeEach
    void setUp() {

        // ====================================================
        // CREATE TEST USER
        // ====================================================

        String email =
            "trial-balance-test-"
            + UUID.randomUUID()
            + "@ledgerbase.local";

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
            email,
            "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
            "Trial Balance Test User"
        );

        // ====================================================
        // CREATE FIRST BUSINESS
        // ====================================================

        testBusinessId = createBusiness(
            "Trial Balance Test Business"
        );

        // ====================================================
        // CREATE FIRST BUSINESS ACCOUNTS
        // ====================================================

        checkingAccountId = createAccount(
            testBusinessId,
            "1010",
            "Operating Checking",
            "ASSET",
            true
        );

        savingsAccountId = createAccount(
            testBusinessId,
            "1020",
            "Business Savings",
            "ASSET",
            true
        );

        revenueAccountId = createAccount(
            testBusinessId,
            "4000",
            "Sales Revenue",
            "REVENUE",
            true
        );

        inactiveAccountId = createAccount(
            testBusinessId,
            "1090",
            "Inactive Account",
            "ASSET",
            false
        );

        // ====================================================
        // CREATE SECOND BUSINESS
        // ====================================================

        otherBusinessId = createBusiness(
            "Second Trial Balance Test Business"
        );

        otherCheckingAccountId = createAccount(
            otherBusinessId,
            "1010",
            "Other Checking",
            "ASSET",
            true
        );

        otherSavingsAccountId = createAccount(
            otherBusinessId,
            "1020",
            "Other Savings",
            "ASSET",
            true
        );
    }

    // ========================================================
    // TEST 1 — POSTED TRANSACTION BALANCES
    // ========================================================

    /**
     * Transfer $500 from checking into savings.
     *
     * Debit Savings:   500.00
     * Credit Checking: 500.00
     *
     * Expected Trial Balance:
     *
     * Checking:
     *   Debit  = 0
     *   Credit = 500
     *
     * Savings:
     *   Debit  = 500
     *   Credit = 0
     *
     * Total Debits  = 500
     * Total Credits = 500
     */
    @Test
    void calculatesBalancedReportFromPostedTransaction() {

        createJournalEntry(
            testBusinessId,
            savingsAccountId,
            checkingAccountId,
            "500.00",
            REPORT_DATE,
            true
        );

        TrialBalanceReport report =
            trialBalanceService.generateTrialBalance(
                testBusinessId,
                REPORT_DATE
            );

        // Verify report identification.
        assertEquals(
            testBusinessId,
            report.getBusinessId()
        );

        assertEquals(
            REPORT_DATE,
            report.getAsOfDate()
        );

        // All four business accounts remain included.
        assertEquals(
            4,
            report.getRows().size()
        );

        TrialBalanceRow checking =
            findRow(report, checkingAccountId);

        TrialBalanceRow savings =
            findRow(report, savingsAccountId);

        // Checking has a net credit balance.
        assertMoneyEquals(
            "0.00",
            checking.getDebitBalance()
        );

        assertMoneyEquals(
            "500.00",
            checking.getCreditBalance()
        );

        // Savings has a net debit balance.
        assertMoneyEquals(
            "500.00",
            savings.getDebitBalance()
        );

        assertMoneyEquals(
            "0.00",
            savings.getCreditBalance()
        );

        // ====================================================
        // VERIFY REPORT TOTALS
        // ====================================================

        assertMoneyEquals(
            "500.00",
            report.getTotalDebits()
        );

        assertMoneyEquals(
            "500.00",
            report.getTotalCredits()
        );

        assertMoneyEquals(
            "0.00",
            report.getDifference()
        );

        assertTrue(report.isBalanced());
    }

    // ========================================================
    // TEST 2 — EXCLUDE DRAFT TRANSACTIONS
    // ========================================================

    /**
     * A DRAFT journal entry contains debit and
     * credit lines but must not affect reports.
     *
     * Posting is what makes a transaction part
     * of the finalized general ledger.
     */
    @Test
    void excludesDraftTransactions() {

        createJournalEntry(
            testBusinessId,
            savingsAccountId,
            checkingAccountId,
            "250.00",
            REPORT_DATE,
            false
        );

        TrialBalanceReport report =
            trialBalanceService.generateTrialBalance(
                testBusinessId,
                REPORT_DATE
            );

        assertMoneyEquals(
            "0.00",
            report.getTotalDebits()
        );

        assertMoneyEquals(
            "0.00",
            report.getTotalCredits()
        );

        assertTrue(report.isBalanced());

        // Every account remains in the report,
        // but each balance must be zero.
        for (TrialBalanceRow row : report.getRows()) {

            assertMoneyEquals(
                "0.00",
                row.getDebitBalance()
            );

            assertMoneyEquals(
                "0.00",
                row.getCreditBalance()
            );
        }
    }

    // ========================================================
    // TEST 3 — REPORTING DATE FILTER
    // ========================================================

    /**
     * Verify that asOfDate includes transactions
     * ON the selected date but excludes later dates.
     *
     * Transaction A:
     * October 13 — $100
     *
     * Transaction B:
     * October 14 — $900
     *
     * Expected:
     *
     * October 12 report: $0
     * October 13 report: $100
     * October 14 report: $1,000
     */
    @Test
    void respectsInclusiveReportingDate() {

        createJournalEntry(
            testBusinessId,
            savingsAccountId,
            checkingAccountId,
            "100.00",
            REPORT_DATE,
            true
        );

        createJournalEntry(
            testBusinessId,
            savingsAccountId,
            checkingAccountId,
            "900.00",
            REPORT_DATE.plusDays(1),
            true
        );

        // ====================================================
        // REPORT BEFORE BOTH TRANSACTIONS
        // ====================================================

        TrialBalanceReport before =
            trialBalanceService.generateTrialBalance(
                testBusinessId,
                REPORT_DATE.minusDays(1)
            );

        assertMoneyEquals(
            "0.00",
            before.getTotalDebits()
        );

        // ====================================================
        // REPORT ON FIRST TRANSACTION DATE
        // ====================================================

        TrialBalanceReport onDate =
            trialBalanceService.generateTrialBalance(
                testBusinessId,
                REPORT_DATE
            );

        assertMoneyEquals(
            "100.00",
            onDate.getTotalDebits()
        );

        assertMoneyEquals(
            "100.00",
            onDate.getTotalCredits()
        );

        // ====================================================
        // REPORT AFTER SECOND TRANSACTION
        // ====================================================

        TrialBalanceReport after =
            trialBalanceService.generateTrialBalance(
                testBusinessId,
                REPORT_DATE.plusDays(1)
            );

        assertMoneyEquals(
            "1000.00",
            after.getTotalDebits()
        );

        assertMoneyEquals(
            "1000.00",
            after.getTotalCredits()
        );

        assertTrue(before.isBalanced());
        assertTrue(onDate.isBalanced());
        assertTrue(after.isBalanced());
    }

    // ========================================================
    // TEST 4 — INACTIVE AND ZERO-BALANCE ACCOUNTS
    // ========================================================

    /**
     * An account without posted activity should
     * appear with zero debit and credit balances.
     *
     * An inactive account should also be included
     * because accounting history must be preserved.
     */
    @Test
    void includesInactiveAndZeroBalanceAccounts() {

        TrialBalanceReport report =
            trialBalanceService.generateTrialBalance(
                testBusinessId,
                REPORT_DATE
            );

        assertEquals(
            4,
            report.getRows().size()
        );

        TrialBalanceRow inactive =
            findRow(report, inactiveAccountId);

        assertFalse(inactive.isActive());

        assertEquals(
            "1090",
            inactive.getCode()
        );

        assertMoneyEquals(
            "0.00",
            inactive.getDebitBalance()
        );

        assertMoneyEquals(
            "0.00",
            inactive.getCreditBalance()
        );

        // An active account without activity
        // must also appear in the report.
        TrialBalanceRow revenue =
            findRow(report, revenueAccountId);

        assertTrue(revenue.isActive());

        assertMoneyEquals(
            "0.00",
            revenue.getCreditBalance()
        );
    }

    // ========================================================
    // TEST 5 — CREDIT-NORMAL ACCOUNT
    // ========================================================

    /**
     * Verify that credit balances appear in the
     * Credit column regardless of account type.
     *
     * Example:
     *
     * Debit Checking: 200.00
     * Credit Revenue: 200.00
     *
     * Expected:
     *
     * Checking Debit Balance = 200.00
     * Revenue Credit Balance = 200.00
     */
    @Test
    void placesRevenueOnCreditSide() {

        createJournalEntry(
            testBusinessId,
            checkingAccountId,
            revenueAccountId,
            "200.00",
            REPORT_DATE,
            true
        );

        TrialBalanceReport report =
            trialBalanceService.generateTrialBalance(
                testBusinessId,
                REPORT_DATE
            );

        TrialBalanceRow checking =
            findRow(report, checkingAccountId);

        TrialBalanceRow revenue =
            findRow(report, revenueAccountId);

        assertMoneyEquals(
            "200.00",
            checking.getDebitBalance()
        );

        assertMoneyEquals(
            "0.00",
            checking.getCreditBalance()
        );

        assertMoneyEquals(
            "0.00",
            revenue.getDebitBalance()
        );

        assertMoneyEquals(
            "200.00",
            revenue.getCreditBalance()
        );

        assertTrue(report.isBalanced());
    }

    // ========================================================
    // TEST 6 — BUSINESS DATA ISOLATION
    // ========================================================

    /**
     * Both businesses have account codes
     * 1010 and 1020.
     *
     * Activity in Business B must never affect
     * Business A's Trial Balance.
     *
     * This verifies that our SQL filters
     * account and transaction data by business.
     *
     * NOTE:
     *
     * Business-level query isolation is not
     * a replacement for user authentication.
     */
    @Test
    void isolatesTrialBalanceByBusiness() {

        // Create activity only in Business B.
        createJournalEntry(
            otherBusinessId,
            otherCheckingAccountId,
            otherSavingsAccountId,
            "125.00",
            REPORT_DATE,
            true
        );

        // ====================================================
        // FIRST BUSINESS REPORT
        // ====================================================

        TrialBalanceReport firstReport =
            trialBalanceService.generateTrialBalance(
                testBusinessId,
                REPORT_DATE
            );

        assertEquals(
            4,
            firstReport.getRows().size()
        );

        assertMoneyEquals(
            "0.00",
            firstReport.getTotalDebits()
        );

        assertMoneyEquals(
            "0.00",
            firstReport.getTotalCredits()
        );

        // The other business's accounts must be absent.
        assertTrue(
            firstReport.getRows().stream()
                .noneMatch(
                    row -> row.getAccountId()
                        .equals(otherCheckingAccountId)
                )
        );

        // ====================================================
        // SECOND BUSINESS REPORT
        // ====================================================

        TrialBalanceReport secondReport =
            trialBalanceService.generateTrialBalance(
                otherBusinessId,
                REPORT_DATE
            );

        assertEquals(
            2,
            secondReport.getRows().size()
        );

        assertMoneyEquals(
            "125.00",
            secondReport.getTotalDebits()
        );

        assertMoneyEquals(
            "125.00",
            secondReport.getTotalCredits()
        );

        assertTrue(secondReport.isBalanced());

        // The first business's accounts must be absent.
        assertTrue(
            secondReport.getRows().stream()
                .noneMatch(
                    row -> row.getAccountId()
                        .equals(checkingAccountId)
                )
        );
    }

    // ========================================================
    // TEST 7 — INVALID REPORT REQUESTS
    // ========================================================

    /**
     * Reject nonexistent businesses and
     * missing reporting dates.
     *
     * The service should not fabricate
     * an empty report for invalid requests.
     */
    @Test
    void rejectsInvalidBusinessAndDate() {

        assertThrows(
            BusinessNotFoundException.class,
            () -> trialBalanceService.generateTrialBalance(
                0L,
                REPORT_DATE
            )
        );

        assertThrows(
            BusinessNotFoundException.class,
            () -> trialBalanceService.generateTrialBalance(
                null,
                REPORT_DATE
            )
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> trialBalanceService.generateTrialBalance(
                testBusinessId,
                null
            )
        );
    }

    // ========================================================
    // TEST 8 — EXACT DECIMAL PRECISION
    // ========================================================

    /**
     * Verify exact cent arithmetic.
     *
     * Two posted transfers:
     *
     * 0.10
     * 0.20
     *
     * Expected report total:
     *
     * 0.30
     *
     * BigDecimal prevents floating-point
     * rounding problems.
     */
    @Test
    void preservesExactCentPrecision() {

        createJournalEntry(
            testBusinessId,
            savingsAccountId,
            checkingAccountId,
            "0.10",
            REPORT_DATE,
            true
        );

        createJournalEntry(
            testBusinessId,
            savingsAccountId,
            checkingAccountId,
            "0.20",
            REPORT_DATE,
            true
        );

        TrialBalanceReport report =
            trialBalanceService.generateTrialBalance(
                testBusinessId,
                REPORT_DATE
            );

        assertMoneyEquals(
            "0.30",
            report.getTotalDebits()
        );

        assertMoneyEquals(
            "0.30",
            report.getTotalCredits()
        );

        assertMoneyEquals(
            "0.00",
            report.getDifference()
        );

        assertTrue(report.isBalanced());
    }

    // ========================================================
    // HELPER — CREATE BUSINESS
    // ========================================================

    /**
     * Create a business owned by the test user.
     *
     * The fixture is automatically rolled back
     * after its test method finishes.
     */
    private Long createBusiness(String name) {

        return jdbcTemplate.queryForObject(
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
            name,
            "USD"
        );
    }

    // ========================================================
    // HELPER — CREATE ACCOUNT
    // ========================================================

    /**
     * Create a real PostgreSQL account.
     *
     * The active parameter allows tests to
     * exercise active and inactive account cases.
     */
    private Long createAccount(
            Long businessId,
            String code,
            String name,
            String accountType,
            boolean active) {

        return jdbcTemplate.queryForObject(
            """
            INSERT INTO accounts (
                business_id,
                code,
                name,
                account_type,
                is_active
            )
            VALUES (?, ?, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            businessId,
            code,
            name,
            accountType,
            active
        );
    }

    // ========================================================
    // HELPER — CREATE JOURNAL TRANSACTION
    // ========================================================

    /**
     * Create a two-line balanced journal transaction.
     *
     * debitAccountId:
     *     Account receiving the debit.
     *
     * creditAccountId:
     *     Account receiving the credit.
     *
     * amount:
     *     Exact amount represented as a string.
     *
     * transactionDate:
     *     Accounting date assigned to the transaction.
     *
     * posted:
     *     true  -> transition to POSTED
     *     false -> leave as DRAFT
     *
     * We first create a DRAFT header, insert
     * both ledger entries, then optionally post.
     *
     * This follows our database's existing
     * double-entry and posting constraints.
     */
    private void createJournalEntry(
            Long businessId,
            Long debitAccountId,
            Long creditAccountId,
            String amount,
            LocalDate transactionDate,
            boolean posted) {

        // ====================================================
        // CREATE TRANSACTION HEADER
        // ====================================================

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
            testUserId,
            "Trial Balance integration test",
            Date.valueOf(transactionDate)
        );

        BigDecimal monetaryAmount =
            new BigDecimal(amount);

        // ====================================================
        // INSERT DEBIT LEDGER ENTRY
        // ====================================================

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
            debitAccountId,
            monetaryAmount,
            BigDecimal.ZERO
        );

        // ====================================================
        // INSERT CREDIT LEDGER ENTRY
        // ====================================================

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
            creditAccountId,
            BigDecimal.ZERO,
            monetaryAmount
        );

        // ====================================================
        // OPTIONALLY POST THE TRANSACTION
        // ====================================================

        /**
         * Only POSTED transactions should contribute
         * to the Trial Balance report.
         *
         * Because the transaction and both entries
         * were created in the same test transaction,
         * they will all roll back after the test.
         */
        if (posted) {

            int updated = jdbcTemplate.update(
                """
                UPDATE transactions
                SET
                    status = 'POSTED',
                    posted_at = NOW()
                WHERE business_id = ?
                  AND id = ?
                  AND status = 'DRAFT'
                """,
                businessId,
                transactionId
            );

            assertEquals(
                1,
                updated,
                "Expected test transaction to be posted"
            );
        }
    }

    // ========================================================
    // HELPER — FIND REPORT ROW BY ACCOUNT ID
    // ========================================================

    /**
     * Retrieve an account from a Trial Balance report.
     *
     * Use account IDs instead of account codes
     * because codes can repeat across businesses.
     */
    private TrialBalanceRow findRow(
            TrialBalanceReport report,
            Long accountId) {

        return report.getRows().stream()
            .filter(
                row -> accountId.equals(row.getAccountId())
            )
            .findFirst()
            .orElseThrow(
                () -> new AssertionError(
                    "Missing Trial Balance row for account "
                        + accountId
                )
            );
    }

    // ========================================================
    // HELPER — COMPARE MONETARY VALUES
    // ========================================================

    /**
     * Compare BigDecimal values numerically.
     *
     * BigDecimal.equals() considers decimal scale.
     *
     * For example:
     *
     * 0
     * 0.0
     * 0.00
     *
     * These values are numerically equal, so
     * compareTo() is appropriate for these tests.
     */
    private void assertMoneyEquals(
            String expected,
            BigDecimal actual) {

        assertNotNull(
            actual,
            "Monetary value cannot be null"
        );

        BigDecimal expectedAmount =
            new BigDecimal(expected);

        assertEquals(
            0,
            expectedAmount.compareTo(actual),
            "Expected " + expected
                + " but received " + actual
        );
    }
}
