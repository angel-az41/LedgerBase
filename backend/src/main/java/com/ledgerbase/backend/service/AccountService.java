package com.ledgerbase.backend.service;

import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.mapper.AccountMapper;
import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.model.Account;
import com.ledgerbase.backend.model.AccountBalance;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

// ============================================================
// LEDGERBASE — ACCOUNT SERVICE
// ============================================================

/**
 * Business logic for LedgerBase's Chart of Accounts.
 *
 * RESPONSIBILITIES:
 *
 * - Retrieve accounts and calculated balances.
 * - Create normal posting accounts.
 * - Create non-postable grouping accounts.
 * - Validate financial reporting classifications.
 * - Validate optional parent-child relationships.
 * - Rename and deactivate accounts.
 * - Track durable business reporting revisions.
 *
 * PostgreSQL remains authoritative for financial
 * integrity through constraints and triggers.
 *
 * REPORTING CONSISTENCY:
 *
 * Account changes that affect financial reports
 * advance the business reporting revision.
 *
 * Each revision increment participates in the same
 * PostgreSQL transaction as the account modification.
 *
 * Redis caching will use these revisions later.
 */
@Service
public class AccountService {

    // ========================================================
    // DATABASE MAPPERS
    // ========================================================

    private final AccountMapper accountMapper;

    private final BusinessMapper businessMapper;


    // ========================================================
    // DURABLE FINANCIAL REPORT REVISION MANAGEMENT
    // ========================================================

    /**
     * Track committed account changes that could
     * affect Trial Balance or other financial reports.
     *
     * PostgreSQL stores the authoritative revision.
     *
     * Each revision update must participate in
     * the same transaction as the account change.
     */
    private final BusinessReportingRevisionService
        reportingRevisionService;


    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    /**
     * Inject persistence, business validation, and durable
     * reporting revision management.
     *
     * Every revision increment uses the same database
     * transaction as the related account modification.
     */
    public AccountService(
            AccountMapper accountMapper,
            BusinessMapper businessMapper,
            BusinessReportingRevisionService reportingRevisionService) {

        this.accountMapper = accountMapper;
        this.businessMapper = businessMapper;
        this.reportingRevisionService = reportingRevisionService;
    }


    // ========================================================
    // GET ALL ACCOUNTS
    // ========================================================

    /**
     * Retrieve the Chart of Accounts for one business.
     *
     * Includes active, inactive, postable, and
     * non-postable grouping accounts.
     */
    public List<Account> getAccounts(Long businessId) {

        return accountMapper.findByBusinessId(businessId);
    }


    // ========================================================
    // GET CALCULATED ACCOUNT BALANCES
    // ========================================================

    /**
     * Calculate account balances from POSTED entries.
     *
     * ACCOUNTING RULES:
     *
     * - DRAFT transactions do not affect balances.
     * - Inactive accounts remain visible.
     * - Accounts without activity have zero balances.
     * - ASSET and EXPENSE are debit-normal.
     * - LIABILITY, EQUITY, and REVENUE are credit-normal.
     *
     * Monetary values use PostgreSQL NUMERIC and
     * Java BigDecimal.
     *
     * Grouping accounts have no direct journal activity.
     * Their child subtotals are calculated separately
     * when preparing hierarchical financial reports.
     */
    @Transactional(readOnly = true)
    public List<AccountBalance> getAccountBalances(Long businessId) {

        // ====================================================
        // VALIDATE BUSINESS EXISTENCE
        // ====================================================

        validateBusinessExists(businessId);


        // ====================================================
        // RETRIEVE POSTED BALANCES
        // ====================================================

        return accountMapper.findBalancesByBusinessId(
            businessId
        );
    }


    // ========================================================
    // GET ONE ACCOUNT
    // ========================================================

    /**
     * Retrieve an individual account in a business.
     *
     * Returns null when the account cannot be found
     * within the requested business.
     */
    public Account getAccount(
            Long businessId,
            Long accountId) {

        return accountMapper.findById(
            businessId,
            accountId
        );
    }


