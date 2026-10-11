package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.CreateAccountRequest;
import com.ledgerbase.backend.dto.RenameAccountRequest;
import com.ledgerbase.backend.model.Account;
import com.ledgerbase.backend.service.AccountService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for financial accounts.
 *
 * Base URL:
 * /api/v1/businesses/{businessId}/accounts
 *
 * The controller handles HTTP requests and responses.
 * AccountService handles business logic.
 *
 * IMPORTANT:
 * These routes are for local development.
 * Authentication and business ownership checks
 * must be implemented before public deployment.
 */
@RestController
@RequestMapping("/api/v1/businesses/{businessId}/accounts")
public class AccountController {

    private final AccountService accountService;

    /**
     * Spring injects the service through the constructor.
     */
    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * GET /api/v1/businesses/1/accounts
     *
     * Lists accounts for the specified business.
     */
    @GetMapping
    public List<Account> getAccounts(
            @PathVariable Long businessId) {

        return accountService.getAccounts(businessId);
    }

    /**
     * GET /api/v1/businesses/1/accounts/5
     *
     * Returns HTTP 404 if no matching account exists.
     */
    @GetMapping("/{accountId}")
    public ResponseEntity<Account> getAccount(
            @PathVariable Long businessId,
            @PathVariable Long accountId) {

        Account account = accountService.getAccount(
            businessId, accountId
        );

        if (account == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(account);
    }

    /**
     * POST /api/v1/businesses/1/accounts
     *
     * Creates an account and returns HTTP 201.
     */
    @PostMapping
    public ResponseEntity<Account> createAccount(
            @PathVariable Long businessId,
            @Valid @RequestBody CreateAccountRequest request) {

        Account created = accountService.createAccount(
            businessId,
            request.code(),
            request.name(),
            request.accountType()
        );

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(created);
    }

    /**
     * PATCH /api/v1/businesses/1/accounts/5
     *
     * Renames an existing active account.
     */
    @PatchMapping("/{accountId}")
    public ResponseEntity<Void> renameAccount(
            @PathVariable Long businessId,
            @PathVariable Long accountId,
            @Valid @RequestBody RenameAccountRequest request) {

        boolean updated = accountService.renameAccount(
            businessId, accountId, request.name()
        );

        if (!updated) {
            return ResponseEntity.notFound().build();
        }

        // HTTP 204: successful, with no response body.
        return ResponseEntity.noContent().build();
    }

    /**
     * PATCH /api/v1/businesses/1/accounts/5/deactivate
     *
     * Disables an account without deleting its history.
     */
    @PatchMapping("/{accountId}/deactivate")
    public ResponseEntity<Void> deactivateAccount(
            @PathVariable Long businessId,
            @PathVariable Long accountId) {

        boolean updated = accountService.deactivateAccount(
            businessId, accountId
        );

        if (!updated) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.noContent().build();
    }
}
