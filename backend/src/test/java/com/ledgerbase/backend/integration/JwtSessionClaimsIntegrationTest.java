package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.dto.AuthenticatedUser;
import com.ledgerbase.backend.security.AuthSessionJwtValidator;
import com.ledgerbase.backend.service.JwtTokenService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// ============================================================
// LEDGERBASE — JWT SESSION CLAIMS INTEGRATION TESTS
// ============================================================

/**
 * Verifies that signed access JWTs correctly contain
 * and validate their authentication session identity.
 *
 * Uses the application's actual JwtEncoder and
 * JwtDecoder beans.
 *
 * No servlet server or database writes are needed.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
class JwtSessionClaimsIntegrationTest {

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JwtDecoder jwtDecoder;


    // ========================================================
    // ISOLATE CRYPTOGRAPHIC TESTS FROM SESSION STORAGE
    // ========================================================

    /**
     * These existing tests focus on:
     *
     * - JWT signing.
     * - JWT verification.
     * - Issuer and audience validation.
     * - Claims and token expiration.
     *
     * Substitute only the PostgreSQL session validator.
     *
     * This is test-only behavior.
     *
     * Production JwtDecoder always uses the real
     * AuthSessionJwtValidator.
     *
     * A separate database integration test verifies
     * the actual session validation requirement.
     */
    @MockitoBean
    private AuthSessionJwtValidator sessionValidator;


    // ========================================================
    // ALLOW SESSION CHECK IN CRYPTOGRAPHIC TESTS
    // ========================================================

    @BeforeEach
    void configureCryptographicTestIsolation() {

        when(
            sessionValidator.validate(any(Jwt.class))
        ).thenReturn(
            OAuth2TokenValidatorResult.success()
        );
    }


    // ========================================================
    // TEST USER
    // ========================================================

    private AuthenticatedUser testUser() {

        return new AuthenticatedUser(
            1L,
            "jwt-session-test@ledgerbase.test",
            "JWT Session Test User"
        );
    }


    // ========================================================
    // TEST — SESSION IDENTIFIER
    // ========================================================

    @Test
    void shouldIncludeSessionIdInSignedAccessToken() {

        UUID sessionId = UUID.randomUUID();

        String token =
            jwtTokenService.generateAccessToken(
                testUser(),
                sessionId,
                OffsetDateTime.now(ZoneOffset.UTC)
                    .plusHours(8)
            );

        Jwt decoded = jwtDecoder.decode(token);

        assertEquals(
            sessionId.toString(),
            decoded.getClaimAsString("sid")
        );

        assertEquals(
            sessionId,
            jwtTokenService.verifyAndGetSessionId(token)
        );

        assertEquals(
            1L,
            jwtTokenService.verifyAndGetUserId(token)
        );
    }


    // ========================================================
    // TEST — SESSION EXPIRATION CAP
    // ========================================================

    @Test
    void shouldNotIssueTokenBeyondSessionExpiration() {

        OffsetDateTime sessionDeadline =
            OffsetDateTime.now(ZoneOffset.UTC)
                .plusMinutes(5);

        String token =
            jwtTokenService.generateAccessToken(
                testUser(),
                UUID.randomUUID(),
                sessionDeadline
            );

        Jwt decoded = jwtDecoder.decode(token);

        assertNotNull(decoded.getExpiresAt());

        assertFalse(
            decoded.getExpiresAt().isAfter(
                sessionDeadline.toInstant()
            )
        );
    }


    // ========================================================
    // TEST — EXPIRED SESSION
    // ========================================================

    @Test
    void shouldRejectExpiredSessionDuringIssuance() {

        assertThrows(
            IllegalArgumentException.class,
            () -> jwtTokenService.generateAccessToken(
                testUser(),
                UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC)
                    .minusMinutes(1)
            )
        );
    }


    // ========================================================
    // TEST — MISSING SESSION IDENTIFIER
    // ========================================================

    @Test
    void shouldRejectMissingSessionIdentifier() {

        assertThrows(
            IllegalArgumentException.class,
            () -> jwtTokenService.generateAccessToken(
                testUser(),
                null,
                OffsetDateTime.now(ZoneOffset.UTC)
                    .plusHours(8)
            )
        );
    }


    // ========================================================
    // TEST — LEGACY TOKEN HAS NO SESSION
    // ========================================================

    @Test
    @SuppressWarnings("deprecation")
    void shouldRejectSessionExtractionFromLegacyToken() {

        String legacyToken =
            jwtTokenService.generateAccessToken(
                testUser()
            );

        assertThrows(
            BadJwtException.class,
            () -> jwtTokenService.verifyAndGetSessionId(
                legacyToken
            )
        );
    }
}
