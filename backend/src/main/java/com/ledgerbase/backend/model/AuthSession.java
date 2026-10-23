package com.ledgerbase.backend.model;

import java.time.OffsetDateTime;
import java.util.UUID;

// ============================================================
// LEDGERBASE — AUTHENTICATION SESSION MODEL
// ============================================================

/**
 * Represents a server-managed authentication session.
 *
 * DATABASE TABLE:
 *
 * auth_sessions
 *
 * Each session belongs to one authenticated user.
 *
 * The session tracks:
 *
 * - When the user logged in.
 * - When the user was last active.
 * - The maximum permitted session lifetime.
 * - Whether the session has been revoked.
 *
 * SECURITY:
 *
 * Session IDs are random UUIDs.
 *
 * Session validity is determined by the backend,
 * not by the frontend's authentication state.
 *
 * The session model does not contain plaintext
 * passwords, refresh tokens, or JWT signing keys.
 *
 * OffsetDateTime preserves timestamp offset
 * information when interacting with PostgreSQL
 * TIMESTAMPTZ columns.
 */
public record AuthSession(

    // Unique database session identifier.
    UUID id,

    // PostgreSQL user who owns the session.
    Long userId,

    // Timestamp when the session was created.
    OffsetDateTime createdAt,

    // Most recent accepted user activity.
    OffsetDateTime lastActivityAt,

    // Absolute expiration regardless of activity.
    OffsetDateTime absoluteExpiresAt,

    // Null until explicitly revoked.
    OffsetDateTime revokedAt

) {
}
