package com.ledgerbase.backend.integration;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// ============================================================
// LEDGERBASE — FINANCIAL API AUTHENTICATION TESTS
// ============================================================

/**
 * Verifies that financial APIs cannot be accessed
 * without an authenticated user.
 *
 * TEST SCOPE:
 *
 * These requests pass through the actual Spring
 * Security filter chain.
 *
 * No mocked authentication principal is supplied.
 *
 * Expected behavior:
 *
 * - Missing JWT: HTTP 401.
 * - Public database health check: HTTP 200.
 * - Protected user identity: HTTP 401.
 *
 * IMPORTANT:
 *
 * These tests establish authentication requirements.
 *
 * Business-level authorization and role-based
 * permissions are covered by separate tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FinancialApiAuthenticationIntegrationTest {

    // ========================================================
    // HTTP TEST CLIENT
    // ========================================================

    @Autowired
    private MockMvc mockMvc;


    // ========================================================
    // UNAUTHENTICATED BUSINESSES REQUEST
    // ========================================================

    /**
     * A visitor without a Bearer JWT must not
     * retrieve the business directory.
     */
    @Test
    void shouldRejectBusinessesWithoutAuthentication()
            throws Exception {

        mockMvc.perform(
            get("/api/v1/businesses")
        )

        .andExpect(
            status().isUnauthorized()
        );
    }


    // ========================================================
    // UNAUTHENTICATED ACCOUNTS REQUEST
    // ========================================================

    /**
     * A visitor without a Bearer JWT must not
     * access a business's Chart of Accounts.
     */
    @Test
    void shouldRejectAccountsWithoutAuthentication()
            throws Exception {

        mockMvc.perform(
            get("/api/v1/businesses/1/accounts")
        )

        .andExpect(
            status().isUnauthorized()
        );
    }


    // ========================================================
    // UNAUTHENTICATED TRANSACTIONS REQUEST
    // ========================================================

    /**
     * Financial transactions must require
     * authentication even for read-only requests.
     */
    @Test
    void shouldRejectTransactionsWithoutAuthentication()
            throws Exception {

        mockMvc.perform(
            get("/api/v1/businesses/1/transactions")
        )

        .andExpect(
            status().isUnauthorized()
        );
    }


    // ========================================================
    // UNAUTHENTICATED FINANCIAL MUTATION
    // ========================================================

    /**
     * Authentication must be checked before
     * a financial write request reaches
     * business logic or request validation.
     */
    @Test
    void shouldRejectAccountCreationWithoutAuthentication()
            throws Exception {

        mockMvc.perform(
            post("/api/v1/businesses/1/accounts")
                .contentType("application/json")
                .content("{}")
        )

        .andExpect(
            status().isUnauthorized()
        );
    }


    // ========================================================
    // UNAUTHENTICATED CURRENT USER REQUEST
    // ========================================================

    /**
     * The current-user endpoint must remain
     * protected by Spring Security.
     */
    @Test
    void shouldRejectCurrentUserWithoutAuthentication()
            throws Exception {

        mockMvc.perform(
            get("/api/v1/auth/me")
        )

        .andExpect(
            status().isUnauthorized()
        );
    }


    // ========================================================
    // PUBLIC DATABASE HEALTH CHECK
    // ========================================================

    /**
     * Operational health checks must remain
     * accessible without a Bearer JWT.
     *
     * This test assumes the test PostgreSQL
     * database is running and healthy.
     */
    @Test
    void shouldAllowPublicDatabaseHealthCheck()
            throws Exception {

        mockMvc.perform(
            get("/api/v1/health/database")
        )

        .andExpect(
            status().isOk()
        );
    }
}
