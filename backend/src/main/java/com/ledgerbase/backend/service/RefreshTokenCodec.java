package com.ledgerbase.backend.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

// ============================================================
// LEDGERBASE — REFRESH TOKEN CODEC
// ============================================================

/**
 * Generates secure refresh tokens and computes
 * their database-safe SHA-256 hashes.
 *
 * TOKEN DESIGN:
 *
 * - 32 cryptographically random bytes.
 * - 256 bits of randomness.
 * - URL-safe Base64 encoding.
 * - No Base64 padding.
 * - 43-character encoded token.
 *
 * DATABASE STORAGE:
 *
 * Only the SHA-256 hash is stored.
 *
 * The original refresh token will eventually be
 * returned to the browser in an HttpOnly cookie.
 *
 * SECURITY:
 *
 * - Never log refresh tokens.
 * - Never place refresh tokens in URLs.
 * - Never store plaintext refresh tokens.
 * - Never generate tokens using java.util.Random.
 *
 * This class does not create or validate sessions.
 * Session lifecycle management belongs to a
 * separate transactional service.
 */
@Component
public final class RefreshTokenCodec {

    // ========================================================
    // TOKEN CONFIGURATION
    // ========================================================

    private static final int TOKEN_BYTE_LENGTH = 32;

    /**
     * A 32-byte value encoded using Base64 URL encoding
     * without padding is exactly 43 characters long.
     *
     * Allow only the URL-safe Base64 alphabet.
     */
    private static final Pattern TOKEN_FORMAT =
        Pattern.compile("^[A-Za-z0-9_-]{43}$");


    // ========================================================
    // SECURE RANDOM GENERATOR
    // ========================================================

    /**
     * SecureRandom is designed for generating values
     * that cannot feasibly be predicted.
     */
    private final SecureRandom secureRandom =
        new SecureRandom();


    // ========================================================
    // GENERATE REFRESH TOKEN
    // ========================================================

    /**
     * Generate a new random refresh token.
     *
     * Each invocation generates fresh random bytes.
     *
     * @return URL-safe opaque refresh token
     */
    public String generateToken() {

        byte[] randomBytes =
            new byte[TOKEN_BYTE_LENGTH];

        try {

            // Generate cryptographically secure bytes.
            secureRandom.nextBytes(randomBytes);

            // Encode for safe use as a cookie value.
            return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        } finally {

            // Clear the temporary byte array.
            //
            // The returned String still contains the
            // credential and must be handled carefully.
            Arrays.fill(randomBytes, (byte) 0);
        }
    }


    // ========================================================
    // HASH REFRESH TOKEN
    // ========================================================

    /**
     * Compute the SHA-256 hash of a refresh token.
     *
     * The result is a 64-character lowercase hex
     * string suitable for auth_refresh_tokens.token_hash.
     *
     * We validate the token format before hashing.
     *
     * The caller must translate invalid input into
     * a generic authentication failure.
     *
     * @param rawToken original opaque refresh token
     * @return SHA-256 hash as lowercase hexadecimal
     */
    public String hashToken(String rawToken) {

        // ----------------------------------------------------
        // VALIDATE TOKEN FORMAT
        // ----------------------------------------------------

        if (
            rawToken == null ||
            !TOKEN_FORMAT.matcher(rawToken).matches()
        ) {
            throw new IllegalArgumentException(
                "Invalid refresh token format"
            );
        }


        // ----------------------------------------------------
        // CALCULATE SHA-256 HASH
        // ----------------------------------------------------

        try {

            MessageDigest digest =
                MessageDigest.getInstance("SHA-256");

            byte[] hashedBytes = digest.digest(
                rawToken.getBytes(StandardCharsets.US_ASCII)
            );

            // Convert 32 bytes into 64 lowercase hex
            // characters for PostgreSQL storage.
            return HexFormat.of()
                .formatHex(hashedBytes);

        } catch (NoSuchAlgorithmException exception) {

            // Every standard Java implementation
            // provides SHA-256.

            throw new IllegalStateException(
                "SHA-256 is unavailable",
                exception
            );
        }
    }
}
