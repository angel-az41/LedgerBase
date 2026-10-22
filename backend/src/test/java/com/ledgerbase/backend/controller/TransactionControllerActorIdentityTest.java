package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.CreateTransactionRequest;
import com.ledgerbase.backend.dto.PostTransactionRequest;

import com.ledgerbase.backend.model.LedgerTransaction;
import com.ledgerbase.backend.service.TransactionService;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;

// ============================================================
// LEDGERBASE — TRANSACTION ACTOR IDENTITY TESTS
// ============================================================

/**
 * Verify that TransactionController never trusts
 * browser-supplied creator or posting actor IDs.
 *
 * These are controller unit tests.
 *
 * TransactionService is mocked so tests can inspect
 * the exact identity passed to the accounting layer.
 *
 * The test JWT is constructed directly because
 * signature and session validation are already
 * covered by separate authentication tests.
 *
 * SECURITY REQUIREMENT:
 *
 * JWT subject always takes precedence over
 * legacy createdBy and actorId request fields.
 */
class TransactionControllerActorIdentityTest {

    // ========================================================
    // HELPER — CREATE TEST JWT
    // ========================================================

    /**
     * Create a test authentication principal.
     *
     * No real JWT signing key is required
     * for this controller unit test.
     */
    private Jwt testJwt(Long userId) {

        return Jwt.withTokenValue(
                "unit-test-token"
            )
            .header("alg", "HS256")
            .subject(userId.toString())
            .build();
    }


    // ========================================================
    // HELPER — CREATE REQUEST
    // ========================================================

    /**
     * A minimal request suitable for testing
     * controller-to-service identity propagation.
     *
     * Accounting entry validation is tested
     * separately in TransactionService tests.
     */
    private CreateTransactionRequest testRequest(
        Long forgedCreatorId
    ) {

        return new CreateTransactionRequest(
            forgedCreatorId,
            "Actor identity security test",
            LocalDate.now(),
            List.of()
        );
    }


    // ========================================================
    // TEST — IGNORE FORGED CREATOR ID
    // ========================================================

    /**
     * Browser claims user 999 created the transaction.
     *
     * Validated JWT identifies user 42.
     *
     * The service must receive creator ID 42.
     */
    @Test
    void shouldUseJwtUserIdWhenCreatingTransaction() {

        // ----------------------------------------------------
        // MOCK ACCOUNTING SERVICE
        // ----------------------------------------------------

        TransactionService service =
            mock(TransactionService.class);

        TransactionController controller =
            new TransactionController(service);


        // ----------------------------------------------------
        // PREPARE FORGED CLIENT REQUEST
        // ----------------------------------------------------

        CreateTransactionRequest forgedRequest =
            testRequest(999L);

        Jwt jwt =
            testJwt(42L);


        // ----------------------------------------------------
        // EXECUTE CONTROLLER METHOD
        // ----------------------------------------------------

        ResponseEntity<LedgerTransaction> response =
            controller.createDraftTransaction(
                1L,
                forgedRequest,
                jwt
            );


        // ----------------------------------------------------
        // CAPTURE REQUEST PASSED TO SERVICE
        // ----------------------------------------------------

        ArgumentCaptor<CreateTransactionRequest> captor =
            ArgumentCaptor.forClass(
                CreateTransactionRequest.class
            );

        verify(service).createDraftTransaction(
            eq(1L),
            captor.capture()
        );


        // ----------------------------------------------------
        // VERIFY TRUSTED CREATOR ID
        // ----------------------------------------------------

        assertEquals(
            42L,
            captor.getValue().createdBy()
        );

        assertNotEquals(
            999L,
            captor.getValue().createdBy()
        );

        assertEquals(
            HttpStatus.CREATED,
            response.getStatusCode()
        );
    }


    // ========================================================
    // TEST — CREATEDBY IS OPTIONAL IN HTTP REQUEST
    // ========================================================

    /**
     * The browser no longer needs to submit createdBy.
     *
     * The controller supplies the authenticated
     * user ID regardless.
     */
    @Test
    void shouldPopulateMissingCreatorFromJwt() {

        TransactionService service =
            mock(TransactionService.class);

        TransactionController controller =
            new TransactionController(service);

        controller.createDraftTransaction(
            1L,
            testRequest(null),
            testJwt(42L)
        );

        ArgumentCaptor<CreateTransactionRequest> captor =
            ArgumentCaptor.forClass(
                CreateTransactionRequest.class
            );

        verify(service).createDraftTransaction(
            eq(1L),
            captor.capture()
        );

        assertEquals(
            42L,
            captor.getValue().createdBy()
        );
    }


    // ========================================================
    // TEST — IGNORE FORGED POSTING ACTOR
    // ========================================================

    /**
     * Browser claims that user 999 is posting.
     *
     * JWT identifies user 42.
     *
     * The accounting service must receive 42.
     */
    @Test
    void shouldUseJwtUserIdWhenPostingTransaction() {

        TransactionService service =
            mock(TransactionService.class);

        TransactionController controller =
            new TransactionController(service);

        PostTransactionRequest forgedRequest =
            new PostTransactionRequest(999L);


        // ----------------------------------------------------
        // EXECUTE POSTING REQUEST
        // ----------------------------------------------------

        controller.postTransaction(
            1L,
            10L,
            forgedRequest,
            testJwt(42L)
        );


        // ----------------------------------------------------
        // VERIFY VERIFIED POSTING ACTOR
        // ----------------------------------------------------

        verify(service).postTransaction(
            1L,
            10L,
            42L
        );

        verify(service, never()).postTransaction(
            1L,
            10L,
            999L
        );
    }


    // ========================================================
    // TEST — POSTING WITHOUT REQUEST BODY
    // ========================================================

    /**
     * No JSON body is needed to determine the
     * authenticated posting actor.
     */
    @Test
    void shouldAllowMissingLegacyPostingBody() {

        TransactionService service =
            mock(TransactionService.class);

        TransactionController controller =
            new TransactionController(service);

        controller.postTransaction(
            1L,
            10L,
            null,
            testJwt(42L)
        );

        verify(service).postTransaction(
            1L,
            10L,
            42L
        );
    }


    // ========================================================
    // TEST — REJECT MISSING AUTHENTICATION
    // ========================================================

    /**
     * No accounting service operation may run
     * when a JWT principal is unavailable.
     */
    @Test
    void shouldRejectTransactionCreationWithoutJwt() {

        TransactionService service =
            mock(TransactionService.class);

        TransactionController controller =
            new TransactionController(service);


        // ----------------------------------------------------
        // REQUIRE HTTP 401
        // ----------------------------------------------------

        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> controller.createDraftTransaction(
                    1L,
                    testRequest(999L),
                    null
                )
            );

        assertEquals(
            HttpStatus.UNAUTHORIZED,
            exception.getStatusCode()
        );


        // ----------------------------------------------------
        // ENSURE SERVICE WAS NOT CALLED
        // ----------------------------------------------------

        verifyNoInteractions(service);
    }
}
