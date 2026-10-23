package com.ledgerbase.backend.config;

import org.junit.jupiter.api.Test;

import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — PASSWORD ENCODER UNIT TESTS
// ============================================================

/**
 * Verifies BCrypt password hashing and comparison.
 *
 * These tests:
 *
 * - Do not use real user credentials.
 * - Do not connect to PostgreSQL.
 * - Do not modify the development database.
 * - Do not require a running Spring Boot server.
 */
class PasswordEncoderConfigTest {

    // ========================================================
    // TEST 1 — HASH AND VERIFY PASSWORD
    // ========================================================

    @Test
    void shouldEncodeAndVerifyPassword() {

        PasswordEncoder encoder =
            new PasswordEncoderConfig().passwordEncoder();

        // Test-only password; never use real credentials.
        String rawPassword = "ExampleOnly@Test123!";

        String encodedPassword =
            encoder.encode(rawPassword);

        // A stored hash must differ from the raw password.
        assertNotEquals(
            rawPassword,
            encodedPassword
        );

        // The correct password must match.
        assertTrue(
            encoder.matches(
                rawPassword,
                encodedPassword
            )
        );

        // An incorrect password must not match.
        assertFalse(
            encoder.matches(
                "IncorrectPassword123!",
                encodedPassword
            )
        );
    }


    // ========================================================
    // TEST 2 — RANDOM SALT
    // ========================================================

    @Test
    void shouldGenerateDifferentHashesForSamePassword() {

        PasswordEncoder encoder =
            new PasswordEncoderConfig().passwordEncoder();

        String rawPassword = "ExampleOnly@Test123!";

        String firstHash = encoder.encode(rawPassword);

        String secondHash = encoder.encode(rawPassword);

        // BCrypt uses a randomly generated salt.
        // Therefore, the two hashes should differ.
        assertNotEquals(
            firstHash,
            secondHash
        );

        // Both hashes must still verify successfully.
        assertTrue(
            encoder.matches(rawPassword, firstHash)
        );

        assertTrue(
            encoder.matches(rawPassword, secondHash)
        );
    }
}
