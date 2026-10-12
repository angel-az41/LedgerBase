package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.CreateTransactionRequest;
import com.ledgerbase.backend.model.LedgerTransaction;
import com.ledgerbase.backend.service.TransactionService;
import com.ledgerbase.backend.dto.TransactionDetailResponse;
import com.ledgerbase.backend.dto.PostTransactionRequest;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

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

    /**
     * Retrieve every transaction belonging to a business.
     *
     * Example:
     * GET /api/v1/businesses/1/transactions
     *
     * Returns HTTP 200 with a JSON array.
     *
     * An existing business without transactions returns
     * an empty array rather than an error.
     *
     * A nonexistent business returns HTTP 404 through
     * our GlobalExceptionHandler.
     */
    @GetMapping
    public ResponseEntity<List<LedgerTransaction>> getTransactions(
            @PathVariable("businessId") Long businessId) {

        // Delegate data retrieval to the service layer.
        List<LedgerTransaction> transactions =
            transactionService.getTransactions(businessId);

        // Spring serializes the list into a JSON array.
        return ResponseEntity.ok(transactions);
    }

    /**
     * Retrieve a single financial transaction,
     * including all debit and credit entries.
     *
     * Example:
     * GET /api/v1/businesses/1/transactions/10
     *
     * Returns:
     * - HTTP 200 when the transaction exists.
     * - HTTP 404 when the business or transaction
     *   cannot be found.
     */
    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionDetailResponse> getTransactionDetails(
            @PathVariable("businessId") Long businessId,
            @PathVariable("transactionId") Long transactionId) {

        // Retrieve the transaction and its ledger entries.
        TransactionDetailResponse details =
            transactionService.getTransactionDetails(
                businessId,
                transactionId
            );

        // Serialize the response into JSON.
        return ResponseEntity.ok(details);
    }

    /**
     * Post (finalize) an existing DRAFT transaction.
     *
     * Example:
     * POST /api/v1/businesses/1/transactions/10/post
     *
     * Returns:
     * - HTTP 200 when successfully posted.
     * - HTTP 404 when the transaction does not exist.
     * - HTTP 409 when the transaction cannot be posted
     *   because of its current state.
     */
    @PostMapping("/{transactionId}/post")
    public ResponseEntity<LedgerTransaction> postTransaction(
            @PathVariable("businessId") Long businessId,
            @PathVariable("transactionId") Long transactionId,
            @Valid @RequestBody PostTransactionRequest request) {

        // The service performs locking and all accounting checks.
        LedgerTransaction postedTransaction =
            transactionService.postTransaction(
                businessId,
                transactionId,
                request.actorId()
            );

        // Return the finalized transaction to the client.
        return ResponseEntity.ok(postedTransaction);
    }
}
