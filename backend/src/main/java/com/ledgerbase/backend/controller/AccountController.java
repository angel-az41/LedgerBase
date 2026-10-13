package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.CreateAccountRequest;
import com.ledgerbase.backend.dto.RenameAccountRequest;

import com.ledgerbase.backend.model.Account;
import com.ledgerbase.backend.model.AccountBalance;

import com.ledgerbase.backend.service.AccountService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// ============================================================
// LEDGERBASE — ACCOUNT REST CONTROLLER
// ============================================================

/**
 * REST API for financial accounts.
 *
 * Base URL:
 *
 * /api/v1/businesses/{businessId}/accounts
 *
 * Responsibilities:
 *
 * - Retrieve Chart of Accounts
 * - Retrieve calculated posted account balances
 * - Retrieve individual accounts
 * - Create new accounts
 * - Rename existing accounts
 * - Deactivate accounts
 *
 * The controller handles HTTP requests and responses.
 * AccountService handles business logic.
 *
 * IMPORTANT:
 *
 * These routes are currently for local development.
 *
 * Authentication and business ownership checks
 * must be implemented before public deployment.
 */
@RestController
@RequestMapping("/api/v1/businesses/{businessId}/accounts")
public class AccountController {

    // ========================================================
    // ACCOUNT SERVICE
    // ========================================================

    private final AccountService accountService;

    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    /**
     * Spring injects the service automatically
     * through constructor injection.
     */
    public AccountController(AccountService accountService) {

        this.accountService = accountService;
    }

    // ========================================================
    // GET ALL ACCOUNTS
    // ========================================================

    /**
     * GET /api/v1/businesses/1/accounts
     *
     * Retrieve the Chart of Accounts for one business.
     *
     * Includes active and inactive accounts.
     */
    @GetMapping
    public List<Account> getAccounts(
            @PathVariable Long businessId) {

        return accountService.getAccounts(businessId);
    }

    // ========================================================
    // GET CALCULATED ACCOUNT BALANCES
    // ========================================================

    /**
     * GET /api/v1/businesses/1/accounts/balances
     *
     * NEW: Retrieve calculated account balances.
     *
     * IMPORTANT ACCOUNTING RULES:
     *
     * 1. Only POSTED journal transactions contribute.
     *
     * 2. DRAFT transactions do not affect balances.
     *
     * 3. Accounts without posted activity return zero.
     *
     * 4. Inactive accounts remain included to preserve
     *    visibility into historical accounting activity.
     *
     * 5. Calculations are performed in PostgreSQL
     *    using exact NUMERIC arithmetic.
     *
     * 6. Java receives monetary amounts as BigDecimal.
     *
     * If the business does not exist, AccountService
     * throws BusinessNotFoundException.
     *
     * Our existing exception handling should return
     * an appropriate HTTP 404 response.
     *
     * This is a read-only API endpoint.
     */
    @GetMapping("/balances")
    public List<AccountBalance> getAccountBalances(
            @PathVariable Long businessId) {

        return accountService.getAccountBalances(businessId);
    }

    // ========================================================
    // GET ONE ACCOUNT
    // ========================================================

    /**
     * GET /api/v1/businesses/1/accounts/5
     *
     * Retrieve one account belonging to a business.
     *
     * Returns HTTP 404 when the account is not found.
     */
    @GetMapping("/{accountId}")
    public ResponseEntity<Account> getAccount(
            @PathVariable Long businessId,
            @PathVariable Long accountId) {

        Account account = accountService.getAccount(
            businessId,
            accountId
        );

        if (account == null) {

            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(account);
    }

    // ========================================================
    // CREATE ACCOUNT
    // ========================================================

    /**
     * POST /api/v1/businesses/1/accounts
     *
     * Create a new financial account.
     *
     * Returns HTTP 201 with the created account.
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

    // ========================================================
    // RENAME ACCOUNT
    // ========================================================

    /**
     * PATCH /api/v1/businesses/1/accounts/5
     *
     * Rename an existing active account.
     *
     * Returns HTTP 204 when successful.
     */
    @PatchMapping("/{accountId}")
    public ResponseEntity<Void> renameAccount(
            @PathVariable Long businessId,
            @PathVariable Long accountId,
            @Valid @RequestBody RenameAccountRequest request) {

        boolean updated = accountService.renameAccount(
            businessId,
            accountId,
            request.name()
        );

        if (!updated) {

            return ResponseEntity.notFound().build();
        }

        // HTTP 204: successful, no response body.
        return ResponseEntity.noContent().build();
    }

    // ========================================================
    // DEACTIVATE ACCOUNT
    // ========================================================

    /**
     * PATCH /api/v1/businesses/1/accounts/5/deactivate
     *
     * Deactivate an account without deleting
     * its historical ledger entries.
     *
     * Returns HTTP 204 when successful.
     */
    @PatchMapping("/{accountId}/deactivate")
    public ResponseEntity<Void> deactivateAccount(
            @PathVariable Long businessId,
            @PathVariable Long accountId) {

        boolean updated = accountService.deactivateAccount(
            businessId,
            accountId
        );

        if (!updated) {

            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.noContent().build();
    }
}
