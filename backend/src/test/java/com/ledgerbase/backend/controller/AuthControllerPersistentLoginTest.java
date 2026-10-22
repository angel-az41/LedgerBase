package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.AuthenticatedUser;
import com.ledgerbase.backend.dto.LoginRequest;
import com.ledgerbase.backend.dto.LoginResponse;

import com.ledgerbase.backend.exception.InvalidCredentialsException;

import com.ledgerbase.backend.service.AuthenticationService;
import com.ledgerbase.backend.service.AuthSessionService;
import com.ledgerbase.backend.service.AuthRequestOriginValidator;
import com.ledgerbase.backend.service.JwtTokenService;
import com.ledgerbase.backend.service.RefreshCookieService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — PERSISTENT LOGIN CONTROLLER TESTS
// ============================================================

/**
 * Verifies the login controller's authentication flow.
 *
 * Mocked collaborators:
 *
 * - AuthenticationService
 * - AuthSessionService
 * - JwtTokenService
 * - RefreshCookieService
 * - AuthRequestOriginValidator
 *
 * These tests verify orchestration and response behavior.
 *
 * HTTP cookie attributes and PostgreSQL persistence
 * are covered by their dedicated tests.
 *
 * HTTP-level integration tests will be added after
 * connecting the remaining authentication endpoints.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerPersistentLoginTest {

    // ========================================================
    // TEST CONSTANTS
    // ========================================================

    private static final String TRUSTED_ORIGIN =
        "http://localhost:5173";

    private static final String TEST_EMAIL =
        "login-test@ledgerbase.test";

    private static final String TEST_PASSWORD =
        "test-password";

    private static final String TEST_ACCESS_TOKEN =
        "signed-test-access-token";

    private static final String TEST_REFRESH_TOKEN =
        "A".repeat(43);

    private static final UUID SESSION_ID =
        UUID.fromString(
            "ba3162c9-8d05-481e-977d-911faf684007"
        );


    // ========================================================
    // MOCKED DEPENDENCIES
    // ========================================================

    @Mock
    private AuthenticationService authenticationService;

    @Mock
    private AuthSessionService authSessionService;

    @Mock
    private JwtTokenService jwtTokenService;

    @Mock
    private RefreshCookieService refreshCookieService;

    @Mock
    private AuthRequestOriginValidator originValidator;

    @InjectMocks
    private AuthController authController;


    // ========================================================
    // TEST INPUT HELPERS
    // ========================================================

    private LoginRequest testRequest() {

        return new LoginRequest(
            TEST_EMAIL,
            TEST_PASSWORD
        );
    }


    private AuthenticatedUser testUser() {

        return new AuthenticatedUser(
            7L,
            TEST_EMAIL,
            "Login Test User"
        );
    }


    // ========================================================
    // TEST — SUCCESSFUL PERSISTENT LOGIN
    // ========================================================

    @Test
    void shouldCreateSessionAndIssueRefreshCookie() {

        AuthenticatedUser user = testUser();

        OffsetDateTime expiration =
            OffsetDateTime.now(ZoneOffset.UTC)
                .plusHours(8);

        AuthSessionService.CreatedSession session =
            new AuthSessionService.CreatedSession(
                SESSION_ID,
                TEST_REFRESH_TOKEN,
                expiration
            );

        MockHttpServletResponse servletResponse =
            new MockHttpServletResponse();


        // ----------------------------------------------------
        // CONFIGURE DEPENDENCIES
        // ----------------------------------------------------

        when(
            originValidator.isTrustedOrigin(TRUSTED_ORIGIN)
        ).thenReturn(true);

        when(
            authenticationService.authenticate(
                TEST_EMAIL,
                TEST_PASSWORD
            )
        ).thenReturn(user);

        when(
            authSessionService.createSession(user.id())
        ).thenReturn(session);

        when(
            jwtTokenService.generateAccessToken(
                user,
                SESSION_ID,
                expiration
            )
        ).thenReturn(TEST_ACCESS_TOKEN);


        // ----------------------------------------------------
        // EXECUTE LOGIN
        // ----------------------------------------------------

        ResponseEntity<LoginResponse> result =
            authController.login(
                testRequest(),
                TRUSTED_ORIGIN,
                servletResponse
            );


        // ----------------------------------------------------
        // VERIFY RESPONSE
        // ----------------------------------------------------

        assertEquals(
            HttpStatus.OK,
            result.getStatusCode()
        );

        assertNotNull(result.getBody());

        assertTrue(
            result.getHeaders()
                .getCacheControl()
                .contains("no-store")
        );


        // ----------------------------------------------------
        // VERIFY AUTHENTICATION FLOW ORDER
        // ----------------------------------------------------

        InOrder ordered = inOrder(
            originValidator,
            authenticationService,
            authSessionService,
            jwtTokenService,
            refreshCookieService
        );

        ordered.verify(originValidator)
            .isTrustedOrigin(TRUSTED_ORIGIN);

        ordered.verify(authenticationService)
            .authenticate(
                TEST_EMAIL,
                TEST_PASSWORD
            );

        ordered.verify(authSessionService)
            .createSession(user.id());

        ordered.verify(jwtTokenService)
            .generateAccessToken(
                user,
                SESSION_ID,
                expiration
            );

        ordered.verify(refreshCookieService)
            .writeRefreshCookie(
                servletResponse,
                TEST_REFRESH_TOKEN,
                expiration
            );
    }


    // ========================================================
    // TEST — UNTRUSTED REQUEST ORIGIN
    // ========================================================

    @Test
    void shouldRejectUntrustedOriginBeforeAuthentication() {

        MockHttpServletResponse servletResponse =
            new MockHttpServletResponse();

        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> authController.login(
                    testRequest(),
                    "https://attacker.example",
                    servletResponse
                )
            );

        assertEquals(
            HttpStatus.FORBIDDEN,
            exception.getStatusCode()
        );

        verifyNoInteractions(
            authenticationService,
            authSessionService,
            jwtTokenService,
            refreshCookieService
        );
    }


    // ========================================================
    // TEST — MISSING REQUEST ORIGIN
    // ========================================================

    @Test
    void shouldRejectMissingOrigin() {

        MockHttpServletResponse servletResponse =
            new MockHttpServletResponse();

        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> authController.login(
                    testRequest(),
                    null,
                    servletResponse
                )
            );

        assertEquals(
            HttpStatus.FORBIDDEN,
            exception.getStatusCode()
        );

        verifyNoInteractions(
            authenticationService,
            authSessionService,
            jwtTokenService,
            refreshCookieService
        );
    }


    // ========================================================
    // TEST — INVALID USER CREDENTIALS
    // ========================================================

    @Test
    void shouldNotCreateSessionWhenCredentialsAreInvalid() {

        MockHttpServletResponse servletResponse =
            new MockHttpServletResponse();

        when(
            originValidator.isTrustedOrigin(TRUSTED_ORIGIN)
        ).thenReturn(true);

        when(
            authenticationService.authenticate(
                TEST_EMAIL,
                TEST_PASSWORD
            )
        ).thenThrow(
            new InvalidCredentialsException()
        );


        // ----------------------------------------------------
        // VERIFY AUTHENTICATION FAILURE
        // ----------------------------------------------------

        assertThrows(
            InvalidCredentialsException.class,
            () -> authController.login(
                testRequest(),
                TRUSTED_ORIGIN,
                servletResponse
            )
        );


        // ----------------------------------------------------
        // VERIFY NO SESSION OR TOKENS WERE CREATED
        // ----------------------------------------------------

        verifyNoInteractions(
            authSessionService,
            jwtTokenService,
            refreshCookieService
        );
    }
}
