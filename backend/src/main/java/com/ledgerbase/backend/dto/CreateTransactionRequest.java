package com.ledgerbase.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

// ============================================================
// LEDGERBASE — CREATE TRANSACTION REQUEST
// ============================================================

/**
 * Represents a request to create a DRAFT transaction.
 *
 * REQUEST INFORMATION:
 *
 * - Transaction description.
 * - Accounting date.
 * - At least two debit/credit ledger entries.
 *
 * BUSINESS IDENTITY:
 *
 * The businessId comes from the API URL.
 *
 * ACTOR IDENTITY:
 *
 * The actual creator comes from the authenticated JWT.
 *
 * The legacy createdBy field is temporarily retained
 * for compatibility with existing frontend requests
 * and transaction tests.
 *
 * IMPORTANT SECURITY RULE:
 *
 * TransactionController must ignore any createdBy
 * value received from the browser and construct
 * a trusted request using the verified JWT subject.
 *
 * Because the browser no longer needs to provide
 * createdBy, this record does not validate that field
 * as required or positive.
 *
 * TransactionService continues to validate that
 * the trusted internal request contains a positive
 * creator ID before writing database records.
 *
 * The legacy field can be removed in a later API
 * contract cleanup after existing callers migrate.
 */
public record CreateTransactionRequest(

    // ========================================================
    // LEGACY CREATOR FIELD
    // ========================================================

    /**
     * Legacy input retained for compatibility.
     *
     * NOT TRUSTED when submitted over HTTP.
     *
     * The controller replaces this value with the
     * authenticated user's PostgreSQL ID.
     *
     * It may be null in incoming browser requests.
     */
    Long createdBy,


    // ========================================================
    // TRANSACTION DESCRIPTION
    // ========================================================

    /**
     * Explain the financial activity.
     *
     * Example:
     *
     * "Office supplies purchase"
     */
    @NotBlank
    String description,


    // ========================================================
    // ACCOUNTING DATE
    // ========================================================

    /**
     * Date used to determine the accounting period.
     *
     * Example:
     *
     * 2026-10-22
     *
     * TransactionService also rejects future dates.
     */
    @NotNull
    LocalDate transactionDate,


    // ========================================================
    // DOUBLE-ENTRY JOURNAL LINES
    // ========================================================

    /**
     * The transaction requires at least two lines.
     *
     * Each nested entry is validated using Jakarta
     * Bean Validation annotations.
     *
     * TransactionService verifies:
     *
     * - Account existence and business ownership.
     * - Active accounts.
     * - Valid monetary precision.
     * - Nonnegative debit/credit amounts.
     * - Exactly one positive side per entry.
     * - Total debit equals total credit.
     */
    @NotNull
    @Size(min = 2)
    List<@NotNull @Valid CreateLedgerEntryRequest> entries

) {}
