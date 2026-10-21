package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.AuthenticatedUser;
import com.ledgerbase.backend.exception.InvalidCredentialsException;
import com.ledgerbase.backend.service.AuthenticationService;
import com.ledgerbase.backend.service.JwtTokenService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// ============================================================
// LEDGERBASE — AUTHENTICATION CONTROLLER TESTS
// ============================================================

/**
 * Tests the HTTP behavior of the authentication API.
 *
 * ENDPOINT:
 *
 * POST /api/v1/auth/login
 *
 * TEST ARCHITECTURE:
 *
 * MockMvc
 *    |
 *    v
 * AuthController
 *    |
 *    +---- Mock AuthenticationService
 *    |
 *    +---- Mock JwtTokenService
 *
 * These are focused controller tests.
 *
 * They verify:
 *
 * - Request body validation.
 * - HTTP response codes.
 * - Successful login response structure.
 * - Authentication failure handling.
 * - JWT service invocation.
 * - Protection against returning credentials.
 *
 * No PostgreSQL connection is required.
 * No Spring Boot application context is started.
 * No real passwords or JWT signing secrets are used.
 */
class AuthControllerTest {

    // ========================================================
    // TEST CONSTANTS
    // ========================================================

    private static final String LOGIN_URL =
        "/api/v1/auth/login";

    private static final String TEST_EMAIL =
        "test@example.com";

    private static final String TEST_PASSWORD =
        "ExampleOnly@Test123!";

    // Test-only token placeholder.
    //
    // This is not a valid signed JWT.

    private static final String TEST_ACCESS_TOKEN =
        "test.header.signature";


    // ========================================================
    // MOCKED DEPENDENCIES
    // ========================================================

    private AuthenticationService authenticationService;

    private JwtTokenService jwtTokenService;

    private MockMvc mockMvc;


    // ========================================================
    // TEST SETUP
    // ========================================================

    /**
     * Configure an isolated controller before each test.
     *
     * Mockito replaces the authentication and JWT
     * services with controlled test doubles.
     *
     * LocalValidatorFactoryBean enables the Jakarta
     * validation rules declared in LoginRequest.
     *
     * MockMvc executes requests through Spring MVC
     * without starting an HTTP server.
     */
    @BeforeEach
    void setUp() {

        // ----------------------------------------------------
        // CREATE SERVICE MOCKS
        // ----------------------------------------------------

        authenticationService =
            mock(AuthenticationService.class);

        jwtTokenService =
            mock(JwtTokenService.class);


        // ----------------------------------------------------
        // CONFIGURE REQUEST VALIDATION
        // ----------------------------------------------------

        LocalValidatorFactoryBean validator =
            new LocalValidatorFactoryBean();

        validator.afterPropertiesSet();


        // ----------------------------------------------------
        // CREATE CONTROLLER
        // ----------------------------------------------------

        AuthController controller =
            new AuthController(
                authenticationService,
                jwtTokenService
            );


        // ----------------------------------------------------
        // CONFIGURE SPRING MVC TEST CLIENT
        // ----------------------------------------------------

        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setValidator(validator)
            .build();
    }


    // ========================================================
    // TEST 1 — SUCCESSFUL LOGIN
    // ========================================================

