package com.ledgerbase.backend.mapper;

import com.ledgerbase.backend.model.TrialBalanceRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

// ============================================================
// LEDGERBASE — TRIAL BALANCE MAPPER
// ============================================================

/**
 * MyBatis mapper for LedgerBase Trial Balance reporting.
 *
 * This interface declares the database operations
 * required to generate a Trial Balance report.
 *
 * The SQL implementation will be defined in:
 *
 * backend/src/main/resources/mapper/TrialBalanceMapper.xml
 *
 * RESPONSIBILITIES:
 *
 * - Retrieve posted account balances as of a date
 * - Separate debit balances from credit balances
 * - Include zero-balance accounts
 * - Include active and inactive accounts
 * - Enforce business-level data isolation
 *
 * IMPORTANT:
 *
 * This mapper is READ-ONLY.
 *
 * Financial reports must never modify:
 *
 * - Accounts
 * - Transactions
 * - Ledger entries
 *
 * All monetary calculations are performed using
 * PostgreSQL NUMERIC and mapped to Java BigDecimal.
 */
@Mapper
public interface TrialBalanceMapper {

    // ========================================================
    // GET TRIAL BALANCE ROWS FOR A BUSINESS
    // ========================================================

    /**
     * Retrieve Trial Balance rows for a business
     * as of a specified reporting date.
     *
     * ACCOUNTING RULES:
     *
     * 1. Include only POSTED journal transactions.
     *
     * 2. Include transactions dated on or before
     *    the specified reporting date.
     *
     * 3. Aggregate posted debits and credits
     *    separately for every general ledger account.
     *
     * 4. Calculate each account's net balance:
     *
     *        Net Balance = Debits - Credits
     *
     * 5. Place a positive net balance into
     *    the Debit column.
     *
     * 6. Place a negative net balance into
     *    the Credit column as a positive amount.
     *
     * 7. Include accounts with no posted activity.
     *
     * 8. Include inactive accounts to preserve
     *    visibility into historical balances.
     *
     * BUSINESS ISOLATION:
     *
     * Only accounts and journal entries belonging
     * to the requested business may be included.
     *
     * DATE FILTERING:
     *
     * The asOfDate parameter allows us to generate
     * historical Trial Balance reports.
     *
     * Example:
     *
     * Business ID: 1
     * As-of date: 2026-10-13
     *
     * Only transactions with transaction_date
     * on or before October 13, 2026 are included.
     *
     * This will also support fiscal-year and
     * accounting-period reporting in the future.
     *
     * @param businessId
     *        ID of the business requesting the report.
     *
     * @param asOfDate
     *        Last transaction date included in the report.
     *
     * @return
     *        Trial Balance rows ordered by account code.
     */
    List<TrialBalanceRow> findByBusinessIdAsOfDate(
        @Param("businessId") Long businessId,
        @Param("asOfDate") LocalDate asOfDate
    );
}
