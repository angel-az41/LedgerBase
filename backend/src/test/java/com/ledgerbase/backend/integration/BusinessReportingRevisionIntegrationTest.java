package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.model.Account;
import com.ledgerbase.backend.service.AccountImportExecutionService;
import com.ledgerbase.backend.service.AccountService;
import com.ledgerbase.backend.service.BusinessReportingRevisionService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — REPORTING REVISION INTEGRATION TESTS
// ============================================================

/**
 * Verify durable business reporting revisions using
 * the actual PostgreSQL database and Spring services.
 *
 * COVERAGE:
 *
 * - Successful account creation.
 * - Successful account renaming.
 * - Account deactivation.
 * - Reporting group updates.
 * - Failed and unchanged operations.
 * - Atomic transaction rollback.
 * - Account import rollback.
 * - Business-level revision isolation.
 *
 * DATABASE:
 *
 * Uses ledgerbase_test through the test profile.
 *
 * This class does not use @Transactional on its tests.
 *
 * Successful service operations therefore commit through
 * their own transactional service boundaries.
 *
 * Explicit rollback tests use TransactionTemplate.
 *
 * Fixture records are removed after each test.
 */
@SpringBootTest
@ActiveProfiles("test")
class BusinessReportingRevisionIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountImportExecutionService importService;

    @Autowired
    private BusinessReportingRevisionService
        reportingRevisionService;

    @Autowired
    private PlatformTransactionManager transactionManager;


    // ========================================================
    // ISOLATED TEST FIXTURES
    // ========================================================

    private Long testUserId;

    private Long firstBusinessId;

    private Long secondBusinessId;


    // ========================================================
    // PREPARE TEST DATABASE
    // ========================================================

    /**
     * Create an isolated test user and two businesses.
     *
     * Each business starts with reporting revision zero.
     *
     * Never use the development or production database
     * for these integration tests.
     */
    @BeforeEach
    void setUp() {

        // ----------------------------------------------------
        // VERIFY TEST DATABASE
        // ----------------------------------------------------

        String databaseName = jdbcTemplate.queryForObject(
            "SELECT current_database()",
            String.class
        );

        assertEquals(
            "ledgerbase_test",
            databaseName,
            "Integration tests must use ledgerbase_test"
        );

        // ----------------------------------------------------
        // CREATE ISOLATED TEST USER
        // ----------------------------------------------------

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
            "reporting-revision-"
                + UUID.randomUUID()
                + "@ledgerbase.local",
            "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
            "Reporting Revision Test User"
        );

        // ----------------------------------------------------
        // CREATE TWO INDEPENDENT BUSINESSES
        // ----------------------------------------------------

        firstBusinessId = createTestBusiness(
            "Reporting Revision Business A"
        );

        secondBusinessId = createTestBusiness(
            "Reporting Revision Business B"
        );

        // ----------------------------------------------------
        // VERIFY INITIAL REPORTING REVISIONS
        // ----------------------------------------------------

        assertEquals(
            0L,
            getRevision(firstBusinessId)
        );

        assertEquals(
            0L,
            getRevision(secondBusinessId)
        );
    }


    // ========================================================
    // TEST — SUCCESSFUL ACCOUNT MUTATIONS
    // ========================================================

    /**
     * Verify that each committed account mutation advances
     * its business's reporting revision.
     *
     * Account creation affects report rows.
     * Renaming affects report descriptions.
     * Deactivation affects active status.
     * Reporting group updates affect classifications.
     */
    @Test
    void advancesRevisionAfterSuccessfulAccountChanges() {

        // ----------------------------------------------------
        // CREATE ACCOUNT
        // ----------------------------------------------------

        Account account = accountService.createAccount(
            firstBusinessId,
            "1010",
            "Operating Cash",
            "ASSET"
        );

        assertNotNull(account);
        assertNotNull(account.getId());

        assertEquals(
            1L,
            getRevision(firstBusinessId)
        );

        // ----------------------------------------------------
        // RENAME ACCOUNT
        // ----------------------------------------------------

        boolean renamed = accountService.renameAccount(
            firstBusinessId,
            account.getId(),
            "Primary Operating Cash"
        );

        assertTrue(renamed);

        assertEquals(
            2L,
            getRevision(firstBusinessId)
        );

        // ----------------------------------------------------
        // DEACTIVATE ACCOUNT
        // ----------------------------------------------------

        boolean deactivated = accountService.deactivateAccount(
            firstBusinessId,
            account.getId()
        );

        assertTrue(deactivated);

        assertEquals(
            3L,
            getRevision(firstBusinessId)
        );

        // ----------------------------------------------------
        // UPDATE REPORTING GROUP
        // ----------------------------------------------------

        /*
         * Inactive accounts can still receive reporting
         * classifications for historical statements.
         */

        Account updated = accountService.updateReportingGroup(
            firstBusinessId,
            account.getId(),
            "CURRENT_ASSET"
        );

        assertEquals(
            "CURRENT_ASSET",
            updated.getReportingGroup()
        );

        assertEquals(
            4L,
            getRevision(firstBusinessId)
        );

        // ----------------------------------------------------
        // VERIFY BUSINESS ISOLATION
        // ----------------------------------------------------

        assertEquals(
            0L,
            getRevision(secondBusinessId)
        );
    }


    // ========================================================
    // TEST — FAILED AND UNCHANGED OPERATIONS
    // ========================================================

    /**
     * Invalid operations should not advance revisions.
     *
     * An account that is already inactive is excluded
     * by the existing mapper's WHERE condition.
     */
    @Test
    void doesNotAdvanceRevisionForFailedOrUnchangedOperations() {

        Account account = accountService.createAccount(
            firstBusinessId,
            "1020",
            "Savings",
            "ASSET"
        );

        assertEquals(
            1L,
            getRevision(firstBusinessId)
        );

        // ----------------------------------------------------
        // REJECT INVALID ACCOUNT NAME
        // ----------------------------------------------------

        assertThrows(
            IllegalArgumentException.class,
            () -> accountService.renameAccount(
                firstBusinessId,
                account.getId(),
                " "
            )
        );

        assertEquals(
            1L,
            getRevision(firstBusinessId)
        );

        // ----------------------------------------------------
        // REJECT INCOMPATIBLE REPORTING GROUP
        // ----------------------------------------------------

        assertThrows(
            IllegalArgumentException.class,
            () -> accountService.updateReportingGroup(
                firstBusinessId,
                account.getId(),
                "OPERATING_REVENUE"
            )
        );

        assertEquals(
            1L,
            getRevision(firstBusinessId)
        );

        // ----------------------------------------------------
        // DEACTIVATE ACCOUNT ONCE
        // ----------------------------------------------------

        assertTrue(
            accountService.deactivateAccount(
                firstBusinessId,
                account.getId()
            )
        );

        assertEquals(
            2L,
            getRevision(firstBusinessId)
        );

        // ----------------------------------------------------
        // REPEATED DEACTIVATION IS A NO-OP
        // ----------------------------------------------------

        assertFalse(
            accountService.deactivateAccount(
                firstBusinessId,
                account.getId()
            )
        );

        assertEquals(
            2L,
            getRevision(firstBusinessId)
        );
    }


    // ========================================================
    // TEST — ATOMIC ACCOUNT CREATION ROLLBACK
    // ========================================================

    /**
     * Verify that rolling back a surrounding transaction
     * removes both the account and its revision increment.
     *
     * The exception is thrown after account creation
     * returns successfully.
     *
     * This isolates transaction rollback behavior from
     * ordinary account validation failures.
     */
    @Test
    void rollsBackAccountCreationAndRevisionTogether() {

        long originalRevision =
            getRevision(firstBusinessId);

        TransactionTemplate transaction =
            new TransactionTemplate(transactionManager);

        // ----------------------------------------------------
        // EXECUTE MUTATION AND FORCE ROLLBACK
        // ----------------------------------------------------

        assertThrows(
            IntentionalRollbackException.class,
            () -> transaction.executeWithoutResult(status -> {

                Account created = accountService.createAccount(
                    firstBusinessId,
                    "1030",
                    "Rollback Test Account",
                    "ASSET"
                );

                assertNotNull(created.getId());

                // Revision is visible inside the transaction.
                assertEquals(
                    originalRevision + 1,
                    getRevision(firstBusinessId)
                );

                // Force the enclosing transaction to roll back.
                throw new IntentionalRollbackException();
            })
        );

        // ----------------------------------------------------
        // VERIFY NO REVISION COMMITTED
        // ----------------------------------------------------

        assertEquals(
            originalRevision,
            getRevision(firstBusinessId)
        );

        // ----------------------------------------------------
        // VERIFY ACCOUNT INSERT WAS ALSO ROLLED BACK
        // ----------------------------------------------------

        assertEquals(
            0L,
            countAccountsByCode(
                firstBusinessId,
                "1030"
            )
        );
    }


    // ========================================================
    // TEST — ATOMIC ACCOUNT IMPORT ROLLBACK
    // ========================================================

    /**
     * Verify that account import and its reporting revision
     * increments participate in an outer transaction.
     *
     * The separate AccountImportRollbackIntegrationTest
     * already verifies rollback on an internal SQL failure.
     *
     * This test additionally verifies revision rollback.
     */
    @Test
    void rollsBackImportAndAllRevisionIncrementsTogether() {

        // ----------------------------------------------------
        // PREPARE VALID ACCOUNT IMPORT
        // ----------------------------------------------------

        String csv = """
            "Account Code","Account Name","Account Type","Reporting Group","Parent Account Code","Active","Postable"
            "9960","Revision Test Parent","ASSET","CURRENT_ASSET","","TRUE","FALSE"
            "9961","Revision Test Child","ASSET","CURRENT_ASSET","9960","TRUE","TRUE"
            """;

        byte[] csvBytes = csv.getBytes(
            StandardCharsets.UTF_8
        );

        long originalRevision =
            getRevision(firstBusinessId);

        TransactionTemplate transaction =
            new TransactionTemplate(transactionManager);

        // ----------------------------------------------------
        // IMPORT ACCOUNTS THEN FORCE ROLLBACK
        // ----------------------------------------------------

        assertThrows(
            IntentionalRollbackException.class,
            () -> transaction.executeWithoutResult(status -> {

                try {

                    importService.importAccounts(
                        firstBusinessId,
                        "revision-test.csv",
                        csvBytes
                    );

                } catch (IOException exception) {

                    throw new IllegalStateException(
                        "Failed to parse test CSV",
                        exception
                    );
                }

                // Two successful account creations
                // advanced the revision in this transaction.
                assertEquals(
                    originalRevision + 2,
                    getRevision(firstBusinessId)
                );

                throw new IntentionalRollbackException();
            })
        );

        // ----------------------------------------------------
        // VERIFY REVISION DID NOT COMMIT
        // ----------------------------------------------------

        assertEquals(
            originalRevision,
            getRevision(firstBusinessId)
        );

        // ----------------------------------------------------
        // VERIFY BOTH ACCOUNTS WERE ROLLED BACK
        // ----------------------------------------------------

        assertEquals(
            0L,
            countAccountsByCode(firstBusinessId, "9960")
        );

        assertEquals(
            0L,
            countAccountsByCode(firstBusinessId, "9961")
        );
    }


    // ========================================================
    // TEST — BUSINESS REVISION ISOLATION
    // ========================================================

    /**
     * Verify that revisions are scoped to individual
     * businesses rather than shared globally.
     */
    @Test
    void maintainsIndependentRevisionsForDifferentBusinesses() {

        Account firstAccount = accountService.createAccount(
            firstBusinessId,
            "1100",
            "Business A Cash",
            "ASSET"
        );

        assertNotNull(firstAccount.getId());

        assertEquals(
            1L,
            getRevision(firstBusinessId)
        );

        assertEquals(
            0L,
            getRevision(secondBusinessId)
        );

        Account secondAccount = accountService.createAccount(
            secondBusinessId,
            "1100",
            "Business B Cash",
            "ASSET"
        );

        assertNotNull(secondAccount.getId());

        assertEquals(
            1L,
            getRevision(firstBusinessId)
        );

        assertEquals(
            1L,
            getRevision(secondBusinessId)
        );

        // ----------------------------------------------------
        // MODIFY BUSINESS A ONLY
        // ----------------------------------------------------

        assertTrue(
            accountService.renameAccount(
                firstBusinessId,
                firstAccount.getId(),
                "Business A Main Cash"
            )
        );

        assertEquals(
            2L,
            getRevision(firstBusinessId)
        );

        assertEquals(
            1L,
            getRevision(secondBusinessId)
        );
    }


    // ========================================================
    // HELPER — CREATE TEST BUSINESS
    // ========================================================

    /**
     * Insert a business owned by the isolated test user.
     *
     * V11 provides the reporting_revision default.
     */
    private Long createTestBusiness(String name) {

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
    // HELPER — READ REPORTING REVISION
    // ========================================================

    /**
     * Read the revision through the production service.
     *
     * This also exercises the real MyBatis SELECT.
     */
    private long getRevision(Long businessId) {

        return reportingRevisionService.getCurrentRevision(
            businessId
        );
    }


    // ========================================================
    // HELPER — COUNT ACCOUNTS BY BUSINESS AND CODE
    // ========================================================

    /**
     * Read committed account rows directly from PostgreSQL.
     *
     * Used to verify that rolled-back inserts did not remain.
     */
    private long countAccountsByCode(
            Long businessId,
            String code) {

        Long count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM accounts
            WHERE business_id = ?
              AND code = ?
            """,
            Long.class,
            businessId,
            code
        );

        return count == null ? 0L : count;
    }


    // ========================================================
    // CLEAN UP TEST FIXTURES
    // ========================================================

    /**
     * Remove committed test records after each test.
     *
     * The tests use unique user and business identities.
     *
     * Delete dependent records before removing their
     * parent business and user records.
     *
     * This cleanup assumes the existing test schema
     * permits account deletion without posted entries.
     */
    @AfterEach
    void tearDown() {

        deleteTestBusiness(firstBusinessId);

        deleteTestBusiness(secondBusinessId);

        if (testUserId != null) {

            jdbcTemplate.update(
                "DELETE FROM users WHERE id = ?",
                testUserId
            );
        }
    }


    // ========================================================
    // HELPER — DELETE TEST BUSINESS
    // ========================================================

    /**
     * Remove accounts and membership records belonging
     * to a test business before deleting the business.
     *
     * No real financial transactions are posted
     * within this test class.
     */
    private void deleteTestBusiness(Long businessId) {

        if (businessId == null) {
            return;
        }

        jdbcTemplate.update(
            "DELETE FROM accounts WHERE business_id = ?",
            businessId
        );

        jdbcTemplate.update(
            "DELETE FROM business_memberships WHERE business_id = ?",
            businessId
        );

        jdbcTemplate.update(
            "DELETE FROM businesses WHERE id = ?",
            businessId
        );
    }


    // ========================================================
    // TEST-ONLY ROLLBACK EXCEPTION
    // ========================================================

    /**
     * Deliberately fail an outer transaction.
     *
     * This is not an application exception.
     * It exists only to verify PostgreSQL rollback.
     */
    private static class IntentionalRollbackException
            extends RuntimeException {
    }
}
