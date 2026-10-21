package com.ledgerbase.backend.service;

import com.ledgerbase.backend.mapper.AuthSessionMapper;
import com.ledgerbase.backend.model.AuthRefreshToken;
import com.ledgerbase.backend.model.AuthSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — REFRESH SESSION SERVICE TESTS
// ============================================================

/**
 * Tests the refresh-token rotation rules.
 *
 * Mockito isolates the service from PostgreSQL,
 * allowing us to simulate valid sessions, expired
 * sessions, token replay, and database failures.
 *
 * Real transaction and concurrency behavior will
 * be tested separately against PostgreSQL.
 */
@ExtendWith(MockitoExtension.class)
class AuthSessionRefreshTest {

    // ========================================================
    // TEST CREDENTIALS
    // ========================================================

    private static final Long USER_ID = 1L;

    private static final UUID SESSION_ID =
        UUID.fromString(
            "71c59f65-85c4-4d3e-91ea-31d20d747007"
        );

    private static final String OLD_TOKEN =
        "A".repeat(43);

    private static final String NEW_TOKEN =
        "B".repeat(43);

    private static final String OLD_HASH =
        "a".repeat(64);

    private static final String NEW_HASH =
        "b".repeat(64);


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
    // TEST SESSION FACTORY
    // ========================================================

    /**
     * Create a test session with controllable
     * activity, expiration, and revocation times.
     */
    private AuthSession session(
        OffsetDateTime lastActivity,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt
    ) {

        return new AuthSession(
            SESSION_ID,
            USER_ID,
            lastActivity.minusHours(1),
            lastActivity,
            expiresAt,
            revokedAt
        );
    }


    // ========================================================
    // TEST TOKEN FACTORY
    // ========================================================

    private AuthRefreshToken token(
        OffsetDateTime consumedAt
    ) {

        return new AuthRefreshToken(
            OLD_HASH,
            SESSION_ID,
            OffsetDateTime.now(ZoneOffset.UTC)
                .minusMinutes(10),
            consumedAt
        );
    }


    // ========================================================
    // COMMON TOKEN LOOKUP
    // ========================================================

    /**
     * Configure the initial credential lookup.
     *
     * Additional database interactions are stubbed
     * individually by each test.
     */
    private void configureTokenLookup() {

        when(refreshTokenCodec.hashToken(OLD_TOKEN))
            .thenReturn(OLD_HASH);

        when(
            authSessionMapper.findSessionIdByTokenHash(
                OLD_HASH
            )
        ).thenReturn(SESSION_ID);
    }


    // ========================================================
    // TEST — SUCCESSFUL TOKEN ROTATION
    // ========================================================

    @Test
    void shouldRotateValidRefreshToken() {

        configureTokenLookup();

        OffsetDateTime now =
            OffsetDateTime.now(ZoneOffset.UTC);

        when(authSessionMapper.lockSession(SESSION_ID))
            .thenReturn(
                session(
                    now.minusMinutes(5),
                    now.plusHours(7),
                    null
                )
            );

        when(
            authSessionMapper.findRefreshTokenByHash(
                OLD_HASH
            )
        ).thenReturn(token(null));

        when(refreshTokenCodec.generateToken())
            .thenReturn(NEW_TOKEN);

        when(refreshTokenCodec.hashToken(NEW_TOKEN))
            .thenReturn(NEW_HASH);

        when(authSessionMapper.consumeRefreshToken(OLD_HASH))
            .thenReturn(1);

        when(
            authSessionMapper.insertRefreshToken(
                NEW_HASH,
                SESSION_ID
            )
        ).thenReturn(1);

        when(authSessionMapper.updateLastActivity(SESSION_ID))
            .thenReturn(1);


        // ----------------------------------------------------
        // EXECUTE REFRESH
        // ----------------------------------------------------

        Optional<AuthSessionService.RefreshedSession> result =
            authSessionService.refreshSession(OLD_TOKEN);

        assertTrue(result.isPresent());

        AuthSessionService.RefreshedSession refreshed =
            result.orElseThrow();


        // ----------------------------------------------------
        // VERIFY NEW CREDENTIAL
        // ----------------------------------------------------

        assertEquals(USER_ID, refreshed.userId());
        assertEquals(SESSION_ID, refreshed.sessionId());
        assertEquals(NEW_TOKEN, refreshed.refreshToken());

        assertFalse(
            refreshed.toString().contains(NEW_TOKEN)
        );


        // ----------------------------------------------------
        // VERIFY WRITE ORDER
        // ----------------------------------------------------

        var ordered = inOrder(authSessionMapper);

        ordered.verify(authSessionMapper)
            .consumeRefreshToken(OLD_HASH);

        ordered.verify(authSessionMapper)
            .insertRefreshToken(NEW_HASH, SESSION_ID);

        ordered.verify(authSessionMapper)
            .updateLastActivity(SESSION_ID);

        verify(authSessionMapper, never())
            .revokeSession(any(UUID.class));
    }


    // ========================================================
    // TEST — CONSUMED TOKEN REPLAY
    // ========================================================

