package com.ledgerbase.reporting.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockHttpServletRequest;

import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — REPORTING AUTHORIZATION UNIT TESTS
// ============================================================

/**
 * Tests the Reporting Service's business-scoped
 * authorization decisions.
 *
 * SECURITY SCENARIOS:
 *
 * - Approved business access is permitted.
 * - Rejected business access is denied.
 * - Missing authentication is denied.
 * - Non-JWT authentication is denied.
 * - Invalid identifiers are denied.
 * - Non-GET requests are denied.
 * - Failed Accounting Core authorization is denied.
 *
 * AccountingCoreAuthorizationClient is mocked.
 *
 * These tests exercise authorization decisions;
 * they do not verify JWT cryptography or HTTP transport.
 */
class ReportingBusinessAuthorizationManagerTest {

    private AccountingCoreAuthorizationClient client;

    private ReportingBusinessAuthorizationManager manager;

    // ========================================================
    // TEST SETUP
    // ========================================================

    @BeforeEach
    void setUp() {

        client = mock(
            AccountingCoreAuthorizationClient.class
        );

        manager = new ReportingBusinessAuthorizationManager(
            client
        );
    }

    // ========================================================
    // AUTHORIZED BUSINESS ACCESS
    // ========================================================

    @Test
    void authorizedBusinessAccessIsAllowed() {

        when(client.hasReportingAccess(
            2L,
            "test-access-token"
        )).thenReturn(true);

        boolean authorized = authorize(
            jwtAuthentication("42"),
            "GET",
            "2"
        );

        assertTrue(authorized);

        verify(client).hasReportingAccess(
            2L,
            "test-access-token"
        );
    }

    // ========================================================
    // UNAUTHORIZED BUSINESS ACCESS
    // ========================================================

    @Test
    void unauthorizedBusinessAccessIsDenied() {

        when(client.hasReportingAccess(
            2L,
            "test-access-token"
        )).thenReturn(false);

        assertFalse(
            authorize(
                jwtAuthentication("42"),
                "GET",
                "2"
            )
        );
    }

    // ========================================================
    // MISSING AUTHENTICATION
    // ========================================================

    @Test
    void missingAuthenticationIsDenied() {

        assertFalse(
            authorize(null, "GET", "2")
        );

        verifyNoInteractions(client);
    }

    // ========================================================
    // REJECT NON-JWT AUTHENTICATION
    // ========================================================

    @Test
    void nonJwtAuthenticationIsDenied() {

        Authentication authentication =
            new TestingAuthenticationToken(
                "user",
                "credentials",
                "ROLE_USER"
            );

        assertFalse(
            authorize(authentication, "GET", "2")
        );

        verifyNoInteractions(client);
    }

    // ========================================================
    // INVALID BUSINESS IDENTIFIERS
    // ========================================================

    @Test
    void invalidBusinessIdentifiersAreDenied() {

        Authentication authentication =
            jwtAuthentication("42");

        assertFalse(
            authorize(authentication, "GET", "abc")
        );

        assertFalse(
            authorize(authentication, "GET", "0")
        );

        assertFalse(
            authorize(authentication, "GET", "-1")
        );

        assertFalse(
            authorize(authentication, "GET", null)
        );

        verifyNoInteractions(client);
    }

    // ========================================================
    // INVALID JWT SUBJECT
    // ========================================================

    @Test
    void invalidJwtSubjectIsDenied() {

        assertFalse(
            authorize(
                jwtAuthentication("invalid-user"),
                "GET",
                "2"
            )
        );

        verifyNoInteractions(client);
    }

    // ========================================================
    // READ-ONLY REPORTING ENFORCEMENT
    // ========================================================

    @Test
    void writeRequestsAreDenied() {

        Authentication authentication =
            jwtAuthentication("42");

        assertFalse(
            authorize(authentication, "POST", "2")
        );

        assertFalse(
            authorize(authentication, "PUT", "2")
        );

        assertFalse(
            authorize(authentication, "DELETE", "2")
        );

        verifyNoInteractions(client);
    }

    // ========================================================
    // FAILED ACCOUNTING CORE AUTHORIZATION
    // ========================================================

    @Test
    void rejectedAccountingCoreCheckIsDenied() {

        // The HTTP client must translate unavailable
        // Accounting Core responses into false.
        //
        // Here we verify that the authorization manager
        // never converts that false result into access.

        when(client.hasReportingAccess(
            2L,
            "test-access-token"
        )).thenReturn(false);

        assertFalse(
            authorize(
                jwtAuthentication("42"),
                "GET",
                "2"
            )
        );
    }

    // ========================================================
    // TEST AUTHENTICATION FACTORY
    // ========================================================

    /**
     * Construct an authenticated JWT principal
     * for isolated authorization-manager tests.
     *
     * This token is synthetic and not cryptographically
     * signed. Signature validation is tested separately.
     */
    private Authentication jwtAuthentication(
        String subject
    ) {

        Instant now = Instant.now();

        Jwt jwt = Jwt.withTokenValue("test-access-token")
            .header("alg", "HS256")
            .subject(subject)
            .claim(
                "sid",
                "123e4567-e89b-12d3-a456-426614174000"
            )
            .issuedAt(now)
            .expiresAt(now.plusSeconds(900))
            .build();

        // Simulate a successfully authenticated JWT.
        //
        // The two-argument constructor marks the token as
        // authenticated, matching Spring Security's behavior
        // after successful JWT verification.
        //
        // No role authorities are necessary here because
        // business permissions are verified through
        // Accounting Core instead of JWT role claims.
        return new JwtAuthenticationToken(
            jwt,
            java.util.List.of()
        );
    }

    // ========================================================
    // AUTHORIZATION TEST HELPER
    // ========================================================

    /**
     * Simulate a business-scoped HTTP request.
     *
     * Extracted businessId values are supplied
     * exactly as Spring Security's URL matcher
     * would provide them.
     */
    private boolean authorize(
        Authentication authentication,
        String method,
        String businessId
    ) {

        MockHttpServletRequest request =
            new MockHttpServletRequest(
                method,
                "/api/v1/businesses/" +
                    businessId +
                    "/reports/trial-balance"
            );

        Map<String, String> variables =
            businessId == null
                ? Map.of()
                : Map.of("businessId", businessId);

        RequestAuthorizationContext context =
            new RequestAuthorizationContext(
                request,
                variables
            );

        AuthorizationResult result =
            manager.authorize(
                () -> authentication,
                context
            );

        return result != null && result.isGranted();
    }
}
