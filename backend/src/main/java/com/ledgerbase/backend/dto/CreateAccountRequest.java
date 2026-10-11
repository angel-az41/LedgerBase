package com.ledgerbase.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Data Transfer Object (DTO) for creating an account.
 *
 * The frontend sends these fields as JSON.
 * We validate them before calling AccountService.
 *
 * Notice that businessId is NOT included here:
 * it comes from the URL path instead.
 */
public record CreateAccountRequest(

    // Human-readable account code, e.g. "1000".
    @NotBlank(message = "Account code is required")
    @Size(max = 20)
    String code,

    // Name shown in the Chart of Accounts.
    @NotBlank(message = "Account name is required")
    @Size(max = 150)
    String name,

    // Explicit accounting classification.
    @NotNull(message = "Account type is required")
    @Pattern(
        regexp = "ASSET|LIABILITY|EQUITY|REVENUE|EXPENSE",
        message = "Invalid account type"
    )
    String accountType
) {}
