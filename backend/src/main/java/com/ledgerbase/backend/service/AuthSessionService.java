package com.ledgerbase.backend.service;

import com.ledgerbase.backend.mapper.AuthSessionMapper;
import com.ledgerbase.backend.model.AuthSession;
import com.ledgerbase.backend.model.AuthRefreshToken;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

// ============================================================
// LEDGERBASE — AUTHENTICATION SESSION SERVICE
// ============================================================

/**
 * Manages persistent server-side authentication sessions.
 *
 * RESPONSIBILITIES:
 *
 * 1. Create authentication sessions after successful login.
 * 2. Generate cryptographically secure refresh tokens.
 * 3. Store only SHA-256 refresh-token hashes.
 * 4. Rotate refresh tokens after successful validation.
 * 5. Enforce session inactivity expiration.
 * 6. Enforce maximum session lifetime.
 * 7. Detect reuse of consumed refresh tokens.
 * 8. Revoke compromised or expired sessions.
 *
 * AUTHENTICATION ARCHITECTURE:
 *
 * AuthenticationService
 *     |
 *     | Validates email and password using BCrypt
 *     |
 *     v
 * AuthSessionService
 *     |
 *     | Creates or refreshes an authenticated session
 *     |
 *     v
 * AuthSessionMapper
 *     |
 *     | MyBatis SQL operations
 *     |
 *     v
 * PostgreSQL
 *
 * DATABASE TABLES:
 *
 * auth_sessions
 * auth_refresh_tokens
 *
 * SECURITY:
 *
 * - Session identifiers use random UUIDs.
 * - Refresh tokens are generated using SecureRandom.
 * - Raw refresh tokens are never stored in PostgreSQL.
 * - Session operations use database transactions.
 * - Refresh rotation uses PostgreSQL row-level locking.
 * - Reused refresh tokens cause session revocation.
 *
 * IMPORTANT:
 *
 * This service manages authentication sessions.
 *
 * AuthenticationService remains responsible for
 * validating user credentials.
 *
 * Spring Security and the business authorization
 * layer remain responsible for protecting financial
 * API endpoints.
 */
@Service
public class AuthSessionService {

    // ========================================================
    // SESSION CONFIGURATION
    // ========================================================

    /**
     * Maximum duration of an authentication session.
     *
     * A session expires after eight hours regardless
     * of whether the user remains active.
     *
     * This is an absolute expiration deadline.
     *
     * Refreshing a token does not extend the
     * absolute session lifetime.
     */
    private static final Duration MAX_SESSION_LIFETIME =
        Duration.ofHours(8);


    // ========================================================
    // SESSION INACTIVITY CONFIGURATION
    // ========================================================

    /**
     * Maximum permitted period of inactivity.
     *
     * A session becomes invalid after 30 minutes
     * without accepted server-side session activity.
     *
     * This timeout is independent of the absolute
     * eight-hour session expiration.
     *
     * Current implementation:
     *
     * Successful refresh operations update the
     * session's last_activity_at timestamp.
     *
     * A future authenticated activity mechanism
     * will allow normal user activity to update
     * this timestamp without rotating tokens.
     */
    private static final Duration INACTIVITY_TIMEOUT =
        Duration.ofMinutes(30);


    // ========================================================
    // DEPENDENCIES
    // ========================================================

    /**
     * MyBatis mapper for authentication session
     * and refresh-token persistence operations.
     */
    private final AuthSessionMapper authSessionMapper;

    /**
     * Generates secure refresh tokens and converts
     * them into SHA-256 hashes for database storage.
     */
    private final RefreshTokenCodec refreshTokenCodec;


    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    /**
     * Inject the persistence mapper and token codec.
     *
     * Spring manages these dependencies through
     * constructor injection.
     */
    public AuthSessionService(
        AuthSessionMapper authSessionMapper,
        RefreshTokenCodec refreshTokenCodec
    ) {

        this.authSessionMapper = authSessionMapper;
        this.refreshTokenCodec = refreshTokenCodec;
    }


    // ========================================================
    // SESSION CREATION RESULT
    // ========================================================

