package com.ledgerbase.backend.integration;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// ============================================================
// LEDGERBASE — ACCOUNTING PERIOD CONTROLLER INTEGRATION TESTS
// ============================================================

/**
 * Integration tests for the Accounting Period REST API.
 *
 * These tests verify:
 *
 * 1. Listing the 12 periods of a fiscal year.
 * 2. Retrieving an individual accounting period.
 * 3. Returning HTTP 404 for missing records.
 * 4. Preventing cross-business data access.
 * 5. Supporting non-calendar fiscal years.
 * 6. Rejecting requests for nonexistent businesses.
 *
 * Tests use the real:
 *
 * - Spring Boot application
 * - AccountingPeriodController
 * - AccountingPeriodService
 * - MyBatis mappers
 * - PostgreSQL ledgerbase_test database
 *
 * Each test runs in a transaction that is
 * automatically rolled back afterward.
 *
 * The development database is never modified.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AccountingPeriodControllerIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FiscalYearService fiscalYearService;

    @Autowired
    private AccountingPeriodMapper accountingPeriodMapper;

    private MockMvc mvc;


    // ========================================================
    // TEST DATA
    // ========================================================

    private Long testUserId;

    private Long testBusinessId;


    // ========================================================
    // SETUP
    // ========================================================

    /**
     * Prepare a new test user and business.
     *
     * The database generates their IDs.
     *
     * We never assume the business ID is 1.
     *
     * By default, the new business uses January
     * as its fiscal-year starting month.
     */
    @BeforeEach
    void setUp() {

        mvc = MockMvcBuilders
            .webAppContextSetup(context)
            .build();

        String email = "period-api-"
            + UUID.randomUUID()
            + "@ledgerbase.local";

        // ====================================================
        // CREATE TEST USER
        // ====================================================

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
            "Accounting Period Test User"
        );


        // ====================================================
        // CREATE TEST BUSINESS
        // ====================================================

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
            "Accounting Period Test Business",
            "USD"
        );
    }


    // ========================================================
    // URL HELPERS
    // ========================================================

    /**
     * Build the URL for retrieving a fiscal year's periods.
     */
    private String periodsUrl(Long fiscalYearId) {

        return "/api/v1/businesses/"
            + testBusinessId
            + "/fiscal-years/"
            + fiscalYearId
            + "/periods";
    }


    /**
     * Build the URL for retrieving a single period.
     */
    private String periodUrl(Long periodId) {

        return "/api/v1/businesses/"
            + testBusinessId
            + "/accounting-periods/"
            + periodId;
    }


    // ========================================================
    // TEST 1 — LIST ALL TWELVE PERIODS
    // ========================================================

    /**
     * Create FY2027 through FiscalYearService.
     *
     * The service automatically creates 12 periods.
     *
     * Verify that GET returns:
     *
     * - HTTP 200
     * - Exactly 12 periods
     * - Periods ordered from 1 to 12
     * - Correct January and December dates
     * - OPEN status
     */
    @Test
    void listsTwelveAccountingPeriodsInOrder()
            throws Exception {

        FiscalYear fiscalYear =
            fiscalYearService.createFiscalYear(
                testBusinessId,
                2027
            );

        mvc.perform(get(periodsUrl(fiscalYear.getId())))

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.length()").value(12)
            )

            .andExpect(
                jsonPath("$[0].periodNumber").value(1)
            )

            .andExpect(
                jsonPath("$[0].startDate")
                    .value("2027-01-01")
            )

            .andExpect(
                jsonPath("$[0].endDate")
                    .value("2027-01-31")
            )

            .andExpect(
                jsonPath("$[0].status").value("OPEN")
            )

            .andExpect(
                jsonPath("$[9].periodNumber").value(10)
            )

            .andExpect(
                jsonPath("$[11].periodNumber").value(12)
            )

            .andExpect(
                jsonPath("$[11].endDate")
                    .value("2027-12-31")
            );


        // ====================================================
        // VERIFY POSTGRESQL RECORD COUNT
        // ====================================================

        Long count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM accounting_periods
            WHERE business_id = ?
              AND fiscal_year_id = ?
            """,
            Long.class,
            testBusinessId,
            fiscalYear.getId()
        );

        assertEquals(12L, count);
    }


    // ========================================================
    // TEST 2 — GET ONE ACCOUNTING PERIOD
    // ========================================================

    /**
     * Retrieve October's accounting period.
     *
     * The endpoint should return the correct:
     *
     * - ID
     * - Business ID
     * - Fiscal year ID
     * - Period number
     * - Date range
     * - Status
     */
    @Test
    void retrievesIndividualAccountingPeriod()
            throws Exception {

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

        // List index 9 corresponds to Period 10.
        AccountingPeriod october = periods.get(9);

        mvc.perform(get(periodUrl(october.getId())))

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.id").value(october.getId())
            )

            .andExpect(
                jsonPath("$.businessId").value(testBusinessId)
            )

            .andExpect(
                jsonPath("$.fiscalYearId")
                    .value(fiscalYear.getId())
            )

            .andExpect(
                jsonPath("$.periodNumber").value(10)
            )

            .andExpect(
                jsonPath("$.startDate")
                    .value("2027-10-01")
            )

            .andExpect(
                jsonPath("$.endDate")
                    .value("2027-10-31")
            )

            .andExpect(
                jsonPath("$.status").value("OPEN")
            );
    }


    // ========================================================
    // TEST 3 — MISSING FISCAL YEAR
    // ========================================================

    /**
     * Requesting periods for a fiscal year that
     * does not exist must return HTTP 404.
     *
     * We use Long.MAX_VALUE as a nonexistent ID.
     */
    @Test
    void returnsNotFoundForMissingFiscalYear()
            throws Exception {

        mvc.perform(get(periodsUrl(Long.MAX_VALUE)))

            .andExpect(status().isNotFound());
    }


    // ========================================================
    // TEST 4 — MISSING ACCOUNTING PERIOD
    // ========================================================

    /**
     * Requesting a nonexistent accounting period
     * must return HTTP 404 rather than an empty object.
     */
    @Test
    void returnsNotFoundForMissingAccountingPeriod()
            throws Exception {

        mvc.perform(get(periodUrl(Long.MAX_VALUE)))

            .andExpect(status().isNotFound());
    }


    // ========================================================
    // TEST 5 — CROSS-BUSINESS FISCAL YEAR ISOLATION
    // ========================================================

    /**
     * A fiscal year belonging to Business A must
     * not be accessible through Business B.
     *
     * Business B can have the same owner, but that
     * does not make Business A's periods its own.
     */
    @Test
    void preventsCrossBusinessFiscalYearAccess()
            throws Exception {

        FiscalYear fiscalYear =
            fiscalYearService.createFiscalYear(
                testBusinessId,
                2027
            );

        // Create a second business under the same user.
        Long otherBusinessId = jdbcTemplate.queryForObject(
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
            "Second Accounting Period Business",
            "USD"
        );

        String otherBusinessUrl =
            "/api/v1/businesses/"
            + otherBusinessId
            + "/fiscal-years/"
            + fiscalYear.getId()
            + "/periods";

        mvc.perform(get(otherBusinessUrl))

            .andExpect(status().isNotFound());
    }


    // ========================================================
    // TEST 6 — CROSS-BUSINESS PERIOD ISOLATION
    // ========================================================

    /**
     * Knowing another business's accounting-period ID
     * must not allow that period to be retrieved.
     *
     * The MyBatis query must match both:
     *
     * - business_id
     * - period id
     */
    @Test
    void preventsCrossBusinessAccountingPeriodAccess()
            throws Exception {

        FiscalYear fiscalYear =
            fiscalYearService.createFiscalYear(
                testBusinessId,
                2027
            );

        AccountingPeriod firstPeriod =
            accountingPeriodMapper.findByFiscalYearId(
                testBusinessId,
                fiscalYear.getId()
            ).get(0);

        Long otherBusinessId = jdbcTemplate.queryForObject(
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
            "Another Accounting Period Business",
            "USD"
        );

        String otherBusinessUrl =
            "/api/v1/businesses/"
            + otherBusinessId
            + "/accounting-periods/"
            + firstPeriod.getId();

        mvc.perform(get(otherBusinessUrl))

            .andExpect(status().isNotFound());
    }


    // ========================================================
    // TEST 7 — NON-CALENDAR FISCAL YEAR
    // ========================================================

    /**
     * Verify that the periods API supports fiscal years
     * that do not start in January.
     *
     * Example:
     *
     * Business start month = April.
     *
     * FY2027:
     * April 1, 2026 - March 31, 2027.
     *
     * Period 1 should be April 2026.
     * Period 12 should be March 2027.
     */
    @Test
    void listsPeriodsForAprilStartingFiscalYear()
            throws Exception {

        // Configure the test business to start in April.
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

        mvc.perform(get(periodsUrl(fiscalYear.getId())))

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.length()").value(12)
            )

            .andExpect(
                jsonPath("$[0].periodNumber").value(1)
            )

            .andExpect(
                jsonPath("$[0].startDate")
                    .value("2026-04-01")
            )

            .andExpect(
                jsonPath("$[0].endDate")
                    .value("2026-04-30")
            )

            .andExpect(
                jsonPath("$[11].periodNumber").value(12)
            )

            .andExpect(
                jsonPath("$[11].startDate")
                    .value("2027-03-01")
            )

            .andExpect(
                jsonPath("$[11].endDate")
                    .value("2027-03-31")
            );
    }


    // ========================================================
    // TEST 8 — NONEXISTENT BUSINESS
    // ========================================================

    /**
     * Requests for a business that does not exist
     * must return HTTP 404.
     *
     * This applies to both accounting-period endpoints.
     *
     * Our existing BusinessNotFoundException handler
     * produces the 404 response.
     */
    @Test
    void rejectsRequestsForMissingBusiness()
            throws Exception {

        mvc.perform(get(
                "/api/v1/businesses/-1"
                + "/fiscal-years/1/periods"
            ))

            .andExpect(status().isNotFound());

        mvc.perform(get(
                "/api/v1/businesses/-1"
                + "/accounting-periods/1"
            ))

            .andExpect(status().isNotFound());
    }
}
