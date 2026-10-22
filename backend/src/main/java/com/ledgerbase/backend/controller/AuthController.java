package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.AuthenticatedUser;
import com.ledgerbase.backend.dto.LoginRequest;
import com.ledgerbase.backend.dto.LoginResponse;
import com.ledgerbase.backend.dto.RefreshResponse;

import com.ledgerbase.backend.exception.InvalidCredentialsException;

import com.ledgerbase.backend.service.AuthenticationService;
import com.ledgerbase.backend.service.AuthSessionService;
import com.ledgerbase.backend.service.AuthRequestOriginValidator;
import com.ledgerbase.backend.service.JwtTokenService;
import com.ledgerbase.backend.service.RefreshCookieService;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

// ============================================================
// LEDGERBASE — AUTHENTICATION REST CONTROLLER
// ============================================================

/**
 * Provides LedgerBase authentication endpoints.
 *
 * BASE URL:
 *
 * /api/v1/auth
 *
 * ENDPOINTS:
 *
 * POST /login
 * - Verify credentials using BCrypt.
 * - Create a persistent PostgreSQL session.
 * - Issue a session-aware access JWT.
 * - Write an HttpOnly refresh cookie.
 *
 * POST /refresh
 * - Validate the browser's request origin.
 * - Read the HttpOnly refresh cookie.
 * - Rotate the refresh credential.
 * - Issue a replacement access JWT and cookie.
 *
 * POST /logout
 * - Validate the browser's request origin.
 * - Revoke the PostgreSQL authentication session.
 * - Clear the HttpOnly refresh cookie.
 *
 * SECURITY:
 *
 * - Never expose password hashes.
 * - Never return refresh tokens in JSON.
 * - Require trusted origins for cookie operations.
 * - Prevent caching of authentication responses.
 *
 * IMPORTANT:
 *
 * Access JWT revocation enforcement and financial
 * API authorization must be configured separately
 * through Spring Security.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    // ========================================================
    // ACCESS TOKEN CONFIGURATION
    // ========================================================

    /**
     * Standard JWT lifetime: 15 minutes.
     *
     * Tokens near their session's absolute expiration
     * may have a shorter remaining lifetime.
     */
    private static final long ACCESS_TOKEN_LIFETIME_SECONDS =
        900L;


    // ========================================================
    // DEPENDENCIES
    // ========================================================

    private final AuthenticationService authenticationService;

    private final JwtTokenService jwtTokenService;

    private final AuthSessionService authSessionService;

    private final RefreshCookieService refreshCookieService;

    private final AuthRequestOriginValidator originValidator;


    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public AuthController(
        AuthenticationService authenticationService,
        JwtTokenService jwtTokenService,
        AuthSessionService authSessionService,
        RefreshCookieService refreshCookieService,
        AuthRequestOriginValidator originValidator
    ) {

        this.authenticationService = authenticationService;
        this.jwtTokenService = jwtTokenService;
        this.authSessionService = authSessionService;
        this.refreshCookieService = refreshCookieService;
        this.originValidator = originValidator;
    }


    // ========================================================
    // LOGIN ENDPOINT
    // ========================================================

    /**
     * POST /api/v1/auth/login
     *
     * Authenticate a user and establish a new
     * persistent authentication session.
     *
     * SUCCESS:
     *
     * HTTP 200 with the existing LoginResponse JSON.
     *
     * An HttpOnly refresh cookie is also issued.
     *
     * FAILURE:
     *
     * 400 — Invalid request fields.
     * 401 — Invalid email or password.
     * 403 — Missing or untrusted Origin.
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(

        @Valid @RequestBody
        LoginRequest request,

        @RequestHeader(
            value = HttpHeaders.ORIGIN,
            required = false
        )
        String requestOrigin,

        HttpServletResponse servletResponse

    ) {

        // ====================================================
        // VALIDATE BROWSER REQUEST ORIGIN
        // ====================================================

        validateTrustedOrigin(requestOrigin);


        // ====================================================
        // VERIFY PASSWORD AND RESOLVE USER IDENTITY
        // ====================================================

        /**
         * The authenticated user ID comes from
         * PostgreSQL after BCrypt verification.
         */
        AuthenticatedUser user =
            authenticationService.authenticate(
                request.email(),
                request.password()
            );


        // ====================================================
        // CREATE PERSISTENT LOGIN SESSION
        // ====================================================

        /**
         * Session creation stores the session and
         * refresh-token hash transactionally.
         */
        AuthSessionService.CreatedSession session =
            authSessionService.createSession(
                user.id()
            );


        // ====================================================
        // GENERATE SESSION-AWARE ACCESS JWT
        // ====================================================

        String accessToken =
            jwtTokenService.generateAccessToken(
                user,
                session.sessionId(),
                session.absoluteExpiresAt()
            );


        // ====================================================
        // ISSUE HTTPONLY REFRESH COOKIE
        // ====================================================

        /**
         * JavaScript cannot read the refresh token.
         *
         * The cookie's expiration is restricted by
         * the session's absolute lifetime.
         */
        refreshCookieService.writeRefreshCookie(
            servletResponse,
            session.refreshToken(),
            session.absoluteExpiresAt()
        );


        // ====================================================
        // PRESERVE EXISTING LOGIN RESPONSE FORMAT
        // ====================================================

        LoginResponse response =
            new LoginResponse(
                accessToken,
                "Bearer",
                ACCESS_TOKEN_LIFETIME_SECONDS,
                user.id(),
                user.email(),
                user.fullName()
            );


        // ====================================================
        // RETURN NON-CACHEABLE LOGIN RESPONSE
        // ====================================================

        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .header("Pragma", "no-cache")
            .body(response);
    }


    // ========================================================
    // ACCESS TOKEN REFRESH ENDPOINT
    // ========================================================

    /**
     * POST /api/v1/auth/refresh
     *
     * Exchange a valid refresh cookie for:
     *
     * - A new short-lived access JWT.
     * - A replacement HttpOnly refresh cookie.
     *
     * AuthSessionService checks session expiration,
     * inactivity, revocation, and token replay.
     *
     * SUCCESS:
     *
     * HTTP 200 with RefreshResponse JSON.
     *
     * FAILURE:
     *
     * 401 — Missing, expired, or invalid credential.
     * 403 — Missing or untrusted Origin.
     */
    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(

        @RequestHeader(
            value = HttpHeaders.ORIGIN,
            required = false
        )
        String requestOrigin,

        @CookieValue(
            value = RefreshCookieService.COOKIE_NAME,
            required = false
        )
        String rawRefreshToken,

        HttpServletResponse servletResponse

    ) {

        // ====================================================
        // VALIDATE BROWSER REQUEST ORIGIN
        // ====================================================

        validateTrustedOrigin(requestOrigin);


        // ====================================================
        // REQUIRE EXISTING REFRESH COOKIE
        // ====================================================

        if (
            rawRefreshToken == null
            || rawRefreshToken.isBlank()
        ) {

            return rejectRefresh(servletResponse);
        }


        // ====================================================
        // ROTATE REFRESH CREDENTIAL TRANSACTIONALLY
        // ====================================================

        Optional<AuthSessionService.RefreshedSession> result =
            authSessionService.refreshSession(
                rawRefreshToken
            );


        // ====================================================
        // REJECT INVALID OR EXPIRED SESSION
        // ====================================================

        if (result.isEmpty()) {

            return rejectRefresh(servletResponse);
        }


        // ====================================================
        // READ REFRESHED SESSION IDENTITY
        // ====================================================

        AuthSessionService.RefreshedSession session =
            result.orElseThrow();


        // ====================================================
        // ISSUE NEW SESSION-AWARE ACCESS JWT
        // ====================================================

        /**
         * The user ID is read from the validated
         * server-side session, not the request body.
         */
        String accessToken =
            jwtTokenService.generateAccessTokenForSession(
                session.userId(),
                session.sessionId(),
                session.absoluteExpiresAt()
            );


        // ====================================================
        // REPLACE HTTPONLY REFRESH COOKIE
        // ====================================================

        refreshCookieService.writeRefreshCookie(
            servletResponse,
            session.refreshToken(),
            session.absoluteExpiresAt()
        );


        // ====================================================
        // CALCULATE REMAINING ACCESS JWT LIFETIME
        // ====================================================

        long remainingSessionSeconds =
            Math.max(
                0L,
                Duration.between(
                    Instant.now(),
                    session.absoluteExpiresAt().toInstant()
                ).getSeconds()
            );

        long expiresInSeconds =
            Math.min(
                ACCESS_TOKEN_LIFETIME_SECONDS,
                remainingSessionSeconds
            );


        // ====================================================
        // CREATE SAFE REFRESH RESPONSE
        // ====================================================

        RefreshResponse response =
            new RefreshResponse(
                accessToken,
                "Bearer",
                expiresInSeconds
            );


        // ====================================================
        // RETURN NON-CACHEABLE RESPONSE
        // ====================================================

        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .header("Pragma", "no-cache")
            .body(response);
    }


    // ========================================================
    // LOGOUT ENDPOINT
    // ========================================================

    /**
     * POST /api/v1/auth/logout
     *
     * Revoke the server-side authentication session
     * and clear the browser's refresh cookie.
     *
     * WORKFLOW:
     *
     * 1. Validate the browser Origin header.
     * 2. Read the refresh cookie, if present.
     * 3. Revoke the session through AuthSessionService.
     * 4. Clear the refresh cookie.
     * 5. Return HTTP 204 No Content.
     *
     * IDEMPOTENCY:
     *
     * A client receives the same successful response
     * when its cookie is missing, invalid, or already
     * associated with a revoked session.
     *
     * This avoids exposing the existence or status
     * of authentication sessions.
     *
     * SECURITY:
     *
     * The token is never included in a response body.
     *
     * Session revocation is performed in PostgreSQL
     * before the browser is instructed to delete
     * its refresh cookie.
     *
     * This endpoint does not require a valid access
     * JWT because the user may already have an
     * expired access token.
     *
     * Note: Existing JWTs must also be checked
     * against session revocation by Spring Security.
     * That validation is not yet implemented.
     *
     * SUCCESS:
     *
     * HTTP 204 No Content.
     *
     * FAILURE:
     *
     * HTTP 403 for an untrusted or missing Origin.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(

        @RequestHeader(
            value = HttpHeaders.ORIGIN,
            required = false
        )
        String requestOrigin,

        @CookieValue(
            value = RefreshCookieService.COOKIE_NAME,
            required = false
        )
        String rawRefreshToken,

        HttpServletResponse servletResponse

    ) {

        // ====================================================
        // VALIDATE REQUEST ORIGIN
        // ====================================================

        /**
         * Reject untrusted requests before accessing
         * or modifying authentication session state.
         */
        validateTrustedOrigin(requestOrigin);


        // ====================================================
        // REVOKE AUTHENTICATION SESSION
        // ====================================================

        if (
            rawRefreshToken != null
            && !rawRefreshToken.isBlank()
        ) {

            /**
             * A recognized refresh token identifies
             * the PostgreSQL session to revoke.
             *
             * The service handles:
             *
             * - Unknown credentials.
             * - Already revoked sessions.
             * - Previously consumed refresh tokens.
             *
             * Its boolean result is deliberately
             * not exposed to the HTTP client.
             *
             * Unexpected database failures should
             * propagate rather than falsely reporting
             * successful server-side revocation.
             */
            authSessionService.revokeSessionByRefreshToken(
                rawRefreshToken
            );
        }


        // ====================================================
        // CLEAR BROWSER REFRESH COOKIE
        // ====================================================

        /**
         * Max-Age=0 instructs the browser to remove
         * the cookie using its original name and path.
         *
         * Clearing a cookie is not a substitute for
         * server-side session revocation.
         */
        refreshCookieService.clearRefreshCookie(
            servletResponse
        );


        // ====================================================
        // RETURN IDEMPOTENT LOGOUT RESPONSE
        // ====================================================

        return ResponseEntity
            .noContent()
            .cacheControl(CacheControl.noStore())
            .header("Pragma", "no-cache")
            .build();
    }


    // ========================================================
    // VALIDATE TRUSTED AUTHENTICATION REQUEST ORIGIN
    // ========================================================

    /**
     * Shared validation for browser operations that
     * create, rotate, or revoke cookie credentials.
     *
     * Login, refresh, and logout all use this helper.
     *
     * Missing and untrusted origins are rejected.
     *
     * Origin validation is one CSRF defense.
     * It does not replace user authentication or RBAC.
     */
    private void validateTrustedOrigin(
        String requestOrigin
    ) {

        if (!originValidator.isTrustedOrigin(requestOrigin)) {

            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Untrusted authentication request origin"
            );
        }
    }


    // ========================================================
    // REJECT REFRESH AND CLEAR STALE COOKIE
    // ========================================================

    /**
     * Return a generic unauthorized response when
     * session restoration is not permitted.
     *
     * Clear the stale refresh cookie so the browser
     * does not repeatedly submit it.
     */
    private ResponseEntity<RefreshResponse> rejectRefresh(
        HttpServletResponse servletResponse
    ) {

        refreshCookieService.clearRefreshCookie(
            servletResponse
        );

        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .cacheControl(CacheControl.noStore())
            .header("Pragma", "no-cache")
            .build();
    }


    // ========================================================
    // INVALID CREDENTIALS HANDLER
    // ========================================================

    /**
     * Return the same HTTP 401 response for invalid
     * email addresses and incorrect passwords.
     *
     * The response does not reveal whether the
     * requested user account exists.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, String>>
        handleInvalidCredentials(
            InvalidCredentialsException exception
        ) {

        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .cacheControl(CacheControl.noStore())
            .header("Pragma", "no-cache")
            .body(
                Map.of(
                    "error",
                    "Invalid email or password"
                )
            );
    }
}