    /**
     * Correct credentials must produce HTTP 200.
     *
     * The response should contain:
     *
     * - Access token
     * - Bearer token type
     * - Token expiration
     * - Authenticated user identity
     *
     * Passwords and password hashes must not appear.
     */
    @Test
    void shouldReturnAccessTokenForValidCredentials()
            throws Exception {

        // ----------------------------------------------------
        // PREPARE AUTHENTICATED IDENTITY
        // ----------------------------------------------------

        AuthenticatedUser user =
            new AuthenticatedUser(
                1L,
                TEST_EMAIL,
                "Test User"
            );


        // ----------------------------------------------------
        // CONFIGURE SERVICE BEHAVIOR
        // ----------------------------------------------------

        when(
            authenticationService.authenticate(
                TEST_EMAIL,
                TEST_PASSWORD
            )
        ).thenReturn(user);

        when(
            jwtTokenService.generateAccessToken(user)
        ).thenReturn(TEST_ACCESS_TOKEN);


        // ----------------------------------------------------
        // EXECUTE LOGIN REQUEST
        // ----------------------------------------------------

        mockMvc.perform(
            post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "test@example.com",
                      "password": "ExampleOnly@Test123!"
                    }
                    """)
        )

        // ----------------------------------------------------
        // VERIFY SUCCESS RESPONSE
        // ----------------------------------------------------

        .andExpect(status().isOk())

        .andExpect(
            jsonPath("$.accessToken")
                .value(TEST_ACCESS_TOKEN)
        )

        .andExpect(
            jsonPath("$.tokenType")
                .value("Bearer")
        )

        .andExpect(
            jsonPath("$.expiresInSeconds")
                .value(900)
        )

        .andExpect(
            jsonPath("$.userId")
                .value(1)
        )

        .andExpect(
            jsonPath("$.email")
                .value(TEST_EMAIL)
        )

        .andExpect(
            jsonPath("$.fullName")
                .value("Test User")
        )


        // ----------------------------------------------------
        // ENSURE CREDENTIALS ARE NOT RETURNED
        // ----------------------------------------------------

        .andExpect(
            jsonPath("$.password").doesNotExist()
        )

        .andExpect(
            jsonPath("$.passwordHash").doesNotExist()
        )


        // ----------------------------------------------------
        // PREVENT TOKEN RESPONSE CACHING
        // ----------------------------------------------------

        .andExpect(
            header().string("Cache-Control", "no-store")
        );


        // ----------------------------------------------------
        // VERIFY SERVICE CALLS
        // ----------------------------------------------------

        verify(authenticationService).authenticate(
            TEST_EMAIL,
            TEST_PASSWORD
        );

        verify(jwtTokenService)
            .generateAccessToken(user);
    }


    // ========================================================
    // TEST 2 — INCORRECT CREDENTIALS
    // ========================================================

    /**
     * Failed authentication must return HTTP 401.
     *
     * The response must use a generic error message.
     *
     * JWT generation must not be attempted.
     */
    @Test
    void shouldReturnUnauthorizedForInvalidCredentials()
            throws Exception {

        // ----------------------------------------------------
        // CONFIGURE AUTHENTICATION FAILURE
        // ----------------------------------------------------

        when(
            authenticationService.authenticate(
                TEST_EMAIL,
                "IncorrectPassword123!"
            )
        ).thenThrow(
            new InvalidCredentialsException()
        );


        // ----------------------------------------------------
        // EXECUTE INVALID LOGIN
        // ----------------------------------------------------

        mockMvc.perform(
            post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "test@example.com",
                      "password": "IncorrectPassword123!"
                    }
                    """)
        )

        .andExpect(status().isUnauthorized())

        .andExpect(
            jsonPath("$.error")
                .value("Invalid email or password")
        )

        .andExpect(
            header().string("Cache-Control", "no-store")
        );


        // ----------------------------------------------------
        // VERIFY NO TOKEN WAS GENERATED
        // ----------------------------------------------------

        verifyNoInteractions(jwtTokenService);
    }


    // ========================================================
    // TEST 3 — INVALID EMAIL FORMAT
    // ========================================================

    /**
     * An invalid email address must be rejected
     * by LoginRequest validation.
     *
     * AuthenticationService must not be invoked.
     */
    @Test
    void shouldRejectInvalidEmailFormat()
            throws Exception {

        mockMvc.perform(
            post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "invalid-email",
                      "password": "ExampleOnly@Test123!"
                    }
                    """)
        )

        .andExpect(status().isBadRequest());


        // ----------------------------------------------------
        // VERIFY INPUT WAS REJECTED EARLY
        // ----------------------------------------------------

        verifyNoInteractions(
            authenticationService,
            jwtTokenService
        );
    }


    // ========================================================
    // TEST 4 — BLANK PASSWORD
    // ========================================================

    /**
     * Empty passwords must fail Jakarta Validation.
     *
     * The request must not reach the authentication
     * service or token generator.
     */
    @Test
    void shouldRejectBlankPassword()
            throws Exception {

        mockMvc.perform(
            post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "test@example.com",
                      "password": ""
                    }
                    """)
        )

        .andExpect(status().isBadRequest());


        // ----------------------------------------------------
        // VERIFY NO AUTHENTICATION ATTEMPT
        // ----------------------------------------------------

        verifyNoInteractions(
            authenticationService,
            jwtTokenService
        );
    }


    // ========================================================
    // TEST 5 — MALFORMED JSON
    // ========================================================

    /**
     * Malformed JSON must produce HTTP 400.
     *
     * Spring MVC should reject the invalid request
     * body before invoking the controller.
     */
    @Test
    void shouldRejectMalformedJson()
            throws Exception {

        mockMvc.perform(
            post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "test@example.com",
                      "password":
                    """)
        )

        .andExpect(status().isBadRequest());


        // ----------------------------------------------------
        // VERIFY NO BUSINESS LOGIC EXECUTED
        // ----------------------------------------------------

        verifyNoInteractions(
            authenticationService,
            jwtTokenService
        );
    }
}
