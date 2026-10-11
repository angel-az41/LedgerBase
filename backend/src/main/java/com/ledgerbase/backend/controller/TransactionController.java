package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.CreateTransactionRequest;
import com.ledgerbase.backend.model.LedgerTransaction;
import com.ledgerbase.backend.service.TransactionService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for LedgerBase financial transactions.
 *
 * Handles HTTP requests from the frontend and delegates
 * accounting operations to TransactionService.
 *
 * The controller should NOT contain accounting rules
 * or directly execute database queries.
 */
@RestController
@RequestMapping("/api/v1/businesses/{businessId}/transactions")
public class TransactionController {

    // Service responsible for accounting validation
    // and saving financial transactions.
    private final TransactionService transactionService;

    /**
     * Spring injects TransactionService automatically.
     *
     * Constructor injection keeps dependencies explicit
     * and makes the controller easier to test.
     */
    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /**
     * Create a new DRAFT financial transaction.
     *
     * Example:
     * POST /api/v1/businesses/1/transactions
     *
     * @PathVariable obtains the business ID from the URL.
     *
     * @RequestBody converts incoming JSON into our
     * CreateTransactionRequest record.
     *
     * @Valid triggers Jakarta Bean Validation for
     * the request and its nested ledger entries.
     *
     * Returns HTTP 201 Created with the saved
     * transaction header.
     */
    @PostMapping
    public ResponseEntity<LedgerTransaction> createDraftTransaction(
            @PathVariable("businessId") Long businessId,
            @Valid @RequestBody CreateTransactionRequest request) {

        // Delegate the financial operation to the service.
        LedgerTransaction savedTransaction =
            transactionService.createDraftTransaction(
                businessId,
                request
            );

        // The new transaction is a DRAFT, not yet POSTED.
        // HTTP 201 indicates that it was created successfully.
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(savedTransaction);
    }
}
