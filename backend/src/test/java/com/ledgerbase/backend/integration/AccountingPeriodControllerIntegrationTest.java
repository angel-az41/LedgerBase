package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.mapper.AccountingPeriodMapper;
import com.ledgerbase.backend.model.AccountingPeriod;
import com.ledgerbase.backend.model.FiscalYear;
import com.ledgerbase.backend.service.FiscalYearService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
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
 * - Listing the 12 periods of a fiscal year.
 * - Retrieving individual accounting periods.
 * - Returning HTTP 404 for missing records.
 * - Enforcing cross-business data isolation.
 * - Supporting non-calendar fiscal years.
 * - Enforcing Spring Security authentication.
 * - Checking business membership authorization.
 * - Closing and reopening accounting periods.
 * - Rejecting invalid status transitions.
 * - Recording the authenticated actor in audit events.
 * - Ignoring forged client-supplied actor IDs.
 *
 * These tests exercise the real:
 *
 * - Spring Boot application context.
 * - Spring Security filter chain.
 * - Business authorization manager.
 * - AccountingPeriodController.
 * - AccountingPeriodService.
 * - FiscalYearService.
 * - MyBatis mappers.
 * - PostgreSQL test database.
 *
 * JWT decoding is mocked to create deterministic
 * authenticated test users.
 *
 * The business memberships and authorization logic
 * remain real and are checked against PostgreSQL.
 *
 * Each test is transactional and automatically rolled
 * back after completion.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccountingPeriodControllerIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    /**
     * Execute fixture SQL and verify database state.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Create fiscal years and their accounting periods.
     */
    @Autowired
    private FiscalYearService fiscalYearService;

    /**
     * Retrieve accounting periods directly from PostgreSQL.
     */
    @Autowired
    private AccountingPeriodMapper accountingPeriodMapper;

    /**
     * Inject MockMvc with the real Spring Security filters.
     *
     * Do not construct MockMvc manually without those filters,
     * because doing so bypasses authentication and authorization.
     */
    @Autowired
    private MockMvc mvc;

    /**
     * Mock JWT decoding for these integration tests.
     *
     * Authentication requests still pass through the
     * actual Spring Security resource-server filter.
     *
     * Only token decoding is replaced.
     */
    @MockitoBean
    private JwtDecoder jwtDecoder;


    // ========================================================
    // TEST DATA
    // ========================================================

    private Long testUserId;

    private Long testBusinessId;


    // ========================================================
    // SETUP — TEST USER AND BUSINESS
    // ========================================================

    /**
     * Create a new test user and business.
     *
     * The database generates their IDs.
     *
     * We never assume that user ID or business ID is 1.
     *
     * The business initially uses January as its
     * fiscal-year starting month.
     *
     * Because we insert the business directly through
     * JDBC, we explicitly provision its OWNER membership.
     */
    @BeforeEach
    void setUp() {

        String email = "period-api-"
            + UUID.randomUUID()
            + "@ledgerbase.local";


        // ----------------------------------------------------
        // CREATE TEST USER
        // ----------------------------------------------------

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


        // ----------------------------------------------------
        // CREATE TEST BUSINESS
        // ----------------------------------------------------

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


        // ----------------------------------------------------
        // GRANT OWNER MEMBERSHIP
        // ----------------------------------------------------

        /**
         * The authorization manager checks memberships,
         * not merely businesses.owner_id.
         *
         * Therefore, creating a business through JDBC
         * also requires an explicit membership fixture.
         */
        grantOwnerMembership(testBusinessId);
    }


    // ========================================================
    // SETUP — JWT DECODER
    // ========================================================

    /**
     * Configure a deterministic mocked JWT decoder.
     *
     * Test Bearer tokens have this format:
     *
     * period-test-user-{userId}
     *
     * Example:
     *
     * period-test-user-42
     *
     * The resulting JWT subject is "42".
     *
     * This matches LedgerBase's use of PostgreSQL
     * user IDs as JWT subjects.
     *
     * Only test tokens with positive numeric
     * subjects are accepted.
     */
    @BeforeEach
    void configureTestJwtDecoder() {

        when(jwtDecoder.decode(anyString()))
            .thenAnswer(invocation -> {

                String token = invocation.getArgument(0);

                String prefix = "period-test-user-";

                if (!token.startsWith(prefix)) {
                    throw new BadJwtException(
                        "Invalid test Bearer token"
                    );
                }

                String subject = token.substring(
                    prefix.length()
                );


                // --------------------------------------------
                // VALIDATE SUBJECT
                // --------------------------------------------

                try {

                    long userId = Long.parseLong(subject);

                    if (userId <= 0) {
                        throw new NumberFormatException(
                            "User ID must be positive"
                        );
                    }

                } catch (NumberFormatException exception) {

                    throw new BadJwtException(
                        "Invalid test JWT subject"
                    );
                }


                // --------------------------------------------
                // BUILD AUTHENTICATED JWT
                // --------------------------------------------

                Instant now = Instant.now();

                return Jwt.withTokenValue(token)
                    .header("alg", "HS256")
                    .subject(subject)
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(900))
                    .build();
            });
    }


    // ========================================================
    // HELPER — GRANT BUSINESS OWNER MEMBERSHIP
    // ========================================================

    /**
     * Add the current test user as an OWNER of a business.
     *
     * This allows Spring Security to authorize business
     * read and write operations for the test user.
     *
     * ON CONFLICT avoids duplicate membership inserts
     * if provisioning has already created the row.
     *
     * The membership belongs only to ledgerbase_test
     * and is rolled back after the test.
     */
    private void grantOwnerMembership(Long businessId) {

        jdbcTemplate.update(
            """
            INSERT INTO business_memberships (
                business_id,
                user_id,
                role
            )
            VALUES (?, ?, 'OWNER')
            ON CONFLICT (business_id, user_id) DO NOTHING
            """,
            businessId,
            testUserId
        );
    }


    // ========================================================
    // HELPER — AUTHENTICATED BEARER TOKEN
    // ========================================================

    /**
     * Construct a test-only Bearer token for a user ID.
     *
     * The JWT decoder converts the token into an
     * authenticated principal.
     *
     * A valid JWT alone is not enough to authorize
     * a business operation: the user must also hold
     * the required business membership.
     */
    private String bearerFor(Long userId) {

        return "Bearer period-test-user-" + userId;
    }


    // ========================================================
    // HELPER — AUTHENTICATED GET
    // ========================================================

    /**
     * Create an authenticated GET request.
     *
     * The current test user is the JWT subject.
     */
    private MockHttpServletRequestBuilder authenticatedGet(
            String url) {

        return get(url)
            .header(
                HttpHeaders.AUTHORIZATION,
                bearerFor(testUserId)
            );
    }


    // ========================================================
    // HELPER — AUTHENTICATED POST
    // ========================================================

    /**
     * Create an authenticated POST request.
     *
     * Accounting-period changes derive the actor
     * from this authenticated JWT.
     *
     * Any actorId inside the JSON body is ignored.
     */
    private MockHttpServletRequestBuilder authenticatedPost(
            String url) {

        return post(url)
            .header(
                HttpHeaders.AUTHORIZATION,
                bearerFor(testUserId)
            );
    }


    // ========================================================
    // HELPER — FISCAL YEAR PERIODS URL
    // ========================================================

    /**
     * Build the URL for retrieving the accounting
     * periods of a fiscal year.
     */
    private String periodsUrl(Long fiscalYearId) {

        return "/api/v1/businesses/"
            + testBusinessId
            + "/fiscal-years/"
            + fiscalYearId
            + "/periods";
    }


    // ========================================================
    // HELPER — INDIVIDUAL PERIOD URL
    // ========================================================

    /**
     * Build the URL for retrieving or modifying
     * one accounting period.
     */
    private String periodUrl(Long periodId) {

        return "/api/v1/businesses/"
            + testBusinessId
            + "/accounting-periods/"
            + periodId;
    }


    // ========================================================
    // LIST ALL TWELVE ACCOUNTING PERIODS
    // ========================================================

    /**
     * Create FY2027 and retrieve its 12 monthly periods.
     *
     * Verify that:
     *
     * - The endpoint returns HTTP 200.
     * - Exactly 12 periods exist.
     * - Periods are ordered from 1 through 12.
     * - The expected calendar dates are returned.
     * - New periods start with OPEN status.
     * - PostgreSQL contains exactly 12 periods.
     */
    @Test
    void listsTwelveAccountingPeriodsInOrder()
            throws Exception {

        FiscalYear fiscalYear =
            fiscalYearService.createFiscalYear(
                testBusinessId,
                2027
            );


        // ----------------------------------------------------
        // REQUEST PERIODS THROUGH AUTHENTICATED HTTP
        // ----------------------------------------------------

        mvc.perform(
                authenticatedGet(
                    periodsUrl(fiscalYear.getId())
                )
            )

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


        // ----------------------------------------------------
        // VERIFY DATABASE RECORD COUNT
        // ----------------------------------------------------

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
    // RETRIEVE AN INDIVIDUAL ACCOUNTING PERIOD
    // ========================================================

    /**
     * Retrieve October's accounting period.
     *
     * Verify the returned ID, business, fiscal year,
     * period number, dates, and status.
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

        // Index 9 corresponds to period 10.
        AccountingPeriod october = periods.get(9);


        // ----------------------------------------------------
        // REQUEST ACCOUNTING PERIOD
        // ----------------------------------------------------

        mvc.perform(
                authenticatedGet(
                    periodUrl(october.getId())
                )
            )

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.id")
                    .value(october.getId())
            )

            .andExpect(
                jsonPath("$.businessId")
                    .value(testBusinessId)
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
    // RETURN 404 FOR MISSING FISCAL YEAR
    // ========================================================

    /**
     * An authenticated business owner must receive
     * HTTP 404 when the fiscal year does not exist.
     *
     * Long.MAX_VALUE is used as a nonexistent ID.
     */
    @Test
    void returnsNotFoundForMissingFiscalYear()
            throws Exception {

        mvc.perform(
                authenticatedGet(
                    periodsUrl(Long.MAX_VALUE)
                )
            )

            .andExpect(status().isNotFound());
    }


    // ========================================================
    // RETURN 404 FOR MISSING ACCOUNTING PERIOD
    // ========================================================

    /**
     * An authenticated business owner must receive
     * HTTP 404 when an accounting period is missing.
     */
    @Test
    void returnsNotFoundForMissingAccountingPeriod()
            throws Exception {

        mvc.perform(
                authenticatedGet(
                    periodUrl(Long.MAX_VALUE)
                )
            )

            .andExpect(status().isNotFound());
    }


    // ========================================================
    // CROSS-BUSINESS FISCAL YEAR ISOLATION
    // ========================================================

    /**
     * A fiscal year belonging to Business A must not
     * be returned through Business B's URL.
     *
     * Both businesses belong to the same test user.
     *
     * The test user has OWNER membership in both.
     *
     * Therefore, authorization succeeds, but the
     * service must still enforce business-scoped
     * data isolation and return HTTP 404.
     */
    @Test
    void preventsCrossBusinessFiscalYearAccess()
            throws Exception {

        // ----------------------------------------------------
        // CREATE BUSINESS A'S FISCAL YEAR
        // ----------------------------------------------------

        FiscalYear fiscalYear =
            fiscalYearService.createFiscalYear(
                testBusinessId,
                2027
            );


        // ----------------------------------------------------
        // CREATE BUSINESS B
        // ----------------------------------------------------

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


        // ----------------------------------------------------
        // AUTHORIZE ACCESS TO BUSINESS B
        // ----------------------------------------------------

        grantOwnerMembership(otherBusinessId);


        // ----------------------------------------------------
        // REQUEST BUSINESS A'S YEAR THROUGH BUSINESS B
        // ----------------------------------------------------

        String otherBusinessUrl =
            "/api/v1/businesses/"
            + otherBusinessId
            + "/fiscal-years/"
            + fiscalYear.getId()
            + "/periods";

        mvc.perform(
                authenticatedGet(otherBusinessUrl)
            )

            .andExpect(status().isNotFound());
    }


    // ========================================================
    // CROSS-BUSINESS ACCOUNTING PERIOD ISOLATION
    // ========================================================

    /**
     * Knowing an accounting-period ID from another
     * business must not provide access to that period.
     *
     * Authorization for Business B is valid.
     *
     * The service must check both business_id
     * and period ID.
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


        // ----------------------------------------------------
        // CREATE BUSINESS B
        // ----------------------------------------------------

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

        grantOwnerMembership(otherBusinessId);


        // ----------------------------------------------------
        // REQUEST A FOREIGN PERIOD
        // ----------------------------------------------------

        String otherBusinessUrl =
            "/api/v1/businesses/"
            + otherBusinessId
            + "/accounting-periods/"
            + firstPeriod.getId();

        mvc.perform(
                authenticatedGet(otherBusinessUrl)
            )

            .andExpect(status().isNotFound());
    }


    // ========================================================
    // SUPPORT NON-CALENDAR FISCAL YEARS
    // ========================================================

    /**
     * Verify accounting periods for a fiscal year
     * starting in April rather than January.
     *
     * FY2027:
     *
     * April 1, 2026 through March 31, 2027.
     */
    @Test
    void listsPeriodsForAprilStartingFiscalYear()
            throws Exception {

        // ----------------------------------------------------
        // CONFIGURE APRIL FISCAL-YEAR START
        // ----------------------------------------------------

        jdbcTemplate.update(
            """
            UPDATE businesses
            SET fiscal_year_start_month = 4
            WHERE id = ?
            """,
            testBusinessId
        );


        // ----------------------------------------------------
        // CREATE FISCAL YEAR
        // ----------------------------------------------------

        FiscalYear fiscalYear =
            fiscalYearService.createFiscalYear(
                testBusinessId,
                2027
            );


        // ----------------------------------------------------
        // VERIFY MONTHLY PERIODS THROUGH HTTP
        // ----------------------------------------------------

        mvc.perform(
                authenticatedGet(
                    periodsUrl(fiscalYear.getId())
                )
            )

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
    // REJECT REQUESTS FOR UNAUTHORIZED BUSINESS
    // ========================================================

    /**
     * A request for a business with no valid membership
     * must be denied by Spring Security.
     *
     * This includes a business ID that does not exist.
     *
     * Previously, bypassing security filters allowed
     * the controller to return HTTP 404.
     *
     * With business membership authorization enabled,
     * access is denied before reaching the controller.
     *
     * Therefore, we expect HTTP 403 for these requests.
     */
    @Test
    void rejectsRequestsForMissingBusiness()
            throws Exception {

        // ----------------------------------------------------
        // REQUEST FISCAL-YEAR PERIODS
        // ----------------------------------------------------

        mvc.perform(
                authenticatedGet(
                    "/api/v1/businesses/-1"
                    + "/fiscal-years/1/periods"
                )
            )

            .andExpect(status().isForbidden());


        // ----------------------------------------------------
        // REQUEST INDIVIDUAL ACCOUNTING PERIOD
        // ----------------------------------------------------

        mvc.perform(
                authenticatedGet(
                    "/api/v1/businesses/-1"
                    + "/accounting-periods/1"
                )
            )

            .andExpect(status().isForbidden());
    }


    // ========================================================
    // CLOSE AND REOPEN AN ACCOUNTING PERIOD THROUGH HTTP
    // ========================================================

    /**
     * Verify the full accounting-period lifecycle.
     *
     * Steps:
     *
     * - Create a fiscal year.
     * - Select an OPEN period.
     * - Close the period.
     * - Verify CLOSED status and closedAt.
     * - Retrieve the CLOSED period through GET.
     * - Reopen the period.
     * - Verify OPEN status and cleared closedAt.
     * - Retrieve the OPEN period through GET.
     * - Verify both audit events use the JWT user.
     */
    @Test
    void closesAndReopensAccountingPeriodThroughApi()
            throws Exception {

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


        // ----------------------------------------------------
        // CLOSE ACCOUNTING PERIOD
        // ----------------------------------------------------

        /**
         * No actorId is supplied.
         *
         * Spring Security determines the actor from
         * the authenticated JWT.
         */
        mvc.perform(
                authenticatedPost(
                    periodUrl(period.getId()) + "/close"
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
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


        // ----------------------------------------------------
        // VERIFY CLOSED STATUS THROUGH GET
        // ----------------------------------------------------

        mvc.perform(
                authenticatedGet(
                    periodUrl(period.getId())
                )
            )

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.status").value("CLOSED")
            );


        // ----------------------------------------------------
        // REOPEN ACCOUNTING PERIOD
        // ----------------------------------------------------

        mvc.perform(
                authenticatedPost(
                    periodUrl(period.getId()) + "/reopen"
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
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


        // ----------------------------------------------------
        // VERIFY OPEN STATUS THROUGH GET
        // ----------------------------------------------------

        mvc.perform(
                authenticatedGet(
                    periodUrl(period.getId())
                )
            )

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.status").value("OPEN")
            );


        // ----------------------------------------------------
        // VERIFY AUDIT EVENTS
        // ----------------------------------------------------

        /**
         * Both status changes must be attributed
         * to the authenticated test user.
         *
         * These are the two expected audit actions:
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
    // CANNOT CLOSE AN ALREADY CLOSED PERIOD
    // ========================================================

    /**
     * A period can only be closed when its status is OPEN.
     *
     * First close:
     * HTTP 200
     *
     * Second close:
     * HTTP 409 Conflict
     */
    @Test
    void returnsConflictWhenClosingAlreadyClosedPeriod()
            throws Exception {

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


        // ----------------------------------------------------
        // FIRST CLOSE — SUCCESS
        // ----------------------------------------------------

        mvc.perform(
                authenticatedPost(closeUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.status").value("CLOSED")
            );


        // ----------------------------------------------------
        // SECOND CLOSE — CONFLICT
        // ----------------------------------------------------

        mvc.perform(
                authenticatedPost(closeUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )

            .andExpect(status().isConflict())

            .andExpect(
                jsonPath("$.error").isNotEmpty()
            );
    }


    // ========================================================
    // CANNOT REOPEN AN ALREADY OPEN PERIOD
    // ========================================================

    /**
     * Reopening is only valid when the current
     * accounting-period status is CLOSED.
     *
     * Newly created periods start OPEN.
     *
     * Reopening one immediately must return HTTP 409.
     */
    @Test
    void returnsConflictWhenReopeningOpenPeriod()
            throws Exception {

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


        // ----------------------------------------------------
        // ATTEMPT INVALID REOPEN
        // ----------------------------------------------------

        mvc.perform(
                authenticatedPost(
                    periodUrl(period.getId()) + "/reopen"
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )

            .andExpect(status().isConflict())

            .andExpect(
                jsonPath("$.error").isNotEmpty()
            );
    }


    // ========================================================
    // IGNORE CLIENT-SUPPLIED ACTOR IDENTITIES
    // ========================================================

    /**
     * Previously, this test expected HTTP 400 for missing
     * or invalid client-supplied actorId values.
     *
     * That is no longer the correct security contract.
     *
     * Authentication now comes from the JWT.
     *
     * Therefore:
     *
     * - Missing actorId is accepted.
     * - A forged positive actorId is ignored.
     * - Zero actorId is ignored.
     * - Negative actorId is ignored.
     *
     * Audit events must always contain the authenticated
     * JWT user's database ID.
     */
    @Test
    void ignoresClientSuppliedActorIdForPeriodActions()
            throws Exception {

        // ----------------------------------------------------
        // CREATE OPEN ACCOUNTING PERIOD
        // ----------------------------------------------------

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


        // ----------------------------------------------------
        // CLOSE WITHOUT ACTOR ID
        // ----------------------------------------------------

        mvc.perform(
                authenticatedPost(closeUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.status").value("CLOSED")
            );


        // ----------------------------------------------------
        // REOPEN WITH FORGED ACTOR ID
        // ----------------------------------------------------

        /**
         * The forged actor ID is not a real test user.
         *
         * The endpoint must ignore it and attribute
         * the action to testUserId from the JWT.
         */
        mvc.perform(
                authenticatedPost(reopenUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"actorId\":999999999}")
            )

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.status").value("OPEN")
            );


        // ----------------------------------------------------
        // CLOSE WITH ZERO ACTOR ID
        // ----------------------------------------------------

        mvc.perform(
                authenticatedPost(closeUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"actorId\":0}")
            )

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.status").value("CLOSED")
            );


        // ----------------------------------------------------
        // REOPEN WITH NEGATIVE ACTOR ID
        // ----------------------------------------------------

        mvc.perform(
                authenticatedPost(reopenUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"actorId\":-1}")
            )

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.status").value("OPEN")
            );


        // ----------------------------------------------------
        // VERIFY AUTHENTICATED AUDIT ATTRIBUTION
        // ----------------------------------------------------

        /**
         * Four valid state transitions occurred.
         *
         * All four must be attributed to the actual
         * authenticated user, regardless of the
         * submitted actorId values.
         */
        Long authenticatedAuditCount =
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

        assertEquals(4L, authenticatedAuditCount);


        // ----------------------------------------------------
        // VERIFY FORGED USER IS NEVER RECORDED
        // ----------------------------------------------------

        Long forgedAuditCount =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_events
                WHERE business_id = ?
                  AND entity_type = 'ACCOUNTING_PERIOD'
                  AND entity_id = ?
                  AND actor_id = 999999999
                """,
                Long.class,
                testBusinessId,
                period.getId()
            );

        assertEquals(0L, forgedAuditCount);


        // ----------------------------------------------------
        // VERIFY FINAL PERIOD STATUS
        // ----------------------------------------------------

        AccountingPeriod finalPeriod =
            accountingPeriodMapper.findById(
                testBusinessId,
                period.getId()
            );

        assertEquals(
            AccountingPeriod.Status.OPEN,
            finalPeriod.getStatus()
        );
    }


    // ========================================================
    // MISSING PERIOD RETURNS HTTP 404 FOR BOTH OPERATIONS
    // ========================================================

    /**
     * Close and reopen operations must return HTTP 404
     * when a period does not exist within the
     * authenticated user's authorized business.
     *
     * Missing period:
     * HTTP 404
     *
     * Invalid state transition:
     * HTTP 409
     */
    @Test
    void returnsNotFoundWhenClosingOrReopeningMissingPeriod()
            throws Exception {

        String missingPeriodUrl =
            periodUrl(Long.MAX_VALUE);


        // ----------------------------------------------------
        // CLOSE NONEXISTENT PERIOD
        // ----------------------------------------------------

        mvc.perform(
                authenticatedPost(
                    missingPeriodUrl + "/close"
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )

            .andExpect(status().isNotFound());


        // ----------------------------------------------------
        // REOPEN NONEXISTENT PERIOD
        // ----------------------------------------------------

        mvc.perform(
                authenticatedPost(
                    missingPeriodUrl + "/reopen"
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )

            .andExpect(status().isNotFound());
    }


    // ========================================================
    // PREVENT CROSS-BUSINESS PERIOD STATUS CHANGES
    // ========================================================

    /**
     * A period belonging to Business A cannot be closed
     * or reopened through Business B's endpoint.
     *
     * The user holds OWNER membership in both businesses,
     * so authentication and RBAC checks pass.
     *
     * The service must independently enforce period
     * ownership and return HTTP 404.
     *
     * The original period must remain unchanged.
     */
    @Test
    void preventsCrossBusinessPeriodStatusChanges()
            throws Exception {

        // ----------------------------------------------------
        // CREATE BUSINESS A'S PERIOD
        // ----------------------------------------------------

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


        // ----------------------------------------------------
        // CREATE BUSINESS B
        // ----------------------------------------------------

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


        // ----------------------------------------------------
        // AUTHORIZE BUSINESS B
        // ----------------------------------------------------

        grantOwnerMembership(otherBusinessId);


        // ----------------------------------------------------
        // BUILD INCORRECT BUSINESS URL
        // ----------------------------------------------------

        String otherBusinessPeriodUrl =
            "/api/v1/businesses/"
            + otherBusinessId
            + "/accounting-periods/"
            + period.getId();


        // ----------------------------------------------------
        // ATTEMPT CLOSE THROUGH BUSINESS B
        // ----------------------------------------------------

        mvc.perform(
                authenticatedPost(
                    otherBusinessPeriodUrl + "/close"
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )

            .andExpect(status().isNotFound());


        // ----------------------------------------------------
        // ATTEMPT REOPEN THROUGH BUSINESS B
        // ----------------------------------------------------

        mvc.perform(
                authenticatedPost(
                    otherBusinessPeriodUrl + "/reopen"
                )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )

            .andExpect(status().isNotFound());


        // ----------------------------------------------------
        // VERIFY BUSINESS A'S PERIOD IS UNCHANGED
        // ----------------------------------------------------

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
    // REJECT UNAUTHENTICATED ACCOUNTING PERIOD CHANGES
    // ========================================================

    /**
     * An unauthenticated request cannot change
     * an accounting period.
     *
     * Even if the browser submits a valid database
     * user ID inside actorId, that does not
     * constitute authentication.
     *
     * Spring Security must return HTTP 401 before
     * executing the accounting-period operation.
     */
    @Test
    void rejectsUnauthenticatedPeriodChanges()
            throws Exception {

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


        // ----------------------------------------------------
        // SEND REQUEST WITHOUT BEARER TOKEN
        // ----------------------------------------------------

        /**
         * Use post() directly rather than authenticatedPost().
         *
         * The Authorization header is intentionally absent.
         */
        mvc.perform(
                post(periodUrl(period.getId()) + "/close")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"actorId\":" + testUserId + "}"
                    )
            )

            .andExpect(status().isUnauthorized());


        // ----------------------------------------------------
        // VERIFY STATUS REMAINS OPEN
        // ----------------------------------------------------

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
    // REJECT AUTHENTICATED USERS WITHOUT BUSINESS MEMBERSHIP
    // ========================================================

    /**
     * JWT authentication does not automatically
     * authorize access to every business.
     *
     * This test creates another valid user who has
     * no membership in the target business.
     *
     * The new user is authenticated successfully
     * but must receive HTTP 403.
     *
     * The accounting-period status must not change.
     */
    @Test
    void rejectsPeriodActionForNonmember()
            throws Exception {

        // ----------------------------------------------------
        // CREATE A PERIOD IN THE ORIGINAL BUSINESS
        // ----------------------------------------------------

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


        // ----------------------------------------------------
        // CREATE AN UNRELATED TEST USER
        // ----------------------------------------------------

        Long unrelatedUserId = jdbcTemplate.queryForObject(
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
            "period-nonmember-"
                + UUID.randomUUID()
                + "@ledgerbase.local",
            "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
            "Nonmember Test User"
        );


        // ----------------------------------------------------
        // SEND AUTHENTICATED REQUEST AS NONMEMBER
        // ----------------------------------------------------

        /**
         * The JWT identifies a real user.
         *
         * However, the user has no membership in
         * testBusinessId, so the authorization manager
         * must deny this write operation.
         */
        mvc.perform(
                post(periodUrl(period.getId()) + "/close")
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        bearerFor(unrelatedUserId)
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )

            .andExpect(status().isForbidden());


        // ----------------------------------------------------
        // VERIFY STATUS REMAINS UNCHANGED
        // ----------------------------------------------------

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
