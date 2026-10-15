package com.ledgerbase.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// ============================================================
// LEDGERBASE — ACCOUNTING PERIOD ACTION REQUEST
// ============================================================

/**
 * Request body for accounting-period status changes.
 *
 * Used by both:
 *
 * POST /accounting-periods/{periodId}/close
 * POST /accounting-periods/{periodId}/reopen
 *
 * actorId identifies the user performing the action.
 *
 * The service records this ID in the audit_events table
 * to preserve a history of accounting-period changes.
 *
 * IMPORTANT:
 *
 * Until authentication is implemented, actorId is
 * supplied by the API client for development purposes.
 *
 * In production, we must obtain the actor identity
 * from an authenticated session or security context
 * rather than trusting a client-supplied ID.
 */
public record AccountingPeriodActionRequest(

    // ========================================================
    // ACTOR ID
    // ========================================================

    /**
     * ID of the user requesting the state change.
     *
     * Must be provided and must be a positive integer.
     */
    @NotNull(message = "Actor ID is required")
    @Positive(message = "Actor ID must be positive")
    Long actorId

) {
}
