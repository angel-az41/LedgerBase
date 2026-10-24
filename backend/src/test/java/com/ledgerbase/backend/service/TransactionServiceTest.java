package com.ledgerbase.backend.service;

import com.ledgerbase.backend.model.LedgerEntry;

import com.ledgerbase.backend.mapper.AccountMapper;
import com.ledgerbase.backend.mapper.AccountingPeriodMapper;
import com.ledgerbase.backend.mapper.AuditEventMapper;
import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.mapper.LedgerEntryMapper;
import com.ledgerbase.backend.mapper.LedgerTransactionMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for LedgerBase's double-entry accounting rules.
 *
 * These tests verify that:
 * - Valid transactions are accepted.
 * - Unbalanced transactions are rejected.
 * - Invalid debit and credit amounts are rejected.
 * - Every entry references an account.
 *
 * No database connection is required.
 */
class TransactionServiceTest {

    // Service containing our accounting validation logic.
    private TransactionService transactionService;
        
    // ========================================================
    // TEST INITIALIZATION
    // ========================================================

    /**
     * Create TransactionService using mocked dependencies.
     *
     * These tests exercise accounting validation only.
     * No database connections or real service calls
     * are necessary.
     *
     * The reporting revision service is also mocked
     * because revision persistence is tested separately.
     */
    @BeforeEach
    void setUp() {

        // ----------------------------------------------------
        // CREATE TRANSACTION SERVICE WITH MOCK DEPENDENCIES
        // ----------------------------------------------------

        transactionService = new TransactionService(

            // Financial transaction database operations.
            mock(LedgerTransactionMapper.class),

            // Double-entry ledger database operations.
            mock(LedgerEntryMapper.class),

            // Account validation and lookup operations.
            mock(AccountMapper.class),

            // Business ownership validation.
            mock(BusinessMapper.class),

            // Financial audit event persistence.
            mock(AuditEventMapper.class),

            // Accounting period validation and locking.
            mock(AccountingPeriodMapper.class),

            // Durable financial reporting revision tracking.
            mock(BusinessReportingRevisionService.class)
        );
    }

    /**
     * Test 1:
     * A balanced transaction with two entries is valid.
     *
     * Debit Cash:      500.00
     * Credit Revenue:  500.00
     */
    @Test
    void acceptsBalancedTransaction() {

        List<LedgerEntry> entries = List.of(
            createEntry(101L, "500.00", "0.00"),
            createEntry(102L, "0.00", "500.00")
        );

        // A valid transaction should not throw an exception.
        assertDoesNotThrow(
            () -> transactionService.validateBalancedEntries(entries)
        );
    }

    /**
     * Test 2:
     * A transaction can contain more than two entries.
     *
     * Debit Cash:       300.00
     * Debit Bank:       200.00
     * Credit Revenue:   500.00
     *
     * Total debits = total credits = 500.00.
     */
    @Test
    void acceptsMultipleBalancedEntries() {

        List<LedgerEntry> entries = List.of(
            createEntry(101L, "300.00", "0.00"),
            createEntry(102L, "200.00", "0.00"),
            createEntry(103L, "0.00", "500.00")
        );

        assertDoesNotThrow(
            () -> transactionService.validateBalancedEntries(entries)
        );
    }

    /**
     * Test 3:
     * Reject transactions where debits and credits differ.
     *
     * Debit:  500.00
     * Credit: 400.00
     */
    @Test
    void rejectsUnbalancedTransaction() {

        List<LedgerEntry> entries = List.of(
            createEntry(101L, "500.00", "0.00"),
            createEntry(102L, "0.00", "400.00")
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> transactionService.validateBalancedEntries(entries)
        );

        assertEquals(
            "Total debits must equal total credits",
            exception.getMessage()
        );
    }

    /**
     * Test 4:
     * Reject a missing list of ledger entries.
     */
    @Test
    void rejectsNullEntriesList() {

        assertThrows(
            IllegalArgumentException.class,
            () -> transactionService.validateBalancedEntries(null)
        );
    }

