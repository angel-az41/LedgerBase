package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.dto.CreateLedgerEntryRequest;
import com.ledgerbase.backend.dto.CreateTransactionRequest;
import com.ledgerbase.backend.mapper.LedgerEntryMapper;
import com.ledgerbase.backend.model.LedgerEntry;
import com.ledgerbase.backend.model.LedgerTransaction;
import com.ledgerbase.backend.service.TransactionService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the LedgerBase transaction engine.
 *
 * These tests exercise:
 * - Spring MVC controllers
 * - TransactionService
 * - MyBatis mappers
 * - PostgreSQL
 * - Audit logging
 * - Posted-transaction immutability
 *
 * The "test" profile connects to ledgerbase_test.
 *
 * Each test creates its own temporary records.
 *
 * @Transactional ensures that Spring rolls back
 * the test data after each test finishes.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransactionIntegrationTest {

    /**
     * Service responsible for financial transaction
     * creation, validation, retrieval, and posting.
     */
    @Autowired
    private TransactionService transactionService;

    /**
     * MyBatis mapper used to retrieve ledger entries.
     */
    @Autowired
    private LedgerEntryMapper entryMapper;

    /**
     * JdbcTemplate allows us to execute SQL directly
     * for test setup and independent verification.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The real Spring web context used by MockMvc.
     */
    @Autowired
    private WebApplicationContext context;

    // Database-generated IDs for each test.
    private Long testUserId;
    private Long testBusinessId;
    private Long cashAccountId;
    private Long revenueAccountId;

    // ============================================================
    // TEST SETUP
    // ============================================================

    /**
     * Create a test user, business, and two accounts
     * before each test method.
     *
     * These records are inserted into ledgerbase_test
     * and rolled back automatically afterward.
     */
    @BeforeEach
    void setUpTestData() {

        // Generate a unique email to avoid collisions.
        String email = "transaction-test-"
            + UUID.randomUUID()
            + "@ledgerbase.local";

        // Create a temporary user.
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
            "Transaction Test User"
        );

        // Create a business belonging to the test user.
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
            "Transaction Test Business",
            "USD"
        );

        // Create the Cash asset account.
        cashAccountId = jdbcTemplate.queryForObject(
            """
            INSERT INTO accounts (
                business_id,
                code,
                name,
                account_type
            )
            VALUES (?, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            testBusinessId,
            "1000",
            "Cash",
            "ASSET"
        );

        // Create the Consulting Revenue account.
        revenueAccountId = jdbcTemplate.queryForObject(
            """
            INSERT INTO accounts (
                business_id,
                code,
                name,
                account_type
            )
            VALUES (?, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            testBusinessId,
            "4000",
            "Consulting Revenue",
            "REVENUE"
        );
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================

    /**
     * Create a transaction request using the test accounts.
     *
     * The first entry debits Cash.
     * The second entry credits Consulting Revenue.
     *
     * Using BigDecimal constructed from strings preserves
     * exact financial amounts.
     */
    private CreateTransactionRequest createRequest(
            String debitAmount,
            String creditAmount) {

        return new CreateTransactionRequest(
            testUserId,
            "Consulting payment",
            LocalDate.of(2026, 10, 11),
            List.of(
                new CreateLedgerEntryRequest(
                    cashAccountId,
                    new BigDecimal(debitAmount),
                    new BigDecimal("0.00")
                ),
                new CreateLedgerEntryRequest(
                    revenueAccountId,
                    new BigDecimal("0.00"),
                    new BigDecimal(creditAmount)
                )
            )
        );
    }

    /**
     * Create MockMvc using the real application context.
     *
     * MockMvc simulates HTTP requests without starting
     * a separate web server.
     */
    private MockMvc mvc() {
        return MockMvcBuilders
            .webAppContextSetup(context)
            .build();
    }

    /**
     * Build the transactions API URL using the
     * generated business ID.
     */
    private String transactionsUrl() {
        return "/api/v1/businesses/"
            + testBusinessId
            + "/transactions";
    }

    /**
     * Send a transaction posting request.
     *
     * The JSON contains the ID of the user performing
     * the posting operation.
     *
     * This currently maps to PostTransactionRequest.
     */
    private ResultActions postAsActor(
            MockMvc mvc,
            String url,
            Long actorId) throws Exception {

        String requestBody = """
            {
              "actorId": %d
            }
            """.formatted(actorId);

        return mvc.perform(
            post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody)
        );
    }

    /**
     * Verify that PostgreSQL rejects a SQL operation
     * against a protected POSTED financial record.
     *
     * This helper is used to test the V4 triggers.
     *
     * Steps:
     * 1. Create a database savepoint.
     * 2. Execute a SQL operation expected to fail.
     * 3. Verify PostgreSQL SQLSTATE 23514.
     * 4. Roll back to the savepoint.
     *
     * PostgreSQL normally aborts a transaction after
     * a SQL error. The savepoint lets the surrounding
     * JUnit test transaction continue.
     *
     * @param sql SQL statement expected to be rejected.
     * @param parameters Values for SQL placeholders.
     */
    private void assertPostedRecordChangeRejected(
            String sql,
            Object... parameters) {

        jdbcTemplate.execute(
            (ConnectionCallback<Void>) connection -> {

                // Create a recovery point.
                Savepoint savepoint = connection.setSavepoint();

                try (PreparedStatement statement =
                        connection.prepareStatement(sql)) {

                    /*
                     * Safely bind SQL parameters.
                     *
                     * JDBC parameters are indexed from 1,
                     * while Java arrays are indexed from 0.
                     */
                    for (int i = 0; i < parameters.length; i++) {
                        statement.setObject(
                            i + 1,
                            parameters[i]
                        );
                    }

                    /*
                     * The database trigger must reject
                     * this SQL operation.
                     */
                    SQLException exception = assertThrows(
                        SQLException.class,
                        () -> statement.executeUpdate()
                    );

                    /*
                     * SQLSTATE 23514 indicates the
                     * constraint violation raised
                     * by our V4 triggers.
                     */
                    assertEquals(
                        "23514",
                        exception.getSQLState()
                    );

                } finally {

                    /*
                     * Roll back only the failed operation,
                     * preserving the surrounding test data.
                     */
                    connection.rollback(savepoint);

                    // Release the savepoint after recovery.
                    connection.releaseSavepoint(savepoint);
                }

                return null;
            }
        );
    }

    // ============================================================
    // TEST 1 — CREATE A BALANCED DRAFT
    // ============================================================

    /**
     * Verify that a balanced transaction creates:
     *
     * - One DRAFT transaction
     * - Two ledger entries
     * - One TRANSACTION_CREATED audit event
     */
    @Test
    void createsBalancedDraftTransaction() {

        LedgerTransaction saved =
            transactionService.createDraftTransaction(
                testBusinessId,
                createRequest("500.00", "500.00")
            );

        // Verify the transaction header.
        assertNotNull(saved.getId());
        assertEquals("DRAFT", saved.getStatus());
        assertEquals(testBusinessId, saved.getBusinessId());
        assertEquals(testUserId, saved.getCreatedBy());

        Integer transactionCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM transactions
            WHERE id = ?
              AND business_id = ?
              AND status = 'DRAFT'
            """,
            Integer.class,
            saved.getId(),
            testBusinessId
        );

        assertEquals(1, transactionCount);

        // Retrieve the saved ledger entries.
        List<LedgerEntry> entries =
            entryMapper.findByTransactionId(
                testBusinessId,
                saved.getId()
            );

        assertEquals(2, entries.size());

        LedgerEntry debitEntry = entries.get(0);
        LedgerEntry creditEntry = entries.get(1);

        // Verify the Cash debit.
        assertEquals(
            cashAccountId,
            debitEntry.getAccountId()
        );

        assertEquals(
            0,
            new BigDecimal("500.00").compareTo(
                debitEntry.getDebit()
            )
        );

        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(
                debitEntry.getCredit()
            )
        );

        // Verify the Revenue credit.
        assertEquals(
            revenueAccountId,
            creditEntry.getAccountId()
        );

        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(
                creditEntry.getDebit()
            )
        );

        assertEquals(
            0,
            new BigDecimal("500.00").compareTo(
                creditEntry.getCredit()
            )
        );

        // Both entries belong to the same transaction.
        assertEquals(
            saved.getId(),
            debitEntry.getTransactionId()
        );

        assertEquals(
            saved.getId(),
            creditEntry.getTransactionId()
        );

        /*
         * Verify the creation audit event.
         *
         * PostgreSQL's ->> operator extracts a JSONB
         * property as text.
         */
        Integer auditCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM audit_events
            WHERE business_id = ?
              AND actor_id = ?
              AND entity_type = 'TRANSACTION'
              AND entity_id = ?
              AND action = 'TRANSACTION_CREATED'
              AND details ->> 'status' = 'DRAFT'
            """,
            Integer.class,
            testBusinessId,
            testUserId,
            saved.getId()
        );

        assertEquals(1, auditCount);
    }

    // ============================================================
    // TEST 2 — REJECT UNBALANCED TRANSACTIONS
    // ============================================================

    /**
     * An unbalanced transaction must be rejected
     * before saving financial records.
     *
     * Debit:  500.00
     * Credit: 400.00
     */
    @Test
    void rejectsUnbalancedTransactionWithoutSaving() {

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> transactionService.createDraftTransaction(
                testBusinessId,
                createRequest("500.00", "400.00")
            )
        );

        assertEquals(
            "Total debits must equal total credits",
            exception.getMessage()
        );

        // No transaction should have been saved.
        Integer transactionCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM transactions
            WHERE business_id = ?
            """,
            Integer.class,
            testBusinessId
        );

        // No ledger entries should have been saved.
        Integer entryCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM ledger_entries
            WHERE business_id = ?
            """,
            Integer.class,
            testBusinessId
        );

        // No audit events should have been saved.
        Integer auditCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM audit_events
            WHERE business_id = ?
            """,
            Integer.class,
            testBusinessId
        );

        assertEquals(0, transactionCount);
        assertEquals(0, entryCount);
        assertEquals(0, auditCount);
    }

    // ============================================================
    // TEST 3 — CREATE THROUGH HTTP
    // ============================================================

    /**
     * Verify that the REST API creates a valid
     * DRAFT transaction through HTTP.
     */
    @Test
    void createsDraftTransactionThroughHttp() throws Exception {

        String requestBody = """
            {
              "createdBy": %d,
              "description": "Consulting payment",
              "transactionDate": "2026-10-11",
              "entries": [
                {
                  "accountId": %d,
                  "debit": 500.00,
                  "credit": 0.00
                },
                {
                  "accountId": %d,
                  "debit": 0.00,
                  "credit": 500.00
                }
              ]
            }
            """.formatted(
                testUserId,
                cashAccountId,
                revenueAccountId
            );

        // Send the HTTP request.
        mvc().perform(
                post(transactionsUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(
                jsonPath("$.businessId").value(testBusinessId)
            )
            .andExpect(
                jsonPath("$.description")
                    .value("Consulting payment")
            );

        // Verify one saved transaction.
        Integer transactionCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM transactions
            WHERE business_id = ?
              AND status = 'DRAFT'
            """,
            Integer.class,
            testBusinessId
        );

        // Verify two ledger entries.
        Integer entryCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM ledger_entries
            WHERE business_id = ?
            """,
            Integer.class,
            testBusinessId
        );

        assertEquals(1, transactionCount);
        assertEquals(2, entryCount);

        // Independently calculate debit and credit totals.
        BigDecimal totalDebit = jdbcTemplate.queryForObject(
            """
            SELECT SUM(debit)
            FROM ledger_entries
            WHERE business_id = ?
            """,
            BigDecimal.class,
            testBusinessId
        );

        BigDecimal totalCredit = jdbcTemplate.queryForObject(
            """
            SELECT SUM(credit)
            FROM ledger_entries
            WHERE business_id = ?
            """,
            BigDecimal.class,
            testBusinessId
        );

        assertNotNull(totalDebit);
        assertNotNull(totalCredit);

        assertEquals(
            0,
            new BigDecimal("500.00").compareTo(totalDebit)
        );

        assertEquals(
            0,
            new BigDecimal("500.00").compareTo(totalCredit)
        );
    }

    // ============================================================
    // TEST 4 — LIST TRANSACTIONS THROUGH HTTP
    // ============================================================

    /**
     * Verify the transaction listing REST endpoint.
     */
    @Test
    void listsTransactionsThroughHttp() throws Exception {

        transactionService.createDraftTransaction(
            testBusinessId,
            createRequest("500.00", "500.00")
        );

        mvc().perform(get(transactionsUrl()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(
                jsonPath("$[0].status").value("DRAFT")
            )
            .andExpect(
                jsonPath("$[0].description")
                    .value("Consulting payment")
            )
            .andExpect(
                jsonPath("$[0].businessId")
                    .value(testBusinessId)
            );
    }

    // ============================================================
    // TEST 5 — RETRIEVE TRANSACTION DETAILS
    // ============================================================

    /**
     * Verify that the transaction detail endpoint
     * returns both the header and ledger entries.
     */
    @Test
    void retrievesTransactionDetailsThroughHttp() throws Exception {

        LedgerTransaction saved =
            transactionService.createDraftTransaction(
                testBusinessId,
                createRequest("500.00", "500.00")
            );

        String url = transactionsUrl()
            + "/" + saved.getId();

        mvc().perform(get(url))
            .andExpect(status().isOk())

            // Verify the transaction header.
            .andExpect(
                jsonPath("$.transaction.id")
                    .value(saved.getId())
            )
            .andExpect(
                jsonPath("$.transaction.status")
                    .value("DRAFT")
            )
            .andExpect(
                jsonPath("$.transaction.description")
                    .value("Consulting payment")
            )

            // Verify two returned entries.
            .andExpect(
                jsonPath("$.entries.length()").value(2)
            )

            // First entry: Cash debit.
            .andExpect(
                jsonPath("$.entries[0].accountId")
                    .value(cashAccountId)
            )
            .andExpect(
                jsonPath("$.entries[0].debit").value(500.0)
            )
            .andExpect(
                jsonPath("$.entries[0].credit").value(0.0)
            )

            // Second entry: Revenue credit.
            .andExpect(
                jsonPath("$.entries[1].accountId")
                    .value(revenueAccountId)
            )
            .andExpect(
                jsonPath("$.entries[1].debit").value(0.0)
            )
            .andExpect(
                jsonPath("$.entries[1].credit").value(500.0)
            );
    }

    // ============================================================
    // TEST 6 — BUSINESS ISOLATION
    // ============================================================

    /**
     * A transaction belonging to Business A must not
     * be returned through Business B's API URL.
     *
     * This tests business-scoped database queries,
     * not authentication or user permissions.
     */
    @Test
    void rejectsTransactionFromAnotherBusiness() throws Exception {

        LedgerTransaction saved =
            transactionService.createDraftTransaction(
                testBusinessId,
                createRequest("500.00", "500.00")
            );

        // Create a second business.
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
            testUserId,
            "Another Test Business",
            "USD"
        );

        String url = "/api/v1/businesses/"
            + otherBusinessId
            + "/transactions/"
            + saved.getId();

        mvc().perform(get(url))
            .andExpect(status().isNotFound())
            .andExpect(
                jsonPath("$.error").value(
                    "Transaction not found: "
                    + saved.getId()
                )
            );
    }

    // ============================================================
    // TEST 7 — POST A VALID TRANSACTION
    // ============================================================

    /**
     * Verify that:
     * 1. A DRAFT transaction becomes POSTED.
     * 2. Ledger entries remain unchanged.
     * 3. The posting audit records the actual actor.
     * 4. Creation and posting generate separate events.
     *
     * A different user performs the posting.
     */
    @Test
    void postsBalancedDraftTransaction() throws Exception {

        // User 1 creates the draft.
        LedgerTransaction draft =
            transactionService.createDraftTransaction(
                testBusinessId,
                createRequest("500.00", "500.00")
            );

        assertEquals("DRAFT", draft.getStatus());

        // Create User 2 to post the transaction.
        Long postingActorId = jdbcTemplate.queryForObject(
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
            "posting-test-" + UUID.randomUUID()
                + "@ledgerbase.local",
            "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
            "Posting Test User"
        );

        String url = transactionsUrl()
            + "/" + draft.getId()
            + "/post";

        // User 2 posts the draft through HTTP.
        postAsActor(mvc(), url, postingActorId)
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.id").value(draft.getId())
            )
            .andExpect(
                jsonPath("$.status").value("POSTED")
            );

        // Check the actual database status.
        String databaseStatus = jdbcTemplate.queryForObject(
            """
            SELECT status
            FROM transactions
            WHERE id = ?
              AND business_id = ?
            """,
            String.class,
            draft.getId(),
            testBusinessId
        );

        assertEquals("POSTED", databaseStatus);

        // Posting must not change the number of entries.
        Integer entryCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM ledger_entries
            WHERE transaction_id = ?
              AND business_id = ?
            """,
            Integer.class,
            draft.getId(),
            testBusinessId
        );

        assertEquals(2, entryCount);

        // Verify User 2 is recorded as the posting actor.
        Integer postingAuditCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM audit_events
            WHERE business_id = ?
              AND actor_id = ?
              AND action = 'TRANSACTION_POSTED'
              AND entity_type = 'TRANSACTION'
              AND entity_id = ?
              AND details ->> 'previousStatus' = 'DRAFT'
              AND details ->> 'newStatus' = 'POSTED'
            """,
            Integer.class,
            testBusinessId,
            postingActorId,
            draft.getId()
        );

        assertEquals(1, postingAuditCount);

        // Verify the poster differs from the creator.
        assertNotEquals(testUserId, postingActorId);

        // Exactly two audit events should exist.
        Integer historyCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM audit_events
            WHERE business_id = ?
              AND entity_type = 'TRANSACTION'
              AND entity_id = ?
            """,
            Integer.class,
            testBusinessId,
            draft.getId()
        );

        assertEquals(2, historyCount);
    }

    // ============================================================
    // TEST 8 — PREVENT DUPLICATE POSTING
    // ============================================================

    /**
     * Posting an already POSTED transaction must fail
     * with HTTP 409 Conflict.
     *
     * The second request must not create another
     * posting audit event.
     */
    @Test
    void rejectsPostingAlreadyPostedTransaction() throws Exception {

        LedgerTransaction draft =
            transactionService.createDraftTransaction(
                testBusinessId,
                createRequest("500.00", "500.00")
            );

        String url = transactionsUrl()
            + "/" + draft.getId()
            + "/post";

        MockMvc mockMvc = mvc();

        // First posting succeeds.
        postAsActor(mockMvc, url, testUserId)
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.status").value("POSTED")
            );

        // Second posting is rejected.
        postAsActor(mockMvc, url, testUserId)
            .andExpect(status().isConflict())
            .andExpect(
                jsonPath("$.error").value(
                    "Transaction is not in DRAFT status: "
                    + draft.getId()
                )
            );

        // Only one posting audit event should exist.
        Integer postingAuditCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM audit_events
            WHERE business_id = ?
              AND entity_id = ?
              AND entity_type = 'TRANSACTION'
              AND action = 'TRANSACTION_POSTED'
            """,
            Integer.class,
            testBusinessId,
            draft.getId()
        );

        assertEquals(1, postingAuditCount);
    }

    // ============================================================
    // TEST 9 — REJECT POSTING A MISSING TRANSACTION
    // ============================================================

    /**
     * Posting a nonexistent transaction should return 404.
     *
     * Long.MAX_VALUE is a valid positive Long ID,
     * but should not exist in the test database.
     */
    @Test
    void rejectsPostingNonexistentTransaction() throws Exception {

        long missingTransactionId = Long.MAX_VALUE;

        String url = transactionsUrl()
            + "/" + missingTransactionId
            + "/post";

        postAsActor(mvc(), url, testUserId)
            .andExpect(status().isNotFound())
            .andExpect(
                jsonPath("$.error").value(
                    "Transaction not found: "
                    + missingTransactionId
                )
            );
    }

    // ============================================================
    // TEST 10 — REJECT POSTING WITH AN INACTIVE ACCOUNT
    // ============================================================

    /**
     * A transaction cannot be posted if one of its
     * referenced accounts becomes inactive.
     *
     * The transaction must remain DRAFT,
     * and no posting audit should be created.
     */
    @Test
    void rejectsPostingWithInactiveAccount() throws Exception {

        LedgerTransaction draft =
            transactionService.createDraftTransaction(
                testBusinessId,
                createRequest("500.00", "500.00")
            );

        // Deactivate the Cash account.
        jdbcTemplate.update(
            """
            UPDATE accounts
            SET is_active = FALSE
            WHERE id = ?
              AND business_id = ?
            """,
            cashAccountId,
            testBusinessId
        );

        String url = transactionsUrl()
            + "/" + draft.getId()
            + "/post";

        // Posting must fail.
        postAsActor(mvc(), url, testUserId)
            .andExpect(status().isConflict())
            .andExpect(
                jsonPath("$.error").value(
                    "Transaction references an inactive account: "
                    + cashAccountId
                )
            );

        // Transaction must remain DRAFT.
        String status = jdbcTemplate.queryForObject(
            """
            SELECT status
            FROM transactions
            WHERE id = ?
              AND business_id = ?
            """,
            String.class,
            draft.getId(),
            testBusinessId
        );

        assertEquals("DRAFT", status);

        // No posting audit event should exist.
        Integer auditCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM audit_events
            WHERE business_id = ?
              AND entity_id = ?
              AND entity_type = 'TRANSACTION'
              AND action = 'TRANSACTION_POSTED'
            """,
            Integer.class,
            testBusinessId,
            draft.getId()
        );

        assertEquals(0, auditCount);
    }

    // ============================================================
    // TEST 11 — PREVENT EDITING POSTED LEDGER ENTRIES
    // ============================================================

    /**
     * Verify that PostgreSQL prevents modifying
     * existing ledger entries after posting.
     *
     * Scenario:
     * 1. Create a balanced $500 transaction.
     * 2. Post the transaction.
     * 3. Attempt to change the Cash debit to $800.
     * 4. Verify PostgreSQL rejects the UPDATE.
     * 5. Confirm the original $500 remains unchanged.
     *
     * This tests the V4 protection trigger.
     */
    @Test
    void preventsEditingPostedLedgerEntry() {

        // Create a balanced DRAFT.
        LedgerTransaction draft =
            transactionService.createDraftTransaction(
                testBusinessId,
                createRequest("500.00", "500.00")
            );

        // Post the transaction.
        LedgerTransaction posted =
            transactionService.postTransaction(
                testBusinessId,
                draft.getId(),
                testUserId
            );

        assertEquals("POSTED", posted.getStatus());

        // Find the existing Cash ledger entry.
        Long cashEntryId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM ledger_entries
            WHERE business_id = ?
              AND transaction_id = ?
              AND account_id = ?
            """,
            Long.class,
            testBusinessId,
            posted.getId(),
            cashAccountId
        );

        assertNotNull(cashEntryId);

        /*
         * Attempt to overwrite the posted debit.
         *
         * The V4 trigger must reject this change.
         */
        assertPostedRecordChangeRejected(
            """
            UPDATE ledger_entries
            SET debit = ?
            WHERE id = ?
            """,
            new BigDecimal("800.00"),
            cashEntryId
        );

        // Verify that the original $500 remains.
        BigDecimal savedDebit = jdbcTemplate.queryForObject(
            """
            SELECT debit
            FROM ledger_entries
            WHERE id = ?
            """,
            BigDecimal.class,
            cashEntryId
        );

        assertNotNull(savedDebit);

        assertEquals(
            0,
            new BigDecimal("500.00").compareTo(savedDebit)
        );

        // Parent transaction must still be POSTED.
        String status = jdbcTemplate.queryForObject(
            """
            SELECT status
            FROM transactions
            WHERE id = ?
              AND business_id = ?
            """,
            String.class,
            posted.getId(),
            testBusinessId
        );

        assertEquals("POSTED", status);
    }

    // ============================================================
    // TEST 12 — PREVENT ADDING ENTRIES AFTER POSTING
    // ============================================================

    /**
     * Verify that PostgreSQL prevents inserting
     * additional entries into POSTED transactions.
     *
     * Adding a new debit after posting could change
     * the accounting balance, so it must be rejected.
     */
    @Test
    void preventsAddingEntryToPostedTransaction() {

        // Create a balanced DRAFT transaction.
        LedgerTransaction draft =
            transactionService.createDraftTransaction(
                testBusinessId,
                createRequest("500.00", "500.00")
            );

        // Finalize the transaction.
        LedgerTransaction posted =
            transactionService.postTransaction(
                testBusinessId,
                draft.getId(),
                testUserId
            );

        assertEquals("POSTED", posted.getStatus());

        /*
         * Attempt to add another debit entry.
         *
         * This INSERT must be rejected by PostgreSQL.
         */
        assertPostedRecordChangeRejected(
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
            testBusinessId,
            posted.getId(),
            cashAccountId,
            new BigDecimal("100.00"),
            new BigDecimal("0.00")
        );

        // Verify only the original two entries remain.
        Integer entryCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM ledger_entries
            WHERE business_id = ?
              AND transaction_id = ?
            """,
            Integer.class,
            testBusinessId,
            posted.getId()
        );

        assertEquals(2, entryCount);

        // The parent must remain POSTED.
        String status = jdbcTemplate.queryForObject(
            """
            SELECT status
            FROM transactions
            WHERE id = ?
              AND business_id = ?
            """,
            String.class,
            posted.getId(),
            testBusinessId
        );

        assertEquals("POSTED", status);
    }

    // ============================================================
    // TEST 13 — PREVENT DELETING ENTRIES AFTER POSTING
    // ============================================================

    /**
     * Verify that PostgreSQL prevents deleting
     * ledger entries belonging to POSTED transactions.
     *
     * Removing an entry would erase part of the
     * finalized accounting history.
     */
    @Test
    void preventsDeletingEntryFromPostedTransaction() {

        // Create a balanced transaction.
        LedgerTransaction draft =
            transactionService.createDraftTransaction(
                testBusinessId,
                createRequest("500.00", "500.00")
            );

        // Finalize the transaction.
        LedgerTransaction posted =
            transactionService.postTransaction(
                testBusinessId,
                draft.getId(),
                testUserId
            );

        assertEquals("POSTED", posted.getStatus());

        // Find an existing entry to attempt to delete.
        Long entryId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM ledger_entries
            WHERE business_id = ?
              AND transaction_id = ?
            ORDER BY id
            LIMIT 1
            """,
            Long.class,
            testBusinessId,
            posted.getId()
        );

        assertNotNull(entryId);

        /*
         * Attempt to delete the posted entry.
         *
         * PostgreSQL must reject this DELETE.
         */
        assertPostedRecordChangeRejected(
            """
            DELETE FROM ledger_entries
            WHERE id = ?
            """,
            entryId
        );

        // Verify that the original entry still exists.
        Integer remainingCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM ledger_entries
            WHERE id = ?
              AND business_id = ?
              AND transaction_id = ?
            """,
            Integer.class,
            entryId,
            testBusinessId,
            posted.getId()
        );

        assertEquals(1, remainingCount);

        // Verify that all original entries remain.
        Integer totalEntries = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM ledger_entries
            WHERE business_id = ?
              AND transaction_id = ?
            """,
            Integer.class,
            testBusinessId,
            posted.getId()
        );

        assertEquals(2, totalEntries);

        // The parent transaction must remain POSTED.
        String status = jdbcTemplate.queryForObject(
            """
            SELECT status
            FROM transactions
            WHERE id = ?
              AND business_id = ?
            """,
            String.class,
            posted.getId(),
            testBusinessId
        );

        assertEquals("POSTED", status);
    }

    // ============================================================
    // TEST 14 — PREVENT EDITING POSTED TRANSACTION HEADERS
    // ============================================================

    /**
     * Verify that a POSTED transaction header cannot
     * be modified after finalization.
     */
    @Test
    void preventsEditingPostedTransactionHeader() {

        // Create a balanced DRAFT transaction.
        LedgerTransaction draft =
            transactionService.createDraftTransaction(
                testBusinessId,
                createRequest("500.00", "500.00")
            );

        // Finalize the transaction.
        LedgerTransaction posted =
            transactionService.postTransaction(
                testBusinessId,
                draft.getId(),
                testUserId
            );

        assertEquals("POSTED", posted.getStatus());

        /*
        * Attempt 1:
        * Change the description of a POSTED transaction.
        *
        * PostgreSQL should reject this UPDATE.
        */
        assertPostedRecordChangeRejected(
            """
            UPDATE transactions
            SET description = ?
            WHERE id = ?
            AND business_id = ?
            """,
            "Modified consulting payment",
            posted.getId(),
            testBusinessId
        );

        /*
        * Attempt 2:
        * Change POSTED back to DRAFT.
        *
        * A finalized transaction must never return
        * to its editable draft state.
        */
        assertPostedRecordChangeRejected(
            """
            UPDATE transactions
            SET status = 'DRAFT'
            WHERE id = ?
            AND business_id = ?
            """,
            posted.getId(),
            testBusinessId
        );

        // Verify the original description is preserved.
        String savedDescription = jdbcTemplate.queryForObject(
            """
            SELECT description
            FROM transactions
            WHERE id = ?
            AND business_id = ?
            """,
            String.class,
            posted.getId(),
            testBusinessId
        );

        assertEquals(
            "Consulting payment",
            savedDescription
        );

        // Verify the transaction remains POSTED.
        String savedStatus = jdbcTemplate.queryForObject(
            """
            SELECT status
            FROM transactions
            WHERE id = ?
            AND business_id = ?
            """,
            String.class,
            posted.getId(),
            testBusinessId
        );

        assertEquals("POSTED", savedStatus);
    }

    // ============================================================
    // TEST 15 — PREVENT DELETING POSTED TRANSACTIONS
    // ============================================================

    /**
     * Verify that a POSTED transaction cannot be deleted.
     */
    @Test
    void preventsDeletingPostedTransaction() {

        // Create a valid DRAFT transaction.
        LedgerTransaction draft =
            transactionService.createDraftTransaction(
                testBusinessId,
                createRequest("500.00", "500.00")
            );

        // Post the transaction.
        LedgerTransaction posted =
            transactionService.postTransaction(
                testBusinessId,
                draft.getId(),
                testUserId
            );

        assertEquals("POSTED", posted.getStatus());

        /*
        * Attempt to delete the transaction header.
        *
        * The V4 trigger must reject this operation
        * with PostgreSQL SQLSTATE 23514.
        */
        assertPostedRecordChangeRejected(
            """
            DELETE FROM transactions
            WHERE id = ?
            AND business_id = ?
            """,
            posted.getId(),
            testBusinessId
        );

        // Verify that the original transaction still exists.
        Integer transactionCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM transactions
            WHERE id = ?
            AND business_id = ?
            AND status = 'POSTED'
            """,
            Integer.class,
            posted.getId(),
            testBusinessId
        );

        assertEquals(1, transactionCount);

        // Verify both ledger entries are preserved.
        Integer entryCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM ledger_entries
            WHERE transaction_id = ?
            AND business_id = ?
            """,
            Integer.class,
            posted.getId(),
            testBusinessId
        );

        assertEquals(2, entryCount);

        // Verify both creation and posting audit records remain.
        Integer auditCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM audit_events
            WHERE business_id = ?
            AND entity_type = 'TRANSACTION'
            AND entity_id = ?
            """,
            Integer.class,
            testBusinessId,
            posted.getId()
        );

        assertEquals(2, auditCount);
    }
}
