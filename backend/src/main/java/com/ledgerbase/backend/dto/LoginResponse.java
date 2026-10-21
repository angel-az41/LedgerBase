package com.ledgerbase.backend.dto;

// ============================================================
// LEDGERBASE — LOGIN RESPONSE DTO
// ============================================================

/**
 * Response returned after successful authentication.
 *
 * EXAMPLE:
 *
 * {
 *     "accessToken": "signed-jwt",
 *     "tokenType": "Bearer",
 *     "expiresInSeconds": 900,
 *     "userId": 1,
 *     "email": "user@example.com",
 *     "fullName": "Example User"
 * }
 *
 * SECURITY:
 *
 * Never include:
 *
 * - Plaintext passwords
 * - Password hashes
 * - JWT signing secrets
 *
 * The frontend will eventually use accessToken
 * for authenticated API requests.
 *
 * Token storage and automatic expiration handling
 * will be implemented during frontend integration.
 */
public record LoginResponse(

    String accessToken,

    String tokenType,

    long expiresInSeconds,

    Long userId,

    String email,

    String fullName

) {
}
