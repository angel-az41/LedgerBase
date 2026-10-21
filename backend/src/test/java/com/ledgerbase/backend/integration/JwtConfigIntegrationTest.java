package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.dto.AuthenticatedUser;
import com.ledgerbase.backend.service.JwtTokenService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

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
// LEDGERBASE — JWT CONFIGURATION INTEGRATION TESTS
// ============================================================

/**
 * Verifies JWT authentication components registered
 * in the actual Spring Boot application context.
 *
 * COMPONENTS UNDER TEST:
 *
 * - JWT signing key
 * - JwtEncoder
 * - JwtDecoder
 * - JwtTokenService
 *
 * SECURITY:
 *
 * The test profile provides a separate signing key.
 *
 * No development or production JWT secrets are needed.
 *
 * No real user passwords or personal credentials
 * are used in these tests.
 *
 * DATABASE:
 *
 * The Spring Boot test context uses ledgerbase_test.
 * These tests do not insert or update database records.
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
    // JWT CONFIGURATION
    // ========================================================

    @Value("${ledgerbase.jwt.issuer}")
    private String issuer;

    @Value("${ledgerbase.jwt.audience}")
    private String audience;


    // ========================================================
    // TEST 1 — VALID ACCESS TOKEN
    // ========================================================

    /**
     * Verify that the real Spring-managed encoder
     * and decoder can generate and validate a token.
     *
     * The token must contain the expected user ID,
     * issuer, audience, and expiration.
     */
    @Test
    void shouldIssueAndVerifyValidAccessToken() {

        // ----------------------------------------------------
        // CREATE A TEST-ONLY AUTHENTICATED USER
        // ----------------------------------------------------

        AuthenticatedUser user = new AuthenticatedUser(
            1L,
            "test@example.com",
            "Test User"
        );


        // ----------------------------------------------------
        // GENERATE THE ACCESS TOKEN
        // ----------------------------------------------------

        String token =
            jwtTokenService.generateAccessToken(user);


        // ----------------------------------------------------
        // VERIFY USING THE CONFIGURED SERVICE
        // ----------------------------------------------------

        Long verifiedUserId =
            jwtTokenService.verifyAndGetUserId(token);

        assertEquals(1L, verifiedUserId);


        // ----------------------------------------------------
        // INSPECT VERIFIED JWT CLAIMS
        // ----------------------------------------------------

        Jwt jwt = jwtDecoder.decode(token);

        assertEquals("1", jwt.getSubject());

        assertEquals(
            issuer,
            jwt.getClaimAsString("iss")
        );

        assertTrue(
            jwt.getAudience().contains(audience)
        );

        assertNotNull(jwt.getIssuedAt());

        assertNotNull(jwt.getExpiresAt());

        assertTrue(
            jwt.getExpiresAt().isAfter(
                jwt.getIssuedAt()
            )
        );

        assertEquals(
            Duration.ofMinutes(15),
            Duration.between(
                jwt.getIssuedAt(),
                jwt.getExpiresAt()
            )
        );
    }


    // ========================================================
    // TEST 2 — WRONG AUDIENCE
    // ========================================================

    /**
     * A JWT signed by LedgerBase must still be rejected
     * when its audience identifies another application.
     *
     * A valid signature alone is not sufficient.
     */
    @Test
    void shouldRejectTokenWithWrongAudience() {

        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .subject("1")
            .audience(List.of("another-api"))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(900))
            .build();

        String token = jwtEncoder.encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims
            )
        ).getTokenValue();

        assertThrows(
            BadJwtException.class,
            () -> jwtTokenService.verifyAndGetUserId(
                token
            )
        );
    }


    // ========================================================
    // TEST 3 — WRONG ISSUER
    // ========================================================

    /**
     * Reject a correctly signed JWT with an unexpected
     * issuer.
     *
     * The decoder's issuer validator must prevent
     * authentication using tokens from another issuer.
     */
    @Test
    void shouldRejectTokenWithWrongIssuer() {

        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("another-issuer")
            .subject("1")
            .audience(List.of(audience))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(900))
            .build();

        String token = jwtEncoder.encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims
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
    // TEST 4 — INVALID SIGNING KEY
    // ========================================================

    /**
     * A JWT signed with a different secret key
     * must fail signature verification.
     *
     * The alternate key exists only inside this test.
     *
     * It must never be reused for real access tokens.
     */
    @Test
    void shouldRejectTokenSignedWithDifferentKey() {

        // ----------------------------------------------------
        // CREATE A DIFFERENT TEST-ONLY KEY
        // ----------------------------------------------------

        byte[] otherKeyBytes = (
            "AnotherTestOnlyJwtSigningKey-NotForProduction"
        ).getBytes(StandardCharsets.UTF_8);

        SecretKey otherKey = new SecretKeySpec(
            otherKeyBytes,
            "HmacSHA256"
        );


        // ----------------------------------------------------
        // CREATE AN INDEPENDENT ENCODER
        // ----------------------------------------------------

        JwtEncoder otherEncoder = NimbusJwtEncoder
            .withSecretKey(otherKey)
            .algorithm(MacAlgorithm.HS256)
            .build();


        // ----------------------------------------------------
        // BUILD OTHERWISE VALID CLAIMS
        // ----------------------------------------------------

        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .subject("1")
            .audience(List.of(audience))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(900))
            .build();


        // ----------------------------------------------------
        // SIGN WITH THE WRONG KEY
        // ----------------------------------------------------

        String token = otherEncoder.encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims
            )
        ).getTokenValue();


        // ----------------------------------------------------
        // EXPECT SIGNATURE VALIDATION FAILURE
        // ----------------------------------------------------

        assertThrows(
            JwtException.class,
            () -> jwtTokenService.verifyAndGetUserId(
                token
            )
        );
    }
}
