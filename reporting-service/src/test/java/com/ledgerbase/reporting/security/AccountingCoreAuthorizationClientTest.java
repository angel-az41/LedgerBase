package com.ledgerbase.reporting.security;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

import java.util.OptionalLong;

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
 * EXISTING SECURITY REQUIREMENTS:
 *
 * - Only HTTP 204 authorizes reporting access.
 * - HTTP 401 and 403 deny access.
 * - Unexpected HTTP responses deny access.
 * - Redirects must not be followed.
 * - Connection failures must deny access.
 * - Invalid input must never trigger HTTP requests.
 *
 * REPORTING REVISION REQUIREMENTS:
 *
 * - Authorized HTTP 204 may carry a reporting revision.
 * - Revision zero is valid.
 * - Missing or malformed revision headers are rejected.
 * - Unauthorized responses must not expose valid revisions.
 * - Revision lookups must forward the existing Bearer token.
 * - Network failures must not produce fabricated revisions.
 *
 * BACKWARD COMPATIBILITY:
 *
 * hasReportingAccess() must continue accepting HTTP 204
 * without requiring a reporting revision header.
 *
 * The new getAuthorizedReportingRevision() method requires
 * both HTTP 204 and a valid revision header.
 *
 * No real database, JWT, or Accounting Core process
 * is required for these isolated transport tests.
 */
class AccountingCoreAuthorizationClientTest {

    // ========================================================
    // TEST CONSTANTS
    // ========================================================

    private static final Long BUSINESS_ID = 2L;

    private static final String TEST_ACCESS_TOKEN =
        "test-access-token";

    private static final String REPORTING_REVISION_HEADER =
        "X-LedgerBase-Reporting-Revision";

    // ========================================================
    // TEST HTTP SERVER
    // ========================================================

    private HttpServer server;

    private AccountingCoreAuthorizationClient client;

    // ========================================================
    // SIMULATED ACCOUNTING CORE RESPONSE
    // ========================================================

    private AtomicInteger responseStatus;

    private AtomicInteger requestCount;

    /**
     * Controls the reporting revision header returned
     * by the simulated Accounting Core.
     *
     * null means the response does not contain the header.
     *
     * We deliberately default to null to preserve coverage
     * of the original authorization-only HTTP contract.
     */
    private AtomicReference<String> responseRevisionHeader;

    // ========================================================
    // CAPTURE OUTGOING REQUEST DETAILS
    // ========================================================

    private AtomicReference<String> authorizationHeader;

    private AtomicReference<String> requestMethod;

    private AtomicBoolean redirectFollowed;

    // ========================================================
    // TEST HTTP SERVER SETUP
    // ========================================================

