package com.ledgerbase.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Represents one debit or credit line supplied
 * when creating a financial transaction.
 *
 * This is a request DTO, not a database model.
 *
 * The backend will assign businessId,
 * transactionId, and the generated entry ID.
 */
public record CreateLedgerEntryRequest(

    // The account affected by this entry.
    @NotNull
    @Positive
    Long accountId,

    // Debit amount, such as 500.00 or 0.00.
    @NotNull
    BigDecimal debit,

    // Credit amount, such as 500.00 or 0.00.
    @NotNull
    BigDecimal credit

) {}
