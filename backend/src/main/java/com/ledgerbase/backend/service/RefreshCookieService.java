package com.ledgerbase.backend.service;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;

// ============================================================
// LEDGERBASE — REFRESH COOKIE SERVICE
// ============================================================

/**
 * Manages browser cookies containing refresh tokens.
 *
 * RESPONSIBILITIES:
 *
 * - Issue an HttpOnly refresh cookie after login.
 * - Replace the cookie after refresh-token rotation.
 * - Clear the cookie during logout or session failure.
 *
 * SECURITY PROPERTIES:
 *
 * HttpOnly:
 * Prevents browser JavaScript from reading the token.
 *
 * Secure:
 * Restricts transmission to HTTPS in production.
 *
 * SameSite=Strict:
 * Restricts cross-site cookie transmission.
 *
 * Path:
 * Limits the cookie to authentication API routes.
 *
 * Domain:
 * Intentionally omitted to create a host-only cookie.
 *
 * IMPORTANT:
 *
 * Cookie attributes are not a complete CSRF defense.
 * Authentication endpoints must validate request origin
 * before accepting cookie-authenticated operations.
 *
 * Server-side session expiration and revocation remain
 * authoritative even when the browser retains a cookie.
 */
@Component
public class RefreshCookieService {

    // ========================================================
    // COOKIE CONFIGURATION
    // ========================================================

    public static final String COOKIE_NAME =
        "ledgerbase_refresh";

    public static final String COOKIE_PATH =
        "/api/v1/auth";

    private static final String SAME_SITE_POLICY =
        "Strict";


    // ========================================================
    // HTTPS COOKIE REQUIREMENT
    // ========================================================

    /**
     * Secure cookies are enabled by default.
     *
     * Local HTTP development can explicitly set:
     *
     * AUTH_REFRESH_COOKIE_SECURE=false
     *
     * HTTPS deployments must keep this setting true.
     */
    private final boolean secureCookie;


    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public RefreshCookieService(
        @Value("${AUTH_REFRESH_COOKIE_SECURE:true}")
        boolean secureCookie
    ) {

        this.secureCookie = secureCookie;
    }


    // ========================================================
    // ISSUE OR REPLACE REFRESH COOKIE
    // ========================================================

    /**
     * Write a refresh token into a browser cookie.
     *
     * The cookie expiration cannot exceed the absolute
     * lifetime of the underlying PostgreSQL session.
     *
     * Refresh-token rotation therefore does not
     * extend the original eight-hour session lifetime.
     *
     * The original token is never included in
     * an ordinary JSON response.
     *
     * @param response HTTP response
     * @param refreshToken newly issued opaque credential
     * @param absoluteExpiresAt session expiration deadline
     */
    public void writeRefreshCookie(
        HttpServletResponse response,
        String refreshToken,
        OffsetDateTime absoluteExpiresAt
    ) {

        Objects.requireNonNull(response);
        Objects.requireNonNull(refreshToken);
        Objects.requireNonNull(absoluteExpiresAt);

        // ====================================================
        // CALCULATE REMAINING COOKIE LIFETIME
        // ====================================================

        /**
         * Use the session's absolute deadline.
         *
         * Never turn an expired deadline into a
         * negative Max-Age, because negative values
         * can represent browser-session cookies.
         *
         * Clamp expiration to zero instead.
         */
        long remainingSeconds =
            Math.max(
                0L,
                Duration.between(
                    Instant.now(),
                    absoluteExpiresAt.toInstant()
                ).getSeconds()
            );


        // ====================================================
        // BUILD SECURE COOKIE
        // ====================================================

        ResponseCookie cookie =
            ResponseCookie
                .from(COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite(SAME_SITE_POLICY)
                .path(COOKIE_PATH)
                .maxAge(
                    Duration.ofSeconds(remainingSeconds)
                )
                .build();


        // ====================================================
        // ADD COOKIE TO HTTP RESPONSE
        // ====================================================

        /**
         * Use addHeader rather than setHeader to avoid
         * overwriting another Set-Cookie header.
         */
        response.addHeader(
            HttpHeaders.SET_COOKIE,
            cookie.toString()
        );
    }


    // ========================================================
    // CLEAR REFRESH COOKIE
    // ========================================================

    /**
     * Instruct the browser to delete its refresh cookie.
     *
     * The cookie name and path must match the
     * original issued cookie.
     *
     * Max-Age=0 causes immediate browser removal.
     *
     * This operation does not revoke the PostgreSQL
     * session. The logout controller must call the
     * session service separately.
     */
    public void clearRefreshCookie(
        HttpServletResponse response
    ) {

        Objects.requireNonNull(response);

        ResponseCookie cookie =
            ResponseCookie
                .from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite(SAME_SITE_POLICY)
                .path(COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .build();

        response.addHeader(
            HttpHeaders.SET_COOKIE,
            cookie.toString()
        );
    }
}
