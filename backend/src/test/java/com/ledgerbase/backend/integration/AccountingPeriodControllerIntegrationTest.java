package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.mapper.AccountingPeriodMapper;
import com.ledgerbase.backend.model.AccountingPeriod;
import com.ledgerbase.backend.model.FiscalYear;
import com.ledgerbase.backend.service.FiscalYearService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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


    // ========================================================
    // TEST 9 — CLOSE AND REOPEN ACCOUNTING PERIOD THROUGH API
    // ========================================================

    /**
     * Verify the complete accounting-period lifecycle
     * through the real Spring MVC REST endpoints.
     *
     * This test covers:
     *
     * 1. Creating a fiscal year with OPEN periods.
     * 2. Closing an OPEN period through POST /close.
     * 3. Receiving HTTP 200 and CLOSED status.
     * 4. Recording the closing timestamp.
     * 5. Reopening the period through POST /reopen.
     * 6. Receiving HTTP 200 and OPEN status.
     * 7. Clearing the closing timestamp.
     * 8. Recording both actions in audit_events.
     *
     * The enclosing @Transactional test annotation
     * rolls back all database changes afterward.
     */
    @Test
    void closesAndReopensAccountingPeriodThroughApi()
            throws Exception {

        // ====================================================
        // CREATE A FISCAL YEAR
        // ====================================================

        FiscalYear fiscalYear =
            fiscalYearService.createFiscalYear(
                testBusinessId,
                2027
            );

        // ====================================================
        // SELECT AN OPEN ACCOUNTING PERIOD
        // ====================================================

        AccountingPeriod period =
            accountingPeriodMapper.findByFiscalYearId(
                testBusinessId,
                fiscalYear.getId()
            ).get(0);

        assertEquals(
            AccountingPeriod.Status.OPEN,
            period.getStatus()
        );

        // ====================================================
        // PREPARE REQUEST BODY
        // ====================================================

        /**
         * The actor is the test user created in setUp().
         *
         * Using a real test user ID ensures that
         * PostgreSQL audit-event foreign keys are valid.
         */
        String requestBody =
            "{\"actorId\":" + testUserId + "}";

        // ====================================================
        // CLOSE THE ACCOUNTING PERIOD
        // ====================================================

        mvc.perform(
                post(periodUrl(period.getId()) + "/close")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.id").value(period.getId())
            )

            .andExpect(
                jsonPath("$.status").value("CLOSED")
            )

            .andExpect(
                jsonPath("$.closedAt").isNotEmpty()
            );

        // ====================================================
        // VERIFY CLOSED STATUS THROUGH GET API
        // ====================================================

        mvc.perform(get(periodUrl(period.getId())))

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.status").value("CLOSED")
            );

        // ====================================================
        // REOPEN THE ACCOUNTING PERIOD
        // ====================================================

        mvc.perform(
                post(periodUrl(period.getId()) + "/reopen")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.id").value(period.getId())
            )

            .andExpect(
                jsonPath("$.status").value("OPEN")
            )

            .andExpect(
                jsonPath("$.closedAt").doesNotExist()
            );

        // ====================================================
        // VERIFY OPEN STATUS THROUGH GET API
        // ====================================================

        mvc.perform(get(periodUrl(period.getId())))

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.status").value("OPEN")
            );

        // ====================================================
        // VERIFY ACCOUNTING PERIOD AUDIT EVENTS
        // ====================================================

        /**
         * Both state changes must be recorded.
         *
         * We verify the audit events belong to:
         *
         * - The correct business
         * - The correct accounting period
         * - The correct actor
         *
         * Exactly two events are expected:
         *
         * ACCOUNTING_PERIOD_CLOSED
         * ACCOUNTING_PERIOD_REOPENED
         */
        Long auditEventCount =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_events
                WHERE business_id = ?
                  AND entity_type = 'ACCOUNTING_PERIOD'
                  AND entity_id = ?
                  AND actor_id = ?
                  AND action IN (
                      'ACCOUNTING_PERIOD_CLOSED',
                      'ACCOUNTING_PERIOD_REOPENED'
                  )
                """,
                Long.class,
                testBusinessId,
                period.getId(),
                testUserId
            );

        assertEquals(2L, auditEventCount);
    }


    // ========================================================
    // TEST 10 — CANNOT CLOSE AN ALREADY CLOSED PERIOD
    // ========================================================

    /**
     * An accounting period can only be closed
     * when its current status is OPEN.
     *
     * Verify:
     *
     * 1. The first closing request succeeds.
     * 2. A second closing request returns HTTP 409.
     * 3. The API provides a meaningful error message.
     */
    @Test
    void returnsConflictWhenClosingAlreadyClosedPeriod()
            throws Exception {

        // ====================================================
        // CREATE FISCAL YEAR AND SELECT A PERIOD
        // ====================================================

        FiscalYear fiscalYear =
            fiscalYearService.createFiscalYear(
                testBusinessId,
                2027
            );

        AccountingPeriod period =
            accountingPeriodMapper.findByFiscalYearId(
                testBusinessId,
                fiscalYear.getId()
            ).get(0);

        String requestBody =
            "{\"actorId\":" + testUserId + "}";

        String closeUrl =
            periodUrl(period.getId()) + "/close";

        // ====================================================
        // FIRST CLOSE — SUCCESS
        // ====================================================

        mvc.perform(
                post(closeUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.status").value("CLOSED")
            );

        // ====================================================
        // SECOND CLOSE — CONFLICT
        // ====================================================

        /**
         * The period is already CLOSED.
         *
         * AccountingPeriodConflictException should
         * be converted to HTTP 409 by our
         * GlobalExceptionHandler.
         */
        mvc.perform(
                post(closeUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )

            .andExpect(status().isConflict())

            .andExpect(
                jsonPath("$.error").isNotEmpty()
            );
    }


    // ========================================================
    // TEST 11 — CANNOT REOPEN AN ALREADY OPEN PERIOD
    // ========================================================

    /**
     * Reopening an accounting period is only valid
     * when its current status is CLOSED.
     *
     * All newly created accounting periods begin OPEN.
     *
     * Attempting to reopen one immediately must
     * return HTTP 409 Conflict.
     */
    @Test
    void returnsConflictWhenReopeningOpenPeriod()
            throws Exception {

        // ====================================================
        // CREATE FISCAL YEAR AND SELECT AN OPEN PERIOD
        // ====================================================

        FiscalYear fiscalYear =
            fiscalYearService.createFiscalYear(
                testBusinessId,
                2027
            );

        AccountingPeriod period =
            accountingPeriodMapper.findByFiscalYearId(
                testBusinessId,
                fiscalYear.getId()
            ).get(0);

        assertEquals(
            AccountingPeriod.Status.OPEN,
            period.getStatus()
        );

        String requestBody =
            "{\"actorId\":" + testUserId + "}";

        // ====================================================
        // REOPEN AN OPEN PERIOD — CONFLICT
        // ====================================================

        mvc.perform(
                post(periodUrl(period.getId()) + "/reopen")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )

            .andExpect(status().isConflict())

            .andExpect(
                jsonPath("$.error").isNotEmpty()
            );
    }


    // ========================================================
    // TEST 12 — INVALID ACTOR ID RETURNS HTTP 400
    // ========================================================

    /**
     * Closing or reopening an accounting period requires
     * a valid actor ID for audit attribution.
     *
     * Jakarta Bean Validation must reject:
     *
     * - Missing actor ID
     * - Null actor ID
     * - Zero or negative actor ID
     *
     * The existing GlobalExceptionHandler converts
     * validation failures into HTTP 400 responses.
     */
    @Test
    void rejectsInvalidActorIdForPeriodActions()
            throws Exception {

        // ====================================================
        // CREATE AN OPEN ACCOUNTING PERIOD
        // ====================================================

        FiscalYear fiscalYear =
            fiscalYearService.createFiscalYear(
                testBusinessId,
                2027
            );

        AccountingPeriod period =
            accountingPeriodMapper.findByFiscalYearId(
                testBusinessId,
                fiscalYear.getId()
            ).get(0);

        String closeUrl =
            periodUrl(period.getId()) + "/close";

        String reopenUrl =
            periodUrl(period.getId()) + "/reopen";

        // ====================================================
        // MISSING ACTOR ID
        // ====================================================

        mvc.perform(
                post(closeUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )

            .andExpect(status().isBadRequest())

            .andExpect(
                jsonPath("$.error").value("Actor ID is required")
            );

        // ====================================================
        // NULL ACTOR ID
        // ====================================================

        mvc.perform(
                post(closeUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"actorId\":null}")
            )

            .andExpect(status().isBadRequest());

        // ====================================================
        // ZERO ACTOR ID
        // ====================================================

        mvc.perform(
                post(closeUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"actorId\":0}")
            )

            .andExpect(status().isBadRequest())

            .andExpect(
                jsonPath("$.error")
                    .value("Actor ID must be positive")
            );

        // ====================================================
        // NEGATIVE ACTOR ID FOR REOPEN
        // ====================================================

        mvc.perform(
                post(reopenUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"actorId\":-1}")
            )

            .andExpect(status().isBadRequest());

        // ====================================================
        // VERIFY NO STATUS CHANGE OCCURRED
        // ====================================================

        AccountingPeriod unchangedPeriod =
            accountingPeriodMapper.findById(
                testBusinessId,
                period.getId()
            );

        assertEquals(
            AccountingPeriod.Status.OPEN,
            unchangedPeriod.getStatus()
        );
    }


    // ========================================================
    // TEST 13 — MISSING PERIOD RETURNS HTTP 404
    // ========================================================

    /**
     * Both period-action endpoints must return HTTP 404
     * if the requested period does not exist.
     *
     * A missing period is different from a status conflict.
     *
     * Missing period = 404
     * Invalid status transition = 409
     */
    @Test
    void returnsNotFoundWhenClosingOrReopeningMissingPeriod()
            throws Exception {

        String requestBody =
            "{\"actorId\":" + testUserId + "}";

        String missingPeriodUrl =
            periodUrl(Long.MAX_VALUE);

        // ====================================================
        // CLOSE NONEXISTENT PERIOD
        // ====================================================

        mvc.perform(
                post(missingPeriodUrl + "/close")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )

            .andExpect(status().isNotFound());

        // ====================================================
        // REOPEN NONEXISTENT PERIOD
        // ====================================================

        mvc.perform(
                post(missingPeriodUrl + "/reopen")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )

            .andExpect(status().isNotFound());
    }


    // ========================================================
    // TEST 14 — CROSS-BUSINESS PERIOD ACCESS RETURNS 404
    // ========================================================

    /**
     * Business B must not be able to close or reopen
     * an accounting period belonging to Business A.
     *
     * This verifies business-scoped data isolation.
     *
     * NOTE:
     *
     * This is not yet authenticated authorization.
     * User permissions will be enforced separately
     * when Spring Security is introduced.
     */
    @Test
    void preventsCrossBusinessPeriodStatusChanges()
            throws Exception {

        // ====================================================
        // CREATE A PERIOD FOR BUSINESS A
        // ====================================================

        FiscalYear fiscalYear =
            fiscalYearService.createFiscalYear(
                testBusinessId,
                2027
            );

        AccountingPeriod period =
            accountingPeriodMapper.findByFiscalYearId(
                testBusinessId,
                fiscalYear.getId()
            ).get(0);

        // ====================================================
        // CREATE BUSINESS B
        // ====================================================

        /**
         * The second business has the same test owner.
         *
         * A valid user does not change which business
         * owns the accounting period.
         */
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
            "Period Isolation Test Business",
            "USD"
        );

        // ====================================================
        // BUILD INCORRECT BUSINESS URL
        // ====================================================

        String otherBusinessPeriodUrl =
            "/api/v1/businesses/"
            + otherBusinessId
            + "/accounting-periods/"
            + period.getId();

        String requestBody =
            "{\"actorId\":" + testUserId + "}";

        // ====================================================
        // BUSINESS B CANNOT CLOSE BUSINESS A'S PERIOD
        // ====================================================

        mvc.perform(
                post(otherBusinessPeriodUrl + "/close")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )

            .andExpect(status().isNotFound());

        // ====================================================
        // BUSINESS B CANNOT REOPEN BUSINESS A'S PERIOD
        // ====================================================

        mvc.perform(
                post(otherBusinessPeriodUrl + "/reopen")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )

            .andExpect(status().isNotFound());

        // ====================================================
        // VERIFY BUSINESS A'S PERIOD REMAINS UNCHANGED
        // ====================================================

        AccountingPeriod unchangedPeriod =
            accountingPeriodMapper.findById(
                testBusinessId,
                period.getId()
            );

        assertEquals(
            AccountingPeriod.Status.OPEN,
            unchangedPeriod.getStatus()
        );
    }
}
