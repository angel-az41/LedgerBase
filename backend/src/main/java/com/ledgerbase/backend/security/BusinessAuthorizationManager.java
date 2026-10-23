package com.ledgerbase.backend.security;

import com.ledgerbase.backend.mapper.BusinessMembershipMapper;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

// ============================================================
// LEDGERBASE — BUSINESS AUTHORIZATION MANAGER
// ============================================================

/**
 * Enforces business-scoped REST API permissions.
 *
 * SECURITY INPUTS:
 *
 * - Authenticated JWT principal from Spring Security.
 * - businessId extracted from the requested API path.
 * - Current membership role retrieved from PostgreSQL.
 *
 * READ PERMISSIONS:
 *
 * OWNER, ADMIN, ACCOUNTANT, VIEWER
 *
 * WRITE PERMISSIONS:
 *
 * OWNER, ADMIN, ACCOUNTANT
 *
 * DENIED:
 *
 * - Missing or invalid authentication.
 * - Malformed user or business IDs.
 * - Missing business membership.
 * - VIEWER mutation requests.
 * - Unsupported HTTP methods.
 * - Unknown membership roles.
 *
 * IMPORTANT:
 *
 * Permissions are evaluated on every matching
 * HTTP request, so removing a user's membership
 * takes effect without issuing another JWT.
 *
 * This layer establishes business access permissions.
 * Acting-user audit identities must still be derived
 * from trusted JWT data in the accounting workflow.
 */
@Component
public class BusinessAuthorizationManager
    implements AuthorizationManager<RequestAuthorizationContext> {

    // ========================================================
    // DATABASE DEPENDENCY
    // ========================================================

    private final BusinessMembershipMapper membershipMapper;


    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public BusinessAuthorizationManager(
        BusinessMembershipMapper membershipMapper
    ) {

        this.membershipMapper = membershipMapper;
    }


    // ========================================================
    // AUTHORIZE BUSINESS API REQUEST
    // ========================================================

    /**
     * Determine whether an authenticated user may
     * perform the requested business operation.
     *
     * The caller is rejected by default.
     *
     * @param authenticationSupplier authenticated identity
     * @param context request and extracted path variables
     * @return explicit allow or deny decision
     */
    @Override
    public AuthorizationResult authorize(
        Supplier<? extends Authentication> authenticationSupplier,
        RequestAuthorizationContext context
    ) {

        // ----------------------------------------------------
        // REQUIRE AUTHENTICATED JWT IDENTITY
        // ----------------------------------------------------

        Authentication authentication =
            authenticationSupplier.get();

        if (
            authentication == null ||
            !authentication.isAuthenticated() ||
            !(authentication.getPrincipal() instanceof Jwt jwt)
        ) {

            return deny();
        }


        // ----------------------------------------------------
        // EXTRACT TRUSTED JWT SUBJECT
        // ----------------------------------------------------

        Long userId =
            parsePositiveId(jwt.getSubject());

        if (userId == null) {
            return deny();
        }


        // ----------------------------------------------------
        // EXTRACT BUSINESS ID FROM URL
        // ----------------------------------------------------

        String rawBusinessId =
            context.getVariables().get("businessId");

        Long businessId =
            parsePositiveId(rawBusinessId);

        if (businessId == null) {
            return deny();
        }


        // ----------------------------------------------------
        // RETRIEVE CURRENT DATABASE MEMBERSHIP
        // ----------------------------------------------------

        String role =
            membershipMapper.findRole(
                businessId,
                userId
            );

        if (role == null) {

            // An authenticated nonmember is forbidden.
            return deny();
        }


        // ----------------------------------------------------
        // IDENTIFY HTTP OPERATION TYPE
        // ----------------------------------------------------

        String method =
            context.getRequest().getMethod();


        // ----------------------------------------------------
        // READ ACCESS
        // ----------------------------------------------------

        /**
         * GET and HEAD require business membership.
         *
         * All four recognized roles have read access.
         */
        if (
            "GET".equals(method) ||
            "HEAD".equals(method)
        ) {

            return new AuthorizationDecision(
                isRecognizedRole(role)
            );
        }


        // ----------------------------------------------------
        // WRITE ACCESS
        // ----------------------------------------------------

        /**
         * Creating, editing, or deleting accounting
         * resources requires write permission.
         *
         * VIEWER is explicitly excluded.
         */
        if (
            "POST".equals(method) ||
            "PUT".equals(method) ||
            "PATCH".equals(method) ||
            "DELETE".equals(method)
        ) {

            return new AuthorizationDecision(
                hasWritePermission(role)
            );
        }


        // ----------------------------------------------------
        // DENY UNSUPPORTED REQUEST METHODS
        // ----------------------------------------------------

        return deny();
    }


    // ========================================================
    // RECOGNIZED BUSINESS ROLES
    // ========================================================

    /**
     * Use an explicit allowlist.
     *
     * Unknown roles must not accidentally receive
     * permission if the schema changes later.
     */
    private boolean isRecognizedRole(
        String role
    ) {

        return switch (role) {

            case "OWNER", "ADMIN", "ACCOUNTANT", "VIEWER"
                -> true;

            default -> false;
        };
    }


    // ========================================================
    // WRITE PERMISSIONS
    // ========================================================

    /**
     * OWNER, ADMIN, and ACCOUNTANT can perform
     * the currently supported accounting writes.
     *
     * Future membership-management endpoints
     * need a separate, stricter policy.
     */
    private boolean hasWritePermission(
        String role
    ) {

        return switch (role) {

            case "OWNER", "ADMIN", "ACCOUNTANT"
                -> true;

            default -> false;
        };
    }


    // ========================================================
    // SAFE IDENTIFIER PARSING
    // ========================================================

    /**
     * Convert a JWT subject or URL parameter into
     * a positive PostgreSQL BIGINT identifier.
     *
     * Invalid identifiers fail closed.
     */
    private Long parsePositiveId(
        String value
    ) {

        if (value == null) {
            return null;
        }

        try {

            long parsed =
                Long.parseLong(value);

            return parsed > 0
                ? parsed
                : null;

        } catch (NumberFormatException exception) {

            return null;
        }
    }


    // ========================================================
    // EXPLICIT DENIAL
    // ========================================================

    /**
     * Never return an undecided authorization result
     * when required identity or membership data is missing.
     */
    private AuthorizationDecision deny() {

        return new AuthorizationDecision(false);
    }
}
