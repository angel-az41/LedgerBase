package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.CreateTransactionRequest;
import com.ledgerbase.backend.dto.PostTransactionRequest;
import com.ledgerbase.backend.dto.TransactionDetailResponse;

import com.ledgerbase.backend.model.LedgerTransaction;
import com.ledgerbase.backend.service.TransactionService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.server.ResponseStatusException;

import java.util.List;

// ============================================================
// LEDGERBASE — TRANSACTION REST CONTROLLER
// ============================================================

/**
 * REST controller for LedgerBase financial transactions.
 *
 * BASE URL:
 *
 * /api/v1/businesses/{businessId}/transactions
 *
 * RESPONSIBILITIES:
 *
 * - Create DRAFT transactions.
 * - Retrieve transaction lists.
 * - Retrieve transaction details.
 * - Post DRAFT transactions.
 *
 * AUTHENTICATION:
 *
 * Spring Security validates JWT Bearer access tokens.
 *
 * AUTHORIZATION:
 *
 * BusinessAuthorizationManager checks the user's
 * current membership and read/write permissions
 * before the request reaches this controller.
 *
 * ACTOR IDENTITY:
 *
 * The creator and posting actor are always derived
 * from Spring Security's validated JWT subject.
 *
 * Browser-submitted createdBy and actorId values
 * must never determine the identity recorded in
 * PostgreSQL or accounting audit events.
 *
 * ACCOUNTING:
 *
 * TransactionService continues to enforce:
 *
 * - Double-entry bookkeeping.
 * - Atomic journal creation.
 * - Account and business validation.
 * - Accounting-period posting rules.
 * - PostgreSQL row-level locking.
 * - Audit event creation.
 *
 * The controller does not perform accounting
 * calculations or database operations directly.
 */
@RestController
@RequestMapping("/api/v1/businesses/{businessId}/transactions")
public class TransactionController {

    // ========================================================
    // TRANSACTION SERVICE
    // ========================================================

    private final TransactionService transactionService;


    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    /**
     * Preserve the existing constructor signature.
     *
     * Spring Boot injects TransactionService.
     *
     * This avoids changing the service dependencies
     * or accounting transaction setup.
     */
    public TransactionController(
        TransactionService transactionService
    ) {

        this.transactionService = transactionService;
    }


    // ========================================================
    // CREATE DRAFT TRANSACTION
    // ========================================================

    /**
     * POST /api/v1/businesses/{businessId}/transactions
     *
     * Create a balanced DRAFT journal entry.
     *
     * Example request:
     *
     * {
     *     "description": "Office supplies purchase",
     *     "transactionDate": "2026-10-22",
     *     "entries": [
     *         {
     *             "accountId": 5,
     *             "debit": 250.00,
     *             "credit": 0.00
     *         },
     *         {
     *             "accountId": 2,
     *             "debit": 0.00,
     *             "credit": 250.00
     *         }
     *     ]
     * }
     *
     * The request does not need createdBy.
     *
     * SECURITY:
     *
     * We explicitly replace any incoming createdBy
     * field with the verified JWT user ID.
     *
     * This prevents one user from creating a journal
     * entry while impersonating another user.
     *
     * RETURNS:
     *
     * HTTP 201 — DRAFT successfully created.
     * HTTP 400 — Invalid accounting request.
     * HTTP 401 — Missing or invalid authentication.
     * HTTP 403 — Insufficient business permissions.
     *
     * @param businessId business from URL
     * @param request validated transaction information
     * @param jwt validated authentication principal
     * @return created DRAFT transaction
     */
    @PostMapping
    public ResponseEntity<LedgerTransaction> createDraftTransaction(

        @PathVariable("businessId")
        Long businessId,

        @Valid
        @RequestBody
        CreateTransactionRequest request,

        @AuthenticationPrincipal
        Jwt jwt

    ) {

        // ----------------------------------------------------
        // RESOLVE TRUSTED CREATOR ID
        // ----------------------------------------------------

        /**
         * JWT identity is verified by Spring Security.
         *
         * Do not use request.createdBy().
         */
        Long authenticatedUserId =
            getAuthenticatedUserId(jwt);


        // ----------------------------------------------------
        // CONSTRUCT TRUSTED TRANSACTION REQUEST
        // ----------------------------------------------------

        /**
         * Create a new immutable DTO containing:
         *
         * - Verified authenticated creator ID.
         * - Validated transaction description.
         * - Validated accounting date.
         * - Validated journal entries.
         *
         * Any createdBy supplied by the browser
         * is deliberately discarded.
         *
         * This preserves the existing
         * TransactionService signature while
         * enforcing trusted identity at the
         * authenticated HTTP boundary.
         */
        CreateTransactionRequest trustedRequest =
            new CreateTransactionRequest(
                authenticatedUserId,
                request.description(),
                request.transactionDate(),
                request.entries()
            );


        // ----------------------------------------------------
        // CREATE TRANSACTION ATOMICALLY
        // ----------------------------------------------------

        /**
         * TransactionService persists:
         *
         * - The transaction header.
         * - The ledger entries.
         * - The TRANSACTION_CREATED audit event.
         *
         * Both transaction.createdBy and the
         * audit event actor use the trusted
         * authenticatedUserId.
         */
        LedgerTransaction savedTransaction =
            transactionService.createDraftTransaction(
                businessId,
                trustedRequest
            );


        // ----------------------------------------------------
        // RETURN HTTP 201 CREATED
        // ----------------------------------------------------

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(savedTransaction);
    }


    // ========================================================
    // RETRIEVE TRANSACTION LIST
    // ========================================================

