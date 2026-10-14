package com.ledgerbase.backend.service;

import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.mapper.TrialBalanceMapper;
import com.ledgerbase.backend.model.TrialBalanceReport;
import com.ledgerbase.backend.model.TrialBalanceRow;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

// ============================================================
// LEDGERBASE — TRIAL BALANCE SERVICE
// ============================================================

/**
 * Business logic for generating Trial Balance reports.
 *
 * The service coordinates:
 *
 * - Business existence validation
 * - Reporting date validation
 * - PostgreSQL Trial Balance calculations
 * - Debit and credit total calculations
 * - Complete report construction
 *
 * ACCOUNTING PRINCIPLES:
 *
 * 1. Only POSTED transactions contribute to reports.
 *
 * 2. Transactions after the reporting date are excluded.
 *
 * 3. Each account has a net debit or credit balance.
 *
 * 4. Total debit balances should equal total
 *    credit balances.
 *
 * 5. All monetary arithmetic uses BigDecimal.
 *
 * 6. Reports are read-only and do not change
 *    the underlying accounting records.
 *
 * The mapper handles SQL calculations.
 * This service handles report-level business logic.
 */
@Service
public class TrialBalanceService {

    // ========================================================
    // DATABASE DEPENDENCIES
    // ========================================================

    /**
     * Executes the Trial Balance reporting SQL
     * through MyBatis.
     */
    private final TrialBalanceMapper trialBalanceMapper;

    /**
     * Checks that the requested business exists.
     */
    private final BusinessMapper businessMapper;

    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    /**
     * Spring injects both mapper dependencies.
     *
     * Constructor injection makes our service
     * easier to maintain and unit test.
     */
    public TrialBalanceService(
            TrialBalanceMapper trialBalanceMapper,
            BusinessMapper businessMapper) {

        this.trialBalanceMapper = trialBalanceMapper;
        this.businessMapper = businessMapper;
    }

    // ========================================================
    // GENERATE TRIAL BALANCE REPORT
    // ========================================================

    /**
     * Generate a Trial Balance for one business
     * as of a specified reporting date.
     *
     * Example:
     *
     * businessId = 1
     * asOfDate   = 2026-10-13
     *
     * The report includes all posted journal
     * transactions dated October 13 or earlier.
     *
     * BUSINESS VALIDATION:
     *
     * A nonexistent business must not silently
     * return an empty financial report.
     *
     * REPORTING DATE:
     *
     * A date is required for every report.
     *
     * This will allow us to support fiscal years
     * and accounting periods in future milestones.
     *
     * TRANSACTION MANAGEMENT:
     *
     * readOnly = true tells Spring that this
     * operation is intended only to read data.
     *
     * The database queries and report calculations
     * take place within the same read-only transaction.
     *
     * @param businessId Business being reported.
     * @param asOfDate Inclusive reporting cutoff date.
     *
     * @return Complete Trial Balance report.
     */
    @Transactional(readOnly = true)
    public TrialBalanceReport generateTrialBalance(
            Long businessId,
            LocalDate asOfDate) {

        // ====================================================
        // STEP 1 — VALIDATE BUSINESS
        // ====================================================

        /**
         * Confirm the business ID is valid and exists.
         *
         * Use our existing BusinessNotFoundException
         * so the API can return HTTP 404.
         *
         * IMPORTANT:
         *
         * Business existence validation is not the
         * same as user authorization.
         *
         * Ownership and permission checks will
         * be implemented with authentication later.
         */
        if (businessId == null ||
                !businessMapper.existsById(businessId)) {

            throw new BusinessNotFoundException(businessId);
        }

        // ====================================================
        // STEP 2 — VALIDATE REPORTING DATE
        // ====================================================

        /**
         * A Trial Balance must have a reporting date.
         *
         * The controller will later receive this
         * through an HTTP query parameter.
         *
         * LocalDate represents a calendar date
         * without time-zone conversions.
         */
        if (asOfDate == null) {

            throw new IllegalArgumentException(
                "Trial Balance reporting date is required"
            );
        }

        // ====================================================
        // STEP 3 — RETRIEVE TRIAL BALANCE ROWS
        // ====================================================

        /**
         * Ask MyBatis to execute the PostgreSQL query.
         *
         * The query:
         *
         * - Filters by business
         * - Includes POSTED transactions only
         * - Applies the reporting date cutoff
         * - Aggregates net debit/credit activity
         * - Includes zero-balance accounts
         * - Includes inactive accounts
         *
         * Each database result becomes a
         * TrialBalanceRow Java object.
         */
        List<TrialBalanceRow> rows =
            trialBalanceMapper.findByBusinessIdAsOfDate(
                businessId,
                asOfDate
            );

        // ====================================================
        // STEP 4 — INITIALIZE REPORT TOTALS
        // ====================================================

        /**
         * Start both totals at exactly zero.
         *
         * BigDecimal.ZERO is safe for
         * financial calculations.
         *
         * Do not use double or float.
         */
        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;

        // ====================================================
        // STEP 5 — CALCULATE DEBIT AND CREDIT TOTALS
        // ====================================================

        /**
         * Loop through every account row returned
         * by PostgreSQL.
         *
         * Add each account's net Debit Balance
         * to totalDebits.
         *
         * Add each account's net Credit Balance
         * to totalCredits.
         *
         * IMPORTANT:
         *
         * These totals represent account-level
         * net balances.
         *
         * They are not the gross sum of every
         * debit and credit journal posting.
         */
        for (TrialBalanceRow row : rows) {

            // ==================================================
            // REQUIRE VALID MONETARY VALUES
            // ==================================================

            /**
             * The SQL query should always return
             * non-null BigDecimal values because
             * it uses COALESCE and GREATEST.
             *
             * If a value is unexpectedly null,
             * fail rather than silently replacing
             * missing accounting data with zero.
             */
            BigDecimal debitBalance = Objects.requireNonNull(
                row.getDebitBalance(),
                "Trial Balance debit amount cannot be null"
            );

            BigDecimal creditBalance = Objects.requireNonNull(
                row.getCreditBalance(),
                "Trial Balance credit amount cannot be null"
            );

            // ==================================================
            // ADD EXACT DECIMAL AMOUNTS
            // ==================================================

            totalDebits = totalDebits.add(debitBalance);

            totalCredits = totalCredits.add(creditBalance);
        }

        // ====================================================
        // STEP 6 — CONSTRUCT COMPLETE REPORT
        // ====================================================

        /**
         * TrialBalanceReport stores:
         *
         * - Business ID
         * - Reporting date
         * - Account rows
         * - Total debit balances
         * - Total credit balances
         *
         * Its constructor automatically calculates:
         *
         * difference = totalDebits - totalCredits
         *
         * balanced = difference == 0
         *
         * An unbalanced result remains visible
         * rather than being falsely reported
         * as balanced.
         */
        return new TrialBalanceReport(
            businessId,
            asOfDate,
            rows,
            totalDebits,
            totalCredits
        );
    }
}
