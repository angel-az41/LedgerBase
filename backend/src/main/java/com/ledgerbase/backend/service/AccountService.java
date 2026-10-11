package com.ledgerbase.backend.service;

import com.ledgerbase.backend.mapper.AccountMapper;
import com.ledgerbase.backend.model.Account;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.exception.BusinessNotFoundException;

import java.util.List;

/**
 * Business logic for LedgerBase financial accounts.
 *
 * The service coordinates database operations and applies
 * rules before accounts are created or modified.
 */
@Service
public class AccountService {

    // Database access for financial accounts.
    private final AccountMapper accountMapper;

    // Database access for checking business existence.
    private final BusinessMapper businessMapper;

    /**
     * Spring injects both MyBatis mappers automatically.
     */
    public AccountService(
            AccountMapper accountMapper,
            BusinessMapper businessMapper) {

        this.accountMapper = accountMapper;
        this.businessMapper = businessMapper;
    }

    /**
     * Retrieve a business's Chart of Accounts.
     */
    public List<Account> getAccounts(Long businessId) {
        return accountMapper.findByBusinessId(businessId);
    }

    /**
     * Retrieve one financial account.
     *
     * Returns null if the account does not exist.
     * We'll add structured 404 handling in the API layer.
     */
    public Account getAccount(Long businessId, Long accountId) {
        return accountMapper.findById(businessId, accountId);
    }

    /**
     * Create a financial account.
     *
     * Account codes are customizable; we do not assume
     * that every ASSET account must start with "1".
     *
     * Transaction management ensures the INSERT and subsequent SELECT
     * execute within the same database transaction.
     *
     * If either operation fails, the database changes
     * are rolled back automatically.
     */
    @Transactional
    public Account createAccount(
            Long businessId,
            String code,
            String name,
            String accountType) {

        // Reject blank account codes and names.
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

        // Only these five fundamental account types are valid.
        List<String> validTypes = List.of(
            "ASSET", "LIABILITY", "EQUITY",
            "REVENUE", "EXPENSE"
        );

        if (accountType == null ||
                !validTypes.contains(accountType)) {
            throw new IllegalArgumentException(
                "Invalid account type"
            );
        }

        // Do not create an account for a nonexistent business.
        if (businessId == null || !businessMapper.existsById(businessId)) {
            throw new BusinessNotFoundException(businessId);
        }

        Account account = new Account();
        account.setBusinessId(businessId);
        account.setCode(code.trim());
        account.setName(name.trim());
        account.setAccountType(accountType);

        // PostgreSQL generates the account ID.
        accountMapper.insert(account);

        // Reload so the response includes database defaults,
        // such as active and createdAt.
        return accountMapper.findById(
            businessId, account.getId()
        );
    }

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

        // MyBatis returns the number of affected rows.
        return accountMapper.updateName(
            businessId, accountId, newName.trim()
        ) > 0;
    }

    /**
     * Deactivate an account without deleting its history.
     *
     * This helps preserve references from ledger entries.
     */
    public boolean deactivateAccount(
            Long businessId,
            Long accountId) {

        return accountMapper.deactivate(
            businessId, accountId
        ) > 0;
    }
}
