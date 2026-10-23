package com.ledgerbase.backend.service;

import com.ledgerbase.backend.mapper.AuthSessionMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — AUTHENTICATION SESSION SERVICE TESTS
// ============================================================

/**
 * Unit tests for authentication session creation.
 *
 * TEST ARCHITECTURE:
 *
 * AuthSessionService
 *     |
 *     +--- Mock AuthSessionMapper
 *     |
 *     +--- Mock RefreshTokenCodec
 *
 * These tests verify service behavior without
 * starting Spring Boot or connecting to PostgreSQL.
 *
 * Database transaction behavior is verified
 * separately in integration tests.
 */
@ExtendWith(MockitoExtension.class)
class AuthSessionServiceTest {

    // ========================================================
    // TEST CONSTANTS
    // ========================================================

    private static final Long TEST_USER_ID = 1L;

    private static final String TEST_REFRESH_TOKEN =
        "A".repeat(43);

    private static final String TEST_TOKEN_HASH =
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
    // TEST CONFIGURATION HELPER
    // ========================================================

    /**
     * Configure successful token generation and hashing.
     *
     * These are test-only values, not actual credentials.
     */
    private void configureTokenGeneration() {

        when(refreshTokenCodec.generateToken())
            .thenReturn(TEST_REFRESH_TOKEN);

        when(
            refreshTokenCodec.hashToken(
                TEST_REFRESH_TOKEN
            )
        ).thenReturn(TEST_TOKEN_HASH);
    }


    // ========================================================
    // TEST — SUCCESSFUL SESSION CREATION
    // ========================================================

    /**
     * Verify:
     *
     * - Session ID is generated.
     * - Absolute expiration is approximately 8 hours.
     * - Both database inserts are requested.
     * - The raw token is not sent to PostgreSQL.
     * - The session result does not print the token.
     */
    @Test
    void shouldCreateSessionAndStoreOnlyTokenHash() {

        configureTokenGeneration();

        when(
            authSessionMapper.insertSession(
                any(UUID.class),
                eq(TEST_USER_ID),
                any(OffsetDateTime.class)
            )
        ).thenReturn(1);

        when(
            authSessionMapper.insertRefreshToken(
                eq(TEST_TOKEN_HASH),
                any(UUID.class)
            )
        ).thenReturn(1);


        // ----------------------------------------------------
        // CREATE SESSION
        // ----------------------------------------------------

        Instant before = Instant.now();

        AuthSessionService.CreatedSession created =
            authSessionService.createSession(
                TEST_USER_ID
            );

        Instant after = Instant.now();


        // ----------------------------------------------------
        // VERIFY SESSION RESULT
        // ----------------------------------------------------

        assertNotNull(created.sessionId());

        assertEquals(
            TEST_REFRESH_TOKEN,
            created.refreshToken()
        );

        assertFalse(
            created.absoluteExpiresAt()
                .toInstant()
                .isBefore(before.plus(Duration.ofHours(8)))
        );

        assertFalse(
            created.absoluteExpiresAt()
                .toInstant()
                .isAfter(after.plus(Duration.ofHours(8)))
        );


        // ----------------------------------------------------
        // VERIFY DATABASE CALLS
        // ----------------------------------------------------

        verify(authSessionMapper).insertSession(
            eq(created.sessionId()),
            eq(TEST_USER_ID),
            eq(created.absoluteExpiresAt())
        );

        verify(authSessionMapper).insertRefreshToken(
            eq(TEST_TOKEN_HASH),
            eq(created.sessionId())
        );


        // ----------------------------------------------------
        // ENSURE SECRET IS NOT PRINTED
        // ----------------------------------------------------

        assertFalse(
            created.toString().contains(
                TEST_REFRESH_TOKEN
            )
        );

        assertTrue(
            created.toString().contains(
                "[REDACTED]"
            )
        );
    }


    // ========================================================
    // TEST — INVALID USER ID
    // ========================================================

    /**
     * Invalid IDs must be rejected before any
     * token generation or database operations.
     */
    @Test
    void shouldRejectInvalidUserIds() {

        assertThrows(
            IllegalArgumentException.class,
            () -> authSessionService.createSession(null)
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> authSessionService.createSession(0L)
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> authSessionService.createSession(-1L)
        );

        verifyNoInteractions(
            authSessionMapper,
            refreshTokenCodec
        );
    }


    // ========================================================
    // TEST — FAILED SESSION INSERT
    // ========================================================

    /**
     * Fail if the session insert does not affect
     * exactly one database row.
     *
     * No refresh-token record should be inserted.
     */
    @Test
    void shouldStopWhenSessionInsertFails() {

        configureTokenGeneration();

        when(
            authSessionMapper.insertSession(
                any(UUID.class),
                eq(TEST_USER_ID),
                any(OffsetDateTime.class)
            )
        ).thenReturn(0);

        assertThrows(
            IllegalStateException.class,
            () -> authSessionService.createSession(
                TEST_USER_ID
            )
        );

        verify(
            authSessionMapper,
            never()
        ).insertRefreshToken(
            anyString(),
            any(UUID.class)
        );
    }


    // ========================================================
    // TEST — FAILED REFRESH TOKEN INSERT
    // ========================================================

    /**
     * If the refresh-token insert fails, the
     * service must propagate a runtime exception.
     *
     * Spring's transaction interceptor will then
     * roll back both writes when the method is
     * invoked through its Spring-managed bean.
     *
     * This unit test checks exception propagation,
     * not the actual database rollback.
     */
    @Test
    void shouldFailWhenRefreshTokenInsertFails() {

        configureTokenGeneration();

        when(
            authSessionMapper.insertSession(
                any(UUID.class),
                eq(TEST_USER_ID),
                any(OffsetDateTime.class)
            )
        ).thenReturn(1);

        when(
            authSessionMapper.insertRefreshToken(
                eq(TEST_TOKEN_HASH),
                any(UUID.class)
            )
        ).thenReturn(0);

        assertThrows(
            IllegalStateException.class,
            () -> authSessionService.createSession(
                TEST_USER_ID
            )
        );

        verify(authSessionMapper).insertRefreshToken(
            eq(TEST_TOKEN_HASH),
            any(UUID.class)
        );
    }
}
