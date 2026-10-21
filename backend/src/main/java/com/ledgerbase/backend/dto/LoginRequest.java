package com.ledgerbase.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// ============================================================
// LEDGERBASE — LOGIN REQUEST DTO
// ============================================================

/**
 * Represents the credentials submitted to the login API.
 *
 * EXAMPLE REQUEST:
 *
 * POST /api/v1/auth/login
 *
 * {
 *     "email": "user@example.com",
 *     "password": "example-password"
 * }
 *
 * VALIDATION:
 *
 * - Email must not be blank.
 * - Email must have a valid format.
 * - Password must not be blank.
 *
 * SECURITY:
 *
 * Passwords must never be logged or included
 * in exception messages.
 *
 * This DTO is used only to receive credentials.
 * It must never be returned in API responses.
 */
public record LoginRequest(

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    String email,

    @NotBlank(message = "Password is required")
    String password

) {
}
