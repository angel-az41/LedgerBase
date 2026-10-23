package com.ledgerbase.backend.dto;

// ============================================================
// LEDGERBASE — AUTHENTICATED USER DTO
// ============================================================

/**
 * Represents a successfully authenticated user.
 *
 * PURPOSE:
 *
 * Return only the identity information required by
 * trusted backend authentication components.
 *
 * SECURITY:
 *
 * This record deliberately excludes:
 *
 * - Password hashes
 * - Raw passwords
 * - Authentication tokens
 *
 * The user's database ID will become the trusted
 * identity used when authorizing business operations.
 *
 * This is an internal authentication result.
 * We will define separate HTTP response DTOs
 * when implementing the login endpoint.
 */
public record AuthenticatedUser(

    Long id,

    String email,

    String fullName

) {
}
