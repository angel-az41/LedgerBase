package com.ledgerbase.reporting.integration;

import com.ledgerbase.reporting.model.TrialBalanceRow;
import com.ledgerbase.reporting.service.TrialBalanceService;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — TRIAL BALANCE REPLICA INTEGRATION TEST
// ============================================================
// This test requires a real PostgreSQL streaming replica.
//
// Ordinary CI runs do not provision a PostgreSQL standby.
// Enable this test explicitly when the replica and required
// database credentials are available.
//
// To enable:
// LEDGERBASE_REPLICA_IT=true
//
// The test is skipped when the variable is not set.
// This does not disable other Reporting Service tests.

@EnabledIfEnvironmentVariable(
    named = "LEDGERBASE_REPLICA_IT",
    matches = "true"
)
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
