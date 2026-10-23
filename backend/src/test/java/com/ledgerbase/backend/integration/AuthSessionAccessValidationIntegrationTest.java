package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.mapper.AuthSessionMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — SESSION ACCESS VALIDATION INTEGRATION TESTS
// ============================================================

/**
 * Verifies authentication session status against
 * the real PostgreSQL test database.
 *
 * DATABASE:
 *
 * ledgerbase_test
 *
 * TEST ISOLATION:
 *
 * Every test creates a temporary user and session.
 *
 * Spring rolls back the test transaction afterward,
 * preserving existing database records.
 *
 * These tests verify read-only session checks.
 * They do not test HTTP authorization yet.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Transactional
@Rollback
class AuthSessionAccessValidationIntegrationTest {

    // ========================================================
    // DATABASE DEPENDENCIES
    // ========================================================

    @Autowired
    private AuthSessionMapper authSessionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    // ========================================================
    // TEST IDENTIFIERS
    // ========================================================

    private Long testUserId;

    private UUID sessionId;


    // ========================================================
    // CREATE ISOLATED TEST SESSION
    // ========================================================

    @BeforeEach
    void setUp() {

        // ----------------------------------------------------
        // CREATE TEMPORARY TEST USER
        // ----------------------------------------------------

        String email =
            "session-access-"
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
            "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
            "Session Access Test User"
        );

        assertNotNull(testUserId);


        // ----------------------------------------------------
        // CREATE AUTHENTICATION SESSION
        // ----------------------------------------------------

        sessionId = UUID.randomUUID();

        OffsetDateTime expiration =
            OffsetDateTime.now(ZoneOffset.UTC)
                .plusHours(8);

        assertEquals(
            1,
            authSessionMapper.insertSession(
                sessionId,
                testUserId,
                expiration
            )
        );
    }


    // ========================================================
    // TEST — ACTIVE SESSION
    // ========================================================

    @Test
    void shouldAcceptActiveSession() {

        assertTrue(
            authSessionMapper.isSessionActiveForUser(
                sessionId,
                testUserId
            )
        );
    }


    // ========================================================
    // TEST — USER OWNERSHIP
    // ========================================================

    /**
     * A session belonging to one user must not
     * authenticate a different user ID.
     */
    @Test
    void shouldRejectSessionForDifferentUser() {

        assertFalse(
            authSessionMapper.isSessionActiveForUser(
                sessionId,
                testUserId + 1L
            )
        );
    }


    // ========================================================
    // TEST — REVOKED SESSION
    // ========================================================

    @Test
    void shouldRejectRevokedSession() {

        assertEquals(
            1,
            authSessionMapper.revokeSession(
                sessionId
            )
        );

        assertFalse(
            authSessionMapper.isSessionActiveForUser(
                sessionId,
                testUserId
            )
        );
    }


    // ========================================================
    // TEST — INACTIVITY EXPIRATION
    // ========================================================

    /**
     * A session with no accepted activity for
     * more than 30 minutes must be rejected.
     */
    @Test
    void shouldRejectInactiveSession() {

        jdbcTemplate.update(
            """
            UPDATE auth_sessions
            SET last_activity_at =
                CURRENT_TIMESTAMP - INTERVAL '31 minutes'
            WHERE id = ?
            """,
            sessionId
        );

        assertFalse(
            authSessionMapper.isSessionActiveForUser(
                sessionId,
                testUserId
            )
        );
    }


    // ========================================================
    // TEST — ABSOLUTE EXPIRATION
    // ========================================================

    /**
     * Even a recently active session must be
     * rejected after its absolute expiration.
     *
     * Both timestamps are updated together to
     * preserve the schema's expiration constraint.
     */
    @Test
    void shouldRejectExpiredSession() {

        jdbcTemplate.update(
            """
            UPDATE auth_sessions
            SET created_at =
                    CURRENT_TIMESTAMP - INTERVAL '9 hours',
                absolute_expires_at =
                    CURRENT_TIMESTAMP - INTERVAL '1 minute'
            WHERE id = ?
            """,
            sessionId
        );

        assertFalse(
            authSessionMapper.isSessionActiveForUser(
                sessionId,
                testUserId
            )
        );
    }


    // ========================================================
    // TEST — UNKNOWN SESSION
    // ========================================================

    @Test
    void shouldRejectUnknownSession() {

        assertFalse(
            authSessionMapper.isSessionActiveForUser(
                UUID.randomUUID(),
                testUserId
            )
        );
    }
}
