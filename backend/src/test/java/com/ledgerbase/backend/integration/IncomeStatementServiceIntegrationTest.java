package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.dto.IncomeStatementResponse;
import com.ledgerbase.backend.service.FiscalYearService;
import com.ledgerbase.backend.service.IncomeStatementService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — INCOME STATEMENT SERVICE INTEGRATION TESTS
// ============================================================

/**
 * Integration tests for Income Statement reporting.
 *
 * These tests exercise the actual application layers:
 *
 * IncomeStatementService
 *       |
 *       v
 * IncomeStatementMapper
 *       |
 *       v
 * PostgreSQL
 *
 * JdbcTemplate is used only to prepare test records.
 *
 * We create balanced journal transactions that comply
 * with the existing V4, V5, V6, and V7 database rules.
 *
 * IMPORTANT:
 *
 * @ActiveProfiles("test") selects ledgerbase_test.
 *
 * @Transactional rolls back test fixtures after
 * each test, keeping the database clean.
 *
 * Monetary assertions compare exact decimal strings
 * produced by the IncomeStatementService.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IncomeStatementServiceIntegrationTest {

    // ========================================================
    // TEST DEPENDENCIES
    // ========================================================

    @Autowired
    private IncomeStatementService incomeStatementService;

    @Autowired
    private FiscalYearService fiscalYearService;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    // ========================================================
    // REPORTING PERIOD
    // ========================================================

    /**
     * Fixed accounting dates ensure repeatable tests.
     *
     * FY2026 in our existing fiscal year implementation
     * includes October 2026.
     */
    private static final LocalDate START_DATE =
        LocalDate.of(2026, 10, 12);

    private static final LocalDate END_DATE =
        LocalDate.of(2026, 10, 14);


    // ========================================================
    // TEST FIXTURE IDENTIFIERS
    // ========================================================

    private Long testUserId;
    private Long testBusinessId;
    private Long otherBusinessId;

    private Long cashAccountId;
    private Long operatingRevenueAccountId;
    private Long otherIncomeAccountId;
    private Long costOfGoodsSoldAccountId;
    private Long operatingExpenseAccountId;
    private Long otherExpenseAccountId;

    private Long unclassifiedRevenueAccountId;
    private Long unclassifiedExpenseAccountId;

    private Long groupingAccountId;

    private Long otherCashAccountId;
    private Long otherRevenueAccountId;


    // ========================================================
    // CREATE ISOLATED TEST FIXTURES
    // ========================================================

    /**
     * Create two businesses and their accounts.
     *
     * Business A:
     *
     * - Cash
     * - Operating Revenue
     * - Other Income
     * - Cost of Goods Sold
     * - Operating Expense
     * - Other Expense
     * - Unclassified Revenue
     * - Unclassified Expense
     * - Non-postable Revenue Group
     *
     * Business B:
     *
     * - Cash
     * - Operating Revenue
     *
     * Both businesses receive FY2026 with OPEN
     * monthly accounting periods.
     *
     * This is required by the V6 posting triggers.
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
            "income-service-"
                + UUID.randomUUID()
                + "@ledgerbase.local",
            "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
            "Income Statement Service Test User"
        );


        // ====================================================
        // CREATE TEST BUSINESSES
        // ====================================================

        testBusinessId = createBusiness(
            "Income Statement Business A"
        );

        otherBusinessId = createBusiness(
            "Income Statement Business B"
        );


        // ====================================================
        // BUSINESS A — BALANCE SHEET ACCOUNT
        // ====================================================

        cashAccountId = createAccount(
            testBusinessId,
            "1010",
            "Cash",
            "ASSET",
            "CURRENT_ASSET",
            true
        );


        // ====================================================
        // BUSINESS A — REVENUE ACCOUNTS
        // ====================================================

        operatingRevenueAccountId = createAccount(
            testBusinessId,
            "4000",
            "Sales Revenue",
            "REVENUE",
            "OPERATING_REVENUE",
            true
        );

        otherIncomeAccountId = createAccount(
            testBusinessId,
            "4100",
            "Interest Income",
            "REVENUE",
            "OTHER_INCOME",
            true
        );


        // ====================================================
        // BUSINESS A — EXPENSE ACCOUNTS
        // ====================================================

        costOfGoodsSoldAccountId = createAccount(
            testBusinessId,
            "5000",
            "Cost of Goods Sold",
            "EXPENSE",
            "COST_OF_GOODS_SOLD",
            true
        );

        operatingExpenseAccountId = createAccount(
            testBusinessId,
            "5100",
            "Rent Expense",
            "EXPENSE",
            "OPERATING_EXPENSE",
            true
        );

        otherExpenseAccountId = createAccount(
            testBusinessId,
            "5200",
            "Other Expense",
            "EXPENSE",
            "OTHER_EXPENSE",
            true
        );


        // ====================================================
        // BUSINESS A — UNCLASSIFIED ACCOUNTS
        // ====================================================

        unclassifiedRevenueAccountId = createAccount(
            testBusinessId,
            "4200",
            "Unclassified Revenue",
            "REVENUE",
            null,
            true
        );

        unclassifiedExpenseAccountId = createAccount(
            testBusinessId,
            "5300",
            "Unclassified Expense",
            "EXPENSE",
            null,
            true
        );


        // ====================================================
        // BUSINESS A — NON-POSTABLE GROUPING ACCOUNT
        // ====================================================

        groupingAccountId = createAccount(
            testBusinessId,
            "3900",
            "Revenue Heading",
            "REVENUE",
            "OPERATING_REVENUE",
            false
        );


        // ====================================================
        // BUSINESS B — INDEPENDENT ACCOUNTS
        // ====================================================

        otherCashAccountId = createAccount(
            otherBusinessId,
            "1010",
            "Other Business Cash",
            "ASSET",
            "CURRENT_ASSET",
            true
        );

        otherRevenueAccountId = createAccount(
            otherBusinessId,
            "4000",
            "Other Business Revenue",
            "REVENUE",
            "OPERATING_REVENUE",
            true
        );


        // ====================================================
        // CREATE OPEN ACCOUNTING PERIODS
        // ====================================================

        /**
         * Match TrialBalanceIntegrationTest.
         *
         * These calls create the accounting calendars
         * required before transactions can be POSTED.
         */
        fiscalYearService.createFiscalYear(
            testBusinessId,
            2026
        );

        fiscalYearService.createFiscalYear(
            otherBusinessId,
            2026
        );
    }


    // ========================================================
    // TEST 1 — CALCULATE COMPLETE INCOME STATEMENT
    // ========================================================

    @Test
    void calculatesRevenueExpensesAndNetIncome() {

        // ====================================================
        // POST OPERATING REVENUE: 1000.10
        // ====================================================

        createJournalEntry(
            testBusinessId,
            cashAccountId,
            operatingRevenueAccountId,
            "1000.10",
            START_DATE,
            true
        );


        // ====================================================
        // POST OTHER INCOME: 20.02
        // ====================================================

        createJournalEntry(
            testBusinessId,
            cashAccountId,
            otherIncomeAccountId,
            "20.02",
            START_DATE,
            true
        );


        // ====================================================
        // POST COST OF GOODS SOLD: 300.03
        // ====================================================

        createJournalEntry(
            testBusinessId,
            costOfGoodsSoldAccountId,
            cashAccountId,
            "300.03",
            START_DATE,
            true
        );


        // ====================================================
        // POST OPERATING EXPENSE: 100.04
        // ====================================================

        createJournalEntry(
            testBusinessId,
            operatingExpenseAccountId,
            cashAccountId,
            "100.04",
            START_DATE,
            true
        );


        // ====================================================
        // POST OTHER EXPENSE: 10.05
        // ====================================================

        createJournalEntry(
            testBusinessId,
            otherExpenseAccountId,
            cashAccountId,
            "10.05",
            START_DATE,
            true
        );


        // ====================================================
        // GENERATE INCOME STATEMENT
        // ====================================================

        IncomeStatementResponse report =
            generateReport(testBusinessId);


        // ====================================================
        // VERIFY REPORT IDENTIFICATION
        // ====================================================

        assertEquals(
            testBusinessId,
            report.businessId()
        );

        assertEquals(
            START_DATE,
            report.startDate()
        );

        assertEquals(
            END_DATE,
            report.endDate()
        );


        // ====================================================
        // VERIFY TOTAL REVENUE
        // ====================================================

        /**
         * Operating Revenue = 1000.10
         * Other Income      =   20.02
         *
         * Total Revenue     = 1020.12
         */
        assertEquals(
            "1020.12",
            report.totalRevenue()
        );


        // ====================================================
        // VERIFY TOTAL EXPENSES
        // ====================================================

        /**
         * Cost of Goods Sold = 300.03
         * Operating Expense  = 100.04
         * Other Expense      =  10.05
         *
         * Total Expenses     = 410.12
         */
        assertEquals(
            "410.12",
            report.totalExpenses()
        );


        // ====================================================
        // VERIFY NET INCOME
        // ====================================================

        /**
         * Net Income = 1020.12 - 410.12
         *
         *            = 610.00
         */
        assertEquals(
            "610.00",
            report.netIncome()
        );


        // ====================================================
        // VERIFY ALL FIVE REPORTING SECTIONS
        // ====================================================

        assertEquals(
            5,
            report.sections().size()
        );

        assertEquals(
            "1000.10",
            findSection(
                report,
                "OPERATING_REVENUE"
            ).subtotal()
        );

        assertEquals(
            "20.02",
            findSection(
                report,
                "OTHER_INCOME"
            ).subtotal()
        );

        assertEquals(
            "300.03",
            findSection(
                report,
                "COST_OF_GOODS_SOLD"
            ).subtotal()
        );

        assertEquals(
            "100.04",
            findSection(
                report,
                "OPERATING_EXPENSE"
            ).subtotal()
        );

        assertEquals(
            "10.05",
            findSection(
                report,
                "OTHER_EXPENSE"
            ).subtotal()
        );


        // ====================================================
        // VERIFY CLASSIFICATION COMPLETENESS
        // ====================================================

        /**
         * Our unclassified accounts have no posted
         * activity in this test.
         *
         * Therefore, they do not block the report.
         */
        assertTrue(report.classificationComplete());

        assertTrue(report.unclassifiedAccounts().isEmpty());


        // ====================================================
        // VERIFY NON-POSTABLE GROUP IS EXCLUDED
        // ====================================================

        /**
         * A grouping account must not appear as
         * a direct financial account line.
         */
        assertTrue(
            report.sections().stream()
                .flatMap(section ->
                    section.accounts().stream()
                )
                .noneMatch(line ->
                    groupingAccountId.equals(
                        line.accountId()
                    )
                )
        );
    }


    // ========================================================
    // TEST 2 — INCLUSIVE DATES AND DRAFT EXCLUSION
    // ========================================================

    @Test
    void respectsDateRangeAndExcludesDraftTransactions() {

        // ====================================================
        // BEFORE REPORTING PERIOD — EXCLUDED
        // ====================================================

        createJournalEntry(
            testBusinessId,
            cashAccountId,
            operatingRevenueAccountId,
            "500.00",
            START_DATE.minusDays(1),
            true
        );


        // ====================================================
        // ON START DATE — INCLUDED
        // ====================================================

        createJournalEntry(
            testBusinessId,
            cashAccountId,
            operatingRevenueAccountId,
            "100.00",
            START_DATE,
            true
        );


        // ====================================================
        // ON END DATE — INCLUDED
        // ====================================================

        createJournalEntry(
            testBusinessId,
            cashAccountId,
            operatingRevenueAccountId,
            "200.00",
            END_DATE,
            true
        );


        // ====================================================
        // AFTER REPORTING PERIOD — EXCLUDED
        // ====================================================

        createJournalEntry(
            testBusinessId,
            cashAccountId,
            operatingRevenueAccountId,
            "800.00",
            END_DATE.plusDays(1),
            true
        );


        // ====================================================
        // DRAFT WITHIN REPORTING PERIOD — EXCLUDED
        // ====================================================

        createJournalEntry(
            testBusinessId,
            cashAccountId,
            operatingRevenueAccountId,
            "900.00",
            START_DATE,
            false
        );


        // ====================================================
        // GENERATE REPORT
        // ====================================================

        IncomeStatementResponse report =
            generateReport(testBusinessId);


        // ====================================================
        // VERIFY ONLY VALID POSTED ACTIVITY IS INCLUDED
        // ====================================================

        /**
         * Included:
         *
         * Start date = 100.00
         * End date   = 200.00
         *
         * Total      = 300.00
         *
         * Posted entries outside the dates and
         * draft entries are ignored.
         */
        assertEquals(
            "300.00",
            report.totalRevenue()
        );

        assertEquals(
            "0.00",
            report.totalExpenses()
        );

        assertEquals(
            "300.00",
            report.netIncome()
        );


        // ====================================================
        // VERIFY POSTED ENTRY COUNT
        // ====================================================

        IncomeStatementResponse.AccountLine revenueLine =
            findSectionLine(
                report,
                "OPERATING_REVENUE",
                operatingRevenueAccountId
            );

        assertEquals(
            2L,
            revenueLine.postedEntryCount()
        );

        assertEquals(
            "300.00",
            revenueLine.amount()
        );
    }


    // ========================================================
    // TEST 3 — PRESERVE NEGATIVE FINANCIAL AMOUNTS
    // ========================================================

    @Test
    void preservesNegativeRevenueAndExpenseAmounts() {

        // ====================================================
        // REVENUE DEBIT — NEGATIVE REVENUE
        // ====================================================

        /**
         * Debit Revenue: 75.00
         * Credit Cash:   75.00
         *
         * Revenue = Credits - Debits
         *
         *         = -75.00
         */
        createJournalEntry(
            testBusinessId,
            operatingRevenueAccountId,
            cashAccountId,
            "75.00",
            START_DATE,
            true
        );


        // ====================================================
        // EXPENSE CREDIT — NEGATIVE EXPENSE
        // ====================================================

        /**
         * Debit Cash:    20.00
         * Credit Expense: 20.00
         *
         * Expenses = Debits - Credits
         *
         *          = -20.00
         */
        createJournalEntry(
            testBusinessId,
            cashAccountId,
            operatingExpenseAccountId,
            "20.00",
            START_DATE,
            true
        );


        // ====================================================
        // GENERATE REPORT
        // ====================================================

        IncomeStatementResponse report =
            generateReport(testBusinessId);


        // ====================================================
        // VERIFY NEGATIVE AMOUNTS
        // ====================================================

        assertEquals(
            "-75.00",
            report.totalRevenue()
        );

        assertEquals(
            "-20.00",
            report.totalExpenses()
        );


        // ====================================================
        // VERIFY NET INCOME SIGN
        // ====================================================

        /**
         * Net Income = -75.00 - (-20.00)
         *
         *            = -55.00
         */
        assertEquals(
            "-55.00",
            report.netIncome()
        );


        // ====================================================
        // VERIFY SECTION SUBTOTALS
        // ====================================================

        assertEquals(
            "-75.00",
            findSection(
                report,
                "OPERATING_REVENUE"
            ).subtotal()
        );

        assertEquals(
            "-20.00",
            findSection(
                report,
                "OPERATING_EXPENSE"
            ).subtotal()
        );
    }


    // ========================================================
    // TEST 4 — DETECT UNCLASSIFIED POSTED ACTIVITY
    // ========================================================

    @Test
    void flagsUnclassifiedAccountsWithPostedActivity() {

        // ====================================================
        // CREATE ZERO-NET REVENUE WITH REAL ACTIVITY
        // ====================================================

        /**
         * First transaction:
         *
         * Debit Cash
         * Credit Unclassified Revenue
         *
         * Second transaction reverses the revenue:
         *
         * Debit Unclassified Revenue
         * Credit Cash
         *
         * Net Revenue = 0.00
         *
         * But two POSTED ledger entries affected
         * the revenue account.
         */
        createJournalEntry(
            testBusinessId,
            cashAccountId,
            unclassifiedRevenueAccountId,
            "120.00",
            START_DATE,
            true
        );

        createJournalEntry(
            testBusinessId,
            unclassifiedRevenueAccountId,
            cashAccountId,
            "120.00",
            START_DATE,
            true
        );


        // ====================================================
        // POST UNCLASSIFIED EXPENSE
        // ====================================================

        createJournalEntry(
            testBusinessId,
            unclassifiedExpenseAccountId,
            cashAccountId,
            "25.00",
            START_DATE,
            true
        );


        // ====================================================
        // POST CLASSIFIED REVENUE
        // ====================================================

        createJournalEntry(
            testBusinessId,
            cashAccountId,
            operatingRevenueAccountId,
            "200.00",
            START_DATE,
            true
        );


        // ====================================================
        // GENERATE REPORT
        // ====================================================

        IncomeStatementResponse report =
            generateReport(testBusinessId);


        // ====================================================
        // VERIFY REPORT IS MARKED INCOMPLETE
        // ====================================================

        assertFalse(
            report.classificationComplete()
        );

        assertEquals(
            2,
            report.unclassifiedAccounts().size()
        );


        // ====================================================
        // VERIFY ZERO-NET UNCLASSIFIED REVENUE IS FLAGGED
        // ====================================================

        IncomeStatementResponse.AccountLine revenueLine =
            findUnclassifiedLine(
                report,
                unclassifiedRevenueAccountId
            );

        assertEquals(
            "0.00",
            revenueLine.amount()
        );

        assertEquals(
            2L,
            revenueLine.postedEntryCount()
        );


        // ====================================================
        // VERIFY UNCLASSIFIED EXPENSE IS FLAGGED
        // ====================================================

        IncomeStatementResponse.AccountLine expenseLine =
            findUnclassifiedLine(
                report,
                unclassifiedExpenseAccountId
            );

        assertEquals(
            "25.00",
            expenseLine.amount()
        );

        assertEquals(
            1L,
            expenseLine.postedEntryCount()
        );


        // ====================================================
        // VERIFY ALL AMOUNTS CONTRIBUTE TO FINANCIAL TOTALS
        // ====================================================

        /**
         * Revenue = 200.00 + 0.00
         * Expenses = 25.00
         *
         * Net Income = 175.00
         *
         * Unclassified amounts cannot silently vanish.
         */
        assertEquals(
            "200.00",
            report.totalRevenue()
        );

        assertEquals(
            "25.00",
            report.totalExpenses()
        );

        assertEquals(
            "175.00",
            report.netIncome()
        );


        // ====================================================
        // VERIFY CLASSIFIED SECTION REMAINS CORRECT
        // ====================================================

        assertEquals(
            "200.00",
            findSection(
                report,
                "OPERATING_REVENUE"
            ).subtotal()
        );
    }


    // ========================================================
    // TEST 5 — INCLUDE INACTIVE HISTORICAL ACCOUNTS
    // ========================================================

    @Test
    void includesInactiveAccountsWithPostedHistory() {

        // ====================================================
        // POST REVENUE WHILE THE ACCOUNT IS ACTIVE
        // ====================================================

        createJournalEntry(
            testBusinessId,
            cashAccountId,
            operatingRevenueAccountId,
            "125.00",
            START_DATE,
            true
        );


        // ====================================================
        // DEACTIVATE THE ACCOUNT AFTER POSTING
        // ====================================================

        /**
         * Historical ledger entries must remain
         * reportable after an account is deactivated.
         */
        int updated = jdbcTemplate.update(
            """
            UPDATE accounts
            SET is_active = FALSE
            WHERE business_id = ?
              AND id = ?
            """,
            testBusinessId,
            operatingRevenueAccountId
        );

        assertEquals(1, updated);


        // ====================================================
        // GENERATE INCOME STATEMENT
        // ====================================================

        IncomeStatementResponse report =
            generateReport(testBusinessId);


        // ====================================================
        // VERIFY HISTORICAL REVENUE IS INCLUDED
        // ====================================================

        assertEquals(
            "125.00",
            report.totalRevenue()
        );

        assertEquals(
            "125.00",
            report.netIncome()
        );


        // ====================================================
        // VERIFY INACTIVE STATUS IS PRESERVED
        // ====================================================

        IncomeStatementResponse.AccountLine revenueLine =
            findSectionLine(
                report,
                "OPERATING_REVENUE",
                operatingRevenueAccountId
            );

        assertFalse(
            revenueLine.active()
        );

        assertEquals(
            "125.00",
            revenueLine.amount()
        );
    }


    // ========================================================
    // TEST 6 — ENFORCE BUSINESS ISOLATION
    // ========================================================

    @Test
    void isolatesIncomeStatementByBusiness() {

        // ====================================================
        // BUSINESS A — POST REVENUE
        // ====================================================

        createJournalEntry(
            testBusinessId,
            cashAccountId,
            operatingRevenueAccountId,
            "10.00",
            START_DATE,
            true
        );


        // ====================================================
        // BUSINESS B — POST DIFFERENT REVENUE
        // ====================================================

        createJournalEntry(
            otherBusinessId,
            otherCashAccountId,
            otherRevenueAccountId,
            "99.00",
            START_DATE,
            true
        );


        // ====================================================
        // GENERATE BOTH REPORTS
        // ====================================================

        IncomeStatementResponse businessAReport =
            generateReport(testBusinessId);

        IncomeStatementResponse businessBReport =
            generateReport(otherBusinessId);


        // ====================================================
        // VERIFY BUSINESS A TOTALS
        // ====================================================

        assertEquals(
            testBusinessId,
            businessAReport.businessId()
        );

        assertEquals(
            "10.00",
            businessAReport.totalRevenue()
        );

        assertEquals(
            "10.00",
            businessAReport.netIncome()
        );


        // ====================================================
        // VERIFY BUSINESS B TOTALS
        // ====================================================

        assertEquals(
            otherBusinessId,
            businessBReport.businessId()
        );

        assertEquals(
            "99.00",
            businessBReport.totalRevenue()
        );

        assertEquals(
            "99.00",
            businessBReport.netIncome()
        );


        // ====================================================
        // VERIFY BUSINESS B ACCOUNT IS ABSENT FROM A
        // ====================================================

        assertTrue(
            businessAReport.sections().stream()
                .flatMap(section ->
                    section.accounts().stream()
                )
                .noneMatch(line ->
                    otherRevenueAccountId.equals(
                        line.accountId()
                    )
                )
        );


        // ====================================================
        // VERIFY BUSINESS A ACCOUNT IS ABSENT FROM B
        // ====================================================

        assertTrue(
            businessBReport.sections().stream()
                .flatMap(section ->
                    section.accounts().stream()
                )
                .noneMatch(line ->
                    operatingRevenueAccountId.equals(
                        line.accountId()
                    )
                )
        );
    }


    // ========================================================
    // TEST 7 — REJECT INVALID REPORT REQUESTS
    // ========================================================

    @Test
    void rejectsInvalidDateRangesAndBusinesses() {

        // ====================================================
        // REJECT REVERSED DATE RANGE
        // ====================================================

        assertThrows(
            IllegalArgumentException.class,
            () -> incomeStatementService.getIncomeStatement(
                testBusinessId,
                END_DATE,
                START_DATE
            )
        );


        // ====================================================
        // REJECT MISSING START DATE
        // ====================================================

        assertThrows(
            IllegalArgumentException.class,
            () -> incomeStatementService.getIncomeStatement(
                testBusinessId,
                null,
                END_DATE
            )
        );


        // ====================================================
        // REJECT MISSING END DATE
        // ====================================================

        assertThrows(
            IllegalArgumentException.class,
            () -> incomeStatementService.getIncomeStatement(
                testBusinessId,
                START_DATE,
                null
            )
        );


        // ====================================================
        // REJECT INVALID BUSINESS ID
        // ====================================================

        assertThrows(
            IllegalArgumentException.class,
            () -> incomeStatementService.getIncomeStatement(
                0L,
                START_DATE,
                END_DATE
            )
        );


        // ====================================================
        // REJECT NONEXISTENT BUSINESS
        // ====================================================

        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> incomeStatementService.getIncomeStatement(
                    Long.MAX_VALUE,
                    START_DATE,
                    END_DATE
                )
            );

        assertEquals(
            HttpStatus.NOT_FOUND,
            exception.getStatusCode()
        );
    }


    // ========================================================
    // HELPER — GENERATE INCOME STATEMENT
    // ========================================================

    /**
     * Generate a report using the same date range
     * throughout the integration tests.
     */
    private IncomeStatementResponse generateReport(
        Long businessId
    ) {

        return incomeStatementService.getIncomeStatement(
            businessId,
            START_DATE,
            END_DATE
        );
    }


    // ========================================================
    // HELPER — CREATE TEST BUSINESS
    // ========================================================

    private Long createBusiness(String businessName) {

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
    // HELPER — CREATE ACCOUNT
    // ========================================================

    /**
     * Create an account using the existing V7 schema.
     *
     * reportingGroup may be null.
     *
     * postable = false creates a grouping account.
     *
     * All accounts start active. Individual tests
     * can deactivate them after posting activity.
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
     * Create a real double-entry journal transaction.
     *
     * The pattern matches TrialBalanceIntegrationTest:
     *
     * 1. Create a DRAFT transaction.
     * 2. Insert one debit ledger entry.
     * 3. Insert one credit ledger entry.
     * 4. Optionally transition the transaction to POSTED.
     *
     * Debit total always equals credit total.
     *
     * The existing PostgreSQL triggers validate
     * that the posting period is OPEN.
     *
     * Because our test uses @Transactional, all
     * records are rolled back afterward.
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
        // CREATE DRAFT TRANSACTION HEADER
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
            "Income Statement integration test",
            Date.valueOf(transactionDate)
        );


        // ====================================================
        // CONVERT AMOUNT TO EXACT BIGDECIMAL
        // ====================================================

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
        // OPTIONALLY POST TRANSACTION
        // ====================================================

        if (posted) {

            /**
             * This transition triggers the existing
             * posting validations in PostgreSQL.
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
                "Expected transaction to be posted"
            );
        }
    }


    // ========================================================
    // HELPER — FIND REPORT SECTION
    // ========================================================

    /**
     * Find a section using its V7 reporting-group code.
     *
     * For example:
     *
     * OPERATING_REVENUE
     * OPERATING_EXPENSE
     */
    private IncomeStatementResponse.Section findSection(
        IncomeStatementResponse report,
        String reportingGroup
    ) {

        return report.sections().stream()
            .filter(section ->
                reportingGroup.equals(
                    section.reportingGroup()
                )
            )
            .findFirst()
            .orElseThrow(() ->
                new AssertionError(
                    "Missing reporting section: "
                    + reportingGroup
                )
            );
    }


    // ========================================================
    // HELPER — FIND ACCOUNT INSIDE A REPORT SECTION
    // ========================================================

    private IncomeStatementResponse.AccountLine findSectionLine(
        IncomeStatementResponse report,
        String reportingGroup,
        Long accountId
    ) {

        return findSection(
            report,
            reportingGroup
        ).accounts().stream()
            .filter(line ->
                accountId.equals(line.accountId())
            )
            .findFirst()
            .orElseThrow(() ->
                new AssertionError(
                    "Missing account " + accountId
                    + " in section " + reportingGroup
                )
            );
    }


    // ========================================================
    // HELPER — FIND UNCLASSIFIED ACCOUNT
    // ========================================================

    private IncomeStatementResponse.AccountLine findUnclassifiedLine(
        IncomeStatementResponse report,
        Long accountId
    ) {

        return report.unclassifiedAccounts().stream()
            .filter(line ->
                accountId.equals(line.accountId())
            )
            .findFirst()
            .orElseThrow(() ->
                new AssertionError(
                    "Missing unclassified account: "
                    + accountId
                )
            );
    }
}
