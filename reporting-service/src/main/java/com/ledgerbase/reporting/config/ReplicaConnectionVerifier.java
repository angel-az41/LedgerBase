package com.ledgerbase.reporting.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// ============================================================
// LEDGERBASE — REPORTING REPLICA CONNECTION VERIFIER
// ============================================================

/**
 * Verify that the Reporting Service connects to
 * the intended PostgreSQL read replica.
 *
 * The verification runs during application startup.
 *
 * REQUIRED CONDITIONS:
 *
 * 1. Database login is ledgerbase_reader.
 * 2. PostgreSQL is operating in standby mode.
 * 3. Database transactions are read-only.
 *
 * If any requirement fails, startup fails.
 *
 * This prevents accidental use of a writable
 * primary database for reporting operations.
 */
@Component
public class ReplicaConnectionVerifier
        implements ApplicationRunner {

    private static final Logger log =
        LoggerFactory.getLogger(
            ReplicaConnectionVerifier.class
        );

    private final JdbcTemplate jdbcTemplate;

    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    public ReplicaConnectionVerifier(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate = jdbcTemplate;
    }

    // ========================================================
    // VERIFY DATABASE CONFIGURATION
    // ========================================================

    @Override
    public void run(ApplicationArguments args) {

        /**
         * PostgreSQL provides built-in functions for
         * identifying the connected database session.
         *
         * current_user:
         *   Authenticated PostgreSQL role.
         *
         * pg_is_in_recovery():
         *   True when PostgreSQL is a standby.
         *
         * transaction_read_only:
         *   Whether the current transaction is read-only.
         */

        ReplicaIdentity identity =
            jdbcTemplate.queryForObject(
                """
                SELECT
                    current_user,
                    pg_is_in_recovery(),
                    current_setting('transaction_read_only')
                """,
                (rs, rowNum) -> new ReplicaIdentity(
                    rs.getString(1),
                    rs.getBoolean(2),
                    rs.getString(3)
                )
            );

        // ====================================================
        // FAIL STARTUP IF CONFIGURATION IS UNSAFE
        // ====================================================

        if (identity == null
                || !"ledgerbase_reader".equals(identity.user())
                || !identity.standby()
                || !"on".equals(identity.readOnly())) {

            throw new IllegalStateException(
                "Reporting Service requires the "
                    + "ledgerbase_reader account connected "
                    + "to a read-only PostgreSQL standby"
            );
        }

        // ====================================================
        // CONFIRM SUCCESSFUL REPLICA CONNECTION
        // ====================================================

        log.info(
            "Reporting Service connected to PostgreSQL "
                + "read replica: user={}, standby={}, readOnly={}",
            identity.user(),
            identity.standby(),
            identity.readOnly()
        );
    }

    // ========================================================
    // DATABASE IDENTITY RESULT
    // ========================================================

    /**
     * Immutable result of the PostgreSQL
     * database identity verification query.
     */
    private record ReplicaIdentity(
        String user,
        boolean standby,
        String readOnly
    ) {
    }
}
