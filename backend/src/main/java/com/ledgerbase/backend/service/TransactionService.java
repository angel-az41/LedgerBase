package com.ledgerbase.backend.service;

import com.ledgerbase.backend.dto.CreateLedgerEntryRequest;
import com.ledgerbase.backend.dto.CreateTransactionRequest;
import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.mapper.AccountMapper;
import com.ledgerbase.backend.mapper.AccountingPeriodMapper;
import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.mapper.LedgerEntryMapper;
import com.ledgerbase.backend.mapper.LedgerTransactionMapper;
import com.ledgerbase.backend.model.Account;
import com.ledgerbase.backend.model.AccountingPeriod;
import com.ledgerbase.backend.model.LedgerEntry;
import com.ledgerbase.backend.model.LedgerTransaction;
import com.ledgerbase.backend.dto.TransactionDetailResponse;
import com.ledgerbase.backend.exception.TransactionNotFoundException;
import com.ledgerbase.backend.exception.TransactionConflictException;
import com.ledgerbase.backend.mapper.AuditEventMapper;
import com.ledgerbase.backend.model.AuditEvent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Service responsible for financial transaction operations.
 *
 * Responsibilities:
 * 1. Validate debit and credit amounts.
 * 2. Check business and account information.
 * 3. Create draft financial transactions.
 * 4. Save all ledger entries atomically.
 *
 * Future responsibilities include transaction posting,
 * audit logging and reversal operations.
 */
@Service
public class TransactionService {

    // Access to the transactions table.
    private final LedgerTransactionMapper transactionMapper;

    // Access to the ledger_entries table.
    private final LedgerEntryMapper entryMapper;

    // Used to verify that accounts exist and are active.
    private final AccountMapper accountMapper;

    // Used to verify that the business exists.
    private final BusinessMapper businessMapper;

    // Used to record audit events in the audit_events table.
    private final AuditEventMapper auditEventMapper;

    // Used to retrieve and lock accounting periods during posting.
    private final AccountingPeriodMapper accountingPeriodMapper;

    /**
     * Spring injects the MyBatis mappers.
     *
     * Keeping dependencies in the constructor makes
     * the service easier to understand and unit test.
     */
    public TransactionService(
            LedgerTransactionMapper transactionMapper,
            LedgerEntryMapper entryMapper,
            AccountMapper accountMapper,
            BusinessMapper businessMapper,
            AuditEventMapper auditEventMapper,
            AccountingPeriodMapper accountingPeriodMapper) {

        this.transactionMapper = transactionMapper;
        this.entryMapper = entryMapper;
        this.accountMapper = accountMapper;
        this.businessMapper = businessMapper;
        this.auditEventMapper = auditEventMapper;
        this.accountingPeriodMapper = accountingPeriodMapper;
    }

