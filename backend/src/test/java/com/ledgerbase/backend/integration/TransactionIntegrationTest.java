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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
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
 * These tests exercise Spring MVC, TransactionService, MyBatis,
 * and the dedicated PostgreSQL database (ledgerbase_test).
 *
 * Each test uses a fresh user, business, and pair of accounts.
 * Spring rolls back each test's changes after it finishes.
 *
 * The posting tests now send actorId in the HTTP request body.
 * This is temporary until authentication supplies the actor ID.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransactionIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private LedgerEntryMapper entryMapper;

    // Used to create fixtures and independently verify database state.
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Provides the real Spring MVC application context to MockMvc.
    @Autowired
    private WebApplicationContext context;

    private Long testUserId;
    private Long testBusinessId;
    private Long cashAccountId;
    private Long revenueAccountId;

    /**
     * Create test fixtures inside the rollback-only test transaction.
     * Generated IDs avoid dependence on manually inserted database rows.
     */
    @BeforeEach
    void setUpTestData() {
        String email = "transaction-test-" + UUID.randomUUID()
            + "@ledgerbase.local";

        testUserId = jdbcTemplate.queryForObject(
            """
            INSERT INTO users (email, password_hash, full_name)
            VALUES (?, ?, ?)
            RETURNING id
            """,
            Long.class,
            email,
            "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
            "Transaction Test User"
        );

        testBusinessId = jdbcTemplate.queryForObject(
            """
            INSERT INTO businesses (owner_id, name, currency)
            VALUES (?, ?, ?)
            RETURNING id
            """,
            Long.class,
            testUserId,
            "Transaction Test Business",
            "USD"
        );

        cashAccountId = jdbcTemplate.queryForObject(
            """
            INSERT INTO accounts (business_id, code, name, account_type)
            VALUES (?, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            testBusinessId,
            "1000",
            "Cash",
            "ASSET"
        );

        revenueAccountId = jdbcTemplate.queryForObject(
            """
            INSERT INTO accounts (business_id, code, name, account_type)
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

    /**
     * Create a two-line journal entry using actual test account IDs.
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
     * Construct MockMvc without starting a separate web server.
     */
    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    /**
     * Build a URL using the test business ID,
     * never a hardcoded ID.
     */
    private String transactionsUrl() {
        return "/api/v1/businesses/" + testBusinessId + "/transactions";
    }

    /**
     * Send a POST request with the ID of the user performing the posting.
     *
     * The request JSON maps to PostTransactionRequest.java.
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
     * Test 1:
     *
     * A balanced draft creates:
     * - One transaction
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

        assertNotNull(saved.getId());
        assertEquals("DRAFT", saved.getStatus());
        assertEquals(testBusinessId, saved.getBusinessId());
        assertEquals(testUserId, saved.getCreatedBy());

        // Verify the transaction header exists.
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

        // Retrieve the saved ledger entries using MyBatis.
        List<LedgerEntry> entries =
            entryMapper.findByTransactionId(
                testBusinessId,
                saved.getId()
            );

        assertEquals(2, entries.size());

        LedgerEntry debitEntry = entries.get(0);
        LedgerEntry creditEntry = entries.get(1);

        // First entry: Debit Cash $500.
        assertEquals(cashAccountId, debitEntry.getAccountId());

        assertEquals(
            0,
            new BigDecimal("500.00").compareTo(debitEntry.getDebit())
        );

        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(debitEntry.getCredit())
        );

        assertEquals(
            saved.getId(),
            debitEntry.getTransactionId()
        );

        // Second entry: Credit Revenue $500.
        assertEquals(revenueAccountId, creditEntry.getAccountId());

        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(creditEntry.getDebit())
        );

        assertEquals(
            0,
            new BigDecimal("500.00").compareTo(creditEntry.getCredit())
        );

        assertEquals(
            saved.getId(),
            creditEntry.getTransactionId()
        );

        /*
         * Verify creation audit logging.
         *
         * JSONB's ->> operator extracts the status
         * property as text.
         */
        Integer creationAuditCount = jdbcTemplate.queryForObject(
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

        assertEquals(1, creationAuditCount);
    }

    /**
     * Test 2:
     *
     * Unbalanced amounts must be rejected
     * before any financial records are inserted.
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

        // Verify no transaction header was saved.
        Integer transactionCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM transactions
            WHERE business_id = ?
            """,
            Integer.class,
            testBusinessId
        );

        // Verify no ledger entries were saved.
        Integer entryCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM ledger_entries
            WHERE business_id = ?
            """,
            Integer.class,
            testBusinessId
        );

        // Verify no audit events were created.
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

    /**
     * Test 3:
     *
     * The REST API must create a balanced DRAFT transaction
     * and return HTTP 201 Created.
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

        // Send the HTTP request through Spring MVC.
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
                jsonPath("$.description").value("Consulting payment")
            );

        // Verify PostgreSQL saved the transaction.
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

        // Verify PostgreSQL saved two ledger entries.
        Integer entryCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM ledger_entries
            WHERE business_id = ?
            """,
            Integer.class,
            testBusinessId
        );

        // Calculate the total debit amount.
        BigDecimal totalDebit = jdbcTemplate.queryForObject(
            """
            SELECT SUM(debit)
            FROM ledger_entries
            WHERE business_id = ?
            """,
            BigDecimal.class,
            testBusinessId
        );

        // Calculate the total credit amount.
        BigDecimal totalCredit = jdbcTemplate.queryForObject(
            """
            SELECT SUM(credit)
            FROM ledger_entries
            WHERE business_id = ?
            """,
            BigDecimal.class,
            testBusinessId
        );

        assertEquals(1, transactionCount);
        assertEquals(2, entryCount);

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

    /**
     * Test 4:
     *
     * GET /transactions must return the business's
     * financial transactions as a JSON array.
     */
    @Test
    void listsTransactionsThroughHttp() throws Exception {

        // Create a transaction for the test business.
        transactionService.createDraftTransaction(
            testBusinessId,
            createRequest("500.00", "500.00")
        );

        // Retrieve the transaction list through HTTP.
        mvc().perform(get(transactionsUrl()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(
                jsonPath("$[0].status").value("DRAFT")
            )
            .andExpect(
                jsonPath("$[0].description").value("Consulting payment")
            )
            .andExpect(
                jsonPath("$[0].businessId").value(testBusinessId)
            );
    }

    /**
     * Test 5:
     *
     * GET /transactions/{transactionId} must return
     * the transaction header and its debit/credit entries.
     */
    @Test
    void retrievesTransactionDetailsThroughHttp() throws Exception {

        LedgerTransaction saved =
            transactionService.createDraftTransaction(
                testBusinessId,
                createRequest("500.00", "500.00")
            );

        String url = transactionsUrl() + "/" + saved.getId();

        // Retrieve transaction details through HTTP.
        mvc().perform(get(url))
            .andExpect(status().isOk())

            // Verify the transaction header.
            .andExpect(
                jsonPath("$.transaction.id").value(saved.getId())
            )
            .andExpect(
                jsonPath("$.transaction.status").value("DRAFT")
            )
            .andExpect(
                jsonPath("$.transaction.description")
                    .value("Consulting payment")
            )

            // Verify the number of entries.
            .andExpect(
                jsonPath("$.entries.length()").value(2)
            )

            // First entry: Debit Cash $500.
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

            // Second entry: Credit Revenue $500.
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

    /**
     * Test 6:
     *
     * A transaction belonging to Business A
     * must not appear under Business B.
     *
     * This checks query scoping, not user authorization.
     */
    @Test
    void rejectsTransactionFromAnotherBusiness() throws Exception {

        // Create a transaction for Business A.
        LedgerTransaction saved =
            transactionService.createDraftTransaction(
                testBusinessId,
                createRequest("500.00", "500.00")
            );

        // Create another test business.
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

        // Attempt to retrieve Business A's transaction
        // through Business B's URL.
        String url = "/api/v1/businesses/"
            + otherBusinessId
            + "/transactions/"
            + saved.getId();

        mvc().perform(get(url))
            .andExpect(status().isNotFound())
            .andExpect(
                jsonPath("$.error").value(
                    "Transaction not found: " + saved.getId()
                )
            );
    }

    /**
     * Test 7:
     *
     * A DRAFT transaction can be posted by a different
     * user from its original creator.
     *
     * Verify:
     * 1. The transaction becomes POSTED.
     * 2. Ledger entries remain intact.
     * 3. The posting audit identifies the actual poster.
     * 4. Creation and posting create separate audit events.
     *
     * This checks audit attribution, not authorization.
     */
    @Test
    void postsBalancedDraftTransaction() throws Exception {

        // User 1 creates the draft transaction.
        LedgerTransaction draft =
            transactionService.createDraftTransaction(
                testBusinessId,
                createRequest("500.00", "500.00")
            );

        assertEquals("DRAFT", draft.getStatus());

        /*
         * Create User 2 to perform the posting.
         *
         * The draft was created by testUserId.
         * This separate user will be the posting actor.
         */
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

        /*
         * User 2 posts the transaction through HTTP.
         *
         * Our postAsActor() helper sends:
         *
         * {
         *   "actorId": postingActorId
         * }
         */
        postAsActor(mvc(), url, postingActorId)
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.id").value(draft.getId())
            )
            .andExpect(
                jsonPath("$.status").value("POSTED")
            );

        // Independently verify the database status.
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

        // Posting must not remove or duplicate ledger entries.
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

        /*
         * Verify the posting audit identifies User 2.
         *
         * The JSONB details must record:
         * previousStatus = DRAFT
         * newStatus = POSTED
         */
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

        // The creator and posting actor are different users.
        assertNotEquals(testUserId, postingActorId);

        /*
         * Exactly two audit events should exist:
         *
         * 1. TRANSACTION_CREATED
         * 2. TRANSACTION_POSTED
         */
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

    /**
     * Test 8:
     *
     * An already-posted transaction must return
     * HTTP 409 Conflict if posted again.
     *
     * A rejected second request must not create
     * a duplicate posting audit event.
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

        // First posting request succeeds.
        postAsActor(mockMvc, url, testUserId)
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.status").value("POSTED")
            );

        // Second posting request must be rejected.
        postAsActor(mockMvc, url, testUserId)
            .andExpect(status().isConflict())
            .andExpect(
                jsonPath("$.error").value(
                    "Transaction is not in DRAFT status: "
                    + draft.getId()
                )
            );

        /*
         * The rejected request must not create another
         * TRANSACTION_POSTED audit event.
         */
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

    /**
     * Test 9:
     *
     * Posting a positive transaction ID that doesn't
     * exist must return HTTP 404 Not Found.
     */
    @Test
    void rejectsPostingNonexistentTransaction() throws Exception {

        // Long.MAX_VALUE is a positive ID unlikely
        // to exist in our isolated test database.
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

    /**
     * Test 10:
     *
     * If an account is deactivated after draft creation,
     * posting must fail with HTTP 409 Conflict.
     *
     * The transaction must remain DRAFT and no
     * TRANSACTION_POSTED audit event should be created.
     */
    @Test
    void rejectsPostingWithInactiveAccount() throws Exception {

        // Create a valid draft while both accounts are active.
        LedgerTransaction draft =
            transactionService.createDraftTransaction(
                testBusinessId,
                createRequest("500.00", "500.00")
            );

        // Deactivate the Cash account.
        // The database column is is_active, not active.
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

        // Posting should fail due to the inactive account.
        postAsActor(mvc(), url, testUserId)
            .andExpect(status().isConflict())
            .andExpect(
                jsonPath("$.error").value(
                    "Transaction references an inactive account: "
                    + cashAccountId
                )
            );

        // The transaction should still be DRAFT.
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

        assertEquals("DRAFT", databaseStatus);

        /*
         * Posting was rejected, so no posting audit
         * event should exist for this transaction.
         */
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

        assertEquals(0, postingAuditCount);
    }
}
