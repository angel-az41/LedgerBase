package com.ledgerbase.backend.security;

import com.ledgerbase.backend.mapper.AuthSessionMapper;
import com.ledgerbase.backend.service.JwtTokenService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — JWT SESSION VALIDATOR UNIT TESTS
// ============================================================

/**
 * Verify JWT session-validation decisions.
 *
 * The PostgreSQL mapper is mocked so these
 * tests can exercise invalid claims without
 * starting Spring Boot or a database.
 *
 * Real SQL behavior is tested separately.
 */
@ExtendWith(MockitoExtension.class)
class AuthSessionJwtValidatorTest {

    // ========================================================
    // MOCK DEPENDENCY
    // ========================================================

    @Mock
    private AuthSessionMapper authSessionMapper;

    @InjectMocks
    private AuthSessionJwtValidator validator;


    // ========================================================
    // TEST SESSION IDENTIFIER
    // ========================================================

    private static final UUID SESSION_ID =
        UUID.fromString(
            "bb91a66d-cc37-42de-b180-f58c92b62909"
        );


    // ========================================================
    // JWT TEST HELPER
    // ========================================================

    /**
     * Build a test JWT with controlled sub and sid claims.
     *
     * This is a decoded test object, not a signed
     * authentication credential.
     */
    private Jwt jwt(
        String subject,
        Object sessionIdClaim
    ) {

        Jwt.Builder builder =
            Jwt.withTokenValue(
                "test.jwt.token"
            )
            .header("alg", "HS256")
            .subject(subject)
            .issuedAt(Instant.now())
            .expiresAt(
                Instant.now().plusSeconds(900)
            );

        if (sessionIdClaim != null) {

            builder.claim(
                JwtTokenService.SESSION_ID_CLAIM,
                sessionIdClaim
            );
        }

        return builder.build();
    }


    // ========================================================
    // TEST — VALID SESSION
    // ========================================================

    @Test
    void shouldAcceptActiveSession() {

        when(
            authSessionMapper.isSessionActiveForUser(
                SESSION_ID,
                7L
            )
        ).thenReturn(true);

        OAuth2TokenValidatorResult result =
            validator.validate(
                jwt("7", SESSION_ID.toString())
            );

        assertFalse(result.hasErrors());

        verify(authSessionMapper)
            .isSessionActiveForUser(
                SESSION_ID,
                7L
            );
    }


    // ========================================================
    // TEST — REVOKED OR EXPIRED SESSION
    // ========================================================

    @Test
    void shouldRejectInactiveSession() {

        when(
            authSessionMapper.isSessionActiveForUser(
                SESSION_ID,
                7L
            )
        ).thenReturn(false);

        OAuth2TokenValidatorResult result =
            validator.validate(
                jwt("7", SESSION_ID.toString())
            );

        assertTrue(result.hasErrors());
    }


    // ========================================================
    // TEST — MISSING SESSION IDENTIFIER
    // ========================================================

    @Test
    void shouldRejectMissingSessionId() {

        OAuth2TokenValidatorResult result =
            validator.validate(
                jwt("7", null)
            );

        assertTrue(result.hasErrors());

        verifyNoInteractions(authSessionMapper);
    }


    // ========================================================
    // TEST — MALFORMED SESSION IDENTIFIER
    // ========================================================

    @Test
    void shouldRejectMalformedSessionId() {

        OAuth2TokenValidatorResult result =
            validator.validate(
                jwt("7", "not-a-uuid")
            );

        assertTrue(result.hasErrors());

        verifyNoInteractions(authSessionMapper);
    }


    // ========================================================
    // TEST — INVALID USER ID
    // ========================================================

    @Test
    void shouldRejectInvalidUserId() {

        OAuth2TokenValidatorResult result =
            validator.validate(
                jwt("not-a-user-id", SESSION_ID.toString())
            );

        assertTrue(result.hasErrors());

        verifyNoInteractions(authSessionMapper);
    }


    // ========================================================
    // TEST — WRONG SESSION CLAIM TYPE
    // ========================================================

    @Test
    void shouldRejectNonStringSessionClaim() {

        OAuth2TokenValidatorResult result =
            validator.validate(
                jwt("7", 12345)
            );

        assertTrue(result.hasErrors());

        verifyNoInteractions(authSessionMapper);
    }
}
