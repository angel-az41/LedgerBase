package com.ledgerbase.backend.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — REFRESH TOKEN CODEC TESTS
// ============================================================

/**
 * Unit tests for generating and hashing refresh tokens.
 *
 * These tests do not require:
 *
 * - Spring Boot application context
 * - PostgreSQL
 * - JWT signing keys
 * - HTTP requests
 *
 * They test the token format, randomness behavior,
 * hashing consistency, and invalid input handling.
 */
class RefreshTokenCodecTest {

    private final RefreshTokenCodec codec =
        new RefreshTokenCodec();


    // ========================================================
    // TEST — TOKEN FORMAT
    // ========================================================

    /**
     * A 32-byte random token becomes a 43-character
     * Base64 URL-safe string without padding.
     */
    @Test
    void shouldGenerateUrlSafeRefreshToken() {

        String token = codec.generateToken();

        assertNotNull(token);

        assertEquals(43, token.length());

        assertTrue(
            token.matches("^[A-Za-z0-9_-]{43}$")
        );

        assertFalse(token.contains("="));
    }


    // ========================================================
    // TEST — TOKEN UNIQUENESS
    // ========================================================

    /**
     * Verify that repeated generation produces
     * distinct values.
     *
     * This is a basic functional check, not a
     * statistical proof of cryptographic security.
     */
    @Test
    void shouldGenerateDifferentTokens() {

        Set<String> generatedTokens =
            new HashSet<>();

        for (int index = 0; index < 100; index++) {

            String token = codec.generateToken();

            assertTrue(
                generatedTokens.add(token),
                "Duplicate refresh token generated"
            );
        }

        assertEquals(100, generatedTokens.size());
    }


    // ========================================================
    // TEST — HASH FORMAT
    // ========================================================

    /**
     * SHA-256 must produce 64 lowercase
     * hexadecimal characters.
     */
    @Test
    void shouldGenerateSha256Hash() {

        String token = codec.generateToken();

        String hash = codec.hashToken(token);

        assertNotNull(hash);

        assertEquals(64, hash.length());

        assertTrue(
            hash.matches("^[0-9a-f]{64}$")
        );

        assertNotEquals(token, hash);
    }


    // ========================================================
    // TEST — DETERMINISTIC HASHING
    // ========================================================

    /**
     * Hashing the same token twice must produce
     * the same database lookup value.
     *
     * Different tokens should produce different hashes.
     */
    @Test
    void shouldHashTokensConsistently() {

        String firstToken = codec.generateToken();

        String secondToken = codec.generateToken();

        String firstHash =
            codec.hashToken(firstToken);

        String repeatedHash =
            codec.hashToken(firstToken);

        String secondHash =
            codec.hashToken(secondToken);

        assertEquals(firstHash, repeatedHash);

        assertNotEquals(firstHash, secondHash);
    }


    // ========================================================
    // TEST — INVALID INPUT
    // ========================================================

    /**
     * Reject values that do not match the expected
     * refresh-token format.
     */
    @Test
    void shouldRejectInvalidRefreshTokens() {

        assertThrows(
            IllegalArgumentException.class,
            () -> codec.hashToken(null)
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> codec.hashToken("")
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> codec.hashToken("invalid-token")
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> codec.hashToken(
                "!" + "a".repeat(42)
            )
        );
    }
}
