package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.mapper.AccountingPeriodMapper;
import com.ledgerbase.backend.model.AccountingPeriod;
import com.ledgerbase.backend.service.FiscalYearService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

// ============================================================
// LEDGERBASE — FISCAL YEAR ROLLBACK INTEGRATION TEST
// ============================================================

/**
 * Verify that fiscal-year creation is atomic.
 *
 * Atomic means:
 *
 * Either the fiscal year and all 12 periods are saved,
 * or none of them are saved.
 *
 * This test deliberately simulates a failure while
 * the sixth accounting period is being inserted.
 *
 * IMPORTANT:
 *
 * We deliberately do NOT put @Transactional on
 * this test class.
 *
 * FiscalYearService must start and manage its own
 * real database transaction.
 *
 * Otherwise, the test framework's automatic rollback
 * could conceal a missing service-level rollback.
 */
@SpringBootTest
@ActiveProfiles("test")
class FiscalYearRollbackIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    @Autowired
    private FiscalYearService fiscalYearService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Spy on the real mapper dependency.
     *
     * This allows us to simulate an unexpected failure
     * at a specific accounting period.
     *
     * Other fiscal-year integration tests already verify
     * normal MyBatis insertion behavior.
     */
    @MockitoSpyBean
    private AccountingPeriodMapper accountingPeriodMapper;


    // ========================================================
    // TEST DATA
    // ========================================================

    private Long testUserId;

    private Long testBusinessId;


    // ========================================================
    // SETUP
    // ========================================================

    @BeforeEach
    void setUp() {

        String email = "fiscal-rollback-"
            + UUID.randomUUID()
            + "@ledgerbase.local";

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
            "Fiscal Rollback Test User"
        );

        testBusinessId = jdbcTemplate.queryForObject(
            """
            INSERT INTO businesses (
                owner_id,
                name,
                currency
            )
            VALUES (?, ?, ?)
            RETURNING id
            """,
            Long.class,
            testUserId,
            "Fiscal Rollback Test Business",
            "USD"
        );
    }


    // ========================================================
    // TEST — ROLLBACK AFTER PARTIAL PERIOD INSERTION
    // ========================================================

    @Test
    void rollsBackFiscalYearWhenPeriodCreationFails() {

        AtomicInteger successfulPeriodInserts =
            new AtomicInteger(0);

        // ====================================================
        // SIMULATE A DATABASE OPERATION FAILURE
        // ====================================================

        /**
         * Intercept accounting-period insertions.
         *
         * For Periods 1–5:
         * Execute real SQL using JdbcTemplate.
         *
         * For Period 6:
         * Throw an unexpected runtime exception.
         *
         * JdbcTemplate joins the same Spring-managed
         * transaction as FiscalYearService.
         *
         * Therefore, the first five database inserts
         * should be rolled back along with the fiscal year.
         */
        doAnswer(invocation -> {

            AccountingPeriod period =
                invocation.getArgument(0);

            if (period.getPeriodNumber() == 6) {

                throw new IllegalStateException(
                    "Simulated failure creating Period 6"
                );
            }

            int inserted = jdbcTemplate.update(
                """
                INSERT INTO accounting_periods (
                    business_id,
                    fiscal_year_id,
                    period_number,
                    start_date,
                    end_date,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                period.getBusinessId(),
                period.getFiscalYearId(),
                period.getPeriodNumber(),
                period.getStartDate(),
                period.getEndDate(),
                period.getStatus().name()
            );

            successfulPeriodInserts.addAndGet(inserted);

            return inserted;

        }).when(accountingPeriodMapper)
          .insert(any(AccountingPeriod.class));


        // ====================================================
        // ATTEMPT TO CREATE FISCAL YEAR
        // ====================================================

        /**
         * The service should propagate the simulated
         * exception and roll back its transaction.
         */
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> fiscalYearService.createFiscalYear(
                testBusinessId,
                2027
            )
        );

        assertEquals(
            "Simulated failure creating Period 6",
            exception.getMessage()
        );

        // Confirm that five periods really were inserted
        // before the simulated failure occurred.
        assertEquals(
            5,
            successfulPeriodInserts.get()
        );


        // ====================================================
        // VERIFY THE FISCAL YEAR WAS ROLLED BACK
        // ====================================================

        Long fiscalYearCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM fiscal_years
            WHERE business_id = ?
            """,
            Long.class,
            testBusinessId
        );

        assertEquals(0L, fiscalYearCount);


        // ====================================================
        // VERIFY ALL ACCOUNTING PERIODS WERE ROLLED BACK
        // ====================================================

        Long periodCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM accounting_periods
            WHERE business_id = ?
            """,
            Long.class,
            testBusinessId
        );

        assertEquals(0L, periodCount);
    }


    // ========================================================
    // CLEANUP
    // ========================================================

    /**
     * Because this test has no test-managed transaction,
     * setup records remain committed.
     *
     * Clean up only this test's generated records.
     *
     * Delete children before parents to satisfy
     * foreign-key constraints.
     */
    @AfterEach
    void cleanUp() {

        if (testBusinessId != null) {

            jdbcTemplate.update(
                """
                DELETE FROM accounting_periods
                WHERE business_id = ?
                """,
                testBusinessId
            );

            jdbcTemplate.update(
                """
                DELETE FROM fiscal_years
                WHERE business_id = ?
                """,
                testBusinessId
            );

            jdbcTemplate.update(
                """
                DELETE FROM businesses
                WHERE id = ?
                """,
                testBusinessId
            );
        }

        if (testUserId != null) {

            jdbcTemplate.update(
                """
                DELETE FROM users
                WHERE id = ?
                """,
                testUserId
            );
        }
    }
}
