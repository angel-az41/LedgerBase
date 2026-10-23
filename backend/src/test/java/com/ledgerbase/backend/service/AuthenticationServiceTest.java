package com.ledgerbase.backend.service;

import com.ledgerbase.backend.dto.AuthenticatedUser;
import com.ledgerbase.backend.exception.InvalidCredentialsException;
import com.ledgerbase.backend.mapper.UserMapper;
import com.ledgerbase.backend.model.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — AUTHENTICATION SERVICE UNIT TESTS
// ============================================================

/**
 * Tests credential validation in AuthenticationService.
 *
 * PURPOSE:
 *
 * Verify that LedgerBase authenticates valid users
 * and rejects invalid login attempts.
 *
 * TEST ARCHITECTURE:
 *
 * AuthenticationService
 *         |
 *         +---- Mock UserMapper
 *         |
 *         +---- Real BCrypt PasswordEncoder
 *
 * Mockito replaces the database mapper.
 *
 * We use a real BCrypt implementation to test
 * password hashing and verification behavior.
 *
 * No Spring Boot context or PostgreSQL database
 * is required for these unit tests.
 */
class AuthenticationServiceTest {

    // ========================================================
    // TEST DEPENDENCIES
    // ========================================================

    private UserMapper userMapper;

    private PasswordEncoder passwordEncoder;

    private AuthenticationService authenticationService;


    // ========================================================
    // SETUP
    // ========================================================

    /**
     * Create fresh dependencies before every test.
     *
     * Mockito creates an isolated UserMapper mock.
     *
     * BCrypt strength 4 is used ONLY in these unit tests
     * to reduce execution time.
     *
     * The production PasswordEncoderConfig continues
     * to use BCrypt strength 12.
     */
    @BeforeEach
    void setUp() {

        userMapper = mock(UserMapper.class);

        passwordEncoder = new BCryptPasswordEncoder(4);

        authenticationService = new AuthenticationService(
            userMapper,
            passwordEncoder
        );
    }


    // ========================================================
    // TEST 1 — VALID LOGIN
    // ========================================================

    /**
     * Correct credentials must return the authenticated
     * user's identity without exposing password hashes.
     */
    @Test
    void shouldAuthenticateWithCorrectCredentials() {

        // Test-only credentials.
        String email = "test@example.com";
        String rawPassword = "ExampleOnly@Test123!";

        User user = createTestUser(
            email,
            rawPassword
        );

        when(userMapper.findByEmail(email))
            .thenReturn(user);

        // Authenticate with the correct password.
        AuthenticatedUser result =
            authenticationService.authenticate(
                email,
                rawPassword
            );

        // Confirm the returned identity.
        assertNotNull(result);

        assertEquals(1L, result.id());

        assertEquals(email, result.email());

        assertEquals("Test User", result.fullName());

        // Ensure the mapper was called for this email.
        verify(userMapper).findByEmail(email);
    }


    // ========================================================
    // TEST 2 — INCORRECT PASSWORD
    // ========================================================

    /**
     * An existing email with the wrong password
     * must not authenticate.
     */
    @Test
    void shouldRejectIncorrectPassword() {

        String email = "test@example.com";

        User user = createTestUser(
            email,
            "CorrectPassword123!"
        );

        when(userMapper.findByEmail(email))
            .thenReturn(user);

        InvalidCredentialsException exception =
            assertThrows(
                InvalidCredentialsException.class,
                () -> authenticationService.authenticate(
                    email,
                    "WrongPassword123!"
                )
            );

        assertEquals(
            "Invalid email or password",
            exception.getMessage()
        );
    }


    // ========================================================
    // TEST 3 — UNKNOWN EMAIL
    // ========================================================

    /**
     * A nonexistent user must receive the same
     * generic error as an incorrect password.
     *
     * This avoids revealing whether an account exists.
     */
    @Test
    void shouldRejectUnknownEmail() {

        String email = "unknown@example.com";

        when(userMapper.findByEmail(email))
            .thenReturn(null);

        InvalidCredentialsException exception =
            assertThrows(
                InvalidCredentialsException.class,
                () -> authenticationService.authenticate(
                    email,
                    "SomePassword123!"
                )
            );

        assertEquals(
            "Invalid email or password",
            exception.getMessage()
        );
    }


    // ========================================================
    // TEST 4 — LEGACY DEVELOPMENT HASH
    // ========================================================

    /**
     * Existing development password placeholders
     * must never be accepted as valid credentials.
     *
     * The real development user currently has a
     * password_hash value beginning with DEV_.
     *
     * This test verifies that such a placeholder
     * cannot authenticate using BCrypt.
     */
    @Test
    void shouldRejectLegacyDevelopmentHash() {

        String email = "legacy@example.com";

        User user = new User();

        user.setId(1L);
        user.setEmail(email);
        user.setFullName("Legacy User");

        // Placeholder, not a valid BCrypt hash.
        user.setPasswordHash("DEV_PLACEHOLDER");

        when(userMapper.findByEmail(email))
            .thenReturn(user);

        assertThrows(
            InvalidCredentialsException.class,
            () -> authenticationService.authenticate(
                email,
                "AnyPassword123!"
            )
        );
    }


    // ========================================================
    // TEST 5 — BLANK CREDENTIALS
    // ========================================================

    /**
     * Blank credentials must be rejected before
     * attempting a database lookup.
     */
    @Test
    void shouldRejectBlankCredentials() {

        assertThrows(
            InvalidCredentialsException.class,
            () -> authenticationService.authenticate(
                " ",
                "ExampleOnly@Test123!"
            )
        );

        assertThrows(
            InvalidCredentialsException.class,
            () -> authenticationService.authenticate(
                "test@example.com",
                " "
            )
        );

        // Invalid input must not query PostgreSQL.
        verifyNoInteractions(userMapper);
    }


    // ========================================================
    // TEST 6 — EMAIL WHITESPACE NORMALIZATION
    // ========================================================

    /**
     * Surrounding whitespace in the submitted email
     * should be removed before database lookup.
     *
     * Email case normalization is intentionally
     * not implemented at this stage.
     */
    @Test
    void shouldTrimEmailBeforeLookup() {

        String email = "test@example.com";
        String rawPassword = "ExampleOnly@Test123!";

        User user = createTestUser(
            email,
            rawPassword
        );

        when(userMapper.findByEmail(email))
            .thenReturn(user);

        AuthenticatedUser result =
            authenticationService.authenticate(
                "  test@example.com  ",
                rawPassword
            );

        assertEquals(email, result.email());

        verify(userMapper).findByEmail(email);
    }


    // ========================================================
    // TEST HELPER — CREATE AN IN-MEMORY USER
    // ========================================================

    /**
     * Build a test user using an actual BCrypt hash.
     *
     * This user exists only in memory.
     *
     * The method never inserts any records into
     * the development or integration test database.
     */
    private User createTestUser(
        String email,
        String rawPassword
    ) {

        User user = new User();

        user.setId(1L);

        user.setEmail(email);

        user.setFullName("Test User");

        user.setPasswordHash(
            passwordEncoder.encode(rawPassword)
        );

        return user;
    }
}
