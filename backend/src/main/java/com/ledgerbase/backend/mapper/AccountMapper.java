package com.ledgerbase.backend.mapper;

import com.ledgerbase.backend.model.Account;
import com.ledgerbase.backend.model.AccountBalance;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

// ============================================================
// LEDGERBASE — ACCOUNT MAPPER
// ============================================================

/**
 * MyBatis mapper for the accounts table.
 *
 * This interface declares database operations.
 * Their SQL implementations are defined in
 * resources/mapper/AccountMapper.xml.
 *
 * Responsibilities:
 *
 * - Retrieve accounts belonging to a business
 * - Retrieve calculated account balances
 * - Retrieve an individual account
 * - Create new accounts
 * - Rename existing accounts
 * - Deactivate accounts
 *
 * All queries must respect business-level isolation.
 */
@Mapper
public interface AccountMapper {

    // ========================================================
    // GET ALL ACCOUNTS FOR A BUSINESS
    // ========================================================

    /**
     * Get all accounts belonging to a business.
     *
     * @param businessId ID of the business.
     * @return Accounts ordered by account code.
     */
    List<Account> findByBusinessId(
        @Param("businessId") Long businessId
    );

    // ========================================================
    // GET CALCULATED ACCOUNT BALANCES
    // ========================================================

    /**
     * Retrieve account balances calculated from
     * POSTED journal entries only.
     *
     * The SQL query will:
     *
     * 1. Retrieve accounts belonging to the business.
     * 2. Join ledger entries with posted transactions.
     * 3. Calculate total posted debits and credits.
     * 4. Determine the balance using account type.
     * 5. Include accounts with no posted activity.
     *
     * IMPORTANT:
     *
     * - DRAFT transactions must not affect balances.
     * - Monetary calculations use PostgreSQL NUMERIC
     *   and Java BigDecimal.
     * - Negative balances are preserved.
     * - Balances are calculated, not stored.
     *
     * @param businessId ID of the business.
     * @return Account balances ordered by account code.
     */
    List<AccountBalance> findBalancesByBusinessId(
        @Param("businessId") Long businessId
    );

    // ========================================================
    // FIND ONE ACCOUNT
    // ========================================================

    /**
     * Find one account belonging to a specific business.
     *
     * Returns null if the account doesn't exist or
     * belongs to a different business.
     */
    Account findById(
        @Param("businessId") Long businessId,
        @Param("accountId") Long accountId
    );

    // ========================================================
    // INSERT NEW ACCOUNT
    // ========================================================

    /**
     * Insert a new account.
     *
     * PostgreSQL generates the primary key.
     * MyBatis writes the generated ID into account.id.
     */
    int insert(Account account);

    // ========================================================
    // UPDATE ACCOUNT NAME
    // ========================================================

    /**
     * Update an account's name.
     *
     * Account codes and types are intentionally not
     * modified by this initial update operation.
     */
    int updateName(
        @Param("businessId") Long businessId,
        @Param("accountId") Long accountId,
        @Param("name") String name
    );

    // ========================================================
    // DEACTIVATE ACCOUNT
    // ========================================================

    /**
     * Deactivate an account instead of deleting it.
     *
     * Keeping the account preserves historical ledger
     * entries that reference it.
     */
    int deactivate(
        @Param("businessId") Long businessId,
        @Param("accountId") Long accountId
    );
}
