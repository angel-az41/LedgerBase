package com.ledgerbase.backend.config;

import com.ledgerbase.backend.security.BusinessAuthorizationManager;

import jakarta.servlet.DispatcherType;

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
 * Configure HTTP authentication and authorization.
 *
 * AUTHENTICATION:
 *
 * - Stateless Spring Security filter chain.
 * - Signed JWT Bearer access tokens.
 * - PostgreSQL-backed authentication sessions.
 * - HttpOnly refresh cookies.
 *
 * AUTHORIZATION:
 *
 * - Public authentication endpoints.
 * - Public application database health checks.
 * - Authenticated current-user and business directory APIs.
 * - PostgreSQL-backed business membership and RBAC.
 * - Explicit deny-by-default fallback.
 *
 * SECURITY DESIGN:
 *
 * Requests must match an explicit authorization rule.
 *
 * Unknown or future endpoints cannot become accessible
 * merely because the caller has a valid JWT.
 *
 * The BusinessAuthorizationManager independently checks
 * business memberships on every business-scoped request.
 *
 * Accounting audit identities are established by
 * controllers using the verified JWT subject.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(
    type = ConditionalOnWebApplication.Type.SERVLET
)
public class SecurityConfig {

    // ========================================================
    // SECURITY FILTER CHAIN
    // ========================================================

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
             * Do not store authenticated identities in
             * the servlet HTTP session.
             *
             * LedgerBase manages login sessions separately
             * through the PostgreSQL auth_sessions table.
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
             * LedgerBase uses JSON-based authentication
             * endpoints and JWT Bearer tokens.
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
             * Financial API requests use Bearer tokens
             * rather than cookie-based access tokens.
             *
             * Login, refresh, and logout enforce the
             * existing Origin checks where relevant.
             *
             * Retain the current implementation while
             * reviewing deployment-specific CSRF and
             * CORS requirements separately.
             */
            .csrf(
                AbstractHttpConfigurer::disable
            )


            // =================================================
            // HTTP AUTHORIZATION
            // =================================================

            .authorizeHttpRequests(authorize -> authorize

                // ---------------------------------------------
                // FRAMEWORK ERROR DISPATCHES
                // ---------------------------------------------

                /**
                 * Permit Spring's internal ERROR dispatches
                 * so legitimate application error responses
                 * can be rendered.
                 *
                 * This does not grant ordinary HTTP requests
                 * access to unmatched application endpoints.
                 */
                .dispatcherTypeMatchers(
                    DispatcherType.ERROR
                ).permitAll()


                // ---------------------------------------------
                // PUBLIC AUTHENTICATION ENDPOINTS
                // ---------------------------------------------

                /**
                 * These endpoints must be reachable without
                 * an existing access JWT.
                 *
                 * Their own controllers and services perform
                 * the relevant authentication and cookie
                 * security checks.
                 */
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout"
                ).permitAll()


                // ---------------------------------------------
                // PUBLIC APPLICATION HEALTH CHECKS
                // ---------------------------------------------

                /**
                 * Preserve the existing application health
                 * endpoints used to verify backend and
                 * database connectivity.
                 */
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/health",
                    "/api/v1/health/**"
                ).permitAll()


                // ---------------------------------------------
                // AUTHENTICATED ACTUATOR ENDPOINTS
                // ---------------------------------------------

                /**
                 * Preserve authenticated access to health
                 * and info without exposing other Actuator
                 * endpoints through a broad wildcard.
                 *
                 * This is separate from the public
                 * /api/v1/health endpoints.
                 */
                .requestMatchers(
                    HttpMethod.GET,
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info"
                ).authenticated()


                // ---------------------------------------------
                // AUTHENTICATED CURRENT USER
                // ---------------------------------------------

                /**
                 * The current-user endpoint requires a
                 * valid, active JWT access token.
                 */
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/auth/me"
                ).authenticated()


                // ---------------------------------------------
                // BUSINESS DIRECTORY — LIST
                // ---------------------------------------------

                /**
                 * Authenticated users may retrieve their
                 * accessible businesses.
                 *
                 * BusinessController and BusinessService
                 * scope results to the current user.
                 *
                 * The browser cannot choose another
                 * user's identity for this query.
                 */
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/businesses"
                ).authenticated()


                // ---------------------------------------------
                // BUSINESS DIRECTORY — CREATE
                // ---------------------------------------------

                /**
                 * Authenticated users may create businesses.
                 *
                 * BusinessController obtains the owner
                 * identity from the verified JWT.
                 *
                 * The existing service provisions business
                 * ownership and related database records.
                 */
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/businesses"
                ).authenticated()


                // ---------------------------------------------
                // INDIVIDUAL BUSINESS METADATA
                // ---------------------------------------------

                /**
                 * Preserve the controller's existing
                 * membership-scoped business lookup.
                 *
                 * Accessible business: HTTP 200.
                 * Missing or inaccessible: HTTP 404.
                 *
                 * Nested financial operations are covered
                 * by the authorization manager below.
                 */
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/businesses/{businessId}"
                ).authenticated()


                // ---------------------------------------------
                // BUSINESS-SCOPED FINANCIAL ENDPOINTS
                // ---------------------------------------------

                /**
                 * All nested business APIs require
                 * membership and role authorization.
                 *
                 * This includes:
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
                 * READ:
                 *
                 * OWNER, ADMIN, ACCOUNTANT, VIEWER.
                 *
                 * WRITE:
                 *
                 * OWNER, ADMIN, ACCOUNTANT.
                 *
                 * VIEWER cannot perform mutations.
                 *
                 * Every decision uses the authenticated
                 * JWT subject and current PostgreSQL
                 * business membership.
                 */
                .requestMatchers(
                    "/api/v1/businesses/{businessId}/**"
                ).access(
                    businessAuthorizationManager
                )


                // ---------------------------------------------
                // EXPLICIT DENY-BY-DEFAULT
                // ---------------------------------------------

                /**
                 * Any unmatched HTTP request is denied.
                 *
                 * A newly added controller is not
                 * automatically accessible to all users
                 * who happen to possess a valid JWT.
                 *
                 * New endpoint groups must receive
                 * explicit authorization rules.
                 *
                 * This prevents accidental exposure
                 * as LedgerBase grows.
                 */
                .anyRequest().denyAll()
            )


            // =================================================
            // JWT RESOURCE SERVER
            // =================================================

            /**
             * Spring Security extracts Bearer access tokens
             * from Authorization headers.
             *
             * The existing JwtDecoder validates signatures,
             * token claims, expiration, and authentication
             * session status.
             *
             * BusinessAuthorizationManager then applies
             * database-backed business permissions.
             */
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(
                    Customizer.withDefaults()
                )
            );

        return http.build();
    }
}
