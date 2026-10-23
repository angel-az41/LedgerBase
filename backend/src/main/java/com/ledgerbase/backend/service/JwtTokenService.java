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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// ============================================================
// LEDGERBASE — JWT TOKEN SERVICE
// ============================================================

/**
 * Generates and verifies signed JWT access tokens.
 *
 * RESPONSIBILITIES:
 *
 * - Generate short-lived access tokens.
 * - Associate access tokens with PostgreSQL sessions.
 * - Generate new access tokens after session refresh.
 * - Verify JWT signatures and required claims.
 * - Extract authenticated user and session identifiers.
 * - Ensure access tokens do not outlive their sessions.
 *
 * JWT CLAIMS:
 *
 * iss — trusted token issuer
 * sub — authenticated PostgreSQL user ID
 * aud — intended LedgerBase API audience
 * iat — token issuance timestamp
 * exp — token expiration timestamp
 * jti — unique token identifier
 * sid — authentication session UUID
 *
 * SECURITY:
 *
 * JWTs contain no passwords, password hashes,
 * refresh tokens, or financial information.
 *
 * A valid JWT signature alone does not prove that
 * its PostgreSQL authentication session is active.
 *
 * Session revocation and inactivity validation
 * must also be enforced by Spring Security before
 * granting access to protected financial endpoints.
 *
 * This class is registered as a Spring bean through
 * the existing JWT configuration.
 */
public class JwtTokenService {

    // ========================================================
    // JWT CLAIM CONFIGURATION
    // ========================================================

    /**
     * Custom claim identifying the PostgreSQL
     * authentication session associated with a JWT.
     */
    public static final String SESSION_ID_CLAIM = "sid";


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

