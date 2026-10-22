package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.service.AuthenticationService;
import com.ledgerbase.backend.service.AuthSessionService;
import com.ledgerbase.backend.service.AuthRequestOriginValidator;
import com.ledgerbase.backend.service.JwtTokenService;
import com.ledgerbase.backend.service.RefreshCookieService;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// ============================================================
// LEDGERBASE — AUTHENTICATION LOGOUT HTTP TESTS
// ============================================================

/**
 * Verifies the HTTP logout endpoint.
 *
 * TEST ARCHITECTURE:
 *
 * - Real Spring MVC request handling through MockMvc.
 * - Mock AuthSessionService.
 * - Real RefreshCookieService.
 * - Real AuthRequestOriginValidator.
 *
 * TEST COVERAGE:
 *
 * - Successful session revocation.
 * - Idempotent repeated logout.
 * - Unknown refresh credentials.
 * - Missing refresh cookies.
 * - Untrusted request origins.
 * - Missing request origins.
 *
 * These tests do not connect to PostgreSQL.
 *
 * Session revocation persistence is already
 * covered by the database integration tests.
 */
class AuthControllerLogoutTest {

    // ========================================================
    // TEST CONFIGURATION
    // ========================================================

    private static final String LOGOUT_URL =
        "/api/v1/auth/logout";

    private static final String TRUSTED_ORIGIN =
        "http://localhost:5173";

    private static final String TEST_REFRESH_TOKEN =
        "A".repeat(43);


    // ========================================================
    // DEPENDENCIES
    // ========================================================

    private AuthSessionService authSessionService;

    private JwtTokenService jwtTokenService;

    private MockMvc mockMvc;


    // ========================================================
    // CONFIGURE HTTP TEST CLIENT
    // ========================================================

    @BeforeEach
    void setUp() {

        // ====================================================
        // MOCK SESSION AND JWT SERVICES
        // ====================================================

        authSessionService =
            mock(AuthSessionService.class);

        jwtTokenService =
            mock(JwtTokenService.class);


        // ====================================================
        // USE REAL COOKIE SERVICE
        // ====================================================

        /**
         * Secure=false allows local HTTP test
         * configuration.
         *
         * HttpOnly, SameSite, and cookie-path
         * settings remain enabled.
         */
        RefreshCookieService refreshCookieService =
            new RefreshCookieService(false);


        // ====================================================
        // USE REAL ORIGIN VALIDATOR
        // ====================================================

        AuthRequestOriginValidator originValidator =
            new AuthRequestOriginValidator(
                "http://localhost:5173,http://localhost:8080"
            );


        // ====================================================
        // CREATE CONTROLLER
        // ====================================================

        AuthController controller =
            new AuthController(
                mock(AuthenticationService.class),
                jwtTokenService,
                authSessionService,
                refreshCookieService,
                originValidator
            );


        // ====================================================
        // CONFIGURE MOCKMVC
        // ====================================================

        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .build();
    }


    // ========================================================
    // TEST — SUCCESSFUL LOGOUT
    // ========================================================

    /**
     * Verify that a valid refresh cookie causes
     * server-side session revocation.
     *
     * The response must:
     *
     * - Return HTTP 204.
     * - Have no response body.
     * - Prevent caching.
     * - Clear the browser refresh cookie.
     */
    @Test
    void shouldRevokeSessionAndClearCookie()
            throws Exception {

        when(
            authSessionService.revokeSessionByRefreshToken(
                TEST_REFRESH_TOKEN
            )
        ).thenReturn(true);


        // ----------------------------------------------------
        // EXECUTE LOGOUT
        // ----------------------------------------------------

        mockMvc.perform(
            post(LOGOUT_URL)
                .header("Origin", TRUSTED_ORIGIN)
                .cookie(
                    new Cookie(
                        RefreshCookieService.COOKIE_NAME,
                        TEST_REFRESH_TOKEN
                    )
                )
        )

        // ----------------------------------------------------
        // VERIFY HTTP RESPONSE
        // ----------------------------------------------------

        .andExpect(status().isNoContent())

        .andExpect(content().string(""))

        .andExpect(
            header().string(
                HttpHeaders.CACHE_CONTROL,
                "no-store"
            )
        )

        // ----------------------------------------------------
        // VERIFY BROWSER COOKIE DELETION
        // ----------------------------------------------------

        .andExpect(
            header().string(
                HttpHeaders.SET_COOKIE,
                containsString("ledgerbase_refresh=")
            )
        )

        .andExpect(
            header().string(
                HttpHeaders.SET_COOKIE,
                containsString("Max-Age=0")
            )
        )

        .andExpect(
            header().string(
                HttpHeaders.SET_COOKIE,
                containsString("Path=/api/v1/auth")
            )
        )

        .andExpect(
            header().string(
                HttpHeaders.SET_COOKIE,
                containsString("HttpOnly")
            )
        );


        // ----------------------------------------------------
        // VERIFY POSTGRESQL REVOCATION DELEGATION
        // ----------------------------------------------------

        verify(authSessionService)
            .revokeSessionByRefreshToken(
                TEST_REFRESH_TOKEN
            );


        // ----------------------------------------------------
        // VERIFY NO ACCESS JWT WAS ISSUED
        // ----------------------------------------------------

        verifyNoInteractions(jwtTokenService);
    }


