package com.ledgerbase.backend.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Centralized error handling for LedgerBase REST APIs.
 *
 * Instead of returning a technical Java stack trace,
 * we return a simple JSON response with an error message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles invalid JSON request fields detected by
     * Jakarta Validation annotations such as @NotBlank.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(
            MethodArgumentNotValidException exception) {

        String message = exception.getBindingResult()
            .getFieldErrors()
            .stream()
            .findFirst()
            .map(error -> error.getDefaultMessage())
            .orElse("Invalid request");

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", message));
    }

    /**
     * Handles invalid arguments detected by services.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(
            IllegalArgumentException exception) {

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", exception.getMessage()));
    }

    /**
     * Handles database constraint violations.
     *
     * For example, two accounts belonging to the same
     * business cannot have the same account code.
     *
     * NOTE: This is an initial implementation.
     * Not all constraint violations represent conflicts:
     * foreign key violations may mean a missing business.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrity(
            DataIntegrityViolationException exception) {

        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(Map.of(
                "error",
                "The operation conflicts with an existing database constraint"
            ));
    }

    /**
     * Handle attempts to create an account for
     * a business that does not exist.
     */
    @ExceptionHandler(BusinessNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleBusinessNotFound(
            BusinessNotFoundException exception) {

        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", exception.getMessage()));
    }

    /**
     * Handle requests for financial transactions
     * that do not exist within the requested business.
     *
     * Returns HTTP 404 with a consistent JSON
     * error response.
     */
    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleTransactionNotFound(
            TransactionNotFoundException exception) {

        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", exception.getMessage()));
    }

    /**
     * Handle transaction posting conflicts.
     *
     * For example, a POSTED transaction cannot
     * be posted a second time.
     *
     * Returns HTTP 409 Conflict.
     */
    @ExceptionHandler(TransactionConflictException.class)
    public ResponseEntity<Map<String, String>> handleTransactionConflict(
            TransactionConflictException exception) {

        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(Map.of("error", exception.getMessage()));
    }

    // ========================================================
    // ACCOUNTING PERIOD STATE CONFLICTS
    // ========================================================

    /**
     * Handles invalid accounting-period status transitions.
     *
     * Examples:
     *
     * - Trying to close an already CLOSED period.
     * - Trying to reopen an already OPEN period.
     * - A concurrent request changing the period's status
     *   before the requested operation completes.
     *
     * Returns HTTP 409 Conflict.
     *
     * This is not a server failure. It means the requested
     * action conflicts with the period's current state.
     */
    @ExceptionHandler(AccountingPeriodConflictException.class)
    public ResponseEntity<Map<String, String>> handleAccountingPeriodConflict(
            AccountingPeriodConflictException exception) {

        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(Map.of("error", exception.getMessage()));
    }
}
