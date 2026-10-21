package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.mapper.AuthSessionMapper;
import com.ledgerbase.backend.model.AuthRefreshToken;
import com.ledgerbase.backend.model.AuthSession;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — AUTHENTICATION SESSION MAPPER TESTS
// ============================================================

/**
 * Verifies authentication session persistence against
 * the real PostgreSQL test database.
 *
 * TEST ENVIRONMENT:
 *
 * - Spring Boot application context without HTTP server.
 * - MyBatis mapper connected to ledgerbase_test.
 * - PostgreSQL tables created by Flyway.
 * - A temporary user created for each test.
 *
 * TRANSACTION SAFETY:
 *
 * Every test runs inside a Spring-managed transaction.
 *
 * Spring rolls back the transaction after each test,
 * including the temporary user and session records.
 *
 * These tests must never run against the production
 * or normal development database.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Transactional
class AuthSessionMapperIntegrationTest {

    // ========================================================
    // DATABASE DEPENDENCIES
    // ========================================================

    @Autowired
    private AuthSessionMapper authSessionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    // ========================================================
    // TEST DATA
    // ========================================================

    private Long testUserId;

    // These are deterministic test-only token hashes.
    //
    // They have the required 64-character hexadecimal
    // structure but are not real refresh credentials.

    private static final String FIRST_TOKEN_HASH =
        "a".repeat(64);

    private static final String SECOND_TOKEN_HASH =
        "b".repeat(64);


    // ========================================================
    // CREATE TEMPORARY TEST USER
    // ========================================================

    /**
     * Create a unique user before every test.
     *
     * We do not rely on development user ID 1 because
     * the test database should be independent of the
     * development database.
     *
     * The dummy password hash is deliberately not a
     * valid login credential.
     *
     * All changes are rolled back after the test.
     */
    @BeforeEach
    void createTestUser() {

        String email =
            "auth-session-"
            + UUID.randomUUID()
            + "@ledgerbase.test";

        testUserId = jdbcTemplate.queryForObject(
            """
            INSERT INTO users (
                email,
                password_hash,
                full_name
            )
            VALUES (?, ?, ?)
            RETURNING id
            """,
            Long.class,
            email,
            "TEST_ONLY_NOT_A_LOGIN_CREDENTIAL",
            "Authentication Session Test User"
        );

        assertNotNull(testUserId);
    }


    // ========================================================
    // HELPER — CREATE TEST SESSION
    // ========================================================

    /**
     * Insert a server-managed session associated
     * with the temporary test user.
     *
     * Returns the UUID for subsequent mapper tests.
     */
    private UUID createTestSession() {

        UUID sessionId = UUID.randomUUID();

        OffsetDateTime absoluteExpiration =
            OffsetDateTime.now(ZoneOffset.UTC)
                .plusHours(8);

        int inserted = authSessionMapper.insertSession(
            sessionId,
            testUserId,
            absoluteExpiration
        );

        assertEquals(1, inserted);

        return sessionId;
    }


    // ========================================================
    // TEST — CREATE AND RETRIEVE SESSION
    // ========================================================

    /**
     * Verify that MyBatis correctly maps:
     *
     * - PostgreSQL UUID
     * - BIGINT user ID
     * - TIMESTAMPTZ values
     * - Nullable revocation timestamp
     *
     * The SELECT FOR UPDATE runs inside this test's
     * active transaction.
     */
    @Test
    void shouldInsertAndLockAuthenticationSession() {

        UUID sessionId = createTestSession();

        AuthSession session =
            authSessionMapper.lockSession(sessionId);

        assertNotNull(session);

        assertEquals(
            sessionId,
            session.id()
        );

        assertEquals(
            testUserId,
            session.userId()
        );

        assertNotNull(session.createdAt());
        assertNotNull(session.lastActivityAt());
        assertNotNull(session.absoluteExpiresAt());

        assertTrue(
            session.absoluteExpiresAt().isAfter(
                session.createdAt()
            )
        );

        assertNull(session.revokedAt());
    }


    // ========================================================
    // TEST — STORE AND RETRIEVE TOKEN HASH
    // ========================================================

    /**
     * A refresh token record must be associated
     * with the correct authentication session.
     *
     * Only the token hash is persisted.
     */
    @Test
    void shouldStoreAndRetrieveRefreshTokenHash() {

        UUID sessionId = createTestSession();

        int inserted =
            authSessionMapper.insertRefreshToken(
                FIRST_TOKEN_HASH,
                sessionId
            );

        assertEquals(1, inserted);

        UUID storedSessionId =
            authSessionMapper.findSessionIdByTokenHash(
                FIRST_TOKEN_HASH
            );

        assertEquals(
            sessionId,
            storedSessionId
        );

        AuthRefreshToken token =
            authSessionMapper.findRefreshTokenByHash(
                FIRST_TOKEN_HASH
            );

        assertNotNull(token);

        assertEquals(
            FIRST_TOKEN_HASH,
            token.tokenHash()
        );

        assertEquals(
            sessionId,
            token.sessionId()
        );

        assertNotNull(token.issuedAt());
        assertNull(token.consumedAt());
    }


    // ========================================================
    // TEST — CONSUME TOKEN ONLY ONCE
    // ========================================================

    /**
     * A refresh token must only transition from
     * unused to consumed once.
     *
     * First consumption:
     * One database row is updated.
     *
     * Second consumption:
     * No rows are updated.
     *
     * This is one building block of replay detection.
     * The session service will implement the actual
     * replay-handling policy separately.
     */
    @Test
    void shouldConsumeRefreshTokenOnlyOnce() {

        UUID sessionId = createTestSession();

        authSessionMapper.insertRefreshToken(
            FIRST_TOKEN_HASH,
            sessionId
        );

        int firstConsumption =
            authSessionMapper.consumeRefreshToken(
                FIRST_TOKEN_HASH
            );

        int secondConsumption =
            authSessionMapper.consumeRefreshToken(
                FIRST_TOKEN_HASH
            );

        assertEquals(1, firstConsumption);
        assertEquals(0, secondConsumption);

        AuthRefreshToken storedToken =
            authSessionMapper.findRefreshTokenByHash(
                FIRST_TOKEN_HASH
            );

        assertNotNull(storedToken);
        assertNotNull(storedToken.consumedAt());
    }


    // ========================================================
    // TEST — REPLACE CONSUMED TOKEN
    // ========================================================

    /**
     * The database must permit a replacement token
     * after the previous token is consumed.
     *
     * This verifies the expected sequence:
     *
     * 1. Insert original token hash.
     * 2. Consume original token.
     * 3. Insert replacement token hash.
     * 4. Confirm one unused token remains.
     *
     * This does not yet implement a complete
     * transactional token rotation service.
     */
    @Test
    void shouldAllowReplacementAfterConsumption() {

        UUID sessionId = createTestSession();

        assertEquals(
            1,
            authSessionMapper.insertRefreshToken(
                FIRST_TOKEN_HASH,
                sessionId
            )
        );

        assertEquals(
            1,
            authSessionMapper.consumeRefreshToken(
                FIRST_TOKEN_HASH
            )
        );

        assertEquals(
            1,
            authSessionMapper.insertRefreshToken(
                SECOND_TOKEN_HASH,
                sessionId
            )
        );

        AuthRefreshToken previousToken =
            authSessionMapper.findRefreshTokenByHash(
                FIRST_TOKEN_HASH
            );

        AuthRefreshToken replacementToken =
            authSessionMapper.findRefreshTokenByHash(
                SECOND_TOKEN_HASH
            );

        assertNotNull(previousToken);
        assertNotNull(replacementToken);

        assertNotNull(previousToken.consumedAt());
        assertNull(replacementToken.consumedAt());

        Integer activeTokenCount =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM auth_refresh_tokens
                WHERE session_id = ?
                  AND consumed_at IS NULL
                """,
                Integer.class,
                sessionId
            );

        assertEquals(1, activeTokenCount);
    }


    // ========================================================
    // TEST — REVOKE SESSION
    // ========================================================

    /**
     * A revoked session must reject:
     *
     * - Further activity updates.
     * - Repeated revocation updates.
     *
     * The session row remains available as history.
     */
    @Test
    void shouldPreventActivityAfterRevocation() {

        UUID sessionId = createTestSession();

        // A valid session initially accepts activity.

        assertEquals(
            1,
            authSessionMapper.updateLastActivity(
                sessionId
            )
        );

        // Revoke the session.

        assertEquals(
            1,
            authSessionMapper.revokeSession(
                sessionId
            )
        );

        // Repeated revocation does not update it again.

        assertEquals(
            0,
            authSessionMapper.revokeSession(
                sessionId
            )
        );

        // Revoked sessions cannot record new activity.

        assertEquals(
            0,
            authSessionMapper.updateLastActivity(
                sessionId
            )
        );

        // Verify the stored revocation timestamp.

        AuthSession session =
            authSessionMapper.lockSession(sessionId);

        assertNotNull(session);
        assertNotNull(session.revokedAt());
    }


    // ========================================================
    // TEST — UNKNOWN TOKEN HASH
    // ========================================================

    /**
     * A refresh-token hash that was never stored
     * must not resolve to a session or token record.
     */
    @Test
    void shouldReturnNullForUnknownTokenHash() {

        assertNull(
            authSessionMapper.findSessionIdByTokenHash(
                FIRST_TOKEN_HASH
            )
        );

        assertNull(
            authSessionMapper.findRefreshTokenByHash(
                FIRST_TOKEN_HASH
            )
        );
    }
}
