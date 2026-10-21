package com.ledgerbase.backend.config;

import com.ledgerbase.backend.service.JwtTokenService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.time.Duration;
import java.util.Base64;

// ============================================================
// LEDGERBASE — JWT AUTHENTICATION CONFIGURATION
// ============================================================

/**
 * Configures the cryptographic components used for
 * LedgerBase JWT access tokens.
 *
 * RESPONSIBILITIES:
 *
 * 1. Decode and validate the JWT signing secret.
 * 2. Create a JWT encoder for token generation.
 * 3. Create a JWT decoder for token verification.
 * 4. Register JwtTokenService for dependency injection.
 *
 * SECURITY:
 *
 * - The signing secret is loaded from configuration.
 * - The secret must contain at least 256 bits.
 * - HS256 is explicitly selected.
 * - Missing or invalid secrets prevent startup.
 * - Token issuer and expiration are validated.
 *
 * IMPORTANT:
 *
 * These beans do not independently protect HTTP routes.
 *
 * Request authentication and business authorization
 * will be configured separately.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    // ========================================================
    // ACCESS TOKEN LIFETIME
    // ========================================================

    /**
     * Access tokens expire 15 minutes after issuance.
     *
     * Refresh-token support is outside the current
     * implementation.
     */
    private static final Duration ACCESS_TOKEN_LIFETIME =
        Duration.ofMinutes(15);


    // ========================================================
    // 1. JWT SIGNING KEY
    // ========================================================

    /**
     * Decode the Base64 signing secret.
     *
     * The underlying key must be at least 32 bytes
     * long to provide 256 bits of key material.
     *
     * An invalid configuration causes startup
     * to fail instead of using an insecure fallback.
     *
     * @param base64Secret configured signing secret
     * @return validated HMAC signing key
     */
    @Bean
    public SecretKey jwtSigningKey(
        @Value("${ledgerbase.jwt.secret-base64:}")
        String base64Secret
    ) {

        // ====================================================
        // REQUIRE A CONFIGURED SECRET
        // ====================================================

        if (
            base64Secret == null
            || base64Secret.isBlank()
        ) {

            throw new IllegalStateException(
                "JWT signing secret is not configured"
            );
        }


        // ====================================================
        // DECODE BASE64 KEY MATERIAL
        // ====================================================

        byte[] keyBytes;

        try {

            keyBytes = Base64.getDecoder().decode(
                base64Secret.trim()
            );

        } catch (IllegalArgumentException exception) {

            // Do not include the secret in error messages.

            throw new IllegalStateException(
                "JWT signing secret must be valid Base64"
            );
        }


        // ====================================================
        // ENFORCE MINIMUM KEY LENGTH
        // ====================================================

        if (keyBytes.length < 32) {

            throw new IllegalStateException(
                "JWT signing key must contain at least 32 bytes"
            );
        }


        // ====================================================
        // CREATE HMAC KEY
        // ====================================================

        return new SecretKeySpec(
            keyBytes,
            "HmacSHA256"
        );
    }


    // ========================================================
    // 2. JWT ENCODER
    // ========================================================

    /**
     * Create the encoder used for signing JWTs.
     *
     * NimbusJwtEncoder signs access tokens using
     * the validated server-side secret key.
     *
     * @param jwtSigningKey trusted signing key
     * @return JWT encoder
     */
    @Bean
    public JwtEncoder jwtEncoder(
        SecretKey jwtSigningKey
    ) {

        return NimbusJwtEncoder
            .withSecretKey(jwtSigningKey)
            .algorithm(MacAlgorithm.HS256)
            .build();
    }


    // ========================================================
    // 3. JWT DECODER
    // ========================================================

    /**
     * Create the decoder used for JWT verification.
     *
     * Signature verification must succeed before
     * any JWT claims can be trusted.
     *
     * Standard validators check token timestamps
     * and the expected issuer.
     *
     * JwtTokenService additionally checks the
     * audience, expiration, and numeric user ID.
     *
     * @param jwtSigningKey trusted signing key
     * @param issuer expected JWT issuer
     * @return validated JWT decoder
     */
    @Bean
    public JwtDecoder jwtDecoder(
        SecretKey jwtSigningKey,
        @Value("${ledgerbase.jwt.issuer}")
        String issuer
    ) {

        NimbusJwtDecoder decoder =
            NimbusJwtDecoder
                .withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();


        // ====================================================
        // CONFIGURE STANDARD JWT VALIDATORS
        // ====================================================

        decoder.setJwtValidator(
            JwtValidators.createDefaultWithIssuer(
                issuer
            )
        );

        return decoder;
    }


    // ========================================================
    // 4. JWT TOKEN SERVICE
    // ========================================================

    /**
     * Register the token service as a Spring bean.
     *
     * Other backend services will be able to inject
     * JwtTokenService through their constructors.
     *
     * This does not issue tokens automatically.
     * Tokens will be issued after successful login.
     */
    @Bean
    public JwtTokenService jwtTokenService(
        JwtEncoder jwtEncoder,
        JwtDecoder jwtDecoder,

        @Value("${ledgerbase.jwt.issuer}")
        String issuer,

        @Value("${ledgerbase.jwt.audience}")
        String audience
    ) {

        return new JwtTokenService(
            jwtEncoder,
            jwtDecoder,
            issuer,
            audience,
            ACCESS_TOKEN_LIFETIME
        );
    }
}
