package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.dto.BalanceSheetResponse;
import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.service.BalanceSheetService;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — BALANCE SHEET SERVICE INTEGRATION TESTS
// ============================================================

/**
 * Verify complete Balance Sheet calculations using
 * real PostgreSQL journal transactions.
 *
 * Architecture under test:
 *
 * BalanceSheetService
 *        |
 *        v
 * BalanceSheetMapper
 *        |
 *        v
 * PostgreSQL
 *
 * TEST COVERAGE:
 *
 * 1. Empty financial statements.
 * 2. Correct account and reporting-group totals.
 * 3. Exact monetary precision.
 * 4. Unclassified accounts with nonzero balances.
 * 5. Unclassified accounts with offsetting activity.
 * 6. Inclusive dates and DRAFT exclusion.
 * 7. Negative account balances.
 * 8. Manual closing entries and double-count prevention.
 * 9. Business isolation.
 * 10. Accumulated earnings across fiscal years.
 * 11. Invalid business and reporting-date requests.
 *
 * IMPORTANT:
 *
 * @Transactional rolls back all fixture data
 * after each test method.
 *
 * These tests do not modify LedgerBase's
 * production or development accounting records.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BalanceSheetServiceIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    @Autowired
    private BalanceSheetService balanceSheetService;

    @Autowired
    private FiscalYearService fiscalYearService;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    // ========================================================
    // REPORTING DATE
    // ========================================================

    private static final LocalDate REPORT_DATE =
        LocalDate.of(2026, 10, 13);


    // ========================================================
    // FIXTURE IDENTIFIERS
    // ========================================================

    private Long testUserId;

    private Long businessAId;
    private Long businessBId;

    private Long cashId;
    private Long equipmentId;
    private Long payableId;
    private Long capitalId;
    private Long revenueId;
    private Long expenseId;

    private Long businessBCashId;
    private Long businessBRevenueId;


    // ========================================================
    // TEST SETUP
    // ========================================================

    /**
     * Create two businesses and realistic
     * financial accounting accounts.
     *
     * FY2026 is created for both businesses
     * because LedgerBase verifies that a POSTED
     * transaction belongs to an OPEN period.
     */
    @BeforeEach
    void setUp() {

        // ====================================================
        // CREATE USER
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
            "balance-sheet-service-"
                + UUID.randomUUID()
                + "@ledgerbase.local",
            "TEST_ONLY_PASSWORD_HASH",
            "Balance Sheet Service Test User"
        );


        // ====================================================
        // CREATE BUSINESSES
        // ====================================================

        businessAId = createBusiness(
            "Balance Sheet Service Business A"
        );

        businessBId = createBusiness(
            "Balance Sheet Service Business B"
        );


        // ====================================================
        // BUSINESS A — ASSETS
        // ====================================================

        cashId = createAccount(
            businessAId,
            "1010",
            "Operating Cash",
            "ASSET",
            "CURRENT_ASSET"
        );

        equipmentId = createAccount(
            businessAId,
            "1500",
            "Equipment",
            "ASSET",
            "NON_CURRENT_ASSET"
        );


        // ====================================================
        // BUSINESS A — LIABILITY
        // ====================================================

        payableId = createAccount(
            businessAId,
            "2000",
            "Accounts Payable",
            "LIABILITY",
            "CURRENT_LIABILITY"
        );


        // ====================================================
        // BUSINESS A — EQUITY
        // ====================================================

        capitalId = createAccount(
            businessAId,
            "3000",
            "Contributed Capital",
            "EQUITY",
            "CONTRIBUTED_CAPITAL"
        );


        // ====================================================
        // BUSINESS A — REVENUE AND EXPENSE
        // ====================================================

        revenueId = createAccount(
            businessAId,
            "4000",
            "Sales Revenue",
            "REVENUE",
            "OPERATING_REVENUE"
        );

        expenseId = createAccount(
            businessAId,
            "5000",
            "Operating Expense",
            "EXPENSE",
            "OPERATING_EXPENSE"
        );


        // ====================================================
        // BUSINESS B — INDEPENDENT ACCOUNTS
        // ====================================================

        businessBCashId = createAccount(
            businessBId,
            "1010",
            "Business B Cash",
            "ASSET",
            "CURRENT_ASSET"
        );

        businessBRevenueId = createAccount(
            businessBId,
            "4000",
            "Business B Revenue",
            "REVENUE",
            "OPERATING_REVENUE"
        );


        // ====================================================
        // CREATE OPEN FISCAL YEARS
        // ====================================================

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
    // TEST 1 — EMPTY BALANCE SHEET
    // ========================================================

    @Test
    void returnsBalancedEmptyFinancialStatement() {

        BalanceSheetResponse report = getReport(
            businessAId,
            REPORT_DATE
        );

        // ====================================================
        // REPORT IDENTIFICATION
        // ====================================================

        assertEquals(
            businessAId,
            report.businessId()
        );

        assertEquals(
            REPORT_DATE,
            report.asOfDate()
        );


        // ====================================================
        // ALL SEVEN REPORTING GROUPS MUST EXIST
        // ====================================================

        assertEquals(
            7,
            report.sections().size()
        );


        // ====================================================
        // EMPTY REPORT TOTALS
        // ====================================================

        assertEquals("0.00", report.totalAssets());
        assertEquals("0.00", report.totalLiabilities());
        assertEquals("0.00", report.postedEquity());
        assertEquals("0.00", report.unclosedEarnings());
        assertEquals("0.00", report.totalEquity());
        assertEquals(
            "0.00",
            report.totalLiabilitiesAndEquity()
        );
        assertEquals("0.00", report.difference());


        // ====================================================
        // VALID REPORT STATUS
        // ====================================================

        assertTrue(report.balanced());

        assertTrue(report.classificationComplete());

        assertTrue(report.unclassifiedAccounts().isEmpty());
    }


    // ========================================================
    // TEST 2 — COMPLETE FINANCIAL STATEMENT
    // ========================================================

    @Test
    void calculatesAllFinancialTotalsExactly() {

        // ====================================================
        // OWNER CONTRIBUTES CAPITAL
        // ====================================================

        /**
         * Debit Cash:       1000.10
         * Credit Capital:   1000.10
         */
        postJournal(
            businessAId,
            cashId,
            capitalId,
            "1000.10",
            REPORT_DATE
        );


        // ====================================================
        // SALES REVENUE
        // ====================================================

        /**
         * Debit Cash:        300.03
         * Credit Revenue:    300.03
         */
        postJournal(
            businessAId,
            cashId,
            revenueId,
            "300.03",
            REPORT_DATE
        );


        // ====================================================
        // OPERATING EXPENSE
        // ====================================================

        /**
         * Debit Expense:      60.01
         * Credit Cash:        60.01
         */
        postJournal(
            businessAId,
            expenseId,
            cashId,
            "60.01",
            REPORT_DATE
        );


        // ====================================================
        // BUSINESS LIABILITY
        // ====================================================

        /**
         * Debit Cash:        200.02
         * Credit Payable:     200.02
         */
        postJournal(
            businessAId,
            cashId,
            payableId,
            "200.02",
            REPORT_DATE
        );


        // ====================================================
        // EQUIPMENT PURCHASE
        // ====================================================

        /**
         * Debit Equipment:   125.05
         * Credit Cash:       125.05
         */
        postJournal(
            businessAId,
            equipmentId,
            cashId,
            "125.05",
            REPORT_DATE
        );


        // ====================================================
        // GENERATE BALANCE SHEET
        // ====================================================

        BalanceSheetResponse report = getReport(
            businessAId,
            REPORT_DATE
        );


        // ====================================================
        // CURRENT ASSETS
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
        BalanceSheetResponse.Section currentAssets =
            findSection(report, "CURRENT_ASSET");

        assertEquals(
            "1315.09",
            currentAssets.subtotal()
        );

        assertEquals(
            "1315.09",
            findAccount(
                currentAssets,
                cashId
            ).balance()
        );


        // ====================================================
        // NON-CURRENT ASSETS
        // ====================================================

        assertEquals(
            "125.05",
            findSection(
                report,
                "NON_CURRENT_ASSET"
            ).subtotal()
        );


        // ====================================================
        // LIABILITIES
        // ====================================================

        assertEquals(
            "200.02",
            findSection(
                report,
                "CURRENT_LIABILITY"
            ).subtotal()
        );


        // ====================================================
        // CONTRIBUTED CAPITAL
        // ====================================================

        assertEquals(
            "1000.10",
            findSection(
                report,
                "CONTRIBUTED_CAPITAL"
            ).subtotal()
        );


        // ====================================================
        // ASSETS = CASH + EQUIPMENT
        // ====================================================

        assertEquals(
            "1440.14",
            report.totalAssets()
        );


        // ====================================================
        // TOTAL LIABILITIES
        // ====================================================

        assertEquals(
            "200.02",
            report.totalLiabilities()
        );


        // ====================================================
        // POSTED EQUITY
        // ====================================================

        assertEquals(
            "1000.10",
            report.postedEquity()
        );


        // ====================================================
        // UNCLOSED EARNINGS
        // ====================================================

        /**
         * Revenue: 300.03
         * Expense:  60.01
         *
         * Earnings = 240.02
         */
        assertEquals(
            "240.02",
            report.unclosedEarnings()
        );


        // ====================================================
        // TOTAL EQUITY
        // ====================================================

        /**
         * Posted Equity:     1000.10
         * Unclosed Earnings:  240.02
         *
         * Total Equity:     1240.12
         */
        assertEquals(
            "1240.12",
            report.totalEquity()
        );


        // ====================================================
        // LIABILITIES + EQUITY
        // ====================================================

        assertEquals(
            "1440.14",
            report.totalLiabilitiesAndEquity()
        );


        // ====================================================
        // ACCOUNTING EQUATION
        // ====================================================

        assertEquals(
            "0.00",
            report.difference()
        );

        assertTrue(report.balanced());

        assertTrue(report.classificationComplete());
    }


    // ========================================================
    // TEST 3 — UNCLASSIFIED LIABILITY WITH A BALANCE
    // ========================================================

    @Test
    void includesUnclassifiedBalancesInFinancialTotals() {

        Long unclassifiedLiabilityId = createAccount(
            businessAId,
            "2090",
            "Unclassified Loan",
            "LIABILITY",
            null
        );


        // ====================================================
        // RECEIVE LOAN PROCEEDS
        // ====================================================

        /**
         * Debit Cash:                  75.00
         * Credit Unclassified Loan:    75.00
         */
        postJournal(
            businessAId,
            cashId,
            unclassifiedLiabilityId,
            "75.00",
            REPORT_DATE
        );


        // ====================================================
        // GENERATE REPORT
        // ====================================================

        BalanceSheetResponse report = getReport(
            businessAId,
            REPORT_DATE
        );


        // ====================================================
        // CLASSIFICATION WARNING
        // ====================================================

        assertFalse(
            report.classificationComplete()
        );

        assertEquals(
            1,
            report.unclassifiedAccounts().size()
        );


        // ====================================================
        // VERIFY UNCLASSIFIED ACCOUNT DETAILS
        // ====================================================

        BalanceSheetResponse.AccountLine account =
            report.unclassifiedAccounts().get(0);

        assertEquals(
            unclassifiedLiabilityId,
            account.accountId()
        );

        assertEquals(
            "LIABILITY",
            account.accountType()
        );

        assertEquals(
            "75.00",
            account.balance()
        );


        // ====================================================
        // ACCOUNT REMAINS IN OVERALL FINANCIAL TOTALS
        // ====================================================

        assertEquals("75.00", report.totalAssets());

        assertEquals("75.00", report.totalLiabilities());


        // ====================================================
        // CLASSIFIED LIABILITY SECTION REMAINS ZERO
        // ====================================================

        assertEquals(
            "0.00",
            findSection(
                report,
                "CURRENT_LIABILITY"
            ).subtotal()
        );


        // ====================================================
        // BALANCE SHEET STILL RECONCILES
        // ====================================================

        assertTrue(report.balanced());

        assertEquals("0.00", report.difference());
    }


    // ========================================================
    // TEST 4 — ZERO-BALANCE UNCLASSIFIED ACTIVITY
    // ========================================================

    @Test
    void flagsInactiveUnclassifiedAccountWithOffsettingEntries() {

        Long unclassifiedAssetId = createAccount(
            businessAId,
            "1090",
            "Unclassified Asset",
            "ASSET",
            null
        );


        // ====================================================
        // FIRST POSTED JOURNAL
        // ====================================================

        /**
         * Debit Asset:      25.00
         * Credit Revenue:   25.00
         */
        postJournal(
            businessAId,
            unclassifiedAssetId,
            revenueId,
            "25.00",
            REPORT_DATE
        );


        // ====================================================
        // SECOND POSTED JOURNAL
        // ====================================================

        /**
         * Debit Revenue:    25.00
         * Credit Asset:     25.00
         *
         * The Asset now has:
         *
         * Balance: 0.00
         * Posted entries: 2
         */
        postJournal(
            businessAId,
            revenueId,
            unclassifiedAssetId,
            "25.00",
            REPORT_DATE
        );


        // ====================================================
        // DEACTIVATE THE ACCOUNT
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
        // GENERATE REPORT
        // ====================================================

        BalanceSheetResponse report = getReport(
            businessAId,
            REPORT_DATE
        );


        // ====================================================
        // HISTORICAL ACCOUNT MUST REMAIN VISIBLE
        // ====================================================

        assertFalse(report.classificationComplete());

        assertEquals(
            1,
            report.unclassifiedAccounts().size()
        );

        BalanceSheetResponse.AccountLine account =
            report.unclassifiedAccounts().get(0);

        assertEquals(
            unclassifiedAssetId,
            account.accountId()
        );

        assertFalse(account.active());

        assertEquals("0.00", account.balance());

        assertEquals(
            2L,
            account.postedEntryCount()
        );


        // ====================================================
        // BALANCE SHEET STILL BALANCES
        // ====================================================

        assertTrue(report.balanced());
    }


    // ========================================================
    // TEST 5 — AS-OF DATE AND DRAFT EXCLUSION
    // ========================================================

    @Test
    void respectsReportingDateAndExcludesDrafts() {

        // ====================================================
        // POST BEFORE REPORT DATE
        // ====================================================

        postJournal(
            businessAId,
            cashId,
            revenueId,
            "20.00",
            REPORT_DATE.minusDays(1)
        );


        // ====================================================
        // POST ON REPORT DATE
        // ====================================================

        postJournal(
            businessAId,
            cashId,
            revenueId,
            "30.00",
            REPORT_DATE
        );


        // ====================================================
        // POST AFTER REPORT DATE
        // ====================================================

        postJournal(
            businessAId,
            cashId,
            revenueId,
            "40.00",
            REPORT_DATE.plusDays(1)
        );


        // ====================================================
        // CREATE DRAFT TRANSACTION
        // ====================================================

        createJournal(
            businessAId,
            cashId,
            revenueId,
            "900.00",
            REPORT_DATE,
            false
        );


        // ====================================================
        // REPORT AS OF PREVIOUS DAY
        // ====================================================

        BalanceSheetResponse before = getReport(
            businessAId,
            REPORT_DATE.minusDays(1)
        );

        assertEquals("20.00", before.totalAssets());

        assertEquals("20.00", before.unclosedEarnings());


        // ====================================================
        // REPORT AS OF SELECTED DAY
        // ====================================================

        BalanceSheetResponse onDate = getReport(
            businessAId,
            REPORT_DATE
        );

        assertEquals("50.00", onDate.totalAssets());

        assertEquals("50.00", onDate.unclosedEarnings());


        // ====================================================
        // REPORT AS OF NEXT DAY
        // ====================================================

        BalanceSheetResponse after = getReport(
            businessAId,
            REPORT_DATE.plusDays(1)
        );

        assertEquals("90.00", after.totalAssets());

        assertEquals("90.00", after.unclosedEarnings());


        // ====================================================
        // ALL THREE REPORTS MUST BALANCE
        // ====================================================

        assertTrue(before.balanced());
        assertTrue(onDate.balanced());
        assertTrue(after.balanced());
    }


    // ========================================================
    // TEST 6 — NEGATIVE ACCOUNT BALANCES
    // ========================================================

    @Test
    void preservesNegativeFinancialAmounts() {

        // ====================================================
        // REVERSE REVENUE
        // ====================================================

        /**
         * Debit Revenue: 40.00
         * Credit Cash:   40.00
         *
         * Revenue balance = -40.00
         */
        postJournal(
            businessAId,
            revenueId,
            cashId,
            "40.00",
            REPORT_DATE
        );


        // ====================================================
        // CREDIT AN EXPENSE ACCOUNT
        // ====================================================

        /**
         * Debit Cash:    10.00
         * Credit Expense:10.00
         *
         * Expense balance = -10.00
         */
        postJournal(
            businessAId,
            cashId,
            expenseId,
            "10.00",
            REPORT_DATE
        );


        // ====================================================
        // GENERATE REPORT
        // ====================================================

        BalanceSheetResponse report = getReport(
            businessAId,
            REPORT_DATE
        );


        // ====================================================
        // VERIFY NEGATIVE CASH BALANCE
        // ====================================================

        assertEquals(
            "-30.00",
            report.totalAssets()
        );


        // ====================================================
        // VERIFY NEGATIVE UNCLOSED EARNINGS
        // ====================================================

        /**
         * Revenue: -40.00
         * Expense: -10.00
         *
         * Earnings:
         *
         * -40.00 - (-10.00) = -30.00
         */
        assertEquals(
            "-30.00",
            report.unclosedEarnings()
        );

        assertEquals(
            "-30.00",
            report.totalEquity()
        );


        // ====================================================
        // ACCOUNTING EQUATION REMAINS BALANCED
        // ====================================================

        assertTrue(report.balanced());

        assertEquals("0.00", report.difference());
    }


    // ========================================================
    // TEST 7 — MANUAL YEAR-END CLOSING ENTRY
    // ========================================================

    @Test
    void avoidsDoubleCountingManuallyClosedEarnings() {

        // ====================================================
        // CREATE RETAINED EARNINGS ACCOUNT
        // ====================================================

        Long retainedEarningsId = createAccount(
            businessAId,
            "3200",
            "Retained Earnings",
            "EQUITY",
            "RETAINED_EARNINGS"
        );


        // ====================================================
        // STEP 1 — EARN REVENUE
        // ====================================================

        /**
         * Debit Cash:       100.00
         * Credit Revenue:   100.00
         */
        postJournal(
            businessAId,
            cashId,
            revenueId,
            "100.00",
            REPORT_DATE.minusDays(1)
        );


        // ====================================================
        // VERIFY EARNINGS BEFORE CLOSING ENTRY
        // ====================================================

        BalanceSheetResponse beforeClosing = getReport(
            businessAId,
            REPORT_DATE.minusDays(1)
        );

        assertEquals(
            "100.00",
            beforeClosing.unclosedEarnings()
        );

        assertEquals(
            "0.00",
            beforeClosing.postedEquity()
        );

        assertEquals(
            "100.00",
            beforeClosing.totalEquity()
        );


        // ====================================================
        // STEP 2 — POST A MANUAL CLOSING JOURNAL
        // ====================================================

        /**
         * Debit Revenue:          100.00
         * Credit Retained Earnings:100.00
         *
         * This is a manually created journal
         * for testing purposes.
         *
         * LedgerBase does not automatically
         * generate year-end closing entries.
         *
         * The journal clears the Revenue balance
         * and transfers it into posted Equity.
         */
        postJournal(
            businessAId,
            revenueId,
            retainedEarningsId,
            "100.00",
            REPORT_DATE
        );


        // ====================================================
        // VERIFY BALANCE SHEET AFTER CLOSING
        // ====================================================

        BalanceSheetResponse afterClosing = getReport(
            businessAId,
            REPORT_DATE
        );


        // ====================================================
        // REVENUE NO LONGER CONTRIBUTES TO UNCLOSED EARNINGS
        // ====================================================

        assertEquals(
            "0.00",
            afterClosing.unclosedEarnings()
        );


        // ====================================================
        // EARNINGS NOW APPEAR IN POSTED EQUITY
        // ====================================================

        assertEquals(
            "100.00",
            afterClosing.postedEquity()
        );

        assertEquals(
            "100.00",
            findSection(
                afterClosing,
                "RETAINED_EARNINGS"
            ).subtotal()
        );


        // ====================================================
        // TOTAL EQUITY MUST NOT DOUBLE
        // ====================================================

        assertEquals(
            "100.00",
            afterClosing.totalEquity()
        );

        assertEquals(
            "100.00",
            afterClosing.totalAssets()
        );

        assertTrue(afterClosing.balanced());
    }


    // ========================================================
    // TEST 8 — BUSINESS ISOLATION
    // ========================================================

    @Test
    void isolatesFinancialReportsByBusiness() {

        // ====================================================
        // BUSINESS B POSTS REVENUE
        // ====================================================

        postJournal(
            businessBId,
            businessBCashId,
            businessBRevenueId,
            "75.00",
            REPORT_DATE
        );


        // ====================================================
        // BUSINESS A MUST HAVE ZERO BALANCES
        // ====================================================

        BalanceSheetResponse businessA = getReport(
            businessAId,
            REPORT_DATE
        );

        assertEquals(
            "0.00",
            businessA.totalAssets()
        );

        assertEquals(
            "0.00",
            businessA.unclosedEarnings()
        );


        // ====================================================
        // BUSINESS B MUST SHOW ITS OWN BALANCES
        // ====================================================

        BalanceSheetResponse businessB = getReport(
            businessBId,
            REPORT_DATE
        );

        assertEquals(
            "75.00",
            businessB.totalAssets()
        );

        assertEquals(
            "75.00",
            businessB.unclosedEarnings()
        );

        assertEquals(
            "75.00",
            businessB.totalEquity()
        );


        // ====================================================
        // BOTH FINANCIAL STATEMENTS RECONCILE
        // ====================================================

        assertTrue(businessA.balanced());

        assertTrue(businessB.balanced());
    }


    // ========================================================
    // TEST 9 — EARNINGS ACROSS FISCAL YEARS
    // ========================================================

    @Test
    void includesHistoricalUnclosedEarningsAcrossFiscalYears() {

        // ====================================================
        // CREATE EARLIER FISCAL YEAR
        // ====================================================

        /**
         * The @BeforeEach setup already created FY2026.
         *
         * FY2025 is separate and non-overlapping.
         *
         * Since LedgerBase does not automatically
         * generate year-end closing entries, amounts
         * remaining in Revenue and Expense accounts
         * carry forward into cumulative calculations.
         */
        fiscalYearService.createFiscalYear(
            businessAId,
            2025
        );


        // ====================================================
        // POST REVENUE DURING FY2025
        // ====================================================

        postJournal(
            businessAId,
            cashId,
            revenueId,
            "120.00",
            LocalDate.of(2025, 10, 13)
        );


        // ====================================================
        // POST REVENUE DURING FY2026
        // ====================================================

        postJournal(
            businessAId,
            cashId,
            revenueId,
            "30.00",
            REPORT_DATE
        );


        // ====================================================
        // VERIFY CUMULATIVE AMOUNTS
        // ====================================================

        BalanceSheetResponse report = getReport(
            businessAId,
            REPORT_DATE
        );

        assertEquals(
            "150.00",
            report.totalAssets()
        );

        assertEquals(
            "150.00",
            report.unclosedEarnings()
        );

        assertEquals(
            "150.00",
            report.totalEquity()
        );

        assertTrue(report.balanced());
    }


    // ========================================================
    // TEST 10 — REQUEST VALIDATION
    // ========================================================

    @Test
    void rejectsInvalidBusinessAndReportingDate() {

        // ====================================================
        // MISSING REPORTING DATE
        // ====================================================

        assertThrows(
            IllegalArgumentException.class,
            () -> balanceSheetService.getBalanceSheet(
                businessAId,
                null
            )
        );


        // ====================================================
        // NONEXISTENT BUSINESS
        // ====================================================

        assertThrows(
            BusinessNotFoundException.class,
            () -> balanceSheetService.getBalanceSheet(
                Long.MAX_VALUE,
                REPORT_DATE
            )
        );


        // ====================================================
        // INVALID BUSINESS IDENTIFIER
        // ====================================================

        assertThrows(
            BusinessNotFoundException.class,
            () -> balanceSheetService.getBalanceSheet(
                -1L,
                REPORT_DATE
            )
        );
    }


    // ========================================================
    // HELPER — GENERATE REPORT
    // ========================================================

    /**
     * Call the real Balance Sheet service.
     */
    private BalanceSheetResponse getReport(
        Long businessId,
        LocalDate asOfDate
    ) {

        return balanceSheetService.getBalanceSheet(
            businessId,
            asOfDate
        );
    }


    // ========================================================
    // HELPER — FIND REPORTING SECTION
    // ========================================================

    /**
     * Locate one of the seven V7 reporting groups.
     */
    private BalanceSheetResponse.Section findSection(
        BalanceSheetResponse report,
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
    // HELPER — FIND ACCOUNT WITHIN SECTION
    // ========================================================

    /**
     * Locate an account within a classified
     * financial statement section.
     */
    private BalanceSheetResponse.AccountLine findAccount(
        BalanceSheetResponse.Section section,
        Long accountId
    ) {

        return section.accounts().stream()
            .filter(account ->
                accountId.equals(
                    account.accountId()
                )
            )
            .findFirst()
            .orElseThrow(() ->
                new AssertionError(
                    "Missing account " + accountId
                )
            );
    }


    // ========================================================
    // HELPER — CREATE BUSINESS
    // ========================================================

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
     * Create an ordinary posting account.
     *
     * All fixture accounts are initially active.
     *
     * reportingGroup may be null because V7
     * permits unclassified accounts.
     */
    private Long createAccount(
        Long businessId,
        String code,
        String name,
        String accountType,
        String reportingGroup
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
            VALUES (?, ?, ?, ?, ?, TRUE, TRUE)
            RETURNING id
            """,
            Long.class,
            businessId,
            code,
            name,
            accountType,
            reportingGroup
        );
    }


    // ========================================================
    // HELPER — CREATE AND POST JOURNAL
    // ========================================================

    /**
     * Convenience method for a POSTED journal.
     */
    private void postJournal(
        Long businessId,
        Long debitAccountId,
        Long creditAccountId,
        String amount,
        LocalDate date
    ) {

        createJournal(
            businessId,
            debitAccountId,
            creditAccountId,
            amount,
            date,
            true
        );
    }


    // ========================================================
    // HELPER — CREATE JOURNAL TRANSACTION
    // ========================================================

    /**
     * Create a legitimate, balanced two-line journal.
     *
     * The fixture follows LedgerBase's existing
     * transaction and ledger-entry schema.
     *
     * Steps:
     *
     * 1. Insert DRAFT transaction.
     * 2. Insert debit ledger entry.
     * 3. Insert credit ledger entry.
     * 4. Optionally transition to POSTED.
     *
     * When posted is false, the transaction remains
     * DRAFT and must not affect financial reports.
     */
    private void createJournal(
        Long businessId,
        Long debitAccountId,
        Long creditAccountId,
        String amount,
        LocalDate transactionDate,
        boolean posted
    ) {

        // ====================================================
        // TRANSACTION HEADER
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
            "Balance Sheet Service integration test",
            Date.valueOf(transactionDate)
        );


        // ====================================================
        // EXACT MONETARY AMOUNT
        // ====================================================

        BigDecimal value = new BigDecimal(amount);


        // ====================================================
        // DEBIT ENTRY
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
            value,
            BigDecimal.ZERO
        );


        // ====================================================
        // CREDIT ENTRY
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
            value
        );


        // ====================================================
        // POST JOURNAL IF REQUESTED
        // ====================================================

        if (posted) {

            /**
             * PostgreSQL posting triggers validate
             * the applicable accounting period.
             */
            int updated = jdbcTemplate.update(
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

            assertEquals(
                1,
                updated,
                "Expected transaction posting to succeed"
            );
        }
    }
}
