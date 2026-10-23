package com.ledgerbase.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;

import java.net.InetSocketAddress;
import java.net.URI;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — API GATEWAY INTEGRATION TESTS
// ============================================================

/**
 * Verify HTTP routing through Spring Cloud Gateway.
 *
 * TEST ARCHITECTURE:
 *
 * HTTP Test Client
 *        |
 *        v
 * Spring Cloud Gateway (random port)
 *        |
 *        +----> Mock Accounting Core (random port)
 *        |
 *        +----> Mock Reporting Service (random port)
 *
 * The Gateway runs in a real embedded web server.
 *
 * Both downstream services use Java's built-in HttpServer.
 *
 * These tests do not require:
 *
 * - Running Accounting Core
 * - Running Reporting Service
 * - PostgreSQL
 * - Docker
 * - Real authentication credentials
 *
 * COVERAGE:
 *
 * - Independent Gateway health monitoring
 * - Accounting Core API routing
 * - Unauthorized response propagation
 * - Authorization header forwarding
 * - Trial Balance routing to Reporting Service
 * - Reporting Service authentication responses
 * - Business authorization denial propagation
 * - Preservation of unmigrated accounting routes
 *
 * SECURITY:
 *
 * The Bearer token used in these tests is synthetic.
 *
 * These tests verify Gateway routing and forwarding,
 * not JWT cryptographic verification.
 *
 * Actual authentication and authorization remain
 * responsibilities of the downstream services.
 */
