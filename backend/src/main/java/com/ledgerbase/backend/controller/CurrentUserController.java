package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.CurrentUserResponse;
import com.ledgerbase.backend.mapper.UserMapper;
import com.ledgerbase.backend.model.User;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// ============================================================
// LEDGERBASE — CURRENT USER CONTROLLER
// ============================================================

/**
 * Retrieves the identity of the authenticated user.
 *
 * ENDPOINT:
 *
 * GET /api/v1/auth/me
 *
 * AUTHENTICATION:
 *
 * Spring Security verifies incoming JWT Bearer tokens
 * before a request reaches this controller.
 *
 * The authenticated JWT subject contains the user's
 * database ID.
 *
 * We use this trusted identity to retrieve the
 * current user record from PostgreSQL.
 *
 * SECURITY:
 *
 * - Never trust a user ID submitted by the frontend.
 * - Never return the complete User persistence model.
 * - Never expose password hashes.
 * - Reject identities that no longer exist.
 * - Prevent caching of identity responses.
 *
 * The corresponding SecurityFilterChain rule must
 * require authentication for this endpoint.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class CurrentUserController {

    // ========================================================
    // DATABASE DEPENDENCY
    // ========================================================

    private final UserMapper userMapper;


    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public CurrentUserController(
        UserMapper userMapper
    ) {

        this.userMapper = userMapper;
    }


    // ========================================================
    // GET AUTHENTICATED USER
    // ========================================================

    /**
     * GET /api/v1/auth/me
     *
     * Retrieves a user through the JWT identity
     * established by Spring Security.
     *
     * RETURNS:
     *
     * HTTP 200 — Authenticated user found.
     * HTTP 401 — Missing, invalid, or unknown identity.
     *
     * @param jwt Spring Security's validated JWT
     * @return safe current user response
     */
    @GetMapping("/me")
    public ResponseEntity<CurrentUserResponse> getCurrentUser(
        @AuthenticationPrincipal Jwt jwt
    ) {

        // ====================================================
        // REQUIRE AN AUTHENTICATED JWT
        // ====================================================

        // The security filter chain normally rejects
        // missing or invalid tokens before this point.
        //
        // This additional check avoids relying on
        // a null principal inside the controller.

        if (jwt == null) {

            return unauthorized();
        }


        // ====================================================
        // PARSE THE TRUSTED USER ID
        // ====================================================

        Long userId;

        try {

            userId = Long.parseLong(
                jwt.getSubject()
            );

            if (userId <= 0) {

                return unauthorized();
            }

        } catch (
            NumberFormatException
            | NullPointerException exception
        ) {

            return unauthorized();
        }


        // ====================================================
        // RETRIEVE CURRENT DATABASE IDENTITY
        // ====================================================

        User user = userMapper.findById(
            userId
        );


        // ====================================================
        // REJECT UNKNOWN OR DELETED USER
        // ====================================================

        // A valid signature does not guarantee that
        // the user still exists in PostgreSQL.
        //
        // Reject tokens belonging to missing users.

        if (user == null) {

            return unauthorized();
        }


        // ====================================================
        // BUILD SAFE RESPONSE
        // ====================================================

        CurrentUserResponse response =
            new CurrentUserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName()
            );


        // ====================================================
        // RETURN AUTHENTICATED USER
        // ====================================================

        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(response);
    }


    // ========================================================
    // UNAUTHORIZED RESPONSE
    // ========================================================

    /**
     * Return HTTP 401 without exposing database
     * identity details or token validation internals.
     */
    private ResponseEntity<CurrentUserResponse>
        unauthorized() {

        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .cacheControl(CacheControl.noStore())
            .build();
    }
}
