package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.model.AccountBalance;
import com.ledgerbase.backend.service.AccountService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — ACCOUNT BALANCE INTEGRATION TESTS
// ============================================================

/**
 * Integration tests for calculated account balances.
 *
 * These tests use:
 *
 * - Real Spring Boot application context
 * - AccountService
 * - AccountMapper
 * - PostgreSQL through MyBatis
 *
 * The database connection uses the "test" profile.
 *
 * IMPORTANT:
 *
 * @Transactional automatically rolls back database
 * changes after each test.
 *
 * This means our fixtures do not need to be deleted
 * manually, which is particularly important because
 * posted transactions are protected by database triggers.
 *
 * Each test creates its own independent accounting data.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AccountBalanceIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    /**
     * Test the actual AccountService implementation,
     * including business existence validation.
     */
    @Autowired
    private AccountService accountService;

    /**
     * Insert temporary PostgreSQL test fixtures.
     *
     * We use JDBC here only for arranging test data.
     * Balance retrieval goes through AccountService
     * and the real MyBatis mapper.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    // PREPARE TEST DATA
    // ========================================================

    /**
     * Create temporary records before each test:
     *
     * - One test user
     * - Two businesses owned by that user
     * - Four accounts in the first business
     * - Two accounts in the second business
     *
     * We do not create any transactions here.
     *
     * Each individual test controls whether
     * transactions are DRAFT or POSTED.
     */
    @BeforeEach
    void setUp() {

        // ====================================================
        // CREATE TEST USER
        // ====================================================

        String email = "balance-test-"
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
            "Account Balance Test User"
        );

        // ====================================================
        // CREATE FIRST TEST BUSINESS
        // ====================================================

        testBusinessId = createBusiness(
            "Account Balance Test Business"
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
            "Inactive Test Account",
            "ASSET",
            false
        );

        // ====================================================
        // CREATE SECOND TEST BUSINESS
        // ====================================================

        /**
         * The second business allows us to verify
         * that account balances cannot accidentally
         * include another business's ledger entries.
         */
        otherBusinessId = createBusiness(
            "Other Account Balance Test Business"
        );

        otherCheckingAccountId = createAccount(
            otherBusinessId,
            "1010",
            "Other Business Checking",
            "ASSET",
            true
        );

        otherSavingsAccountId = createAccount(
            otherBusinessId,
            "1020",
            "Other Business Savings",
            "ASSET",
            true
        );
    }

    // ========================================================
    // TEST 1 — POSTED TRANSACTION BALANCES
    // ========================================================

    /**
     * Verify the accounting treatment of transferring
     * $500 from checking to savings.
     *
     * Debit Savings:  500.00
     * Credit Checking: 500.00
     *
     * Expected balances:
     *
     * Checking: -500.00
     * Savings:  +500.00
     *
     * Both are ASSET accounts, so their signed balance
     * is calculated as debits minus credits.
     */
    @Test
    void calculatesBalancesFromPostedTransactions() {

        createJournalEntry(
            testBusinessId,
            savingsAccountId,
            checkingAccountId,
            "500.00",
            true
        );

        List<AccountBalance> balances =
            accountService.getAccountBalances(testBusinessId);

        AccountBalance checking =
            findAccount(balances, "1010");

        AccountBalance savings =
            findAccount(balances, "1020");

        // ====================================================
        // VERIFY CHECKING ACCOUNT
        // ====================================================

        assertMoneyEquals(
            "0.00",
            checking.getPostedDebits()
        );

        assertMoneyEquals(
            "500.00",
            checking.getPostedCredits()
        );

        assertMoneyEquals(
            "-500.00",
            checking.getBalance()
        );

        // ====================================================
        // VERIFY SAVINGS ACCOUNT
        // ====================================================

        assertMoneyEquals(
            "500.00",
            savings.getPostedDebits()
        );

        assertMoneyEquals(
            "0.00",
            savings.getPostedCredits()
        );

        assertMoneyEquals(
            "500.00",
            savings.getBalance()
        );
    }

    // ========================================================
    // TEST 2 — EXCLUDE DRAFT TRANSACTIONS
    // ========================================================

    /**
     * Verify that a balanced DRAFT transaction
     * does not change posted account balances.
     *
     * A draft may contain valid debit and credit
     * entries, but those entries are not yet finalized.
     */
    @Test
    void excludesDraftTransactionsFromBalances() {

        createJournalEntry(
            testBusinessId,
            savingsAccountId,
            checkingAccountId,
            "250.00",
            false
        );

        List<AccountBalance> balances =
            accountService.getAccountBalances(testBusinessId);

        AccountBalance checking =
            findAccount(balances, "1010");

        AccountBalance savings =
            findAccount(balances, "1020");

        assertMoneyEquals(
            "0.00",
            checking.getBalance()
        );

        assertMoneyEquals(
            "0.00",
            savings.getBalance()
        );

        assertMoneyEquals(
            "0.00",
            savings.getPostedDebits()
        );

        assertMoneyEquals(
            "0.00",
            checking.getPostedCredits()
        );
    }

    // ========================================================
    // TEST 3 — ZERO-BALANCE AND INACTIVE ACCOUNTS
    // ========================================================

    /**
     * Verify that accounts remain visible even
     * when they have no posted ledger activity.
     *
     * Inactive accounts must also remain included
     * because historical accounting records may
     * reference them.
     */
    @Test
    void includesZeroBalanceAndInactiveAccounts() {

        List<AccountBalance> balances =
            accountService.getAccountBalances(testBusinessId);

        // Four accounts were created for this business.
        assertEquals(4, balances.size());

        AccountBalance inactive =
            findAccount(balances, "1090");

        assertEquals(
            inactiveAccountId,
            inactive.getId()
        );

        assertFalse(inactive.isActive());

        assertMoneyEquals(
            "0.00",
            inactive.getPostedDebits()
        );

        assertMoneyEquals(
            "0.00",
            inactive.getPostedCredits()
        );

        assertMoneyEquals(
            "0.00",
            inactive.getBalance()
        );
    }

    // ========================================================
    // TEST 4 — CREDIT-NORMAL ACCOUNT BALANCES
    // ========================================================

    /**
     * Verify the correct balance direction
     * for a REVENUE account.
     *
     * Example:
     *
     * Debit Checking: 200.00
     * Credit Revenue: 200.00
     *
     * Expected:
     *
     * Checking balance: +200.00
     * Revenue balance:  +200.00
     *
     * Revenue accounts have a normal CREDIT balance.
     */
    @Test
    void calculatesCreditNormalAccountBalances() {

        createJournalEntry(
            testBusinessId,
            checkingAccountId,
            revenueAccountId,
            "200.00",
            true
        );

        List<AccountBalance> balances =
            accountService.getAccountBalances(testBusinessId);

        AccountBalance checking =
            findAccount(balances, "1010");

        AccountBalance revenue =
            findAccount(balances, "4000");

        assertMoneyEquals(
            "200.00",
            checking.getBalance()
        );

        assertMoneyEquals(
            "0.00",
            revenue.getPostedDebits()
        );

        assertMoneyEquals(
            "200.00",
            revenue.getPostedCredits()
        );

        assertMoneyEquals(
            "200.00",
            revenue.getBalance()
        );
    }

    // ========================================================
    // TEST 5 — BUSINESS DATA ISOLATION
    // ========================================================

    /**
     * Verify that one business cannot receive
     * ledger balances belonging to another.
     *
     * Both businesses intentionally have accounts
     * with codes 1010 and 1020.
     *
     * This proves account codes alone are not
     * sufficient to identify account ownership.
     */
    @Test
    void keepsAccountBalancesIsolatedByBusiness() {

        // Create activity in the OTHER business only.
        createJournalEntry(
            otherBusinessId,
            otherCheckingAccountId,
            otherSavingsAccountId,
            "125.00",
            true
        );

        // ====================================================
        // QUERY FIRST BUSINESS
        // ====================================================

        List<AccountBalance> firstBusinessBalances =
            accountService.getAccountBalances(testBusinessId);

        AccountBalance firstChecking =
            findAccount(firstBusinessBalances, "1010");

        assertMoneyEquals(
            "0.00",
            firstChecking.getBalance()
        );

        // ====================================================
        // QUERY SECOND BUSINESS
        // ====================================================

        List<AccountBalance> secondBusinessBalances =
            accountService.getAccountBalances(otherBusinessId);

        AccountBalance secondChecking =
            findAccount(secondBusinessBalances, "1010");

        assertMoneyEquals(
            "125.00",
            secondChecking.getBalance()
        );

        // All returned accounts belong to the
        // business requested by the service caller.
        assertTrue(
            firstBusinessBalances.stream().allMatch(
                account ->
                    account.getBusinessId().equals(testBusinessId)
            )
        );

        assertTrue(
            secondBusinessBalances.stream().allMatch(
                account ->
                    account.getBusinessId().equals(otherBusinessId)
            )
        );
    }

    // ========================================================
    // TEST 6 — NONEXISTENT BUSINESS
    // ========================================================

    /**
     * Verify that AccountService rejects a
     * request for a nonexistent business.
     *
     * BusinessNotFoundException should eventually
     * translate into HTTP 404 through the API layer.
     */
    @Test
    void rejectsNonexistentBusiness() {

        assertThrows(
            BusinessNotFoundException.class,
            () -> accountService.getAccountBalances(0L)
        );

        assertThrows(
            BusinessNotFoundException.class,
            () -> accountService.getAccountBalances(null)
        );
    }

    // ========================================================
    // HELPER — CREATE TEST BUSINESS
    // ========================================================

    /**
     * Insert a business owned by our temporary user.
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
    // HELPER — CREATE TEST ACCOUNT
    // ========================================================

    /**
     * Create a real PostgreSQL account fixture.
     *
     * We can explicitly choose the active status
     * so tests can cover inactive accounts.
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
    // HELPER — CREATE JOURNAL ENTRY
    // ========================================================

    /**
     * Create a balanced two-line journal entry.
     *
     * Parameters:
     *
     * businessId:
     *     Business that owns the transaction.
     *
     * debitAccountId:
     *     Account receiving the debit.
     *
     * creditAccountId:
     *     Account receiving the credit.
     *
     * amount:
     *     Exact decimal amount, such as "500.00".
     *
     * posted:
     *     Whether the fixture should become POSTED.
     *
     * IMPORTANT:
     *
     * First create the transaction as DRAFT.
     *
     * Then insert its ledger entries.
     *
     * Only afterward transition to POSTED.
     *
     * This respects the order in which our accounting
     * database expects journal entries to be created.
     *
     * This helper sets up test data directly through
     * JDBC to isolate the account-balance calculation.
     * The production posting workflow has separate tests.
     */
    private void createJournalEntry(
            Long businessId,
            Long debitAccountId,
            Long creditAccountId,
            String amount,
            boolean posted) {

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
            VALUES (
                ?,
                ?,
                ?,
                DATE '2026-10-13',
                'DRAFT'
            )
            RETURNING id
            """,
            Long.class,
            businessId,
            testUserId,
            "Account balance integration test"
        );

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
        // OPTIONALLY MARK THE TRANSACTION AS POSTED
        // ====================================================

        /**
         * Transition only after the ledger entries
         * have been inserted and balanced.
         *
         * The entire fixture is rolled back after
         * its test method completes.
         */
        if (posted) {

            jdbcTemplate.update(
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
        }
    }

    // ========================================================
    // HELPER — FIND ACCOUNT BY CODE
    // ========================================================

    /**
     * Find one account inside a returned balance list.
     *
     * These are already business-scoped results.
     */
    private AccountBalance findAccount(
            List<AccountBalance> balances,
            String code) {

        return balances.stream()
            .filter(account -> code.equals(account.getCode()))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError(
                    "Expected account not found: " + code
                )
            );
    }

    // ========================================================
    // HELPER — COMPARE EXACT MONETARY VALUES
    // ========================================================

    /**
     * Compare monetary amounts numerically.
     *
     * BigDecimal.equals() also compares scale.
     *
     * For example:
     *
     * 0.0 and 0.00 are numerically equal but
     * may not be equal using BigDecimal.equals().
     *
     * compareTo() lets us verify the exact amount
     * without requiring matching decimal scale.
     */
    private void assertMoneyEquals(
            String expected,
            BigDecimal actual) {

        assertNotNull(
            actual,
            "Monetary value should not be null"
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
