package com.ledgerbase.backend.exception;

/**
 * Thrown when a financial transaction cannot
 * be found within the requested business.
 *
 * This lets our centralized exception handler
 * return HTTP 404 instead of an unexpected error.
 */
public class TransactionNotFoundException
        extends RuntimeException {

    public TransactionNotFoundException(Long transactionId) {
        super("Transaction not found: " + transactionId);
    }
}