    /**
     * Test 5:
     * A transaction cannot contain only one entry.
     *
     * Double-entry bookkeeping requires at least
     * two entries.
     */
    @Test
    void rejectsSingleEntry() {

        List<LedgerEntry> entries = List.of(
            createEntry(101L, "500.00", "0.00")
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> transactionService.validateBalancedEntries(entries)
        );
    }

    /**
     * Test 6:
     * Every ledger entry must reference an account.
     *
     * A null account ID is not allowed.
     */
    @Test
    void rejectsMissingAccountId() {

        List<LedgerEntry> entries = List.of(
            createEntry(null, "500.00", "0.00"),
            createEntry(102L, "0.00", "500.00")
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> transactionService.validateBalancedEntries(entries)
        );
    }

    /**
     * Test 7:
     * Debit and credit amounts cannot be null.
     */
    @Test
    void rejectsNullAmounts() {

        List<LedgerEntry> entries = List.of(
            createEntry(101L, null, "0.00"),
            createEntry(102L, "0.00", "500.00")
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> transactionService.validateBalancedEntries(entries)
        );
    }

    /**
     * Test 8:
     * Negative debit amounts are not allowed.
     */
    @Test
    void rejectsNegativeDebit() {

        List<LedgerEntry> entries = List.of(
            createEntry(101L, "-500.00", "0.00"),
            createEntry(102L, "0.00", "500.00")
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> transactionService.validateBalancedEntries(entries)
        );
    }

    /**
     * Test 9:
     * Negative credit amounts are not allowed.
     */
    @Test
    void rejectsNegativeCredit() {

        List<LedgerEntry> entries = List.of(
            createEntry(101L, "500.00", "0.00"),
            createEntry(102L, "0.00", "-500.00")
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> transactionService.validateBalancedEntries(entries)
        );
    }

    /**
     * Test 10:
     * Amounts cannot have more than two decimal places.
     *
     * Our PostgreSQL columns use NUMERIC(18,2).
     *
     * Example:
     * 500.001 is invalid because it has three
     * decimal places.
     */
    @Test
    void rejectsMoreThanTwoDecimalPlaces() {

        List<LedgerEntry> entries = List.of(
            createEntry(101L, "500.001", "0.00"),
            createEntry(102L, "0.00", "500.001")
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> transactionService.validateBalancedEntries(entries)
        );
    }

    /**
     * Test 11:
     * An individual entry cannot have both a positive
     * debit and a positive credit.
     */
    @Test
    void rejectsBothDebitAndCreditPositive() {

        List<LedgerEntry> entries = List.of(
            createEntry(101L, "500.00", "500.00"),
            createEntry(102L, "0.00", "500.00")
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> transactionService.validateBalancedEntries(entries)
        );
    }

    /**
     * Test 12:
     * An individual entry cannot have both amounts zero.
     *
     * Every valid entry must represent an actual
     * debit or credit amount.
     */
    @Test
    void rejectsBothDebitAndCreditZero() {

        List<LedgerEntry> entries = List.of(
            createEntry(101L, "0.00", "0.00"),
            createEntry(102L, "0.00", "500.00")
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> transactionService.validateBalancedEntries(entries)
        );
    }

    /**
     * Helper method for creating test ledger entries.
     *
     * Instead of repeating the setters in every test,
     * we create entries through this method.
     *
     * BigDecimal values are constructed from strings
     * to preserve exact decimal precision.
     *
     * A null amount is kept as null so we can also
     * test validation of missing amounts.
     */
    private LedgerEntry createEntry(
            Long accountId,
            String debit,
            String credit) {

        LedgerEntry entry = new LedgerEntry();

        entry.setAccountId(accountId);

        entry.setDebit(
            debit == null ? null : new BigDecimal(debit)
        );

        entry.setCredit(
            credit == null ? null : new BigDecimal(credit)
        );

        return entry;
    }
}
