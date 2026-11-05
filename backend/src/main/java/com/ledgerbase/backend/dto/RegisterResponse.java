package com.ledgerbase.backend.dto;

// ============================================================
// LEDGERBASE — USER REGISTRATION RESPONSE
// ============================================================

/**
 * Represents a successfully registered LedgerBase user.
 *
 * ENDPOINT:
 *
 * POST /api/v1/auth/register
 *
 * SUCCESS:
 *
 * HTTP 201 Created.
 *
 * The response contains only public-facing
 * account identity information.
 *
 * SECURITY:
 *
 * - Never expose password hashes.
 * - Never return plaintext passwords.
 * - Registration does not automatically create
 *   an authenticated session.
 * - Registration does not grant access to any
 *   existing LedgerBase business.
 *
 * The user signs in through the existing login
 * endpoint after account creation.
 */
public record RegisterResponse(

    Long userId,

    String email,

    String fullName

) {
}
