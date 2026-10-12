package com.ledgerbase.backend.exception;

/**
 * Raised when a financial transaction cannot be posted
 * because its current state conflicts with the operation.
 *
 * For example:
 * - The transaction is already POSTED.
 * - An account is no longer active.
 *
 * Our global exception handler converts this into
 * HTTP 409 Conflict.
 */
public class TransactionConflictException
        extends RuntimeException {

    public TransactionConflictException(String message) {
        super(message);
    }
}
