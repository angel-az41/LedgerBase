package com.ledgerbase.backend.mapper;

import com.ledgerbase.backend.model.AuthRefreshToken;
import com.ledgerbase.backend.model.AuthSession;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.UUID;

// ============================================================
// LEDGERBASE — AUTHENTICATION SESSION MAPPER
// ============================================================

/**
 * PostgreSQL persistence operations for authentication
 * sessions and refresh token records.
 *
 * SECURITY:
 *
 * - All SQL values use MyBatis parameters.
 * - Raw refresh tokens are never stored.
 * - Session locking supports safe token rotation.
 * - Revoked sessions remain recorded.
 *
 * TRANSACTION REQUIREMENT:
 *
 * Operations that lock, consume, and replace refresh
 * tokens must be executed inside the same Spring
 * @Transactional service method.
 *
 * SELECT FOR UPDATE protects rows only while the
 * surrounding database transaction remains active.
 */
@Mapper
public interface AuthSessionMapper {

    // ========================================================
    // CREATE AUTHENTICATION SESSION
    // ========================================================

    /**
     * Insert a new authentication session.
     *
     * The service generates the UUID and calculates
     * the absolute expiration timestamp.
     *
     * PostgreSQL supplies the initial creation and
     * activity timestamps.
     *
     * @return number of inserted records
     */
    @Insert("""
        INSERT INTO auth_sessions (
            id,
            user_id,
            absolute_expires_at
        )
        VALUES (
            #{sessionId},
            #{userId},
            #{absoluteExpiresAt}
        )
        """)
    int insertSession(
        @Param("sessionId") UUID sessionId,
        @Param("userId") Long userId,
        @Param("absoluteExpiresAt")
        OffsetDateTime absoluteExpiresAt
    );


    // ========================================================
    // LOCK SESSION FOR UPDATE
    // ========================================================

    /**
     * Retrieve and lock an authentication session.
     *
     * PostgreSQL prevents another transaction from
     * acquiring a conflicting lock on this session
     * until the current transaction completes.
     *
     * This will coordinate concurrent refresh requests.
     *
     * The caller must already be inside a Spring
     * transaction for the lock to remain effective.
     *
     * Constructor arguments are mapped explicitly
     * because AuthSession is an immutable Java record.
     */
    @Select("""
        SELECT
            id,
            user_id,
            created_at,
            last_activity_at,
            absolute_expires_at,
            revoked_at
        FROM auth_sessions
        WHERE id = #{sessionId}
        FOR UPDATE
        """)
    @ConstructorArgs({
        @Arg(
            column = "id",
            javaType = UUID.class,
            id = true
        ),
        @Arg(
            column = "user_id",
            javaType = Long.class
        ),
        @Arg(
            column = "created_at",
            javaType = OffsetDateTime.class
        ),
        @Arg(
            column = "last_activity_at",
            javaType = OffsetDateTime.class
        ),
        @Arg(
            column = "absolute_expires_at",
            javaType = OffsetDateTime.class
        ),
        @Arg(
            column = "revoked_at",
            javaType = OffsetDateTime.class
        )
    })
    AuthSession lockSession(
        @Param("sessionId") UUID sessionId
    );


    // ========================================================
    // FIND SESSION ASSOCIATED WITH TOKEN HASH
    // ========================================================

    /**
     * Find the session identifier associated with
     * a stored refresh token hash.
     *
     * This initial lookup does not lock anything.
     *
     * The session service will subsequently lock
     * the session and recheck token state before
     * modifying or rotating credentials.
     */
    @Select("""
        SELECT session_id
        FROM auth_refresh_tokens
        WHERE token_hash = #{tokenHash}
        """)
    UUID findSessionIdByTokenHash(
        @Param("tokenHash") String tokenHash
    );


    // ========================================================
    // RETRIEVE STORED REFRESH TOKEN
    // ========================================================

    /**
     * Retrieve the token record after the relevant
     * authentication session has been locked.
     *
     * A non-null consumedAt value indicates that
     * the token was already used.
     */
    @Select("""
        SELECT
            token_hash,
            session_id,
            issued_at,
            consumed_at
        FROM auth_refresh_tokens
        WHERE token_hash = #{tokenHash}
        """)
    @ConstructorArgs({
        @Arg(
            column = "token_hash",
            javaType = String.class,
            id = true
        ),
        @Arg(
            column = "session_id",
            javaType = UUID.class
        ),
        @Arg(
            column = "issued_at",
            javaType = OffsetDateTime.class
        ),
        @Arg(
            column = "consumed_at",
            javaType = OffsetDateTime.class
        )
    })
    AuthRefreshToken findRefreshTokenByHash(
        @Param("tokenHash") String tokenHash
    );


