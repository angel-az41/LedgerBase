package com.ledgerbase.backend.service;

import com.ledgerbase.backend.dto.RegisterRequest;
import com.ledgerbase.backend.dto.RegisterResponse;

import com.ledgerbase.backend.mapper.UserMapper;
import com.ledgerbase.backend.model.User;

import org.springframework.dao.DuplicateKeyException;

import org.springframework.http.HttpStatus;

import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

// ============================================================
// LEDGERBASE — USER REGISTRATION SERVICE
// ============================================================

/**
 * Creates new LedgerBase user accounts.
 *
 * REGISTRATION WORKFLOW:
 *
 * - Receive validated registration information.
 * - Normalize the user's email address.
 * - Normalize the user's display name.
 * - Validate the password's UTF-8 byte length.
 * - Hash the password using the existing encoder.
 * - Insert the user through MyBatis.
 * - Return a safe registration response.
 *
 * SECURITY:
 *
 * - Never store plaintext passwords.
 * - Never log passwords or password hashes.
 * - Never include password hashes in API responses.
 * - Never accept a user ID from the client.
 * - Never grant business permissions during registration.
 *
 * DATABASE INTEGRITY:
 *
 * PostgreSQL enforces case-insensitive email
 * uniqueness using the existing unique index.
 *
 * The database constraint is authoritative,
 * including when concurrent registration requests
 * attempt to use the same email address.
 *
 * AUTHENTICATION:
 *
 * Registration does not create an authenticated
 * session or issue JWT credentials.
 *
 * Newly registered users must sign in through
 * the existing authentication workflow.
 */
@Service
public class RegistrationService {

    // ========================================================
    // PASSWORD POLICY
    // ========================================================

    /**
     * BCrypt processes a maximum of 72 password bytes.
     *
     * Passwords containing multibyte UTF-8 characters
     * may reach this limit before 72 characters.
     *
     * Reject oversized passwords rather than silently
     * accepting truncated credentials.
     */
    private static final int MAX_PASSWORD_BYTES = 72;


    // ========================================================
    // DEPENDENCIES
    // ========================================================

    private final UserMapper userMapper;

    private final PasswordEncoder passwordEncoder;


    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    /**
     * Reuse the application's existing MyBatis mapper
     * and BCrypt-based password encoder.
     *
     * No separate hashing configuration is introduced.
     */
    public RegistrationService(
        UserMapper userMapper,
        PasswordEncoder passwordEncoder
    ) {

        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }


    // ========================================================
    // REGISTER USER
    // ========================================================

    /**
     * Register a new LedgerBase user.
     *
     * INPUT:
     *
     * RegisterRequest containing the user's
     * full name, email address, and password.
     *
     * SUCCESS:
     *
     * Return the generated user ID and safe
     * account identity information.
     *
     * FAILURE:
     *
     * - HTTP 400 for invalid normalized input.
     * - HTTP 409 for a duplicate email address.
     *
     * TRANSACTION:
     *
     * User creation is atomic.
     *
     * Any failure causes the registration
     * transaction to roll back.
     *
     * No business ownership or membership
     * is created by this method.
     *
     * @param request validated registration request
     *
     * @return safe registration response
     */
    @Transactional
    public RegisterResponse register(
        RegisterRequest request
    ) {

        // ====================================================
        // NORMALIZE FULL NAME
        // ====================================================

        /**
         * Remove surrounding whitespace.
         *
         * Preserve the user's intended capitalization
         * and internal spacing.
         */
        String normalizedFullName =
            request.fullName().trim();

        if (
            normalizedFullName.length() < 2 ||
            normalizedFullName.length() > 150
        ) {

            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Full name must contain 2 to 150 characters"
            );
        }


        // ====================================================
        // NORMALIZE EMAIL ADDRESS
        // ====================================================

        /**
         * Apply the same normalization policy used
         * by the existing login implementation.
         *
         * PostgreSQL separately enforces normalized
         * email uniqueness through a unique index.
         */
        String normalizedEmail =
            request.email()
                .trim()
                .toLowerCase(Locale.ROOT);


        // ====================================================
        // VALIDATE NORMALIZED EMAIL
        // ====================================================

        if (
            normalizedEmail.isBlank() ||
            normalizedEmail.length() > 255
        ) {

            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid email address"
            );
        }


        // ====================================================
        // VALIDATE PASSWORD BYTE LENGTH
        // ====================================================

        /**
         * Do not trim or lowercase passwords.
         *
         * Passwords must be preserved exactly
         * as entered by the user.
         *
         * RegisterRequest already validates the
         * minimum character length.
         *
         * This additional check enforces the BCrypt
         * limit using actual UTF-8 bytes.
         */
        String rawPassword =
            request.password();

        int passwordByteLength =
            rawPassword
                .getBytes(StandardCharsets.UTF_8)
                .length;

        if (
            passwordByteLength > MAX_PASSWORD_BYTES
        ) {

            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Password must not exceed 72 UTF-8 bytes"
            );
        }


        // ====================================================
        // HASH PASSWORD
        // ====================================================

        /**
         * Use the existing application's PasswordEncoder.
         *
         * Never persist rawPassword directly.
         *
         * Never include the resulting hash in the
         * registration response.
         */
        String passwordHash =
            passwordEncoder.encode(rawPassword);


        // ====================================================
        // PREPARE NEW USER
        // ====================================================

        User newUser = new User();

        newUser.setEmail(normalizedEmail);

        newUser.setFullName(normalizedFullName);

        newUser.setPasswordHash(passwordHash);


        // ====================================================
        // INSERT USER INTO POSTGRESQL
        // ====================================================

        /**
         * PostgreSQL enforces normalized email
         * uniqueness.
         *
         * Do not rely on checking for an existing
         * user before inserting.
         *
         * A pre-insert check cannot safely prevent
         * concurrent duplicate registrations.
         */
        try {

            userMapper.insertUser(newUser);

        } catch (DuplicateKeyException exception) {

            // ------------------------------------------------
            // REJECT DUPLICATE EMAIL
            // ------------------------------------------------

            /**
             * Both the original email uniqueness
             * constraint and the normalized email
             * unique index can reject duplicates.
             *
             * A duplicate insertion is returned as
             * HTTP 409 Conflict.
             *
             * Do not expose SQL exception details.
             */
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Unable to register with this email address",
                exception
            );
        }


        // ====================================================
        // VERIFY GENERATED USER ID
        // ====================================================

        /**
         * The inserted user should have received
         * its PostgreSQL-generated primary key.
         *
         * Treat a missing ID as an application error.
         *
         * The transaction will roll back if this
         * invariant is violated.
         */
        if (newUser.getId() == null) {

            throw new IllegalStateException(
                "User registration did not return a generated ID"
            );
        }


        // ====================================================
        // RETURN SAFE REGISTRATION RESPONSE
        // ====================================================

        /**
         * Return only the new user's public identity.
         *
         * No password, password hash, JWT, refresh
         * credential, or business role is returned.
         */
        return new RegisterResponse(
            newUser.getId(),
            newUser.getEmail(),
            newUser.getFullName()
        );
    }
}
