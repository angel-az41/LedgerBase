package com.ledgerbase.backend.service;

import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.mapper.AccountMapper;
import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.model.Account;
import com.ledgerbase.backend.model.AccountBalance;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// ============================================================
// LEDGERBASE — ACCOUNT SERVICE
// ============================================================

/**
 * Business logic for LedgerBase financial accounts.
 *
 * The service coordinates database operations and applies
 * rules before accounts are created or modified.
 *
 * Responsibilities:
 *
 * - Retrieve a business's Chart of Accounts
 * - Retrieve calculated posted account balances
 * - Retrieve individual accounts
 * - Create new accounts
 * - Rename existing accounts
 * - Deactivate accounts
 *
 * Account balances are calculated from posted
 * ledger entries and are not stored separately.
 */
@Service
public class AccountService {

    // ========================================================
    // DATABASE MAPPERS
    // ========================================================

    /**
     * Database access for financial accounts
     * and calculated account balances.
     */
    private final AccountMapper accountMapper;

    /**
     * Database access for checking business existence.
     */
    private final BusinessMapper businessMapper;

    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    /**
     * Spring injects both MyBatis mappers automatically.
     */
    public AccountService(
            AccountMapper accountMapper,
            BusinessMapper businessMapper) {

        this.accountMapper = accountMapper;
        this.businessMapper = businessMapper;
    }

    // ========================================================
    // GET ALL ACCOUNTS
    // ========================================================

    /**
     * Retrieve a business's Chart of Accounts.
     */
    public List<Account> getAccounts(Long businessId) {

        return accountMapper.findByBusinessId(businessId);
    }

    // ========================================================
    // GET CALCULATED ACCOUNT BALANCES
    // ========================================================

    /**
     * Retrieve calculated account balances for a business.
     *
     * IMPORTANT ACCOUNTING RULES:
     *
     * 1. Only POSTED transactions contribute to balances.
     *
     * 2. DRAFT transactions are excluded.
     *
     * 3. Accounts with no posted activity have zero balances.
     *
     * 4. Inactive accounts remain included because they
     *    may contain historical posted transactions.
     *
     * 5. Monetary calculations use PostgreSQL NUMERIC
     *    and Java BigDecimal.
     *
     * 6. Balance signs depend on account type:
     *
     *    ASSET and EXPENSE:
     *        Debits - Credits
     *
     *    LIABILITY, EQUITY, and REVENUE:
     *        Credits - Debits
     *
     * This method is read-only and does not modify
     * transactions, ledger entries, or account records.
     *
     * @param businessId Business whose balances are requested.
     * @return Calculated balances for all business accounts.
     */
    @Transactional(readOnly = true)
    public List<AccountBalance> getAccountBalances(Long businessId) {

        // ====================================================
        // VALIDATE BUSINESS EXISTENCE
        // ====================================================

        /**
         * Reject requests for a business that does not exist.
         *
         * Without this check, a nonexistent business
         * would simply return an empty list.
         *
         * BusinessNotFoundException will allow our
         * API exception handler to return HTTP 404.
         */
        if (businessId == null ||
                !businessMapper.existsById(businessId)) {

            throw new BusinessNotFoundException(businessId);
        }

        // ====================================================
        // FETCH CALCULATED BALANCES FROM MYBATIS
        // ====================================================

        /**
         * AccountMapper.xml executes the PostgreSQL query.
         *
         * The query:
         *
         * - Joins ledger_entries with transactions
         * - Filters for POSTED transactions
         * - Aggregates debit and credit amounts
         * - Calculates account balances
         * - Includes accounts without posted activity
         *
         * MyBatis maps the results into AccountBalance
         * objects using BigDecimal monetary values.
         */
        return accountMapper.findBalancesByBusinessId(
            businessId
        );
    }

    // ========================================================
    // GET ONE ACCOUNT
    // ========================================================

    /**
     * Retrieve one financial account.
     *
     * Returns null if the account does not exist.
     * The API layer handles missing accounts.
     */
    public Account getAccount(Long businessId, Long accountId) {

        return accountMapper.findById(
            businessId,
            accountId
        );
    }

    // ========================================================
    // CREATE ACCOUNT
    // ========================================================

    /**
     * Create a financial account.
     *
     * Account codes are customizable; we do not assume
     * that every ASSET account must start with "1".
     *
     * Transaction management ensures the INSERT and
     * subsequent SELECT execute within the same
     * database transaction.
     *
     * If either operation fails, changes are rolled back.
     */
    @Transactional
    public Account createAccount(
            Long businessId,
            String code,
            String name,
            String accountType) {

        // ====================================================
        // VALIDATE ACCOUNT CODE
        // ====================================================

        if (code == null || code.isBlank()) {

            throw new IllegalArgumentException(
                "Account code cannot be blank"
            );
        }

        // ====================================================
        // VALIDATE ACCOUNT NAME
        // ====================================================

        if (name == null || name.isBlank()) {

            throw new IllegalArgumentException(
                "Account name cannot be blank"
            );
        }

        // ====================================================
        // VALIDATE ACCOUNT TYPE
        // ====================================================

        /**
         * Only the five fundamental account types
         * are currently supported.
         */
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
        // VALIDATE BUSINESS EXISTENCE
        // ====================================================

        /**
         * Do not create an account for a
         * nonexistent business.
         */
        if (businessId == null ||
                !businessMapper.existsById(businessId)) {

            throw new BusinessNotFoundException(businessId);
        }

        // ====================================================
        // CREATE ACCOUNT MODEL
        // ====================================================

        Account account = new Account();

        account.setBusinessId(businessId);
        account.setCode(code.trim());
        account.setName(name.trim());
        account.setAccountType(accountType);

        // ====================================================
        // INSERT ACCOUNT
        // ====================================================

        /**
         * PostgreSQL generates the account ID.
         */
        accountMapper.insert(account);

        // ====================================================
        // RETURN THE CREATED ACCOUNT
        // ====================================================

        /**
         * Reload the account so the response includes
         * database defaults such as active and createdAt.
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
     * Rename an active financial account.
     */
    public boolean renameAccount(
            Long businessId,
            Long accountId,
            String newName) {

        if (newName == null || newName.isBlank()) {

            throw new IllegalArgumentException(
                "Account name cannot be blank"
            );
        }

        /**
         * MyBatis returns the number of affected rows.
         */
        return accountMapper.updateName(
            businessId,
            accountId,
            newName.trim()
        ) > 0;
    }

    // ========================================================
    // DEACTIVATE ACCOUNT
    // ========================================================

    /**
     * Deactivate an account without deleting its history.
     *
     * This preserves references from ledger entries.
     */
    public boolean deactivateAccount(
            Long businessId,
            Long accountId) {

        return accountMapper.deactivate(
            businessId,
            accountId
        ) > 0;
    }
}