    @BeforeEach
    void setUp() throws IOException {

        // ====================================================
        // INITIALIZE RESPONSE CONFIGURATION
        // ====================================================

        responseStatus = new AtomicInteger(204);

        requestCount = new AtomicInteger(0);

        responseRevisionHeader =
            new AtomicReference<>(null);

        authorizationHeader = new AtomicReference<>();

        requestMethod = new AtomicReference<>();

        redirectFollowed = new AtomicBoolean(false);

        // ====================================================
        // START TEMPORARY LOCAL HTTP SERVER
        // ====================================================

        /*
         * Port zero instructs the operating system
         * to allocate an available localhost port.
         *
         * This avoids hard-coded ports and allows
         * independent test execution.
         */
        server = HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0),
            0
        );

        // ====================================================
        // SIMULATE ACCOUNTING CORE REPORTING ACCESS API
        // ====================================================

        server.createContext(
            "/api/v1/businesses/2/reporting-access",
            exchange -> {

                try {

                    // ----------------------------------------
                    // TRACK REQUEST COUNT
                    // ----------------------------------------

                    requestCount.incrementAndGet();

                    // ----------------------------------------
                    // CAPTURE AUTHENTICATION HEADER
                    // ----------------------------------------

                    authorizationHeader.set(
                        exchange.getRequestHeaders()
                            .getFirst("Authorization")
                    );

                    // ----------------------------------------
                    // CAPTURE HTTP METHOD
                    // ----------------------------------------

                    requestMethod.set(
                        exchange.getRequestMethod()
                    );

                    // ----------------------------------------
                    // READ SIMULATED RESPONSE STATUS
                    // ----------------------------------------

                    int status = responseStatus.get();

                    // ----------------------------------------
                    // SIMULATE HTTP REDIRECT
                    // ----------------------------------------

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

                    // ----------------------------------------
                    // SIMULATE REPORTING REVISION HEADER
                    // ----------------------------------------

                    /*
                     * The production Accounting Core now
                     * returns the authoritative revision in:
                     *
                     * X-LedgerBase-Reporting-Revision
                     *
                     * Include the header only when a test
                     * explicitly configures a revision.
                     *
                     * This allows us to test both the
                     * new revision contract and the older
                     * authorization-only response.
                     */
                    String revision =
                        responseRevisionHeader.get();

                    if (revision != null) {

                        exchange.getResponseHeaders().set(
                            REPORTING_REVISION_HEADER,
                            revision
                        );
                    }

                    // ----------------------------------------
                    // RETURN CONFIGURED HTTP RESPONSE
                    // ----------------------------------------

                    /*
                     * -1 means the HTTP response has
                     * no response body.
                     *
                     * This matches Accounting Core's
                     * 204 No Content contract.
                     */
                    exchange.sendResponseHeaders(
                        status,
                        -1
                    );

                } finally {

                    exchange.close();
                }
            }
        );

        // ====================================================
        // DETECT UNEXPECTED REDIRECT FOLLOWING
        // ====================================================

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

        // ====================================================
        // START HTTP SERVER
        // ====================================================

        server.start();

        // ====================================================
        // CONSTRUCT PRODUCTION AUTHORIZATION CLIENT
        // ====================================================

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
    // EXISTING TEST — AUTHORIZED RESPONSE
    // ========================================================

    @Test
    void http204GrantsReportingAccess() {

        assertTrue(
            client.hasReportingAccess(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            )
        );

        // Verify the existing JWT is forwarded.
        assertEquals(
            "Bearer " + TEST_ACCESS_TOKEN,
            authorizationHeader.get()
        );

        // The authorization check must use GET.
        assertEquals(
            "GET",
            requestMethod.get()
        );

        assertEquals(1, requestCount.get());

        /*
         * IMPORTANT BACKWARD COMPATIBILITY:
         *
         * There is no reporting revision header in
         * this simulated 204 response.
         *
         * hasReportingAccess() must still return true.
         */
        assertNull(responseRevisionHeader.get());
    }

    // ========================================================
    // EXISTING TEST — INVALID AUTHENTICATION
    // ========================================================

    @Test
    void http401DeniesReportingAccess() {

        responseStatus.set(401);

        assertFalse(
            client.hasReportingAccess(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            )
        );
    }

    // ========================================================
    // EXISTING TEST — MISSING BUSINESS PERMISSION
    // ========================================================

    @Test
    void http403DeniesReportingAccess() {

        responseStatus.set(403);

        assertFalse(
            client.hasReportingAccess(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            )
        );
    }

    // ========================================================
    // EXISTING TEST — UNEXPECTED SUCCESS STATUS
    // ========================================================

    @Test
    void http200DoesNotGrantAccess() {

        responseStatus.set(200);

        // Only HTTP 204 authorizes reporting access.
        assertFalse(
            client.hasReportingAccess(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            )
        );
    }

    // ========================================================
    // EXISTING TEST — REDIRECT PROTECTION
    // ========================================================

    @Test
    void http302RedirectIsRejected() {

        responseStatus.set(302);

        assertFalse(
            client.hasReportingAccess(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            )
        );

        // Bearer tokens must never be forwarded
        // to redirected destinations.
        assertFalse(
            redirectFollowed.get()
        );
    }

    // ========================================================
    // EXISTING TEST — ACCOUNTING CORE SERVICE FAILURE
    // ========================================================

    @Test
    void http503DeniesReportingAccess() {

        responseStatus.set(503);

        assertFalse(
            client.hasReportingAccess(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            )
        );
    }

    // ========================================================
    // EXISTING TEST — NETWORK FAILURE
    // ========================================================

    @Test
    void unavailableAccountingCoreDeniesAccess() {

        // Stop the simulated Accounting Core.
        server.stop(0);

        // Connection failures must never grant access.
        assertFalse(
            client.hasReportingAccess(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            )
        );
    }

    // ========================================================
    // EXISTING TEST — INVALID INPUT VALIDATION
    // ========================================================

    @Test
    void invalidInputsAreRejectedBeforeHttpRequest() {

        assertFalse(
            client.hasReportingAccess(
                null,
                TEST_ACCESS_TOKEN
            )
        );

        assertFalse(
            client.hasReportingAccess(
                0L,
                TEST_ACCESS_TOKEN
            )
        );

        assertFalse(
            client.hasReportingAccess(
                -1L,
                TEST_ACCESS_TOKEN
            )
        );

        assertFalse(
            client.hasReportingAccess(
                BUSINESS_ID,
                null
            )
        );

        assertFalse(
            client.hasReportingAccess(
                BUSINESS_ID,
                ""
            )
        );

        assertEquals(
            0,
            requestCount.get(),
            "Invalid inputs must not trigger HTTP requests"
        );
    }

    // ========================================================
    // NEW TEST — RETRIEVE AUTHORIZED REPORTING REVISION
    // ========================================================

    /**
     * Verify that Reporting Service accepts the revision
     * only when Accounting Core returns HTTP 204 with
     * a valid reporting revision header.
     *
     * The actual value must come from the HTTP response,
     * not a default or browser-provided value.
     */
    @Test
    void http204ReturnsAuthorizedReportingRevision() {

        // Simulate the current PostgreSQL revision.
        responseRevisionHeader.set("12");

        OptionalLong revision =
            client.getAuthorizedReportingRevision(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            );

        // The revision must be present.
        assertTrue(revision.isPresent());

        // Verify the actual value returned by Accounting Core.
        assertEquals(
            12L,
            revision.getAsLong()
        );

        // Verify authenticated JWT forwarding.
        assertEquals(
            "Bearer " + TEST_ACCESS_TOKEN,
            authorizationHeader.get()
        );

        assertEquals("GET", requestMethod.get());

        assertEquals(1, requestCount.get());
    }

    // ========================================================
    // NEW TEST — ZERO IS A VALID REPORTING REVISION
    // ========================================================

    /**
     * Newly created businesses begin with revision zero.
     *
     * OptionalLong.empty() means authorization or
     * revision retrieval failed.
     *
     * OptionalLong.of(0) means an authorized business
     * has a valid revision of zero.
     *
     * These two results must never be confused.
     */
    @Test
    void http204AcceptsReportingRevisionZero() {

        responseRevisionHeader.set("0");

        OptionalLong revision =
            client.getAuthorizedReportingRevision(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            );

        assertTrue(revision.isPresent());

        assertEquals(
            0L,
            revision.getAsLong()
        );
    }

    // ========================================================
    // NEW TEST — MISSING REVISION HEADER
    // ========================================================

    /**
     * HTTP 204 alone is sufficient for the existing
     * authorization method.
     *
     * It is NOT sufficient for revision-aware caching.
     *
     * A missing revision cannot be replaced with zero
     * because doing so could select stale Redis data.
     */
    @Test
    void http204WithoutRevisionHeaderFailsClosed() {

        // The mock server sends no revision header.
        responseRevisionHeader.set(null);

        OptionalLong revision =
            client.getAuthorizedReportingRevision(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            );

        assertTrue(
            revision.isEmpty(),
            "Missing revision must not be interpreted as zero"
        );

        // Preserve backward-compatible authorization.
        assertTrue(
            client.hasReportingAccess(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            )
        );
    }

    // ========================================================
    // NEW TEST — INVALID REPORTING REVISION VALUES
    // ========================================================

    /**
     * Reporting revisions must be nonnegative Long values.
     *
     * Invalid data from an upstream HTTP response must
     * never become an authoritative cache version.
     */
    @Test
    void malformedReportingRevisionIsRejected() {

        String[] invalidRevisions = {
            "not-a-number",
            "-1",
            "1.5",
            "9223372036854775808"
        };

        for (String invalidRevision : invalidRevisions) {

            // Configure malformed upstream response.
            responseRevisionHeader.set(invalidRevision);

            OptionalLong revision =
                client.getAuthorizedReportingRevision(
                    BUSINESS_ID,
                    TEST_ACCESS_TOKEN
                );

            assertTrue(
                revision.isEmpty(),
                "Malformed revision must be rejected: "
                    + invalidRevision
            );
        }
    }

    // ========================================================
    // NEW TEST — UNAUTHORIZED RESPONSES CANNOT SUPPLY REVISION
    // ========================================================

    /**
     * A response header alone is not proof of authorization.
     *
     * The Reporting Service must require HTTP 204 before
     * accepting any reported revision.
     */
    @Test
    void unauthorizedResponsesCannotSupplyReportingRevision() {

        int[] invalidStatuses = {
            200,
            302,
            401,
            403,
            404,
            503
        };

        // Include a plausible revision in every response.
        responseRevisionHeader.set("99");

        for (int status : invalidStatuses) {

            responseStatus.set(status);

            OptionalLong revision =
                client.getAuthorizedReportingRevision(
                    BUSINESS_ID,
                    TEST_ACCESS_TOKEN
                );

            assertTrue(
                revision.isEmpty(),
                "HTTP " + status
                    + " must not provide an authorized revision"
            );
        }

        // Even the revision method must not follow redirects.
        assertFalse(
            redirectFollowed.get()
        );
    }

    // ========================================================
    // NEW TEST — REVISION LOOKUP NETWORK FAILURE
    // ========================================================

    /**
     * If Accounting Core becomes unavailable, Reporting
     * Service cannot safely determine the latest
     * committed reporting revision.
     *
     * Returning an empty value ensures future Redis
     * caching can fail closed rather than serve data
     * under an invented cache version.
     */
    @Test
    void unavailableAccountingCoreCannotSupplyRevision() {

        responseRevisionHeader.set("12");

        // Shut down the simulated Accounting Core.
        server.stop(0);

        OptionalLong revision =
            client.getAuthorizedReportingRevision(
                BUSINESS_ID,
                TEST_ACCESS_TOKEN
            );

        assertTrue(
            revision.isEmpty(),
            "A network failure must not produce a revision"
        );
    }

    // ========================================================
    // NEW TEST — REVISION LOOKUP INVALID INPUTS
    // ========================================================

    /**
     * Invalid business IDs and JWT inputs must be rejected
     * locally before issuing any HTTP request.
     */
    @Test
    void invalidRevisionInputsAreRejectedBeforeHttpRequest() {

        assertTrue(
            client.getAuthorizedReportingRevision(
                null,
                TEST_ACCESS_TOKEN
            ).isEmpty()
        );

        assertTrue(
            client.getAuthorizedReportingRevision(
                0L,
                TEST_ACCESS_TOKEN
            ).isEmpty()
        );

        assertTrue(
            client.getAuthorizedReportingRevision(
                -1L,
                TEST_ACCESS_TOKEN
            ).isEmpty()
        );

        assertTrue(
            client.getAuthorizedReportingRevision(
                BUSINESS_ID,
                null
            ).isEmpty()
        );

        assertTrue(
            client.getAuthorizedReportingRevision(
                BUSINESS_ID,
                ""
            ).isEmpty()
        );

        // No outbound authorization request is allowed.
        assertEquals(
            0,
            requestCount.get()
        );
    }
}
