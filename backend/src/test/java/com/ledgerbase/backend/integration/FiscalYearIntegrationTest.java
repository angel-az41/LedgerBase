package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.mapper.AccountingPeriodMapper;
import com.ledgerbase.backend.model.AccountingPeriod;
import com.ledgerbase.backend.model.FiscalYear;
import com.ledgerbase.backend.service.FiscalYearService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — FISCAL YEAR INTEGRATION TESTS
// ============================================================

/**
 * Integration tests for fiscal-year creation and management.
 *
 * These tests use:
 *
 * 1. The real Spring Boot application context.
 * 2. The real FiscalYearService.
 * 3. The real MyBatis mappers.
 * 4. The dedicated PostgreSQL ledgerbase_test database.
 *
 * Each test creates its own business.
 *
 * @Transactional automatically rolls back test data
 * after every test, keeping the database isolated.
 *
 * IMPORTANT:
 *
 * Tests must never depend on the development business
 * having ID 1 or containing FY2026.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FiscalYearIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    /**
     * The real fiscal-year service being tested.
     */
    @Autowired
    private FiscalYearService fiscalYearService;

    /**
     * Retrieves accounting periods created by the service.
     */
    @Autowired
    private AccountingPeriodMapper accountingPeriodMapper;

    /**
     * Executes SQL directly for test setup and verification.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;


    // ========================================================
    // TEST DATA
    // ========================================================

    private Long testUserId;

    private Long testBusinessId;


    // ========================================================
    // BEFORE EACH TEST
    // ========================================================

    /**
     * Create an independent test user and business.
     *
     * The business initially uses the database default
     * fiscal-year starting month: January (1).
     *
     * Because the test is transactional, these records
     * are rolled back after the test finishes.
     */
    @BeforeEach
    void setUpTestData() {

        String email = "fiscal-test-"
            + UUID.randomUUID()
            + "@ledgerbase.local";

        // Create a test user.
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
            "Fiscal Year Test User"
        );

        // Create a test business.
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
            "Fiscal Year Test Business",
            "USD"
        );
    }


    // ========================================================
    // TEST 1 — DEFAULT CALENDAR FISCAL YEAR
    // ========================================================

    /**
     * A new US business should default to January.
     *
     * FY2027 should cover:
     *
     * January 1, 2027 - December 31, 2027.
     */
    @Test
    void createsCalendarFiscalYear() {

        FiscalYear fiscalYear =
            fiscalYearService.createFiscalYear(
                testBusinessId,
                2027
            );

        assertNotNull(fiscalYear);
        assertNotNull(fiscalYear.getId());

        assertEquals(
            testBusinessId,
            fiscalYear.getBusinessId()
        );

        assertEquals("FY2027", fiscalYear.getLabel());

        assertEquals(
            LocalDate.of(2027, 1, 1),
            fiscalYear.getStartDate()
        );

        assertEquals(
            LocalDate.of(2027, 12, 31),
            fiscalYear.getEndDate()
        );

        // Independently verify the database record.
        Long count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM fiscal_years
            WHERE business_id = ?
              AND label = 'FY2027'
            """,
            Long.class,
            testBusinessId
        );

        assertEquals(1L, count);
    }


    // ========================================================
    // TEST 2 — TWELVE CONSECUTIVE ACCOUNTING PERIODS
    // ========================================================

    /**
     * Creating a fiscal year must automatically generate
     * exactly 12 consecutive calendar-month periods.
     *
     * Every period must:
     *
     * - Belong to the correct business.
     * - Belong to the correct fiscal year.
     * - Have the correct period number.
     * - Have the correct monthly start and end dates.
     * - Initially have OPEN status.
     */
    @Test
    void generatesTwelveMonthlyPeriods() {

        FiscalYear fiscalYear =
            fiscalYearService.createFiscalYear(
                testBusinessId,
                2027
            );

        List<AccountingPeriod> periods =
            accountingPeriodMapper.findByFiscalYearId(
                testBusinessId,
                fiscalYear.getId()
            );

        assertEquals(12, periods.size());

        for (int index = 0; index < 12; index++) {

            AccountingPeriod period = periods.get(index);

            int expectedPeriodNumber = index + 1;

            LocalDate expectedStart =
                LocalDate.of(2027, 1, 1)
                    .plusMonths(index);

            LocalDate expectedEnd =
                expectedStart.plusMonths(1).minusDays(1);

            assertNotNull(period.getId());

            assertEquals(
                testBusinessId,
                period.getBusinessId()
            );

            assertEquals(
                fiscalYear.getId(),
                period.getFiscalYearId()
            );

            assertEquals(
                expectedPeriodNumber,
                period.getPeriodNumber()
            );

            assertEquals(
                expectedStart,
                period.getStartDate()
            );

            assertEquals(
                expectedEnd,
                period.getEndDate()
            );

            assertEquals(
                AccountingPeriod.Status.OPEN,
                period.getStatus()
            );

            assertNull(period.getClosedAt());
        }
    }


    // ========================================================
    // TEST 3 — NON-CALENDAR FISCAL YEAR
    // ========================================================

    /**
     * Businesses must be able to use a fiscal year
     * that does not begin in January.
     *
     * Example:
     *
     * Starting month = April (4)
     *
     * FY2027:
     * April 1, 2026 - March 31, 2027
     *
     * Period 1 = April 2026
     * Period 12 = March 2027
     */
    @Test
    void createsAprilToMarchFiscalYear() {

        // Configure this new test business to start in April.
        jdbcTemplate.update(
            """
            UPDATE businesses
            SET fiscal_year_start_month = 4
            WHERE id = ?
            """,
            testBusinessId
        );

        FiscalYear fiscalYear =
            fiscalYearService.createFiscalYear(
                testBusinessId,
                2027
            );

        assertEquals("FY2027", fiscalYear.getLabel());

        assertEquals(
            LocalDate.of(2026, 4, 1),
            fiscalYear.getStartDate()
        );

        assertEquals(
            LocalDate.of(2027, 3, 31),
            fiscalYear.getEndDate()
        );

        List<AccountingPeriod> periods =
            accountingPeriodMapper.findByFiscalYearId(
                testBusinessId,
                fiscalYear.getId()
            );

        assertEquals(12, periods.size());

        // The first accounting period is April.
        AccountingPeriod firstPeriod = periods.get(0);

        assertEquals(1, firstPeriod.getPeriodNumber());

        assertEquals(
            LocalDate.of(2026, 4, 1),
            firstPeriod.getStartDate()
        );

        assertEquals(
            LocalDate.of(2026, 4, 30),
            firstPeriod.getEndDate()
        );

        // The final accounting period is March.
        AccountingPeriod lastPeriod = periods.get(11);

        assertEquals(12, lastPeriod.getPeriodNumber());

        assertEquals(
            LocalDate.of(2027, 3, 1),
            lastPeriod.getStartDate()
        );

        assertEquals(
            LocalDate.of(2027, 3, 31),
            lastPeriod.getEndDate()
        );
    }


    // ========================================================
    // TEST 4 — DUPLICATE FISCAL YEAR
    // ========================================================

    /**
     * The same business must not create FY2027 twice.
     *
     * The service should reject the duplicate before
     * inserting additional records.
     */
    @Test
    void rejectsDuplicateFiscalYear() {

        fiscalYearService.createFiscalYear(
            testBusinessId,
            2027
        );

        IllegalArgumentException exception =
            assertThrows(
                IllegalArgumentException.class,
                () -> fiscalYearService.createFiscalYear(
                    testBusinessId,
                    2027
                )
            );

        assertTrue(
            exception.getMessage().contains(
                "already exists"
            )
        );
    }


    // ========================================================
    // TEST 5 — OVERLAPPING FISCAL YEARS
    // ========================================================

    /**
     * Fiscal years for one business must not overlap.
     *
     * We first create calendar FY2028:
     *
     * Jan 1, 2028 - Dec 31, 2028
     *
     * Then simulate a configuration change to April.
     *
     * Attempting to create FY2029 would produce:
     *
     * Apr 1, 2028 - Mar 31, 2029
     *
     * These date ranges overlap, so creation must fail.
     *
     * The business configuration is changed directly
     * here only to exercise the overlap validation.
     * The future configuration API will need stronger rules.
     */
    @Test
    void rejectsOverlappingFiscalYears() {

        fiscalYearService.createFiscalYear(
            testBusinessId,
            2028
        );

        jdbcTemplate.update(
            """
            UPDATE businesses
            SET fiscal_year_start_month = 4
            WHERE id = ?
            """,
            testBusinessId
        );

        IllegalArgumentException exception =
            assertThrows(
                IllegalArgumentException.class,
                () -> fiscalYearService.createFiscalYear(
                    testBusinessId,
                    2029
                )
            );

        assertTrue(
            exception.getMessage().contains(
                "overlaps"
            )
        );
    }


    // ========================================================
    // TEST 6 — BUSINESS ISOLATION
    // ========================================================

    /**
     * A fiscal year belonging to Business A
     * must not be visible through Business B.
     *
     * Both businesses can independently use
     * the same fiscal-year label.
     */
    @Test
    void enforcesBusinessIsolation() {

        FiscalYear firstBusinessYear =
            fiscalYearService.createFiscalYear(
                testBusinessId,
                2027
            );

        // Create a separate business.
        Long secondBusinessId = jdbcTemplate.queryForObject(
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
            "Second Test Business",
            "USD"
        );

        // Business B cannot retrieve Business A's year.
        FiscalYear inaccessibleYear =
            fiscalYearService.getFiscalYear(
                secondBusinessId,
                firstBusinessYear.getId()
            );

        assertNull(inaccessibleYear);

        // Business B initially has no fiscal years.
        assertTrue(
            fiscalYearService
                .getFiscalYears(secondBusinessId)
                .isEmpty()
        );

        // Business B can independently create FY2027.
        FiscalYear secondBusinessYear =
            fiscalYearService.createFiscalYear(
                secondBusinessId,
                2027
            );

        assertEquals(
            "FY2027",
            secondBusinessYear.getLabel()
        );

        assertNotEquals(
            firstBusinessYear.getId(),
            secondBusinessYear.getId()
        );

        assertEquals(
            secondBusinessId,
            secondBusinessYear.getBusinessId()
        );
    }


    // ========================================================
    // TEST 7 — MISSING BUSINESS
    // ========================================================

    /**
     * A nonexistent business must not be allowed
     * to create or retrieve fiscal years.
     */
    @Test
    void rejectsMissingBusiness() {

        long missingBusinessId = -1L;

        assertThrows(
            BusinessNotFoundException.class,
            () -> fiscalYearService.createFiscalYear(
                missingBusinessId,
                2027
            )
        );

        assertThrows(
            BusinessNotFoundException.class,
            () -> fiscalYearService.getFiscalYears(
                missingBusinessId
            )
        );
    }


    // ========================================================
    // TEST 8 — INVALID FISCAL YEAR
    // ========================================================

    /**
     * Reject years outside our initial supported range.
     *
     * Invalid requests must not create database records.
     */
    @Test
    void rejectsInvalidFiscalYearWithoutInsertingRecords() {

        assertThrows(
            IllegalArgumentException.class,
            () -> fiscalYearService.createFiscalYear(
                testBusinessId,
                1800
            )
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> fiscalYearService.createFiscalYear(
                testBusinessId,
                10000
            )
        );

        Long count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM fiscal_years
            WHERE business_id = ?
            """,
            Long.class,
            testBusinessId
        );

        assertEquals(0L, count);
    }
}
