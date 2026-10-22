package com.ledgerbase.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

// ============================================================
// LEDGERBASE — AUTHENTICATION REQUEST ORIGIN VALIDATOR
// ============================================================

/**
 * Validates the Origin header of browser requests
 * that use cookie-based authentication.
 *
 * PURPOSE:
 *
 * Prevent untrusted websites from initiating
 * authenticated session operations through
 * the victim's browser.
 *
 * PROTECTED OPERATIONS:
 *
 * - Login that issues a refresh cookie.
 * - Refresh-token rotation.
 * - Session logout.
 *
 * SECURITY:
 *
 * - Uses an explicit allowlist of trusted origins.
 * - Requires exact origin matching.
 * - Rejects missing or unrecognized origins.
 * - Rejects wildcard configuration.
 *
 * IMPORTANT:
 *
 * Origin validation is a CSRF defense, not user
 * authentication or authorization.
 *
 * The HTTP endpoints must invoke this validator
 * before executing authentication operations.
 *
 * Creating this component alone does not protect
 * any endpoint until it is connected.
 */
@Component
public class AuthRequestOriginValidator {

    // ========================================================
    // TRUSTED ORIGINS
    // ========================================================

    private final Set<String> trustedOrigins;


    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    /**
     * Load the explicitly permitted browser origins.
     *
     * Local development defaults:
     *
     * http://localhost:5173 — React + Vite
     * http://localhost:8080 — Direct Spring Boot access
     *
     * Production must configure its actual HTTPS origin
     * through LEDGERBASE_AUTH_ALLOWED_ORIGINS.
     *
     * Multiple origins are separated by commas.
     */
    public AuthRequestOriginValidator(
        @Value(
            "${LEDGERBASE_AUTH_ALLOWED_ORIGINS:"
            + "http://localhost:5173,http://localhost:8080}"
        )
        String configuredOrigins
    ) {

        this.trustedOrigins =
            Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        // ====================================================
        // VALIDATE CONFIGURATION
        // ====================================================

        if (trustedOrigins.isEmpty()) {

            throw new IllegalArgumentException(
                "At least one trusted origin is required"
            );
        }

        for (String origin : trustedOrigins) {

            if (!isValidConfiguredOrigin(origin)) {

                throw new IllegalArgumentException(
                    "Invalid trusted origin configuration"
                );
            }
        }
    }


    // ========================================================
    // VALIDATE CONFIGURED ORIGIN
    // ========================================================

    /**
     * A trusted origin must contain only:
     *
     * - HTTP or HTTPS scheme.
     * - Hostname.
     * - Optional port.
     *
     * Paths, query strings, fragments, wildcard
     * values, and embedded user information
     * are not valid origin configurations.
     */
    private boolean isValidConfiguredOrigin(
        String origin
    ) {

        try {

            URI uri = URI.create(origin);

            boolean validScheme =
                "http".equals(uri.getScheme()) ||
                "https".equals(uri.getScheme());

            boolean validHost =
                uri.getHost() != null;

            boolean noPath =
                uri.getRawPath() == null ||
                uri.getRawPath().isEmpty();

            boolean noQuery =
                uri.getRawQuery() == null;

            boolean noFragment =
                uri.getRawFragment() == null;

            boolean noUserInfo =
                uri.getRawUserInfo() == null;

            boolean exactOriginFormat =
                origin.equals(
                    uri.getScheme()
                    + "://"
                    + uri.getRawAuthority()
                );

            return validScheme
                && validHost
                && noPath
                && noQuery
                && noFragment
                && noUserInfo
                && exactOriginFormat;

        } catch (IllegalArgumentException exception) {

            return false;
        }
    }


    // ========================================================
    // CHECK REQUEST ORIGIN
    // ========================================================

    /**
     * Check whether a browser request originated
     * from an explicitly trusted application origin.
     *
     * Exact equality prevents deceptive domains
     * from matching through prefix or suffix tricks.
     *
     * Missing headers, the literal "null", and
     * untrusted origins are rejected.
     *
     * @param requestOrigin Origin request header
     * @return true only for an allowed origin
     */
    public boolean isTrustedOrigin(
        String requestOrigin
    ) {

        return requestOrigin != null
            && trustedOrigins.contains(requestOrigin);
    }
}
