package com.ledgerbase.reporting;

import com.ledgerbase.reporting.model.TrialBalanceRow;
import com.ledgerbase.reporting.service.TrialBalanceService;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.SqlSessionFactory;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ============================================================
 * LEDGERBASE — TRIAL BALANCE FINANCIAL CONSISTENCY TEST
 * ============================================================
 *
 * Compares Trial Balance results from:
 *
 * - PostgreSQL primary: reference financial data.
 * - PostgreSQL replica: Reporting Service query results.
 *
 * This integration test uses the same SQL on both databases,
 * preventing duplicate accounting logic inside the test.
 *
 * The primary connection exists only in this test.
 * Production reporting remains replica-only.
 *
 * Run against a stable local dataset with replication caught up.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class TrialBalanceConsistencyIntegrationTest {

    private static final Long BUSINESS_ID = 2L;

    private static final LocalDate AS_OF_DATE =
            LocalDate.of(2026, 10, 23);

    @Autowired
    private TrialBalanceService trialBalanceService;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Autowired
    private JdbcTemplate replicaJdbcTemplate;

    /**
     * Compare account-level balances from the primary
     * and the reporting replica.
     */
    @Test
    void replicaTrialBalanceMatchesPrimary() {

        // ----------------------------------------------------
        // PRIMARY DATABASE — TEST-ONLY CONNECTION
        // ----------------------------------------------------

        String password =
                System.getenv("POSTGRES_REPLICA_PASSWORD");

        assertTrue(
                password != null && !password.isBlank(),
                "Load the root .env variables before testing."
        );

        // The reader role is replicated from the primary.
        // This assumes the role can connect to both databases.
        DriverManagerDataSource primaryDataSource =
                new DriverManagerDataSource();

        primaryDataSource.setDriverClassName(
                "org.postgresql.Driver"
        );

        primaryDataSource.setUrl(
                "jdbc:postgresql://127.0.0.1:5434/ledgerbase"
        );

        primaryDataSource.setUsername("ledgerbase_reader");
        primaryDataSource.setPassword(password);

        JdbcTemplate primaryJdbcTemplate =
                new JdbcTemplate(primaryDataSource);

        // ----------------------------------------------------
        // VERIFY DATABASE ROLES
        // ----------------------------------------------------

        assertEquals(
                Boolean.FALSE,
                primaryJdbcTemplate.queryForObject(
                        "SELECT pg_is_in_recovery()",
                        Boolean.class
                ),
                "Reference database must be the primary."
        );

        assertEquals(
                Boolean.TRUE,
                replicaJdbcTemplate.queryForObject(
                        "SELECT pg_is_in_recovery()",
                        Boolean.class
                ),
                "Reporting database must be the replica."
        );

        // ----------------------------------------------------
        // REUSE THE MYBATIS TRIAL BALANCE SQL
        // ----------------------------------------------------

        Map<String, Object> parameters = Map.of(
                "businessId", BUSINESS_ID,
                "asOfDate", AS_OF_DATE
        );

        MappedStatement statement =
                sqlSessionFactory.getConfiguration()
                        .getMappedStatement(
                                "com.ledgerbase.reporting.mapper." +
                                "TrialBalanceMapper." +
                                "findByBusinessIdAsOfDate"
                        );

        // Resolve the XML mapper's named parameters
        // into the JDBC positional parameter order.
        BoundSql boundSql = statement.getBoundSql(parameters);

        Object[] sqlArguments =
                boundSql.getParameterMappings()
                        .stream()
                        .map(mapping -> {
                            String name = mapping.getProperty();

                            if (!parameters.containsKey(name)) {
                                throw new IllegalStateException(
                                        "Unexpected SQL parameter: " + name
                                );
                            }

                            return parameters.get(name);
                        })
                        .toArray();

        // ----------------------------------------------------
        // PRIMARY — EXPECTED FINANCIAL RESULT
        // ----------------------------------------------------

        List<TrialBalanceRow> primaryRows =
                primaryJdbcTemplate.query(
                        boundSql.getSql(),
                        (rs, rowNumber) -> {

                            TrialBalanceRow row =
                                    new TrialBalanceRow();

                            row.setAccountId(
                                    rs.getLong("account_id")
                            );

                            row.setCode(rs.getString("code"));
                            row.setName(rs.getString("name"));

                            row.setAccountType(
                                    rs.getString("account_type")
                            );

                            row.setActive(
                                    rs.getBoolean("is_active")
                            );

                            row.setDebitBalance(
                                    rs.getBigDecimal("debit_balance")
                            );

                            row.setCreditBalance(
                                    rs.getBigDecimal("credit_balance")
                            );

                            return row;
                        },
                        sqlArguments
                );

        // ----------------------------------------------------
        // REPLICA — REPORTING SERVICE RESULT
        // ----------------------------------------------------

        List<TrialBalanceRow> replicaRows =
                trialBalanceService.getTrialBalance(
                        BUSINESS_ID,
                        AS_OF_DATE
                );

        // Require a populated business to make the
        // comparison meaningful.
        assertFalse(
                primaryRows.isEmpty(),
                "The selected business must have accounts."
        );

        assertEquals(
                primaryRows.size(),
                replicaRows.size(),
                "Primary and replica account counts differ."
        );

        // ----------------------------------------------------
        // COMPARE EACH ACCOUNT
        // ----------------------------------------------------

        for (int i = 0; i < primaryRows.size(); i++) {

            TrialBalanceRow expected = primaryRows.get(i);
            TrialBalanceRow actual = replicaRows.get(i);

            assertEquals(
                    expected.getAccountId(),
                    actual.getAccountId()
            );

            assertEquals(
                    expected.getCode(),
                    actual.getCode()
            );

            assertEquals(
                    expected.getName(),
                    actual.getName()
            );

            assertEquals(
                    expected.getAccountType(),
                    actual.getAccountType()
            );

            assertEquals(
                    expected.isActive(),
                    actual.isActive()
            );

            // BigDecimal.compareTo compares numeric value
            // independently of decimal scale.
            assertEquals(
                    0,
                    expected.getDebitBalance().compareTo(
                            actual.getDebitBalance()
                    ),
                    "Debit mismatch for account " +
                            expected.getCode()
            );

            assertEquals(
                    0,
                    expected.getCreditBalance().compareTo(
                            actual.getCreditBalance()
                    ),
                    "Credit mismatch for account " +
                            expected.getCode()
            );
        }

        // ----------------------------------------------------
        // CHECK DOUBLE-ENTRY ACCOUNTING TOTALS
        // ----------------------------------------------------

        BigDecimal totalDebits =
                replicaRows.stream()
                        .map(TrialBalanceRow::getDebitBalance)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredits =
                replicaRows.stream()
                        .map(TrialBalanceRow::getCreditBalance)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(
                0,
                totalDebits.compareTo(totalCredits),
                "Trial Balance total debits and credits must match."
        );
    }
}
