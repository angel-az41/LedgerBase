package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.mapper.BalanceSheetMapper;
import com.ledgerbase.backend.model.BalanceSheetRow;
import com.ledgerbase.backend.service.FiscalYearService;

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
// LEDGERBASE — BALANCE SHEET MAPPER INTEGRATION TESTS
// ============================================================

/**
 * Verify BalanceSheetMapper using real PostgreSQL data.
 *
 * Test flow:
 *
 * BalanceSheetMapper
 *       |
 *       v
 * BalanceSheetMapper.xml
 *       |
 *       v
 * PostgreSQL
 *
 * TEST COVERAGE:
 *
 * 1. Correct normal-balance calculations.
 * 2. Exact decimal precision.
 * 3. Exclusion of DRAFT transactions.
 * 4. Inclusive reporting cutoff dates.
 * 5. Preservation of negative balances.
 * 6. Inactive and unclassified account handling.
 * 7. Exclusion of non-postable grouping accounts.
 * 8. Business-level data isolation.
 *
 * IMPORTANT:
 *
 * This class verifies account-level SQL results.
 *
 * It does not calculate Balance Sheet subtotals
 * or the final accounting equation.
 *
 * Those calculations belong to BalanceSheetService
 * and will receive separate integration tests.
 *
 * @Transactional ensures all fixture records
 * are rolled back after each test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BalanceSheetMapperIntegrationTest {

    // ========================================================
    // TEST DEPENDENCIES
    // ========================================================

    @Autowired
    private BalanceSheetMapper balanceSheetMapper;

    @Autowired
    private FiscalYearService fiscalYearService;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    // ========================================================
    // FIXED REPORTING DATES
    // ========================================================

    /**
     * Use historical dates to keep tests repeatable.
     *
     * These dates belong to FY2026 for businesses
     * using the default January fiscal-year start.
     */
    private static final LocalDate REPORT_DATE =
        LocalDate.of(2026, 10, 13);


    // ========================================================
    // TEST FIXTURE IDENTIFIERS
    // ========================================================

    private Long testUserId;

    private Long businessAId;
    private Long businessBId;

    private Long cashAccountId;
    private Long equipmentAccountId;
    private Long liabilityAccountId;
    private Long equityAccountId;
    private Long revenueAccountId;
    private Long expenseAccountId;

    private Long otherCashAccountId;
    private Long otherRevenueAccountId;


    // ========================================================
    // CREATE TEST FIXTURES
    // ========================================================

    /**
     * Create two separate businesses.
     *
     * Business A contains all five fundamental
     * account types.
     *
     * Business B contains independent Cash and
     * Revenue accounts.
     *
     * We create FY2026 for both businesses so
     * the V6 database posting trigger can verify
     * that October 2026 is an OPEN period.
     */
    @BeforeEach
    void setUp() {

        // ====================================================
        // CREATE TEST USER
        // ====================================================

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
            "balance-sheet-"
                + UUID.randomUUID()
                + "@ledgerbase.local",
            "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
            "Balance Sheet Test User"
        );


        // ====================================================
        // CREATE TWO INDEPENDENT BUSINESSES
        // ====================================================

        businessAId = createBusiness(
            "Balance Sheet Business A"
        );

        businessBId = createBusiness(
            "Balance Sheet Business B"
        );


        // ====================================================
        // BUSINESS A — CURRENT ASSET
        // ====================================================

        cashAccountId = createAccount(
            businessAId,
            "1010",
            "Operating Cash",
            "ASSET",
            "CURRENT_ASSET",
            true
        );


        // ====================================================
        // BUSINESS A — NON-CURRENT ASSET
        // ====================================================

        equipmentAccountId = createAccount(
            businessAId,
            "1500",
            "Equipment",
            "ASSET",
            "NON_CURRENT_ASSET",
            true
        );


        // ====================================================
        // BUSINESS A — LIABILITY
        // ====================================================

        liabilityAccountId = createAccount(
            businessAId,
            "2000",
            "Accounts Payable",
            "LIABILITY",
            "CURRENT_LIABILITY",
            true
        );


        // ====================================================
        // BUSINESS A — EQUITY
        // ====================================================

        equityAccountId = createAccount(
            businessAId,
            "3000",
            "Contributed Capital",
            "EQUITY",
            "CONTRIBUTED_CAPITAL",
            true
        );


        // ====================================================
        // BUSINESS A — REVENUE
        // ====================================================

        revenueAccountId = createAccount(
            businessAId,
            "4000",
            "Sales Revenue",
            "REVENUE",
            "OPERATING_REVENUE",
            true
        );


        // ====================================================
        // BUSINESS A — EXPENSE
        // ====================================================

        expenseAccountId = createAccount(
            businessAId,
            "5000",
            "Operating Expense",
            "EXPENSE",
            "OPERATING_EXPENSE",
            true
        );


        // ====================================================
        // BUSINESS B — INDEPENDENT ASSET AND REVENUE
        // ====================================================

        otherCashAccountId = createAccount(
            businessBId,
            "1010",
            "Business B Cash",
            "ASSET",
            "CURRENT_ASSET",
            true
        );

        otherRevenueAccountId = createAccount(
            businessBId,
            "4000",
            "Business B Revenue",
            "REVENUE",
            "OPERATING_REVENUE",
            true
        );


        // ====================================================
        // INITIALIZE ACCOUNTING CALENDARS
        // ====================================================

        /**
         * Match our existing Trial Balance fixture.
         *
         * FiscalYearService creates 12 OPEN periods.
         *
         * These are required before journal entries
         * can transition to POSTED.
         */
        fiscalYearService.createFiscalYear(
            businessAId,
            2026
        );

        fiscalYearService.createFiscalYear(
            businessBId,
            2026
        );
    }


    // ========================================================
    // TEST 1 — NORMAL BALANCES AND DECIMAL PRECISION
    // ========================================================

    @Test
    void calculatesNormalBalancesWithExactPrecision() {

        // ====================================================
        // OWNER CONTRIBUTES CAPITAL
        // ====================================================

        /**
         * Debit Cash:          1000.10
         * Credit Capital:       1000.10
         */
        createJournalEntry(
            businessAId,
            cashAccountId,
            equityAccountId,
            "1000.10",
            REPORT_DATE,
            true
        );


        // ====================================================
        // BUSINESS EARNS REVENUE
        // ====================================================

        /**
         * Debit Cash:           300.03
         * Credit Revenue:       300.03
         */
        createJournalEntry(
            businessAId,
            cashAccountId,
            revenueAccountId,
            "300.03",
            REPORT_DATE,
            true
        );


        // ====================================================
        // BUSINESS PAYS AN EXPENSE
        // ====================================================

        /**
         * Debit Expense:         60.01
         * Credit Cash:           60.01
         */
        createJournalEntry(
            businessAId,
            expenseAccountId,
            cashAccountId,
            "60.01",
            REPORT_DATE,
            true
        );


        // ====================================================
        // BUSINESS BORROWS MONEY
        // ====================================================

        /**
         * Debit Cash:           200.02
         * Credit Liability:     200.02
         */
        createJournalEntry(
            businessAId,
            cashAccountId,
            liabilityAccountId,
            "200.02",
            REPORT_DATE,
            true
        );


        // ====================================================
        // BUSINESS PURCHASES EQUIPMENT
        // ====================================================

        /**
         * Debit Equipment:      125.05
         * Credit Cash:          125.05
         */
        createJournalEntry(
            businessAId,
            equipmentAccountId,
            cashAccountId,
            "125.05",
            REPORT_DATE,
            true
        );


        // ====================================================
        // REQUEST BALANCE SHEET ACCOUNT ROWS
        // ====================================================

        List<BalanceSheetRow> rows =
            findRows(businessAId, REPORT_DATE);


        // ====================================================
        // VERIFY ALL SIX POSTING ACCOUNTS ARE RETURNED
        // ====================================================

        assertEquals(
            6,
            rows.size()
        );


        // ====================================================
        // VERIFY CURRENT ASSET
        // ====================================================

        /**
         * Cash:
         *
         * 1000.10
         * +300.03
         * - 60.01
         * +200.02
         * -125.05
         *
         * = 1315.09
         */
        assertMoneyEquals(
            "1315.09",
            findRow(rows, cashAccountId).getBalance()
        );


        // ====================================================
        // VERIFY NON-CURRENT ASSET
        // ====================================================

        assertMoneyEquals(
            "125.05",
            findRow(rows, equipmentAccountId).getBalance()
        );


        // ====================================================
        // VERIFY LIABILITY — CREDIT NORMAL
        // ====================================================

        assertMoneyEquals(
            "200.02",
            findRow(rows, liabilityAccountId).getBalance()
        );


        // ====================================================
        // VERIFY EQUITY — CREDIT NORMAL
        // ====================================================

        assertMoneyEquals(
            "1000.10",
            findRow(rows, equityAccountId).getBalance()
        );


        // ====================================================
        // VERIFY REVENUE — CREDIT NORMAL
        // ====================================================

        assertMoneyEquals(
            "300.03",
            findRow(rows, revenueAccountId).getBalance()
        );


        // ====================================================
        // VERIFY EXPENSE — DEBIT NORMAL
        // ====================================================

        assertMoneyEquals(
            "60.01",
            findRow(rows, expenseAccountId).getBalance()
        );


        // ====================================================
        // VERIFY POSTED ENTRY COUNTS
        // ====================================================

        /**
         * Cash participates in all five transactions.
         *
         * Each other account participates once.
         */
        assertEquals(
            Long.valueOf(5),
            findRow(rows, cashAccountId)
                .getPostedEntryCount()
        );

        assertEquals(
            Long.valueOf(1),
            findRow(rows, revenueAccountId)
                .getPostedEntryCount()
        );


        // ====================================================
        // VERIFY ACCOUNT CLASSIFICATION
        // ====================================================

        assertEquals(
            "NON_CURRENT_ASSET",
            findRow(rows, equipmentAccountId)
                .getReportingGroup()
        );

        assertEquals(
            "CONTRIBUTED_CAPITAL",
            findRow(rows, equityAccountId)
                .getReportingGroup()
        );
    }


    // ========================================================
    // TEST 2 — EXCLUDE DRAFT TRANSACTIONS
    // ========================================================

    @Test
    void excludesDraftTransactions() {

        // ====================================================
        // CREATE A DRAFT REVENUE TRANSACTION
        // ====================================================

        createJournalEntry(
            businessAId,
            cashAccountId,
            revenueAccountId,
            "900.00",
            REPORT_DATE,
            false
        );


        // ====================================================
        // QUERY AS-OF BALANCES
        // ====================================================

        List<BalanceSheetRow> rows =
            findRows(businessAId, REPORT_DATE);


        // ====================================================
        // VERIFY DRAFT DOES NOT AFFECT BALANCES
        // ====================================================

        for (BalanceSheetRow row : rows) {

            assertMoneyEquals(
                "0.00",
                row.getBalance()
            );

            assertEquals(
                Long.valueOf(0),
                row.getPostedEntryCount()
            );
        }
    }


    // ========================================================
    // TEST 3 — INCLUSIVE AS-OF DATE
    // ========================================================

    @Test
    void respectsInclusiveReportingDate() {

        // ====================================================
        // TRANSACTION BEFORE REPORT DATE
        // ====================================================

        createJournalEntry(
            businessAId,
            cashAccountId,
            revenueAccountId,
            "20.00",
            REPORT_DATE.minusDays(1),
            true
        );


        // ====================================================
        // TRANSACTION ON REPORT DATE
        // ====================================================

        createJournalEntry(
            businessAId,
            cashAccountId,
            revenueAccountId,
            "30.00",
            REPORT_DATE,
            true
        );


        // ====================================================
        // TRANSACTION AFTER REPORT DATE
        // ====================================================

        createJournalEntry(
            businessAId,
            cashAccountId,
            revenueAccountId,
            "40.00",
            REPORT_DATE.plusDays(1),
            true
        );


        // ====================================================
        // BEFORE REPORT DATE
        // ====================================================

        List<BalanceSheetRow> before =
            findRows(
                businessAId,
                REPORT_DATE.minusDays(1)
            );

        assertMoneyEquals(
            "20.00",
            findRow(before, revenueAccountId).getBalance()
        );


        // ====================================================
        // ON REPORT DATE — INCLUDE BOTH FIRST TWO ENTRIES
        // ====================================================

        List<BalanceSheetRow> onDate =
            findRows(
                businessAId,
                REPORT_DATE
            );

        assertMoneyEquals(
            "50.00",
            findRow(onDate, revenueAccountId).getBalance()
        );

        assertEquals(
            Long.valueOf(2),
            findRow(onDate, revenueAccountId)
                .getPostedEntryCount()
        );


        // ====================================================
        // AFTER REPORT DATE — INCLUDE ALL THREE ENTRIES
        // ====================================================

        List<BalanceSheetRow> after =
            findRows(
                businessAId,
                REPORT_DATE.plusDays(1)
            );

        assertMoneyEquals(
            "90.00",
            findRow(after, revenueAccountId).getBalance()
        );

        assertEquals(
            Long.valueOf(3),
            findRow(after, revenueAccountId)
                .getPostedEntryCount()
        );
    }


    // ========================================================
    // TEST 4 — PRESERVE NEGATIVE BALANCES
    // ========================================================

    @Test
    void preservesNegativeAccountBalances() {

        // ====================================================
        // NEGATIVE REVENUE
        // ====================================================

        /**
         * Debit Revenue: 40.00
         * Credit Cash:   40.00
         *
         * Revenue = Credits - Debits = -40.00
         */
        createJournalEntry(
            businessAId,
            revenueAccountId,
            cashAccountId,
            "40.00",
            REPORT_DATE,
            true
        );


        // ====================================================
        // NEGATIVE EXPENSE
        // ====================================================

        /**
         * Debit Cash:    10.00
         * Credit Expense: 10.00
         *
         * Expense = Debits - Credits = -10.00
         */
        createJournalEntry(
            businessAId,
            cashAccountId,
            expenseAccountId,
            "10.00",
            REPORT_DATE,
            true
        );


        // ====================================================
        // QUERY BALANCES
        // ====================================================

        List<BalanceSheetRow> rows =
            findRows(businessAId, REPORT_DATE);


        // ====================================================
        // VERIFY SIGN PRESERVATION
        // ====================================================

        assertMoneyEquals(
            "-40.00",
            findRow(rows, revenueAccountId).getBalance()
        );

        assertMoneyEquals(
            "-10.00",
            findRow(rows, expenseAccountId).getBalance()
        );

        assertMoneyEquals(
            "-30.00",
            findRow(rows, cashAccountId).getBalance()
        );
    }


    // ========================================================
    // TEST 5 — INACTIVE AND UNCLASSIFIED ACCOUNT HANDLING
    // ========================================================

    @Test
    void includesInactiveAndUnclassifiedAccounts() {

        // ====================================================
        // CREATE AN UNCLASSIFIED ASSET
        // ====================================================

        Long unclassifiedAssetId = createAccount(
            businessAId,
            "1090",
            "Unclassified Asset",
            "ASSET",
            null,
            true
        );


        // ====================================================
        // CREATE OFFSETTING POSTED ACTIVITY
        // ====================================================

        /**
         * First:
         *
         * Debit Unclassified Asset: 25.00
         * Credit Sales Revenue:     25.00
         *
         * Second:
         *
         * Debit Sales Revenue:      25.00
         * Credit Unclassified Asset:25.00
         *
         * The asset has a zero balance but two
         * posted ledger entries.
         */
        createJournalEntry(
            businessAId,
            unclassifiedAssetId,
            revenueAccountId,
            "25.00",
            REPORT_DATE,
            true
        );

        createJournalEntry(
            businessAId,
            revenueAccountId,
            unclassifiedAssetId,
            "25.00",
            REPORT_DATE,
            true
        );


        // ====================================================
        // DEACTIVATE THE ASSET AFTER POSTING
        // ====================================================

        int updated = jdbcTemplate.update(
            """
            UPDATE accounts
            SET is_active = FALSE
            WHERE business_id = ?
              AND id = ?
            """,
            businessAId,
            unclassifiedAssetId
        );

        assertEquals(1, updated);


        // ====================================================
        // QUERY REPORT ROWS
        // ====================================================

        List<BalanceSheetRow> rows =
            findRows(businessAId, REPORT_DATE);


        // ====================================================
        // VERIFY THE ACCOUNT REMAINS INCLUDED
        // ====================================================

        BalanceSheetRow asset =
            findRow(rows, unclassifiedAssetId);

        assertEquals(
            Boolean.FALSE,
            asset.getActive()
        );

        assertNull(
            asset.getReportingGroup()
        );

        assertMoneyEquals(
            "0.00",
            asset.getBalance()
        );

        assertEquals(
            Long.valueOf(2),
            asset.getPostedEntryCount()
        );


        // ====================================================
        // VERIFY UNUSED ACCOUNTS ARE ALSO INCLUDED
        // ====================================================

        BalanceSheetRow unusedExpense =
            findRow(rows, expenseAccountId);

        assertMoneyEquals(
            "0.00",
            unusedExpense.getBalance()
        );

        assertEquals(
            Long.valueOf(0),
            unusedExpense.getPostedEntryCount()
        );
    }


    // ========================================================
    // TEST 6 — EXCLUDE NON-POSTABLE GROUPING ACCOUNTS
    // ========================================================

    @Test
    void excludesNonPostableGroupingAccounts() {

        // ====================================================
        // CREATE A GROUPING ACCOUNT
        // ====================================================

        Long groupingAccountId = createAccount(
            businessAId,
            "1000",
            "Current Assets Group",
            "ASSET",
            "CURRENT_ASSET",
            false
        );


        // ====================================================
        // QUERY ALL POSTING ACCOUNT BALANCES
        // ====================================================

        List<BalanceSheetRow> rows =
            findRows(businessAId, REPORT_DATE);


        // ====================================================
        // VERIFY GROUPING ACCOUNT IS NOT RETURNED
        // ====================================================

        assertTrue(
            rows.stream()
                .noneMatch(row ->
                    groupingAccountId.equals(
                        row.getAccountId()
                    )
                )
        );


        // ====================================================
        // VERIFY ORDINARY POSTING ACCOUNTS REMAIN
        // ====================================================

        assertNotNull(
            findRow(rows, cashAccountId)
        );
    }


    // ========================================================
    // TEST 7 — BUSINESS DATA ISOLATION
    // ========================================================

    @Test
    void isolatesBalancesByBusiness() {

        // ====================================================
        // POST REVENUE IN BUSINESS B ONLY
        // ====================================================

        createJournalEntry(
            businessBId,
            otherCashAccountId,
            otherRevenueAccountId,
            "75.00",
            REPORT_DATE,
            true
        );


        // ====================================================
        // REQUEST BUSINESS A BALANCES
        // ====================================================

        List<BalanceSheetRow> businessARows =
            findRows(businessAId, REPORT_DATE);


        // ====================================================
        // BUSINESS A MUST HAVE NO POSTED ACTIVITY
        // ====================================================

        assertMoneyEquals(
            "0.00",
            findRow(
                businessARows,
                revenueAccountId
            ).getBalance()
        );

        assertTrue(
            businessARows.stream()
                .noneMatch(row ->
                    otherRevenueAccountId.equals(
                        row.getAccountId()
                    )
                )
        );


        // ====================================================
        // REQUEST BUSINESS B BALANCES
        // ====================================================

        List<BalanceSheetRow> businessBRows =
            findRows(businessBId, REPORT_DATE);


        // ====================================================
        // VERIFY BUSINESS B ACTIVITY IS CORRECT
        // ====================================================

        assertEquals(
            2,
            businessBRows.size()
        );

        assertMoneyEquals(
            "75.00",
            findRow(
                businessBRows,
                otherCashAccountId
            ).getBalance()
        );

        assertMoneyEquals(
            "75.00",
            findRow(
                businessBRows,
                otherRevenueAccountId
            ).getBalance()
        );


        // ====================================================
        // VERIFY BUSINESS A ACCOUNTS ARE NOT INCLUDED
        // ====================================================

        assertTrue(
            businessBRows.stream()
                .noneMatch(row ->
                    revenueAccountId.equals(
                        row.getAccountId()
                    )
                )
        );
    }


    // ========================================================
    // HELPER — RETRIEVE BALANCE SHEET ROWS
    // ========================================================

    /**
     * Execute the real MyBatis mapper query.
     */
    private List<BalanceSheetRow> findRows(
        Long businessId,
        LocalDate asOfDate
    ) {

        return balanceSheetMapper.findByBusinessIdAsOfDate(
            businessId,
            asOfDate
        );
    }


    // ========================================================
    // HELPER — CREATE BUSINESS
    // ========================================================

    /**
     * Create a business owned by the fixture user.
     */
    private Long createBusiness(
        String businessName
    ) {

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
            businessName,
            "USD"
        );
    }


    // ========================================================
    // HELPER — CREATE GENERAL LEDGER ACCOUNT
    // ========================================================

    /**
     * Create an account using the V7 schema.
     *
     * reportingGroup may be null.
     *
     * postable controls whether the account is
     * a journal posting account or grouping account.
     */
    private Long createAccount(
        Long businessId,
        String code,
        String name,
        String accountType,
        String reportingGroup,
        boolean postable
    ) {

        return jdbcTemplate.queryForObject(
            """
            INSERT INTO accounts (
                business_id,
                code,
                name,
                account_type,
                reporting_group,
                is_postable,
                is_active
            )
            VALUES (?, ?, ?, ?, ?, ?, TRUE)
            RETURNING id
            """,
            Long.class,
            businessId,
            code,
            name,
            accountType,
            reportingGroup,
            postable
        );
    }


    // ========================================================
    // HELPER — CREATE BALANCED JOURNAL TRANSACTION
    // ========================================================

    /**
     * Create a legitimate two-line journal transaction.
     *
     * Steps:
     *
     * 1. Insert a DRAFT transaction header.
     * 2. Insert the debit ledger entry.
     * 3. Insert the credit ledger entry.
     * 4. Optionally transition the transaction to POSTED.
     *
     * The posting transition invokes LedgerBase's
     * existing PostgreSQL accounting-period safeguards.
     *
     * All fixture records are automatically rolled
     * back by the test transaction.
     */
    private void createJournalEntry(
        Long businessId,
        Long debitAccountId,
        Long creditAccountId,
        String amount,
        LocalDate transactionDate,
        boolean posted
    ) {

        // ====================================================
        // CREATE DRAFT TRANSACTION
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
            "Balance Sheet integration test",
            Date.valueOf(transactionDate)
        );


        // ====================================================
        // PREPARE EXACT DECIMAL AMOUNT
        // ====================================================

        BigDecimal monetaryAmount =
            new BigDecimal(amount);


        // ====================================================
        // INSERT DEBIT ENTRY
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
        // INSERT CREDIT ENTRY
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

        if (posted) {

            /**
             * Existing database triggers validate:
             *
             * - Transaction posting eligibility
             * - OPEN accounting period
             * - Valid account references
             */
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
                "Expected journal transaction to be posted"
            );
        }
    }


    // ========================================================
    // HELPER — FIND ACCOUNT ROW
    // ========================================================

    /**
     * Locate an account using its ID.
     *
     * Account codes may repeat across businesses,
     * so IDs are safer for assertions.
     */
    private BalanceSheetRow findRow(
        List<BalanceSheetRow> rows,
        Long accountId
    ) {

        return rows.stream()
            .filter(row ->
                accountId.equals(row.getAccountId())
            )
            .findFirst()
            .orElseThrow(() ->
                new AssertionError(
                    "Missing Balance Sheet row for account "
                    + accountId
                )
            );
    }


    // ========================================================
    // HELPER — EXACT MONETARY ASSERTION
    // ========================================================

    /**
     * Compare BigDecimal values numerically.
     *
     * BigDecimal.equals() considers scale.
     *
     * compareTo() considers numeric value.
     *
     * For example:
     *
     * 0
     * 0.0
     * 0.00
     *
     * are equivalent accounting amounts.
     */
    private void assertMoneyEquals(
        String expected,
        BigDecimal actual
    ) {

        assertNotNull(
            actual,
            "Monetary balance must not be null"
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