    // ========================================================
    // TEST — REPEATED LOGOUT
    // ========================================================

    /**
     * Logging out twice must be harmless.
     *
     * The first request revokes the session.
     *
     * The second request sees an already-revoked
     * session and receives the same HTTP response.
     */
    @Test
    void shouldHandleRepeatedLogout()
            throws Exception {

        when(
            authSessionService.revokeSessionByRefreshToken(
                TEST_REFRESH_TOKEN
            )
        ).thenReturn(true, false);


        // ----------------------------------------------------
        // FIRST LOGOUT
        // ----------------------------------------------------

        mockMvc.perform(
            post(LOGOUT_URL)
                .header("Origin", TRUSTED_ORIGIN)
                .cookie(
                    new Cookie(
                        RefreshCookieService.COOKIE_NAME,
                        TEST_REFRESH_TOKEN
                    )
                )
        )
        .andExpect(status().isNoContent());


        // ----------------------------------------------------
        // SECOND LOGOUT
        // ----------------------------------------------------

        mockMvc.perform(
            post(LOGOUT_URL)
                .header("Origin", TRUSTED_ORIGIN)
                .cookie(
                    new Cookie(
                        RefreshCookieService.COOKIE_NAME,
                        TEST_REFRESH_TOKEN
                    )
                )
        )
        .andExpect(status().isNoContent());


        // ----------------------------------------------------
        // VERIFY BOTH ATTEMPTS WERE HANDLED
        // ----------------------------------------------------

        verify(authSessionService, times(2))
            .revokeSessionByRefreshToken(
                TEST_REFRESH_TOKEN
            );
    }


    // ========================================================
    // TEST — UNKNOWN REFRESH CREDENTIAL
    // ========================================================

    /**
     * Unknown refresh tokens must not expose
     * whether a database session exists.
     *
     * The controller returns the same logout
     * response and clears the browser cookie.
     */
    @Test
    void shouldHandleUnknownRefreshToken()
            throws Exception {

        // Mockito returns false by default for
        // the boolean revocation method.

        mockMvc.perform(
            post(LOGOUT_URL)
                .header("Origin", TRUSTED_ORIGIN)
                .cookie(
                    new Cookie(
                        RefreshCookieService.COOKIE_NAME,
                        TEST_REFRESH_TOKEN
                    )
                )
        )

        .andExpect(status().isNoContent())

        .andExpect(
            header().string(
                HttpHeaders.SET_COOKIE,
                containsString("Max-Age=0")
            )
        );

        verify(authSessionService)
            .revokeSessionByRefreshToken(
                TEST_REFRESH_TOKEN
            );
    }


    // ========================================================
    // TEST — MISSING REFRESH COOKIE
    // ========================================================

    /**
     * Users can request logout even if their
     * browser no longer holds a refresh cookie.
     *
     * The endpoint must not require an access JWT.
     */
    @Test
    void shouldHandleMissingRefreshCookie()
            throws Exception {

        mockMvc.perform(
            post(LOGOUT_URL)
                .header("Origin", TRUSTED_ORIGIN)
        )

        .andExpect(status().isNoContent())

        .andExpect(
            header().string(
                HttpHeaders.SET_COOKIE,
                containsString("Max-Age=0")
            )
        );


        // ----------------------------------------------------
        // NO TOKEN MEANS NO SESSION LOOKUP
        // ----------------------------------------------------

        verifyNoInteractions(
            authSessionService,
            jwtTokenService
        );
    }


    // ========================================================
    // TEST — UNTRUSTED REQUEST ORIGIN
    // ========================================================

    /**
     * An untrusted website must not be permitted
     * to revoke another browser's login session.
     *
     * The endpoint rejects the request before
     * any session revocation is attempted.
     */
    @Test
    void shouldRejectUntrustedLogoutOrigin()
            throws Exception {

        mockMvc.perform(
            post(LOGOUT_URL)
                .header(
                    "Origin",
                    "https://attacker.example"
                )
                .cookie(
                    new Cookie(
                        RefreshCookieService.COOKIE_NAME,
                        TEST_REFRESH_TOKEN
                    )
                )
        )

        .andExpect(status().isForbidden());


        // ----------------------------------------------------
        // VERIFY NO AUTHENTICATION OPERATIONS
        // ----------------------------------------------------

        verifyNoInteractions(
            authSessionService,
            jwtTokenService
        );
    }


    // ========================================================
    // TEST — MISSING REQUEST ORIGIN
    // ========================================================

    /**
     * A logout request without an Origin header
     * must be rejected.
     *
     * This matches the origin-validation policy
     * already used by login and refresh.
     */
    @Test
    void shouldRejectMissingLogoutOrigin()
            throws Exception {

        mockMvc.perform(
            post(LOGOUT_URL)
                .cookie(
                    new Cookie(
                        RefreshCookieService.COOKIE_NAME,
                        TEST_REFRESH_TOKEN
                    )
                )
        )

        .andExpect(status().isForbidden());


        // ----------------------------------------------------
        // VERIFY NO SESSION REVOCATION
        // ----------------------------------------------------

        verifyNoInteractions(
            authSessionService,
            jwtTokenService
        );
    }
}
