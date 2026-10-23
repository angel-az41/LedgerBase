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
 * HTTP test client
 *       |
 *       v
 * Spring Cloud Gateway (random port)
 *       |
 *       v
 * Local mock backend (random port)
 *
 * The gateway runs in a real embedded web server.
 *
 * The mock backend uses Java's built-in HttpServer.
 *
 * This test does not require:
 *
 * - The existing LedgerBase backend
 * - PostgreSQL
 * - Docker
 * - Authentication credentials
 *
 * COVERAGE:
 *
 * - Independent gateway health
 * - Downstream API routing
 * - Unauthorized response propagation
 * - Authorization header forwarding
 *
 * IMPORTANT:
 *
 * The bearer token used in this test is synthetic.
 * The mock backend checks header forwarding only.
 * Actual JWT validation remains the responsibility
 * of the accounting backend's security tests.
 */

// ============================================================
// SPRING BOOT INTEGRATION TEST CONFIGURATION
// ============================================================

/**
 * Start the actual Spring Cloud Gateway application
 * using an automatically assigned HTTP port.
 *
 * Explicitly selecting GatewayApplication prevents
 * ambiguity when loading the application context.
 *
 * Tests use a mock backend instead of the real
 * LedgerBase accounting backend.
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
     * Send real HTTP requests to the running gateway.
     */
    private static final HttpClient CLIENT =
        HttpClient.newHttpClient();

    // ========================================================
    // MOCK BACKEND SERVER
    // ========================================================

    /**
     * Start the mock backend before Spring initializes
     * the gateway's route configuration.
     */
    private static final HttpServer MOCK_BACKEND =
        startMockBackend();

    // ========================================================
    // GATEWAY HTTP PORT
    // ========================================================

    /**
     * Spring Boot selects a free port for testing.
     *
     * This prevents conflicts with the local gateway
     * running on port 8081.
     */
    @LocalServerPort
    private int gatewayPort;

    // ========================================================
    // DYNAMIC GATEWAY ROUTING CONFIGURATION
    // ========================================================

    /**
     * Point the gateway's existing route to our mock
     * backend instead of localhost:8080.
     *
     * application.yaml already contains:
     *
     * ${LEDGERBASE_CORE_URL:http://127.0.0.1:8080}
     *
     * The dynamic test property overrides that URL.
     */
    @DynamicPropertySource
    static void configureGateway(
            DynamicPropertyRegistry registry) {

        registry.add(
            "LEDGERBASE_CORE_URL",
            () -> "http://127.0.0.1:"
                + MOCK_BACKEND.getAddress().getPort()
        );
    }

    // ========================================================
    // INITIALIZE MOCK BACKEND
    // ========================================================

    /**
     * Create a lightweight HTTP server representing
     * the existing LedgerBase accounting backend.
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
             * If the gateway returns this JSON,
             * the request reached the mock backend
             * without requiring PostgreSQL.
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
             * Simulate backend authorization.
             *
             * Missing or unexpected bearer headers
             * produce HTTP 401.
             *
             * The expected test header produces 200.
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
            // START MOCK BACKEND
            // ================================================

            server.start();

            return server;

        } catch (IOException exception) {

            throw new IllegalStateException(
                "Failed to start mock backend",
                exception
            );
        }
    }

    // ========================================================
    // MOCK HTTP RESPONSE HELPER
    // ========================================================

    /**
     * Write a JSON response with an explicit HTTP status.
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
     * Send a GET request through the actual gateway.
     *
     * @param path requested gateway API path
     * @param authorization optional bearer header
     * @return complete HTTP response
     */
    private HttpResponse<String> get(
            String path,
            String authorization
    ) throws IOException, InterruptedException {

        URI uri = URI.create(
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
     * The gateway should expose its own health
     * endpoint independently of the backend.
     */
    @Test
    void gatewayHealthReturnsUp() throws Exception {

        HttpResponse<String> response =
            get("/actuator/health", null);

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
    // TEST 2 — DOWNSTREAM API ROUTING
    // ========================================================

    /**
     * Verify that the gateway forwards /api/v1/**
     * requests to the configured backend.
     *
     * The distinctive mock database value proves
     * the response came from the test server.
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
    // TEST 3 — UNAUTHORIZED RESPONSE
    // ========================================================

    /**
     * Requests without a bearer token must receive
     * the backend's unauthorized response.
     *
     * The gateway must not bypass backend security.
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
    // TEST 4 — AUTHORIZATION HEADER FORWARDING
    // ========================================================

    /**
     * Verify that the gateway forwards the original
     * Authorization header to the backend.
     *
     * This is required for the existing JWT/RBAC
     * implementation to work behind the gateway.
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
    // CLEANUP
    // ========================================================

    /**
     * Stop the temporary backend after the tests.
     *
     * No application data or Docker resources
     * are modified.
     */
    @AfterAll
    static void stopMockBackend() {

        MOCK_BACKEND.stop(0);
    }
}
