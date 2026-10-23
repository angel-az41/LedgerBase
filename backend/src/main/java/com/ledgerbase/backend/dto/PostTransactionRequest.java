package com.ledgerbase.backend.dto;

// ============================================================
// LEDGERBASE — POST TRANSACTION REQUEST
// ============================================================

/**
 * Legacy request DTO for posting a DRAFT transaction.
 *
 * The posting operation is identified by the URL:
 *
 * POST
 * /api/v1/businesses/{businessId}/transactions/{transactionId}/post
 *
 * AUTHENTICATION:
 *
 * The actual posting actor is obtained from the
 * authenticated JWT by TransactionController.
 *
 * The browser does not need to provide an actor ID.
 *
 * BACKWARD COMPATIBILITY:
 *
 * The old frontend currently sends:
 *
 * {
 *     "actorId": 1
 * }
 *
 * This record keeps that field temporarily so
 * existing frontend and test code can migrate
 * without breaking Java compilation.
 *
 * IMPORTANT:
 *
 * TransactionController must not use this
 * actorId when recording a posting audit event.
 *
 * The field is optional and untrusted.
 *
 * Once the frontend is updated, the posting
 * endpoint can accept an empty request body.
 */
public record PostTransactionRequest(

    // ========================================================
    // LEGACY ACTOR FIELD — NOT TRUSTED
    // ========================================================

    /**
     * Optional legacy field.
     *
     * The backend ignores any value submitted here.
     *
     * The verified JWT subject identifies
     * the person performing the posting.
     */
    Long actorId

) {}
