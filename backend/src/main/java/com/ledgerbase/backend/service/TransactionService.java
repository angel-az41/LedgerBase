package com.ledgerbase.backend.service;

import com.ledgerbase.backend.dto.CreateLedgerEntryRequest;
import com.ledgerbase.backend.dto.CreateTransactionRequest;
import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.mapper.AccountMapper;
import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.mapper.LedgerEntryMapper;
import com.ledgerbase.backend.mapper.LedgerTransactionMapper;
import com.ledgerbase.backend.model.Account;
import com.ledgerbase.backend.model.LedgerEntry;
import com.ledgerbase.backend.model.LedgerTransaction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    /**
     * Spring automatically injects the four MyBatis mappers.
     *
     * Constructor injection makes dependencies explicit
     * and allows us to provide mock implementations in tests.
     */
    public TransactionService(
            LedgerTransactionMapper transactionMapper,
            LedgerEntryMapper entryMapper,
            AccountMapper accountMapper,
            BusinessMapper businessMapper) {

        this.transactionMapper = transactionMapper;
        this.entryMapper = entryMapper;
        this.accountMapper = accountMapper;
        this.businessMapper = businessMapper;
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
