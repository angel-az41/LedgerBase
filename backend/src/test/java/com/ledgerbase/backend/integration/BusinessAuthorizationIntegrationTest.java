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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// ============================================================
// LEDGERBASE — BUSINESS AUTHORIZATION INTEGRATION TESTS
// ============================================================

/**
 * Verify business-scoped access through Spring Security.
 *
 * REAL COMPONENTS:
 *
 * - HTTP security filter chain.
 * - BusinessAuthorizationManager.
 * - BusinessMembershipMapper.
 * - PostgreSQL membership records.
 * - Account and Transaction REST controllers.
 *
 * TEST-ONLY MOCK:
 *
 * JwtDecoder supplies authenticated test identities.
 *
 * No changes are made to production authentication.
 *
 * All test-created database rows are rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BusinessAuthorizationIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BusinessService businessService;

    @MockitoBean
    private JwtDecoder jwtDecoder;


    // ========================================================
    // TEST AUTHENTICATION CONFIGURATION
    // ========================================================

    /**
     * Test tokens contain a positive user ID.
     *
     * Only the decoder is mocked.
     *
     * Spring Security still authenticates requests
     * and invokes the real authorization manager.
     */
    @BeforeEach
    void configureJwtDecoder() {

        when(jwtDecoder.decode(anyString()))
            .thenAnswer(invocation -> {

                String token =
                    invocation.getArgument(0);

                String prefix =
                    "rbac-test-user-";

                if (!token.startsWith(prefix)) {

                    throw new org.springframework.security.oauth2.jwt.BadJwtException(
                        "Invalid test token"
                    );
                }

                String subject =
                    token.substring(prefix.length());

                long userId;

                try {

                    userId =
                        Long.parseLong(subject);

                    if (userId <= 0) {
                        throw new NumberFormatException();
                    }

                } catch (NumberFormatException exception) {

                    throw new org.springframework.security.oauth2.jwt.BadJwtException(
                        "Invalid test user"
                    );
                }

                Instant issuedAt =
                    Instant.now();

                return Jwt.withTokenValue(token)
                    .header("alg", "HS256")
                    .subject(subject)
                    .issuedAt(issuedAt)
                    .expiresAt(
                        issuedAt.plusSeconds(900)
                    )
                    .build();
            });
    }


    // ========================================================
    // CREATE TEMPORARY USER
    // ========================================================

    /**
     * Insert a user with a unique email.
     *
     * No existing test fixtures are required.
     */
    private Long createUser() {

        String email =
            "rbac-" +
            UUID.randomUUID() +
            "@example.invalid";

        String passwordHash =
            new BCryptPasswordEncoder().encode(
                "TemporaryRbacTestPassword123!"
            );

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
                "RBAC Test User",
                passwordHash
            );

        assertNotNull(userId);

        return userId;
    }


    // ========================================================
    // CREATE BUSINESS WITH OWNER MEMBERSHIP
    // ========================================================

    private Business createBusiness(
        Long ownerId
    ) {

        return businessService.createBusiness(
            ownerId,
            "RBAC Test " + UUID.randomUUID(),
            "USD",
            1
        );
    }


    // ========================================================
    // GRANT ADDITIONAL BUSINESS ROLE
    // ========================================================

    private void grantRole(
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
    // TEST AUTHORIZATION HEADER
    // ========================================================

    private String bearerFor(
        Long userId
    ) {

        return "Bearer rbac-test-user-" + userId;
    }


    // ========================================================
    // UNAUTHENTICATED ACCOUNT ACCESS
    // ========================================================

    @Test
    void anonymousUserCannotReadAccounts()
            throws Exception {

        mockMvc.perform(
            get("/api/v1/businesses/1/accounts")
        )
        .andExpect(status().isUnauthorized());
    }


    // ========================================================
    // OWNER CAN READ ACCOUNTS
    // ========================================================

    @Test
    void ownerCanReadAccounts()
            throws Exception {

        Long ownerId =
            createUser();

        Business business =
            createBusiness(ownerId);

        mockMvc.perform(
            get(
                "/api/v1/businesses/{businessId}/accounts",
                business.getId()
            )
            .header(
                HttpHeaders.AUTHORIZATION,
                bearerFor(ownerId)
            )
        )
        .andExpect(status().isOk());
    }


    // ========================================================
    // NONMEMBER CANNOT READ ACCOUNTS
    // ========================================================

    @Test
    void nonMemberCannotReadAccounts()
            throws Exception {

        Long ownerId =
            createUser();

        Long nonMemberId =
            createUser();

        Business business =
            createBusiness(ownerId);

        mockMvc.perform(
            get(
                "/api/v1/businesses/{businessId}/accounts",
                business.getId()
            )
            .header(
                HttpHeaders.AUTHORIZATION,
                bearerFor(nonMemberId)
            )
        )
        .andExpect(status().isForbidden());
    }


    // ========================================================
    // VIEWER CAN READ TRANSACTIONS
    // ========================================================

    @Test
    void viewerCanReadTransactions()
            throws Exception {

        Long ownerId =
            createUser();

        Long viewerId =
            createUser();

        Business business =
            createBusiness(ownerId);

        grantRole(
            business.getId(),
            viewerId,
            "VIEWER"
        );

        mockMvc.perform(
            get(
                "/api/v1/businesses/{businessId}/transactions",
                business.getId()
            )
            .header(
                HttpHeaders.AUTHORIZATION,
                bearerFor(viewerId)
            )
        )
        .andExpect(status().isOk());
    }


    // ========================================================
    // VIEWER CANNOT CREATE ACCOUNTS
    // ========================================================

    @Test
    void viewerCannotCreateAccounts()
            throws Exception {

        Long ownerId =
            createUser();

        Long viewerId =
            createUser();

        Business business =
            createBusiness(ownerId);

        grantRole(
            business.getId(),
            viewerId,
            "VIEWER"
        );

        mockMvc.perform(
            post(
                "/api/v1/businesses/{businessId}/accounts",
                business.getId()
            )
            .header(
                HttpHeaders.AUTHORIZATION,
                bearerFor(viewerId)
            )
            .contentType(
                MediaType.APPLICATION_JSON
            )
            .content("{}")
        )
        .andExpect(status().isForbidden());
    }


    // ========================================================
    // ACCOUNTANT CAN REACH WRITE CONTROLLER
    // ========================================================

    /**
     * Use malformed JSON deliberately.
     *
     * Authorization should succeed for ACCOUNTANT,
     * allowing MVC to reject the invalid request
     * with HTTP 400 rather than HTTP 403.
     *
     * No account should be created.
     */
    @Test
    void accountantHasWritePermission()
            throws Exception {

        Long ownerId =
            createUser();

        Long accountantId =
            createUser();

        Business business =
            createBusiness(ownerId);

        grantRole(
            business.getId(),
            accountantId,
            "ACCOUNTANT"
        );

        mockMvc.perform(
            post(
                "/api/v1/businesses/{businessId}/accounts",
                business.getId()
            )
            .header(
                HttpHeaders.AUTHORIZATION,
                bearerFor(accountantId)
            )
            .contentType(
                MediaType.APPLICATION_JSON
            )
            .content("{")
        )
        .andExpect(status().isBadRequest());
    }


    // ========================================================
    // NONMEMBER CANNOT CREATE TRANSACTIONS
    // ========================================================

    @Test
    void nonMemberCannotCreateTransactions()
            throws Exception {

        Long ownerId =
            createUser();

        Long nonMemberId =
            createUser();

        Business business =
            createBusiness(ownerId);

        mockMvc.perform(
            post(
                "/api/v1/businesses/{businessId}/transactions",
                business.getId()
            )
            .header(
                HttpHeaders.AUTHORIZATION,
                bearerFor(nonMemberId)
            )
            .contentType(
                MediaType.APPLICATION_JSON
            )
            .content("{}")
        )
        .andExpect(status().isForbidden());
    }


    // ========================================================
    // NONMEMBER CANNOT READ FINANCIAL REPORT
    // ========================================================

    /**
     * This verifies that authorization applies
     * beyond the two controllers we inspected.
     */
    @Test
    void nonMemberCannotAccessReport()
            throws Exception {

        Long ownerId =
            createUser();

        Long nonMemberId =
            createUser();

        Business business =
            createBusiness(ownerId);

        mockMvc.perform(
            get(
                "/api/v1/businesses/{businessId}/reports/trial-balance",
                business.getId()
            )
            .header(
                HttpHeaders.AUTHORIZATION,
                bearerFor(nonMemberId)
            )
        )
        .andExpect(status().isForbidden());
    }


    // ========================================================
    // INDIVIDUAL BUSINESS REMAINS NOT FOUND
    // ========================================================

    /**
     * The standalone business metadata endpoint
     * preserves its existing membership-scoped
     * HTTP 404 behavior.
     */
    @Test
    void nonMemberBusinessLookupRemainsNotFound()
            throws Exception {

        Long ownerId =
            createUser();

        Long nonMemberId =
            createUser();

        Business business =
            createBusiness(ownerId);

        mockMvc.perform(
            get(
                "/api/v1/businesses/{businessId}",
                business.getId()
            )
            .header(
                HttpHeaders.AUTHORIZATION,
                bearerFor(nonMemberId)
            )
        )
        .andExpect(status().isNotFound());
    }
}
