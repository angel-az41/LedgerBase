package com.ledgerbase.reporting.mapper;

import com.ledgerbase.reporting.model.TrialBalanceRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

// ============================================================
// LEDGERBASE — REPORTING SERVICE TRIAL BALANCE MAPPER
// ============================================================

/**
 * Execute Trial Balance reporting SQL through MyBatis.
 *
 * DATABASE:
 *
 * This mapper uses the Reporting Service's datasource,
 * which connects to PostgreSQL's read-only replica.
 *
 * ACCOUNTING RULES:
 *
 * - Include POSTED transactions only.
 * - Apply the inclusive reporting cutoff date.
 * - Include zero-balance and inactive accounts.
 * - Restrict results to the requested business.
 * - Preserve monetary precision using BigDecimal.
 *
 * SECURITY:
 *
 * This mapper does not authenticate users.
 * Authentication and business-level authorization
 * must be enforced before a reporting API invokes it.
 *
 * The mapper must never modify financial records.
 */
@Mapper
public interface TrialBalanceMapper {

    // ========================================================
    // FIND ACCOUNT BALANCES AS OF REPORTING DATE
    // ========================================================

    /**
     * Retrieve account-level Trial Balance rows.
     *
     * @param businessId business being reported
     * @param asOfDate inclusive reporting cutoff date
     * @return account balances ordered by account code
     */
    List<TrialBalanceRow> findByBusinessIdAsOfDate(
        @Param("businessId") Long businessId,
        @Param("asOfDate") LocalDate asOfDate
    );
}
