package com.ledgerbase.backend.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for LedgerBase Account APIs.
 *
 * These tests use:
 * - The real Spring Boot application context
 * - The real AccountController and AccountService
 * - The real MyBatis SQL mapper
 * - The dedicated PostgreSQL database "ledgerbase_test"
 *
 * Normal tests automatically roll back their database changes.
 * The duplicate-code test uses independent transactions and
 * explicitly cleans up its test records afterward.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AccountIntegrationTest {

    // Spring provides the web application context for MockMvc.
    @Autowired
    private WebApplicationContext context;

    // Runs SQL directly to set up and verify test records.
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // PostgreSQL generates a fresh business ID for each test.
    private Long testBusinessId;

    // The ID of the test user who owns the test business.
    private Long testUserId;

    /**
     * Runs before each test.
     *
     * Creates a separate user and business so tests do not
     * depend on manually inserted development data.
     */
    @BeforeEach
    void setUpTestData() {

        // UUID prevents duplicate email addresses.
        String email = "test-"
            + java.util.UUID.randomUUID()
            + "@ledgerbase.local";

        // This password hash is only placeholder test data.
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
            "Integration Test User"
        );

        // Create a business owned by the test user.
        testBusinessId = jdbcTemplate.queryForObject(
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
            "Integration Test Business",
            "USD"
        );
    }

    /**
     * Builds the Accounts API URL using the generated
     * business ID rather than assuming the ID is 1.
     */
    private String accountsUrl() {
        return "/api/v1/businesses/"
            + testBusinessId
            + "/accounts";
    }

    /**
     * Test 1:
     * Reject invalid accounting classifications.
     *
     * Example: NOT_VALID is not one of the five
     * supported account types.
     */
    @Test
    void rejectsInvalidAccountType() throws Exception {

        MockMvc mvc = MockMvcBuilders
            .webAppContextSetup(context)
            .build();

        mvc.perform(post(accountsUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code": "9000",
                      "name": "Invalid Account",
                      "accountType": "NOT_VALID"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(
                jsonPath("$.error").value("Invalid account type")
            );
    }

    /**
     * Test 2:
     * List accounts for the test business.
     *
     * This exercises a real MyBatis SELECT query.
     */
    @Test
    void listsAccounts() throws Exception {

        MockMvc mvc = MockMvcBuilders
            .webAppContextSetup(context)
            .build();

        mvc.perform(get(accountsUrl()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    /**
     * Test 3:
     * Create an account through the API and independently
     * verify the result in PostgreSQL.
     *
     * The class-level @Transactional annotation ensures
     * this test's records are rolled back afterward.
     */
    @Test
    void createsAccountSuccessfully() throws Exception {

        MockMvc mvc = MockMvcBuilders
            .webAppContextSetup(context)
            .build();

        String accountCode = "TEST-1000";

        mvc.perform(post(accountsUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code": "TEST-1000",
                      "name": "Test Cash Account",
                      "accountType": "ASSET"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(accountCode))
            .andExpect(
                jsonPath("$.name").value("Test Cash Account")
            )
            .andExpect(
                jsonPath("$.accountType").value("ASSET")
            )
            .andExpect(
                jsonPath("$.businessId").value(testBusinessId)
            );

        // Verify the row exists using independent SQL.
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM accounts
            WHERE business_id = ?
              AND code = ?
            """,
            Integer.class,
            testBusinessId,
            accountCode
        );

        assertEquals(1, count);
    }

    /**
     * Test 4:
     * Reject duplicate account codes with HTTP 409.
     *
     * This test overrides the class-level @Transactional
     * annotation so it does not run inside a shared
     * test-managed transaction.
     *
     * The first POST commits normally. The second POST
     * fails and its own service transaction rolls back.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectsDuplicateAccountCode() throws Exception {

        MockMvc mvc = MockMvcBuilders
            .webAppContextSetup(context)
            .build();

        String requestBody = """
            {
              "code": "TEST-DUP",
              "name": "Duplicate Test Account",
              "accountType": "ASSET"
            }
            """;

        // First request should successfully insert the account.
        mvc.perform(post(accountsUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isCreated());

        // Second request should violate the business-scoped
        // UNIQUE (business_id, code) database constraint.
        mvc.perform(post(accountsUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").exists());

        // Confirm that the duplicate request did not
        // create a second account.
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM accounts
            WHERE business_id = ?
              AND code = ?
            """,
            Integer.class,
            testBusinessId,
            "TEST-DUP"
        );

        assertEquals(1, count);
    }

    /**
     * Clean up data created by the non-transactional test.
     *
     * We delete in reverse foreign-key dependency order:
     *
     * accounts -> businesses -> users
     *
     * Tests using the normal class-level transaction have
     * already rolled back their inserted data.
     */
    @AfterEach
    void cleanUpTestData() {

        // For normal transactional tests, their uncommitted
        // data is handled by Spring's automatic rollback.
        // No manual deletion should be performed here.
        if (!isNonTransactionalDuplicateTest()) {
            return;
        }

        // Guard against attempting cleanup without IDs.
        if (testBusinessId == null || testUserId == null) {
            return;
        }

        // Remove only records belonging to this test business.
        jdbcTemplate.update(
            "DELETE FROM accounts WHERE business_id = ?",
            testBusinessId
        );

        jdbcTemplate.update(
            "DELETE FROM businesses WHERE id = ?",
            testBusinessId
        );

        jdbcTemplate.update(
            "DELETE FROM users WHERE id = ?",
            testUserId
        );
    }

    /**
     * Determine whether the current test needs explicit
     * cleanup by checking Spring's test transaction state.
     *
     * A transaction-managed test returns true from
     * TestTransaction.isActive().
     *
     * The duplicate-code test does not have an active
     * test-managed transaction.
     */
    private boolean isNonTransactionalDuplicateTest() {
        return !org.springframework.test.context.transaction
            .TestTransaction.isActive();
    }
}
