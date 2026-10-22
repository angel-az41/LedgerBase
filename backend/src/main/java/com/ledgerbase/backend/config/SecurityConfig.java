package com.ledgerbase.backend.config;

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
 * Configures Spring Security for LedgerBase's REST APIs.
 *
 * AUTHENTICATION ARCHITECTURE:
 *
 * - Stateless HTTP requests.
 * - JWT Bearer access tokens.
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
 * PROTECTED ENDPOINTS:
 *
 * - GET /api/v1/auth/me
 * - All accounting and financial APIs.
 * - All other endpoints not explicitly made public.
 *
 * JWT VALIDATION:
 *
 * The configured JwtDecoder validates:
 *
 * - JWT cryptographic signature.
 * - Token expiration.
 * - Issuer and audience.
 * - Session identifier.
 * - Active PostgreSQL authentication session.
 *
 * AUTHORIZATION:
 *
 * This configuration establishes the requirement
 * that API callers must be authenticated.
 *
 * Business membership and role-specific permissions
 * must be enforced separately.
 *
 * Being authenticated does not automatically mean
 * the user may access every business.
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
     * Register the application HTTP security rules.
     *
     * @param http Spring Security HTTP configuration
     * @return configured security filter chain
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
        HttpSecurity http
    ) throws Exception {

        http

            // =================================================
            // STATELESS AUTHENTICATION
            // =================================================

            /**
             * Spring Security does not create or use
             * servlet HTTP sessions for authentication.
             *
             * Authentication-session records are instead
             * managed by LedgerBase in PostgreSQL.
             */
            .sessionManagement(session ->
                session.sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS
                )
            )


            // =================================================
            // DISABLE UNUSED LOGIN MECHANISMS
            // =================================================

            /**
             * LedgerBase has its own JSON-based
             * authentication endpoints.
             *
             * Browser form login and HTTP Basic
             * authentication are unnecessary.
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
             * LedgerBase's cookie-authenticated login,
             * refresh, and logout operations explicitly
             * validate the request Origin header.
             *
             * Financial API requests use Bearer JWTs
             * rather than cookie authentication.
             *
             * The complete CSRF strategy must be
             * reviewed before production deployment.
             */
            .csrf(
                AbstractHttpConfigurer::disable
            )


            // =================================================
            // HTTP AUTHORIZATION RULES
            // =================================================

            /**
             * Spring Security evaluates these rules
             * in the order in which they are declared.
             *
             * More specific public endpoints must
             * appear before the authenticated fallback.
             */
            .authorizeHttpRequests(authorize -> authorize

                // ---------------------------------------------
                // PUBLIC AUTHENTICATION OPERATIONS
                // ---------------------------------------------

                /**
                 * Login, refresh, and logout are
                 * deliberately accessible without
                 * an existing access JWT.
                 *
                 * Login uses credentials.
                 *
                 * Refresh and logout use the
                 * HttpOnly refresh cookie.
                 *
                 * All three operations validate
                 * trusted request origins in the
                 * authentication controller.
                 *
                 * Restrict public access to POST
                 * rather than every HTTP method.
                 */
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout"
                ).permitAll()


                // ---------------------------------------------
                // PUBLIC DATABASE HEALTH ENDPOINTS
                // ---------------------------------------------

                /**
                 * Health checks must remain accessible
                 * before a user has authenticated.
                 *
                 * Existing development endpoint:
                 *
                 * GET /api/v1/health/database
                 *
                 * Only GET requests are allowed through
                 * this public authentication exception.
                 *
                 * Health responses should not expose
                 * database credentials or sensitive
                 * infrastructure configuration.
                 */
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/health",
                    "/api/v1/health/**"
                ).permitAll()


                // ---------------------------------------------
                // AUTHENTICATED CURRENT USER
                // ---------------------------------------------

                /**
                 * Spring Security must verify a valid
                 * Bearer JWT before allowing access
                 * to the authenticated user's identity.
                 */
                .requestMatchers(
                    "/api/v1/auth/me"
                ).authenticated()


                // ---------------------------------------------
                // PROTECT ALL REMAINING API ENDPOINTS
                // ---------------------------------------------

                /**
                 * Every other API request requires
                 * an authenticated principal.
                 *
                 * This covers:
                 *
                 * - Businesses.
                 * - Chart of Accounts.
                 * - Account import/export.
                 * - Transactions.
                 * - Fiscal years.
                 * - Accounting periods.
                 * - Trial Balance.
                 * - Income Statement.
                 * - Balance Sheet.
                 *
                 * It also protects newly added APIs
                 * by default.
                 *
                 * Authentication establishes identity.
                 *
                 * Business-scoped authorization is
                 * the next security implementation.
                 */
                .anyRequest().authenticated()
            )


            // =================================================
            // JWT BEARER RESOURCE SERVER
            // =================================================

            /**
             * Configure Spring Security to read:
             *
             * Authorization: Bearer <JWT>
             *
             * The application's JwtDecoder validates
             * cryptographic claims and checks the
             * PostgreSQL authentication-session state.
             *
             * An invalid, expired, or revoked session
             * must not authenticate successfully.
             */
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(
                    Customizer.withDefaults()
                )
            );

        return http.build();
    }
}
