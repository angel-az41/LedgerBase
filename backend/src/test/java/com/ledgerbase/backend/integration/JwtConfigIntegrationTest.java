package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.dto.AuthenticatedUser;
import com.ledgerbase.backend.security.AuthSessionJwtValidator;
import com.ledgerbase.backend.service.JwtTokenService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// ============================================================
// LEDGERBASE — JWT CONFIGURATION INTEGRATION TESTS
// ============================================================

/**
 * Verifies JWT authentication components registered
 * in the actual Spring Boot application context.
 *
 * COMPONENTS UNDER TEST:
 *
 * - JWT signing key.
 * - JwtEncoder.
 * - JwtDecoder.
 * - JwtTokenService.
 *
 * SECURITY TEST COVERAGE:
 *
 * - Valid session-aware JWT generation.
 * - JWT subject and session claims.
 * - Expected issuer.
 * - Expected audience.
 * - Access token expiration.
 * - Rejection of incorrect audiences.
 * - Rejection of incorrect issuers.
 * - Rejection of incorrect signing keys.
 *
 * TEST ISOLATION:
 *
 * These tests verify cryptographic configuration
 * independently of PostgreSQL session persistence.
 *
 * The AuthSessionJwtValidator is mocked.
 *
 * Separate integration tests exercise real
 * database-backed session validation.
 *
 * The Spring Boot test profile provides a separate
 * JWT signing secret.
 *
 * No development or production credentials
 * are needed in these tests.
 *
 * No database records are modified.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
class JwtConfigIntegrationTest {

    // ========================================================
    // SPRING-MANAGED COMPONENTS
    // ========================================================

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;


    // ========================================================
    // JWT APPLICATION CONFIGURATION
    // ========================================================

    @Value("${ledgerbase.jwt.issuer}")
    private String issuer;

    @Value("${ledgerbase.jwt.audience}")
    private String audience;


    // ========================================================
    // MOCK POSTGRESQL SESSION VALIDATION
    // ========================================================

    /**
     * These tests focus on JWT cryptography and claims.
     *
     * Replace the PostgreSQL session validator in
     * this test context only.
     *
     * The configured decoder still verifies:
     *
     * - JWT signatures.
     * - Token expiration.
     * - Token issuer.
     * - Intended audience.
     *
     * Production continues to use the real
     * AuthSessionJwtValidator.
     */
    @MockitoBean
    private AuthSessionJwtValidator sessionValidator;


    // ========================================================
    // CONFIGURE CRYPTOGRAPHIC TEST ISOLATION
    // ========================================================

    /**
     * Allow test JWTs to pass session validation
     * so each test can examine cryptographic
     * signing and other token requirements.
     */
    @BeforeEach
    void configureCryptographicTestIsolation() {

        when(
            sessionValidator.validate(any(Jwt.class))
        ).thenReturn(
            OAuth2TokenValidatorResult.success()
        );
    }


    // ========================================================
    // TEST — VALID SESSION-AWARE ACCESS TOKEN
    // ========================================================

    /**
     * Verify that the real Spring-managed encoder
     * and decoder can generate and verify a JWT.
     *
     * The JWT must contain:
     *
     * - Correct user ID.
     * - Authentication session UUID.
     * - Expected issuer.
     * - Expected audience.
     * - Correct expiration lifetime.
     */
    @Test
    void shouldIssueAndVerifyValidAccessToken() {

        // ----------------------------------------------------
        // CREATE TEST-ONLY AUTHENTICATED USER
        // ----------------------------------------------------

        AuthenticatedUser user =
            new AuthenticatedUser(
                1L,
                "test@example.com",
                "Test User"
            );


        // ----------------------------------------------------
        // CREATE TEST AUTHENTICATION SESSION DETAILS
        // ----------------------------------------------------

        /**
         * JWT signing does not require a database
         * session insert in this test class.
         *
         * The session validator is mocked.
         */
        UUID sessionId =
            UUID.randomUUID();

        OffsetDateTime absoluteExpiresAt =
            OffsetDateTime.now(ZoneOffset.UTC)
                .plusHours(8);


        // ----------------------------------------------------
        // GENERATE SESSION-AWARE ACCESS TOKEN
        // ----------------------------------------------------

        String token =
            jwtTokenService.generateAccessToken(
                user,
                sessionId,
                absoluteExpiresAt
            );


        // ----------------------------------------------------
        // VERIFY USING CONFIGURED TOKEN SERVICE
        // ----------------------------------------------------

        Long verifiedUserId =
            jwtTokenService.verifyAndGetUserId(token);

        assertEquals(
            1L,
            verifiedUserId
        );


        // ----------------------------------------------------
        // VERIFY SESSION IDENTIFIER
        // ----------------------------------------------------

        UUID verifiedSessionId =
            jwtTokenService.verifyAndGetSessionId(token);

        assertEquals(
            sessionId,
            verifiedSessionId
        );


        // ----------------------------------------------------
        // DECODE AND INSPECT JWT CLAIMS
        // ----------------------------------------------------

        Jwt jwt =
            jwtDecoder.decode(token);


        // ----------------------------------------------------
        // VERIFY AUTHENTICATED USER SUBJECT
        // ----------------------------------------------------

        assertEquals(
            "1",
            jwt.getSubject()
        );


        // ----------------------------------------------------
        // VERIFY SESSION CLAIM
        // ----------------------------------------------------

        assertEquals(
            sessionId.toString(),
            jwt.getClaimAsString(
                JwtTokenService.SESSION_ID_CLAIM
            )
        );


        // ----------------------------------------------------
        // VERIFY ISSUER
        // ----------------------------------------------------

        assertEquals(
            issuer,
            jwt.getClaimAsString("iss")
        );


        // ----------------------------------------------------
        // VERIFY AUDIENCE
        // ----------------------------------------------------

        assertTrue(
            jwt.getAudience().contains(audience)
        );


        // ----------------------------------------------------
        // VERIFY ISSUANCE AND EXPIRATION CLAIMS
        // ----------------------------------------------------

        assertNotNull(jwt.getIssuedAt());

        assertNotNull(jwt.getExpiresAt());

        assertTrue(
            jwt.getExpiresAt().isAfter(
                jwt.getIssuedAt()
            )
        );


        // ----------------------------------------------------
        // VERIFY STANDARD ACCESS TOKEN LIFETIME
        // ----------------------------------------------------

        /**
         * The session expires in eight hours,
         * so it does not shorten the standard
         * 15-minute JWT lifetime.
         */
        assertEquals(
            Duration.ofMinutes(15),
            Duration.between(
                jwt.getIssuedAt(),
                jwt.getExpiresAt()
            )
        );
    }


    // ========================================================
    // TEST — WRONG AUDIENCE
    // ========================================================

    /**
     * A correctly signed JWT must be rejected
     * when its audience identifies another API.
     *
     * A valid cryptographic signature alone
     * is not sufficient for authentication.
     */
    @Test
    void shouldRejectTokenWithWrongAudience() {

        // ----------------------------------------------------
        // BUILD CLAIMS WITH AN INCORRECT AUDIENCE
        // ----------------------------------------------------

        Instant now =
            Instant.now();

        JwtClaimsSet claims =
            JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("1")
                .audience(List.of("another-api"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build();


        // ----------------------------------------------------
        // SIGN TOKEN WITH THE REAL APPLICATION ENCODER
        // ----------------------------------------------------

        String token =
            jwtEncoder.encode(
                JwtEncoderParameters.from(
                    JwsHeader
                        .with(MacAlgorithm.HS256)
                        .build(),
                    claims
                )
            ).getTokenValue();


        // ----------------------------------------------------
        // REJECT INCORRECT AUDIENCE
        // ----------------------------------------------------

        assertThrows(
            BadJwtException.class,
            () -> jwtTokenService.verifyAndGetUserId(
                token
            )
        );
    }


    // ========================================================
    // TEST — WRONG ISSUER
    // ========================================================

    /**
     * Reject a correctly signed JWT with an
     * unexpected token issuer.
     *
     * Tokens from a different issuer must not
     * be accepted by LedgerBase.
     */
    @Test
    void shouldRejectTokenWithWrongIssuer() {

        // ----------------------------------------------------
        // BUILD CLAIMS WITH AN INCORRECT ISSUER
        // ----------------------------------------------------

        Instant now =
            Instant.now();

        JwtClaimsSet claims =
            JwtClaimsSet.builder()
                .issuer("another-issuer")
                .subject("1")
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build();


        // ----------------------------------------------------
        // SIGN WITH THE REAL APPLICATION ENCODER
        // ----------------------------------------------------

        String token =
            jwtEncoder.encode(
                JwtEncoderParameters.from(
                    JwsHeader
                        .with(MacAlgorithm.HS256)
                        .build(),
                    claims
                )
            ).getTokenValue();


        // ----------------------------------------------------
        // EXPECT ISSUER VALIDATION FAILURE
        // ----------------------------------------------------

        assertThrows(
            JwtException.class,
            () -> jwtTokenService.verifyAndGetUserId(
                token
            )
        );
    }


    // ========================================================
    // TEST — INVALID SIGNING KEY
    // ========================================================

    /**
     * A JWT signed with a different secret key
     * must fail cryptographic signature verification.
     *
     * The alternate signing key is test-only.
     *
     * It must not be reused for application
     * authentication or production.
     */
    @Test
    void shouldRejectTokenSignedWithDifferentKey() {

        // ----------------------------------------------------
        // CREATE AN ALTERNATE TEST-ONLY SIGNING KEY
        // ----------------------------------------------------

        byte[] otherKeyBytes = (
            "AnotherTestOnlyJwtSigningKey-NotForProduction"
        ).getBytes(StandardCharsets.UTF_8);

        SecretKey otherKey =
            new SecretKeySpec(
                otherKeyBytes,
                "HmacSHA256"
            );


        // ----------------------------------------------------
        // CREATE AN INDEPENDENT JWT ENCODER
        // ----------------------------------------------------

        JwtEncoder otherEncoder =
            NimbusJwtEncoder
                .withSecretKey(otherKey)
                .algorithm(MacAlgorithm.HS256)
                .build();


        // ----------------------------------------------------
        // BUILD OTHERWISE VALID JWT CLAIMS
        // ----------------------------------------------------

        Instant now =
            Instant.now();

        JwtClaimsSet claims =
            JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("1")
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build();


        // ----------------------------------------------------
        // SIGN TOKEN USING THE WRONG KEY
        // ----------------------------------------------------

        String token =
            otherEncoder.encode(
                JwtEncoderParameters.from(
                    JwsHeader
                        .with(MacAlgorithm.HS256)
                        .build(),
                    claims
                )
            ).getTokenValue();


        // ----------------------------------------------------
        // VERIFY SIGNATURE REJECTION
        // ----------------------------------------------------

        assertThrows(
            JwtException.class,
            () -> jwtTokenService.verifyAndGetUserId(
                token
            )
        );
    }
}
