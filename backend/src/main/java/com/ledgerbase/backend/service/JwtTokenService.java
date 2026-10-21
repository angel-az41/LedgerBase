package com.ledgerbase.backend.service;

import com.ledgerbase.backend.dto.AuthenticatedUser;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// ============================================================
// LEDGERBASE — JWT TOKEN SERVICE
// ============================================================

/**
 * Generates and verifies signed JWT access tokens.
 *
 * PURPOSE:
 *
 * 1. Issue an access token after successful login.
 * 2. Verify token signatures and required claims.
 * 3. Extract the authenticated user ID.
 *
 * SECURITY:
 *
 * - Uses the HS256 signing algorithm.
 * - Requires a properly configured JWT encoder/decoder.
 * - Validates token issuer and intended audience.
 * - Rejects expired access tokens.
 * - Never stores passwords or financial data in JWTs.
 *
 * IMPORTANT:
 *
 * This class is intentionally not annotated with
 * @Service yet.
 *
 * We will register it as a Spring bean when we
 * configure the signing secret in the next step.
 *
 * This prevents the existing backend from requiring
 * JWT configuration before it is ready.
 */
public class JwtTokenService {

    // ========================================================
    // DEPENDENCIES AND CONFIGURATION
    // ========================================================

    private final JwtEncoder jwtEncoder;

    private final JwtDecoder jwtDecoder;

    private final String issuer;

    private final String audience;

    private final Duration accessTokenLifetime;


    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    public JwtTokenService(
        JwtEncoder jwtEncoder,
        JwtDecoder jwtDecoder,
        String issuer,
        String audience,
        Duration accessTokenLifetime
    ) {

        // Reject incomplete token configuration.

        if (
            jwtEncoder == null
            || jwtDecoder == null
            || issuer == null
            || issuer.isBlank()
            || audience == null
            || audience.isBlank()
            || accessTokenLifetime == null
            || accessTokenLifetime.isNegative()
            || accessTokenLifetime.isZero()
        ) {

            throw new IllegalArgumentException(
                "Invalid JWT configuration"
            );
        }

        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
        this.issuer = issuer;
        this.audience = audience;
        this.accessTokenLifetime = accessTokenLifetime;
    }


    // ========================================================
    // 1. GENERATE ACCESS TOKEN
    // ========================================================

    /**
     * Issue a signed access token for a user who has
     * already passed password authentication.
     *
     * The user ID becomes the JWT subject.
     *
     * We intentionally omit business roles. Future
     * authorization services will check memberships
     * against PostgreSQL.
     *
     * @param user previously authenticated identity
     * @return signed JWT access token
     */
    public String generateAccessToken(
        AuthenticatedUser user
    ) {

        // ====================================================
        // VALIDATE USER ID
        // ====================================================

        if (
            user == null
            || user.id() == null
            || user.id() <= 0
        ) {

            throw new IllegalArgumentException(
                "A valid authenticated user is required"
            );
        }


        // ====================================================
        // CALCULATE TOKEN LIFETIME
        // ====================================================

        Instant issuedAt = Instant.now();

        Instant expiresAt = issuedAt.plus(
            accessTokenLifetime
        );


        // ====================================================
        // BUILD JWT CLAIMS
        // ====================================================

        JwtClaimsSet claims = JwtClaimsSet.builder()

            .issuer(issuer)

            .subject(user.id().toString())

            .audience(List.of(audience))

            .issuedAt(issuedAt)

            .expiresAt(expiresAt)

            .id(UUID.randomUUID().toString())

            .build();


        // ====================================================
        // SPECIFY THE SIGNING ALGORITHM
        // ====================================================

        JwsHeader header = JwsHeader
            .with(MacAlgorithm.HS256)
            .build();


        // ====================================================
        // SIGN AND RETURN TOKEN
        // ====================================================

        return jwtEncoder.encode(
            JwtEncoderParameters.from(
                header,
                claims
            )
        ).getTokenValue();
    }


    // ========================================================
    // 2. VERIFY TOKEN AND EXTRACT USER ID
    // ========================================================

    /**
     * Decode and validate a JWT access token.
     *
     * The JwtDecoder must cryptographically verify
     * the signature using our trusted signing key.
     *
     * We additionally require:
     *
     * - Expected issuer
     * - Expected audience
     * - An unexpired token
     * - A positive numeric user ID
     *
     * The decoder will also be configured with
     * Spring Security's standard JWT validators.
     *
     * @param token raw access token
     * @return authenticated database user ID
     */
    public Long verifyAndGetUserId(
        String token
    ) {

        // ====================================================
        // REJECT EMPTY TOKEN
        // ====================================================

        if (token == null || token.isBlank()) {

            throw new BadJwtException(
                "Invalid access token"
            );
        }


        // ====================================================
        // VERIFY SIGNATURE AND DECODE
        // ====================================================

        // A signature verification failure causes
        // JwtDecoder to throw a JwtException.

        Jwt jwt = jwtDecoder.decode(token);


        // ====================================================
        // VALIDATE ISSUER
        // ====================================================

        if (
            !issuer.equals(
                jwt.getClaimAsString("iss")
            )
        ) {

            throw new BadJwtException(
                "Invalid access token issuer"
            );
        }


        // ====================================================
        // VALIDATE AUDIENCE
        // ====================================================

        if (
            jwt.getAudience() == null
            || !jwt.getAudience().contains(audience)
        ) {

            throw new BadJwtException(
                "Invalid access token audience"
            );
        }


        // ====================================================
        // VALIDATE EXPIRATION
        // ====================================================

        if (
            jwt.getExpiresAt() == null
            || !jwt.getExpiresAt().isAfter(
                Instant.now()
            )
        ) {

            throw new BadJwtException(
                "Access token expired"
            );
        }


        // ====================================================
        // EXTRACT TRUSTED USER ID
        // ====================================================

        try {

            Long userId = Long.parseLong(
                jwt.getSubject()
            );

            if (userId <= 0) {

                throw new NumberFormatException(
                    "Invalid user ID"
                );
            }

            return userId;

        } catch (
            NumberFormatException
            | NullPointerException exception
        ) {

            throw new BadJwtException(
                "Invalid access token subject"
            );
        }
    }
}
