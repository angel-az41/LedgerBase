package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.dto.AuthenticatedUser;
import com.ledgerbase.backend.mapper.AuthSessionMapper;
import com.ledgerbase.backend.service.JwtTokenService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — JWT SESSION DECODER INTEGRATION TESTS
// ============================================================

/**
 * Tests authentication session enforcement in the
 * application's real Spring-managed JwtDecoder.
 *
 * COMPONENTS:
 *
 * - Real JwtTokenService
 * - Real NimbusJwtDecoder
 * - Real AuthSessionJwtValidator
 * - Real AuthSessionMapper
 * - Real PostgreSQL test database
 *
 * DATABASE:
 *
 * ledgerbase_test
 *
 * TEST ISOLATION:
 *
 * Each test creates its own temporary user and session.
 *
 * Test-managed transactions are rolled back after
 * each method, leaving other database data unchanged.
 *
 * SECURITY:
 *
 * The decoder must reject access JWTs associated
 * with revoked, expired, inactive, or mismatched
 * authentication sessions.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Transactional
class JwtDecoderSessionIntegrationTest {

    // ========================================================
    // SPRING-MANAGED DEPENDENCIES
    // ========================================================

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private AuthSessionMapper authSessionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    // ========================================================
    // TEST SESSION DETAILS
    // ========================================================

    private Long testUserId;

    private UUID sessionId;

    private OffsetDateTime absoluteExpiresAt;


    // ========================================================
    // CREATE ISOLATED AUTHENTICATION SESSION
    // ========================================================

    @BeforeEach
    void setUp() {

        // ----------------------------------------------------
        // CREATE UNIQUE TEMPORARY TEST USER
        // ----------------------------------------------------

        String email =
            "jwt-decoder-test-"
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
            "JWT Decoder Test User"
        );

        assertNotNull(testUserId);


        // ----------------------------------------------------
        // PREPARE AUTHENTICATION SESSION
        // ----------------------------------------------------

        sessionId = UUID.randomUUID();

        absoluteExpiresAt =
            OffsetDateTime.now(ZoneOffset.UTC)
                .plusHours(8);


        // ----------------------------------------------------
        // INSERT SESSION INTO POSTGRESQL
        // ----------------------------------------------------

        assertEquals(
            1,
            authSessionMapper.insertSession(
                sessionId,
                testUserId,
                absoluteExpiresAt
            )
        );
    }


    // ========================================================
    // GENERATE SIGNED SESSION-AWARE JWT
    // ========================================================

    /**
     * Issue a JWT for the temporary user and session.
     *
     * The token contains:
     *
     * sub = testUserId
     * sid = sessionId
     *
     * The real JWT signing configuration is used.
     */
    private String issueTestAccessToken() {

        return jwtTokenService.generateAccessTokenForSession(
            testUserId,
            sessionId,
            absoluteExpiresAt
        );
    }


    // ========================================================
    // TEST — ACTIVE SESSION
    // ========================================================

    /**
     * Verify that a valid signed JWT associated with
     * an active PostgreSQL session is accepted.
     */
    @Test
    void shouldAcceptJwtForActiveSession() {

        String token =
            issueTestAccessToken();

        Jwt decoded =
            jwtDecoder.decode(token);

        assertEquals(
            testUserId.toString(),
            decoded.getSubject()
        );

        assertEquals(
            sessionId.toString(),
            decoded.getClaimAsString("sid")
        );
    }


    // ========================================================
    // TEST — REVOKED SESSION
    // ========================================================

    /**
     * Verify immediate JWT rejection after a
     * database session is revoked.
     *
     * The JWT signature and expiration remain valid.
     *
     * Only the session state changes.
     */
    @Test
    void shouldRejectJwtAfterSessionRevocation() {

        String token =
            issueTestAccessToken();


        // ----------------------------------------------------
        // CONFIRM JWT WORKS BEFORE REVOCATION
        // ----------------------------------------------------

        assertDoesNotThrow(
            () -> jwtDecoder.decode(token)
        );


        // ----------------------------------------------------
        // REVOKE SESSION IN POSTGRESQL
        // ----------------------------------------------------

        assertEquals(
            1,
            authSessionMapper.revokeSession(
                sessionId
            )
        );


        // ----------------------------------------------------
        // VERIFY SAME JWT IS NOW REJECTED
        // ----------------------------------------------------

        assertThrows(
            JwtException.class,
            () -> jwtDecoder.decode(token)
        );
    }


    // ========================================================
    // TEST — INACTIVE SESSION
    // ========================================================

    /**
     * Verify that a JWT cannot authenticate a session
     * whose last accepted activity was more than
     * 30 minutes ago.
     */
    @Test
    void shouldRejectJwtForInactiveSession() {

        String token =
            issueTestAccessToken();


        // ----------------------------------------------------
        // EXPIRE SESSION INACTIVITY WINDOW
        // ----------------------------------------------------

        jdbcTemplate.update(
            """
            UPDATE auth_sessions
            SET last_activity_at =
                CURRENT_TIMESTAMP - INTERVAL '31 minutes'
            WHERE id = ?
            """,
            sessionId
        );


        // ----------------------------------------------------
        // VERIFY DECODER REJECTS JWT
        // ----------------------------------------------------

        assertThrows(
            JwtException.class,
            () -> jwtDecoder.decode(token)
        );
    }


    // ========================================================
    // TEST — EXPIRED SESSION
    // ========================================================

    /**
     * Reject an access JWT even if its own exp claim
     * is still valid, when the associated PostgreSQL
     * session has reached its absolute expiration.
     */
    @Test
    void shouldRejectJwtForExpiredSession() {

        String token =
            issueTestAccessToken();


        // ----------------------------------------------------
        // CHANGE SESSION ABSOLUTE EXPIRATION
        // ----------------------------------------------------

        /**
         * Move created_at into the past so the
         * expiration constraint remains satisfied.
         */
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


        // ----------------------------------------------------
        // VERIFY SESSION VALIDATOR REJECTS TOKEN
        // ----------------------------------------------------

        assertThrows(
            JwtException.class,
            () -> jwtDecoder.decode(token)
        );
    }


    // ========================================================
    // TEST — WRONG SESSION OWNER
    // ========================================================

    /**
     * A signed JWT must not authenticate if its
     * subject does not own the referenced session.
     */
    @Test
    void shouldRejectJwtWithMismatchedSessionOwner() {

        // ----------------------------------------------------
        // CREATE JWT WITH DIFFERENT USER SUBJECT
        // ----------------------------------------------------

        String token =
            jwtTokenService.generateAccessTokenForSession(
                testUserId + 1000L,
                sessionId,
                absoluteExpiresAt
            );


        // ----------------------------------------------------
        // VERIFY JWT IS REJECTED
        // ----------------------------------------------------

        assertThrows(
            JwtException.class,
            () -> jwtDecoder.decode(token)
        );
    }


    // ========================================================
    // TEST — LEGACY JWT WITHOUT SESSION
    // ========================================================

    /**
     * Legacy access JWTs that do not include sid
     * must not authenticate against the new decoder.
     *
     * They may still have a valid HS256 signature,
     * but are no longer sufficient for authentication.
     */
    @Test
    @SuppressWarnings("deprecation")
    void shouldRejectLegacyJwtWithoutSessionId() {

        AuthenticatedUser user =
            new AuthenticatedUser(
                testUserId,
                "jwt-decoder-test@ledgerbase.test",
                "JWT Decoder Test User"
            );


        // ----------------------------------------------------
        // CREATE LEGACY JWT WITHOUT SID
        // ----------------------------------------------------

        String token =
            jwtTokenService.generateAccessToken(user);


        // ----------------------------------------------------
        // REQUIRE SESSION-AWARE AUTHENTICATION
        // ----------------------------------------------------

        assertThrows(
            JwtException.class,
            () -> jwtDecoder.decode(token)
        );
    }
}
