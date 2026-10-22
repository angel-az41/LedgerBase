package com.ledgerbase.backend.service;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — REFRESH COOKIE SERVICE TESTS
// ============================================================

/**
 * Unit tests for refresh-cookie creation and deletion.
 *
 * These tests inspect HTTP Set-Cookie headers.
 *
 * They do not require:
 *
 * - A running Spring Boot server.
 * - PostgreSQL.
 * - JWT signing configuration.
 * - A real browser.
 *
 * Browser behavior will be tested after the HTTP
 * authentication endpoints have been implemented.
 */
class RefreshCookieServiceTest {

    // ========================================================
    // TEST CREDENTIAL
    // ========================================================

    private static final String TEST_TOKEN =
        "A".repeat(43);


    // ========================================================
    // TEST — SECURE PRODUCTION COOKIE
    // ========================================================

    /**
     * Production cookies must use:
     *
     * - HttpOnly
     * - Secure
     * - SameSite=Strict
     * - Restricted Path
     * - A finite lifetime
     *
     * No Domain attribute should be present.
     */
    @Test
    void shouldIssueSecureRefreshCookie() {

        RefreshCookieService service =
            new RefreshCookieService(true);

        MockHttpServletResponse response =
            new MockHttpServletResponse();

        OffsetDateTime expiration =
            OffsetDateTime.now(ZoneOffset.UTC)
                .plusHours(8);

        service.writeRefreshCookie(
            response,
            TEST_TOKEN,
            expiration
        );

        String cookie = response.getHeader(
            HttpHeaders.SET_COOKIE
        );

        assertNotNull(cookie);

        assertTrue(
            cookie.contains(
                "ledgerbase_refresh=" + TEST_TOKEN
            )
        );

        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=Strict"));
        assertTrue(cookie.contains("Path=/api/v1/auth"));
        assertTrue(cookie.contains("Max-Age="));

        assertFalse(cookie.contains("Domain="));
    }


    // ========================================================
    // TEST — LOCAL HTTP COOKIE
    // ========================================================

    /**
     * Local development can disable Secure so the
     * cookie can be tested over HTTP.
     *
     * HttpOnly and SameSite protections remain enabled.
     */
    @Test
    void shouldSupportLocalHttpDevelopment() {

        RefreshCookieService service =
            new RefreshCookieService(false);

        MockHttpServletResponse response =
            new MockHttpServletResponse();

        service.writeRefreshCookie(
            response,
            TEST_TOKEN,
            OffsetDateTime.now(ZoneOffset.UTC)
                .plusHours(8)
        );

        String cookie = response.getHeader(
            HttpHeaders.SET_COOKIE
        );

        assertNotNull(cookie);

        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict"));

        assertFalse(cookie.contains("; Secure"));
    }


    // ========================================================
    // TEST — CLEAR REFRESH COOKIE
    // ========================================================

    /**
     * Deleting a cookie requires:
     *
     * - The same cookie name.
     * - The same cookie path.
     * - Max-Age=0.
     *
     * Browser deletion does not replace
     * server-side session revocation.
     */
    @Test
    void shouldClearRefreshCookie() {

        RefreshCookieService service =
            new RefreshCookieService(true);

        MockHttpServletResponse response =
            new MockHttpServletResponse();

        service.clearRefreshCookie(response);

        String cookie = response.getHeader(
            HttpHeaders.SET_COOKIE
        );

        assertNotNull(cookie);

        assertTrue(
            cookie.contains("ledgerbase_refresh=")
        );

        assertTrue(cookie.contains("Max-Age=0"));

        assertTrue(
            cookie.contains("Path=/api/v1/auth")
        );

        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=Strict"));
    }


    // ========================================================
    // TEST — EXPIRED SESSION DEADLINE
    // ========================================================

    /**
     * Expired sessions must not create cookies
     * with a negative expiration lifetime.
     *
     * An already-expired deadline produces
     * Max-Age=0.
     */
    @Test
    void shouldNotPersistAnExpiredCookie() {

        RefreshCookieService service =
            new RefreshCookieService(true);

        MockHttpServletResponse response =
            new MockHttpServletResponse();

        service.writeRefreshCookie(
            response,
            TEST_TOKEN,
            OffsetDateTime.now(ZoneOffset.UTC)
                .minusMinutes(1)
        );

        String cookie = response.getHeader(
            HttpHeaders.SET_COOKIE
        );

        assertNotNull(cookie);

        assertTrue(cookie.contains("Max-Age=0"));
    }
}
