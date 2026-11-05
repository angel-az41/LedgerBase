package com.ledgerbase.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// ============================================================
// LEDGERBASE — USER REGISTRATION REQUEST
// ============================================================

/**
 * Represents the information submitted when a new
 * user creates a LedgerBase account.
 *
 * ENDPOINT:
 *
 * POST /api/v1/auth/register
 *
 * VALIDATION:
 *
 * - Full name is required.
 * - Email must have a valid format.
 * - Password must satisfy the minimum length.
 *
 * SECURITY:
 *
 * - The password must never be logged.
 * - The password must never be stored in plaintext.
 * - Password hashing belongs in RegistrationService.
 * - Email normalization belongs in RegistrationService.
 *
 * The service layer will additionally validate the
 * UTF-8 password byte length before BCrypt encoding.
 *
 * This request does not include business membership
 * or authorization information.
 */
public record RegisterRequest(

    @NotBlank(
        message = "Full name is required"
    )
    @Size(
        min = 2,
        max = 150,
        message = "Full name must contain 2 to 150 characters"
    )
    String fullName,

    @NotBlank(
        message = "Email is required"
    )
    @Email(
        message = "Please enter a valid email address"
    )
    @Size(
        max = 255,
        message = "Email must not exceed 255 characters"
    )
    String email,

    @NotBlank(
        message = "Password is required"
    )
    @Size(
        min = 12,
        max = 72,
        message = "Password must contain 12 to 72 characters"
    )
    String password

) {
}
