package com.ledgerbase.backend.service;

import com.ledgerbase.backend.mapper.AuthSessionMapper;
import com.ledgerbase.backend.model.AuthSession;
import com.ledgerbase.backend.model.AuthRefreshToken;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — AUTHENTICATION LOGOUT SERVICE TESTS
// ============================================================

/**
 * Tests server-side authentication session revocation.
 *
 * TEST ARCHITECTURE:
 *
 * AuthSessionService
 *     |
 *     +--- Mock AuthSessionMapper
 *     |
 *     +--- Mock RefreshTokenCodec
 *
 * These tests verify logout decisions without
 * modifying PostgreSQL records.
 *
 * Actual transaction behavior is covered separately
 * through database integration tests.
 */
@ExtendWith(MockitoExtension.class)
class AuthSessionLogoutTest {

    // ========================================================
    // TEST IDENTIFIERS
    // ========================================================

    private static final UUID SESSION_ID =
        UUID.fromString(
            "91fa37fb-c0ed-4f65-88e9-46252e90d81c"
        );

    private static final String TEST_TOKEN =
        "A".repeat(43);

    private static final String TEST_HASH =
        "a".repeat(64);


    // ========================================================
    // MOCKED DEPENDENCIES
    // ========================================================

    @Mock
    private AuthSessionMapper authSessionMapper;

    @Mock
    private RefreshTokenCodec refreshTokenCodec;

    @InjectMocks
    private AuthSessionService authSessionService;


    // ========================================================
    // TEST DATA HELPERS
    // ========================================================

    /**
     * Construct a session with controllable
     * revocation status.
     */
    private AuthSession createSession(
        OffsetDateTime revokedAt
    ) {

        OffsetDateTime now =
            OffsetDateTime.now(ZoneOffset.UTC);

        return new AuthSession(
            SESSION_ID,
            1L,
            now.minusMinutes(10),
            now.minusMinutes(2),
            now.plusHours(7),
            revokedAt
        );
    }


    /**
     * Construct a token belonging to the test session.
     */
    private AuthRefreshToken createToken() {

        return new AuthRefreshToken(
            TEST_HASH,
            SESSION_ID,
            OffsetDateTime.now(ZoneOffset.UTC)
                .minusMinutes(10),
            null
        );
    }


    /**
     * Configure a known token and session lookup.
     */
    private void configureKnownToken() {

        when(refreshTokenCodec.hashToken(TEST_TOKEN))
            .thenReturn(TEST_HASH);

        when(
            authSessionMapper.findSessionIdByTokenHash(
                TEST_HASH
            )
        ).thenReturn(SESSION_ID);
    }


    // ========================================================
    // TEST — SUCCESSFUL LOGOUT
    // ========================================================

    @Test
    void shouldRevokeActiveSession() {

        configureKnownToken();

        when(authSessionMapper.lockSession(SESSION_ID))
            .thenReturn(createSession(null));

        when(
            authSessionMapper.findRefreshTokenByHash(
                TEST_HASH
            )
        ).thenReturn(createToken());

        when(authSessionMapper.revokeSession(SESSION_ID))
            .thenReturn(1);


        // ----------------------------------------------------
        // REVOKE SESSION
        // ----------------------------------------------------

        boolean revoked =
            authSessionService.revokeSessionByRefreshToken(
                TEST_TOKEN
            );


        // ----------------------------------------------------
        // VERIFY RESULT AND DATABASE OPERATION
        // ----------------------------------------------------

        assertTrue(revoked);

        verify(authSessionMapper)
            .revokeSession(SESSION_ID);
    }


    // ========================================================
    // TEST — ALREADY REVOKED SESSION
    // ========================================================

    @Test
    void shouldNotRevokeSessionTwice() {

        configureKnownToken();

        OffsetDateTime now =
            OffsetDateTime.now(ZoneOffset.UTC);

        when(authSessionMapper.lockSession(SESSION_ID))
            .thenReturn(createSession(now));

        when(
            authSessionMapper.findRefreshTokenByHash(
                TEST_HASH
            )
        ).thenReturn(createToken());


        boolean revoked =
            authSessionService.revokeSessionByRefreshToken(
                TEST_TOKEN
            );

        assertFalse(revoked);

        verify(authSessionMapper, never())
            .revokeSession(SESSION_ID);
    }


    // ========================================================
    // TEST — UNKNOWN TOKEN
    // ========================================================

    @Test
    void shouldRejectUnknownRefreshToken() {

        when(refreshTokenCodec.hashToken(TEST_TOKEN))
            .thenReturn(TEST_HASH);

        // An unknown hash returns no session ID.

        boolean revoked =
            authSessionService.revokeSessionByRefreshToken(
                TEST_TOKEN
            );

        assertFalse(revoked);

        verify(authSessionMapper, never())
            .lockSession(any(UUID.class));

        verify(authSessionMapper, never())
            .revokeSession(any(UUID.class));
    }


    // ========================================================
    // TEST — MALFORMED TOKEN
    // ========================================================

    @Test
    void shouldRejectMalformedRefreshToken() {

        when(refreshTokenCodec.hashToken("invalid"))
            .thenThrow(
                new IllegalArgumentException(
                    "Invalid refresh token format"
                )
            );

        boolean revoked =
            authSessionService.revokeSessionByRefreshToken(
                "invalid"
            );

        assertFalse(revoked);

        verifyNoInteractions(authSessionMapper);
    }


    // ========================================================
    // TEST — MISMATCHED TOKEN ASSOCIATION
    // ========================================================

    @Test
    void shouldRejectMismatchedTokenSession() {

        configureKnownToken();

        when(authSessionMapper.lockSession(SESSION_ID))
            .thenReturn(createSession(null));

        UUID differentSessionId = UUID.randomUUID();

        when(
            authSessionMapper.findRefreshTokenByHash(
                TEST_HASH
            )
        ).thenReturn(
            new AuthRefreshToken(
                TEST_HASH,
                differentSessionId,
                OffsetDateTime.now(ZoneOffset.UTC),
                null
            )
        );


        boolean revoked =
            authSessionService.revokeSessionByRefreshToken(
                TEST_TOKEN
            );

        assertFalse(revoked);

        verify(authSessionMapper, never())
            .revokeSession(any(UUID.class));
    }
}
