package com.ledgerbase.reporting;

import com.ledgerbase.reporting.model.TrialBalanceRow;
import com.ledgerbase.reporting.service.TrialBalanceService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ============================================================
 * LEDGERBASE — TRIAL BALANCE REPLICA INTEGRATION TEST
 * ============================================================
 *
 * Verifies the Reporting Service's real database integration.
 *
 * Test responsibilities:
 *
 * - Start the Reporting Service application context.
 * - Confirm PostgreSQL is operating as a standby.
 * - Confirm database connections are read-only.
 * - Execute the Trial Balance query through MyBatis.
 *
 * This test requires the local PostgreSQL read replica.
 *
 * It does not create, modify, or delete accounting records.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class TrialBalanceReplicaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TrialBalanceService trialBalanceService;

    /**
     * Verify that reporting queries execute against
     * the PostgreSQL read-only standby.
     */
    @Test
    void trialBalanceExecutesAgainstReadReplica() {

        // Verify the database is a PostgreSQL standby.
        Boolean isStandby = jdbcTemplate.queryForObject(
                "SELECT pg_is_in_recovery()",
                Boolean.class
        );

        assertEquals(
                Boolean.TRUE,
                isStandby,
                "Reporting queries must use the read replica."
        );

        // Verify PostgreSQL enforces read-only transactions.
        String readOnly = jdbcTemplate.queryForObject(
                "SHOW transaction_read_only",
                String.class
        );

        assertEquals(
                "on",
                readOnly,
                "Reporting connections must be read-only."
        );

        // Execute the migrated Trial Balance SQL.
        // Business ID 2 is used for this local smoke test.
        List<TrialBalanceRow> rows =
                trialBalanceService.getTrialBalance(
                        2L,
                        LocalDate.of(2026, 10, 23)
                );

        // A non-null result confirms the mapper returned
        // successfully, even if no matching rows exist.
        assertNotNull(
                rows,
                "Trial Balance query must return a result list."
        );
    }
}
