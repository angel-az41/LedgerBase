package com.ledgerbase.reporting.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.util.Base64;
import java.util.List;

// ============================================================
// LEDGERBASE — REPORTING JWT CONFIGURATION
// ============================================================

/**
 * Verifies JWT access tokens issued by Accounting Core.
 *
 * SECURITY:
 *
 * - Use the existing LedgerBase HS256 signing secret.
 * - Require a minimum 256-bit signing key.
 * - Validate JWT signature and timestamps.
 * - Validate the expected issuer and audience.
 * - Require user and authentication session claims.
 *
 * This service does not generate access tokens.
 *
 * Current session validity and business permissions
 * will be checked against Accounting Core separately.
 */
@Configuration(proxyBeanMethods = false)
public class ReportingJwtConfig {

    // ========================================================
    // JWT SIGNING KEY
    // ========================================================

    /**
     * Decode the existing LedgerBase HMAC secret.
     *
     * Never use a fallback signing secret.
     */
    @Bean
    public SecretKey reportingJwtSigningKey(
        @Value("${ledgerbase.jwt.secret-base64:}")
        String base64Secret
    ) {

        if (
            base64Secret == null
            || base64Secret.isBlank()
        ) {
            throw new IllegalStateException(
                "Reporting JWT signing secret is missing"
            );
        }

        byte[] keyBytes;

        try {
            keyBytes = Base64.getDecoder().decode(
                base64Secret.trim()
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                "Reporting JWT secret must be valid Base64"
            );
        }

        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                "Reporting JWT key must contain at least 32 bytes"
            );
        }

        return new SecretKeySpec(
            keyBytes,
            "HmacSHA256"
        );
    }

    // ========================================================
    // JWT DECODER
    // ========================================================

    /**
     * Validate existing Accounting Core access tokens.
     *
     * This decoder performs local cryptographic and
     * claim validation.
     *
     * PostgreSQL authentication session revocation
     * is checked separately through Accounting Core.
     */
    @Bean
    public JwtDecoder reportingJwtDecoder(
        SecretKey reportingJwtSigningKey,

        @Value("${ledgerbase.jwt.issuer}")
        String issuer,

        @Value("${ledgerbase.jwt.audience}")
        String audience
    ) {

        NimbusJwtDecoder decoder =
            NimbusJwtDecoder
                .withSecretKey(reportingJwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        // Require the intended LedgerBase API audience.
        JwtClaimValidator<List<String>> audienceValidator =
            new JwtClaimValidator<>(
                JwtClaimNames.AUD,
                audiences -> audiences != null
                    && audiences.contains(audience)
            );

        // Require the session-aware token structure.
        OAuth2TokenValidator<Jwt> requiredClaimsValidator =
            jwt -> {

                Object sessionClaim =
                    jwt.getClaims().get("sid");

                boolean valid =
                    jwt.getExpiresAt() != null
                    && jwt.getIssuedAt() != null
                    && jwt.getSubject() != null
                    && jwt.getSubject().matches("[1-9][0-9]*")
                    && sessionClaim instanceof String
                    && !((String) sessionClaim).isBlank();

                if (!valid) {
                    return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error(
                            "invalid_token",
                            "Missing required access token claims",
                            null
                        )
                    );
                }

                return OAuth2TokenValidatorResult.success();
            };

        // Validate issuer, timestamps, audience,
        // and the required LedgerBase token claims.
        decoder.setJwtValidator(
            new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(
                    issuer
                ),
                audienceValidator,
                requiredClaimsValidator
            )
        );

        return decoder;
    }
}
