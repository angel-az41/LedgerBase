package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.model.Account;
import com.ledgerbase.backend.service.AccountService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — ACCOUNT REPORTING GROUP INTEGRATION TESTS
// ============================================================

/**
 * Verify the full service-to-database workflow for
 * updating financial reporting classifications.
 *
 * The tests exercise:
 *
 * AccountService
 *     -> AccountMapper
 *     -> MyBatis XML
 *     -> PostgreSQL
 *
 * Each test creates an isolated user and business.
 *
 * TransactionTemplate rolls back all fixture data
 * after the test, keeping ledgerbase_test clean.
 *
 * IMPORTANT:
 *
 * These are service/database integration tests.
 *
 * They verify that missing accounts produce the
 * intended ResponseStatusException, but they do
 * not directly test HTTP response serialization.
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountReportingGroupIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    @Autowired
    private AccountService accountService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;


    // ========================================================
    // TEST 1 — UPDATE A VALID REPORTING GROUP
    // ========================================================

    @Test
    void updatesValidReportingGroup() {

        withRollbackFixture((userId, businessId) -> {

            // ====================================================
            // CREATE AN UNCLASSIFIED ASSET ACCOUNT
            // ====================================================

            Long accountId = createAccount(
                businessId,
                "1010",
                "Operating Checking Account",
                "ASSET",
                null,
                true
            );

            // ====================================================
            // UPDATE CLASSIFICATION THROUGH ACCOUNT SERVICE
            // ====================================================

            Account updatedAccount =
                accountService.updateReportingGroup(
                    businessId,
                    accountId,
                    "CURRENT_ASSET"
                );

            // ====================================================
            // VERIFY THE RETURNED ACCOUNT
            // ====================================================

            assertNotNull(updatedAccount);

            assertEquals(
                accountId,
                updatedAccount.getId()
            );

            assertEquals(
                "ASSET",
                updatedAccount.getAccountType()
            );

            assertEquals(
                "CURRENT_ASSET",
                updatedAccount.getReportingGroup()
            );

            // ====================================================
            // VERIFY POSTGRESQL PERSISTED THE CHANGE
            // ====================================================

            assertEquals(
                "CURRENT_ASSET",
                findReportingGroup(businessId, accountId)
            );
        });
    }


    // ========================================================
    // TEST 2 — REJECT AN INCOMPATIBLE REPORTING GROUP
    // ========================================================

    @Test
    void rejectsIncompatibleReportingGroup() {

        withRollbackFixture((userId, businessId) -> {

            // ====================================================
            // CREATE A VALID ASSET ACCOUNT
            // ====================================================

            Long accountId = createAccount(
                businessId,
                "1020",
                "Business Savings",
                "ASSET",
                "CURRENT_ASSET",
                true
            );

            // ====================================================
            // ATTEMPT AN INVALID CLASSIFICATION
            // ====================================================

            /*
             * OPERATING_REVENUE is a recognized V7 group,
             * but it does not belong to the ASSET type.
             *
             * AccountService must reject this combination.
             */
            assertThrows(
                IllegalArgumentException.class,
                () -> accountService.updateReportingGroup(
                    businessId,
                    accountId,
                    "OPERATING_REVENUE"
                )
            );

            // ====================================================
            // VERIFY THE STORED CLASSIFICATION WAS PRESERVED
            // ====================================================

            assertEquals(
                "CURRENT_ASSET",
                findReportingGroup(businessId, accountId)
            );
        });
    }


    // ========================================================
    // TEST 3 — ENFORCE ISOLATION BETWEEN REAL BUSINESSES
    // ========================================================

    @Test
    void rejectsCrossBusinessReportingGroupUpdate() {

        withRollbackFixture((userId, businessAId) -> {

            // ====================================================
            // CREATE A SECOND REAL BUSINESS
            // ====================================================

            /*
             * Both businesses use the same test owner.
             *
             * Even then, an account must only be modified
             * through its own business-scoped API logic.
             */
            Long businessBId = createBusiness(
                userId,
                "Reporting Group Test Business B"
            );

            // ====================================================
            // CREATE AN ACCOUNT BELONGING TO BUSINESS A
            // ====================================================

            Long accountId = createAccount(
                businessAId,
                "1030",
                "Business A Cash",
                "ASSET",
                "CURRENT_ASSET",
                true
            );

            // ====================================================
            // ATTEMPT TO MODIFY IT THROUGH BUSINESS B
            // ====================================================

            ResponseStatusException exception =
                assertThrows(
                    ResponseStatusException.class,
                    () -> accountService.updateReportingGroup(
                        businessBId,
                        accountId,
                        "NON_CURRENT_ASSET"
                    )
                );

            // ====================================================
            // VERIFY THE SERVICE RETURNS NOT FOUND
            // ====================================================

            assertEquals(
                HttpStatus.NOT_FOUND,
                exception.getStatusCode()
            );

            // ====================================================
            // VERIFY BUSINESS A'S ACCOUNT WAS NOT MODIFIED
            // ====================================================

            assertEquals(
                "CURRENT_ASSET",
                findReportingGroup(
                    businessAId,
                    accountId
                )
            );
        });
    }


    // ========================================================
    // TEST 4 — HANDLE NONEXISTENT ACCOUNTS
    // ========================================================

    @Test
    void returnsNotFoundForMissingAccount() {

        withRollbackFixture((userId, businessId) -> {

            /*
             * Long.MAX_VALUE is used as an account ID
             * that should not exist in our isolated
             * test fixture.
             *
             * The service must not throw a
             * NullPointerException.
             */
            ResponseStatusException exception =
                assertThrows(
                    ResponseStatusException.class,
                    () -> accountService.updateReportingGroup(
                        businessId,
                        Long.MAX_VALUE,
                        "CURRENT_ASSET"
                    )
                );

            assertEquals(
                HttpStatus.NOT_FOUND,
                exception.getStatusCode()
            );
        });
    }


    // ========================================================
    // TEST 5 — CLASSIFY AN INACTIVE ACCOUNT
    // ========================================================

    @Test
    void allowsInactiveAccountToBeClassified() {

        withRollbackFixture((userId, businessId) -> {

            // ====================================================
            // CREATE A NORMAL ASSET ACCOUNT
            // ====================================================

            Long accountId = createAccount(
                businessId,
                "1040",
                "Former Savings Account",
                "ASSET",
                null,
                true
            );

            // ====================================================
            // DEACTIVATE USING THE EXISTING SERVICE
            // ====================================================

            /*
             * An inactive account remains part of the
             * historical Chart of Accounts.
             *
             * Deactivation should not prevent its
             * financial reporting classification.
             */
            accountService.deactivateAccount(
                businessId,
                accountId
            );

            // ====================================================
            // CLASSIFY THE INACTIVE ACCOUNT
            // ====================================================

            Account updatedAccount =
                accountService.updateReportingGroup(
                    businessId,
                    accountId,
                    "CURRENT_ASSET"
                );

            // ====================================================
            // VERIFY THE UPDATE SUCCEEDED
            // ====================================================

            assertEquals(
                "CURRENT_ASSET",
                updatedAccount.getReportingGroup()
            );

            assertEquals(
                "CURRENT_ASSET",
                findReportingGroup(businessId, accountId)
            );
        });
    }


    // ========================================================
    // TEST 6 — PRESERVE ACCOUNT IDENTITY AND JOURNAL HISTORY
    // ========================================================

    @Test
    void preservesAccountIdentityAndLedgerEntries() {

        withRollbackFixture((userId, businessId) -> {

            // ====================================================
            // CREATE AN ACCOUNT WITH EXISTING JOURNAL HISTORY
            // ====================================================

            Long accountId = createAccount(
                businessId,
                "1050",
                "Historical Cash",
                "ASSET",
                null,
                true
            );

            // ====================================================
            // CREATE A DRAFT TRANSACTION
            // ====================================================

            /*
             * A DRAFT transaction lets us create ledger
             * history without requiring a posted fiscal
             * period in this particular test fixture.
             *
             * We verify existing ledger entry rows are
             * unchanged by reporting-group updates.
             */
            Long transactionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO transactions (
                    business_id,
                    created_by,
                    description,
                    transaction_date,
                    status
                )
                VALUES (?, ?, ?, CURRENT_DATE, 'DRAFT')
                RETURNING id
                """,
                Long.class,
                businessId,
                userId,
                "Reporting group history protection test"
            );

            // ====================================================
            // CREATE AN EXISTING LEDGER ENTRY
            // ====================================================

            int inserted = jdbcTemplate.update(
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
                accountId,
                new BigDecimal("75.00"),
                BigDecimal.ZERO
            );

            assertEquals(1, inserted);

            // ====================================================
            // SNAPSHOT ACCOUNT IDENTITY BEFORE THE UPDATE
            // ====================================================

            Map<String, Object> accountBefore =
                jdbcTemplate.queryForMap(
                    """
                    SELECT
                        id,
                        business_id,
                        code,
                        name,
                        account_type,
                        parent_account_id,
                        is_postable
                    FROM accounts
                    WHERE business_id = ?
                      AND id = ?
                    """,
                    businessId,
                    accountId
                );

            // ====================================================
            // UPDATE ONLY THE REPORTING CLASSIFICATION
            // ====================================================

            accountService.updateReportingGroup(
                businessId,
                accountId,
                "CURRENT_ASSET"
            );

            // ====================================================
            // SNAPSHOT ACCOUNT IDENTITY AFTER THE UPDATE
            // ====================================================

            Map<String, Object> accountAfter =
                jdbcTemplate.queryForMap(
                    """
                    SELECT
                        id,
                        business_id,
                        code,
                        name,
                        account_type,
                        parent_account_id,
                        is_postable
                    FROM accounts
                    WHERE business_id = ?
                      AND id = ?
                    """,
                    businessId,
                    accountId
                );

            // ====================================================
            // VERIFY ACCOUNT IDENTITY REMAINS UNCHANGED
            // ====================================================

            assertEquals(
                accountBefore,
                accountAfter
            );

            // ====================================================
            // VERIFY REPORTING CLASSIFICATION WAS UPDATED
            // ====================================================

            assertEquals(
                "CURRENT_ASSET",
                findReportingGroup(businessId, accountId)
            );

            // ====================================================
            // VERIFY THE EXISTING LEDGER ENTRY STILL EXISTS
            // ====================================================

            Integer entryCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM ledger_entries
                WHERE business_id = ?
                  AND transaction_id = ?
                  AND account_id = ?
                """,
                Integer.class,
                businessId,
                transactionId,
                accountId
            );

            assertEquals(1, entryCount);

            // ====================================================
            // VERIFY THE ORIGINAL DEBIT REMAINS UNCHANGED
            // ====================================================

            BigDecimal storedDebit =
                jdbcTemplate.queryForObject(
                    """
                    SELECT debit
                    FROM ledger_entries
                    WHERE business_id = ?
                      AND transaction_id = ?
                      AND account_id = ?
                    """,
                    BigDecimal.class,
                    businessId,
                    transactionId,
                    accountId
                );

            assertNotNull(storedDebit);

            assertEquals(
                0,
                storedDebit.compareTo(
                    new BigDecimal("75.00")
                )
            );

            // ====================================================
            // VERIFY THE ORIGINAL CREDIT REMAINS UNCHANGED
            // ====================================================

            BigDecimal storedCredit =
                jdbcTemplate.queryForObject(
                    """
                    SELECT credit
                    FROM ledger_entries
                    WHERE business_id = ?
                      AND transaction_id = ?
                      AND account_id = ?
                    """,
                    BigDecimal.class,
                    businessId,
                    transactionId,
                    accountId
                );

            assertNotNull(storedCredit);

            assertEquals(
                0,
                storedCredit.compareTo(BigDecimal.ZERO)
            );
        });
    }


    // ========================================================
    // TEST 7 — POSTGRESQL REJECTS INVALID DIRECT UPDATES
    // ========================================================

    @Test
    void databaseRejectsIncompatibleReportingGroupUpdate() {

        withRollbackFixture((userId, businessId) -> {

            // ====================================================
            // CREATE A VALID ASSET ACCOUNT
            // ====================================================

            Long accountId = createAccount(
                businessId,
                "1060",
                "Database Constraint Test",
                "ASSET",
                "CURRENT_ASSET",
                true
            );

            // ====================================================
            // BYPASS THE SERVICE AND UPDATE DIRECTLY
            // ====================================================

            /*
             * The database must enforce classification
             * integrity even if an application accidentally
             * bypasses AccountService.
             *
             * This test intentionally causes a PostgreSQL
             * constraint violation.
             */
            DataIntegrityViolationException exception =
                assertThrows(
                    DataIntegrityViolationException.class,
                    () -> jdbcTemplate.update(
                        """
                        UPDATE accounts
                        SET reporting_group = ?
                        WHERE business_id = ?
                          AND id = ?
                        """,
                        "OPERATING_REVENUE",
                        businessId,
                        accountId
                    )
                );

            // ====================================================
            // VERIFY DATABASE CONSTRAINT REJECTION
            // ====================================================

            assertTrue(
                exception.getMostSpecificCause()
                    .getMessage()
                    .contains("chk_account_reporting_group")
            );

            /*
             * PostgreSQL marks this transaction as aborted
             * after the expected SQL error.
             *
             * Do not run additional SQL here.
             *
             * withRollbackFixture() rolls back the
             * entire temporary transaction afterward.
             */
        });
    }


    // ========================================================
    // HELPER — CREATE AN ISOLATED ROLLBACK FIXTURE
    // ========================================================

    /**
     * Create a temporary user and business for each test.
     *
     * TransactionTemplate ensures fixture changes are
     * rolled back even when an assertion fails.
     *
     * This matches the approach already used in
     * AccountHierarchyIntegrationTest.
     */
    private void withRollbackFixture(
        BiConsumer<Long, Long> testAction
    ) {

        TransactionTemplate template =
            new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status -> {

            try {

                // ====================================================
                // CREATE TEMPORARY TEST USER
                // ====================================================

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
                    "v7-reporting-"
                        + UUID.randomUUID()
                        + "@ledgerbase.local",
                    "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
                    "V7 Reporting Group Test User"
                );

                // ====================================================
                // CREATE TEMPORARY TEST BUSINESS
                // ====================================================

                Long businessId = createBusiness(
                    userId,
                    "V7 Reporting Group Test Business"
                );

                // ====================================================
                // EXECUTE TEST LOGIC
                // ====================================================

                testAction.accept(userId, businessId);

            } finally {

                // Never commit temporary test records.
                status.setRollbackOnly();
            }
        });
    }


    // ========================================================
    // HELPER — CREATE A BUSINESS
    // ========================================================

    /**
     * Create a business owned by the supplied user.
     *
     * Also used to create a second real business
     * for the business-isolation test.
     */
    private Long createBusiness(
        Long userId,
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
            userId,
            businessName,
            "USD"
        );
    }


    // ========================================================
    // HELPER — CREATE A POSTABLE ACCOUNT
    // ========================================================

    /**
     * Insert a test account directly into PostgreSQL.
     *
     * We allow an optional initial reporting group
     * so tests can start with classified or
     * unclassified accounts.
     */
    private Long createAccount(
        Long businessId,
        String code,
        String name,
        String accountType,
        String reportingGroup,
        boolean isPostable
    ) {

        return jdbcTemplate.queryForObject(
            """
            INSERT INTO accounts (
                business_id,
                code,
                name,
                account_type,
                reporting_group,
                parent_account_id,
                is_postable
            )
            VALUES (?, ?, ?, ?, ?, NULL, ?)
            RETURNING id
            """,
            Long.class,
            businessId,
            code,
            name,
            accountType,
            reportingGroup,
            isPostable
        );
    }


    // ========================================================
    // HELPER — READ PERSISTED REPORTING GROUP
    // ========================================================

    /**
     * Read the actual classification stored in PostgreSQL.
     *
     * This avoids relying only on the Account object
     * returned by AccountService.
     */
    private String findReportingGroup(
        Long businessId,
        Long accountId
    ) {

        return jdbcTemplate.queryForObject(
            """
            SELECT reporting_group
            FROM accounts
            WHERE business_id = ?
              AND id = ?
            """,
            String.class,
            businessId,
            accountId
        );
    }
}
