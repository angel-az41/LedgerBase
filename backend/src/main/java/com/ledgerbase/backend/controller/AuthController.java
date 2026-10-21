package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.AuthenticatedUser;
import com.ledgerbase.backend.dto.LoginRequest;
import com.ledgerbase.backend.dto.LoginResponse;

import com.ledgerbase.backend.exception.InvalidCredentialsException;

import com.ledgerbase.backend.service.AuthenticationService;
import com.ledgerbase.backend.service.JwtTokenService;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// ============================================================
// LEDGERBASE — AUTHENTICATION REST CONTROLLER
// ============================================================

/**
 * REST API for LedgerBase authentication.
 *
 * BASE URL:
 *
 * /api/v1/auth
 *
 * RESPONSIBILITIES:
 *
 * 1. Receive login credentials.
 * 2. Delegate password verification to AuthenticationService.
 * 3. Generate signed JWTs through JwtTokenService.
 * 4. Return a safe login response.
 *
 * SECURITY:
 *
 * - This controller never handles password hashes.
 * - Invalid credentials produce HTTP 401.
 * - Authentication errors use generic messages.
 * - Login responses must not be cached.
 *
 * IMPORTANT:
 *
 * This endpoint does not yet activate authentication
 * on the remaining accounting APIs.
 *
 * Spring Security request protection will be
 * implemented separately.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    // ========================================================
    // ACCESS TOKEN CONFIGURATION
    // ========================================================

    /**
     * Must match the configured JWT access token lifetime.
     *
     * Future configuration improvements can centralize
     * this value instead of maintaining it in two places.
     */
    private static final long ACCESS_TOKEN_LIFETIME_SECONDS =
        900L;


    // ========================================================
    // DEPENDENCIES
    // ========================================================

    private final AuthenticationService authenticationService;

    private final JwtTokenService jwtTokenService;


    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public AuthController(
        AuthenticationService authenticationService,
        JwtTokenService jwtTokenService
    ) {

        this.authenticationService = authenticationService;
        this.jwtTokenService = jwtTokenService;
    }


    // ========================================================
    // LOGIN ENDPOINT
    // ========================================================

    /**
     * POST /api/v1/auth/login
     *
     * Authenticates a user and returns a signed
     * JWT access token.
     *
     * SUCCESS:
     *
     * HTTP 200
     *
     * FAILURE:
     *
     * HTTP 400 — Invalid request format or fields.
     * HTTP 401 — Invalid email or password.
     *
     * The user ID is retrieved from PostgreSQL
     * after successful password verification.
     *
     * The client cannot choose the authenticated
     * identity by supplying a user ID.
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
        @Valid @RequestBody LoginRequest request
    ) {

        // ====================================================
        // VERIFY CREDENTIALS
        // ====================================================

        AuthenticatedUser user =
            authenticationService.authenticate(
                request.email(),
                request.password()
            );


        // ====================================================
        // GENERATE SIGNED ACCESS TOKEN
        // ====================================================

        String accessToken =
            jwtTokenService.generateAccessToken(user);


        // ====================================================
        // BUILD SAFE RESPONSE
        // ====================================================

        LoginResponse response = new LoginResponse(
            accessToken,
            "Bearer",
            ACCESS_TOKEN_LIFETIME_SECONDS,
            user.id(),
            user.email(),
            user.fullName()
        );


        // ====================================================
        // PREVENT RESPONSE CACHING
        // ====================================================

        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .header("Pragma", "no-cache")
            .body(response);
    }


    // ========================================================
    // INVALID CREDENTIALS HANDLER
    // ========================================================

    /**
     * Convert failed password authentication into
     * HTTP 401 Unauthorized.
     *
     * Using one generic response avoids exposing
     * whether an email exists in the database.
     *
     * This handler applies to this controller.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, String>>
        handleInvalidCredentials(
            InvalidCredentialsException exception
        ) {

        // ====================================================
        // RETURN GENERIC AUTHENTICATION FAILURE
        // ====================================================

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
