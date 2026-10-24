package com.ledgerbase.reporting.config;

import com.ledgerbase.reporting.security.ReportingBusinessAuthorizationManager;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.http.HttpMethod;

import org.springframework.security.authorization.AuthorizationDecision;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

// ============================================================
// LEDGERBASE — REPORTING HTTP SECURITY
// ============================================================

/**
 * Protect the independent LedgerBase Reporting Service.
 *
 * AUTHENTICATION:
 *
 * - Stateless JWT Bearer authentication.
 * - Existing LedgerBase HS256 access tokens.
 * - No servlet-managed login sessions.
 *
 * FINANCIAL REPORT AUTHORIZATION:
 *
 * - Require authenticated JWT identity.
 * - Check active Accounting Core sessions.
 * - Verify current business membership and permissions.
 * - Restrict financial reporting to authorized businesses.
 *
 * MONITORING:
 *
 * - Keep the existing public health endpoint.
 * - Permit local metrics access only when explicitly enabled.
 * - Require the metrics client to connect from loopback.
 * - Deny unknown application endpoints.
 *
 * SECURITY:
 *
 * Local metrics access is intended only for development
 * and controlled local performance verification.
 *
 * Production monitoring should use dedicated monitoring
 * authentication and network isolation.
 *
 * Redis cache availability never changes financial
 * reporting authorization requirements.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(
    type = ConditionalOnWebApplication.Type.SERVLET
)
public class SecurityConfig {

    // ========================================================
    // CONFIGURE REPORTING SECURITY FILTER CHAIN
    // ========================================================

    @Bean
    public SecurityFilterChain reportingSecurityFilterChain(
        HttpSecurity http,

        ReportingBusinessAuthorizationManager
            reportingBusinessAuthorizationManager,

        @Value(
            "${ledgerbase.monitoring.local-metrics-enabled:false}"
        )
        boolean localMetricsEnabled

    ) throws Exception {

        http

            // ====================================================
            // STATELESS AUTHENTICATION
            // ====================================================

            .sessionManagement(session ->
                session.sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS
                )
            )

            // JWT Bearer authentication replaces
            // browser form login and HTTP Basic.
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)

            // Financial API clients use Bearer tokens
            // rather than cookie-based authentication.
            .csrf(AbstractHttpConfigurer::disable)

            // ====================================================
            // HTTP ENDPOINT AUTHORIZATION
            // ====================================================

            .authorizeHttpRequests(authorize -> authorize

                // ------------------------------------------------
                // FRAMEWORK ERROR DISPATCHES
                // ------------------------------------------------

                .dispatcherTypeMatchers(
                    DispatcherType.ERROR
                ).permitAll()

                // ------------------------------------------------
                // PUBLIC HEALTH ENDPOINT
                // ------------------------------------------------

                /*
                 * Preserve existing health monitoring.
                 *
                 * Sensitive health details remain hidden
                 * through application.yaml.
                 */
                .requestMatchers(
                    HttpMethod.GET,
                    "/actuator/health",
                    "/actuator/health/**"
                ).permitAll()

                // ------------------------------------------------
                // LOCAL-ONLY ACTUATOR METRICS
                // ------------------------------------------------

                /*
                 * Metrics are available only when:
                 *
                 * 1. local-metrics-enabled is true.
                 *
                 * 2. The immediate connecting client
                 *    has the IPv4 loopback address.
                 *
                 * No Bearer token is required for this
                 * specific local monitoring endpoint.
                 *
                 * Do not use forwarded HTTP headers
                 * to determine the client's address.
                 *
                 * The Reporting Service also binds to
                 * 127.0.0.1 in local application.yaml.
                 *
                 * Keep this feature disabled in deployed
                 * or externally accessible environments.
                 */
                .requestMatchers(
                    HttpMethod.GET,
                    "/actuator/metrics",
                    "/actuator/metrics/**"
                ).access(
                    (authentication, context) -> {

                        String remoteAddress =
                            context.getRequest()
                                .getRemoteAddr();

                        boolean isLocalClient =
                            "127.0.0.1".equals(
                                remoteAddress
                            );

                        boolean allowed =
                            localMetricsEnabled
                            && isLocalClient;

                        return new AuthorizationDecision(
                            allowed
                        );
                    }
                )

                // ------------------------------------------------
                // BUSINESS-SCOPED TRIAL BALANCE REPORTING
                // ------------------------------------------------

                /*
                 * Preserve the existing financial
                 * reporting authorization manager.
                 *
                 * It:
                 *
                 * - Requires a valid JWT.
                 * - Validates the requested business ID.
                 * - Checks current authentication sessions.
                 * - Verifies business membership through
                 *   Accounting Core.
                 * - Allows authorized read-only reporting.
                 *
                 * OWNER, ADMIN, ACCOUNTANT, and VIEWER
                 * may read reports for authorized businesses.
                 *
                 * Redis cache hits must not bypass this rule.
                 */
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/businesses/{businessId}/reports/trial-balance"
                ).access(
                    reportingBusinessAuthorizationManager
                )

                // ------------------------------------------------
                // DENY UNRECOGNIZED ENDPOINTS
                // ------------------------------------------------

                /*
                 * Every additional financial API or
                 * monitoring endpoint must receive
                 * an explicit authorization rule.
                 */
                .anyRequest().denyAll()
            )

            // ====================================================
            // JWT RESOURCE SERVER
            // ====================================================

            /*
             * Continue validating incoming JWT Bearer
             * tokens for protected reporting endpoints.
             *
             * This does not require credentials on the
             * separately authorized local metrics route.
             */
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(
                    Customizer.withDefaults()
                )
            );

        return http.build();
    }
}