    /**
     * Represents a newly created authentication session.
     *
     * FIELDS:
     *
     * sessionId:
     * Random UUID identifying the server-side session.
     *
     * refreshToken:
     * New secret refresh credential.
     *
     * absoluteExpiresAt:
     * Maximum session expiration timestamp.
     *
     * SECURITY:
     *
     * The raw refresh token must eventually be
     * delivered through an HttpOnly cookie.
     *
     * It must never be:
     *
     * - Written to application logs.
     * - Returned in an ordinary JSON response.
     * - Stored in PostgreSQL.
     * - Included in exception messages.
     *
     * Java records normally print every field when
     * toString() is called.
     *
     * We override toString() to redact the credential.
     */
    public record CreatedSession(

        UUID sessionId,

        String refreshToken,

        OffsetDateTime absoluteExpiresAt

    ) {

        @Override
        public String toString() {

            return "CreatedSession["
                + "sessionId=" + sessionId
                + ", refreshToken=[REDACTED]"
                + ", absoluteExpiresAt="
                + absoluteExpiresAt
                + "]";
        }
    }


    // ========================================================
    // REFRESH RESULT MODEL
    // ========================================================

    /**
     * Represents a successfully refreshed session.
     *
     * FIELDS:
     *
     * userId:
     * Database ID of the authenticated user.
     *
     * sessionId:
     * Existing authentication session identifier.
     *
     * refreshToken:
     * Newly generated replacement refresh token.
     *
     * absoluteExpiresAt:
     * Original maximum session expiration timestamp.
     *
     * IMPORTANT:
     *
     * Refresh-token rotation preserves the existing
     * session identifier and absolute expiration.
     *
     * The new refresh token replaces the previously
     * issued refresh credential.
     *
     * The raw credential must never be logged.
     */
    public record RefreshedSession(

        Long userId,

        UUID sessionId,

        String refreshToken,

        OffsetDateTime absoluteExpiresAt

    ) {

        // ====================================================
        // REDACT SECRET FROM STRING REPRESENTATION
        // ====================================================

        @Override
        public String toString() {

            return "RefreshedSession["
                + "userId=" + userId
                + ", sessionId=" + sessionId
                + ", refreshToken=[REDACTED]"
                + ", absoluteExpiresAt="
                + absoluteExpiresAt
                + "]";
        }
    }


    // ========================================================
    // CREATE AUTHENTICATION SESSION
    // ========================================================

