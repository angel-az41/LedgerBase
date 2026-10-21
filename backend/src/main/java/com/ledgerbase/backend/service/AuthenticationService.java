package com.ledgerbase.backend.service;

import com.ledgerbase.backend.dto.AuthenticatedUser;
import com.ledgerbase.backend.exception.InvalidCredentialsException;
import com.ledgerbase.backend.mapper.UserMapper;
import com.ledgerbase.backend.model.User;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

// ============================================================
// LEDGERBASE — AUTHENTICATION SERVICE
// ============================================================

/**
 * Validates user login credentials.
 *
 * AUTHENTICATION WORKFLOW:
 *
 * 1. Receive an email address and password.
 * 2. Validate the supplied input.
 * 3. Retrieve the stored user through MyBatis.
 * 4. Compare the password against its stored hash.
 * 5. Return a safe authenticated user identity.
 *
 * SECURITY:
 *
 * - Never compare plaintext passwords directly.
 * - Never return password hashes to the frontend.
 * - Never log submitted passwords.
 * - Never trust user IDs supplied by login requests.
 * - Use the same error for invalid credentials.
 *
 * JWT token generation will be added separately.
 *
 * This service does not yet authorize HTTP requests.
 */
@Service
public class AuthenticationService {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    private final UserMapper userMapper;

    private final PasswordEncoder passwordEncoder;

    private final String dummyPasswordHash;


    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    /**
     * Spring injects the UserMapper and PasswordEncoder.
     *
     * The dummy BCrypt hash is used when a requested
     * user does not exist or has no stored hash.
     *
     * This allows password verification to perform
     * expensive hash comparison work even when a
     * matching user record is unavailable.
     *
     * It reduces a simple timing difference between
     * known and unknown email addresses, although
     * it does not eliminate every timing side channel.
     *
     * The dummy password is not a login credential.
     */
    public AuthenticationService(
        UserMapper userMapper,
        PasswordEncoder passwordEncoder
    ) {

        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;

        this.dummyPasswordHash = passwordEncoder.encode(
            UUID.randomUUID().toString()
        );
    }


    // ========================================================
    // 1. AUTHENTICATE USER
    // ========================================================

    /**
     * Authenticate a user using email and password.
     *
     * @param email submitted email address
     * @param rawPassword submitted plaintext password
     *
     * @return authenticated identity without credentials
     *
     * @throws InvalidCredentialsException
     *         if authentication fails
     */
    public AuthenticatedUser authenticate(
        String email,
        String rawPassword
    ) {

        // ====================================================
        // VALIDATE LOGIN INPUT
        // ====================================================

        if (
            email == null
            || email.isBlank()
            || rawPassword == null
            || rawPassword.isBlank()
        ) {

            throw new InvalidCredentialsException();
        }


        // ====================================================
        // LOOK UP USER
        // ====================================================

        // Trim surrounding whitespace from email.
        //
        // Do not change email case here because the
        // current database uses exact email matching.
        //
        // We will establish consistent case-insensitive
        // lookup and uniqueness rules separately.

        String normalizedEmail = email.trim();

        User user = userMapper.findByEmail(
            normalizedEmail
        );


        // ====================================================
        // SELECT HASH FOR VERIFICATION
        // ====================================================

        // Unknown users use the dummy hash.
        //
        // Existing users use the database-stored hash.
        //
        // This does not accept the legacy DEV_ placeholder:
        // BCrypt verification will not consider it valid.

        String hashToVerify =
            user != null && user.getPasswordHash() != null
                ? user.getPasswordHash()
                : dummyPasswordHash;


        // ====================================================
        // VERIFY PASSWORD
        // ====================================================

        boolean passwordMatches =
            passwordEncoder.matches(
                rawPassword,
                hashToVerify
            );


        // ====================================================
        // REJECT INVALID CREDENTIALS
        // ====================================================

        // Use the same exception for:
        //
        // - Unknown email
        // - Incorrect password
        // - Invalid legacy password hash

        if (user == null || !passwordMatches) {

            throw new InvalidCredentialsException();
        }


        // ====================================================
        // RETURN SAFE AUTHENTICATED IDENTITY
        // ====================================================

        // Never return the complete User model,
        // which contains passwordHash.

        return new AuthenticatedUser(
            user.getId(),
            user.getEmail(),
            user.getFullName()
        );
    }
}
