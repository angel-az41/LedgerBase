package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.AccountingPeriodActionRequest;
import com.ledgerbase.backend.model.AccountingPeriod;
import com.ledgerbase.backend.service.AccountingPeriodService;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — ACCOUNTING PERIOD ACTOR IDENTITY TESTS
// ============================================================

/**
 * Unit tests for the authenticated actor identity
 * used by AccountingPeriodController.
 *
 * These tests verify that:
 *
 * - Closing a period uses the JWT subject.
 * - Reopening a period uses the JWT subject.
 * - Client-supplied actorId values are ignored.
 * - Legacy request bodies are optional.
 * - Requests without an authenticated principal fail.
 * - Invalid JWT subjects are rejected.
 *
 * AccountingPeriodService is mocked so that we can
 * verify the exact actor ID passed by the controller.
 *
 * These are controller unit tests, not HTTP integration
 * tests. They do not execute the Spring Security filter
 * chain or query PostgreSQL.
 *
 * Business RBAC and JWT authentication through HTTP
 * must also be verified in integration tests.
 */
class AccountingPeriodControllerActorIdentityTest {

    // ========================================================
    // TEST JWT HELPER
    // ========================================================

    /**
     * Construct a JWT principal for controller testing.
     *
     * LedgerBase uses the PostgreSQL user ID as the
     * authenticated JWT subject.
     *
     * Example:
     *
     * subject = "42"
     *
     * This represents authenticated user ID 42.
     *
     * The JWT is created directly because these tests
     * exercise controller behavior rather than actual
     * token signature verification.
     */
    private Jwt testJwt(String subject) {

        return Jwt.withTokenValue("test-token")
            .header("alg", "HS256")
            .subject(subject)
            .build();
    }


    // ========================================================
    // CLOSE PERIOD — IGNORE FORGED ACTOR ID
    // ========================================================

    /**
     * The authenticated user is 42.
     *
     * The client attempts to impersonate user 999.
     *
     * The service must receive 42, not 999.
     */
    @Test
    void shouldUseJwtActorWhenClosingPeriod() {

        // ----------------------------------------------------
        // ARRANGE — MOCK THE SERVICE
        // ----------------------------------------------------

        AccountingPeriodService service =
            mock(AccountingPeriodService.class);

        AccountingPeriodController controller =
            new AccountingPeriodController(service);

        AccountingPeriod period =
            mock(AccountingPeriod.class);

        when(
            service.closeAccountingPeriod(
                1L,
                10L,
                42L
            )
        ).thenReturn(period);


        // ----------------------------------------------------
        // ACT — SEND A FORGED LEGACY ACTOR ID
        // ----------------------------------------------------

        ResponseEntity<AccountingPeriod> response =
            controller.closeAccountingPeriod(
                1L,
                10L,
                new AccountingPeriodActionRequest(999L),
                testJwt("42")
            );


        // ----------------------------------------------------
        // ASSERT — REQUEST SUCCEEDED
        // ----------------------------------------------------

        assertEquals(
            HttpStatus.OK,
            response.getStatusCode()
        );

        assertSame(
            period,
            response.getBody()
        );


        // ----------------------------------------------------
        // ASSERT — TRUST THE JWT IDENTITY
        // ----------------------------------------------------

        verify(service).closeAccountingPeriod(
            1L,
            10L,
            42L
        );


        // ----------------------------------------------------
        // ASSERT — IGNORE THE FORGED IDENTITY
        // ----------------------------------------------------

        verify(service, never()).closeAccountingPeriod(
            1L,
            10L,
            999L
        );

        verifyNoMoreInteractions(service);
    }


    // ========================================================
    // REOPEN PERIOD — IGNORE FORGED ACTOR ID
    // ========================================================

    /**
     * Reopening must follow the same authentication
     * rules as closing.
     *
     * The backend must not trust actorId from JSON.
     */
    @Test
    void shouldUseJwtActorWhenReopeningPeriod() {

        AccountingPeriodService service =
            mock(AccountingPeriodService.class);

        AccountingPeriodController controller =
            new AccountingPeriodController(service);

        AccountingPeriod period =
            mock(AccountingPeriod.class);

        when(
            service.reopenAccountingPeriod(
                1L,
                10L,
                42L
            )
        ).thenReturn(period);


        // ----------------------------------------------------
        // ACT
        // ----------------------------------------------------

        ResponseEntity<AccountingPeriod> response =
            controller.reopenAccountingPeriod(
                1L,
                10L,
                new AccountingPeriodActionRequest(999L),
                testJwt("42")
            );


        // ----------------------------------------------------
        // ASSERT
        // ----------------------------------------------------

        assertEquals(
            HttpStatus.OK,
            response.getStatusCode()
        );

        assertSame(
            period,
            response.getBody()
        );

        verify(service).reopenAccountingPeriod(
            1L,
            10L,
            42L
        );

        verify(service, never()).reopenAccountingPeriod(
            1L,
            10L,
            999L
        );

        verifyNoMoreInteractions(service);
    }


    // ========================================================
    // CLOSE PERIOD — OPTIONAL LEGACY REQUEST BODY
    // ========================================================

