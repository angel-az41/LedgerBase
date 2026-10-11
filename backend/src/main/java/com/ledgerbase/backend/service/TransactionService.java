package com.ledgerbase.backend.service;

import com.ledgerbase.backend.model.LedgerEntry;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Service responsible for LedgerBase's accounting rules.
 *
 * Eventually, this service will also:
 *
 * 1. Create draft transactions.
 * 2. Save ledger entries.
 * 3. Validate that transactions are balanced.
 * 4. Post transactions atomically.
 * 5. Record audit events.
 *
 * For now, we implement double-entry validation.
 */
@Service
public class TransactionService {

    /**
     * Validates the debit and credit entries of a transaction.
     *
     * Rules:
     * - At least two entries are required.
     * - Every entry must reference an account.
     * - Debit and credit amounts cannot be null or negative.
     * - Each entry must have exactly one positive side.
     * - Amounts cannot have more than two decimal places.
     * - Total debits must equal total credits.
     *
     * Throws IllegalArgumentException if validation fails.
     *
     * This method does not insert or update database records.
     */
    public void validateBalancedEntries(List<LedgerEntry> entries) {

        // A transaction needs at least two accounting entries.
        if (entries == null || entries.size() < 2) {
            throw new IllegalArgumentException(
                "A transaction requires at least two ledger entries"
            );
        }

        // BigDecimal is used for accurate decimal arithmetic.
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        // Check each individual entry.
        for (LedgerEntry entry : entries) {

            // Reject missing entries or account references.
            if (entry == null || entry.getAccountId() == null) {
                throw new IllegalArgumentException(
                    "Every ledger entry must reference an account"
                );
            }

            BigDecimal debit = entry.getDebit();
            BigDecimal credit = entry.getCredit();

            // Both values must be provided explicitly.
            if (debit == null || credit == null) {
                throw new IllegalArgumentException(
                    "Debit and credit amounts cannot be null"
                );
            }

            // Reject amounts with more than two decimal places.
            // PostgreSQL stores these columns as NUMERIC(18,2).
            if (debit.scale() > 2 || credit.scale() > 2) {
                throw new IllegalArgumentException(
                    "Amounts cannot have more than two decimal places"
                );
            }

            // Negative accounting entry amounts are not allowed.
            if (debit.compareTo(BigDecimal.ZERO) < 0
                    || credit.compareTo(BigDecimal.ZERO) < 0) {

                throw new IllegalArgumentException(
                    "Debit and credit amounts cannot be negative"
                );
            }

            // Determine which side of the entry is positive.
            boolean hasDebit = debit.compareTo(BigDecimal.ZERO) > 0;
            boolean hasCredit = credit.compareTo(BigDecimal.ZERO) > 0;

            // Exactly one side must be positive.
            //
            // Invalid examples:
            // debit=100, credit=50
            // debit=0, credit=0
            if (hasDebit == hasCredit) {
                throw new IllegalArgumentException(
                    "Each entry must have either a debit or a credit"
                );
            }

            // Add the entry amounts to the transaction totals.
            totalDebit = totalDebit.add(debit);
            totalCredit = totalCredit.add(credit);
        }

        // Double-entry accounting requires matching totals.
        //
        // compareTo() compares numerical values:
        // 500.0 and 500.00 are considered equal.
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new IllegalArgumentException(
                "Total debits must equal total credits"
            );
        }
    }
}