    // ========================================================
    // CREATE ACCOUNT — COMPATIBILITY METHOD
    // ========================================================

    /**
     * Preserve the existing four-argument API.
     *
     * Existing AccountController and service tests
     * can continue calling this method.
     *
     * Default values:
     *
     * reportingGroup = null
     * parentAccountId = null
     * postable = true
     *
     * These values create a normal, top-level
     * posting account.
     *
     * This method delegates to the enhanced creation
     * method, which advances the reporting revision.
     *
     * Do not increment the revision a second time here.
     */
    @Transactional
    public Account createAccount(
            Long businessId,
            String code,
            String name,
            String accountType) {

        return createAccount(
            businessId,
            code,
            name,
            accountType,
            null,
            null,
            true
        );
    }


    // ========================================================
    // CREATE ACCOUNT — ENHANCED METHOD
    // ========================================================

    /**
     * Create a normal account or grouping account.
     *
     * @param businessId       Owner business.
     * @param code             Account code.
     * @param name             Account name.
     * @param accountType      Fundamental account type.
     * @param reportingGroup    Financial statement category.
     * @param parentAccountId   Optional parent account ID.
     * @param postable          Whether ledger entries are allowed.
     *
     * A null reportingGroup means unclassified.
     *
     * A null parentAccountId means a top-level account.
     *
     * A null postable value defaults to true for
     * backward compatibility.
     *
     * PostgreSQL also validates these relationships,
     * including concurrent database modifications.
     *
     * The account insertion and reporting revision
     * increment commit atomically.
     */
    @Transactional
    public Account createAccount(
            Long businessId,
            String code,
            String name,
            String accountType,
            String reportingGroup,
            Long parentAccountId,
            Boolean postable) {

        // ====================================================
        // STEP 1 — VALIDATE BASIC ACCOUNT INFORMATION
        // ====================================================

        if (code == null || code.isBlank()) {

            throw new IllegalArgumentException(
                "Account code cannot be blank"
            );
        }

        if (name == null || name.isBlank()) {

            throw new IllegalArgumentException(
                "Account name cannot be blank"
            );
        }

        List<String> validTypes = List.of(
            "ASSET",
            "LIABILITY",
            "EQUITY",
            "REVENUE",
            "EXPENSE"
        );

        if (accountType == null ||
                !validTypes.contains(accountType)) {

            throw new IllegalArgumentException(
                "Invalid account type"
            );
        }


        // ====================================================
        // STEP 2 — VALIDATE BUSINESS EXISTENCE
        // ====================================================

        validateBusinessExists(businessId);


        // ====================================================
        // STEP 3 — VALIDATE REPORTING CLASSIFICATION
        // ====================================================

        /*
         * A reporting group must be compatible with
         * the fundamental account type.
         *
         * Example:
         *
         * ASSET -> CURRENT_ASSET      Valid
         *
         * ASSET -> OPERATING_REVENUE  Invalid
         *
         * Null is permitted for existing accounts and
         * accounts awaiting classification.
         */

        validateReportingGroup(
            accountType,
            reportingGroup
        );


        // ====================================================
        // STEP 4 — VALIDATE OPTIONAL PARENT ACCOUNT
        // ====================================================

        /*
         * A parent account must:
         *
         * - Exist in the same business.
         * - Have the same fundamental account type.
         * - Be a non-postable grouping account.
         *
         * Database triggers enforce these rules again
         * to prevent concurrency-related bypasses.
         */

        if (parentAccountId != null) {

            Account parentAccount = accountMapper.findById(
                businessId,
                parentAccountId
            );

            // =================================================
            // REQUIRE PARENT IN THE SAME BUSINESS
            // =================================================

            if (parentAccount == null) {

                throw new IllegalArgumentException(
                    "Parent account does not exist in this business"
                );
            }


            // =================================================
            // REQUIRE COMPATIBLE ACCOUNT TYPES
            // =================================================

            if (!accountType.equals(
                    parentAccount.getAccountType())) {

                throw new IllegalArgumentException(
                    "Parent and child accounts must have "
                    + "the same account type"
                );
            }


            // =================================================
            // REQUIRE NON-POSTABLE PARENT
            // =================================================

            if (!Boolean.FALSE.equals(
                    parentAccount.getPostable())) {

                throw new IllegalArgumentException(
                    "Parent account must be non-postable"
                );
            }
        }


        // ====================================================
        // STEP 5 — CREATE ACCOUNT MODEL
        // ====================================================

        Account account = new Account();

        account.setBusinessId(businessId);

        account.setCode(code.trim());

        account.setName(name.trim());

        account.setAccountType(accountType);


        // ====================================================
        // SET REPORTING CLASSIFICATION
        // ====================================================

        account.setReportingGroup(reportingGroup);


        // ====================================================
        // SET OPTIONAL PARENT
        // ====================================================

        account.setParentAccountId(parentAccountId);


        // ====================================================
        // SET POSTING ELIGIBILITY
        // ====================================================

        /*
         * Default to TRUE when callers omit postable.
         *
         * This preserves the original Chart of Accounts
         * creation behavior.
         */
        account.setPostable(
            postable == null ? Boolean.TRUE : postable
        );


        // ====================================================
        // STEP 6 — INSERT ACCOUNT
        // ====================================================

        /*
         * MyBatis inserts the account and populates
         * its PostgreSQL-generated ID.
         *
         * Database triggers validate hierarchy and
         * posting restrictions.
         */
        accountMapper.insert(account);


        // ====================================================
        // ADVANCE BUSINESS REPORTING REVISION
        // ====================================================

        /**
         * Every account appears in Trial Balance, including
         * accounts with no posted financial activity.
         *
         * Account creation and the revision increment run
         * in the same Spring-managed database transaction.
         *
         * When an account import calls this method, both
         * operations join its outer import transaction.
         *
         * If either operation fails, both roll back.
         *
         * Do not update Redis directly here.
         */
        reportingRevisionService.advanceRevision(
            businessId
        );


        // ====================================================
        // STEP 7 — RELOAD CREATED ACCOUNT
        // ====================================================

        /*
         * Retrieve database-generated and default values:
         *
         * id
         * active
         * postable
         * createdAt
         *
         * This also returns the reporting fields.
         */
        return accountMapper.findById(
            businessId,
            account.getId()
        );
    }