    /**
     * Create a persistent authentication session.
     *
     * CALLED AFTER:
     *
     * AuthenticationService successfully verifies
     * the user's email and BCrypt password.
     *
     * WORKFLOW:
     *
     * 1. Validate the authenticated user ID.
     * 2. Generate a random session UUID.
     * 3. Calculate absolute session expiration.
     * 4. Generate a secure refresh token.
     * 5. Calculate the SHA-256 token hash.
     * 6. Insert the authentication session.
     * 7. Insert the initial refresh-token hash.
     * 8. Return the new session credentials.
     *
     * TRANSACTION:
     *
     * The session and refresh-token inserts execute
     * within the same Spring-managed transaction.
     *
     * If either insert fails, the transaction rolls
     * back both database operations.
     *
     * The method must be invoked through the
     * Spring-managed service bean for @Transactional
     * to take effect.
     *
     * @param userId authenticated PostgreSQL user ID
     * @return newly generated session credentials
     */
    @Transactional
    public CreatedSession createSession(
        Long userId
    ) {

        // ====================================================
        // VALIDATE AUTHENTICATED USER ID
        // ====================================================

        /**
         * Reject null, zero, and negative IDs.
         *
         * The database foreign key provides an
         * additional existence check when inserting
         * the authentication session.
         */
        if (userId == null || userId <= 0) {

            throw new IllegalArgumentException(
                "A valid authenticated user ID is required"
            );
        }


        // ====================================================
        // GENERATE SESSION IDENTIFIER
        // ====================================================

        /**
         * UUID.randomUUID() generates a version 4
         * random session identifier.
         *
         * Each login receives a different session ID.
         */
        UUID sessionId = UUID.randomUUID();


        // ====================================================
        // CALCULATE ABSOLUTE SESSION EXPIRATION
        // ====================================================

        /**
         * Calculate the session's absolute deadline.
         *
         * This deadline remains unchanged during
         * subsequent refresh-token rotations.
         */
        OffsetDateTime absoluteExpiresAt =
            OffsetDateTime
                .now(ZoneOffset.UTC)
                .plus(MAX_SESSION_LIFETIME);


        // ====================================================
        // GENERATE INITIAL REFRESH TOKEN
        // ====================================================

        /**
         * Generate a cryptographically secure
         * 256-bit refresh token.
         *
         * The token codec returns a URL-safe
         * Base64-encoded credential.
         */
        String refreshToken =
            refreshTokenCodec.generateToken();


        // ====================================================
        // HASH INITIAL REFRESH TOKEN
        // ====================================================

        /**
         * Only the SHA-256 hash is stored.
         *
         * The raw refresh token remains available
         * for delivery through an HttpOnly cookie.
         */
        String refreshTokenHash =
            refreshTokenCodec.hashToken(
                refreshToken
            );


        // ====================================================
        // INSERT AUTHENTICATION SESSION
        // ====================================================

        int sessionRows =
            authSessionMapper.insertSession(
                sessionId,
                userId,
                absoluteExpiresAt
            );


        // ====================================================
        // VERIFY SESSION INSERT
        // ====================================================

        /**
         * Exactly one authentication session
         * must be inserted.
         *
         * Any unexpected result stops creation.
         */
        if (sessionRows != 1) {

            throw new IllegalStateException(
                "Authentication session creation failed"
            );
        }


        // ====================================================
        // INSERT INITIAL REFRESH TOKEN HASH
        // ====================================================

        int tokenRows =
            authSessionMapper.insertRefreshToken(
                refreshTokenHash,
                sessionId
            );


        // ====================================================
        // VERIFY REFRESH TOKEN INSERT
        // ====================================================

        /**
         * The initial refresh-token record must
         * belong to the newly created session.
         *
         * If insertion fails, throwing a runtime
         * exception causes Spring to roll back
         * the entire transaction.
         */
        if (tokenRows != 1) {

            throw new IllegalStateException(
                "Refresh token persistence failed"
            );
        }


        // ====================================================
        // RETURN CREATED SESSION CREDENTIALS
        // ====================================================

        /**
         * Both database inserts were successful.
         *
         * The caller will eventually deliver the
         * refresh token through a secure cookie.
         *
         * No HTTP cookies are created here.
         */
        return new CreatedSession(
            sessionId,
            refreshToken,
            absoluteExpiresAt
        );
    }


    // ========================================================
    // ROTATE REFRESH TOKEN
    // ========================================================

