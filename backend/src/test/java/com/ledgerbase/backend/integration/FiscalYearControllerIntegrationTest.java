package com.ledgerbase.backend.integration;

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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// ============================================================
// LEDGERBASE — FISCAL YEAR CONTROLLER INTEGRATION TESTS
// ============================================================

/**
 * Integration tests for the Fiscal Year REST API.
 *
 * These tests exercise:
 *
 * HTTP request
 *     -> FiscalYearController
 *     -> FiscalYearService
 *     -> MyBatis
 *     -> PostgreSQL
 *
 * We use the dedicated ledgerbase_test database.
 *
 * Each test runs inside a transaction that Spring
 * automatically rolls back when the test finishes.
 *
 * No development database records are modified.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FiscalYearControllerIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FiscalYearService fiscalYearService;

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
     * Create a new test user and business before each test.
     *
     * Never assume that a test business has ID 1.
     */
    @BeforeEach
    void setUp() {

        mvc = MockMvcBuilders
            .webAppContextSetup(context)
            .build();

        String email = "fiscal-api-"
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
            "Fiscal API Test User"
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
            "Fiscal API Test Business",
            "USD"
        );
    }


    // ========================================================
    // API URL HELPER
    // ========================================================

    /**
     * Build the business-scoped fiscal-year API URL.
     */
    private String fiscalYearsUrl() {

        return "/api/v1/businesses/"
            + testBusinessId
            + "/fiscal-years";
    }


    // ========================================================
    // TEST 1 — CREATE FISCAL YEAR
    // ========================================================

    /**
     * A valid POST request should:
     *
     * 1. Return HTTP 201.
     * 2. Return the correct fiscal-year information.
     * 3. Insert one fiscal year into PostgreSQL.
     * 4. Generate all 12 accounting periods.
     */
    @Test
    void createsFiscalYearThroughApi() throws Exception {

        mvc.perform(post(fiscalYearsUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "fiscalYearEndYear": 2027
                    }
                    """))

            .andExpect(status().isCreated())

            .andExpect(
                jsonPath("$.label").value("FY2027")
            )

            .andExpect(
                jsonPath("$.businessId").value(testBusinessId)
            )

            .andExpect(
                jsonPath("$.startDate").value("2027-01-01")
            )

            .andExpect(
                jsonPath("$.endDate").value("2027-12-31")
            );

        // ====================================================
        // VERIFY THE DATABASE RECORDS
        // ====================================================

        Long fiscalYearCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM fiscal_years
            WHERE business_id = ?
              AND label = 'FY2027'
            """,
            Long.class,
            testBusinessId
        );

        assertEquals(1L, fiscalYearCount);

        Long periodCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM accounting_periods
            WHERE business_id = ?
            """,
            Long.class,
            testBusinessId
        );

        assertEquals(12L, periodCount);
    }


    // ========================================================
    // TEST 2 — LIST FISCAL YEARS
    // ========================================================

    /**
     * GET should return fiscal years belonging to
     * the requested business.
     *
     * Newest years should appear first.
     */
    @Test
    void listsFiscalYearsNewestFirst() throws Exception {

        fiscalYearService.createFiscalYear(
            testBusinessId,
            2027
        );

        fiscalYearService.createFiscalYear(
            testBusinessId,
            2028
        );

        mvc.perform(get(fiscalYearsUrl()))

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.length()").value(2)
            )

            .andExpect(
                jsonPath("$[0].label").value("FY2028")
            )

            .andExpect(
                jsonPath("$[1].label").value("FY2027")
            );
    }


    // ========================================================
    // TEST 3 — GET ONE FISCAL YEAR
    // ========================================================

    /**
     * GET /fiscal-years/{fiscalYearId}
     *
     * A valid fiscal-year ID should return HTTP 200
     * and the corresponding fiscal-year record.
     */
    @Test
    void retrievesFiscalYearById() throws Exception {

        FiscalYear created =
            fiscalYearService.createFiscalYear(
                testBusinessId,
                2027
            );

        mvc.perform(get(
                fiscalYearsUrl() + "/" + created.getId()
            ))

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.id").value(created.getId())
            )

            .andExpect(
                jsonPath("$.label").value("FY2027")
            );
    }


    // ========================================================
    // TEST 4 — UNKNOWN FISCAL YEAR
    // ========================================================

    /**
     * A valid positive ID that does not exist
     * should return HTTP 404.
     */
    @Test
    void returnsNotFoundForMissingFiscalYear()
            throws Exception {

        mvc.perform(get(
                fiscalYearsUrl() + "/" + Long.MAX_VALUE
            ))

            .andExpect(status().isNotFound());
    }


    // ========================================================
    // TEST 5 — BUSINESS ISOLATION
    // ========================================================

    /**
     * A fiscal year belonging to Business A
     * must not be accessible through Business B.
     *
     * Both businesses may belong to the same
     * test user, but their records remain isolated.
     */
    @Test
    void doesNotExposeAnotherBusinessFiscalYear()
            throws Exception {

        FiscalYear created =
            fiscalYearService.createFiscalYear(
                testBusinessId,
                2027
            );

        Long secondBusinessId =
            jdbcTemplate.queryForObject(
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
                "Second Fiscal Test Business",
                "USD"
            );

        String otherBusinessUrl =
            "/api/v1/businesses/"
            + secondBusinessId
            + "/fiscal-years/"
            + created.getId();

        mvc.perform(get(otherBusinessUrl))

            .andExpect(status().isNotFound());
    }


    // ========================================================
    // TEST 6 — INVALID REQUEST
    // ========================================================

    /**
     * Jakarta validation should reject requests
     * missing the required fiscalYearEndYear field.
     *
     * The service should not create any records.
     */
    @Test
    void rejectsMissingFiscalYearValue()
            throws Exception {

        mvc.perform(post(fiscalYearsUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {}
                    """))

            .andExpect(status().isBadRequest());

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


    // ========================================================
    // TEST 7 — OUT-OF-RANGE FISCAL YEAR
    // ========================================================

    /**
     * The request DTO only permits fiscal years
     * between 1900 and 9999.
     */
    @Test
    void rejectsOutOfRangeFiscalYear()
            throws Exception {

        mvc.perform(post(fiscalYearsUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "fiscalYearEndYear": 1800
                    }
                    """))

            .andExpect(status().isBadRequest());
    }


    // ========================================================
    // TEST 8 — DUPLICATE FISCAL YEAR
    // ========================================================

    /**
     * A business must not create the same FY twice.
     *
     * Our current service rejects duplicates using
     * IllegalArgumentException, which the existing
     * API exception handler maps to HTTP 400.
     */
    @Test
    void rejectsDuplicateFiscalYear()
            throws Exception {

        fiscalYearService.createFiscalYear(
            testBusinessId,
            2027
        );

        mvc.perform(post(fiscalYearsUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "fiscalYearEndYear": 2027
                    }
                    """))

            .andExpect(status().isBadRequest());

        // The original fiscal year should remain,
        // with no duplicate records created.
        Long count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM fiscal_years
            WHERE business_id = ?
            """,
            Long.class,
            testBusinessId
        );

        assertEquals(1L, count);
    }


    // ========================================================
    // TEST 9 — NON-CALENDAR FISCAL YEAR THROUGH API
    // ========================================================

    /**
     * Verify that the REST API respects a business's
     * custom fiscal-year starting month.
     *
     * April-starting FY2027:
     *
     * April 1, 2026 - March 31, 2027.
     */
    @Test
    void createsCustomFiscalYearThroughApi()
            throws Exception {

        jdbcTemplate.update(
            """
            UPDATE businesses
            SET fiscal_year_start_month = 4
            WHERE id = ?
            """,
            testBusinessId
        );

        mvc.perform(post(fiscalYearsUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "fiscalYearEndYear": 2027
                    }
                    """))

            .andExpect(status().isCreated())

            .andExpect(
                jsonPath("$.label").value("FY2027")
            )

            .andExpect(
                jsonPath("$.startDate").value("2026-04-01")
            )

            .andExpect(
                jsonPath("$.endDate").value("2027-03-31")
            );

        // Verify that the service generated 12 periods.
        Long count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM accounting_periods
            WHERE business_id = ?
            """,
            Long.class,
            testBusinessId
        );

        assertEquals(12L, count);
    }


    // ========================================================
    // TEST 10 — MISSING BUSINESS
    // ========================================================

    /**
     * Requests for a nonexistent business
     * must return HTTP 404.
     */
    @Test
    void rejectsMissingBusiness() throws Exception {

        mvc.perform(get(
                "/api/v1/businesses/-1/fiscal-years"
            ))

            .andExpect(status().isNotFound());
    }
}
