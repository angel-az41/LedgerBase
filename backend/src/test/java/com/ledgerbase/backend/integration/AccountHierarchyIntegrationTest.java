package com.ledgerbase.backend.integration;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// V7 ACCOUNT HIERARCHY INTEGRATION TESTS
// ============================================================

/**
 * Verify PostgreSQL's enhanced Chart of Accounts rules.
 *
 * These tests intentionally bypass AccountService and
 * TransactionService to exercise database constraints
 * and trigger functions directly.
 *
 * Each test creates an isolated business and rolls back
 * all changes through TransactionTemplate.
 *
 * IMPORTANT:
 *
 * PostgreSQL aborts the current transaction when a SQL
 * statement fails. After an expected SQL exception,
 * we inspect the exception without running more SQL.
 *
 * V7 must be installed in the test database through
 * Flyway before these tests can be executed.
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountHierarchyIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;


    // ========================================================
    // TEST 1 — ALLOW VALID ACCOUNT HIERARCHY
    // ========================================================

    @Test
    void allowsValidParentChildHierarchy() {

        withRollbackFixture((userId, businessId) -> {

            // Create a non-postable organizational heading.
            Long parentId = createAccount(
                businessId,
                "1000",
                "Current Assets",
                "ASSET",
                "CURRENT_ASSET",
                null,
                false
            );

            // Create a postable account beneath the heading.
            Long childId = createAccount(
                businessId,
                "1010",
                "Cash",
                "ASSET",
                "CURRENT_ASSET",
                parentId,
                true
            );

            // Verify the relationship was stored correctly.
            Long storedParentId = jdbcTemplate.queryForObject(
                """
                SELECT parent_account_id
                FROM accounts
                WHERE business_id = ?
                  AND id = ?
                """,
                Long.class,
                businessId,
                childId
            );

            // Confirm both account roles.
            Boolean parentPostable = jdbcTemplate.queryForObject(
                """
                SELECT is_postable
                FROM accounts
                WHERE business_id = ?
                  AND id = ?
                """,
                Boolean.class,
                businessId,
                parentId
            );

            Boolean childPostable = jdbcTemplate.queryForObject(
                """
                SELECT is_postable
                FROM accounts
                WHERE business_id = ?
                  AND id = ?
                """,
                Boolean.class,
                businessId,
                childId
            );

            assertEquals(parentId, storedParentId);
            assertEquals(Boolean.FALSE, parentPostable);
            assertEquals(Boolean.TRUE, childPostable);
        });
    }


    // ========================================================
    // TEST 2 — REJECT INCOMPATIBLE REPORTING GROUP
    // ========================================================

    @Test
    void rejectsIncompatibleReportingGroup() {

        withRollbackFixture((userId, businessId) -> {

            /*
             * An ASSET account cannot be classified
             * as OPERATING_REVENUE.
             *
             * This must fail at the PostgreSQL level,
             * even without Java service validation.
             */
            DataIntegrityViolationException exception =
                assertThrows(
                    DataIntegrityViolationException.class,
                    () -> createAccount(
                        businessId,
                        "1020",
                        "Invalid Asset",
                        "ASSET",
                        "OPERATING_REVENUE",
                        null,
                        true
                    )
                );

            assertTrue(
                exception.getMostSpecificCause()
                    .getMessage()
                    .contains("chk_account_reporting_group")
            );
        });
    }


    // ========================================================
    // TEST 3 — REQUIRE NON-POSTABLE PARENT
    // ========================================================

    @Test
    void rejectsPostableAccountAsParent() {

        withRollbackFixture((userId, businessId) -> {

            // This account is a normal posting account.
            Long parentId = createAccount(
                businessId,
                "1000",
                "Cash",
                "ASSET",
                "CURRENT_ASSET",
                null,
                true
            );

            /*
             * A postable account cannot serve as an
             * organizational heading for other accounts.
             */
            DataIntegrityViolationException exception =
                assertThrows(
                    DataIntegrityViolationException.class,
                    () -> createAccount(
                        businessId,
                        "1010",
                        "Petty Cash",
                        "ASSET",
                        "CURRENT_ASSET",
                        parentId,
                        true
                    )
                );

            assertTrue(
                exception.getMostSpecificCause()
                    .getMessage()
                    .contains("must be non-postable")
            );
        });
    }


    // ========================================================
    // TEST 4 — REJECT LEDGER ENTRY TO GROUPING ACCOUNT
    // ========================================================

    @Test
    void rejectsLedgerEntryToNonPostableAccount() {

        withRollbackFixture((userId, businessId) -> {

            Long groupingAccountId = createAccount(
                businessId,
                "1000",
                "Current Assets",
                "ASSET",
                "CURRENT_ASSET",
                null,
                false
            );

            /*
             * Create a DRAFT transaction.
             *
             * A draft transaction does not require an
             * OPEN accounting period just to exist.
             *
             * The V7 ledger-entry trigger should reject
             * the account before journal posting occurs.
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
                "V7 non-postable account test"
            );

            // Attempt to debit a non-postable heading.
            DataIntegrityViolationException exception =
                assertThrows(
                    DataIntegrityViolationException.class,
                    () -> jdbcTemplate.update(
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
                        groupingAccountId,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO
                    )
                );

            assertTrue(
                exception.getMostSpecificCause()
                    .getMessage()
                    .contains("non-postable")
            );
        });
    }
    

    // ========================================================
    // TEST 5 — REJECT CROSS-BUSINESS ACCOUNT PARENTS
    // ========================================================

    @Test
    void rejectsParentAccountFromAnotherBusiness() {

        withRollbackFixture((userId, businessId) -> {

            // ====================================================
            // CREATE A SECOND BUSINESS
            // ====================================================

            /*
             * Both businesses belong to the same test user.
             *
             * Even with the same owner, their financial
             * accounts must remain strictly isolated.
             */
            Long otherBusinessId = jdbcTemplate.queryForObject(
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
                "V7 Other Business",
                "USD"
            );


            // ====================================================
            // CREATE A PARENT IN THE SECOND BUSINESS
            // ====================================================

            Long otherBusinessParentId = createAccount(
                otherBusinessId,
                "1000",
                "Other Business Current Assets",
                "ASSET",
                "CURRENT_ASSET",
                null,
                false
            );


            // ====================================================
            // ATTEMPT AN INVALID CROSS-BUSINESS RELATIONSHIP
            // ====================================================

            /*
             * The new account belongs to the first business.
             *
             * Its proposed parent belongs to the second.
             *
             * PostgreSQL must reject this relationship.
             */
            DataIntegrityViolationException exception =
                assertThrows(
                    DataIntegrityViolationException.class,
                    () -> createAccount(
                        businessId,
                        "1010",
                        "Invalid Cross-Business Cash",
                        "ASSET",
                        "CURRENT_ASSET",
                        otherBusinessParentId,
                        true
                    )
                );


            // ====================================================
            // VERIFY DATABASE-LEVEL REJECTION
            // ====================================================

            assertTrue(
                exception.getMostSpecificCause()
                    .getMessage()
                    .contains("does not exist in business")
            );
        });
    }


    // ========================================================
    // TEST 6 — REJECT CIRCULAR ACCOUNT HIERARCHIES
    // ========================================================

    @Test
    void rejectsCircularAccountHierarchy() {

        withRollbackFixture((userId, businessId) -> {

            // ====================================================
            // CREATE GROUP A
            // ====================================================

            Long groupAId = createAccount(
                businessId,
                "1000",
                "Asset Group A",
                "ASSET",
                "CURRENT_ASSET",
                null,
                false
            );


            // ====================================================
            // CREATE GROUP B UNDER GROUP A
            // ====================================================

            Long groupBId = createAccount(
                businessId,
                "1010",
                "Asset Group B",
                "ASSET",
                "CURRENT_ASSET",
                groupAId,
                false
            );


            /*
             * Existing hierarchy:
             *
             * Group A
             *   └── Group B
             *
             * Attempting to make Group A a child
             * of Group B would create:
             *
             * A -> B -> A
             *
             * The V7 trigger must reject this cycle.
             */


            // ====================================================
            // ATTEMPT CIRCULAR PARENT ASSIGNMENT
            // ====================================================

            DataIntegrityViolationException exception =
                assertThrows(
                    DataIntegrityViolationException.class,
                    () -> jdbcTemplate.update(
                        """
                        UPDATE accounts
                        SET parent_account_id = ?
                        WHERE business_id = ?
                          AND id = ?
                        """,
                        groupBId,
                        businessId,
                        groupAId
                    )
                );


            // ====================================================
            // VERIFY CYCLE DETECTION
            // ====================================================

            assertTrue(
                exception.getMostSpecificCause()
                    .getMessage()
                    .contains("Circular account hierarchy detected")
            );
        });
    }


    // ========================================================
    // TEST 7 — PROTECT ACCOUNTS WITH JOURNAL HISTORY
    // ========================================================

    @Test
    void rejectsMakingAccountWithLedgerEntriesNonPostable() {

        withRollbackFixture((userId, businessId) -> {

            // ====================================================
            // CREATE A NORMAL POSTABLE ACCOUNT
            // ====================================================

            Long cashAccountId = createAccount(
                businessId,
                "1000",
                "Cash",
                "ASSET",
                "CURRENT_ASSET",
                null,
                true
            );


            // ====================================================
            // CREATE A DRAFT JOURNAL TRANSACTION
            // ====================================================

            /*
             * We intentionally leave this transaction
             * in DRAFT status.
             *
             * Even draft ledger entries must prevent
             * the referenced account from becoming
             * non-postable.
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
                "V7 existing ledger entry protection test"
            );


            // ====================================================
            // CREATE A VALID LEDGER ENTRY
            // ====================================================

            /*
             * A single debit is allowed in DRAFT.
             *
             * Double-entry balancing is checked
             * when the transaction is POSTED.
             */
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
                cashAccountId,
                new BigDecimal("100.00"),
                BigDecimal.ZERO
            );

            assertEquals(1, inserted);


            // ====================================================
            // ATTEMPT TO MAKE THE ACCOUNT NON-POSTABLE
            // ====================================================

            /*
             * Cash already has journal history.
             *
             * Converting it into a grouping account
             * would violate our accounting model.
             *
             * PostgreSQL must reject the change.
             */
            DataIntegrityViolationException exception =
                assertThrows(
                    DataIntegrityViolationException.class,
                    () -> jdbcTemplate.update(
                        """
                        UPDATE accounts
                        SET is_postable = FALSE
                        WHERE business_id = ?
                          AND id = ?
                        """,
                        businessId,
                        cashAccountId
                    )
                );


            // ====================================================
            // VERIFY HISTORICAL INTEGRITY PROTECTION
            // ====================================================

            assertTrue(
                exception.getMostSpecificCause()
                    .getMessage()
                    .contains("has ledger entries")
            );
        });
    }


    // ========================================================
    // HELPER — ISOLATED ROLLBACK FIXTURE
    // ========================================================

    /**
     * Create a fresh user and business for each test.
     *
     * Using unique businesses ensures tests cannot
     * accidentally affect each other's accounting data.
     *
     * The fixture always rolls back, whether the
     * test succeeds or throws an exception.
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
                    "v7-hierarchy-" + UUID.randomUUID()
                        + "@ledgerbase.local",
                    "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
                    "V7 Hierarchy Test User"
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
                    "V7 Hierarchy Test Business",
                    "USD"
                );

                testAction.accept(userId, businessId);

            } finally {

                // Never commit temporary test records.
                status.setRollbackOnly();
            }
        });
    }


    // ========================================================
    // HELPER — CREATE ACCOUNT
    // ========================================================

    /**
     * Insert an account directly through PostgreSQL.
     *
     * Using raw SQL here tests the actual V7 constraints
     * and triggers rather than Java validation.
     */
    private Long createAccount(
            Long businessId,
            String code,
            String name,
            String accountType,
            String reportingGroup,
            Long parentAccountId,
            boolean isPostable) {

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
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            businessId,
            code,
            name,
            accountType,
            reportingGroup,
            parentAccountId,
            isPostable
        );
    }
}