    // ========================================================
    // INSERT REFRESH TOKEN HASH
    // ========================================================

    /**
     * Store the hash of a newly generated refresh token.
     *
     * PostgreSQL enforces one unconsumed refresh token
     * per authentication session.
     *
     * @return number of inserted records
     */
    @Insert("""
        INSERT INTO auth_refresh_tokens (
            token_hash,
            session_id
        )
        VALUES (
            #{tokenHash},
            #{sessionId}
        )
        """)
    int insertRefreshToken(
        @Param("tokenHash") String tokenHash,
        @Param("sessionId") UUID sessionId
    );


    // ========================================================
    // CONSUME AN EXISTING REFRESH TOKEN
    // ========================================================

    /**
     * Mark an unused token as consumed.
     *
     * The WHERE condition prevents the same record
     * from being successfully consumed twice.
     *
     * A successful update must affect exactly one row.
     *
     * Token consumption and replacement will be
     * performed within a single transaction.
     */
    @Update("""
        UPDATE auth_refresh_tokens
        SET consumed_at = CURRENT_TIMESTAMP
        WHERE token_hash = #{tokenHash}
          AND consumed_at IS NULL
        """)
    int consumeRefreshToken(
        @Param("tokenHash") String tokenHash
    );


    // ========================================================
    // UPDATE LAST ACTIVITY
    // ========================================================

    /**
     * Record accepted session activity.
     *
     * Revoked sessions must not be updated.
     *
     * Session timeout validation happens in the
     * service before calling this method.
     */
    @Update("""
        UPDATE auth_sessions
        SET last_activity_at = CURRENT_TIMESTAMP
        WHERE id = #{sessionId}
          AND revoked_at IS NULL
        """)
    int updateLastActivity(
        @Param("sessionId") UUID sessionId
    );


    // ========================================================
    // REVOKE AUTHENTICATION SESSION
    // ========================================================

    /**
     * Revoke an authentication session.
     *
     * Existing session history is retained for
     * auditability and replay investigation.
     *
     * The caller must also clear the browser's
     * refresh cookie through the HTTP response.
     */
    @Update("""
        UPDATE auth_sessions
        SET revoked_at = CURRENT_TIMESTAMP
        WHERE id = #{sessionId}
          AND revoked_at IS NULL
        """)
    int revokeSession(
        @Param("sessionId") UUID sessionId
    );
    

    // ========================================================
    // CHECK AUTHENTICATION SESSION VALIDITY
    // ========================================================

    /**
     * Check whether a JWT belongs to an active
     * PostgreSQL authentication session.
     *
     * VALIDATION:
     *
     * - The session ID must exist.
     * - The session must belong to the JWT subject.
     * - The session must not have been revoked.
     * - The absolute expiration must not have passed.
     * - The inactivity timeout must not have passed.
     *
     * PERFORMANCE:
     *
     * This is a read-only query.
     *
     * It uses the session's primary key and does not
     * acquire a SELECT FOR UPDATE row lock.
     *
     * Normal API requests must not serialize on
     * the same session row merely to authenticate.
     *
     * TIMING:
     *
     * PostgreSQL evaluates expiration using its
     * own database clock.
     *
     * The check does not extend the inactivity
     * deadline or write to the database.
     *
     * @param sessionId UUID from the signed JWT sid
     * @param userId database user ID from JWT sub
     * @return true only when the session is active
     */
    @Select("""
        SELECT EXISTS (
            SELECT 1
            FROM auth_sessions
            WHERE id = #{sessionId}
              AND user_id = #{userId}
              AND revoked_at IS NULL
              AND absolute_expires_at > CURRENT_TIMESTAMP
              AND last_activity_at + INTERVAL '30 minutes'
                  > CURRENT_TIMESTAMP
        )
        """)
    boolean isSessionActiveForUser(
        @Param("sessionId") UUID sessionId,
        @Param("userId") Long userId
    );
}
