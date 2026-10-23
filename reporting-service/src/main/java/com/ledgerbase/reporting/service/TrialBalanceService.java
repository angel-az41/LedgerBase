package com.ledgerbase.reporting.service;

import com.ledgerbase.reporting.mapper.TrialBalanceMapper;
import com.ledgerbase.reporting.model.TrialBalanceReport;
import com.ledgerbase.reporting.model.TrialBalanceRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

// ============================================================
// LEDGERBASE — TRIAL BALANCE REPORTING SERVICE
// ============================================================

/**
 * Generates Trial Balance financial reports using the
 * independent Reporting Service's PostgreSQL read replica.
 *
 * RESPONSIBILITIES:
 *
 * - Validate reporting parameters.
 * - Retrieve account balances through MyBatis.
 * - Calculate debit and credit totals.
 * - Construct the complete Trial Balance report.
 * - Preserve Accounting Core's report response structure.
 *
 * ACCOUNTING RULES:
 *
 * - Only POSTED transactions contribute to balances.
 * - Future-dated transactions are excluded.
 * - Inactive and zero-balance accounts are included.
 * - Financial calculations use BigDecimal.
 * - Unbalanced reports remain visible.
 *
 * SECURITY:
 *
 * JWT authentication and business authorization must
 * succeed before requests reach this service.
 *
 * DATABASE:
 *
 * All financial queries use the read-only PostgreSQL
 * replica configured in application.yaml.
 *
 * There is no fallback to the primary database.
 */
@Service
public class TrialBalanceService {

    private static final Logger log =
        LoggerFactory.getLogger(
            TrialBalanceService.class
        );

    // ========================================================
    // REPLICA-BACKED MYBATIS MAPPER
    // ========================================================

    private final TrialBalanceMapper trialBalanceMapper;

    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public TrialBalanceService(
        TrialBalanceMapper trialBalanceMapper
    ) {

        this.trialBalanceMapper = trialBalanceMapper;
    }

    // ========================================================
    // RETRIEVE TRIAL BALANCE ACCOUNT ROWS
    // ========================================================

    /**
     * Retrieve account balances from the read replica.
     *
     * Preserve this method because existing integration
     * tests already exercise it directly.
     *
     * @param businessId requested business
     * @param asOfDate inclusive reporting cutoff
     * @return account-level Trial Balance rows
     */
    @Transactional(readOnly = true)
    public List<TrialBalanceRow> getTrialBalance(
        Long businessId,
        LocalDate asOfDate
    ) {

        validateParameters(businessId, asOfDate);

        return trialBalanceMapper.findByBusinessIdAsOfDate(
            businessId,
            asOfDate
        );
    }

    // ========================================================
    // GENERATE COMPLETE TRIAL BALANCE REPORT
    // ========================================================

    /**
     * Generate the complete report using the same
     * financial calculations as Accounting Core.
     *
     * PostgreSQL calculates account-level balances.
     * This service calculates the report totals.
     *
     * @param businessId requested business
     * @param asOfDate inclusive reporting cutoff
     * @return complete Trial Balance report
     */
    @Transactional(readOnly = true)
    public TrialBalanceReport generateTrialBalance(
        Long businessId,
        LocalDate asOfDate
    ) {

        // ----------------------------------------------------
        // RETRIEVE ACCOUNT BALANCES
        // ----------------------------------------------------

        List<TrialBalanceRow> rows =
            getTrialBalance(
                businessId,
                asOfDate
            );

        // ----------------------------------------------------
        // INITIALIZE FINANCIAL TOTALS
        // ----------------------------------------------------

        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;

        // ----------------------------------------------------
        // AGGREGATE ACCOUNT BALANCES
        // ----------------------------------------------------

        for (TrialBalanceRow row : rows) {

            // Financial values must never silently
            // default to zero when unexpectedly null.

            BigDecimal debitBalance =
                Objects.requireNonNull(
                    row.getDebitBalance(),
                    "Trial Balance debit amount cannot be null"
                );

            BigDecimal creditBalance =
                Objects.requireNonNull(
                    row.getCreditBalance(),
                    "Trial Balance credit amount cannot be null"
                );

            // Use exact decimal arithmetic.

            totalDebits =
                totalDebits.add(debitBalance);

            totalCredits =
                totalCredits.add(creditBalance);
        }

        // ----------------------------------------------------
        // LOG REPORT COMPLETION
        // ----------------------------------------------------

        log.info(
            "Replica Trial Balance completed: "
                + "businessId={}, asOfDate={}, "
                + "accountCount={}, totalDebits={}, "
                + "totalCredits={}",
            businessId,
            asOfDate,
            rows.size(),
            totalDebits,
            totalCredits
        );

        // ----------------------------------------------------
        // CONSTRUCT FINANCIAL REPORT
        // ----------------------------------------------------

        /**
         * TrialBalanceReport automatically calculates:
         *
         * difference = totalDebits - totalCredits
         *
         * balanced = difference.compareTo(0) == 0
         *
         * An unbalanced result is returned honestly,
         * rather than being modified or suppressed.
         */

        return new TrialBalanceReport(
            businessId,
            asOfDate,
            rows,
            totalDebits,
            totalCredits
        );
    }

    // ========================================================
    // VALIDATE REPORT PARAMETERS
    // ========================================================

    /**
     * Require a positive business identifier and a
     * reporting date that is not in the future.
     *
     * Business membership authorization is enforced
     * separately through Accounting Core.
     */
    private void validateParameters(
        Long businessId,
        LocalDate asOfDate
    ) {

        if (
            businessId == null
            || businessId <= 0
        ) {
            throw new IllegalArgumentException(
                "A valid business ID is required"
            );
        }

        if (asOfDate == null) {
            throw new IllegalArgumentException(
                "Trial Balance reporting date is required"
            );
        }

        if (asOfDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException(
                "Trial Balance reporting date cannot be in the future"
            );
        }
    }
}
