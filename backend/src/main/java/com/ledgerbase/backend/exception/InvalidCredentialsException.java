package com.ledgerbase.backend.exception;

// ============================================================
// LEDGERBASE — INVALID CREDENTIALS EXCEPTION
// ============================================================

/**
 * Raised when user authentication fails.
 *
 * SECURITY:
 *
 * The error message must not reveal whether:
 *
 * - The email address exists.
 * - The password is incorrect.
 * - The account has not yet been initialized.
 *
 * All authentication failures use the same message.
 *
 * A future authentication controller will translate
 * this exception into HTTP 401 Unauthorized.
 */
public class InvalidCredentialsException
        extends RuntimeException {

    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    public InvalidCredentialsException() {

        super("Invalid email or password");
    }
}