    /**
     * Create a new DRAFT transaction and its ledger entries.
     *
     * @Transactional makes the entire operation atomic.
     *
     * If saving any ledger entry fails, Spring rolls back
     * the transaction header and all previously inserted
     * entries as well.
     *
     * The new transaction is not yet POSTED.
     *
     * @param businessId Business from the API URL.
     * @param request Incoming transaction and entry data.
     * @return The saved transaction header.
     */
    @Transactional
    public LedgerTransaction createDraftTransaction(
            Long businessId,
            CreateTransactionRequest request) {

        // Validate required transaction information.
        if (businessId == null || businessId <= 0) {
            throw new IllegalArgumentException(
                "A valid business ID is required"
            );
        }

        if (request == null) {
            throw new IllegalArgumentException(
                "Transaction request cannot be null"
            );
        }

        if (request.createdBy() == null
                || request.createdBy() <= 0) {
            throw new IllegalArgumentException(
                "A valid creator ID is required"
            );
        }

        if (request.description() == null
                || request.description().isBlank()) {
            throw new IllegalArgumentException(
                "Transaction description is required"
            );
        }

        if (request.transactionDate() == null) {
            throw new IllegalArgumentException(
                "Transaction date is required"
            );
        }

        LocalDate today = LocalDate.now();

        if (request.transactionDate().isAfter(today)) {

            throw new IllegalArgumentException(
                "Transaction date cannot be in the future"
            );
        }

        // Reject unknown businesses before inserting records.
        if (!businessMapper.existsById(businessId)) {
            throw new BusinessNotFoundException(businessId);
        }

        /*
         * Convert incoming DTOs into LedgerEntry model objects.
         *
         * We do not assign transactionId yet, because the
         * parent transaction has not been inserted.
         */
        List<LedgerEntry> entries = new ArrayList<>();

        if (request.entries() == null) {
            throw new IllegalArgumentException(
                "Transaction entries cannot be null"
            );
        }

        for (CreateLedgerEntryRequest line : request.entries()) {

            if (line == null) {
                throw new IllegalArgumentException(
                    "Transaction entries cannot contain null"
                );
            }

            LedgerEntry entry = new LedgerEntry();

            entry.setAccountId(line.accountId());
            entry.setDebit(line.debit());
            entry.setCredit(line.credit());

            entries.add(entry);
        }

        /*
         * Validate double-entry accounting rules.
         *
         * This checks that:
         * - At least two entries exist.
         * - Amounts are valid.
         * - Each entry has exactly one positive side.
         * - Total debits equal total credits.
         */
        validateBalancedEntries(entries);

        /*
         * Ensure every account belongs to this business
         * and is currently active.
         *
         * The AccountMapper query is scoped by businessId.
         */
        for (LedgerEntry entry : entries) {

            Account account = accountMapper.findById(
                businessId,
                entry.getAccountId()
            );

            if (account == null) {
                throw new IllegalArgumentException(
                    "Account not found in this business: "
                    + entry.getAccountId()
                );
            }

            if (!Boolean.TRUE.equals(account.getActive())) {
                throw new IllegalArgumentException(
                    "Account is inactive: " + entry.getAccountId()
                );
            }
        }

        /*
         * Build the transaction header.
         *
         * The PostgreSQL INSERT generates the ID.
         * Our mapper explicitly inserts status = 'DRAFT'.
         */
        LedgerTransaction transaction = new LedgerTransaction();

        transaction.setBusinessId(businessId);
        transaction.setCreatedBy(request.createdBy());
        transaction.setDescription(request.description());
        transaction.setTransactionDate(request.transactionDate());
        transaction.setStatus("DRAFT");

        /*
         * Save the transaction header first so we can
         * obtain its generated ID.
         */
        int insertedTransactions = transactionMapper.insert(
            transaction
        );

        if (insertedTransactions != 1 || transaction.getId() == null) {
            throw new IllegalStateException(
                "Failed to create transaction header"
            );
        }

        /*
         * Save every ledger entry.
         *
         * All entries use the same businessId and
         * the generated parent transaction ID.
         */
        for (LedgerEntry entry : entries) {

            entry.setBusinessId(businessId);
            entry.setTransactionId(transaction.getId());

            int insertedEntries = entryMapper.insert(entry);

            if (insertedEntries != 1) {
                throw new IllegalStateException(
                    "Failed to create ledger entry"
                );
            }
        }

    /*
    * Record an audit event after the transaction header
    * and all ledger entries have been inserted.
    *
    * This operation participates in the same database
    * transaction because createDraftTransaction()
    * is annotated with @Transactional.
    *
    * If the audit INSERT fails, Spring rolls back the
    * transaction header and ledger entries too.
    */
    AuditEvent auditEvent = new AuditEvent();

    // Record which business this event belongs to.
    auditEvent.setBusinessId(businessId);

    // For now, the creator ID comes from the request.
    // Authentication will eventually provide a trusted actor.
    auditEvent.setActorId(request.createdBy());

    // Describe what action occurred.
    auditEvent.setAction("TRANSACTION_CREATED");

    // Identify the type and ID of the affected record.
    auditEvent.setEntityType("TRANSACTION");
    auditEvent.setEntityId(transaction.getId());

    // Store extra information as a valid JSON string.
    // AuditEventMapper.xml casts this to PostgreSQL JSONB.
    auditEvent.setDetails("{\"status\":\"DRAFT\"}");

    // Save the audit event in the same transaction.
    int insertedAuditEvents = auditEventMapper.insert(auditEvent);

    if (insertedAuditEvents != 1) {
        throw new IllegalStateException(
            "Failed to create transaction audit event"
        );
    }

        /*
         * Retrieve the transaction from PostgreSQL.
         *
         * This populates database-managed fields such as
         * createdAt and confirms the saved DRAFT status.
         */
        LedgerTransaction savedTransaction =
            transactionMapper.findById(
                businessId,
                transaction.getId()
            );

        if (savedTransaction == null) {
            throw new IllegalStateException(
                "Created transaction could not be retrieved"
            );
        }

        return savedTransaction;
    }

