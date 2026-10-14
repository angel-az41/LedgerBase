package com.ledgerbase.backend.exception;

// ============================================================
// LEDGERBASE — ACCOUNTING PERIOD CONFLICT EXCEPTION
// ============================================================

/**
 * Indicates that an accounting-period operation conflicts
 * with the period's current state.
 *
 * Examples:
 *
 * - Trying to close an already CLOSED period.
 * - Trying to reopen an already OPEN period.
 * - Another request changing the status concurrently.
 *
 * The REST exception handler will map this exception
 * to HTTP 409 Conflict when we add the endpoints.
 */
public class AccountingPeriodConflictException
        extends RuntimeException {

    public AccountingPeriodConflictException(String message) {
        super(message);
    }
}
