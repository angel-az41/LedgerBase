package com.ledgerbase.backend.dto;

import com.ledgerbase.backend.model.LedgerEntry;
import com.ledgerbase.backend.model.LedgerTransaction;

import java.util.List;

/**
 * Response DTO containing a financial transaction
 * and all its associated debit/credit entries.
 *
 * This combines data from two PostgreSQL tables:
 *
 * - transactions
 * - ledger_entries
 *
 * Java records automatically provide a constructor
 * and accessor methods.
 */
public record TransactionDetailResponse(

    // The transaction header, including status and date.
    LedgerTransaction transaction,

    // All debit and credit entries for this transaction.
    List<LedgerEntry> entries

) {}