    /**
     * Receive JWT signing, verification, and lifetime
     * settings from the existing JWT configuration.
     *
     * Reject incomplete or invalid configuration.
     */
    public JwtTokenService(
        JwtEncoder jwtEncoder,
        JwtDecoder jwtDecoder,
        String issuer,
        String audience,
        Duration accessTokenLifetime
    ) {

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
    // LEGACY ACCESS TOKEN GENERATION
    // ========================================================

    /**
     * Generate an access JWT without a session identifier.
     *
     * TEMPORARY COMPATIBILITY:
     *
     * Retained for older JWT tests and callers.
     *
     * New authentication code must use a session-aware
     * method instead.
     *
     * This method will be removed after legacy
     * authentication paths have been migrated.
     *
     * @param user authenticated user identity
     * @return signed legacy access JWT
     */
    @Deprecated
    public String generateAccessToken(
        AuthenticatedUser user
    ) {

        // ====================================================
        // EXTRACT USER ID
        // ====================================================

        /**
         * Validation of the extracted user ID is
         * performed by the shared helper.
         */
        Long userId =
            user == null ? null : user.id();


        // ====================================================
        // GENERATE LEGACY ACCESS TOKEN
        // ====================================================

        return generateAccessTokenInternal(
            userId,
            null,
            null
        );
    }


    // ========================================================
    // ACCESS TOKEN GENERATION AFTER LOGIN
    // ========================================================

    /**
     * Generate a session-aware JWT after successful login.
     *
     * AuthenticatedUser comes from AuthenticationService
     * after BCrypt password verification.
     *
     * The session ID and expiration come from the
     * newly created PostgreSQL authentication session.
     *
     * @param user verified authenticated identity
     * @param sessionId PostgreSQL authentication session
     * @param absoluteExpiresAt session expiration deadline
     * @return signed session-aware JWT
     */
    public String generateAccessToken(
        AuthenticatedUser user,
        UUID sessionId,
        OffsetDateTime absoluteExpiresAt
    ) {

        // ====================================================
        // VALIDATE SESSION INFORMATION
        // ====================================================

        if (
            sessionId == null
            || absoluteExpiresAt == null
        ) {

            throw new IllegalArgumentException(
                "A valid authentication session is required"
            );
        }


        // ====================================================
        // EXTRACT AUTHENTICATED USER ID
        // ====================================================

        Long userId =
            user == null ? null : user.id();


        // ====================================================
        // GENERATE SESSION-AWARE JWT
        // ====================================================

        return generateAccessTokenInternal(
            userId,
            sessionId,
            absoluteExpiresAt.toInstant()
        );
    }


    // ========================================================
    // ACCESS TOKEN GENERATION AFTER SESSION REFRESH
    // ========================================================

    /**
     * Generate a new access JWT after refresh-token
     * rotation succeeds.
     *
     * The user ID is obtained from the PostgreSQL
     * authentication session.
     *
     * The browser cannot select the authenticated
     * user by supplying its own user ID.
     *
     * The existing session UUID is preserved.
     *
     * The session's absolute expiration is not
     * extended when a JWT is renewed.
     *
     * @param userId authenticated PostgreSQL user ID
     * @param sessionId existing session UUID
     * @param absoluteExpiresAt original session deadline
     * @return newly signed access JWT
     */
    public String generateAccessTokenForSession(
        Long userId,
        UUID sessionId,
        OffsetDateTime absoluteExpiresAt
    ) {

        // ====================================================
        // VALIDATE REFRESHED SESSION DETAILS
        // ====================================================

        if (
            userId == null
            || userId <= 0
            || sessionId == null
            || absoluteExpiresAt == null
        ) {

            throw new IllegalArgumentException(
                "Valid authentication session details are required"
            );
        }


        // ====================================================
        // GENERATE SESSION-AWARE JWT
        // ====================================================

        return generateAccessTokenInternal(
            userId,
            sessionId,
            absoluteExpiresAt.toInstant()
        );
    }


    // ========================================================
    // INTERNAL ACCESS TOKEN GENERATION
    // ========================================================

    /**
     * Centralized JWT issuance implementation.
     *
     * All three public JWT generation methods
     * delegate to this helper.
     *
     * SESSION-AWARE TOKENS:
     *
     * - Include the sid claim.
     * - Expire after the configured JWT lifetime.
     * - Cannot outlive the session's absolute deadline.
     *
     * LEGACY TOKENS:
     *
     * - Temporarily omit sid.
     * - Exist only for backward compatibility.
     *
     * @param userId authenticated PostgreSQL user ID
     * @param sessionId optional session UUID for legacy use
     * @param sessionExpiresAt optional absolute deadline
     * @return signed access JWT
     */
    private String generateAccessTokenInternal(
        Long userId,
        UUID sessionId,
        Instant sessionExpiresAt
    ) {

        // ====================================================
        // VALIDATE AUTHENTICATED USER ID
        // ====================================================

        /**
         * This helper receives the numeric user ID,
         * not an AuthenticatedUser object.
         *
         * Validate the Long value directly.
         */
        if (
            userId == null
            || userId <= 0
        ) {

            throw new IllegalArgumentException(
                "A valid authenticated user is required"
            );
        }


        // ====================================================
        // CALCULATE ACCESS TOKEN LIFETIME
        // ====================================================

        Instant issuedAt =
            Instant.now();

        Instant expiresAt =
            issuedAt.plus(accessTokenLifetime);


        // ====================================================
        // ENFORCE ABSOLUTE SESSION EXPIRATION
        // ====================================================

        if (sessionExpiresAt != null) {

            // ------------------------------------------------
            // REJECT AN ALREADY EXPIRED SESSION
            // ------------------------------------------------

            if (!sessionExpiresAt.isAfter(issuedAt)) {

                throw new IllegalArgumentException(
                    "Authentication session has expired"
                );
            }


            // ------------------------------------------------
            // CAP JWT EXPIRATION AT SESSION DEADLINE
            // ------------------------------------------------

            /**
             * A refresh must never extend access beyond
             * the original session expiration.
             */
            if (sessionExpiresAt.isBefore(expiresAt)) {

                expiresAt = sessionExpiresAt;
            }
        }


        // ====================================================
        // BUILD STANDARD JWT CLAIMS
        // ====================================================

        JwtClaimsSet.Builder claimsBuilder =
            JwtClaimsSet.builder()

                .issuer(issuer)

                .subject(userId.toString())

                .audience(List.of(audience))

                .issuedAt(issuedAt)

                .expiresAt(expiresAt)

                .id(UUID.randomUUID().toString());


        // ====================================================
        // INCLUDE POSTGRESQL SESSION IDENTIFIER
        // ====================================================

        /**
         * The sid claim identifies the authentication
         * session associated with the JWT.
         *
         * It is not a refresh credential.
         *
         * The security layer must independently
         * confirm that this session is valid.
         */
        if (sessionId != null) {

            claimsBuilder.claim(
                SESSION_ID_CLAIM,
                sessionId.toString()
            );
        }


        // ====================================================
        // FINALIZE JWT CLAIMS
        // ====================================================

        JwtClaimsSet claims =
            claimsBuilder.build();


        // ====================================================
        // SELECT HS256 SIGNING ALGORITHM
        // ====================================================

        JwsHeader header =
            JwsHeader
                .with(MacAlgorithm.HS256)
                .build();


        // ====================================================
        // SIGN AND RETURN JWT
        // ====================================================

        return jwtEncoder.encode(
            JwtEncoderParameters.from(
                header,
                claims
            )
        ).getTokenValue();
    }


    // ========================================================
    // VERIFY JWT AND EXTRACT USER ID
    // ========================================================

    /**
     * Verify a signed JWT and retrieve its
     * authenticated PostgreSQL user ID.
     *
     * Signature, issuer, audience, and expiration
     * validation are performed before extracting
     * the subject.
     *
     * @param token raw signed JWT
     * @return authenticated database user ID
     */
    public Long verifyAndGetUserId(
        String token
    ) {

        // ====================================================
        // VERIFY JWT
        // ====================================================

        Jwt jwt =
            decodeAndValidateToken(token);


        // ====================================================
        // PARSE DATABASE USER ID
        // ====================================================

        try {

            Long userId =
                Long.parseLong(jwt.getSubject());

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


    // ========================================================
    // VERIFY JWT AND EXTRACT SESSION ID
    // ========================================================

    /**
     * Verify a signed JWT and retrieve its
     * authentication session UUID.
     *
     * Tokens missing sid are rejected.
     *
     * IMPORTANT:
     *
     * This method validates the JWT claim, but
     * does not query PostgreSQL for session status.
     *
     * Server-side revocation checks are required
     * separately in Spring Security.
     *
     * @param token raw signed access JWT
     * @return authentication session UUID
     */
    public UUID verifyAndGetSessionId(
        String token
    ) {

        // ====================================================
        // VERIFY JWT
        // ====================================================

        Jwt jwt =
            decodeAndValidateToken(token);


        // ====================================================
        // READ SESSION CLAIM
        // ====================================================

        String sessionIdValue =
            jwt.getClaimAsString(
                SESSION_ID_CLAIM
            );


        // ====================================================
        // REJECT MISSING SESSION IDENTIFIER
        // ====================================================

        if (
            sessionIdValue == null
            || sessionIdValue.isBlank()
        ) {

            throw new BadJwtException(
                "Access token has no session identifier"
            );
        }


        // ====================================================
        // PARSE SESSION UUID
        // ====================================================

        try {

            return UUID.fromString(
                sessionIdValue
            );

        } catch (IllegalArgumentException exception) {

            throw new BadJwtException(
                "Invalid access token session identifier"
            );
        }
    }


    // ========================================================
    // DECODE AND VALIDATE ACCESS TOKEN
    // ========================================================

    /**
     * Verify cryptographic JWT signature and
     * required application claims.
     *
     * The configured JwtDecoder validates the
     * signature and its configured JWT constraints.
     *
     * These explicit checks additionally verify:
     *
     * - Expected issuer.
     * - Expected audience.
     * - Non-expired access token.
     *
     * PostgreSQL session revocation and inactivity
     * checks remain separate responsibilities.
     *
     * @param token raw signed access JWT
     * @return verified decoded JWT
     */
    private Jwt decodeAndValidateToken(
        String token
    ) {

        // ====================================================
        // REJECT EMPTY ACCESS TOKEN
        // ====================================================

        if (
            token == null
            || token.isBlank()
        ) {

            throw new BadJwtException(
                "Invalid access token"
            );
        }


        // ====================================================
        // DECODE AND VERIFY JWT SIGNATURE
        // ====================================================

        Jwt jwt =
            jwtDecoder.decode(token);


        // ====================================================
        // VALIDATE TOKEN ISSUER
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
        // VALIDATE TOKEN AUDIENCE
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
        // VALIDATE TOKEN EXPIRATION
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
        // RETURN VERIFIED JWT
        // ====================================================

        return jwt;
    }
}
