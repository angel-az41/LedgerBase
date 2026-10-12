package com.ledgerbase.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request DTO for posting a financial transaction.
 *
 * actorId identifies the user performing the posting.
 *
 * For local development, this ID is supplied by
 * the client. Once authentication is implemented,
 * the backend must obtain the trusted user identity
 * from the authenticated session instead.
 */
public record PostTransactionRequest(

    // The user who is posting the transaction.
    @NotNull
    @Positive
    Long actorId

) {}
