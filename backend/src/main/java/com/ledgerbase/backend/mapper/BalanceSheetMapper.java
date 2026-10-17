package com.ledgerbase.backend.mapper;

import com.ledgerbase.backend.model.BalanceSheetRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

// ============================================================
// LEDGERBASE — BALANCE SHEET MYBATIS MAPPER
// ============================================================

/**
 * MyBatis mapper interface for Balance Sheet reporting.
 *
 * This interface connects the Java service layer to
 * PostgreSQL queries defined in:
 *
 * backend/src/main/resources/mapper/BalanceSheetMapper.xml
 *
 * RESPONSIBILITIES:
 *
 * 1. Retrieve account balances as of a reporting date.
 *
 * 2. Include only POSTED journal transactions.
 *
 * 3. Restrict all results to the requested business.
 *
 * 4. Include inactive accounts with historical balances.
 *
 * 5. Exclude non-postable grouping accounts.
 *
 * 6. Preserve unclassified accounts for validation.
 *
 * 7. Preserve exact monetary amounts using BigDecimal.
 *
 * 8. Retrieve Revenue and Expense balances so the
 *    service can calculate unclosed earnings.
 *
 * IMPORTANT:
 *
 * This mapper performs READ-ONLY reporting.
 *
 * It does not:
 *
 * - Create or modify accounts
 * - Create or post transactions
 * - Update ledger entries
 * - Close accounting periods
 * - Transfer earnings to retained earnings
 *
 * All database aggregation uses PostgreSQL NUMERIC.
 */
@Mapper
public interface BalanceSheetMapper {

    // ========================================================
    // FIND ACCOUNT BALANCES AS OF A REPORTING DATE
    // ========================================================

    /**
     * Retrieve account-level balances for one business
     * through an inclusive reporting cutoff date.
     *
     * EXAMPLE:
     *
     * businessId = 1
     * asOfDate   = 2026-10-17
     *
     * Includes POSTED journal transactions dated
     * October 17, 2026 or earlier.
     *
     * Transactions after this date are excluded.
     *
     * DRAFT transactions never contribute to balances.
     *
     * ========================================================
     * ACCOUNTING CALCULATIONS
     * ========================================================
     *
     * ASSET:
     *     Debits - Credits
     *
     * LIABILITY:
     *     Credits - Debits
     *
     * EQUITY:
     *     Credits - Debits
     *
     * REVENUE:
     *     Credits - Debits
     *
     * EXPENSE:
     *     Debits - Credits
     *
     * Negative balances remain negative.
     *
     * ========================================================
     * BALANCE SHEET TREATMENT
     * ========================================================
     *
     * ASSET, LIABILITY, and EQUITY accounts are
     * displayed in their reporting sections.
     *
     * REVENUE and EXPENSE accounts are used by the
     * service to calculate unclosed earnings.
     *
     * The mapper does not independently create
     * retained earnings or year-end closing entries.
     *
     * ========================================================
     * ACCOUNT ELIGIBILITY
     * ========================================================
     *
     * Include:
     *
     * - Active posting accounts
     * - Inactive posting accounts
     * - Accounts with zero posted activity
     * - Accounts without reporting classifications
     *
     * Exclude:
     *
     * - Non-postable grouping accounts
     * - Accounts belonging to other businesses
     *
     * Each result includes postedEntryCount.
     *
     * An unclassified account with posted entries
     * must not be silently omitted, even when
     * its net balance equals zero.
     *
     * ========================================================
     * PARAMETERS
     * ========================================================
     *
     * @param businessId Business whose ledger is reported.
     * @param asOfDate Inclusive reporting cutoff date.
     *
     * @return Account-level Balance Sheet reporting rows.
     */
    List<BalanceSheetRow> findByBusinessIdAsOfDate(

        @Param("businessId")
        Long businessId,

        @Param("asOfDate")
        LocalDate asOfDate

    );

}
