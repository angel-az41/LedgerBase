package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.AuthenticatedUser;
import com.ledgerbase.backend.exception.InvalidCredentialsException;

import com.ledgerbase.backend.service.AuthenticationService;
import com.ledgerbase.backend.service.AuthSessionService;
import com.ledgerbase.backend.service.AuthRequestOriginValidator;
import com.ledgerbase.backend.service.JwtTokenService;
import com.ledgerbase.backend.service.RefreshCookieService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

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
 *    +---- Mock AuthSessionService
 *    |
 *    +---- Mock JwtTokenService
 *    |
 *    +---- Mock RefreshCookieService
 *    |
 *    +---- Real AuthRequestOriginValidator
 *
 * These are focused controller tests.
 *
 * They verify:
 *
 * - Request body validation.
 * - HTTP response codes.
 * - Successful login response structure.
 * - Persistent session creation.
 * - Session-aware JWT generation.
 * - Refresh-cookie service invocation.
 * - Authentication failure handling.
 * - Request-origin validation.
 * - Protection against returning credentials.
 *
 * No PostgreSQL connection is required.
 * No Spring Boot application context is started.
 * No real passwords or JWT signing secrets are used.
 *
 * PostgreSQL persistence, cryptographic signing,
 * and cookie attributes are tested separately.
 */
class AuthControllerTest {

    // ========================================================
    // TEST CONSTANTS
    // ========================================================

    private static final String LOGIN_URL =
        "/api/v1/auth/login";

    private static final String TRUSTED_ORIGIN =
        "http://localhost:5173";

    private static final String TEST_EMAIL =
        "test@example.com";

    private static final String TEST_PASSWORD =
        "ExampleOnly@Test123!";

    /**
     * Test-only access token placeholder.
     *
     * This is not a valid signed JWT.
     */
    private static final String TEST_ACCESS_TOKEN =
        "test.header.signature";

    /**
     * Test-only refresh token placeholder.
     *
     * This is not a real authentication credential.
     */
    private static final String TEST_REFRESH_TOKEN =
        "A".repeat(43);


    // ========================================================
    // MOCKED DEPENDENCIES
    // ========================================================

    private AuthenticationService authenticationService;

    private AuthSessionService authSessionService;

    private JwtTokenService jwtTokenService;

    private RefreshCookieService refreshCookieService;


    // ========================================================
    // REAL ORIGIN VALIDATOR
    // ========================================================

    /**
     * Use the real validator rather than mocking
     * trusted-origin decisions.
     *
     * This lets the controller tests verify that
     * trusted and untrusted origins are handled
     * correctly.
     */
    private AuthRequestOriginValidator originValidator;


    // ========================================================
    // SPRING MVC TEST CLIENT
    // ========================================================

    private MockMvc mockMvc;


    // ========================================================
    // TEST SETUP
    // ========================================================

    /**
     * Configure an isolated authentication controller
     * before each test.
     *
     * Mockito replaces external authentication and
     * persistence services with controlled test doubles.
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
        // CREATE AUTHENTICATION SERVICE MOCK
        // ----------------------------------------------------

        authenticationService =
            mock(AuthenticationService.class);


        // ----------------------------------------------------
        // CREATE SESSION SERVICE MOCK
        // ----------------------------------------------------

        authSessionService =
            mock(AuthSessionService.class);


        // ----------------------------------------------------
        // CREATE JWT SERVICE MOCK
        // ----------------------------------------------------

        jwtTokenService =
            mock(JwtTokenService.class);


        // ----------------------------------------------------
        // CREATE REFRESH COOKIE SERVICE MOCK
        // ----------------------------------------------------

        refreshCookieService =
            mock(RefreshCookieService.class);


        // ----------------------------------------------------
        // CONFIGURE TRUSTED REQUEST ORIGINS
        // ----------------------------------------------------

        /**
         * Match the local React and Spring Boot
         * development origins.
         *
         * Origin checks use exact string matching.
         */
        originValidator =
            new AuthRequestOriginValidator(
                "http://localhost:5173,http://localhost:8080"
            );


        // ----------------------------------------------------
        // CONFIGURE REQUEST VALIDATION
        // ----------------------------------------------------

