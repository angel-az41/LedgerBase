package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.RegisterRequest;
import com.ledgerbase.backend.dto.RegisterResponse;

import com.ledgerbase.backend.service.AuthenticationService;
import com.ledgerbase.backend.service.RegistrationService;
import com.ledgerbase.backend.service.JwtTokenService;
import com.ledgerbase.backend.service.AuthSessionService;
import com.ledgerbase.backend.service.RefreshCookieService;
import com.ledgerbase.backend.service.AuthRequestOriginValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import org.springframework.web.server.ResponseStatusException;

import static org.hamcrest.Matchers.containsString;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// ============================================================
// LEDGERBASE — REGISTRATION CONTROLLER TESTS
// ============================================================

/**
 * Verify the HTTP contract for user registration.
 *
 * TEST COVERAGE:
 *
 * - Successful registration returns HTTP 201.
 * - Invalid request fields return HTTP 400.
 * - Missing or untrusted origins return HTTP 403.
 * - Duplicate email conflicts return HTTP 409.
 * - Registration responses never expose credentials.
 *
 * SECURITY:
 *
 * These tests use standalone MockMvc with mocked
 * application services.
 *
 * They do not execute the Spring Security filter
 * chain or access the PostgreSQL database.
 *
 * Security filter rules and database uniqueness
 * require separate integration verification.
 */
class AuthControllerRegistrationTest {

    // ========================================================
    // TEST CONFIGURATION
    // ========================================================

    private static final String TRUSTED_ORIGIN =
        "http://localhost:5173";

    private static final String VALID_REQUEST = """
        {
          "fullName": "Example User",
          "email": "example@email.com",
          "password": "SecurePassword123!"
        }
        """;

    private MockMvc mockMvc;

    private RegistrationService registrationService;

    private AuthRequestOriginValidator originValidator;


    // ========================================================
    // TEST SETUP
    // ========================================================

    @BeforeEach
    void setUp() {

        registrationService =
            mock(RegistrationService.class);

        originValidator =
            mock(AuthRequestOriginValidator.class);

        // Simulate the configured trusted frontend origin.
        when(originValidator.isTrustedOrigin(TRUSTED_ORIGIN))
            .thenReturn(true);

        AuthController controller = new AuthController(
            mock(AuthenticationService.class),
            registrationService,
            mock(JwtTokenService.class),
            mock(AuthSessionService.class),
            mock(RefreshCookieService.class),
            originValidator
        );

        // Enable Jakarta Bean Validation for @Valid
        // request bodies in standalone controller tests.
        LocalValidatorFactoryBean validator =
            new LocalValidatorFactoryBean();

        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setValidator(validator)
            .build();
    }


    // ========================================================
    // SUCCESSFUL REGISTRATION
    // ========================================================

    /**
     * Verify that a valid registration request
     * returns HTTP 201 and a safe user identity.
     *
     * No JWT or refresh cookie is issued.
     */
    @Test
    void registersUserSuccessfully() throws Exception {

        when(registrationService.register(
            any(RegisterRequest.class)
        )).thenReturn(
            new RegisterResponse(
                101L,
                "example@email.com",
                "Example User"
            )
        );

        mockMvc.perform(
            post("/api/v1/auth/register")
                .header(HttpHeaders.ORIGIN, TRUSTED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST)
        )
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.userId").value(101))
        .andExpect(jsonPath("$.email")
            .value("example@email.com"))
        .andExpect(jsonPath("$.fullName")
            .value("Example User"))
        .andExpect(jsonPath("$.password").doesNotExist())
        .andExpect(jsonPath("$.passwordHash").doesNotExist())
        .andExpect(header().string(
            HttpHeaders.CACHE_CONTROL,
            containsString("no-store")
        ));

        verify(registrationService, times(1))
            .register(any(RegisterRequest.class));
    }


    // ========================================================
    // INVALID REQUEST VALIDATION
    // ========================================================

    /**
     * Reject a password shorter than the minimum
     * length defined in RegisterRequest.
     *
     * The registration service must not be invoked.
     */
    @Test
    void rejectsShortPassword() throws Exception {

        String invalidRequest = """
            {
              "fullName": "Example User",
              "email": "example@email.com",
              "password": "short"
            }
            """;

        mockMvc.perform(
            post("/api/v1/auth/register")
                .header(HttpHeaders.ORIGIN, TRUSTED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidRequest)
        )
        .andExpect(status().isBadRequest());

        verifyNoInteractions(registrationService);
    }


    // ========================================================
    // UNTRUSTED REQUEST ORIGIN
    // ========================================================

    /**
     * A registration request from an untrusted
     * browser origin must be rejected.
     */
    @Test
    void rejectsUntrustedOrigin() throws Exception {

        mockMvc.perform(
            post("/api/v1/auth/register")
                .header(
                    HttpHeaders.ORIGIN,
                    "https://untrusted.example"
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST)
        )
        .andExpect(status().isForbidden());

        verifyNoInteractions(registrationService);
    }


    // ========================================================
    // MISSING REQUEST ORIGIN
    // ========================================================

    /**
     * The existing authentication origin policy
     * rejects browser requests without Origin.
     */
    @Test
    void rejectsMissingOrigin() throws Exception {

        mockMvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST)
        )
        .andExpect(status().isForbidden());

        verifyNoInteractions(registrationService);
    }


    // ========================================================
    // DUPLICATE EMAIL CONFLICT
    // ========================================================

    /**
     * Verify that an email conflict from
     * RegistrationService is returned as HTTP 409.
     *
     * Actual database uniqueness is enforced by
     * PostgreSQL and tested separately.
     */
    @Test
    void rejectsDuplicateEmail() throws Exception {

        when(registrationService.register(
            any(RegisterRequest.class)
        )).thenThrow(
            new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Unable to register with this email address"
            )
        );

        mockMvc.perform(
            post("/api/v1/auth/register")
                .header(HttpHeaders.ORIGIN, TRUSTED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST)
        )
        .andExpect(status().isConflict());
    }
}
