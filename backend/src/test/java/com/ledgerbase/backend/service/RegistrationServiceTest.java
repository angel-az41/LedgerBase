package com.ledgerbase.backend.service;

import com.ledgerbase.backend.dto.RegisterRequest;
import com.ledgerbase.backend.dto.RegisterResponse;
import com.ledgerbase.backend.mapper.UserMapper;
import com.ledgerbase.backend.model.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — USER REGISTRATION SERVICE TESTS
// ============================================================

/**
 * Verify user registration behavior without writing
 * records to the development database.
 *
 * TEST COVERAGE:
 *
 * - Successful registration.
 * - Email and name normalization.
 * - Secure BCrypt password hashing.
 * - Duplicate email rejection.
 * - Password UTF-8 byte-length enforcement.
 * - Generated user ID validation.
 *
 * DATABASE:
 *
 * UserMapper is mocked. PostgreSQL integration
 * and case-insensitive uniqueness are tested
 * separately.
 */
class RegistrationServiceTest {

    // ========================================================
    // TEST DEPENDENCIES
    // ========================================================

    private UserMapper userMapper;

    private PasswordEncoder passwordEncoder;

    private RegistrationService registrationService;


    // ========================================================
    // TEST SETUP
    // ========================================================

    @BeforeEach
    void setUp() {

        userMapper = mock(UserMapper.class);

        // Use real BCrypt for password verification.
        //
        // A reduced work factor keeps unit tests fast.
        // Production continues using its configured
        // PasswordEncoder.
        passwordEncoder = new BCryptPasswordEncoder(4);

        registrationService = new RegistrationService(
            userMapper,
            passwordEncoder
        );
    }


    // ========================================================
    // SUCCESSFUL USER REGISTRATION
    // ========================================================

    /**
     * Verify that a new user is persisted with a
     * normalized email and an encoded password.
     *
     * The returned response must never contain
     * the password or its stored hash.
     */
    @Test
    void registersUserWithNormalizedEmailAndHashedPassword() {

        String rawPassword = "SecurePassword123!";

        RegisterRequest request = new RegisterRequest(
            "  Example User  ",
            "  Example@Email.com  ",
            rawPassword
        );

        // Simulate PostgreSQL assigning a generated ID.
        doAnswer(invocation -> {

            User insertedUser = invocation.getArgument(0);

            assertEquals(
                "example@email.com",
                insertedUser.getEmail()
            );

            assertEquals(
                "Example User",
                insertedUser.getFullName()
            );

            assertNotEquals(
                rawPassword,
                insertedUser.getPasswordHash()
            );

            assertTrue(
                passwordEncoder.matches(
                    rawPassword,
                    insertedUser.getPasswordHash()
                )
            );

            insertedUser.setId(101L);

            return null;

        }).when(userMapper).insertUser(any(User.class));


        RegisterResponse response =
            registrationService.register(request);


        assertEquals(101L, response.userId());

        assertEquals(
            "example@email.com",
            response.email()
        );

        assertEquals(
            "Example User",
            response.fullName()
        );

        verify(userMapper, times(1))
            .insertUser(any(User.class));
    }


    // ========================================================
    // DUPLICATE EMAIL
    // ========================================================

    /**
     * Verify that a database uniqueness violation
     * produces HTTP 409 Conflict.
     *
     * The unique PostgreSQL index is authoritative,
     * including for concurrent registration requests.
     */
    @Test
    void rejectsDuplicateEmailWithConflictStatus() {

        RegisterRequest request = new RegisterRequest(
            "Example User",
            "example@email.com",
            "SecurePassword123!"
        );

        doThrow(
            new DuplicateKeyException(
                "Simulated unique email conflict"
            )
        ).when(userMapper).insertUser(any(User.class));


        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> registrationService.register(request)
            );


        assertEquals(
            HttpStatus.CONFLICT,
            exception.getStatusCode()
        );
    }


    // ========================================================
    // PASSWORD BYTE LIMIT
    // ========================================================

    /**
     * Verify that the service rejects passwords
     * exceeding BCrypt's 72-byte input limit.
     *
     * UTF-8 characters may require multiple bytes,
     * even when the character count is below 72.
     */
    @Test
    void rejectsPasswordExceedingSeventyTwoUtf8Bytes() {

        // Each character uses three UTF-8 bytes.
        //
        // 25 characters = 75 bytes.
        String oversizedPassword = "界".repeat(25);

        assertEquals(
            75,
            oversizedPassword
                .getBytes(StandardCharsets.UTF_8)
                .length
        );

        RegisterRequest request = new RegisterRequest(
            "Example User",
            "example@email.com",
            oversizedPassword
        );


        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> registrationService.register(request)
            );


        assertEquals(
            HttpStatus.BAD_REQUEST,
            exception.getStatusCode()
        );

        // No user may be inserted.
        verify(userMapper, never())
            .insertUser(any(User.class));
    }


    // ========================================================
    // MISSING GENERATED USER ID
    // ========================================================

    /**
     * A successful database insertion must return
     * its generated primary key.
     *
     * If MyBatis does not populate the ID, reject
     * the result instead of returning a malformed
     * registration response.
     */
    @Test
    void rejectsRegistrationWithoutGeneratedUserId() {

        RegisterRequest request = new RegisterRequest(
            "Example User",
            "example@email.com",
            "SecurePassword123!"
        );

        // The mock deliberately does not set an ID.
        IllegalStateException exception =
            assertThrows(
                IllegalStateException.class,
                () -> registrationService.register(request)
            );

        assertEquals(
            "User registration did not return a generated ID",
            exception.getMessage()
        );
    }
}
