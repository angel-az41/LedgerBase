package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.mapper.AuthSessionMapper;
import com.ledgerbase.backend.service.AuthSessionService;
import com.ledgerbase.backend.service.RefreshTokenCodec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — REFRESH TOKEN DATABASE INTEGRATION TESTS
// ============================================================

/**
 * Verifies refresh-token rotation using real PostgreSQL
 * transactions and the actual MyBatis persistence mapper.
 *
 * TEST DATABASE:
 *
 * ledgerbase_test
 *
 * COMPONENTS:
 *
 * - Real Spring-managed AuthSessionService
 * - Real AuthSessionMapper
 * - Real PostgreSQL database
 * - Mocked RefreshTokenCodec
 *
 * WHY MOCK THE CODEC?
 *
 * Predictable test credentials allow us to simulate
 * a database uniqueness violation intentionally.
 *
 * The session service, transaction manager, and
 * database operations remain real.
 *
 * TRANSACTION TESTING:
 *
 * This class is deliberately NOT annotated with
 * @Transactional.
 *
 * The service must start and complete its own
 * transactions so that tests can verify committed
 * revocations and actual rollback behavior.
 *
 * TEST CLEANUP:
 *
 * Every test creates a temporary PostgreSQL user.
 *
 * After the test, deleting that user removes its
 * authentication sessions and refresh-token records
 * through the database's cascading foreign keys.
 *
 * No development business or accounting records
 * are modified.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
class AuthSessionRotationIntegrationTest {

    // ========================================================
    // TEST CREDENTIALS
    // ========================================================

    /**
     * Deterministic test-only credentials.
     *
     * These values are never issued to real users.
     */
    private static final String OLD_TOKEN =
        "A".repeat(43);

    private static final String NEW_TOKEN =
        "B".repeat(43);

    private static final String OLD_HASH =
        "a".repeat(64);

    private static final String NEW_HASH =
        "b".repeat(64);


    // ========================================================
    // REAL SPRING DEPENDENCIES
    // ========================================================

    @Autowired
    private AuthSessionService authSessionService;

    @Autowired
    private AuthSessionMapper authSessionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    // ========================================================
    // MOCKED CRYPTOGRAPHIC COMPONENT
    // ========================================================

    /**
     * Override only the token codec.
     *
     * The refresh-session service itself remains
     * Spring-managed, including @Transactional.
     */
    @MockitoBean
    private RefreshTokenCodec refreshTokenCodec;


    // ========================================================
    // TEST RECORD IDENTIFIERS
    // ========================================================

    private Long testUserId;

    private UUID sessionId;


    // ========================================================
    // PREPARE TEMPORARY DATABASE RECORDS
    // ========================================================

    @BeforeEach
    void setUp() {

        // ----------------------------------------------------
        // CREATE UNIQUE TEST USER
        // ----------------------------------------------------

        String email =
            "rotation-test-"
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
            "Refresh Rotation Test User"
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


        // ----------------------------------------------------
        // INSERT INITIAL TOKEN HASH
        // ----------------------------------------------------

        assertEquals(
            1,
            authSessionMapper.insertRefreshToken(
                OLD_HASH,
                sessionId
            )
        );
    }


    // ========================================================
    // REMOVE TEMPORARY TEST RECORDS
    // ========================================================

    /**
     * Delete only the user created by this test.
     *
     * ON DELETE CASCADE automatically removes:
     *
     * - Its authentication sessions
     * - Their refresh-token records
     */
    @AfterEach
    void cleanUp() {

        if (testUserId != null) {

            jdbcTemplate.update(
                "DELETE FROM users WHERE id = ?",
                testUserId
            );
        }
    }


    // ========================================================
    // CONFIGURE DETERMINISTIC TOKEN ROTATION
    // ========================================================

    /**
     * Mock only token generation and hashing.
     *
     * Database reads, writes, locking, commits,
     * and rollbacks remain real.
     */
    private void configureTokenRotation() {

        when(
            refreshTokenCodec.hashToken(OLD_TOKEN)
        ).thenReturn(OLD_HASH);

        when(
            refreshTokenCodec.generateToken()
        ).thenReturn(NEW_TOKEN);

        when(
            refreshTokenCodec.hashToken(NEW_TOKEN)
        ).thenReturn(NEW_HASH);
    }


    // ========================================================
    // HELPER — COUNT ACTIVE TOKENS
    // ========================================================

    private int countActiveTokens(UUID targetSessionId) {

        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM auth_refresh_tokens
            WHERE session_id = ?
              AND consumed_at IS NULL
            """,
            Integer.class,
            targetSessionId
        );

        return count == null ? 0 : count;
    }


    // ========================================================
    // HELPER — CHECK TOKEN CONSUMPTION
    // ========================================================

    private boolean isTokenConsumed(String tokenHash) {

        Boolean consumed = jdbcTemplate.queryForObject(
            """
            SELECT consumed_at IS NOT NULL
            FROM auth_refresh_tokens
            WHERE token_hash = ?
            """,
            Boolean.class,
            tokenHash
        );

        return Boolean.TRUE.equals(consumed);
    }


    // ========================================================
    // HELPER — CHECK SESSION REVOCATION
    // ========================================================

    private boolean isSessionRevoked() {

        Boolean revoked = jdbcTemplate.queryForObject(
            """
            SELECT revoked_at IS NOT NULL
            FROM auth_sessions
            WHERE id = ?
            """,
            Boolean.class,
            sessionId
        );

        return Boolean.TRUE.equals(revoked);
    }


    // ========================================================
    // TEST — SUCCESSFUL DATABASE TOKEN ROTATION
    // ========================================================

    /**
     * Verify that a successful refresh commits:
     *
     * - Consumption of the original token
     * - Insertion of the replacement token
     * - Exactly one active token for the session
     *
     * The replacement must belong to the original
     * authentication session.
     */
    @Test
    void shouldCommitSuccessfulRefreshRotation() {

        configureTokenRotation();

        // ----------------------------------------------------
        // EXECUTE REAL SERVICE TRANSACTION
        // ----------------------------------------------------

        Optional<AuthSessionService.RefreshedSession> result =
            authSessionService.refreshSession(
                OLD_TOKEN
            );

        assertTrue(result.isPresent());

        AuthSessionService.RefreshedSession refreshed =
            result.orElseThrow();


        // ----------------------------------------------------
        // VERIFY SERVICE RESPONSE
        // ----------------------------------------------------

        assertEquals(
            testUserId,
            refreshed.userId()
        );

        assertEquals(
            sessionId,
            refreshed.sessionId()
        );

        assertEquals(
            NEW_TOKEN,
            refreshed.refreshToken()
        );


        // ----------------------------------------------------
        // VERIFY COMMITTED DATABASE STATE
        // ----------------------------------------------------

        assertTrue(isTokenConsumed(OLD_HASH));

        assertFalse(isTokenConsumed(NEW_HASH));

        assertEquals(
            1,
            countActiveTokens(sessionId)
        );

        assertFalse(isSessionRevoked());
    }


    // ========================================================
    // TEST — REPLAY REVOCATION IS COMMITTED
    // ========================================================

    /**
     * Verify that presenting an already-consumed
     * refresh token revokes the session.
     *
     * This checks that revocation is committed even
     * though the refresh operation returns empty.
     */
    @Test
    void shouldCommitSessionRevocationAfterReplay() {

        configureTokenRotation();


        // ----------------------------------------------------
        // FIRST REFRESH SUCCEEDS
        // ----------------------------------------------------

        assertTrue(
            authSessionService
                .refreshSession(OLD_TOKEN)
                .isPresent()
        );

        assertFalse(isSessionRevoked());


        // ----------------------------------------------------
        // REUSE THE CONSUMED TOKEN
        // ----------------------------------------------------

        Optional<AuthSessionService.RefreshedSession> replay =
            authSessionService.refreshSession(
                OLD_TOKEN
            );


        // ----------------------------------------------------
        // VERIFY REPLAY IS REJECTED
        // ----------------------------------------------------

        assertTrue(replay.isEmpty());


        // ----------------------------------------------------
        // VERIFY REVOCATION WAS COMMITTED
        // ----------------------------------------------------

        assertTrue(isSessionRevoked());


        // ----------------------------------------------------
        // REPLACEMENT TOKEN MUST ALSO BE REJECTED
        // ----------------------------------------------------

        assertTrue(
            authSessionService
                .refreshSession(NEW_TOKEN)
                .isEmpty()
        );
    }


    // ========================================================
    // TEST — FAILED ROTATION ROLLS BACK
    // ========================================================

    /**
     * Deliberately trigger a PostgreSQL unique-key
     * violation during replacement-token insertion.
     *
     * TEST STRATEGY:
     *
     * 1. Create another valid session.
     * 2. Give that session the replacement token hash.
     * 3. Attempt to rotate the original session.
     * 4. PostgreSQL rejects the duplicate token hash.
     *
     * EXPECTED:
     *
     * The original token consumption must roll back.
     *
     * The original session must still have one
     * unused refresh token.
     */
    @Test
    void shouldRollBackWhenReplacementInsertFails() {

        configureTokenRotation();


        // ----------------------------------------------------
        // CREATE SECOND SESSION
        // ----------------------------------------------------

        UUID otherSessionId = UUID.randomUUID();

        OffsetDateTime expiration =
            OffsetDateTime.now(ZoneOffset.UTC)
                .plusHours(8);

        assertEquals(
            1,
            authSessionMapper.insertSession(
                otherSessionId,
                testUserId,
                expiration
            )
        );


        // ----------------------------------------------------
        // RESERVE REPLACEMENT HASH IN SECOND SESSION
        // ----------------------------------------------------

        assertEquals(
            1,
            authSessionMapper.insertRefreshToken(
                NEW_HASH,
                otherSessionId
            )
        );


        // ----------------------------------------------------
        // ATTEMPT ROTATION
        // ----------------------------------------------------

        /**
         * The service consumes OLD_HASH and then
         * attempts to insert NEW_HASH.
         *
         * NEW_HASH already exists, so PostgreSQL
         * rejects the insert.
         *
         * Spring must roll back the entire rotation.
         */
        assertThrows(
            RuntimeException.class,
            () -> authSessionService.refreshSession(
                OLD_TOKEN
            )
        );


        // ----------------------------------------------------
        // VERIFY ORIGINAL TOKEN WAS RESTORED
        // ----------------------------------------------------

        assertFalse(isTokenConsumed(OLD_HASH));


        // ----------------------------------------------------
        // VERIFY SESSION REMAINS CONSISTENT
        // ----------------------------------------------------

        assertEquals(
            1,
            countActiveTokens(sessionId)
        );

        assertFalse(isSessionRevoked());


        // ----------------------------------------------------
        // VERIFY SECOND SESSION REMAINS UNCHANGED
        // ----------------------------------------------------

        assertEquals(
            1,
            countActiveTokens(otherSessionId)
        );
    }


    // ========================================================
    // TEST — CONCURRENT REFRESH REQUESTS
    // ========================================================

    /**
     * Simulate two requests presenting the same
     * refresh token concurrently.
     *
     * Both requests execute on separate threads,
     * allowing PostgreSQL to manage row locking.
     *
     * EXPECTED UNDER CURRENT STRICT REPLAY POLICY:
     *
     * - Exactly one refresh succeeds.
     * - The second request observes token reuse.
     * - The session is revoked.
     *
     * This highlights why browser refresh requests
     * must be coordinated before frontend integration.
     */
    @Test
    void shouldSerializeConcurrentRefreshAttempts()
            throws Exception {

        configureTokenRotation();

        ExecutorService executor =
            Executors.newFixedThreadPool(2);

        CountDownLatch ready =
            new CountDownLatch(2);

        CountDownLatch start =
            new CountDownLatch(1);

        try {

            // ------------------------------------------------
            // DEFINE CONCURRENT REFRESH WORK
            // ------------------------------------------------

            Callable<Optional<AuthSessionService.RefreshedSession>>
                refreshTask = () -> {

                    ready.countDown();

                    if (!start.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                            "Concurrent test start timed out"
                        );
                    }

                    return authSessionService.refreshSession(
                        OLD_TOKEN
                    );
                };


            // ------------------------------------------------
            // SUBMIT TWO REQUESTS
            // ------------------------------------------------

            Future<Optional<AuthSessionService.RefreshedSession>>
                first = executor.submit(refreshTask);

            Future<Optional<AuthSessionService.RefreshedSession>>
                second = executor.submit(refreshTask);


            // ------------------------------------------------
            // START BOTH REQUESTS TOGETHER
            // ------------------------------------------------

            assertTrue(
                ready.await(15, TimeUnit.SECONDS)
            );

            start.countDown();


            // ------------------------------------------------
            // COLLECT RESULTS
            // ------------------------------------------------

            Optional<AuthSessionService.RefreshedSession>
                firstResult = first.get(
                    15,
                    TimeUnit.SECONDS
                );

            Optional<AuthSessionService.RefreshedSession>
                secondResult = second.get(
                    15,
                    TimeUnit.SECONDS
                );


            // ------------------------------------------------
            // VERIFY EXACTLY ONE REFRESH SUCCEEDED
            // ------------------------------------------------

            int successfulRefreshes =
                (firstResult.isPresent() ? 1 : 0)
                + (secondResult.isPresent() ? 1 : 0);

            assertEquals(
                1,
                successfulRefreshes
            );


            // ------------------------------------------------
            // VERIFY STRICT REPLAY REVOCATION
            // ------------------------------------------------

            assertTrue(isSessionRevoked());

            assertTrue(isTokenConsumed(OLD_HASH));

        } finally {

            // Release waiting workers even if an
            // assertion or request fails.

            start.countDown();

            executor.shutdownNow();

            assertTrue(
                executor.awaitTermination(
                    15,
                    TimeUnit.SECONDS
                )
            );
        }
    }
}
