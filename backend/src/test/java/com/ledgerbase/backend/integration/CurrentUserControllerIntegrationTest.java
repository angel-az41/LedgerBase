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
 * - Real JWT signing and verification
 * - CurrentUserController
 * - Mocked UserMapper
 *
 * The database mapper is mocked so that no real
 * user or accounting records are modified.
 *
 * The Spring Boot test profile provides a
 * separate JWT signing key.
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

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private AuthSessionJwtValidator sessionValidator;


    // ========================================================
    // CONFIGURE CONTROLLER TEST AUTHENTICATION
    // ========================================================

    /**
     * Allow the existing controller-focused test
     * JWTs through the session-validation layer.
     *
     * Other token validations remain active.
     *
     * This allows the controller to reach UserMapper
     * and exercise its existing response behavior.
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
    // TEST 1 — MISSING ACCESS TOKEN
    // ========================================================

    /**
     * Requests without a Bearer token must receive
     * HTTP 401 before reaching the controller.
     */
    @Test
    void shouldRejectRequestWithoutToken()
            throws Exception {

        mockMvc.perform(
            get("/api/v1/auth/me")
        )
        .andExpect(
            status().isUnauthorized()
        );

        verifyNoInteractions(userMapper);
    }


    // ========================================================
    // TEST 2 — VALID ACCESS TOKEN
    // ========================================================

    /**
     * A valid JWT must establish the authenticated
     * user identity.
     *
     * The controller retrieves the user from PostgreSQL
     * through the mocked UserMapper.
     */
    @Test
    void shouldReturnCurrentUserWithValidToken()
            throws Exception {

        // ----------------------------------------------------
        // PREPARE AN EXISTING TEST USER
        // ----------------------------------------------------

        User user = new User();

        user.setId(1L);
        user.setEmail("test@example.com");
        user.setFullName("Test User");

        when(userMapper.findById(1L))
            .thenReturn(user);


        // ----------------------------------------------------
        // GENERATE A VALID SIGNED JWT
        // ----------------------------------------------------

        String token =
            jwtTokenService.generateAccessToken(
                new AuthenticatedUser(
                    1L,
                    "test@example.com",
                    "Test User"
                )
            );


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

        .andExpect(status().isOk())

        .andExpect(
            jsonPath("$.userId").value(1)
        )

        .andExpect(
            jsonPath("$.email")
                .value("test@example.com")
        )

        .andExpect(
            jsonPath("$.fullName")
                .value("Test User")
        )

        .andExpect(
            jsonPath("$.passwordHash").doesNotExist()
        )

        .andExpect(
            header().string(
                "Cache-Control",
                "no-store"
            )
        );


        // ----------------------------------------------------
        // VERIFY TRUSTED DATABASE LOOKUP
        // ----------------------------------------------------

        verify(userMapper).findById(1L);
    }


    // ========================================================
    // TEST 3 — INVALID ACCESS TOKEN
    // ========================================================

    /**
     * A malformed JWT must be rejected by the
     * Spring Security filter chain.
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

        verifyNoInteractions(userMapper);
    }


    // ========================================================
    // TEST 4 — USER NO LONGER EXISTS
    // ========================================================

    /**
     * A token can remain cryptographically valid
     * after a user has been deleted.
     *
     * The endpoint must reject the identity when
     * no corresponding database record exists.
     */
    @Test
    void shouldRejectTokenForUnknownUser()
            throws Exception {

        String token =
            jwtTokenService.generateAccessToken(
                new AuthenticatedUser(
                    999L,
                    "missing@example.com",
                    "Missing User"
                )
            );

        // The mocked mapper returns null by default.

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

        verify(userMapper).findById(999L);
    }
}