    /**
     * GET /api/v1/businesses/{businessId}/transactions
     *
     * Returns all transaction headers belonging
     * to the requested business.
     *
     * BusinessAuthorizationManager must verify
     * membership before calling this method.
     *
     * RETURNS:
     *
     * HTTP 200 — List of transactions.
     * HTTP 401 — Missing or invalid authentication.
     * HTTP 403 — User does not have read permission.
     */
    @GetMapping
    public ResponseEntity<List<LedgerTransaction>> getTransactions(

        @PathVariable("businessId")
        Long businessId

    ) {

        // ----------------------------------------------------
        // DELEGATE TO EXISTING SERVICE
        // ----------------------------------------------------

        List<LedgerTransaction> transactions =
            transactionService.getTransactions(
                businessId
            );


        // ----------------------------------------------------
        // RETURN TRANSACTION LIST
        // ----------------------------------------------------

        return ResponseEntity.ok(
            transactions
        );
    }


    // ========================================================
    // RETRIEVE TRANSACTION DETAILS
    // ========================================================

    /**
     * GET
     * /api/v1/businesses/{businessId}/transactions/{transactionId}
     *
     * Retrieve one transaction with its debit/credit lines.
     *
     * Both service queries are scoped by businessId.
     *
     * RETURNS:
     *
     * HTTP 200 — Transaction found.
     * HTTP 404 — Transaction not found.
     *
     * Spring Security independently handles
     * authentication and authorization.
     */
    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionDetailResponse> getTransactionDetails(

        @PathVariable("businessId")
        Long businessId,

        @PathVariable("transactionId")
        Long transactionId

    ) {

        // ----------------------------------------------------
        // FETCH TRANSACTION AND LEDGER ENTRIES
        // ----------------------------------------------------

        TransactionDetailResponse details =
            transactionService.getTransactionDetails(
                businessId,
                transactionId
            );


        // ----------------------------------------------------
        // RETURN TRANSACTION DETAILS
        // ----------------------------------------------------

        return ResponseEntity.ok(
            details
        );
    }


    // ========================================================
    // POST DRAFT TRANSACTION
    // ========================================================

    /**
     * POST
     * /api/v1/businesses/{businessId}/transactions/{transactionId}/post
     *
     * Finalize a DRAFT financial transaction.
     *
     * An empty request body is now supported.
     *
     * For backward compatibility, the frontend
     * may still submit:
     *
     * {
     *     "actorId": 1
     * }
     *
     * IMPORTANT:
     *
     * That legacy actorId is ignored completely.
     *
     * The verified JWT user ID is always used
     * for the TRANSACTION_POSTED audit event.
     *
     * TransactionService performs:
     *
     * - SELECT ... FOR UPDATE locking.
     * - DRAFT-status validation.
     * - Accounting-period validation and locking.
     * - Account eligibility checks.
     * - Double-entry balance checks.
     * - Atomic posting and audit logging.
     *
     * RETURNS:
     *
     * HTTP 200 — Transaction posted successfully.
     * HTTP 401 — Authentication required.
     * HTTP 403 — Insufficient business permission.
     * HTTP 404 — Transaction not found.
     * HTTP 409 — Posting conflict.
     *
     * @param businessId business from URL
     * @param transactionId transaction to finalize
     * @param ignoredLegacyRequest optional untrusted legacy body
     * @param jwt validated authentication principal
     * @return posted transaction
     */
    @PostMapping("/{transactionId}/post")
    public ResponseEntity<LedgerTransaction> postTransaction(

        @PathVariable("businessId")
        Long businessId,

        @PathVariable("transactionId")
        Long transactionId,

        @RequestBody(required = false)
        PostTransactionRequest ignoredLegacyRequest,

        @AuthenticationPrincipal
        Jwt jwt

    ) {

        // ----------------------------------------------------
        // RESOLVE TRUSTED POSTING ACTOR
        // ----------------------------------------------------

        /**
         * Do not read ignoredLegacyRequest.actorId().
         *
         * An ACCOUNTANT may post a journal, but must
         * never claim another user's identity.
         */
        Long authenticatedActorId =
            getAuthenticatedUserId(jwt);


        // ----------------------------------------------------
        // POST USING VERIFIED ACTOR ID
        // ----------------------------------------------------

        LedgerTransaction postedTransaction =
            transactionService.postTransaction(
                businessId,
                transactionId,
                authenticatedActorId
            );


        // ----------------------------------------------------
        // RETURN POSTED TRANSACTION
        // ----------------------------------------------------

        return ResponseEntity.ok(
            postedTransaction
        );
    }


    // ========================================================
    // RESOLVE AUTHENTICATED USER ID
    // ========================================================

    /**
     * Read the PostgreSQL user ID from Spring Security's
     * already-validated JWT principal.
     *
     * This follows the same approach as
     * BusinessController and CurrentUserController.
     *
     * The JWT subject must be a positive numeric ID.
     *
     * We fail closed if the authentication principal
     * is unavailable or has an invalid subject.
     *
     * @param jwt verified JWT principal
     * @return trusted authenticated user ID
     */
    private Long getAuthenticatedUserId(
        Jwt jwt
    ) {

        // ----------------------------------------------------
        // REQUIRE AUTHENTICATION PRINCIPAL
        // ----------------------------------------------------

        if (jwt == null) {

            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Authentication required"
            );
        }


        // ----------------------------------------------------
        // PARSE JWT SUBJECT
        // ----------------------------------------------------

        try {

            Long userId =
                Long.parseLong(
                    jwt.getSubject()
                );


            // ------------------------------------------------
            // REQUIRE POSITIVE DATABASE USER ID
            // ------------------------------------------------

            if (userId <= 0) {

                throw new NumberFormatException(
                    "Invalid user ID"
                );
            }

            return userId;

        } catch (
            NumberFormatException
            | NullPointerException exception
        ) {

            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Invalid authenticated identity"
            );
        }
    }
}