        LocalValidatorFactoryBean validator =
            new LocalValidatorFactoryBean();

        validator.afterPropertiesSet();


        // ----------------------------------------------------
        // CREATE CONTROLLER
        // ----------------------------------------------------

        /**
         * AuthController now requires five dependencies.
         *
         * The constructor must match the updated
         * production controller.
         */
        AuthController controller =
            new AuthController(
                authenticationService,
                jwtTokenService,
                authSessionService,
                refreshCookieService,
                originValidator
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
    // TEST — SUCCESSFUL PERSISTENT LOGIN
    // ========================================================

    /**
     * Correct credentials must produce HTTP 200.
     *
     * The controller should:
     *
     * 1. Accept a trusted request origin.
     * 2. Authenticate the user.
     * 3. Create a persistent PostgreSQL session
     *    through the mocked session service.
     * 4. Generate a session-aware access JWT.
     * 5. Delegate refresh-cookie creation.
     * 6. Return the existing login response.
     *
     * The response should contain:
     *
     * - Access token
     * - Bearer token type
     * - Token expiration
     * - Authenticated user identity
     *
     * Passwords, password hashes, and refresh
     * tokens must not appear in the JSON response.
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
        // PREPARE AUTHENTICATION SESSION
        // ----------------------------------------------------

        /**
         * Simulate a session that remains valid
         * for the next eight hours.
         */
        UUID sessionId =
            UUID.randomUUID();

        OffsetDateTime absoluteExpiresAt =
            OffsetDateTime.now(ZoneOffset.UTC)
                .plusHours(8);

        AuthSessionService.CreatedSession session =
            new AuthSessionService.CreatedSession(
                sessionId,
                TEST_REFRESH_TOKEN,
                absoluteExpiresAt
            );


        // ----------------------------------------------------
        // CONFIGURE PASSWORD AUTHENTICATION
        // ----------------------------------------------------

        when(
            authenticationService.authenticate(
                TEST_EMAIL,
                TEST_PASSWORD
            )
        ).thenReturn(user);


        // ----------------------------------------------------
        // CONFIGURE PERSISTENT SESSION CREATION
        // ----------------------------------------------------

        when(
            authSessionService.createSession(
                user.id()
            )
        ).thenReturn(session);


        // ----------------------------------------------------
        // CONFIGURE SESSION-AWARE JWT GENERATION
        // ----------------------------------------------------

        /**
         * The new JWT generation method requires
         * the authenticated user, session UUID,
         * and absolute session expiration.
         */
        when(
            jwtTokenService.generateAccessToken(
                user,
                sessionId,
                absoluteExpiresAt
            )
        ).thenReturn(TEST_ACCESS_TOKEN);


        // ----------------------------------------------------
        // EXECUTE LOGIN REQUEST
        // ----------------------------------------------------

        mockMvc.perform(
            post(LOGIN_URL)
                .header("Origin", TRUSTED_ORIGIN)
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
        // VERIFY NO SENSITIVE DATA IN JSON
        // ----------------------------------------------------

        .andExpect(
            jsonPath("$.password").doesNotExist()
        )

        .andExpect(
            jsonPath("$.passwordHash").doesNotExist()
        )

        .andExpect(
            jsonPath("$.refreshToken").doesNotExist()
        )


        // ----------------------------------------------------
        // PREVENT AUTHENTICATION RESPONSE CACHING
        // ----------------------------------------------------

        .andExpect(
            header().string(
                "Cache-Control",
                "no-store"
            )
        );


        // ----------------------------------------------------
        // VERIFY AUTHENTICATION SERVICE INVOCATION
        // ----------------------------------------------------

        verify(authenticationService).authenticate(
            TEST_EMAIL,
            TEST_PASSWORD
        );


        // ----------------------------------------------------
        // VERIFY SESSION CREATION
        // ----------------------------------------------------

        verify(authSessionService).createSession(
            user.id()
        );


        // ----------------------------------------------------
        // VERIFY SESSION-AWARE JWT GENERATION
        // ----------------------------------------------------

        verify(jwtTokenService).generateAccessToken(
            user,
            sessionId,
            absoluteExpiresAt
        );


        // ----------------------------------------------------
        // VERIFY REFRESH COOKIE DELEGATION
        // ----------------------------------------------------

        /**
         * The cookie service is mocked here.
         *
         * We verify the controller invokes it.
         * Actual Set-Cookie header attributes are
         * covered by RefreshCookieServiceTest.
         */
        verify(refreshCookieService).writeRefreshCookie(
            any(),
            eq(TEST_REFRESH_TOKEN),
            eq(absoluteExpiresAt)
        );


        // ----------------------------------------------------
        // VERIFY LEGACY JWT METHOD IS NOT CALLED
        // ----------------------------------------------------

        /**
         * New persistent login must use the
         * session-aware JWT generation method.
         */
        verify(jwtTokenService, never())
            .generateAccessToken(user);
    }


    // ========================================================
    // TEST — INCORRECT CREDENTIALS
    // ========================================================

    /**
     * Failed authentication must return HTTP 401.
     *
     * The response must use a generic error message.
     *
     * No session, access token, or refresh cookie
     * should be created.
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
                .header("Origin", TRUSTED_ORIGIN)
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
            header().string(
                "Cache-Control",
                "no-store"
            )
        );


        // ----------------------------------------------------
        // VERIFY NO SESSION OR TOKENS WERE GENERATED
        // ----------------------------------------------------

        verifyNoInteractions(
            authSessionService,
            jwtTokenService,
            refreshCookieService
        );
    }


    // ========================================================
    // TEST — INVALID EMAIL FORMAT
    // ========================================================

    /**
     * An invalid email address must be rejected
     * by LoginRequest validation.
     *
     * No authentication or session creation
     * should be attempted.
     */
    @Test
    void shouldRejectInvalidEmailFormat()
            throws Exception {

        mockMvc.perform(
            post(LOGIN_URL)
                .header("Origin", TRUSTED_ORIGIN)
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
            authSessionService,
            jwtTokenService,
            refreshCookieService
        );
    }


    // ========================================================
    // TEST — BLANK PASSWORD
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
                .header("Origin", TRUSTED_ORIGIN)
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
            authSessionService,
            jwtTokenService,
            refreshCookieService
        );
    }


    // ========================================================
    // TEST — MALFORMED JSON
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
                .header("Origin", TRUSTED_ORIGIN)
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
            authSessionService,
            jwtTokenService,
            refreshCookieService
        );
    }


    // ========================================================
    // TEST — MISSING REQUEST ORIGIN
    // ========================================================

    /**
     * Login requests without an Origin header
     * must be rejected with HTTP 403.
     *
     * This prevents requests without a verified
     * browser origin from creating sessions.
     */
    @Test
    void shouldRejectMissingRequestOrigin()
            throws Exception {

        // ----------------------------------------------------
        // EXECUTE LOGIN WITHOUT ORIGIN HEADER
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

        .andExpect(status().isForbidden());


        // ----------------------------------------------------
        // VERIFY NO AUTHENTICATION SIDE EFFECTS
        // ----------------------------------------------------

        verifyNoInteractions(
            authenticationService,
            authSessionService,
            jwtTokenService,
            refreshCookieService
        );
    }


    // ========================================================
    // TEST — UNTRUSTED REQUEST ORIGIN
    // ========================================================

    /**
     * Requests from an untrusted origin must
     * be rejected before password authentication.
     *
     * The controller must not create sessions
     * or issue access credentials.
     */
    @Test
    void shouldRejectUntrustedRequestOrigin()
            throws Exception {

        // ----------------------------------------------------
        // EXECUTE LOGIN FROM AN UNTRUSTED ORIGIN
        // ----------------------------------------------------

        mockMvc.perform(
            post(LOGIN_URL)
                .header(
                    "Origin",
                    "https://attacker.example"
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "test@example.com",
                      "password": "ExampleOnly@Test123!"
                    }
                    """)
        )

        .andExpect(status().isForbidden());


        // ----------------------------------------------------
        // VERIFY NO SESSION OR TOKEN CREATION
        // ----------------------------------------------------

        verifyNoInteractions(
            authenticationService,
            authSessionService,
            jwtTokenService,
            refreshCookieService
        );
    }
}
