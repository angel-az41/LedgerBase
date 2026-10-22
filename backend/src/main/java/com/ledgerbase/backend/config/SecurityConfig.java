package com.ledgerbase.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

// ============================================================
// LEDGERBASE — HTTP SECURITY CONFIGURATION
// ============================================================

/**
 * Configures HTTP authentication for LedgerBase.
 *
 * This configuration is activated only when the
 * application runs as a servlet web application.
 *
 * Non-web integration tests can load JWT signing
 * and validation components without requiring
 * HttpSecurity or a servlet filter chain.
 *
 * AUTHENTICATION:
 *
 * - Stateless Spring Security authentication.
 * - JWT Bearer authentication for protected routes.
 * - Cookie-based refresh-session restoration.
 *
 * PUBLIC AUTHENTICATION ROUTES:
 *
 * POST /api/v1/auth/login
 * POST /api/v1/auth/refresh
 *
 * PROTECTED IDENTITY ROUTE:
 *
 * GET /api/v1/auth/me
 *
 * IMPORTANT:
 *
 * Accounting APIs are temporarily accessible
 * without authentication during development.
 *
 * That compatibility rule must be removed after
 * adding frontend Bearer-token propagation,
 * database-backed session validation, and
 * business-scoped authorization.
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
     * Register Spring Security HTTP authentication rules.
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
            // STATELESS HTTP AUTHENTICATION
            // =================================================

            /**
             * Spring Security does not create an HTTP
             * session to store the authenticated user.
             *
             * Persistent login state is tracked by
             * AuthSessionService in PostgreSQL.
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
             * LedgerBase uses REST authentication
             * rather than server-rendered login forms.
             */
            .formLogin(AbstractHttpConfigurer::disable)

            .httpBasic(AbstractHttpConfigurer::disable)


            // =================================================
            // CSRF CONFIGURATION
            // =================================================

            /**
             * CSRF is temporarily disabled globally.
             *
             * Cookie-authenticated login and refresh
             * endpoints explicitly validate the Origin
             * header before performing session operations.
             *
             * Do not add further cookie-authenticated
             * mutations without appropriate protection.
             *
             * A complete Spring Security CSRF strategy
             * must be reviewed before production use.
             */
            .csrf(AbstractHttpConfigurer::disable)


            // =================================================
            // HTTP AUTHORIZATION RULES
            // =================================================

            .authorizeHttpRequests(authorize -> authorize

                // ---------------------------------------------
                // PUBLIC AUTHENTICATION ENDPOINTS
                // ---------------------------------------------

                /**
                 * Login verifies a password.
                 *
                 * Refresh uses the HttpOnly refresh cookie.
                 *
                 * Logout revokes the authentication session
                 * identified by the refresh cookie.
                 *
                 * These operations cannot require an existing
                 * access JWT because it may have expired.
                 *
                 * The controller validates trusted origins
                 * before performing cookie-based operations.
                 */
                .requestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout"
                ).permitAll()


                // ---------------------------------------------
                // AUTHENTICATED USER ENDPOINT
                // ---------------------------------------------

                /**
                 * /auth/me requires a valid Bearer JWT.
                 *
                 * Database-backed session revocation
                 * validation will be added separately.
                 */
                .requestMatchers(
                    "/api/v1/auth/me"
                ).authenticated()


                // ---------------------------------------------
                // TEMPORARY ACCOUNTING API COMPATIBILITY
                // ---------------------------------------------

                /**
                 * Preserve existing frontend operations
                 * while JWT propagation and business RBAC
                 * are being implemented.
                 *
                 * WARNING:
                 *
                 * These routes are not yet protected.
                 *
                 * Replace this rule before considering
                 * the application secure for deployment.
                 */
                .anyRequest().permitAll()
            )


            // =================================================
            // JWT BEARER AUTHENTICATION
            // =================================================

            /**
             * Validate incoming Bearer JWTs using
             * the configured JwtDecoder.
             *
             * This verifies the JWT cryptographically.
             *
             * Server-side revocation and membership
             * authorization must still be enforced.
             */
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(Customizer.withDefaults())
            );

        return http.build();
    }
}
