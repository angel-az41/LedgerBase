package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.dto.AccountImportPreviewResponse;
import com.ledgerbase.backend.service.AccountImportExecutionService;
import com.ledgerbase.backend.service.AccountImportFileParser;
import com.ledgerbase.backend.service.AccountImportValidationService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — ACCOUNT IMPORT ROLLBACK INTEGRATION TEST
// ============================================================

/**
 * Verify that Chart of Accounts imports are atomic.
 *
 * Uses:
 *
 * - Real Spring Boot application context.
 * - AccountImportExecutionService.
 * - Real PostgreSQL database.
 * - Existing MyBatis account mapper.
 *
 * IMPORTANT:
 *
 * This test MUST NOT use @Transactional on the test
 * class or test method.
 *
 * Otherwise, the test framework's automatic rollback
 * could hide a missing service-level rollback.
 *
 * A temporary database trigger deliberately rejects
 * the second account during insertion.
 *
 * The test verifies that the first account is also
 * rolled back by AccountImportExecutionService.
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountImportRollbackIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AccountImportExecutionService importService;

    @Autowired
    private AccountImportFileParser fileParser;

    @Autowired
    private AccountImportValidationService validationService;


    // ============================================================
    // TEST 1 — ROLLBACK ALL ACCOUNTS AFTER DATABASE FAILURE
    // ============================================================

    @Test
    void rollsBackEntireImportWhenSecondInsertFails()
            throws Exception {

        // ====================================================
        // STEP 1 — VERIFY TEST DATABASE IS ACTIVE
        // ====================================================

        String databaseName = jdbcTemplate.queryForObject(
            "SELECT current_database()",
            String.class
        );

        assertEquals(
            "ledgerbase_test",
            databaseName,
            "Rollback test must use the isolated test database"
        );


        // ====================================================
        // STEP 2 — CREATE AN ISOLATED TEST USER
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
            "import-rollback-" + UUID.randomUUID()
                + "@ledgerbase.local",
            "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
            "Import Rollback Test User"
        );


        // ====================================================
        // STEP 3 — CREATE AN ISOLATED TEST BUSINESS
        // ====================================================

        Long businessId;

        try {

            businessId = jdbcTemplate.queryForObject(
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
                "Import Rollback Test Business",
                "USD"
            );

        } catch (RuntimeException exception) {

            jdbcTemplate.update(
                "DELETE FROM users WHERE id = ?",
                userId
            );

            throw exception;
        }


        // ====================================================
        // STEP 4 — EXECUTE TEST AND CLEAN UP
        // ====================================================

        try {

            // =================================================
            // PREPARE TWO VALID ACCOUNT ROWS
            // =================================================

            /*
             * Account 9960 is inserted first.
             *
             * Account 9961 depends on 9960.
             *
             * Both pass ordinary accounting validation.
             */
            String csv = """
                "Account Code","Account Name","Account Type","Reporting Group","Parent Account Code","Active","Postable"
                "9960","Rollback Test Parent","ASSET","CURRENT_ASSET","","TRUE","FALSE"
                "9961","Rollback Test Child","ASSET","CURRENT_ASSET","9960","TRUE","TRUE"
                """;

            byte[] csvBytes = csv.getBytes(
                StandardCharsets.UTF_8
            );


            // =================================================
            // VERIFY THE CSV IS VALID BEFORE EXECUTION
            // =================================================

            AccountImportPreviewResponse preview =
                validationService.validate(
                    businessId,
                    fileParser.parse(
                        "rollback-test.csv",
                        csvBytes
                    )
                );

            assertTrue(
                preview.readyToImport(),
                "CSV must pass validation before rollback test"
            );

            assertEquals(2, preview.validRows());


            // =================================================
            // CREATE TEST-ONLY POSTGRESQL FAILURE FUNCTION
            // =================================================

            /*
             * This function deliberately raises an SQL
             * exception when the trigger invokes it.
             *
             * It exists only during this integration test.
             */
            jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION
                    ledgerbase_import_test_reject_second_row()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION
                        'Deliberate account import rollback failure';
                END;
                $$;
                """
            );


            // =================================================
            // CREATE FAILURE TRIGGER FOR SECOND ACCOUNT ONLY
            // =================================================

            /*
             * Restrict the trigger by both:
             *
             * - Account code 9961.
             * - Our isolated test business ID.
             *
             * The trigger does not reject account 9960.
             *
             * This guarantees the first insert succeeds
             * before the database rejects the second.
             */
            jdbcTemplate.execute(
                """
                CREATE TRIGGER
                    ledgerbase_import_test_failure_trigger

                BEFORE INSERT ON accounts

                FOR EACH ROW

                WHEN (
                    NEW.code = '9961'
                    AND NEW.business_id = %d
                )

                EXECUTE FUNCTION
                    ledgerbase_import_test_reject_second_row()
                """.formatted(businessId)
            );


            // =================================================
            // EXECUTE IMPORT AND EXPECT DATABASE FAILURE
            // =================================================

            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> importService.importAccounts(
                    businessId,
                    "rollback-test.csv",
                    csvBytes
                )
            );


            // =================================================
            // VERIFY THE FAILURE CAME FROM POSTGRESQL
            // =================================================

            /*
             * Walk through wrapper exceptions to find
             * the underlying PostgreSQL exception.
             *
             * This prevents a validation failure from
             * accidentally making the rollback test pass.
             */
            Throwable rootCause = exception;

            while (rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
            }

            assertTrue(
                rootCause.getMessage().contains(
                    "Deliberate account import rollback failure"
                ),
                "Failure must originate from our test trigger"
            );


            // =================================================
            // VERIFY FIRST INSERT WAS ROLLED BACK
            // =================================================

            Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM accounts
                WHERE business_id = ?
                  AND code IN ('9960', '9961')
                """,
                Integer.class,
                businessId
            );

            assertEquals(
                0,
                count,
                "Failed import must leave zero accounts"
            );

        } finally {

            // =================================================
            // REMOVE TEST-ONLY DATABASE TRIGGER
            // =================================================

            /*
             * Cleanup happens even if an assertion fails.
             *
             * Never leave a testing trigger installed.
             */
            try {

                jdbcTemplate.execute(
                    """
                    DROP TRIGGER IF EXISTS
                        ledgerbase_import_test_failure_trigger
                    ON accounts
                    """
                );

            } finally {

                try {

                    jdbcTemplate.execute(
                        """
                        DROP FUNCTION IF EXISTS
                            ledgerbase_import_test_reject_second_row()
                        """
                    );

                } finally {

                    // =================================================
                    // REMOVE ISOLATED BUSINESS AND USER
                    // =================================================

                    try {

                        jdbcTemplate.update(
                            "DELETE FROM businesses WHERE id = ?",
                            businessId
                        );

                    } finally {

                        jdbcTemplate.update(
                            "DELETE FROM users WHERE id = ?",
                            userId
                        );
                    }
                }
            }
        }
    }
}
