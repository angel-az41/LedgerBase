package com.ledgerbase.backend.exception;

/**
 * Raised when a requested business does not exist.
 *
 * This custom exception lets our HTTP error handler
 * distinguish a missing business from other errors.
 */
public class BusinessNotFoundException extends RuntimeException {

    public BusinessNotFoundException(Long businessId) {
        super("Business not found: " + businessId);
    }
}
