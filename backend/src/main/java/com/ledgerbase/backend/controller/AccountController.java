package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.CreateAccountRequest;
import com.ledgerbase.backend.dto.RenameAccountRequest;
import com.ledgerbase.backend.dto.UpdateAccountReportingGroupRequest;

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
 * - Create normal posting accounts
 * - Create non-postable grouping accounts
 * - Rename existing accounts
 * - Deactivate accounts
 *
 * V7 supports:
 *
 * - Financial reporting classifications
 * - Optional account hierarchy
 * - Posting eligibility
 *
 * The controller handles HTTP requests and responses.
 * AccountService handles business logic.
 *
 * IMPORTANT:
 *
 * These routes are currently intended for local
 * development.
 *
 * Authentication, JWT validation, and business-level
 * RBAC must be implemented before public deployment.
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
     * Spring injects AccountService automatically.
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
     * Includes:
     *
     * - Active and inactive accounts
     * - Postable and non-postable accounts
     * - Reporting classifications
     * - Parent-account relationships
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
     * Retrieve account balances calculated from
     * POSTED journal entries only.
     *
     * IMPORTANT ACCOUNTING RULES:
     *
     * 1. DRAFT transactions do not affect balances.
     *
     * 2. Accounts without posted activity return zero.
     *
     * 3. Inactive accounts remain included because
     *    they may contain historical journal activity.
     *
     * 4. PostgreSQL performs calculations using NUMERIC.
     *
     * 5. Java handles monetary amounts using BigDecimal.
     *
     * 6. Grouping accounts have no direct journal activity.
     *    Hierarchical subtotals are calculated separately.
     *
     * This is a read-only endpoint.
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
    // CREATE ACCOUNT — V7 ENHANCED
    // ========================================================

    /**
     * POST /api/v1/businesses/1/accounts
     *
     * Create a financial account.
     *
     * BASIC REQUEST:
     *
     * {
     *   "code": "1010",
     *   "name": "Cash",
     *   "accountType": "ASSET"
     * }
     *
     * V7 ENHANCED REQUEST:
     *
     * {
     *   "code": "1010",
     *   "name": "Cash",
     *   "accountType": "ASSET",
     *   "reportingGroup": "CURRENT_ASSET",
     *   "parentAccountId": 12,
     *   "postable": true
     * }
     *
     * Optional V7 properties:
     *
     * reportingGroup:
     *   Defaults to null (unclassified).
     *
     * parentAccountId:
     *   Defaults to null (top-level account).
     *
     * postable:
     *   Defaults to true in AccountService.
     *
     * @Valid invokes Jakarta Bean Validation.
     *
     * AccountService validates business relationships.
     *
     * PostgreSQL enforces the final integrity rules.
     *
     * Returns HTTP 201 with the created account.
     */
    @PostMapping
    public ResponseEntity<Account> createAccount(
            @PathVariable Long businessId,
            @Valid @RequestBody CreateAccountRequest request) {

        // ====================================================
        // DELEGATE CREATION TO ACCOUNT SERVICE
        // ====================================================

        /*
         * Call the new V7 createAccount overload.
         *
         * Existing requests may omit the final
         * three properties.
         *
         * AccountService provides backward-compatible
         * defaults and validates the account structure.
         */
        Account created = accountService.createAccount(
            businessId,
            request.code(),
            request.name(),
            request.accountType(),
            request.reportingGroup(),
            request.parentAccountId(),
            request.postable()
        );


        // ====================================================
        // RETURN HTTP 201 CREATED
        // ====================================================

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(created);
    }


    // ============================================================
    // UPDATE ACCOUNT REPORTING GROUP
    // ============================================================

    /**
     * Update an existing account's financial reporting
     * classification.
     *
     * Endpoint:
     *
     * PATCH
     * /api/v1/businesses/{businessId}/accounts/{accountId}/reporting-group
     *
     * Example:
     *
     * PATCH /api/v1/businesses/1/accounts/5/reporting-group
     *
     * Request body:
     *
     * {
     *   "reportingGroup": "CURRENT_ASSET"
     * }
     *
     * The service layer validates:
     *
     * 1. The account belongs to the requested business.
     * 2. The reporting group is compatible with its type.
     * 3. Only the reporting classification is updated.
     *
     * This operation does not modify existing ledger
     * entries or recalculate financial balances.
     *
     * Inactive accounts may also be classified because
     * they can contribute to historical reports.
     *
     * @return The updated account, including its new
     *         reportingGroup value.
     */
    @PatchMapping("/{accountId}/reporting-group")
    public Account updateAccountReportingGroup(

        @PathVariable("businessId")
        Long businessId,

        @PathVariable("accountId")
        Long accountId,

        @Valid
        @RequestBody
        UpdateAccountReportingGroupRequest request

    ) {

        // ========================================================
        // DELEGATE VALIDATION AND UPDATE TO ACCOUNT SERVICE
        // ========================================================

        return accountService.updateReportingGroup(
            businessId,
            accountId,
            request.reportingGroup()
        );
    }


    // ========================================================
    // RENAME ACCOUNT
    // ========================================================

    /**
     * PATCH /api/v1/businesses/1/accounts/5
     *
     * Rename an existing active account.
     *
     * This endpoint only changes the account name.
     *
     * Account classifications, hierarchy, and posting
     * eligibility are intentionally not modified here.
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

        return ResponseEntity.noContent().build();
    }


    // ========================================================
    // DEACTIVATE ACCOUNT
    // ========================================================

    /**
     * PATCH /api/v1/businesses/1/accounts/5/deactivate
     *
     * Deactivate an account without deleting it.
     *
     * Historical ledger entries remain preserved.
     *
     * Deactivation changes is_active only.
     * It does not change is_postable.
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