    /**
     * Exchange a valid unused refresh token for
     * a newly generated refresh token.
     *
     * WORKFLOW:
     *
     * 1. Validate and hash the supplied refresh token.
     * 2. Find its associated authentication session.
     * 3. Lock the PostgreSQL session row.
     * 4. Validate revocation and expiration status.
     * 5. Retrieve the stored refresh-token record.
     * 6. Detect whether the token was already consumed.
     * 7. Generate a new refresh token and hash.
     * 8. Mark the old refresh token as consumed.
     * 9. Insert the replacement token hash.
     * 10. Update the session's last activity timestamp.
     * 11. Return the replacement refresh credential.
     *
     * SESSION VALIDATION:
     *
     * A session must:
     *
     * - Exist in PostgreSQL.
     * - Not have been revoked.
     * - Be within the eight-hour absolute lifetime.
     * - Be within the 30-minute inactivity window.
     *
     * REPLAY DETECTION:
     *
     * When a consumed refresh token is presented
     * again, the associated session is revoked.
     *
     * CONCURRENCY:
     *
     * PostgreSQL SELECT FOR UPDATE serializes refresh
     * operations for the same authentication session.
     *
     * After acquiring the lock, the service reads
     * the token state again before rotating it.
     *
     * TRANSACTION:
     *
     * All database mutations run inside one
     * Spring-managed transaction.
     *
     * Expected authentication rejections return
     * Optional.empty() instead of throwing.
     *
     * This allows revocation changes to commit.
     *
     * Unexpected database errors throw runtime
     * exceptions and trigger transaction rollback.
     *
     * @param rawToken refresh credential from the client
     * @return refreshed session or empty on rejection
     */
    @Transactional
    public Optional<RefreshedSession> refreshSession(
        String rawToken
    ) {

        // ====================================================
        // VALIDATE AND HASH THE SUPPLIED TOKEN
        // ====================================================

        String tokenHash;

        try {

            /**
             * RefreshTokenCodec validates the expected
             * URL-safe token format before hashing.
             */
            tokenHash =
                refreshTokenCodec.hashToken(
                    rawToken
                );

        } catch (IllegalArgumentException exception) {

            // Malformed tokens must not reach PostgreSQL.
            // Do not reveal credential details.

            return Optional.empty();
        }


        // ====================================================
        // FIND THE ASSOCIATED SESSION IDENTIFIER
        // ====================================================

        UUID sessionId =
            authSessionMapper.findSessionIdByTokenHash(
                tokenHash
            );


        // ====================================================
        // REJECT UNKNOWN TOKEN
        // ====================================================

        /**
         * Unknown token hashes do not identify
         * an authentication session.
         *
         * No database changes should occur.
         */
        if (sessionId == null) {

            return Optional.empty();
        }


        // ====================================================
        // LOCK AUTHENTICATION SESSION
        // ====================================================

        /**
         * The mapper executes:
         *
         * SELECT ... FOR UPDATE
         *
         * PostgreSQL holds the session row lock
         * until this transaction completes.
         *
         * Other refresh operations attempting
         * to lock the same session must wait.
         */
        AuthSession session =
            authSessionMapper.lockSession(
                sessionId
            );


        // ====================================================
        // REJECT MISSING SESSION
        // ====================================================

        if (session == null) {

            return Optional.empty();
        }


        // ====================================================
        // REJECT ALREADY REVOKED SESSION
        // ====================================================

        /**
         * A revoked session must not issue
         * any further refresh credentials.
         */
        if (session.revokedAt() != null) {

            return Optional.empty();
        }


        // ====================================================
        // CALCULATE CURRENT TIME
        // ====================================================

        OffsetDateTime now =
            OffsetDateTime.now(ZoneOffset.UTC);


        // ====================================================
        // CHECK ABSOLUTE SESSION EXPIRATION
        // ====================================================

        /**
         * A session expires when current time
         * reaches its absolute expiration.
         *
         * The deadline cannot be extended by
         * refreshing the access token.
         */
        boolean absoluteExpired =
            !now.isBefore(
                session.absoluteExpiresAt()
            );


        // ====================================================
        // CHECK SESSION INACTIVITY
        // ====================================================

        /**
         * The inactivity deadline is calculated from
         * the last accepted session activity.
         *
         * Example:
         *
         * Last activity: 10:00
         * Deadline:      10:30
         *
         * A refresh attempted at or after 10:30
         * is rejected unless accepted activity has
         * already updated last_activity_at.
         */
        OffsetDateTime inactivityDeadline =
            session.lastActivityAt()
                .plus(INACTIVITY_TIMEOUT);

        boolean inactiveTooLong =
            !now.isBefore(
                inactivityDeadline
            );


        // ====================================================
        // REVOKE EXPIRED SESSION
        // ====================================================

        if (
            absoluteExpired ||
            inactiveTooLong
        ) {

            /**
             * The session is no longer valid.
             *
             * Record revocation in PostgreSQL.
             *
             * Returning normally allows this
             * database update to commit.
             */
            authSessionMapper.revokeSession(
                sessionId
            );

            return Optional.empty();
        }


        // ====================================================
        // READ TOKEN AFTER ACQUIRING SESSION LOCK
        // ====================================================

        /**
         * Another request may have consumed the
         * token before this transaction acquired
         * the session row lock.
         *
         * Always read the token state again.
         */
        AuthRefreshToken storedToken =
            authSessionMapper.findRefreshTokenByHash(
                tokenHash
            );


        // ====================================================
        // VERIFY TOKEN BELONGS TO THE LOCKED SESSION
        // ====================================================

        if (
            storedToken == null ||
            !sessionId.equals(
                storedToken.sessionId()
            )
        ) {

            return Optional.empty();
        }


        // ====================================================
        // DETECT REFRESH TOKEN REPLAY
        // ====================================================

        /**
         * consumedAt is null:
         *
         * The token has not yet been used.
         *
         * consumedAt is not null:
         *
         * The token was already used in an earlier
         * successful refresh operation.
         *
         * Reusing a consumed token may indicate
         * credential theft or concurrent use.
         *
         * Under the current strict replay policy,
         * the entire session is revoked.
         */
        if (
            storedToken.consumedAt() != null
        ) {

            // ------------------------------------------------
            // REVOKE COMPROMISED SESSION
            // ------------------------------------------------

            authSessionMapper.revokeSession(
                sessionId
            );


            // ------------------------------------------------
            // REJECT REPLAYED CREDENTIAL
            // ------------------------------------------------

            /**
             * Return normally to commit the
             * session revocation.
             */
            return Optional.empty();
        }


        // ====================================================
        // GENERATE REPLACEMENT REFRESH TOKEN
        // ====================================================

        /**
         * Every successful rotation produces
         * fresh cryptographically random bytes.
         */
        String replacementToken =
            refreshTokenCodec.generateToken();


        // ====================================================
        // HASH REPLACEMENT REFRESH TOKEN
        // ====================================================

        String replacementHash =
            refreshTokenCodec.hashToken(
                replacementToken
            );


        // ====================================================
        // CONSUME ORIGINAL REFRESH TOKEN
        // ====================================================

        /**
         * Mark the original token as consumed.
         *
         * The SQL update contains:
         *
         * WHERE consumed_at IS NULL
         *
         * This prevents an already-consumed token
         * from being consumed successfully again.
         */
        int consumedRows =
            authSessionMapper.consumeRefreshToken(
                tokenHash
            );


        // ====================================================
        // VERIFY TOKEN CONSUMPTION
        // ====================================================

        if (consumedRows != 1) {

            /**
             * An unexpected result means rotation
             * cannot safely continue.
             *
             * Throwing an exception triggers rollback.
             */
            throw new IllegalStateException(
                "Refresh token rotation failed"
            );
        }


        // ====================================================
        // INSERT REPLACEMENT TOKEN HASH
        // ====================================================

        int insertedRows =
            authSessionMapper.insertRefreshToken(
                replacementHash,
                sessionId
            );


        // ====================================================
        // VERIFY REPLACEMENT INSERT
        // ====================================================

        /**
         * PostgreSQL's partial unique index
         * permits only one unconsumed refresh
         * token per authentication session.
         *
         * The old token has already been consumed
         * inside this transaction.
         */
        if (insertedRows != 1) {

            /**
             * An exception rolls back both the
             * replacement insert and the previous
             * token consumption.
             */
            throw new IllegalStateException(
                "Refresh token replacement failed"
            );
        }


        // ====================================================
        // UPDATE LAST ACCEPTED ACTIVITY
        // ====================================================

        /**
         * A successful refresh represents accepted
         * authenticated session activity.
         *
         * This updates last_activity_at in PostgreSQL.
         */
        int updatedRows =
            authSessionMapper.updateLastActivity(
                sessionId
            );


        // ====================================================
        // VERIFY SESSION ACTIVITY UPDATE
        // ====================================================

        if (updatedRows != 1) {

            /**
             * The session must remain active during
             * the entire refresh operation.
             *
             * Unexpected update failures trigger
             * transaction rollback.
             */
            throw new IllegalStateException(
                "Authentication session update failed"
            );
        }


        // ====================================================
        // RETURN REFRESHED SESSION
        // ====================================================

        /**
         * The token rotation completed successfully.
         *
         * The returned result contains:
         *
         * - Existing authenticated user ID.
         * - Existing session UUID.
         * - Newly generated refresh token.
         * - Original absolute expiration.
         *
         * The future HTTP controller will:
         *
         * 1. Issue a replacement HttpOnly cookie.
         * 2. Generate a new short-lived access JWT.
         * 3. Return safe authentication information.
         *
         * The refresh token must never appear
         * in the ordinary JSON response.
         */
        return Optional.of(
            new RefreshedSession(
                session.userId(),
                sessionId,
                replacementToken,
                session.absoluteExpiresAt()
            )
        );
    }
}
