package com.ledgerbase.backend.mapper;

import com.ledgerbase.backend.model.IncomeStatementRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

// ============================================================
// LEDGERBASE — INCOME STATEMENT MYBATIS MAPPER
// ============================================================

/**
 * MyBatis mapper interface for Income Statement reporting.
 *
 * This interface connects the Java service layer to
 * the PostgreSQL queries defined in:
 *
 * backend/src/main/resources/mapper/IncomeStatementMapper.xml
 *
 * Responsibilities:
 *
 * - Retrieve revenue and expense account activity
 * - Restrict results to one business
 * - Support a selected reporting date range
 * - Include only POSTED journal transactions
 * - Include inactive accounts with relevant history
 * - Preserve exact monetary values as BigDecimal
 * - Identify unclassified accounts with posted activity
 *
 * IMPORTANT:
 *
 * This mapper performs READ-ONLY reporting.
 *
 * It does not modify transactions, ledger entries,
 * accounts, or accounting periods.
 *
 * All financial calculations happen in PostgreSQL
 * using NUMERIC arithmetic.
 */
@Mapper
public interface IncomeStatementMapper {

    // ========================================================
    // FIND INCOME STATEMENT ROWS FOR A DATE RANGE
    // ========================================================

    /**
     * Retrieve account-level financial activity for
     * an Income Statement reporting period.
     *
     * Example:
     *
     * businessId = 1
     * startDate  = 2026-10-01
     * endDate    = 2026-10-31
     *
     * The date range is inclusive:
     *
     * 2026-10-01 <= transaction_date <= 2026-10-31
     *
     * ACCOUNTING RULES:
     *
     * 1. Only REVENUE and EXPENSE accounts are included.
     *
     * 2. Only POSTED transactions contribute to amounts.
     *
     * 3. Revenue uses:
     *
     *    credits - debits
     *
     * 4. Expenses use:
     *
     *    debits - credits
     *
     * 5. Grouping accounts are excluded from direct
     *    financial activity calculations.
     *
     * 6. Inactive posting accounts are included to
     *    preserve historical financial reporting.
     *
     * 7. Accounts with no posted activity may return
     *    a zero amount and zero posted entry count.
     *
     * 8. Unclassified accounts are not silently removed.
     *    Their reportingGroup remains null.
     *
     * The service layer will validate the date range
     * and calculate reporting-group subtotals and
     * final net income using BigDecimal.
     *
     * @param businessId Business whose accounts are reported.
     * @param startDate Inclusive reporting start date.
     * @param endDate Inclusive reporting end date.
     * @return Account-level Income Statement rows.
     */
    List<IncomeStatementRow> findByBusinessIdAndDateRange(

        @Param("businessId")
        Long businessId,

        @Param("startDate")
        LocalDate startDate,

        @Param("endDate")
        LocalDate endDate

    );

}
