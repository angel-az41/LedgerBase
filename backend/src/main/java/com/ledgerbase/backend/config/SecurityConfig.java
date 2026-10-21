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
 * Configures HTTP authentication only when LedgerBase
 * runs as a servlet-based web application.
 *
 * Non-web integration tests can still load JWT signing
 * and validation components without requiring
 * HttpSecurity or a servlet security filter chain.
 *
 * The HTTP security rules remain active when the
 * application runs normally as a Spring Boot REST API.
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
     * Register the security rules applied to requests.
     *
     * @param http Spring Security HTTP configuration
     * @return configured filter chain
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
        HttpSecurity http
    ) throws Exception {

        http

            // =================================================
            // STATELESS AUTHENTICATION
            // =================================================

            .sessionManagement(session ->
                session.sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS
                )
            )

            // =================================================
            // DISABLE UNUSED LOGIN MECHANISMS
            // =================================================

            // Authentication will use JWT Bearer headers,
            // not server-side HTML login forms or HTTP Basic.

            .formLogin(AbstractHttpConfigurer::disable)

            .httpBasic(AbstractHttpConfigurer::disable)


            // =================================================
            // CSRF CONFIGURATION
            // =================================================

            // The planned API authentication mechanism uses
            // explicit Bearer headers rather than browser
            // cookies that are sent automatically.
            //
            // Revisit this configuration if cookie-based
            // authentication is introduced.

            .csrf(AbstractHttpConfigurer::disable)


            // =================================================
            // HTTP AUTHORIZATION RULES
            // =================================================

            .authorizeHttpRequests(authorize -> authorize

                // Login must be accessible without a JWT.
                .requestMatchers(
                    "/api/v1/auth/login"
                ).permitAll()

                // The identity verification endpoint
                // will require a valid JWT.
                //
                // We'll implement this endpoint next.
                .requestMatchers(
                    "/api/v1/auth/me"
                ).authenticated()

                // TEMPORARY COMPATIBILITY RULE:
                //
                // Keep existing accounting requests working
                // while frontend JWT support is developed.
                //
                // This does not provide business authorization.
                .anyRequest().permitAll()
            )


            // =================================================
            // JWT BEARER AUTHENTICATION
            // =================================================

            // Uses the application's JwtDecoder bean
            // to verify signatures and JWT claims.

            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(Customizer.withDefaults())
            );

        return http.build();
    }
}
