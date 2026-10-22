package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.model.Business;
import com.ledgerbase.backend.service.BusinessService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// ============================================================
// LEDGERBASE — BUSINESS CONTROLLER INTEGRATION TESTS
// ============================================================

/**
 * Verifies LedgerBase's authenticated Business REST API.
 *
 * API ENDPOINTS:
 *
 * GET  /api/v1/businesses
 * GET  /api/v1/businesses/{businessId}
 * POST /api/v1/businesses
 *
 * TEST COMPONENTS:
 *
 * - Real Spring MVC controllers.
 * - Real Spring Security HTTP filter chain.
 * - Real BusinessService.
 * - Real MyBatis business queries.
 * - Real PostgreSQL test database.
 *
 * AUTHENTICATION TESTING:
 *
 * JWT decoding is mocked only in this test class.
 *
 * This allows us to simulate authenticated users
 * without creating real login sessions for every test.
 *
 * The mocked decoder returns a Jwt with the requested
 * test user's ID as its subject.
 *
 * IMPORTANT:
 *
 * These tests do NOT validate JWT signatures,
 * refresh tokens, or PostgreSQL session revocation.
 *
 * Those authentication mechanisms must continue
 * to be tested separately using their real components.
 *
 * BUSINESS AUTHORIZATION:
 *
 * The controller and service use the JWT subject
 * to query business_memberships.
 *
 * Tests verify:
 *
 * - Authenticated business listing.
 * - Membership-based business retrieval.
 * - Isolation between different users.
 * - Business creation with OWNER membership.
 * - Input normalization and validation.
 * - Unauthenticated request rejection.
 *
 * DATABASE ISOLATION:
 *
 * Every test creates its own temporary users and
 * businesses inside a rollback-only test transaction.
 *
 * Tests do not rely on development owner ID 1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BusinessControllerIntegrationTest {

    // ========================================================
    // API CONFIGURATION
    // ========================================================

    private static final String BUSINESS_URL =
        "/api/v1/businesses";

    /**
     * Test-only Bearer token prefix.
     *
     * These strings are not signed JWTs.
     *
     * The test JwtDecoder maps each token to a
     * Spring Security Jwt principal.
     */
    private static final String TEST_TOKEN_PREFIX =
        "ledgerbase-test-user-";


    // ========================================================
    // SPRING-MANAGED DEPENDENCIES
    // ========================================================

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessService businessService;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    // ========================================================
    // MOCK JWT DECODER
    // ========================================================

    /**
     * Replace the application JwtDecoder only for
     * this integration test's Spring context.
     *
     * Spring Security still requires a Bearer token
     * and creates the authenticated principal.
     *
     * Production JWT configuration is not modified.
     *
     * This also avoids requiring the separate
     * spring-security-test Maven dependency.
     */
    @MockitoBean
    private JwtDecoder jwtDecoder;


    // ========================================================
    // PASSWORD ENCODER FOR TEST USERS
    // ========================================================

    /**
     * Temporary users receive valid BCrypt hashes.
     *
     * Their passwords are never used for login
     * in this particular controller test.
     */
    private static final BCryptPasswordEncoder
        TEST_PASSWORD_ENCODER =
            new BCryptPasswordEncoder();


    // ========================================================
    // BEFORE EACH TEST
    // ========================================================

    /**
     * Configure the mocked JWT decoder.
     *
     * Example request header:
     *
     * Authorization: Bearer ledgerbase-test-user-42
     *
     * The decoder extracts subject "42".
     *
     * The actual user must still be created by
     * the corresponding test database fixture.
     *
     * Invalid test token formats are rejected.
     */
    @BeforeEach
    void configureJwtDecoder() {

        when(
            jwtDecoder.decode(anyString())
        ).thenAnswer(invocation -> {

            // ------------------------------------------------
            // READ SUPPLIED TEST TOKEN
            // ------------------------------------------------

            String token =
                invocation.getArgument(0);


            // ------------------------------------------------
            // VALIDATE TEST TOKEN PREFIX
            // ------------------------------------------------

            if (!token.startsWith(TEST_TOKEN_PREFIX)) {

                throw new BadJwtException(
                    "Invalid test authentication token"
                );
            }


            // ------------------------------------------------
            // EXTRACT USER ID FROM TOKEN
            // ------------------------------------------------

            String subject =
                token.substring(
                    TEST_TOKEN_PREFIX.length()
                );

            try {

                long userId =
                    Long.parseLong(subject);

                if (userId <= 0) {

                    throw new NumberFormatException(
                        "Invalid user ID"
                    );
                }

            } catch (NumberFormatException exception) {

                throw new BadJwtException(
                    "Invalid test user identity"
                );
            }


            // ------------------------------------------------
            // CONSTRUCT TEST JWT PRINCIPAL
            // ------------------------------------------------

            Instant now =
                Instant.now();

            return Jwt.withTokenValue(token)

                .header(
                    "alg",
                    "HS256"
                )

                .subject(subject)

                .issuedAt(now)

                .expiresAt(
                    now.plusSeconds(900)
                )

                .build();
        });
    }


    // ========================================================
    // HELPER — AUTHENTICATED REQUEST HEADER
    // ========================================================

    /**
     * Build a test-only Authorization header value.
     *
     * Example:
     *
     * userId = 12
     *
     * Returns:
     *
     * Bearer ledgerbase-test-user-12
     *
     * JwtDecoder converts this test token into
     * the authenticated user's JWT principal.
     *
     * @param userId authenticated test user's ID
     * @return Bearer authorization header value
     */
    private String bearerFor(
        Long userId
    ) {

        return "Bearer " +
            TEST_TOKEN_PREFIX +
            userId;
    }


    // ========================================================
    // HELPER — CREATE TEST USER
    // ========================================================

    /**
     * Insert a temporary PostgreSQL user.
     *
     * This test uses its own fixture rather than
     * assuming user ID 1 exists.
     *
     * UUID-based emails satisfy uniqueness constraints.
     *
     * The test transaction rolls back the INSERT
     * when the test finishes.
     *
     * @return generated user ID
     */
    private Long createTestUser() {

        // ----------------------------------------------------
        // GENERATE UNIQUE EMAIL
        // ----------------------------------------------------

        String email =
            "business-controller-" +
            UUID.randomUUID() +
            "@example.invalid";


        // ----------------------------------------------------
        // GENERATE VALID PASSWORD HASH
        // ----------------------------------------------------

        String passwordHash =
            TEST_PASSWORD_ENCODER.encode(
                "TemporaryBusinessTestPassword123!"
            );


        // ----------------------------------------------------
        // INSERT AND RETURN USER ID
        // ----------------------------------------------------

        Long userId =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO users (
                    email,
                    full_name,
                    password_hash
                )
                VALUES (?, ?, ?)
                RETURNING id
                """,
                Long.class,
                email,
                "Business Controller Test User",
                passwordHash
            );


        // ----------------------------------------------------
        // VERIFY USER WAS CREATED
        // ----------------------------------------------------

        assertNotNull(userId);

        return userId;
    }


    // ========================================================
    // HELPER — CREATE BUSINESS
    // ========================================================

    /**
     * Create a real business through BusinessService.
     *
     * The service must create:
     *
     * - businesses row.
     * - OWNER membership row.
     *
     * Both are part of the surrounding transaction.
     *
     * @param ownerId owner user ID
     * @return created business
     */
    private Business createTestBusiness(
        Long ownerId
    ) {

        return businessService.createBusiness(
            ownerId,
            "Controller Test " + UUID.randomUUID(),
            "USD",
            1
        );
    }


    // ========================================================
    // HELPER — ADD BUSINESS MEMBERSHIP
    // ========================================================

    /**
     * Add a non-owner membership for an existing business.
     *
     * This helper is used to test whether users
     * with roles such as VIEWER can retrieve
     * accessible business information.
     *
     * @param businessId existing business ID
     * @param userId additional member ID
     * @param role business membership role
     */
    private void addMembership(
        Long businessId,
        Long userId,
        String role
    ) {

        int insertedRows =
            jdbcTemplate.update(
                """
                INSERT INTO business_memberships (
                    business_id,
                    user_id,
                    role
                )
                VALUES (?, ?, ?)
                """,
                businessId,
                userId,
                role
            );

        assertEquals(1, insertedRows);
    }


    // ========================================================
    // HELPER — BUILD BUSINESS REQUEST JSON
    // ========================================================

    /**
     * Build a valid JSON request body.
     *
     * Generated test names contain only safe
     * characters and require no escaping.
     */
    private String businessRequestJson(
        String name,
        String currency,
        int fiscalYearStartMonth
    ) {

        return """
            {
              "name": "%s",
              "currency": "%s",
              "fiscalYearStartMonth": %d
            }
            """.formatted(
                name,
                currency,
                fiscalYearStartMonth
            );
    }


    // ========================================================
    // TEST — MEMBERSHIP-SCOPED BUSINESS LIST
    // ========================================================

    /**
     * GET /api/v1/businesses
     *
     * The authenticated user should see:
     *
     * - Businesses they own.
     * - Businesses shared through membership.
     *
     * They should not see unrelated businesses.
     */
    @Test
    void listsOnlyAuthenticatedUsersBusinesses()
            throws Exception {

        // ----------------------------------------------------
        // CREATE TWO USERS
        // ----------------------------------------------------

        Long userA =
            createTestUser();

        Long userB =
            createTestUser();


        // ----------------------------------------------------
        // CREATE THREE BUSINESSES
        // ----------------------------------------------------

        Business ownedBusiness =
            createTestBusiness(userA);

        Business sharedBusiness =
            createTestBusiness(userB);

        createTestBusiness(userB);


        // ----------------------------------------------------
        // SHARE ONE BUSINESS WITH USER A
        // ----------------------------------------------------

        addMembership(
            sharedBusiness.getId(),
            userA,
            "VIEWER"
        );


        // ----------------------------------------------------
        // REQUEST BUSINESS DIRECTORY AS USER A
        // ----------------------------------------------------

        mockMvc.perform(
            get(BUSINESS_URL)

                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearerFor(userA)
                )
        )

        // ----------------------------------------------------
        // EXPECT TWO ACCESSIBLE BUSINESSES
        // ----------------------------------------------------

        .andExpect(
            status().isOk()
        )

        .andExpect(
            jsonPath("$.length()").value(2)
        )

        .andExpect(
            jsonPath("$[0].id")
                .value(ownedBusiness.getId())
        )

        .andExpect(
            jsonPath("$[1].id")
                .value(sharedBusiness.getId())
        );
    }


    // ========================================================
    // TEST — RETRIEVE EXISTING BUSINESS
    // ========================================================

    /**
     * GET /api/v1/businesses/{businessId}
     *
     * An owner should retrieve their own business.
     */
    @Test
    void retrievesExistingBusiness()
            throws Exception {

        Long ownerId =
            createTestUser();

        Business business =
            createTestBusiness(ownerId);


        // ----------------------------------------------------
        // RETRIEVE AS AUTHENTICATED OWNER
        // ----------------------------------------------------

        mockMvc.perform(
            get(
                BUSINESS_URL + "/{businessId}",
                business.getId()
            )

            .header(
                HttpHeaders.AUTHORIZATION,
                bearerFor(ownerId)
            )
        )

        // ----------------------------------------------------
        // VERIFY RESPONSE
        // ----------------------------------------------------

        .andExpect(
            status().isOk()
        )

        .andExpect(
            jsonPath("$.id")
                .value(business.getId())
        )

        .andExpect(
            jsonPath("$.ownerId")
                .value(ownerId)
        )

        .andExpect(
            jsonPath("$.currency")
                .value("USD")
        );
    }


    // ========================================================
    // TEST — PREVENT CROSS-BUSINESS ACCESS
    // ========================================================

    /**
     * A valid JWT does not automatically grant
     * access to every business.
     *
     * User B must not retrieve User A's business
     * unless a membership is explicitly granted.
     *
     * The endpoint deliberately returns HTTP 404
     * to avoid disclosing other businesses.
     */
    @Test
    void doesNotExposeAnotherOwnersBusiness()
            throws Exception {

        Long ownerId =
            createTestUser();

        Long otherUserId =
            createTestUser();

        Business privateBusiness =
            createTestBusiness(ownerId);


        // ----------------------------------------------------
        // ATTEMPT ACCESS AS NONMEMBER
        // ----------------------------------------------------

        mockMvc.perform(
            get(
                BUSINESS_URL + "/{businessId}",
                privateBusiness.getId()
            )

            .header(
                HttpHeaders.AUTHORIZATION,
                bearerFor(otherUserId)
            )
        )

        // ----------------------------------------------------
        // EXPECT BUSINESS TO REMAIN INACCESSIBLE
        // ----------------------------------------------------

        .andExpect(
            status().isNotFound()
        );
    }


    // ========================================================
    // TEST — MISSING BUSINESS
    // ========================================================

    /**
     * A business ID that does not exist must
     * result in HTTP 404.
     */
    @Test
    void returnsNotFoundForMissingBusiness()
            throws Exception {

        Long userId =
            createTestUser();


        // ----------------------------------------------------
        // REQUEST NONEXISTENT BUSINESS
        // ----------------------------------------------------

        mockMvc.perform(
            get(
                BUSINESS_URL + "/{businessId}",
                Long.MAX_VALUE
            )

            .header(
                HttpHeaders.AUTHORIZATION,
                bearerFor(userId)
            )
        )

        .andExpect(
            status().isNotFound()
        );
    }


    // ========================================================
    // TEST — CREATE BUSINESS
    // ========================================================

    /**
     * POST /api/v1/businesses
     *
     * The authenticated user should become:
     *
     * - businesses.owner_id.
     * - business_memberships OWNER.
     *
     * The owner is derived from the JWT subject,
     * not an ownerId in the HTTP request body.
     */
    @Test
    void createsBusinessThroughApi()
            throws Exception {

        Long ownerId =
            createTestUser();

        String businessName =
            "New Business " + UUID.randomUUID();


        // ----------------------------------------------------
        // CREATE THROUGH REAL HTTP CONTROLLER
        // ----------------------------------------------------

        mockMvc.perform(
            post(BUSINESS_URL)

                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearerFor(ownerId)
                )

                .contentType(
                    MediaType.APPLICATION_JSON
                )

                .content(
                    businessRequestJson(
                        businessName,
                        "USD",
                        1
                    )
                )
        )

        // ----------------------------------------------------
        // VERIFY CREATED RESPONSE
        // ----------------------------------------------------

        .andExpect(
            status().isCreated()
        )

        .andExpect(
            jsonPath("$.id").isNumber()
        )

        .andExpect(
            jsonPath("$.ownerId")
                .value(ownerId)
        )

        .andExpect(
            jsonPath("$.name")
                .value(businessName)
        );


        // ----------------------------------------------------
        // VERIFY DATABASE BUSINESS OWNER
        // ----------------------------------------------------

        Long businessId =
            jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM businesses
                WHERE owner_id = ?
                  AND name = ?
                """,
                Long.class,
                ownerId,
                businessName
            );

        assertNotNull(businessId);


        // ----------------------------------------------------
        // VERIFY AUTOMATIC OWNER MEMBERSHIP
        // ----------------------------------------------------

        String role =
            jdbcTemplate.queryForObject(
                """
                SELECT role
                FROM business_memberships
                WHERE business_id = ?
                  AND user_id = ?
                """,
                String.class,
                businessId,
                ownerId
            );

        assertEquals(
            "OWNER",
            role
        );
    }


    // ========================================================
    // TEST — DEFAULT BUSINESS VALUES
    // ========================================================

    /**
     * BusinessService defaults:
     *
     * - currency -> USD
     * - fiscalYearStartMonth -> 1
     *
     * Omitting optional fields should still produce
     * a valid business.
     */
    @Test
    void appliesDefaultsThroughApi()
            throws Exception {

        Long ownerId =
            createTestUser();

        String businessName =
            "Defaults Test " + UUID.randomUUID();


        // ----------------------------------------------------
        // SUBMIT ONLY REQUIRED NAME
        // ----------------------------------------------------

        String requestBody = """
            {
              "name": "%s"
            }
            """.formatted(businessName);


        // ----------------------------------------------------
        // CREATE BUSINESS
        // ----------------------------------------------------

        mockMvc.perform(
            post(BUSINESS_URL)

                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearerFor(ownerId)
                )

                .contentType(
                    MediaType.APPLICATION_JSON
                )

                .content(requestBody)
        )

        // ----------------------------------------------------
        // VERIFY DEFAULT VALUES
        // ----------------------------------------------------

        .andExpect(
            status().isCreated()
        )

        .andExpect(
            jsonPath("$.currency")
                .value("USD")
        )

        .andExpect(
            jsonPath("$.fiscalYearStartMonth")
                .value(1)
        );
    }


    // ========================================================
    // TEST — NORMALIZE BUSINESS INPUT
    // ========================================================

    /**
     * BusinessService normalizes:
     *
     * - Leading and trailing name whitespace.
     * - Lowercase currency letters.
     */
    @Test
    void normalizesBusinessInputThroughApi()
            throws Exception {

        Long ownerId =
            createTestUser();

        String expectedName =
            "Normalized " + UUID.randomUUID();


        // ----------------------------------------------------
        // SUBMIT UNNORMALIZED INPUT
        // ----------------------------------------------------

        mockMvc.perform(
            post(BUSINESS_URL)

                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearerFor(ownerId)
                )

                .contentType(
                    MediaType.APPLICATION_JSON
                )

                .content(
                    businessRequestJson(
                        "  " + expectedName + "  ",
                        "usd",
                        4
                    )
                )
        )

        // ----------------------------------------------------
        // VERIFY NORMALIZED RESPONSE
        // ----------------------------------------------------

        .andExpect(
            status().isCreated()
        )

        .andExpect(
            jsonPath("$.name")
                .value(expectedName)
        )

        .andExpect(
            jsonPath("$.currency")
                .value("USD")
        )

        .andExpect(
            jsonPath("$.fiscalYearStartMonth")
                .value(4)
        );
    }


    // ========================================================
    // TEST — INVALID CURRENCY
    // ========================================================

    /**
     * A currency must contain exactly three letters.
     *
     * Invalid currency formats must return HTTP 400.
     */
    @Test
    void rejectsInvalidCurrencyThroughApi()
            throws Exception {

        Long ownerId =
            createTestUser();


        // ----------------------------------------------------
        // SUBMIT CURRENCY CONTAINING DIGIT
        // ----------------------------------------------------

        mockMvc.perform(
            post(BUSINESS_URL)

                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearerFor(ownerId)
                )

                .contentType(
                    MediaType.APPLICATION_JSON
                )

                .content(
                    businessRequestJson(
                        "Invalid Currency Test",
                        "US1",
                        1
                    )
                )
        )

        .andExpect(
            status().isBadRequest()
        );
    }


    // ========================================================
    // TEST — INVALID BUSINESS ID
    // ========================================================

    /**
     * Nonpositive business identifiers are invalid.
     *
     * The existing service validation rejects them
     * before querying PostgreSQL.
     */
    @Test
    void rejectsInvalidBusinessId()
            throws Exception {

        Long userId =
            createTestUser();


        // ----------------------------------------------------
        // REQUEST BUSINESS ID -1
        // ----------------------------------------------------

        mockMvc.perform(
            get(
                BUSINESS_URL + "/{businessId}",
                -1
            )

            .header(
                HttpHeaders.AUTHORIZATION,
                bearerFor(userId)
            )
        )

        .andExpect(
            status().isBadRequest()
        );
    }


    // ========================================================
    // TEST — BLANK BUSINESS NAME
    // ========================================================

    /**
     * Empty or whitespace-only business names
     * must not be accepted.
     */
    @Test
    void rejectsBlankBusinessName()
            throws Exception {

        Long ownerId =
            createTestUser();


        // ----------------------------------------------------
        // SUBMIT BLANK BUSINESS NAME
        // ----------------------------------------------------

        mockMvc.perform(
            post(BUSINESS_URL)

                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearerFor(ownerId)
                )

                .contentType(
                    MediaType.APPLICATION_JSON
                )

                .content(
                    businessRequestJson(
                        "   ",
                        "USD",
                        1
                    )
                )
        )

        .andExpect(
            status().isBadRequest()
        );
    }


    // ========================================================
    // TEST — INVALID FISCAL-YEAR START
    // ========================================================

    /**
     * Only month values 1 through 12 are allowed.
     */
    @Test
    void rejectsInvalidFiscalYearStartMonth()
            throws Exception {

        Long ownerId =
            createTestUser();


        // ----------------------------------------------------
        // SUBMIT MONTH 13
        // ----------------------------------------------------

        mockMvc.perform(
            post(BUSINESS_URL)

                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearerFor(ownerId)
                )

                .contentType(
                    MediaType.APPLICATION_JSON
                )

                .content(
                    businessRequestJson(
                        "Invalid Fiscal Month",
                        "USD",
                        13
                    )
                )
        )

        .andExpect(
            status().isBadRequest()
        );
    }


    // ========================================================
    // TEST — VIEWER CAN READ BUSINESS
    // ========================================================

    /**
     * Any valid business member may retrieve
     * their business information.
     *
     * A VIEWER does not need to be the
     * canonical business owner.
     *
     * Write restrictions for VIEWER will be
     * covered by later financial RBAC tests.
     */
    @Test
    void allowsViewerToRetrieveBusiness()
            throws Exception {

        Long ownerId =
            createTestUser();

        Long viewerId =
            createTestUser();

        Business business =
            createTestBusiness(ownerId);


        // ----------------------------------------------------
        // GRANT VIEWER MEMBERSHIP
        // ----------------------------------------------------

        addMembership(
            business.getId(),
            viewerId,
            "VIEWER"
        );


        // ----------------------------------------------------
        // RETRIEVE BUSINESS AS VIEWER
        // ----------------------------------------------------

        mockMvc.perform(
            get(
                BUSINESS_URL + "/{businessId}",
                business.getId()
            )

            .header(
                HttpHeaders.AUTHORIZATION,
                bearerFor(viewerId)
            )
        )

        // ----------------------------------------------------
        // VERIFY VIEW ACCESS
        // ----------------------------------------------------

        .andExpect(
            status().isOk()
        )

        .andExpect(
            jsonPath("$.id")
                .value(business.getId())
        )

        .andExpect(
            jsonPath("$.ownerId")
                .value(ownerId)
        );
    }


    // ========================================================
    // TEST — PUBLIC ACCESS MUST BE REJECTED
    // ========================================================

    /**
     * Business information must not be visible
     * to anonymous requests.
     *
     * No Authorization header is supplied.
     */
    @Test
    void rejectsUnauthenticatedBusinessListing()
            throws Exception {

        mockMvc.perform(
            get(BUSINESS_URL)
        )

        .andExpect(
            status().isUnauthorized()
        );
    }


    // ========================================================
    // TEST — ANONYMOUS BUSINESS CREATION
    // ========================================================

    /**
     * Anonymous clients must not create businesses.
     *
     * Security should reject the HTTP request
     * before calling the business service.
     */
    @Test
    void rejectsUnauthenticatedBusinessCreation()
            throws Exception {

        mockMvc.perform(
            post(BUSINESS_URL)

                .contentType(
                    MediaType.APPLICATION_JSON
                )

                .content(
                    businessRequestJson(
                        "Unauthorized Business",
                        "USD",
                        1
                    )
                )
        )

        .andExpect(
            status().isUnauthorized()
        );
    }


    // ========================================================
    // TEST — INVALID BEARER TOKEN
    // ========================================================

    /**
     * A malformed authentication token must not
     * reach protected business endpoints.
     *
     * The mocked decoder rejects any token
     * without our test-specific prefix.
     *
     * This checks Spring Security's response
     * to a decoder rejection, not cryptographic
     * signature verification.
     */
    @Test
    void rejectsInvalidBearerToken()
            throws Exception {

        mockMvc.perform(
            get(BUSINESS_URL)

                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer invalid-test-token"
                )
        )

        .andExpect(
            status().isUnauthorized()
        );
    }
}
