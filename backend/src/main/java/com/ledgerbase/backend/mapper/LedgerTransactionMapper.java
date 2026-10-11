package com.ledgerbase.backend.mapper;

import com.ledgerbase.backend.model.LedgerTransaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis mapper for the PostgreSQL "transactions" table.
 *
 * This interface declares database operations.
 *
 * The SQL queries will be written separately in
 * resources/mapper/LedgerTransactionMapper.xml.
 *
 * MyBatis generates the implementation automatically,
 * so we do not need to write a Java implementation class.
 */
@Mapper
public interface LedgerTransactionMapper {

    /**
     * Insert a new transaction into PostgreSQL.
     *
     * After insertion, MyBatis will populate the
     * generated ID on the LedgerTransaction object.
     *
     * Returns the number of rows inserted.
     */
    int insert(LedgerTransaction transaction);

    /**
     * Find a transaction using its business ID and transaction ID.
     *
     * Including businessId ensures queries are scoped to
     * a specific business.
     *
     * Returns null when no matching transaction exists.
     */
    LedgerTransaction findById(
        @Param("businessId") Long businessId,
        @Param("transactionId") Long transactionId
    );

    /**
     * Retrieve all transactions belonging to a business.
     *
     * Newest transactions appear first.
     */
    List<LedgerTransaction> findByBusinessId(
        @Param("businessId") Long businessId
    );

    /**
     * Change a transaction from DRAFT to POSTED.
     *
     * Only DRAFT transactions may be posted.
     *
     * Returns 1 when the update succeeds and
     * 0 when no matching draft transaction exists.
     */
    int markAsPosted(
        @Param("businessId") Long businessId,
        @Param("transactionId") Long transactionId
    );
}
