package com.ledgerbase.backend.mapper;

import com.ledgerbase.backend.model.Account;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis mapper for the accounts table.
 *
 * This interface declares database operations.
 * Their SQL implementations are defined in
 * resources/mapper/AccountMapper.xml.
 */
@Mapper
public interface AccountMapper {

    /**
     * Get all accounts belonging to a business.
     *
     * @param businessId ID of the business.
     * @return Accounts ordered by account code.
     */
    List<Account> findByBusinessId(
        @Param("businessId") Long businessId
    );

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

    /**
     * Insert a new account.
     *
     * PostgreSQL generates the primary key.
     * MyBatis writes the generated ID into account.id.
     */
    int insert(Account account);

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