    /**
     * Retrieve all financial transactions belonging to a business.
     *
     * The transaction mapper automatically returns records
     * ordered by transaction date, newest first.
     *
     * @Transactional(readOnly = true) tells Spring that
     * this operation only reads database records.
     *
     * This method returns transaction headers.
     * We will retrieve individual ledger entries separately.
     *
     * @param businessId The business whose transactions we want.
     * @return A list of that business's transactions.
     */
    @Transactional(readOnly = true)
    public List<LedgerTransaction> getTransactions(Long businessId) {

        // Reject invalid business IDs.
        if (businessId == null || businessId <= 0) {
            throw new IllegalArgumentException(
                "A valid business ID is required"
            );
        }

        // Verify that the requested business actually exists.
        if (!businessMapper.existsById(businessId)) {
            throw new BusinessNotFoundException(businessId);
        }

        // MyBatis retrieves only transactions belonging
        // to this business.
        return transactionMapper.findByBusinessId(businessId);
    }

    /**
     * Retrieve one financial transaction together
     * with all its debit and credit entries.
     *
     * Both database queries are scoped by businessId.
     *
     * @Transactional(readOnly = true) groups the reads
     * into a read-only database transaction.
     *
     * @param businessId Business that owns the transaction.
     * @param transactionId Requested transaction ID.
     * @return Transaction header and ledger entries.
     */
    @Transactional(readOnly = true)
    public TransactionDetailResponse getTransactionDetails(
            Long businessId,
            Long transactionId) {

        // Validate the requested identifiers.
        if (businessId == null || businessId <= 0) {
            throw new IllegalArgumentException(
                "A valid business ID is required"
            );
        }

        if (transactionId == null || transactionId <= 0) {
            throw new IllegalArgumentException(
                "A valid transaction ID is required"
            );
        }

        // Verify that the business exists.
        if (!businessMapper.existsById(businessId)) {
            throw new BusinessNotFoundException(businessId);
        }

        // Retrieve the transaction header.
        // This query includes businessId for data isolation.
        LedgerTransaction transaction = transactionMapper.findById(
            businessId,
            transactionId
        );

        // Return 404 if the transaction doesn't exist
        // within this business.
        if (transaction == null) {
            throw new TransactionNotFoundException(transactionId);
        }

        // Retrieve all ledger entries for this transaction.
        List<LedgerEntry> entries = entryMapper.findByTransactionId(
            businessId,
            transactionId
        );

        // Combine the transaction header and entries
        // into one response object.
        return new TransactionDetailResponse(
            transaction,
            entries
        );
    }