    // ========================================================
    // RENAME ACCOUNT
    // ========================================================

    /**
     * Rename an active account.
     *
     * Account code, fundamental type, reporting group,
     * hierarchy, and posting eligibility are preserved.
     *
     * REPORTING CONSISTENCY:
     *
     * Trial Balance includes account names.
     *
     * A successful account rename must therefore
     * advance the durable business reporting revision.
     *
     * Both operations execute in one PostgreSQL
     * transaction and commit atomically.
     */
    @Transactional
    public boolean renameAccount(
            Long businessId,
            Long accountId,
            String newName) {

        // ====================================================
        // VALIDATE ACCOUNT NAME
        // ====================================================

        if (newName == null || newName.isBlank()) {

            throw new IllegalArgumentException(
                "Account name cannot be blank"
            );
        }


        // ====================================================
        // UPDATE ACCOUNT NAME
        // ====================================================

        boolean updated = accountMapper.updateName(
            businessId,
            accountId,
            newName.trim()
        ) > 0;


        // ====================================================
        // ADVANCE REVISION ON SUCCESSFUL UPDATE
        // ====================================================

        /**
         * The account update and revision increment
         * commit or roll back together.
         *
         * An unsuccessful update does not advance
         * the reporting revision.
         *
         * A successful update to the same existing name
         * may conservatively advance the revision.
         */
        if (updated) {

            reportingRevisionService.advanceRevision(
                businessId
            );
        }

        return updated;
    }


