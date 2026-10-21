package com.ledgerbase.backend.dto;

// ============================================================
// LEDGERBASE — CURRENT USER RESPONSE
// ============================================================

/**
 * Represents the identity returned by the
 * authenticated user endpoint.
 *
 * ENDPOINT:
 *
 * GET /api/v1/auth/me
 *
 * SECURITY:
 *
 * This response contains only safe identity fields.
 *
 * It deliberately excludes:
 *
 * - Plaintext passwords
 * - Password hashes
 * - JWT signing secrets
 * - Internal authentication credentials
 *
 * Business membership roles will be retrieved
 * through the authorization layer separately.
 */
public record CurrentUserResponse(

    Long userId,

    String email,

    String fullName

) {
}