    /**
     * Finalize a DRAFT financial transaction.
     *
     * The entire posting operation runs inside one
     * PostgreSQL database transaction.
     *
     * Steps:
     * 1. Lock the transaction row.
     * 2. Verify that its status is DRAFT.
     * 3. Retrieve and validate its ledger entries.
     * 4. Check that all accounts are still active.
     * 5. Update the transaction status to POSTED.
     *
     * The database row lock prevents two concurrent
     * posting requests from posting the same transaction.
     *
     * @param businessId Business that owns the transaction.
     * @param transactionId Transaction to post.
     * @return The updated transaction with POSTED status.
     */
    @Transactional
    public LedgerTransaction postTransaction(
            Long businessId,
            Long transactionId,
            Long actorId) {

        // Validate both IDs before querying PostgreSQL.
        if (businessId == null || businessId <= 0) {
            throw new IllegalArgumentException(
                "A valid business ID is required"
            );
        }

        if (transactionId == null || transactionId <= 0) {
            throw new IllegalArgumentException(
                "A valid transaction ID is required"
            );
        }

        if (actorId == null || actorId <= 0) {
            throw new IllegalArgumentException(
                "A valid posting actor ID is required"
            );
        }

        // Return 404 when the requested business does not exist.
        if (!businessMapper.existsById(businessId)) {
            throw new BusinessNotFoundException(businessId);
        }

        /*
        * Lock the transaction row using SELECT ... FOR UPDATE.
        *
        * Another posting request for the same transaction
        * must wait until this database transaction finishes.
        */
        LedgerTransaction transaction =
            transactionMapper.findByIdForUpdate(
                businessId,
                transactionId
            );

        // Return 404 if the transaction cannot be found.
        if (transaction == null) {
            throw new TransactionNotFoundException(transactionId);
        }

        /*
        * Only DRAFT transactions can be posted.
        *
        * This prevents posting an already-posted transaction
        * a second time.
        */
        if (!"DRAFT".equals(transaction.getStatus())) {
            throw new TransactionConflictException(
                "Transaction is not in DRAFT status: "
                + transactionId
            );
        }

        /**
         * An invalid existing draft is treated as a conflict
         * with the current posting rules.
         *
         * The transaction remains in DRAFT status.
         *
         * No posting audit event is created.
         */

        LocalDate transactionDate = transaction.getTransactionDate();

        if (transactionDate == null) {

            throw new TransactionConflictException(
                "Cannot post a transaction without a date: "
                + transactionId
            );
        }

        if (transactionDate.isAfter(LocalDate.now())) {

            throw new TransactionConflictException(
                "Cannot post a future-dated transaction: "
                + transactionId
            );
        }

        /**
         * Every POSTED transaction must belong to an OPEN
         * accounting period.
         *
         * We use the transaction's accounting date, not the
         * current date, to determine its accounting period.
         *
         * Example:
         *
         * Transaction date: September 15, 2026
         * Current date:     October 14, 2026
         *
         * If September is CLOSED, posting must be rejected
         * even though October is currently OPEN.
         *
         * findByDateForUpdate() executes SELECT ... FOR UPDATE.
         *
         * This obtains a PostgreSQL row lock on the matching
         * accounting period.
         *
         * The lock remains held until the surrounding
         * @Transactional posting operation completes.
         *
         * This prevents another request from closing the period
         * between our validation and the POSTED status update.
         */
        AccountingPeriod accountingPeriod =
            accountingPeriodMapper.findByDateForUpdate(
                businessId,
                transactionDate
            );

        /**
         * A transaction must not be posted if its date
         * is outside every configured accounting period.
         *
         * We deliberately do not create a missing fiscal year
         * or accounting period automatically during posting.
         *
         * Accounting calendars must be established explicitly.
         */
        if (accountingPeriod == null) {

            throw new TransactionConflictException(
                "No accounting period exists for transaction date: "
                + transactionDate
            );
        }

        /**
         * Only OPEN periods allow new journal postings.
         *
         * CLOSED periods preserve finalized accounting results.
         *
         * If posting is rejected:
         *
         * - The transaction remains DRAFT.
         * - Its existing ledger entries remain unchanged.
         * - No TRANSACTION_POSTED audit event is created.
         *
         * The surrounding transaction rolls back normally.
         */
        if (accountingPeriod.getStatus() != AccountingPeriod.Status.OPEN) {

            throw new TransactionConflictException(
                "Cannot post into a closed accounting period: "
                + transactionDate
            );
        }

        /*
        * Retrieve the transaction's ledger entries.
        *
        * The existing MyBatis query returns entries
        * belonging to this business and transaction.
        */
        List<LedgerEntry> entries =
            entryMapper.findByTransactionId(
                businessId,
                transactionId
            );

        /*
        * Validate double-entry accounting rules again.
        *
        * A transaction must still be balanced at the
        * moment it is posted, even if it was valid
        * when originally created.
        */
        validateBalancedEntries(entries);

        /*
        * Verify that each account still exists
        * and belongs to the correct business.
        *
        * We also reject inactive accounts.
        */
        for (LedgerEntry entry : entries) {

            Account account = accountMapper.findById(
                businessId,
                entry.getAccountId()
            );

            if (account == null) {
                throw new TransactionConflictException(
                    "Transaction references a missing account: "
                    + entry.getAccountId()
                );
            }

            if (!Boolean.TRUE.equals(account.getActive())) {
                throw new TransactionConflictException(
                    "Transaction references an inactive account: "
                    + entry.getAccountId()
                );
            }
        }

        /*
        * Update the transaction status.
        *
        * The SQL includes:
        *
        * WHERE business_id = ?
        *   AND id = ?
        *   AND status = 'DRAFT'
        *
        * The update should affect exactly one row.
        */
        int updatedRows = transactionMapper.markAsPosted(
            businessId,
            transactionId
        );

        if (updatedRows != 1) {
            throw new TransactionConflictException(
                "Transaction could not be posted: "
                + transactionId
            );
        }

        /*
        * Record the successful DRAFT -> POSTED transition.
        *
        * The audit event is inserted inside the same
        * @Transactional method as the status update.
        *
        * Therefore, if the audit INSERT fails, Spring
        * rolls back the POSTED status change as well.
        */
        AuditEvent postingAudit = new AuditEvent();

        // Identify the business affected by the operation.
        postingAudit.setBusinessId(businessId);

        // Record the user who performed the posting.
        // This may be different from transaction.getCreatedBy().
        postingAudit.setActorId(actorId);

        // Describe the accounting action.
        postingAudit.setAction("TRANSACTION_POSTED");

        // Identify the affected financial transaction.
        postingAudit.setEntityType("TRANSACTION");
        postingAudit.setEntityId(transactionId);

        // Store the status transition in PostgreSQL JSONB.
        postingAudit.setDetails(
            "{\"previousStatus\":\"DRAFT\",\"newStatus\":\"POSTED\"}"
        );

        // Insert the audit record into PostgreSQL.
        int insertedAuditEvents = auditEventMapper.insert(
            postingAudit
        );

        // Fail the entire posting operation if audit logging fails.
        if (insertedAuditEvents != 1) {
            throw new IllegalStateException(
                "Failed to create transaction posting audit event"
            );
        }

        /*
        * Retrieve the latest record from PostgreSQL.
        *
        * This ensures that the returned status reflects
        * the database rather than only changing a
        * Java object's in-memory property.
        */
        LedgerTransaction postedTransaction =
            transactionMapper.findById(
                businessId,
                transactionId
            );

        if (postedTransaction == null) {
            throw new IllegalStateException(
                "Posted transaction could not be retrieved"
            );
        }

        return postedTransaction;
    }

