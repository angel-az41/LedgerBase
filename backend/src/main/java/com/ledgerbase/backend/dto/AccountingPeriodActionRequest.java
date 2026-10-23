package com.ledgerbase.backend.dto;

// ============================================================
// LEDGERBASE — ACCOUNTING PERIOD ACTION REQUEST
// ============================================================

/**
 * Legacy request DTO for accounting-period status changes.
 *
 * Used by:
 *
 * POST /accounting-periods/{periodId}/close
 * POST /accounting-periods/{periodId}/reopen
 *
 * AUTHENTICATION:
 *
 * Spring Security authenticates the user through JWT.
 *
 * AccountingPeriodController obtains the actual actor
 * from the verified JWT subject.
 *
 * SECURITY:
 *
 * The browser must not determine which user is
 * recorded in financial audit events.
 *
 * The incoming actorId is therefore ignored.
 *
 * BACKWARD COMPATIBILITY:
 *
 * Older frontend requests may still contain:
 *
 * {
 *     "actorId": 1
 * }
 *
 * This record temporarily retains the field
 * so those requests can continue to deserialize.
 *
 * The field is not required or validated because
 * its value is never used to determine identity.
 *
 * New frontend requests may submit an empty
 * JSON object or omit the body entirely.
 *
 * AccountingPeriodService continues to receive
 * the trusted, validated actor ID supplied by
 * AccountingPeriodController.
 *
 * Once all legacy clients and tests have migrated,
 * this DTO can be removed from the HTTP contract.
 */
public record AccountingPeriodActionRequest(

    // ========================================================
    // LEGACY ACTOR ID — UNTRUSTED
    // ========================================================

    /**
     * Optional legacy actor ID.
     *
     * This value is not used by the controller
     * or passed into AccountingPeriodService.
     *
     * Only the authenticated JWT subject determines
     * the actor recorded in the database audit log.
     */
    Long actorId

) {
}