    @Test
    void shouldRevokeSessionWhenTokenIsReused() {

        configureTokenLookup();

        OffsetDateTime now =
            OffsetDateTime.now(ZoneOffset.UTC);

        when(authSessionMapper.lockSession(SESSION_ID))
            .thenReturn(
                session(
                    now.minusMinutes(5),
                    now.plusHours(7),
                    null
                )
            );

        when(
            authSessionMapper.findRefreshTokenByHash(
                OLD_HASH
            )
        ).thenReturn(
            token(now.minusMinutes(1))
        );

        Optional<AuthSessionService.RefreshedSession> result =
            authSessionService.refreshSession(OLD_TOKEN);

        assertTrue(result.isEmpty());

        verify(authSessionMapper)
            .revokeSession(SESSION_ID);

        verify(refreshTokenCodec, never())
            .generateToken();
    }


    // ========================================================
    // TEST — INACTIVITY TIMEOUT
    // ========================================================

    @Test
    void shouldRevokeSessionAfterThirtyMinutesInactivity() {

        configureTokenLookup();

        OffsetDateTime now =
            OffsetDateTime.now(ZoneOffset.UTC);

        when(authSessionMapper.lockSession(SESSION_ID))
            .thenReturn(
                session(
                    now.minusMinutes(31),
                    now.plusHours(7),
                    null
                )
            );

        Optional<AuthSessionService.RefreshedSession> result =
            authSessionService.refreshSession(OLD_TOKEN);

        assertTrue(result.isEmpty());

        verify(authSessionMapper)
            .revokeSession(SESSION_ID);

        verify(refreshTokenCodec, never())
            .generateToken();
    }


    // ========================================================
    // TEST — ABSOLUTE EXPIRATION
    // ========================================================

    @Test
    void shouldRevokeSessionAfterAbsoluteExpiration() {

        configureTokenLookup();

        OffsetDateTime now =
            OffsetDateTime.now(ZoneOffset.UTC);

        when(authSessionMapper.lockSession(SESSION_ID))
            .thenReturn(
                session(
                    now.minusMinutes(2),
                    now.minusSeconds(1),
                    null
                )
            );

        Optional<AuthSessionService.RefreshedSession> result =
            authSessionService.refreshSession(OLD_TOKEN);

        assertTrue(result.isEmpty());

        verify(authSessionMapper)
            .revokeSession(SESSION_ID);
    }


    // ========================================================
    // TEST — ALREADY REVOKED SESSION
    // ========================================================

    @Test
    void shouldRejectAlreadyRevokedSession() {

        configureTokenLookup();

        OffsetDateTime now =
            OffsetDateTime.now(ZoneOffset.UTC);

        when(authSessionMapper.lockSession(SESSION_ID))
            .thenReturn(
                session(
                    now.minusMinutes(2),
                    now.plusHours(7),
                    now.minusMinutes(1)
                )
            );

        Optional<AuthSessionService.RefreshedSession> result =
            authSessionService.refreshSession(OLD_TOKEN);

        assertTrue(result.isEmpty());

        verify(authSessionMapper, never())
            .insertRefreshToken(
                anyString(),
                any(UUID.class)
            );
    }


    // ========================================================
    // TEST — MALFORMED TOKEN
    // ========================================================

    @Test
    void shouldRejectMalformedRefreshToken() {

        when(
            refreshTokenCodec.hashToken("invalid")
        ).thenThrow(
            new IllegalArgumentException(
                "Invalid refresh token format"
            )
        );

        Optional<AuthSessionService.RefreshedSession> result =
            authSessionService.refreshSession("invalid");

        assertTrue(result.isEmpty());

        verifyNoInteractions(authSessionMapper);
    }


    // ========================================================
    // TEST — REPLACEMENT INSERT FAILURE
    // ========================================================

    @Test
    void shouldThrowWhenReplacementCannotBeStored() {

        configureTokenLookup();

        OffsetDateTime now =
            OffsetDateTime.now(ZoneOffset.UTC);

        when(authSessionMapper.lockSession(SESSION_ID))
            .thenReturn(
                session(
                    now.minusMinutes(5),
                    now.plusHours(7),
                    null
                )
            );

        when(
            authSessionMapper.findRefreshTokenByHash(
                OLD_HASH
            )
        ).thenReturn(token(null));

        when(refreshTokenCodec.generateToken())
            .thenReturn(NEW_TOKEN);

        when(refreshTokenCodec.hashToken(NEW_TOKEN))
            .thenReturn(NEW_HASH);

        when(authSessionMapper.consumeRefreshToken(OLD_HASH))
            .thenReturn(1);

        when(
            authSessionMapper.insertRefreshToken(
                NEW_HASH,
                SESSION_ID
            )
        ).thenReturn(0);


        // ----------------------------------------------------
        // ASSERT FAILURE PROPAGATION
        // ----------------------------------------------------

        assertThrows(
            IllegalStateException.class,
            () -> authSessionService.refreshSession(
                OLD_TOKEN
            )
        );

        verify(authSessionMapper, never())
            .updateLastActivity(SESSION_ID);
    }
}