    /**
     * Validate double-entry accounting rules.
     *
     * This method checks amounts and mathematical balance.
     * It does not query PostgreSQL or save records.
     */
    public void validateBalancedEntries(List<LedgerEntry> entries) {

        if (entries == null || entries.size() < 2) {
            throw new IllegalArgumentException(
                "A transaction requires at least two ledger entries"
            );
        }

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (LedgerEntry entry : entries) {

            // Every entry must reference an account.
            if (entry == null || entry.getAccountId() == null) {
                throw new IllegalArgumentException(
                    "Every ledger entry must reference an account"
                );
            }

            BigDecimal debit = entry.getDebit();
            BigDecimal credit = entry.getCredit();

            // Missing amounts are not permitted.
            if (debit == null || credit == null) {
                throw new IllegalArgumentException(
                    "Debit and credit amounts cannot be null"
                );
            }

            // PostgreSQL stores amounts as NUMERIC(18,2).
            if (debit.scale() > 2 || credit.scale() > 2) {
                throw new IllegalArgumentException(
                    "Amounts cannot have more than two decimal places"
                );
            }

            // Prevent negative amounts.
            if (debit.compareTo(BigDecimal.ZERO) < 0
                    || credit.compareTo(BigDecimal.ZERO) < 0) {

                throw new IllegalArgumentException(
                    "Debit and credit amounts cannot be negative"
                );
            }

            boolean hasDebit =
                debit.compareTo(BigDecimal.ZERO) > 0;

            boolean hasCredit =
                credit.compareTo(BigDecimal.ZERO) > 0;

            // Exactly one side must have a positive amount.
            if (hasDebit == hasCredit) {
                throw new IllegalArgumentException(
                    "Each entry must have either a debit or a credit"
                );
            }

            totalDebit = totalDebit.add(debit);
            totalCredit = totalCredit.add(credit);
        }

        // The central double-entry accounting invariant.
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new IllegalArgumentException(
                "Total debits must equal total credits"
            );
        }
    }
}
