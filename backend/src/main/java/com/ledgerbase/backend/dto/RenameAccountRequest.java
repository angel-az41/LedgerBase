package com.ledgerbase.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for changing an account's display name.
 *
 * We deliberately do not let this endpoint change
 * its account code or accounting classification.
 */
public record RenameAccountRequest(

    @NotBlank(message = "New account name is required")
    @Size(max = 150)
    String name

) {}
