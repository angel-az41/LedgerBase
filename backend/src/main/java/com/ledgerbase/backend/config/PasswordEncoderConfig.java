package com.ledgerbase.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

// ============================================================
// LEDGERBASE — PASSWORD ENCODER CONFIGURATION
// ============================================================

/**
 * Configures secure password hashing for LedgerBase.
 *
 * MILESTONE 23:
 *
 * Authentication and Role-Based Access Control.
 *
 * PURPOSE:
 *
 * 1. Provide a reusable PasswordEncoder.
 * 2. Hash passwords using BCrypt.
 * 3. Verify submitted passwords against stored hashes.
 *
 * SECURITY:
 *
 * Passwords must never be stored in plaintext.
 *
 * BCrypt automatically generates a random salt for
 * every newly encoded password.
 *
 * The encoded hash can be stored in the existing
 * users.password_hash database column.
 *
 * IMPORTANT:
 *
 * This configuration does not yet authenticate HTTP
 * requests or enforce endpoint permissions.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfig {

    // ========================================================
    // BCRYPT WORK FACTOR
    // ========================================================

    /**
     * BCrypt strength (cost factor).
     *
     * A larger number makes password hashing more
     * computationally expensive.
     *
     * We start with 12 for local development.
     *
     * Before production deployment, benchmark password
     * verification and tune the factor as appropriate.
     */
    private static final int BCRYPT_STRENGTH = 12;


    // ========================================================
    // PASSWORD ENCODER BEAN
    // ========================================================

    /**
     * Register a PasswordEncoder in the Spring container.
     *
     * Spring can inject this bean into future services:
     *
     * - AuthenticationService
     * - User registration
     * - Password reset workflows
     *
     * encode(rawPassword):
     * Returns a salted BCrypt hash.
     *
     * matches(rawPassword, storedHash):
     * Verifies a password without decrypting the hash.
     *
     * @return BCrypt-based PasswordEncoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {

        return new BCryptPasswordEncoder(
            BCRYPT_STRENGTH
        );
    }
}
