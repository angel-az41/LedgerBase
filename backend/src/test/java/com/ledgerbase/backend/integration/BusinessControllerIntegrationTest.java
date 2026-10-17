package com.ledgerbase.backend.integration;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// ============================================================
// LEDGERBASE — BUSINESS CONTROLLER INTEGRATION TESTS
// ============================================================

/**
 * Verify the Business Management REST API.
 *
 * REQUEST FLOW:
 *
 * MockMvc HTTP Request
 *         |
 *         v
 * BusinessController
 *         |
 *         v
 * BusinessService
 *         |
 *         v
 * BusinessMapper
 *         |
 *         v
 * PostgreSQL
 *
 * TEST COVERAGE:
 *
 * 1. List businesses.
 * 2. Retrieve an existing business.
 * 3. Reject another owner's business.
 * 4. Return 404 for an unknown business.
 * 5. Create a business through HTTP.
 * 6. Apply default configuration.
 * 7. Normalize request fields.
 * 8. Reject missing or blank names.
 * 9. Reject excessively long names.
 * 10. Reject invalid currencies.
 * 11. Reject invalid fiscal-year months.
 * 12. Reject invalid business IDs.
 *
 * IMPORTANT:
 *
 * The controller currently uses development owner ID 1.
 *
 * This test prepares that owner in the TEST database.
 *
 * A second owner is used to verify that controller
 * queries do not expose the second owner's businesses.
 *
 * Owner filtering is NOT authentication.
 *
 * Future JWT/RBAC tests must verify that callers
 * cannot impersonate another business owner.
 *
 * DATABASE ISOLATION:
 *
 * @ActiveProfiles("test") selects ledgerbase_test.
 *
 * @Transactional rolls back temporary fixture data
 * after every test.
 *
 * The normal development database is not modified.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BusinessControllerIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mvc;


    // ========================================================
    // API CONFIGURATION
    // ========================================================

    /**
     * Base URL implemented by BusinessController.
     */
    private static final String BUSINESSES_URL =
        "/api/v1/businesses";

    /**
     * Temporary development owner configured in
     * BusinessController.
     *
     * This constant must stay consistent with the
     * current local-development controller.
     */
    private static final Long DEVELOPMENT_OWNER_ID = 1L;


    // ========================================================
    // TEST FIXTURE IDENTIFIERS
    // ========================================================

    private Long otherOwnerId;

    private Long developmentBusinessId;

    private Long otherBusinessId;

    private String developmentBusinessName;

    private String otherBusinessName;


    // ========================================================
    // SETUP
    // ========================================================

    /**
     * Runs before every test.
     *
     * We prepare:
     *
     * Development owner (ID 1)
     *   - Development test business
     *
     * Other owner
     *   - Independent test business
     *
     * This allows us to verify that the controller
     * only accesses the fixed development owner's data.
     */
    @BeforeEach
    void setUp() {

        // ====================================================
        // INITIALIZE SPRING MVC TEST CLIENT
        // ====================================================

        mvc = MockMvcBuilders
            .webAppContextSetup(context)
            .build();


        // ====================================================
        // CREATE AN INDEPENDENT TEST USER
        // ====================================================

        /**
         * Create a user using PostgreSQL's normal
         * generated-ID mechanism.
         *
         * If this is the first user in a fresh test
         * database, PostgreSQL might assign ID 1.
         *
         * In that case, the user can serve as our
         * development-owner fixture, and we create
         * another user for owner-isolation tests.
         */
        Long firstUserId = createTestUser(
            "Business API Test User"
        );

        if (DEVELOPMENT_OWNER_ID.equals(firstUserId)) {

            otherOwnerId = createTestUser(
                "Independent Business API Owner"
            );

        } else {

            otherOwnerId = firstUserId;
        }


        // ====================================================
        // ENSURE DEVELOPMENT OWNER EXISTS
        // ====================================================

        /**
         * BusinessController currently uses owner ID 1.
         *
         * The isolated test database may not already
         * contain a user with that ID.
         *
         * This helper inserts the development owner
         * only if ID 1 is absent.
         *
         * The operation belongs to the test transaction
         * and is rolled back afterward.
         */
        ensureDevelopmentOwnerExists();


        // ====================================================
        // PREPARE UNIQUE BUSINESS NAMES
        // ====================================================

        /**
         * UUID avoids confusion with existing data,
         * even if the test database already contains
         * business records.
         */
        developmentBusinessName =
            "Development API Business " + UUID.randomUUID();

        otherBusinessName =
            "Other Owner API Business " + UUID.randomUUID();


        // ====================================================
        // CREATE DEVELOPMENT OWNER'S BUSINESS
        // ====================================================

        developmentBusinessId = createTestBusiness(
            DEVELOPMENT_OWNER_ID,
            developmentBusinessName
        );


        // ====================================================
        // CREATE OTHER OWNER'S BUSINESS
        // ====================================================

        otherBusinessId = createTestBusiness(
            otherOwnerId,
            otherBusinessName
        );
    }


    // ========================================================
    // TEST 1 — LIST BUSINESSES
    // ========================================================

    /**
     * GET /api/v1/businesses
     *
     * The response must contain the development
     * owner's business.
     *
     * It must not contain the other owner's business.
     */
    @Test
    void listsOnlyDevelopmentOwnersBusinesses()
            throws Exception {

        mvc.perform(get(BUSINESSES_URL))

            // =================================================
            // VERIFY HTTP STATUS
            // =================================================

            .andExpect(status().isOk())

            // =================================================
            // VERIFY DEVELOPMENT BUSINESS IS INCLUDED
            // =================================================

            .andExpect(
                content().string(
                    containsString(developmentBusinessName)
                )
            )

            // =================================================
            // VERIFY OTHER OWNER IS EXCLUDED
            // =================================================

            .andExpect(
                content().string(
                    not(containsString(otherBusinessName))
                )
            );
    }


    // ========================================================
    // TEST 2 — RETRIEVE AN EXISTING BUSINESS
    // ========================================================

    /**
     * GET /api/v1/businesses/{businessId}
     *
     * An existing development-owned business
     * should return HTTP 200 and its JSON fields.
     */
    @Test
    void retrievesExistingBusiness() throws Exception {

        mvc.perform(get(
                BUSINESSES_URL + "/" + developmentBusinessId
            ))

            .andExpect(status().isOk())

            .andExpect(
                jsonPath("$.id").value(developmentBusinessId)
            )

            .andExpect(
                jsonPath("$.ownerId").value(
                    DEVELOPMENT_OWNER_ID
                )
            )

            .andExpect(
                jsonPath("$.name").value(
                    developmentBusinessName
                )
            )

            .andExpect(
                jsonPath("$.currency").value("USD")
            )

            .andExpect(
                jsonPath("$.fiscalYearStartMonth").value(1)
            )

            .andExpect(
                jsonPath("$.createdAt").exists()
            );
    }


    // ========================================================
    // TEST 3 — OWNER-SCOPED RETRIEVAL
    // ========================================================

    /**
     * A business owned by another user should
     * not be returned by the development controller.
     *
     * BusinessService throws BusinessNotFoundException.
     *
     * GlobalExceptionHandler converts it to HTTP 404.
     */
    @Test
    void doesNotExposeAnotherOwnersBusiness()
            throws Exception {

        mvc.perform(get(
                BUSINESSES_URL + "/" + otherBusinessId
            ))

            .andExpect(status().isNotFound())

            .andExpect(
                jsonPath("$.error").exists()
            );
    }


    // ========================================================
    // TEST 4 — UNKNOWN BUSINESS
    // ========================================================

    /**
     * A nonexistent business should return HTTP 404.
     *
     * Long.MAX_VALUE is used as an unlikely valid ID.
     */
    @Test
    void returnsNotFoundForMissingBusiness()
            throws Exception {

        mvc.perform(get(
                BUSINESSES_URL + "/" + Long.MAX_VALUE
            ))

            .andExpect(status().isNotFound())

            .andExpect(
                jsonPath("$.error").exists()
            );
    }


    // ========================================================
    // TEST 5 — CREATE BUSINESS THROUGH HTTP
    // ========================================================

    /**
     * POST /api/v1/businesses
     *
     * A valid request should:
     *
     * 1. Return HTTP 201.
     * 2. Return the normalized business fields.
     * 3. Return a generated business ID.
     * 4. Return the creation timestamp.
     * 5. Persist the business in PostgreSQL.
     */
    @Test
    void createsBusinessThroughApi() throws Exception {

        // ====================================================
        // CHOOSE A UNIQUE BUSINESS NAME
        // ====================================================

        String businessName =
            "New API Consulting " + UUID.randomUUID();


        // ====================================================
        // SEND HTTP POST REQUEST
        // ====================================================

        mvc.perform(post(BUSINESSES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "%s",
                        "currency": "USD",
                        "fiscalYearStartMonth": 1
                    }
                    """.formatted(businessName)))

            // =================================================
            // VERIFY HTTP 201 CREATED
            // =================================================

            .andExpect(status().isCreated())

            // =================================================
            // VERIFY RESPONSE BODY
            // =================================================

            .andExpect(
                jsonPath("$.id").isNumber()
            )

            .andExpect(
                jsonPath("$.ownerId").value(
                    DEVELOPMENT_OWNER_ID
                )
            )

            .andExpect(
                jsonPath("$.name").value(businessName)
            )

            .andExpect(
                jsonPath("$.currency").value("USD")
            )

            .andExpect(
                jsonPath("$.fiscalYearStartMonth").value(1)
            )

            .andExpect(
                jsonPath("$.createdAt").exists()
            );


        // ====================================================
        // VERIFY DATABASE PERSISTENCE
        // ====================================================

        /**
         * We verify that PostgreSQL contains the
         * newly created business under owner ID 1.
         */
        Long businessCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM businesses
            WHERE owner_id = ?
              AND name = ?
            """,
            Long.class,
            DEVELOPMENT_OWNER_ID,
            businessName
        );

        assertEquals(Long.valueOf(1), businessCount);
    }


    // ========================================================
    // TEST 6 — DEFAULT BUSINESS CONFIGURATION
    // ========================================================

    /**
     * Omitting optional fields should apply:
     *
     * currency = USD
     * fiscalYearStartMonth = 1
     */
    @Test
    void appliesDefaultsThroughApi() throws Exception {

        mvc.perform(post(BUSINESSES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Default API Configuration"
                    }
                    """))

            .andExpect(status().isCreated())

            .andExpect(
                jsonPath("$.currency").value("USD")
            )

            .andExpect(
                jsonPath("$.fiscalYearStartMonth").value(1)
            );
    }


    // ========================================================
    // TEST 7 — NORMALIZE REQUEST FIELDS
    // ========================================================

    /**
     * BusinessService should:
     *
     * - Trim business names.
     * - Trim currency codes.
     * - Convert currency codes to uppercase.
     *
     * A custom fiscal-year starting month must
     * be preserved.
     */
    @Test
    void normalizesBusinessInputThroughApi()
            throws Exception {

        mvc.perform(post(BUSINESSES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "   Example Consulting LLC   ",
                        "currency": " gbp ",
                        "fiscalYearStartMonth": 4
                    }
                    """))

            .andExpect(status().isCreated())

            .andExpect(
                jsonPath("$.name").value(
                    "Example Consulting LLC"
                )
            )

            .andExpect(
                jsonPath("$.currency").value("GBP")
            )

            .andExpect(
                jsonPath("$.fiscalYearStartMonth").value(4)
            );
    }


    // ========================================================
    // TEST 8 — REJECT MISSING OR BLANK BUSINESS NAME
    // ========================================================

    /**
     * @NotBlank on CreateBusinessRequest.name
     * should reject missing or whitespace-only names.
     *
     * GlobalExceptionHandler returns HTTP 400.
     */
    @Test
    void rejectsMissingAndBlankBusinessNames()
            throws Exception {

        // ====================================================
        // MISSING BUSINESS NAME
        // ====================================================

        mvc.perform(post(BUSINESSES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "currency": "USD"
                    }
                    """))

            .andExpect(status().isBadRequest())

            .andExpect(
                jsonPath("$.error").exists()
            );


        // ====================================================
        // BLANK BUSINESS NAME
        // ====================================================

        mvc.perform(post(BUSINESSES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "    "
                    }
                    """))

            .andExpect(status().isBadRequest())

            .andExpect(
                jsonPath("$.error").exists()
            );
    }


    // ========================================================
    // TEST 9 — REJECT EXCESSIVELY LONG BUSINESS NAME
    // ========================================================

    /**
     * PostgreSQL stores business names as VARCHAR(200).
     *
     * CreateBusinessRequest uses @Size(max = 200).
     *
     * A name containing 201 characters must be rejected.
     */
    @Test
    void rejectsBusinessNamesOver200Characters()
            throws Exception {

        String longName = "A".repeat(201);

        mvc.perform(post(BUSINESSES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "%s"
                    }
                    """.formatted(longName)))

            .andExpect(status().isBadRequest())

            .andExpect(
                jsonPath("$.error").exists()
            );
    }


    // ========================================================
    // TEST 10 — REJECT INVALID CURRENCY
    // ========================================================

    /**
     * Currency must contain exactly three letters.
     *
     * This service-level validation rejects
     * invalid values before database insertion.
     */
    @Test
    void rejectsInvalidCurrencyThroughApi()
            throws Exception {

        mvc.perform(post(BUSINESSES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Invalid Currency Business",
                        "currency": "US1"
                    }
                    """))

            .andExpect(status().isBadRequest())

            .andExpect(
                jsonPath("$.error").exists()
            );
    }


    // ========================================================
    // TEST 11 — REJECT INVALID FISCAL-YEAR MONTH
    // ========================================================

    /**
     * The request DTO permits fiscal-year
     * starting months from 1 through 12.
     *
     * Month 13 must return HTTP 400.
     */
    @Test
    void rejectsInvalidFiscalYearStartMonth()
            throws Exception {

        mvc.perform(post(BUSINESSES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Invalid Calendar Business",
                        "currency": "USD",
                        "fiscalYearStartMonth": 13
                    }
                    """))

            .andExpect(status().isBadRequest())

            .andExpect(
                jsonPath("$.error").exists()
            );
    }


    // ========================================================
    // TEST 12 — REJECT INVALID BUSINESS ID
    // ========================================================

    /**
     * BusinessService requires positive identifiers.
     *
     * A negative business ID results in
     * IllegalArgumentException.
     *
     * GlobalExceptionHandler converts it to HTTP 400.
     */
    @Test
    void rejectsInvalidBusinessId() throws Exception {

        mvc.perform(get(BUSINESSES_URL + "/-1"))

            .andExpect(status().isBadRequest())

            .andExpect(
                jsonPath("$.error").exists()
            );
    }


    // ========================================================
    // HELPER — CREATE TEST USER
    // ========================================================

    /**
     * Insert a test user using the database's normal
     * generated-ID mechanism.
     *
     * UUID provides a unique email address.
     *
     * The placeholder password hash is only
     * test data, not a real credential.
     */
    private Long createTestUser(String fullName) {

        return jdbcTemplate.queryForObject(
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
            "business-api-"
                + UUID.randomUUID()
                + "@ledgerbase.local",
            "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
            fullName
        );
    }


    // ========================================================
    // HELPER — ENSURE DEVELOPMENT OWNER EXISTS
    // ========================================================

    /**
     * BusinessController temporarily uses owner ID 1.
     *
     * Unlike the normal generated-ID fixtures,
     * this test sometimes needs a specific ID.
     *
     * PostgreSQL's OVERRIDING SYSTEM VALUE permits
     * inserting a specified ID into an identity column.
     *
     * ON CONFLICT DO NOTHING handles the case
     * where user ID 1 already exists.
     *
     * This operation affects only the test database.
     *
     * We will remove this special fixture approach
     * when the controller uses authenticated identity.
     */
    private void ensureDevelopmentOwnerExists() {

        jdbcTemplate.update(
            """
            INSERT INTO users (
                id,
                email,
                password_hash,
                full_name
            )
            OVERRIDING SYSTEM VALUE
            VALUES (?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """,
            DEVELOPMENT_OWNER_ID,
            "development-api-"
                + UUID.randomUUID()
                + "@ledgerbase.local",
            "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
            "Business API Development Owner"
        );
    }


    // ========================================================
    // HELPER — CREATE TEST BUSINESS
    // ========================================================

    /**
     * Insert a temporary business owned by the
     * specified fixture user.
     *
     * PostgreSQL generates the business ID.
     *
     * Currency and fiscal-year month use
     * their database defaults.
     */
    private Long createTestBusiness(
        Long ownerId,
        String businessName
    ) {

        return jdbcTemplate.queryForObject(
            """
            INSERT INTO businesses (
                owner_id,
                name
            )
            VALUES (?, ?)
            RETURNING id
            """,
            Long.class,
            ownerId,
            businessName
        );
    }
}
