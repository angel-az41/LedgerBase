package com.ledgerbase.reporting.config;

import com.ledgerbase.reporting.security.ReportingBusinessAuthorizationManager;

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
// LEDGERBASE — REPORTING HTTP SECURITY
// ============================================================

/**
 * Protects the independent Reporting Service.
 *
 * Authentication:
 * - Stateless JWT Bearer authentication.
 * - Existing LedgerBase HS256 access tokens.
 *
 * Authorization:
 * - Public application health monitoring.
 * - All other requests denied until business-scoped
 *   authorization has been implemented.
 *
 * This initial configuration prevents accidental
 * exposure of financial reporting endpoints.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(
    type = ConditionalOnWebApplication.Type.SERVLET
)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain reportingSecurityFilterChain(
        HttpSecurity http,
        ReportingBusinessAuthorizationManager
            reportingBusinessAuthorizationManager
    ) throws Exception {

        http

            // No servlet-managed login session.
            .sessionManagement(session ->
                session.sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS
                )
            )

            // Bearer tokens are used instead of
            // browser form login or HTTP Basic.
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)

            // Reporting APIs will accept JWT headers,
            // not cookie-based authentication.
            .csrf(AbstractHttpConfigurer::disable)

            .authorizeHttpRequests(authorize -> authorize

                // Allow internal framework error dispatches.
                .dispatcherTypeMatchers(
                    DispatcherType.ERROR
                ).permitAll()

                // Preserve the public health endpoint.
                .requestMatchers(
                    HttpMethod.GET,
                    "/actuator/health",
                    "/actuator/health/**"
                ).permitAll()
                
                // ---------------------------------------------
                // TRIAL BALANCE REPORTING
                // ---------------------------------------------

                /**
                 * Allow GET requests to the Trial Balance
                 * endpoint only after business authorization.
                 *
                 * The authorization manager:
                 *
                 * 1. Requires authenticated JWT identity.
                 * 2. Validates the requested business ID.
                 * 3. Allows read-only HTTP operations.
                 * 4. Checks current session and membership
                 *    through Accounting Core.
                 *
                 * OWNER, ADMIN, ACCOUNTANT, and VIEWER
                 * may read authorized business reports.
                 *
                 * No financial writes are permitted.
                 */
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/businesses/{businessId}/reports/trial-balance"
                ).access(
                    reportingBusinessAuthorizationManager
                )

                // ---------------------------------------------
                // DENY ALL UNKNOWN ENDPOINTS
                // ---------------------------------------------

                /**
                 * Additional reporting APIs must receive
                 * explicit security rules before use.
                 */
                .anyRequest().denyAll()
            )

            // Validate incoming Bearer JWTs.
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(
                    Customizer.withDefaults()
                )
            );

        return http.build();
    }
}
