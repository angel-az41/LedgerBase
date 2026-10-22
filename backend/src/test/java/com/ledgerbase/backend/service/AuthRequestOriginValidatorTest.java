package com.ledgerbase.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — REQUEST ORIGIN VALIDATION TESTS
// ============================================================

/**
 * Unit tests for validating browser request origins.
 *
 * These tests do not require Spring Boot
 * or a PostgreSQL connection.
 */
class AuthRequestOriginValidatorTest {

    // ========================================================
    // TEST VALIDATOR
    // ========================================================

    private final AuthRequestOriginValidator validator =
        new AuthRequestOriginValidator(
            "http://localhost:5173,http://localhost:8080"
        );


    // ========================================================
    // TEST — TRUSTED REACT ORIGIN
    // ========================================================

    @Test
    void shouldAllowTrustedReactOrigin() {

        assertTrue(
            validator.isTrustedOrigin(
                "http://localhost:5173"
            )
        );
    }


    // ========================================================
    // TEST — TRUSTED BACKEND ORIGIN
    // ========================================================

    @Test
    void shouldAllowTrustedBackendOrigin() {

        assertTrue(
            validator.isTrustedOrigin(
                "http://localhost:8080"
            )
        );
    }


    // ========================================================
    // TEST — UNTRUSTED ORIGIN
    // ========================================================

    @Test
    void shouldRejectUntrustedOrigin() {

        assertFalse(
            validator.isTrustedOrigin(
                "https://attacker.example"
            )
        );
    }


    // ========================================================
    // TEST — SIMILAR-LOOKING DOMAIN
    // ========================================================

    @Test
    void shouldRejectOriginPrefixAttack() {

        assertFalse(
            validator.isTrustedOrigin(
                "http://localhost:5173.attacker.example"
            )
        );
    }


    // ========================================================
    // TEST — DIFFERENT PORT
    // ========================================================

    @Test
    void shouldRejectDifferentPort() {

        assertFalse(
            validator.isTrustedOrigin(
                "http://localhost:5174"
            )
        );
    }


    // ========================================================
    // TEST — DIFFERENT SCHEME
    // ========================================================

    @Test
    void shouldRejectDifferentScheme() {

        assertFalse(
            validator.isTrustedOrigin(
                "https://localhost:5173"
            )
        );
    }


    // ========================================================
    // TEST — MISSING OR NULL ORIGIN
    // ========================================================

    @Test
    void shouldRejectMissingOrigin() {

        assertFalse(
            validator.isTrustedOrigin(null)
        );

        assertFalse(
            validator.isTrustedOrigin("")
        );

        assertFalse(
            validator.isTrustedOrigin("null")
        );
    }


    // ========================================================
    // TEST — INVALID CONFIGURATION
    // ========================================================

    @Test
    void shouldRejectUnsafeOriginConfiguration() {

        assertThrows(
            IllegalArgumentException.class,
            () -> new AuthRequestOriginValidator("*")
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new AuthRequestOriginValidator(
                "http://localhost:5173/path"
            )
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new AuthRequestOriginValidator("")
        );
    }
}
