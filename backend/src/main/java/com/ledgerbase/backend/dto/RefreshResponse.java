package com.ledgerbase.backend.dto;

// ============================================================
// LEDGERBASE — ACCESS TOKEN REFRESH RESPONSE
// ============================================================

/**
 * Safe response returned after successfully rotating
 * a refresh token.
 *
 * FIELDS:
 *
 * accessToken:
 * Newly signed short-lived access JWT.
 *
 * tokenType:
 * Authentication scheme, normally "Bearer".
 *
 * expiresInSeconds:
 * Remaining JWT lifetime in seconds.
 *
 * SECURITY:
 *
 * The refresh token is never included here.
 *
 * The browser receives the replacement refresh
 * credential through an HttpOnly cookie instead.
 *
 * The frontend should keep the access JWT in memory,
 * not in localStorage or sessionStorage.
 */
public record RefreshResponse(

    String accessToken,

    String tokenType,

    long expiresInSeconds

) {
}
