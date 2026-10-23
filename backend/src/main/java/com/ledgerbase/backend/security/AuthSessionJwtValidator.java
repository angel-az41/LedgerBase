package com.ledgerbase.backend.security;

import com.ledgerbase.backend.mapper.AuthSessionMapper;
import com.ledgerbase.backend.service.JwtTokenService;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.stereotype.Component;

import java.util.UUID;

// ============================================================
// LEDGERBASE — JWT AUTHENTICATION SESSION VALIDATOR
// ============================================================

/**
 * Validates the PostgreSQL session associated with
 * a signed LedgerBase JWT access token.
 *
 * PURPOSE:
 *
 * A signed JWT is not sufficient for authentication
 * when its underlying login session was revoked.
 *
 * VALIDATION FLOW:
 *
 * 1. Read the JWT sub claim.
 * 2. Parse the authenticated PostgreSQL user ID.
 * 3. Read the JWT sid claim.
 * 4. Parse the authentication session UUID.
 * 5. Check the session against PostgreSQL.
 * 6. Accept only an active session owned by that user.
 *
 * SECURITY:
 *
 * - Missing session identifiers are rejected.
 * - Malformed identifiers are rejected.
 * - Incorrect user/session associations are rejected.
 * - Revoked and expired sessions are rejected.
 *
 * This validator does not modify the database.
 *
 * IMPORTANT:
 *
 * JWT signature, issuer, audience, and expiration
 * validation remain the responsibility of the
 * existing JwtDecoder configuration.
 *
 * This component will be connected to JwtDecoder
 * after its individual behavior has been tested.
 */
@Component
public class AuthSessionJwtValidator
    implements OAuth2TokenValidator<Jwt> {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    private final AuthSessionMapper authSessionMapper;


    // ========================================================
    // GENERIC VALIDATION ERROR
    // ========================================================

    /**
     * Return one generic error for invalid sessions.
     *
     * Do not expose whether the user ID, session UUID,
     * revocation state, or expiration caused rejection.
     */
    private static final OAuth2Error INVALID_SESSION =
        new OAuth2Error(
            "invalid_token",
            "Invalid authentication session",
            null
        );


    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public AuthSessionJwtValidator(
        AuthSessionMapper authSessionMapper
    ) {

        this.authSessionMapper = authSessionMapper;
    }


    // ========================================================
    // VALIDATE JWT SESSION
    // ========================================================

    /**
     * Verify that the JWT identifies an active,
     * server-side authentication session.
     *
     * The JWT signature must be verified by the
     * configured decoder before this validator
     * is used for authorization decisions.
     *
     * @param jwt decoded JWT access token
     * @return success or a generic invalid-token error
     */
    @Override
    public OAuth2TokenValidatorResult validate(
        Jwt jwt
    ) {

        // ====================================================
        // REJECT MISSING JWT
        // ====================================================

        if (jwt == null) {

            return invalidSession();
        }


        // ====================================================
        // VALIDATE AUTHENTICATED USER ID
        // ====================================================

        Long userId;

        try {

            userId = Long.parseLong(
                jwt.getSubject()
            );

            if (userId <= 0) {

                return invalidSession();
            }

        } catch (
            NumberFormatException
            | NullPointerException exception
        ) {

            return invalidSession();
        }


        // ====================================================
        // READ SESSION CLAIM
        // ====================================================

        /**
         * The sid claim must be a string.
         *
         * Reject unexpected claim types rather
         * than coercing arbitrary values into UUIDs.
         */
        Object rawSessionId =
            jwt.getClaims().get(
                JwtTokenService.SESSION_ID_CLAIM
            );

        if (!(rawSessionId instanceof String sessionIdText)) {

            return invalidSession();
        }


        // ====================================================
        // PARSE SESSION UUID
        // ====================================================

        UUID sessionId;

        try {

            sessionId = UUID.fromString(
                sessionIdText
            );

            // Require the canonical UUID representation.
            if (!sessionId.toString().equals(sessionIdText)) {

                return invalidSession();
            }

        } catch (IllegalArgumentException exception) {

            return invalidSession();
        }


        // ====================================================
        // CHECK POSTGRESQL SESSION STATUS
        // ====================================================

        /**
         * PostgreSQL checks:
         *
         * - Session existence.
         * - Authenticated user ownership.
         * - Revocation.
         * - Absolute expiration.
         * - Inactivity timeout.
         *
         * Any unavailable or invalid session
         * must fail authentication.
         *
         * Unexpected database failures are not
         * converted into successful validation.
         */
        boolean sessionActive =
            authSessionMapper.isSessionActiveForUser(
                sessionId,
                userId
            );


        // ====================================================
        // RETURN VALIDATION RESULT
        // ====================================================

        if (!sessionActive) {

            return invalidSession();
        }

        return OAuth2TokenValidatorResult.success();
    }


    // ========================================================
    // BUILD INVALID SESSION RESULT
    // ========================================================

    /**
     * Keep authentication errors generic.
     */
    private OAuth2TokenValidatorResult invalidSession() {

        return OAuth2TokenValidatorResult.failure(
            INVALID_SESSION
        );
    }
}
