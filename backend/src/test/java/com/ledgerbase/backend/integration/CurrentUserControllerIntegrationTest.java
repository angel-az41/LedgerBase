package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.dto.AuthenticatedUser;
import com.ledgerbase.backend.mapper.UserMapper;
import com.ledgerbase.backend.model.User;
import com.ledgerbase.backend.security.AuthSessionJwtValidator;
import com.ledgerbase.backend.service.JwtTokenService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// ============================================================
// LEDGERBASE — CURRENT USER SECURITY INTEGRATION TESTS
// ============================================================

/**
 * Tests the current-user API through Spring Security.
 *
 * Unlike standalone controller unit tests, these
 * tests include the HTTP security filter chain.
 *
 * COMPONENTS:
 *
 * - MockMvc
 * - Spring Security Bearer authentication
 * - Real JWT signing and signature verification
 * - CurrentUserController
 * - Mocked UserMapper
 * - Mocked PostgreSQL session validator
 *
 * TEST SCOPE:
 *
 * These tests verify:
 *
 * - Missing-token rejection.
 * - Successful authenticated user lookup.
 * - Malformed-token rejection.
 * - Handling of users missing from PostgreSQL.
 *
 * Session-aware JWTs contain the sub and sid claims.
 *
 * The session validator is mocked because these tests
 * focus on controller behavior rather than PostgreSQL
 * session validity.
 *
 * Separate database integration tests verify real
 * session ownership, revocation, and expiration.
 *
 * No real user or accounting records are modified.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CurrentUserControllerIntegrationTest {

    // ========================================================
    // TEST DEPENDENCIES
    // ========================================================

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;


    // ========================================================
    // MOCKED DATABASE DEPENDENCIES
    // ========================================================

    /**
     * Replace user database access so each test
     * controls whether the requested user exists.
     */
    @MockitoBean
    private UserMapper userMapper;


    // ========================================================
    // ISOLATE HTTP TESTS FROM SESSION STORAGE
    // ========================================================

    /**
     * Replace the session validator in this test
     * application context only.
     *
     * The application still verifies JWT signatures,
     * issuer, audience, and expiration.
     *
     * Production uses the real PostgreSQL validator.
     */
    @MockitoBean
    private AuthSessionJwtValidator sessionValidator;


    // ========================================================
    // CONFIGURE CONTROLLER TEST AUTHENTICATION
    // ========================================================

    /**
     * Allow signed test JWTs to pass the database
     * session check.
     *
     * This lets the tests reach CurrentUserController
     * and verify its user lookup behavior.
     */
    @BeforeEach
    void allowSessionChecksForControllerTests() {

        when(
            sessionValidator.validate(any(Jwt.class))
        ).thenReturn(
            OAuth2TokenValidatorResult.success()
        );
    }


    // ========================================================
    // GENERATE SESSION-AWARE TEST ACCESS TOKEN
    // ========================================================

    /**
     * Generate an access JWT using the application's
     * current session-aware authentication method.
     *
     * Each test token receives:
     *
     * - The authenticated user ID as sub.
     * - A randomly generated session UUID as sid.
     * - An absolute session expiration in the future.
     *
     * The session is not inserted into PostgreSQL
     * because the session validator is mocked.
     *
     * This helper must not be used in tests that
     * verify real database session enforcement.
     *
     * @param user authenticated test identity
     * @return signed session-aware JWT
     */
    private String issueSessionAwareToken(
        AuthenticatedUser user
    ) {

        // ----------------------------------------------------
        // GENERATE TEST SESSION IDENTIFIER
        // ----------------------------------------------------

        UUID sessionId =
            UUID.randomUUID();


        // ----------------------------------------------------
        // SET TEST SESSION EXPIRATION
        // ----------------------------------------------------

        OffsetDateTime absoluteExpiresAt =
            OffsetDateTime.now(ZoneOffset.UTC)
                .plusHours(8);


        // ----------------------------------------------------
        // GENERATE SIGNED ACCESS JWT
        // ----------------------------------------------------

        return jwtTokenService.generateAccessToken(
            user,
            sessionId,
            absoluteExpiresAt
        );
    }


    // ========================================================
    // TEST — MISSING ACCESS TOKEN
    // ========================================================

    /**
     * Requests without a Bearer token must receive
     * HTTP 401 before reaching the controller.
     */
    @Test
    void shouldRejectRequestWithoutToken()
            throws Exception {

        // ----------------------------------------------------
        // SEND REQUEST WITHOUT AUTHORIZATION
        // ----------------------------------------------------

        mockMvc.perform(
            get("/api/v1/auth/me")
        )

        .andExpect(
            status().isUnauthorized()
        );


        // ----------------------------------------------------
        // ENSURE DATABASE LOOKUP WAS NOT ATTEMPTED
        // ----------------------------------------------------

        verifyNoInteractions(userMapper);
    }


    // ========================================================
    // TEST — VALID SESSION-AWARE ACCESS TOKEN
    // ========================================================

    /**
     * A correctly signed session-aware JWT must
     * establish the authenticated user identity.
     *
     * The controller retrieves the user from the
     * mocked UserMapper.
     */
    @Test
    void shouldReturnCurrentUserWithValidToken()
            throws Exception {

        // ----------------------------------------------------
        // PREPARE EXISTING TEST USER
        // ----------------------------------------------------

        User user =
            new User();

        user.setId(1L);
        user.setEmail("test@example.com");
        user.setFullName("Test User");


        // ----------------------------------------------------
        // CONFIGURE DATABASE LOOKUP
        // ----------------------------------------------------

        when(
            userMapper.findById(1L)
        ).thenReturn(user);


        // ----------------------------------------------------
        // GENERATE SESSION-AWARE ACCESS JWT
        // ----------------------------------------------------

        AuthenticatedUser identity =
            new AuthenticatedUser(
                1L,
                "test@example.com",
                "Test User"
            );

        String token =
            issueSessionAwareToken(identity);


        // ----------------------------------------------------
        // SEND AUTHENTICATED REQUEST
        // ----------------------------------------------------

        mockMvc.perform(
            get("/api/v1/auth/me")
                .header(
                    "Authorization",
                    "Bearer " + token
                )
        )

        // ----------------------------------------------------
        // VERIFY SUCCESSFUL RESPONSE
        // ----------------------------------------------------

        .andExpect(status().isOk())

        .andExpect(
            jsonPath("$.userId")
                .value(1)
        )

        .andExpect(
            jsonPath("$.email")
                .value("test@example.com")
        )

        .andExpect(
            jsonPath("$.fullName")
                .value("Test User")
        )


        // ----------------------------------------------------
        // ENSURE PASSWORD HASH IS NOT EXPOSED
        // ----------------------------------------------------

        .andExpect(
            jsonPath("$.passwordHash")
                .doesNotExist()
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
        // VERIFY TRUSTED USER LOOKUP
        // ----------------------------------------------------

        verify(userMapper)
            .findById(1L);
    }


    // ========================================================
    // TEST — MALFORMED ACCESS TOKEN
    // ========================================================

    /**
     * An invalid JWT must be rejected by the
     * Spring Security authentication filter.
     *
     * The controller must not access UserMapper.
     */
    @Test
    void shouldRejectMalformedAccessToken()
            throws Exception {

        mockMvc.perform(
            get("/api/v1/auth/me")
                .header(
                    "Authorization",
                    "Bearer invalid.jwt.token"
                )
        )

        .andExpect(
            status().isUnauthorized()
        );


        // ----------------------------------------------------
        // VERIFY NO USER LOOKUP
        // ----------------------------------------------------

        verifyNoInteractions(userMapper);
    }


    // ========================================================
    // TEST — AUTHENTICATED USER NO LONGER EXISTS
    // ========================================================

    /**
     * A JWT may remain cryptographically valid even
     * when its associated user has been deleted.
     *
     * The endpoint must reject the identity when
     * UserMapper cannot locate a user record.
     *
     * This test isolates controller behavior by
     * mocking session validation.
     *
     * Production also performs database-backed
     * session ownership validation.
     */
    @Test
    void shouldRejectTokenForUnknownUser()
            throws Exception {

        // ----------------------------------------------------
        // PREPARE AUTHENTICATED TEST IDENTITY
        // ----------------------------------------------------

        AuthenticatedUser missingUser =
            new AuthenticatedUser(
                999L,
                "missing@example.com",
                "Missing User"
            );


        // ----------------------------------------------------
        // GENERATE SESSION-AWARE ACCESS JWT
        // ----------------------------------------------------

        String token =
            issueSessionAwareToken(missingUser);


        // ----------------------------------------------------
        // USERMAPPER RETURNS NULL BY DEFAULT
        // ----------------------------------------------------

        /**
         * No UserMapper stub is configured for 999L.
         *
         * The mocked database lookup therefore
         * returns null.
         */


        // ----------------------------------------------------
        // REQUEST CURRENT USER
        // ----------------------------------------------------

        mockMvc.perform(
            get("/api/v1/auth/me")
                .header(
                    "Authorization",
                    "Bearer " + token
                )
        )

        .andExpect(
            status().isUnauthorized()
        );


        // ----------------------------------------------------
        // VERIFY CONTROLLER ATTEMPTED USER LOOKUP
        // ----------------------------------------------------

        verify(userMapper)
            .findById(999L);
    }
}
