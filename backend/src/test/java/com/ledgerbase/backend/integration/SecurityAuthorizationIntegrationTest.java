package com.ledgerbase.backend.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.mock.web.MockMultipartFile;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// ============================================================
// LEDGERBASE — SECURITY AUTHORIZATION INTEGRATION TESTS
// ============================================================

/**
 * Verify that LedgerBase protects financial endpoints
 * using JWT authentication and business-scoped RBAC.
 *
 * TEST COVERAGE:
 *
 * - Unauthenticated requests are rejected.
 * - Authenticated nonmembers are forbidden.
 * - VIEWER can read financial information.
 * - VIEWER cannot create financial records.
 * - VIEWER cannot import accounting data.
 * - OWNER can read authorized business information.
 * - Unknown API routes are denied by default.
 * - Public database health checks remain accessible.
 * - Authenticated business-directory access works.
 *
 * TEST ENVIRONMENT:
 *
 * - Real Spring Boot application context.
 * - Real Spring Security filter chain.
 * - Real BusinessAuthorizationManager.
 * - Real PostgreSQL test database.
 * - Mocked JwtDecoder for predictable identities.
 *
 * Each test runs in a transaction that is
 * rolled back after completion.
 *
 * No production or development financial data
 * is modified by these tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SecurityAuthorizationIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Mock JWT decoding without bypassing the
     * real Spring Security filter chain.
     */
    @MockitoBean
    private JwtDecoder jwtDecoder;


    // ========================================================
    // TEST IDENTIFIERS
    // ========================================================

    private Long ownerUserId;

    private Long viewerUserId;

    private Long nonmemberUserId;

    private Long testBusinessId;


    // ========================================================
    // SETUP
    // ========================================================

    /**
     * Create three test users:
     *
     * OWNER:
     *   Full business read/write permission.
     *
     * VIEWER:
     *   Business read permission only.
     *
     * NONMEMBER:
     *   Authenticated user with no business access.
     *
     * All records are created in ledgerbase_test.
     */
    @BeforeEach
    void setUp() {

        // ----------------------------------------------------
        // CREATE TEST USERS
        // ----------------------------------------------------

        ownerUserId = createTestUser(
            "Security Test Owner"
        );

        viewerUserId = createTestUser(
            "Security Test Viewer"
        );

        nonmemberUserId = createTestUser(
            "Security Test Nonmember"
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
            ownerUserId,
            "Security Authorization Test Business",
            "USD"
        );


        // ----------------------------------------------------
        // CREATE OWNER MEMBERSHIP
        // ----------------------------------------------------

        grantMembership(
            ownerUserId,
            "OWNER"
        );


        // ----------------------------------------------------
        // CREATE VIEWER MEMBERSHIP
        // ----------------------------------------------------

        grantMembership(
            viewerUserId,
            "VIEWER"
        );


        // ----------------------------------------------------
        // CONFIGURE TEST JWT DECODING
        // ----------------------------------------------------

        /**
         * Bearer tokens use a test-only format:
         *
         * security-test-user-{userId}
         *
         * The decoded JWT subject is the numeric
         * PostgreSQL user ID.
         *
         * Spring Security still performs its
         * normal authentication and authorization
         * processing.
         */
        when(jwtDecoder.decode(anyString()))
            .thenAnswer(invocation -> {

                String token =
                    invocation.getArgument(0);

                String prefix =
                    "security-test-user-";

                if (!token.startsWith(prefix)) {

                    throw new BadJwtException(
                        "Invalid security test token"
                    );
                }

                String subject =
                    token.substring(prefix.length());

                try {

                    long userId =
                        Long.parseLong(subject);

                    if (userId <= 0) {

                        throw new NumberFormatException(
                            "User ID must be positive"
                        );
                    }

                } catch (NumberFormatException exception) {

                    throw new BadJwtException(
                        "Invalid security test user ID"
                    );
                }

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
    // HELPER — CREATE TEST USER
    // ========================================================

    /**
     * Insert one isolated test user.
     *
     * A unique email prevents conflicts with
     * other integration tests.
     */
    private Long createTestUser(
        String fullName
    ) {

        String email =
            "security-test-"
            + UUID.randomUUID()
            + "@ledgerbase.local";

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
            email,
            "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
            fullName
        );
    }


    // ========================================================
    // HELPER — GRANT BUSINESS MEMBERSHIP
    // ========================================================

    /**
     * Assign a business role to a test user.
     *
     * This writes real membership records so the
     * BusinessAuthorizationManager can query
     * PostgreSQL normally.
     */
    private void grantMembership(
        Long userId,
        String role
    ) {

        jdbcTemplate.update(
            """
            INSERT INTO business_memberships (
                business_id,
                user_id,
                role
            )
            VALUES (?, ?, ?)
            """,
            testBusinessId,
            userId,
            role
        );
    }


    // ========================================================
    // HELPER — TEST BEARER TOKEN
    // ========================================================

    /**
     * Construct the Authorization header for
     * a specific authenticated test user.
     */
    private String bearerFor(
        Long userId
    ) {

        return "Bearer security-test-user-" + userId;
    }


    // ========================================================
    // HELPER — ACCOUNTS URL
    // ========================================================

    private String accountsUrl() {

        return "/api/v1/businesses/"
            + testBusinessId
            + "/accounts";
    }


    // ========================================================
    // UNAUTHENTICATED BUSINESS REQUEST
    // ========================================================

    /**
     * Financial data cannot be accessed without
     * an authenticated JWT.
     *
     * Expected: HTTP 401 Unauthorized.
     */
    @Test
    void rejectsUnauthenticatedAccountAccess()
            throws Exception {

        mvc.perform(
                get(accountsUrl())
            )

            .andExpect(status().isUnauthorized());
    }


    // ========================================================
    // AUTHENTICATED NONMEMBER
    // ========================================================

    /**
     * Authentication alone does not provide
     * access to a business.
     *
     * The nonmember has a valid test JWT but
     * no business_memberships record.
     *
     * Expected: HTTP 403 Forbidden.
     */
    @Test
    void rejectsNonmemberAccountAccess()
            throws Exception {

        mvc.perform(
                get(accountsUrl())
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        bearerFor(nonmemberUserId)
                    )
            )

            .andExpect(status().isForbidden());
    }


    // ========================================================
    // VIEWER READ ACCESS
    // ========================================================

    /**
     * VIEWER is a recognized business role.
     *
     * The role permits GET and HEAD operations
     * on business-scoped resources.
     *
     * Expected: HTTP 200 OK.
     */
    @Test
    void allowsViewerToReadAccounts()
            throws Exception {

        mvc.perform(
                get(accountsUrl())
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        bearerFor(viewerUserId)
                    )
            )

            .andExpect(status().isOk());
    }


    // ========================================================
    // VIEWER CANNOT CREATE ACCOUNT
    // ========================================================

    /**
     * Creating an accounting resource requires
     * business write permission.
     *
     * VIEWER does not have write permission.
     *
     * Authorization must reject the request
     * before account validation or creation.
     *
     * Expected: HTTP 403 Forbidden.
     */
    @Test
    void rejectsViewerAccountCreation()
            throws Exception {

        mvc.perform(
                post(accountsUrl())
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        bearerFor(viewerUserId)
                    )
                    .contentType(
                        MediaType.APPLICATION_JSON
                    )
                    .content("{}")
            )

            .andExpect(status().isForbidden());
    }


    // ========================================================
    // VIEWER CANNOT IMPORT ACCOUNTS
    // ========================================================

    /**
     * Account imports use a multipart POST request.
     *
     * VIEWER must not be able to invoke even the
     * import-preview operation because the current
     * RBAC policy requires write permission for POST.
     *
     * This test ensures multipart endpoints are
     * protected by the same security rules.
     */
    @Test
    void rejectsViewerAccountImport()
            throws Exception {

        // ----------------------------------------------------
        // CREATE TEST-ONLY CSV FILE
        // ----------------------------------------------------

        MockMultipartFile file =
            new MockMultipartFile(
                "file",
                "test-accounts.csv",
                "text/csv",
                """
                code,name,account_type
                1000,Cash,ASSET
                """.getBytes(StandardCharsets.UTF_8)
            );


        // ----------------------------------------------------
        // REQUEST ACCOUNT IMPORT AS VIEWER
        // ----------------------------------------------------

        mvc.perform(
                multipart(
                    accountsUrl() + "/import/preview"
                )
                    .file(file)
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        bearerFor(viewerUserId)
                    )
            )

            .andExpect(status().isForbidden());
    }


    // ========================================================
    // OWNER READ ACCESS
    // ========================================================

    /**
     * OWNER has business read and write permission.
     *
     * The account list should be available even
     * when no accounts have yet been created.
     *
     * Expected: HTTP 200 OK.
     */
    @Test
    void allowsOwnerToReadAccounts()
            throws Exception {

        mvc.perform(
                get(accountsUrl())
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        bearerFor(ownerUserId)
                    )
            )

            .andExpect(status().isOk());
    }


    // ========================================================
    // DENY UNKNOWN API ROUTES
    // ========================================================

    /**
     * Verify SecurityConfig's default rule:
     *
     * .anyRequest().denyAll()
     *
     * Even a fully authenticated OWNER must not
     * gain access to an unmatched API endpoint.
     *
     * This URL is deliberately outside every
     * explicitly authorized endpoint group.
     *
     * Expected: HTTP 403 Forbidden.
     */
    @Test
    void deniesUnknownApiRoutesByDefault()
            throws Exception {

        mvc.perform(
                get("/api/v1/internal/security-test")
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        bearerFor(ownerUserId)
                    )
            )

            .andExpect(status().isForbidden());
    }


    // ========================================================
    // PUBLIC DATABASE HEALTH CHECK
    // ========================================================

    /**
     * The database health endpoint is explicitly
     * accessible without authentication.
     *
     * This ensures the deny-by-default change
     * does not break application health monitoring.
     *
     * Expected: HTTP 200 OK when the test
     * database is healthy.
     */
    @Test
    void allowsPublicDatabaseHealthCheck()
            throws Exception {

        mvc.perform(
                get("/api/v1/health/database")
            )

            .andExpect(status().isOk());
    }


    // ========================================================
    // AUTHENTICATED BUSINESS DIRECTORY
    // ========================================================

    /**
     * GET /api/v1/businesses requires authentication.
     *
     * SecurityConfig explicitly permits authenticated
     * users to access their business directory.
     *
     * BusinessController scopes the returned
     * businesses to the authenticated user.
     *
     * Expected: HTTP 200 OK.
     */
    @Test
    void allowsAuthenticatedBusinessDirectory()
            throws Exception {

        mvc.perform(
                get("/api/v1/businesses")
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        bearerFor(ownerUserId)
                    )
            )

            .andExpect(status().isOk());
    }
}
