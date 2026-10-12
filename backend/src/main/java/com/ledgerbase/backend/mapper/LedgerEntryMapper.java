package com.ledgerbase.backend.mapper;

import com.ledgerbase.backend.model.LedgerEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis mapper for the PostgreSQL ledger_entries table.
 *
 * Each ledger entry represents one debit or credit
 * belonging to a financial transaction.
 *
 * SQL implementations are stored separately in
 * resources/mapper/LedgerEntryMapper.xml.
 */
@Mapper
public interface LedgerEntryMapper {

    /**
     * Insert a ledger entry into PostgreSQL.
     *
     * MyBatis will populate the generated ID
     * on the LedgerEntry object.
     *
     * Returns the number of rows inserted.
     */
    int insert(LedgerEntry entry);

    /**
     * Retrieve all ledger entries for a transaction.
     *
     * Both businessId and transactionId are required
     * to keep the query scoped to one business.
     *
     * Entries are returned in ascending ID order.
     */
    List<LedgerEntry> findByTransactionId(
        @Param("businessId") Long businessId,
        @Param("transactionId") Long transactionId
    );
}
