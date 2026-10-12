package com.ledgerbase.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Represents the data supplied when creating
 * a new financial transaction.
 *
 * A transaction contains:
 * - Information about the financial event
 * - The user who created it
 * - Two or more debit/credit entries
 *
 * The business ID comes from the API URL,
 * not from the JSON request body.
 */
public record CreateTransactionRequest(

    // Temporary creator ID for local development.
    // Authentication will eventually determine this.
    @NotNull
    @Positive
    Long createdBy,

    // Explanation of the financial activity.
    @NotBlank
    String description,

    // Accounting date of the transaction.
    @NotNull
    LocalDate transactionDate,

    // A transaction must contain at least two lines.
    //
    // @Valid also validates each nested entry DTO.
    @NotNull
    @Size(min = 2)
    List<@NotNull @Valid CreateLedgerEntryRequest> entries

) {}