    // ========================================================
    // DEACTIVATE ACCOUNT
    // ========================================================

    /**
     * Deactivate an account without deleting it.
     *
     * This preserves existing ledger history.
     *
     * Deactivation changes is_active only.
     * It does not modify is_postable.
     *
     * REPORTING CONSISTENCY:
     *
     * Trial Balance includes inactive accounts
     * and returns their current active status.
     *
     * Deactivation and reporting revision advancement
     * must therefore commit atomically.
     */
    @Transactional
    public boolean deactivateAccount(
            Long businessId,
            Long accountId) {

        // ====================================================
        // UPDATE ACCOUNT ACTIVE STATUS
        // ====================================================

        boolean updated = accountMapper.deactivate(
            businessId,
            accountId
        ) > 0;


        // ====================================================
        // ADVANCE REVISION ON SUCCESSFUL UPDATE
        // ====================================================

        /**
         * Inactive accounts remain visible in Trial Balance.
         *
         * The status change and revision increment share
         * the same database transaction.
         *
         * This operation also participates in the outer
         * transaction when called by account import.
         */
        if (updated) {

            reportingRevisionService.advanceRevision(
                businessId
            );
        }

        return updated;
    }


    // ========================================================
    // UPDATE ACCOUNT REPORTING GROUP
    // ========================================================

    /**
     * Update an existing account's financial statement
     * reporting classification.
     *
     * Examples:
     *
     * ASSET:
     *   CURRENT_ASSET
     *   NON_CURRENT_ASSET
     *
     * REVENUE:
     *   OPERATING_REVENUE
     *   OTHER_INCOME
     *
     * EXPENSE:
     *   COST_OF_GOODS_SOLD
     *   OPERATING_EXPENSE
     *   OTHER_EXPENSE
     *
     * IMPORTANT ACCOUNTING RULES:
     *
     * 1. The account must belong to the requested business.
     *
     * 2. The reporting group must be compatible with
     *    the account's fundamental account type.
     *
     * 3. The operation must not modify the account's
     *    type, code, parent, or posting eligibility.
     *
     * 4. Inactive accounts may still be classified
     *    because they can contribute to historical
     *    financial statements.
     *
     * 5. Existing ledger entries and balances
     *    must remain unchanged.
     *
     * REPORTING CONSISTENCY:
     *
     * Reporting group changes affect financial
     * statement classifications.
     *
     * The reporting revision is therefore advanced
     * in the same PostgreSQL transaction.
     *
     * @param businessId Business owning the account.
     * @param accountId Account to update.
     * @param reportingGroup New reporting classification.
     * @return Updated account.
     */
    @Transactional
    public Account updateReportingGroup(
        Long businessId,
        Long accountId,
        String reportingGroup
    ) {

        // ====================================================
        // STEP 1 — VERIFY ACCOUNT OWNERSHIP
        // ====================================================

        /**
         * Reuse our existing business-scoped account lookup.
         *
         * An account belonging to another business must
         * not be accessible through this operation.
         */
        Account account = getAccount(
            businessId,
            accountId
        );


        // ====================================================
        // VERIFY ACCOUNT EXISTS IN REQUESTED BUSINESS
        // ====================================================

        /**
         * A business-scoped lookup returns null when:
         *
         * - The account does not exist, or
         * - The account belongs to another business.
         *
         * Return HTTP 404 rather than allowing a
         * NullPointerException to produce HTTP 500.
         *
         * Using the same response for both cases
         * avoids revealing whether another business
         * owns the requested account.
         */
        if (account == null) {

            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Account not found"
            );
        }


        // ====================================================
        // STEP 2 — VALIDATE REQUIRED CLASSIFICATION
        // ====================================================

        /**
         * This endpoint assigns a reporting group.
         *
         * Null and blank values are not accepted.
         * The request DTO also enforces this rule.
         */
        if (
            reportingGroup == null ||
            reportingGroup.isBlank()
        ) {

            throw new IllegalArgumentException(
                "Reporting group is required"
            );
        }


