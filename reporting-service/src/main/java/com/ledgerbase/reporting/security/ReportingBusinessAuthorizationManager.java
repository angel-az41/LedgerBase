package com.ledgerbase.reporting.security;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

// ============================================================
// LEDGERBASE — REPORTING BUSINESS AUTHORIZATION
// ============================================================

/**
 * Enforces business-scoped financial reporting access.
 *
 * AUTHENTICATION:
 *
 * - Require an authenticated Spring Security JWT.
 * - Extract identity exclusively from the verified JWT.
 *
 * AUTHORIZATION:
 *
 * - Require a valid business identifier.
 * - Permit only HTTP GET report requests.
 * - Delegate session and membership checks to
 *   Accounting Core.
 *
 * Accepted roles are determined by Accounting Core:
 *
 * OWNER, ADMIN, ACCOUNTANT, VIEWER.
 *
 * SECURITY:
 *
 * Missing authentication, malformed identifiers,
 * unsupported methods, and unavailable Accounting Core
 * all result in authorization denial.
 */
@Component
public class ReportingBusinessAuthorizationManager
    implements AuthorizationManager<RequestAuthorizationContext> {

    private final AccountingCoreAuthorizationClient
        authorizationClient;

    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public ReportingBusinessAuthorizationManager(
        AccountingCoreAuthorizationClient authorizationClient
    ) {

        this.authorizationClient = authorizationClient;
    }

    // ========================================================
    // AUTHORIZE REPORT REQUEST
    // ========================================================

    @Override
    public AuthorizationResult authorize(
        Supplier<? extends Authentication> authenticationSupplier,
        RequestAuthorizationContext context
    ) {

        // ----------------------------------------------------
        // REQUIRE VERIFIED JWT AUTHENTICATION
        // ----------------------------------------------------

        Authentication authentication =
            authenticationSupplier.get();

        if (
            !(authentication instanceof JwtAuthenticationToken jwtAuth)
            || !authentication.isAuthenticated()
        ) {
            return deny();
        }

        // ----------------------------------------------------
        // REQUIRE READ-ONLY HTTP OPERATION
        // ----------------------------------------------------

        if (!"GET".equals(context.getRequest().getMethod())) {
            return deny();
        }

        // ----------------------------------------------------
        // VALIDATE AUTHENTICATED USER ID
        // ----------------------------------------------------

        String subject =
            jwtAuth.getToken().getSubject();

        if (parsePositiveId(subject) == null) {
            return deny();
        }

        // ----------------------------------------------------
        // VALIDATE REQUESTED BUSINESS ID
        // ----------------------------------------------------

        String rawBusinessId =
            context.getVariables().get("businessId");

        Long businessId =
            parsePositiveId(rawBusinessId);

        if (businessId == null) {
            return deny();
        }

        // ----------------------------------------------------
        // VERIFY CURRENT BUSINESS PERMISSIONS
        // ----------------------------------------------------

        String accessToken =
            jwtAuth.getToken().getTokenValue();

        boolean authorized =
            authorizationClient.hasReportingAccess(
                businessId,
                accessToken
            );

        return new AuthorizationDecision(authorized);
    }

    // ========================================================
    // SAFE IDENTIFIER PARSING
    // ========================================================

    private Long parsePositiveId(String value) {

        if (value == null) {
            return null;
        }

        try {

            long parsed = Long.parseLong(value);

            return parsed > 0 ? parsed : null;

        } catch (NumberFormatException exception) {

            return null;
        }
    }

    // ========================================================
    // EXPLICIT DENIAL
    // ========================================================

    private AuthorizationDecision deny() {
        return new AuthorizationDecision(false);
    }
}