    /**
     * Closing must work without the old JSON actorId.
     *
     * The frontend no longer needs to submit
     * a user identity in the request body.
     */
    @Test
    void shouldAllowClosingWithoutLegacyActorBody() {

        AccountingPeriodService service =
            mock(AccountingPeriodService.class);

        AccountingPeriodController controller =
            new AccountingPeriodController(service);


        // ----------------------------------------------------
        // ACT — NO REQUEST BODY
        // ----------------------------------------------------

        controller.closeAccountingPeriod(
            1L,
            10L,
            null,
            testJwt("42")
        );


        // ----------------------------------------------------
        // ASSERT — JWT USER PASSED TO SERVICE
        // ----------------------------------------------------

        verify(service).closeAccountingPeriod(
            1L,
            10L,
            42L
        );

        verifyNoMoreInteractions(service);
    }


    // ========================================================
    // REOPEN PERIOD — OPTIONAL LEGACY REQUEST BODY
    // ========================================================

    /**
     * Reopening must also work without a browser-
     * supplied actor ID.
     */
    @Test
    void shouldAllowReopeningWithoutLegacyActorBody() {

        AccountingPeriodService service =
            mock(AccountingPeriodService.class);

        AccountingPeriodController controller =
            new AccountingPeriodController(service);


        // ----------------------------------------------------
        // ACT
        // ----------------------------------------------------

        controller.reopenAccountingPeriod(
            1L,
            10L,
            null,
            testJwt("42")
        );


        // ----------------------------------------------------
        // ASSERT
        // ----------------------------------------------------

        verify(service).reopenAccountingPeriod(
            1L,
            10L,
            42L
        );

        verifyNoMoreInteractions(service);
    }


    // ========================================================
    // CLOSE PERIOD — MISSING JWT
    // ========================================================

    /**
     * A request without an authenticated principal
     * must not perform an accounting-period change.
     *
     * Even a positive legacy actorId cannot
     * substitute for authentication.
     */
    @Test
    void shouldRejectClosingWithoutAuthentication() {

        AccountingPeriodService service =
            mock(AccountingPeriodService.class);

        AccountingPeriodController controller =
            new AccountingPeriodController(service);


        // ----------------------------------------------------
        // ACT — NO AUTHENTICATED JWT
        // ----------------------------------------------------

        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> controller.closeAccountingPeriod(
                    1L,
                    10L,
                    new AccountingPeriodActionRequest(999L),
                    null
                )
            );


        // ----------------------------------------------------
        // ASSERT
        // ----------------------------------------------------

        assertEquals(
            HttpStatus.UNAUTHORIZED,
            exception.getStatusCode()
        );

        verifyNoInteractions(service);
    }


    // ========================================================
    // REOPEN PERIOD — MISSING JWT
    // ========================================================

    /**
     * Reopening must also reject requests without
     * an authenticated JWT principal.
     */
    @Test
    void shouldRejectReopeningWithoutAuthentication() {

        AccountingPeriodService service =
            mock(AccountingPeriodService.class);

        AccountingPeriodController controller =
            new AccountingPeriodController(service);


        // ----------------------------------------------------
        // ACT
        // ----------------------------------------------------

        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> controller.reopenAccountingPeriod(
                    1L,
                    10L,
                    new AccountingPeriodActionRequest(999L),
                    null
                )
            );


        // ----------------------------------------------------
        // ASSERT
        // ----------------------------------------------------

        assertEquals(
            HttpStatus.UNAUTHORIZED,
            exception.getStatusCode()
        );

        verifyNoInteractions(service);
    }


    // ========================================================
    // INVALID JWT — NON-NUMERIC USER ID
    // ========================================================

    /**
     * LedgerBase expects a positive PostgreSQL user ID
     * in the JWT subject.
     *
     * A nonnumeric subject must be rejected.
     */
    @Test
    void shouldRejectReopeningWithInvalidJwtSubject() {

        AccountingPeriodService service =
            mock(AccountingPeriodService.class);

        AccountingPeriodController controller =
            new AccountingPeriodController(service);


        // ----------------------------------------------------
        // ACT — INVALID SUBJECT
        // ----------------------------------------------------

        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> controller.reopenAccountingPeriod(
                    1L,
                    10L,
                    new AccountingPeriodActionRequest(999L),
                    testJwt("invalid-user")
                )
            );


        // ----------------------------------------------------
        // ASSERT
        // ----------------------------------------------------

        assertEquals(
            HttpStatus.UNAUTHORIZED,
            exception.getStatusCode()
        );

        verifyNoInteractions(service);
    }


    // ========================================================
    // INVALID JWT — NONPOSITIVE USER ID
    // ========================================================

    /**
     * A JWT subject of zero is not a valid
     * LedgerBase database user ID.
     *
     * The controller must reject the request
     * before executing any service operation.
     */
    @Test
    void shouldRejectClosingWithNonpositiveJwtSubject() {

        AccountingPeriodService service =
            mock(AccountingPeriodService.class);

        AccountingPeriodController controller =
            new AccountingPeriodController(service);


        // ----------------------------------------------------
        // ACT — JWT SUBJECT IS ZERO
        // ----------------------------------------------------

        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> controller.closeAccountingPeriod(
                    1L,
                    10L,
                    new AccountingPeriodActionRequest(999L),
                    testJwt("0")
                )
            );


        // ----------------------------------------------------
        // ASSERT
        // ----------------------------------------------------

        assertEquals(
            HttpStatus.UNAUTHORIZED,
            exception.getStatusCode()
        );

        verifyNoInteractions(service);
    }
}
