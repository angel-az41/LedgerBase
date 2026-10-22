package com.ledgerbase.backend.config;

import com.ledgerbase.backend.security.BusinessAuthorizationManager;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.http.HttpMethod;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;

import org.springframework.security.web.SecurityFilterChain;

// ============================================================
// LEDGERBASE — HTTP SECURITY CONFIGURATION
// ============================================================

/**
 * Configure HTTP authentication and business authorization.
 *
 * AUTHENTICATION:
 *
 * - Stateless Spring Security.
 * - Signed JWT Bearer access tokens.
 * - PostgreSQL-backed authentication sessions.
 * - HttpOnly refresh cookies.
 *
 * PUBLIC ENDPOINTS:
 *
 * - POST /api/v1/auth/login
 * - POST /api/v1/auth/refresh
 * - POST /api/v1/auth/logout
 * - GET  /api/v1/health/**
 *
 * BUSINESS DIRECTORY:
 *
 * GET /api/v1/businesses
 * POST /api/v1/businesses
 *
 * These require authentication.
 *
 * BusinessController obtains the current user's ID
 * from Spring Security, and BusinessService enforces
 * membership-scoped reads and business ownership.
 *
 * BUSINESS-SCOPED FINANCIAL ENDPOINTS:
 *
 * /api/v1/businesses/{businessId}/...
 *
 * BusinessAuthorizationManager verifies the user's
 * current PostgreSQL membership and role.
 *
 * Unauthenticated requests return HTTP 401.
 *
 * Authenticated users without sufficient membership
 * permissions receive HTTP 403.
 *
 * A trusted audit-actor identity and additional
 * endpoint-specific permissions must still be
 * enforced separately.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(
    type = ConditionalOnWebApplication.Type.SERVLET
)
public class SecurityConfig {

    // ========================================================
    // SECURITY FILTER CHAIN
    // ========================================================

    /**
     * Configure authentication and authorization.
     *
     * @param http Spring Security HTTP configuration
     * @param businessAuthorizationManager membership authorization
     * @return configured filter chain
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        BusinessAuthorizationManager businessAuthorizationManager
    ) throws Exception {

        http

            // =================================================
            // STATELESS SESSION MANAGEMENT
            // =================================================

            /**
             * Do not store authenticated identities
             * in servlet HTTP sessions.
             *
             * LedgerBase's PostgreSQL auth_sessions
             * table tracks refreshable login sessions.
             */
            .sessionManagement(session ->
                session.sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS
                )
            )


            // =================================================
            // DISABLE UNUSED AUTHENTICATION MECHANISMS
            // =================================================

            /**
             * LedgerBase authenticates through its
             * JSON-based /api/v1/auth endpoints.
             */
            .formLogin(
                AbstractHttpConfigurer::disable
            )

            .httpBasic(
                AbstractHttpConfigurer::disable
            )


            // =================================================
            // CSRF CONFIGURATION
            // =================================================

            /**
             * CSRF is currently disabled globally.
             *
             * Existing cookie-authenticated login,
             * refresh, and logout operations validate
             * trusted Origin headers.
             *
             * Financial API requests use Bearer JWTs.
             *
             * Review the full CSRF and CORS strategy
             * before production deployment.
             */
            .csrf(
                AbstractHttpConfigurer::disable
            )


            // =================================================
            // HTTP AUTHORIZATION RULES
            // =================================================

            .authorizeHttpRequests(authorize -> authorize

                // ---------------------------------------------
                // PUBLIC AUTHENTICATION ENDPOINTS
                // ---------------------------------------------

                /**
                 * These endpoints do not require
                 * an existing access JWT.
                 *
                 * Their authentication and cookie
                 * security checks remain in the
                 * existing authentication services.
                 */
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout"
                ).permitAll()


                // ---------------------------------------------
                // PUBLIC DATABASE HEALTH CHECKS
                // ---------------------------------------------

                /**
                 * Allow existing read-only health
                 * checks without authentication.
                 */
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/health",
                    "/api/v1/health/**"
                ).permitAll()


                // ---------------------------------------------
                // AUTHENTICATED USER IDENTITY
                // ---------------------------------------------

                /**
                 * A signed, active JWT is required
                 * to retrieve the current user.
                 */
                .requestMatchers(
                    "/api/v1/auth/me"
                ).authenticated()


                // ---------------------------------------------
                // INDIVIDUAL BUSINESS METADATA
                // ---------------------------------------------

                /**
                 * BusinessController already performs
                 * its own membership-scoped lookup.
                 *
                 * Preserve its existing behavior:
                 *
                 * - Accessible business: HTTP 200.
                 * - Missing or inaccessible: HTTP 404.
                 *
                 * Only the GET endpoint receives this
                 * specific authentication rule.
                 *
                 * Nested accounting APIs use the
                 * business authorization manager below.
                 */
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/businesses/{businessId}"
                ).authenticated()


                // ---------------------------------------------
                // BUSINESS-SCOPED FINANCIAL AUTHORIZATION
                // ---------------------------------------------

                /**
                 * Every nested business API request
                 * must pass the business-level RBAC check.
                 *
                 * This covers existing routes for:
                 *
                 * - Chart of Accounts.
                 * - Account balances.
                 * - Account import/export.
                 * - Financial transactions.
                 * - Transaction posting.
                 * - Fiscal years.
                 * - Accounting periods.
                 * - Trial Balance.
                 * - Income Statement.
                 * - Balance Sheet.
                 *
                 * BusinessAuthorizationManager receives:
                 *
                 * - The authenticated JWT principal.
                 * - businessId from the URL.
                 * - The HTTP method.
                 *
                 * It checks the user's current
                 * business_memberships record.
                 *
                 * IMPORTANT:
                 *
                 * This is not merely an authentication
                 * check. It enforces business membership
                 * and read/write role permissions.
                 */
                .requestMatchers(
                    "/api/v1/businesses/{businessId}/**"
                ).access(
                    businessAuthorizationManager
                )


                // ---------------------------------------------
                // AUTHENTICATED DEFAULT
                // ---------------------------------------------

                /**
                 * Preserve authentication for all
                 * other endpoints.
                 *
                 * Future financial routes outside the
                 * current business URL structure must
                 * receive explicit authorization rules.
                 */
                .anyRequest().authenticated()
            )


            // =================================================
            // JWT RESOURCE SERVER
            // =================================================

            /**
             * Spring Security extracts Bearer tokens
             * from Authorization headers.
             *
             * The application's JwtDecoder validates
             * signatures, claims, expiration, and
             * PostgreSQL-backed session status.
             *
             * BusinessAuthorizationManager then applies
             * the business-specific authorization policy.
             */
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(
                    Customizer.withDefaults()
                )
            );

        return http.build();
    }
}
