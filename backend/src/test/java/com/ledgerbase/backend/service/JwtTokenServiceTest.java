package com.ledgerbase.backend.service;

import com.ledgerbase.backend.dto.AuthenticatedUser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — JWT TOKEN SERVICE UNIT TESTS
// ============================================================

/**
 * Tests JWT signing and verification.
 *
 * All tests operate in memory.
 *
 * No real credentials or database records are used.
 *
 * The test signing key must NEVER be reused
 * in development or production.
 */
class JwtTokenServiceTest {

    private static final String ISSUER =
        "https://ledgerbase.test";

    private static final String AUDIENCE =
        "ledgerbase-api";

    private JwtTokenService jwtTokenService;

    private JwtEncoder jwtEncoder;


    // ========================================================
    // SETUP
    // ========================================================

    @BeforeEach
    void setUp() {

        // Fixed key for deterministic test configuration.
        //
        // This is NOT a production secret.

        byte[] keyBytes = (
            "TestOnlyJwtKey-DoNotUseInProduction-123456"
        ).getBytes(StandardCharsets.UTF_8);

        SecretKey secretKey = new SecretKeySpec(
            keyBytes,
            "HmacSHA256"
        );


        // ====================================================
        // CONFIGURE HS256 ENCODER
        // ====================================================

        jwtEncoder = NimbusJwtEncoder
            .withSecretKey(secretKey)
            .algorithm(MacAlgorithm.HS256)
            .build();


        // ====================================================
        // CONFIGURE HS256 DECODER
        // ====================================================

        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder
            .withSecretKey(secretKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build();


        // Require standard timestamp and issuer checks.

        jwtDecoder.setJwtValidator(
            JwtValidators.createDefaultWithIssuer(
                ISSUER
            )
        );


        // ====================================================
        // CREATE SERVICE
        // ====================================================

        jwtTokenService = new JwtTokenService(
            jwtEncoder,
            jwtDecoder,
            ISSUER,
            AUDIENCE,
            Duration.ofMinutes(15)
        );
    }


    // ========================================================
    // TEST 1 — VALID TOKEN
    // ========================================================

    @Test
    void shouldGenerateAndVerifyAccessToken() {

        AuthenticatedUser user = new AuthenticatedUser(
            1L,
            "test@example.com",
            "Test User"
        );

        String token =
            jwtTokenService.generateAccessToken(user);

        assertNotNull(token);

        assertEquals(
            3,
            token.split("\\.").length
        );

        assertEquals(
            1L,
            jwtTokenService.verifyAndGetUserId(token)
        );
    }


    // ========================================================
    // TEST 2 — REJECT EXPIRED TOKEN
    // ========================================================

    @Test
    void shouldRejectExpiredToken() {

        Instant now = Instant.now();

        JwtClaimsSet expiredClaims =
            JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("1")
                .audience(List.of(AUDIENCE))
                .issuedAt(now.minusSeconds(120))
                .expiresAt(now.minusSeconds(60))
                .build();

        String token = jwtEncoder.encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256)
                    .build(),
                expiredClaims
            )
        ).getTokenValue();

        assertThrows(
            JwtException.class,
            () -> jwtTokenService.verifyAndGetUserId(
                token
            )
        );
    }


    // ========================================================
    // TEST 3 — REJECT INCORRECT AUDIENCE
    // ========================================================

    @Test
    void shouldRejectWrongAudience() {

        AuthenticatedUser user = new AuthenticatedUser(
            1L,
            "test@example.com",
            "Test User"
        );

        // Generate a validly signed token intended
        // for a DIFFERENT API.

        JwtTokenService otherService =
            new JwtTokenService(
                jwtEncoder,
                token -> {
                    throw new UnsupportedOperationException();
                },
                ISSUER,
                "another-api",
                Duration.ofMinutes(15)
            );

        String token =
            otherService.generateAccessToken(user);

        assertThrows(
            BadJwtException.class,
            () -> jwtTokenService.verifyAndGetUserId(
                token
            )
        );
    }


    // ========================================================
    // TEST 4 — REJECT MALFORMED TOKEN
    // ========================================================

    @Test
    void shouldRejectMalformedToken() {

        assertThrows(
            JwtException.class,
            () -> jwtTokenService.verifyAndGetUserId(
                "not.a.valid.jwt"
            )
        );
    }


    // ========================================================
    // TEST 5 — REJECT INVALID USER ID
    // ========================================================

    @Test
    void shouldRejectInvalidUserIdentity() {

        AuthenticatedUser invalidUser =
            new AuthenticatedUser(
                -1L,
                "invalid@example.com",
                "Invalid User"
            );

        assertThrows(
            IllegalArgumentException.class,
            () -> jwtTokenService.generateAccessToken(
                invalidUser
            )
        );
    }
}