@SpringBootTest(
    classes = GatewayApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class GatewayRoutingIntegrationTest {

    // ========================================================
    // TEST HTTP CLIENT
    // ========================================================

    /**
     * Send HTTP requests through the running Gateway.
     *
     * The Gateway itself is started by Spring Boot
     * using an automatically assigned local port.
     */
    private static final HttpClient CLIENT =
        HttpClient.newHttpClient();


    // ========================================================
    // MOCK ACCOUNTING CORE
    // ========================================================

    /**
     * Independent mock Accounting Core.
     *
     * Handles the existing accounting and authentication
     * API routes.
     *
     * Started before Spring initializes Gateway routing.
     */
    private static final HttpServer MOCK_BACKEND =
        startMockBackend();


    // ========================================================
    // MOCK FINANCIAL REPORTING SERVICE
    // ========================================================

    /**
     * Independent mock Reporting Service.
     *
     * Uses a different HTTP port from Accounting Core.
     *
     * This allows tests to detect incorrect routing
     * between the two downstream services.
     */
    private static final HttpServer MOCK_REPORTING =
        startMockReporting();


    // ========================================================
    // GATEWAY HTTP PORT
    // ========================================================

    /**
     * Spring Boot automatically assigns a free port.
     *
     * This prevents conflicts with the development
     * Gateway normally running on port 8081.
     */
    @LocalServerPort
    private int gatewayPort;


    // ========================================================
    // DYNAMIC GATEWAY ROUTING CONFIGURATION
    // ========================================================

    /**
     * Override the real downstream service addresses.
     *
     * application.yaml contains:
     *
     * ${LEDGERBASE_CORE_URL:http://127.0.0.1:8080}
     *
     * ${LEDGERBASE_REPORTING_URL:http://127.0.0.1:8082}
     *
     * Tests replace these values with the temporary
     * mock servers' dynamically allocated ports.
     *
     * This means no production or local-development
     * services are contacted during these tests.
     */
    @DynamicPropertySource
    static void configureGateway(
        DynamicPropertyRegistry registry
    ) {

        // ----------------------------------------------------
        // ACCOUNTING CORE DESTINATION
        // ----------------------------------------------------

        registry.add(
            "LEDGERBASE_CORE_URL",
            () -> "http://127.0.0.1:"
                + MOCK_BACKEND.getAddress().getPort()
        );

        // ----------------------------------------------------
        // REPORTING SERVICE DESTINATION
        // ----------------------------------------------------

        registry.add(
            "LEDGERBASE_REPORTING_URL",
            () -> "http://127.0.0.1:"
                + MOCK_REPORTING.getAddress().getPort()
        );
    }


    // ========================================================
    // INITIALIZE MOCK ACCOUNTING CORE
    // ========================================================

    /**
     * Create a lightweight HTTP server representing
     * the existing LedgerBase Accounting Core.
     *
     * The mock implements:
     *
     * - Database health check
     * - Protected accounts endpoint
     * - Business-scoped API fallback
     *
     * A distinctive response identifier allows tests
     * to verify that Gateway selected Accounting Core.
     */
    private static HttpServer startMockBackend() {

        try {

            HttpServer server =
                HttpServer.create(
                    new InetSocketAddress(
                        "127.0.0.1",
                        0
                    ),
                    0
                );

            // ================================================
            // DATABASE HEALTH ENDPOINT
            // ================================================

            /**
             * Return a distinctive mock response.
             *
             * This proves ordinary accounting routes
             * continue reaching Accounting Core.
             */
            server.createContext(
                "/api/v1/health/database",
                exchange -> sendJson(
                    exchange,
                    200,
                    "{\"database\":\"mock-backend\","
                        + "\"status\":\"connected\"}"
                )
            );


            // ================================================
            // PROTECTED ACCOUNTS ENDPOINT
            // ================================================

            /**
             * Simulate Accounting Core JWT enforcement.
             *
             * Expected synthetic Bearer token:
             *
             * Bearer gateway-test-token
             *
             * Missing or incorrect tokens return 401.
             */
            server.createContext(
                "/api/v1/accounts",
                exchange -> {

                    String authorization =
                        exchange.getRequestHeaders()
                            .getFirst("Authorization");

                    if (
                        "Bearer gateway-test-token"
                            .equals(authorization)
                    ) {

                        sendJson(
                            exchange,
                            200,
                            "{\"authorized\":true}"
                        );

                    } else {

                        exchange.getResponseHeaders().set(
                            "WWW-Authenticate",
                            "Bearer"
                        );

                        sendJson(
                            exchange,
                            401,
                            "{\"error\":\"Unauthorized\"}"
                        );
                    }
                }
            );


            // ================================================
            // BUSINESS API FALLBACK
            // ================================================

            /**
             * Return a distinctive Accounting Core response
             * for other business-scoped API requests.
             *
             * The response is intentionally different
             * from Reporting Service's response.
             *
             * If Trial Balance accidentally reaches the
             * general /api/v1/** route, our reporting test
             * will detect the incorrect destination.
             *
             * This is a routing mock, not a real
             * authorization implementation.
             */
            server.createContext(
                "/api/v1/businesses/",
                exchange -> sendJson(
                    exchange,
                    200,
                    "{\"service\":\"mock-core\"}"
                )
            );


            // ================================================
            // START MOCK ACCOUNTING CORE
            // ================================================

            server.start();

            return server;

        } catch (IOException exception) {

            throw new IllegalStateException(
                "Failed to start mock Accounting Core",
                exception
            );
        }
    }


    // ========================================================
    // INITIALIZE MOCK REPORTING SERVICE
    // ========================================================

    /**
     * Create an independent HTTP server representing
     * LedgerBase Reporting Service.
     *
     * SIMULATED ENDPOINT:
     *
     * GET /api/v1/businesses/{businessId}/reports/trial-balance
     *
     * SIMULATED RESPONSES:
     *
     * 200:
     *   Valid synthetic token and authorized business.
     *
     * 401:
     *   Missing or incorrect token.
     *
     * 403:
     *   Inaccessible business.
     *
     * 400:
     *   Incorrect reporting date parameter.
     *
     * 404:
     *   Unknown reporting endpoint.
     *
     * The mock does not execute SQL or validate JWTs.
     * It verifies that Gateway preserves the request
     * and forwards it to the correct downstream service.
     */
    private static HttpServer startMockReporting() {

        try {

            HttpServer server =
                HttpServer.create(
                    new InetSocketAddress(
                        "127.0.0.1",
                        0
                    ),
                    0
                );

            // ================================================
            // FINANCIAL REPORTING ENDPOINT
            // ================================================

            server.createContext(
                "/api/v1/businesses/",
                exchange -> {

                    // ----------------------------------------
                    // IDENTIFY REQUEST PATH
                    // ----------------------------------------

                    String path =
                        exchange.getRequestURI().getPath();

                    boolean authorizedBusiness =
                        path.equals(
                            "/api/v1/businesses/2/"
                                + "reports/trial-balance"
                        );

                    boolean inaccessibleBusiness =
                        path.equals(
                            "/api/v1/businesses/999999/"
                                + "reports/trial-balance"
                        );

                    // ----------------------------------------
                    // REJECT UNKNOWN REPORTING PATHS
                    // ----------------------------------------

                    if (
                        !authorizedBusiness
                        && !inaccessibleBusiness
                    ) {

                        sendJson(
                            exchange,
                            404,
                            "{\"service\":\"mock-reporting\","
                                + "\"error\":\"Not Found\"}"
                        );

                        return;
                    }


                    // ----------------------------------------
                    // REQUIRE HTTP GET
                    // ----------------------------------------

                    if (
                        !"GET".equals(
                            exchange.getRequestMethod()
                        )
                    ) {

                        sendJson(
                            exchange,
                            405,
                            "{\"service\":\"mock-reporting\","
                                + "\"error\":\"Method Not Allowed\"}"
                        );

                        return;
                    }


                    // ----------------------------------------
                    // VERIFY AUTHORIZATION HEADER
                    // ----------------------------------------

                    /**
                     * The Gateway must forward the
                     * original Bearer token unchanged.
                     *
                     * This mock recognizes one synthetic
                     * token for integration testing.
                     */
                    String authorization =
                        exchange.getRequestHeaders()
                            .getFirst("Authorization");

                    if (
                        !"Bearer gateway-test-token"
                            .equals(authorization)
                    ) {

                        exchange.getResponseHeaders().set(
                            "WWW-Authenticate",
                            "Bearer"
                        );

                        sendJson(
                            exchange,
                            401,
                            "{\"service\":\"mock-reporting\","
                                + "\"error\":\"Unauthorized\"}"
                        );

                        return;
                    }


                    // ----------------------------------------
                    // REJECT INACCESSIBLE BUSINESS
                    // ----------------------------------------

                    /**
                     * Simulate downstream business-scoped
                     * authorization enforcement.
                     *
                     * Gateway must propagate HTTP 403
                     * without changing it to 200.
                     */
                    if (inaccessibleBusiness) {

                        sendJson(
                            exchange,
                            403,
                            "{\"service\":\"mock-reporting\","
                                + "\"error\":\"Forbidden\"}"
                        );

                        return;
                    }


                    // ----------------------------------------
                    // VERIFY REPORTING DATE PARAMETER
                    // ----------------------------------------

                    /**
                     * The Gateway must preserve the
                     * original asOfDate query parameter.
                     *
                     * Missing or modified parameters
                     * produce a distinctive error.
                     */
                    String query =
                        exchange.getRequestURI()
                            .getRawQuery();

                    if (
                        !"asOfDate=2026-10-23".equals(query)
                    ) {

                        sendJson(
                            exchange,
                            400,
                            "{\"service\":\"mock-reporting\","
                                + "\"error\":\"Invalid date\"}"
                        );

                        return;
                    }


                    // ----------------------------------------
                    // RETURN SUCCESSFUL MOCK REPORT
                    // ----------------------------------------

                    /**
                     * The service identifier proves that
                     * the request reached Reporting Service
                     * instead of Accounting Core.
                     */
                    sendJson(
                        exchange,
                        200,
                        "{\"service\":\"mock-reporting\","
                            + "\"query\":\"asOfDate=2026-10-23\","
                            + "\"authorized\":true}"
                    );
                }
            );


            // ================================================
            // START MOCK REPORTING SERVICE
            // ================================================

            server.start();

            return server;

        } catch (IOException exception) {

            throw new IllegalStateException(
                "Failed to start mock Reporting Service",
                exception
            );
        }
    }


    // ========================================================
    // MOCK HTTP RESPONSE HELPER
    // ========================================================

    /**
     * Write a JSON response with an explicit HTTP status.
     *
     * Both mock services reuse this helper so that
     * downstream responses have consistent formatting.
     *
     * @param exchange incoming HTTP exchange
     * @param status response status code
     * @param json JSON response body
     */
    private static void sendJson(
        HttpExchange exchange,
        int status,
        String json
    ) throws IOException {

        byte[] body =
            json.getBytes(
                StandardCharsets.UTF_8
            );

        exchange.getResponseHeaders().set(
            "Content-Type",
            "application/json"
        );

        exchange.sendResponseHeaders(
            status,
            body.length
        );

        try (
            var output =
                exchange.getResponseBody()
        ) {

            output.write(body);
        }

        exchange.close();
    }


    // ========================================================
    // HTTP REQUEST HELPER
    // ========================================================

    /**
     * Send a GET request through the actual Gateway.
     *
     * The caller may supply a synthetic Bearer token
     * or null for an unauthenticated request.
     *
     * @param path requested Gateway API path
     * @param authorization optional Authorization header
     * @return complete HTTP response
     */
    private HttpResponse<String> get(
        String path,
        String authorization
    ) throws IOException, InterruptedException {

        URI uri =
            URI.create(
                "http://127.0.0.1:"
                    + gatewayPort
                    + path
            );

        HttpRequest.Builder builder =
            HttpRequest.newBuilder()
                .uri(uri)
                .GET();

        if (authorization != null) {

            builder.header(
                "Authorization",
                authorization
            );
        }

        return CLIENT.send(
            builder.build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }


    // ========================================================
    // TEST 1 — GATEWAY HEALTH
    // ========================================================

    /**
     * The Gateway must expose its own health endpoint
     * independently of Accounting Core and Reporting Service.
     */
    @Test
    void gatewayHealthReturnsUp()
            throws Exception {

        HttpResponse<String> response =
            get(
                "/actuator/health",
                null
            );

        assertEquals(
            200,
            response.statusCode()
        );

        assertTrue(
            response.body().contains(
                "\"status\":\"UP\""
            )
        );
    }


    // ========================================================
    // TEST 2 — ACCOUNTING CORE API ROUTING
    // ========================================================

    /**
     * Verify ordinary /api/v1/** requests reach
     * Accounting Core.
     *
     * The distinctive mock database identifier
     * proves which downstream service responded.
     */
    @Test
    void gatewayForwardsDatabaseHealthRequest()
            throws Exception {

        HttpResponse<String> response =
            get(
                "/api/v1/health/database",
                null
            );

        assertEquals(
            200,
            response.statusCode()
        );

        assertTrue(
            response.body().contains(
                "\"database\":\"mock-backend\""
            )
        );
    }


    // ========================================================
    // TEST 3 — UNAUTHORIZED ACCOUNTING RESPONSE
    // ========================================================

    /**
     * Requests without an access token must receive
     * Accounting Core's HTTP 401 response.
     *
     * The Gateway must not bypass backend security.
     */
    @Test
    void gatewayPreservesUnauthorizedResponse()
            throws Exception {

        HttpResponse<String> response =
            get(
                "/api/v1/accounts",
                null
            );

        assertEquals(
            401,
            response.statusCode()
        );
    }


    // ========================================================
    // TEST 4 — ACCOUNTING AUTHORIZATION HEADER
    // ========================================================

    /**
     * Verify the Gateway forwards the Authorization
     * header unchanged to Accounting Core.
     *
     * This preserves the existing JWT authentication
     * and business-scoped RBAC implementation.
     */
    @Test
    void gatewayForwardsAuthorizationHeader()
            throws Exception {

        HttpResponse<String> response =
            get(
                "/api/v1/accounts",
                "Bearer gateway-test-token"
            );

        assertEquals(
            200,
            response.statusCode()
        );

        assertTrue(
            response.body().contains(
                "\"authorized\":true"
            )
        );
    }


    // ========================================================
    // TEST 5 — TRIAL BALANCE REPORTING ROUTE
    // ========================================================

    /**
     * Verify the Trial Balance request reaches the
     * independent Reporting Service.
     *
     * The specific reporting route must take precedence
     * over Accounting Core's /api/v1/** fallback.
     *
     * Also verify:
     *
     * - Authorization header forwarding.
     * - asOfDate query parameter forwarding.
     * - Successful response propagation.
     * - Correct downstream service selection.
     */
    @Test
    void gatewayRoutesTrialBalanceToReportingService()
            throws Exception {

        HttpResponse<String> response =
            get(
                "/api/v1/businesses/2/reports/trial-balance"
                    + "?asOfDate=2026-10-23",
                "Bearer gateway-test-token"
            );

        assertEquals(
            200,
            response.statusCode()
        );

        // Confirm Reporting Service handled the request.
        assertTrue(
            response.body().contains(
                "\"service\":\"mock-reporting\""
            )
        );

        // Confirm the reporting date survived routing.
        assertTrue(
            response.body().contains(
                "\"query\":\"asOfDate=2026-10-23\""
            )
        );

        // Confirm the mock accepted the forwarded token.
        assertTrue(
            response.body().contains(
                "\"authorized\":true"
            )
        );
    }


    // ========================================================
    // TEST 6 — REPORTING UNAUTHORIZED RESPONSE
    // ========================================================

    /**
     * Verify a Trial Balance request without a JWT
     * reaches Reporting Service and returns HTTP 401.
     *
     * The Gateway must not silently route the request
     * to Accounting Core instead.
     */
    @Test
    void gatewayPreservesReportingUnauthorizedResponse()
            throws Exception {

        HttpResponse<String> response =
            get(
                "/api/v1/businesses/2/reports/trial-balance"
                    + "?asOfDate=2026-10-23",
                null
            );

        assertEquals(
            401,
            response.statusCode()
        );

        assertTrue(
            response.body().contains(
                "\"service\":\"mock-reporting\""
            )
        );
    }


    // ========================================================
    // TEST 7 — BUSINESS AUTHORIZATION DENIAL
    // ========================================================

    /**
     * Verify a business-scoped reporting request
     * rejected by Reporting Service preserves HTTP 403.
     *
     * A valid Bearer token alone does not guarantee
     * access to the requested business.
     */
    @Test
    void gatewayPreservesReportingForbiddenResponse()
            throws Exception {

        HttpResponse<String> response =
            get(
                "/api/v1/businesses/999999/reports/trial-balance"
                    + "?asOfDate=2026-10-23",
                "Bearer gateway-test-token"
            );

        assertEquals(
            403,
            response.statusCode()
        );

        assertTrue(
            response.body().contains(
                "\"service\":\"mock-reporting\""
            )
        );
    }


    // ========================================================
    // TEST 8 — UNMIGRATED REPORTS STAY ON ACCOUNTING CORE
    // ========================================================

    /**
     * Verify other financial reports still reach
     * Accounting Core.
     *
     * Only Trial Balance has been extracted.
     *
     * Income Statement and Balance Sheet remain
     * Accounting Core responsibilities for now.
     */
    @Test
    void gatewayKeepsOtherReportsOnAccountingCore()
            throws Exception {

        HttpResponse<String> response =
            get(
                "/api/v1/businesses/2/reports/income-statement",
                "Bearer gateway-test-token"
            );

        assertEquals(
            200,
            response.statusCode()
        );

        assertTrue(
            response.body().contains(
                "\"service\":\"mock-core\""
            )
        );
    }


    // ========================================================
    // CLEANUP
    // ========================================================

    /**
     * Stop both temporary downstream servers
     * after the integration tests complete.
     *
     * No accounting records, real service processes,
     * or Docker resources are modified.
     */
    @AfterAll
    static void stopMockServers() {

        MOCK_BACKEND.stop(0);
        MOCK_REPORTING.stop(0);
    }
}
