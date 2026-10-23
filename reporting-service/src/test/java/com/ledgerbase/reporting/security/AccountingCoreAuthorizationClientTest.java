package com.ledgerbase.reporting.security;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — ACCOUNTING CORE AUTHORIZATION CLIENT TESTS
// ============================================================

/**
 * Tests HTTP communication between Reporting Service
 * and Accounting Core.
 *
 * A temporary local HTTP server simulates Accounting Core.
 *
 * SECURITY REQUIREMENTS:
 *
 * - Only HTTP 204 authorizes reporting access.
 * - HTTP 401 and 403 deny access.
 * - Unexpected HTTP responses deny access.
 * - Redirects must not be followed.
 * - Connection failures must deny access.
 * - Invalid input must never trigger HTTP requests.
 *
 * No real database, JWT, or Accounting Core process
 * is required for these isolated transport tests.
 */
class AccountingCoreAuthorizationClientTest {

    private HttpServer server;

    private AccountingCoreAuthorizationClient client;

    private AtomicInteger responseStatus;
    private AtomicInteger requestCount;

    private AtomicReference<String> authorizationHeader;
    private AtomicReference<String> requestMethod;

    private AtomicBoolean redirectFollowed;

    // ========================================================
    // TEST HTTP SERVER SETUP
    // ========================================================

    @BeforeEach
    void setUp() throws IOException {

        responseStatus = new AtomicInteger(204);
        requestCount = new AtomicInteger(0);

        authorizationHeader = new AtomicReference<>();
        requestMethod = new AtomicReference<>();

        redirectFollowed = new AtomicBoolean(false);

        // Bind to localhost using an automatically
        // assigned available port.
        server = HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0),
            0
        );

        // Simulate Accounting Core's reporting-access API.
        server.createContext(
            "/api/v1/businesses/2/reporting-access",
            exchange -> {

                try {

                    requestCount.incrementAndGet();

                    // Capture request details for assertions.
                    authorizationHeader.set(
                        exchange.getRequestHeaders()
                            .getFirst("Authorization")
                    );

                    requestMethod.set(
                        exchange.getRequestMethod()
                    );

                    int status = responseStatus.get();

                    // Simulate an HTTP redirect.
                    if (status == 302) {

                        String redirectUrl =
                            "http://127.0.0.1:"
                            + server.getAddress().getPort()
                            + "/redirect-target";

                        exchange.getResponseHeaders().set(
                            "Location",
                            redirectUrl
                        );
                    }

                    // Return the configured HTTP status.
                    // No response body is necessary.
                    exchange.sendResponseHeaders(
                        status,
                        -1
                    );

                } finally {
                    exchange.close();
                }
            }
        );

        // Detect whether the client follows redirects.
        server.createContext(
            "/redirect-target",
            exchange -> {

                try {

                    redirectFollowed.set(true);

                    exchange.sendResponseHeaders(
                        204,
                        -1
                    );

                } finally {
                    exchange.close();
                }
            }
        );

        server.start();

        String baseUrl =
            "http://127.0.0.1:"
            + server.getAddress().getPort();

        client = new AccountingCoreAuthorizationClient(
            baseUrl
        );
    }

    // ========================================================
    // TEST SERVER CLEANUP
    // ========================================================

    @AfterEach
    void tearDown() {

        if (server != null) {
            server.stop(0);
        }
    }

    // ========================================================
    // AUTHORIZED RESPONSE
    // ========================================================

    @Test
    void http204GrantsReportingAccess() {

        assertTrue(
            client.hasReportingAccess(
                2L,
                "test-access-token"
            )
        );

        // Verify the existing JWT is forwarded.
        assertEquals(
            "Bearer test-access-token",
            authorizationHeader.get()
        );

        // The authorization check must use GET.
        assertEquals(
            "GET",
            requestMethod.get()
        );

        assertEquals(1, requestCount.get());
    }

    // ========================================================
    // INVALID AUTHENTICATION
    // ========================================================

    @Test
    void http401DeniesReportingAccess() {

        responseStatus.set(401);

        assertFalse(
            client.hasReportingAccess(
                2L,
                "test-access-token"
            )
        );
    }

    // ========================================================
    // MISSING BUSINESS PERMISSION
    // ========================================================

    @Test
    void http403DeniesReportingAccess() {

        responseStatus.set(403);

        assertFalse(
            client.hasReportingAccess(
                2L,
                "test-access-token"
            )
        );
    }

    // ========================================================
    // UNEXPECTED SUCCESS STATUS
    // ========================================================

    @Test
    void http200DoesNotGrantAccess() {

        responseStatus.set(200);

        // Only 204 is accepted, even though 200
        // is normally considered an HTTP success.
        assertFalse(
            client.hasReportingAccess(
                2L,
                "test-access-token"
            )
        );
    }

    // ========================================================
    // REDIRECT PROTECTION
    // ========================================================

    @Test
    void http302RedirectIsRejected() {

        responseStatus.set(302);

        assertFalse(
            client.hasReportingAccess(
                2L,
                "test-access-token"
            )
        );

        // A Bearer token must not be forwarded
        // to a redirected destination.
        assertFalse(
            redirectFollowed.get()
        );
    }

    // ========================================================
    // ACCOUNTING CORE SERVICE FAILURE
    // ========================================================

    @Test
    void http503DeniesReportingAccess() {

        responseStatus.set(503);

        assertFalse(
            client.hasReportingAccess(
                2L,
                "test-access-token"
            )
        );
    }

    // ========================================================
    // NETWORK FAILURE
    // ========================================================

    @Test
    void unavailableAccountingCoreDeniesAccess() {

        // Stop the simulated Accounting Core.
        server.stop(0);

        // A connection failure must never allow
        // financial data access.
        assertFalse(
            client.hasReportingAccess(
                2L,
                "test-access-token"
            )
        );
    }

    // ========================================================
    // INVALID INPUT VALIDATION
    // ========================================================

    @Test
    void invalidInputsAreRejectedBeforeHttpRequest() {

        assertFalse(
            client.hasReportingAccess(
                null,
                "test-access-token"
            )
        );

        assertFalse(
            client.hasReportingAccess(
                0L,
                "test-access-token"
            )
        );

        assertFalse(
            client.hasReportingAccess(
                -1L,
                "test-access-token"
            )
        );

        assertFalse(
            client.hasReportingAccess(
                2L,
                null
            )
        );

        assertFalse(
            client.hasReportingAccess(
                2L,
                ""
            )
        );

        // Invalid input must not trigger
        // outgoing HTTP authorization requests.
        assertEquals(
            0,
            requestCount.get()
        );
    }
}
