package com.ledgerbase.backend.model;

import java.time.OffsetDateTime;
import java.util.UUID;

// ============================================================
// LEDGERBASE — REFRESH TOKEN PERSISTENCE MODEL
// ============================================================

/**
 * Represents a stored refresh token record.
 *
 * DATABASE TABLE:
 *
 * auth_refresh_tokens
 *
 * IMPORTANT:
 *
 * This model contains the HASH of a refresh token,
 * not the original refresh token itself.
 *
 * The backend will:
 *
 * 1. Generate a cryptographically random token.
 * 2. Hash it using SHA-256.
 * 3. Store only the hash in PostgreSQL.
 * 4. Send the original token through an HttpOnly cookie.
 *
 * When a token is used successfully, consumedAt
 * is populated and a replacement token is issued.
 *
 * Keeping consumed token records also allows the
 * backend to detect replay attempts.
 */
public record AuthRefreshToken(

    // SHA-256 hash represented as lowercase hex.
    String tokenHash,

    // Authentication session associated with the token.
    UUID sessionId,

    // Timestamp when this token was issued.
    OffsetDateTime issuedAt,

    // Null until this token has been consumed.
    OffsetDateTime consumedAt

) {
}