        // ====================================================
        // STEP 3 — VALIDATE TYPE/GROUP COMPATIBILITY
        // ====================================================

        /**
         * Reuse the existing reporting group validation.
         *
         * For example:
         *
         * ASSET + CURRENT_ASSET -> Valid
         *
         * ASSET + OPERATING_REVENUE -> Invalid
         */
        validateReportingGroup(
            account.getAccountType(),
            reportingGroup
        );


        // ====================================================
        // STEP 4 — UPDATE REPORTING GROUP THROUGH MYBATIS
        // ====================================================

        /**
         * The mapper uses:
         *
         * WHERE business_id = #{businessId}
         *   AND id = #{accountId}
         *
         * This ensures the update remains business-scoped.
         */
        int updatedRows = accountMapper.updateReportingGroup(
            businessId,
            accountId,
            reportingGroup
        );


        // ====================================================
        // STEP 5 — VERIFY UPDATE RESULT
        // ====================================================

        /**
         * A successful update affects exactly one account.
         *
         * If the row disappears between reading and updating,
         * do not silently report success.
         */
        if (updatedRows != 1) {

            throw new IllegalStateException(
                "Unable to update account reporting group"
            );
        }


        // ====================================================
        // STEP 6 — ADVANCE REPORTING REVISION
        // ====================================================

        /**
         * Reporting classifications affect financial
         * statements even though the current Trial Balance
         * response does not expose reportingGroup.
         *
         * The update and revision share one transaction.
         *
         * If revision advancement fails, the reporting
         * group update must also roll back.
         */
        reportingRevisionService.advanceRevision(
            businessId
        );


        // ====================================================
        // STEP 7 — RETURN UPDATED ACCOUNT
        // ====================================================

        /**
         * Reload the account from PostgreSQL.
         *
         * This ensures the REST API receives the
         * persisted reporting classification.
         */
        return getAccount(
            businessId,
            accountId
        );
    }


    // ========================================================
    // HELPER — VALIDATE BUSINESS EXISTENCE
    // ========================================================

    /**
     * Shared validation used by account creation
     * and account balance retrieval.
     *
     * Preserve the existing BusinessNotFoundException
     * behavior for nonexistent businesses.
     */
    private void validateBusinessExists(Long businessId) {

        if (
            businessId == null ||
            !businessMapper.existsById(businessId)
        ) {

            throw new BusinessNotFoundException(
                businessId
            );
        }
    }


    // ========================================================
    // HELPER — VALIDATE REPORTING GROUP
    // ========================================================

    /**
     * Restrict reporting groups according to account type.
     *
     * We explicitly classify accounts rather than
     * guessing their meaning from account codes.
     *
     * This mirrors PostgreSQL's reporting group constraint.
     *
     * Null is allowed until an account is classified.
     */
    private void validateReportingGroup(
            String accountType,
            String reportingGroup) {

        if (reportingGroup == null) {
            return;
        }

        List<String> allowedGroups = switch (accountType) {

            case "ASSET" -> List.of(
                "CURRENT_ASSET",
                "NON_CURRENT_ASSET"
            );

            case "LIABILITY" -> List.of(
                "CURRENT_LIABILITY",
                "NON_CURRENT_LIABILITY"
            );

            case "EQUITY" -> List.of(
                "CONTRIBUTED_CAPITAL",
                "RETAINED_EARNINGS",
                "OTHER_EQUITY"
            );

            case "REVENUE" -> List.of(
                "OPERATING_REVENUE",
                "OTHER_INCOME"
            );

            case "EXPENSE" -> List.of(
                "COST_OF_GOODS_SOLD",
                "OPERATING_EXPENSE",
                "OTHER_EXPENSE"
            );

            default -> List.of();
        };

        if (!allowedGroups.contains(reportingGroup)) {

            throw new IllegalArgumentException(
                "Reporting group " + reportingGroup
                + " is invalid for account type "
                + accountType
            );
        }
    }
}
