package com.ledgerbase.backend.service;

import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.mapper.TrialBalanceMapper;
import com.ledgerbase.backend.model.TrialBalanceReport;
import com.ledgerbase.backend.model.TrialBalanceRow;
import com.ledgerbase.backend.reporting.ReplicaTrialBalanceReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
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
 * RESPONSIBILITIES:
 *
 * - Validate the requested business
 * - Validate the reporting date
 * - Select the appropriate reporting database
 * - Retrieve posted accounting balances through MyBatis
 * - Calculate total debit and credit balances
 * - Construct the complete Trial Balance report
 *
 * ACCOUNTING RULES:
 *
 * - Include only POSTED transactions.
 * - Exclude transactions after the reporting date.
 * - Include zero-balance and inactive accounts.
 * - Calculate monetary amounts using BigDecimal.
 * - Preserve visibility of unbalanced results.
 *
 * DATABASE ROUTING:
 *
 * Primary PostgreSQL:
 *   Used for business validation and the default
 *   Trial Balance query.
 *
 * Replica PostgreSQL:
 *   Used for the Trial Balance aggregation when
 *   replica reporting is enabled.
 *
 * IMPORTANT:
 *
 * PostgreSQL streaming replication is asynchronous.
 * Reports generated through the replica may briefly
 * lag behind recently committed transactions.
 *
 * Reports requiring immediate consistency should
 * continue to use the primary database.
 */
@Service
public class TrialBalanceService {

    // ========================================================
    // LOGGING
    // ========================================================

    /**
     * Log report execution and database selection.
     *
     * These messages help verify that requests
     * reach the service and use the intended
     * PostgreSQL connection.
     */
    private static final Logger log =
        LoggerFactory.getLogger(
            TrialBalanceService.class
        );

    // ========================================================
    // PRIMARY DATABASE DEPENDENCIES
    // ========================================================

    /**
     * Default MyBatis mapper connected to
     * the primary PostgreSQL database.
     */
    private final TrialBalanceMapper trialBalanceMapper;

    /**
     * Verify business existence through
     * the primary database.
     */
    private final BusinessMapper businessMapper;

    // ========================================================
    // OPTIONAL READ REPLICA DEPENDENCY
    // ========================================================

    /**
     * Replica-backed Trial Balance reader.
     *
     * Spring creates this component only when
     * ledgerbase.read-replica.enabled=true.
     *
     * It is optional so the application can
     * continue running without a replica.
     */
    private ReplicaTrialBalanceReader replicaTrialBalanceReader;

    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    /**
     * Preserve the existing constructor so ordinary
     * application startup and existing tests can
     * continue injecting the primary MyBatis mappers.
     */
    public TrialBalanceService(
            TrialBalanceMapper trialBalanceMapper,
            BusinessMapper businessMapper) {

        this.trialBalanceMapper = trialBalanceMapper;
        this.businessMapper = businessMapper;
    }

    // ========================================================
    // OPTIONAL REPLICA INJECTION
    // ========================================================

    /**
     * Inject the replica reader when available.
     *
     * If no replica is configured, this field
     * remains null and primary reporting is used.
     */
    @Autowired(required = false)
    public void setReplicaTrialBalanceReader(
            ReplicaTrialBalanceReader replicaTrialBalanceReader) {

        this.replicaTrialBalanceReader =
            replicaTrialBalanceReader;
    }

    // ========================================================
    // GENERATE TRIAL BALANCE REPORT
    // ========================================================

    /**
     * Generate the Trial Balance for a business
     * as of the specified reporting date.
     *
     * TRANSACTION MANAGEMENT:
     *
     * The primary-backed service operations execute
     * within a Spring read-only transaction.
     *
     * When replica reporting is enabled, the replica
     * query uses a separate DataSource and does not
     * share the primary database's MVCC snapshot.
     *
     * The report must therefore tolerate replication
     * lag and eventual consistency.
     *
     * @param businessId requested business ID
     * @param asOfDate inclusive reporting cutoff date
     * @return complete Trial Balance report
     */
    @Transactional(readOnly = true)
    public TrialBalanceReport generateTrialBalance(
            Long businessId,
            LocalDate asOfDate) {

        // ====================================================
        // STEP 1 — LOG THE REPORT REQUEST
        // ====================================================

        /**
         * This message confirms that the frontend
         * request reached TrialBalanceService.
         *
         * If this message does not appear after
         * generating a report, investigate the
         * controller endpoint and frontend request.
         */
        log.info(
            "Trial Balance requested: businessId={}, "
                + "asOfDate={}, replicaReaderEnabled={}",
            businessId,
            asOfDate,
            replicaTrialBalanceReader != null
        );

        // ====================================================
        // STEP 2 — VALIDATE BUSINESS EXISTENCE
        // ====================================================

        /**
         * A nonexistent business must not produce
         * an apparently valid but empty report.
         *
         * Business existence validation is distinct
         * from user authorization.
         *
         * JWT authentication, RBAC, and business
         * authorization must remain enforced
         * by the application's security layer.
         */
        if (businessId == null ||
                !businessMapper.existsById(businessId)) {

            throw new BusinessNotFoundException(
                businessId
            );
        }

        // ====================================================
        // STEP 3 — VALIDATE REPORTING DATE
        // ====================================================

        /**
         * Every Trial Balance requires a reporting date.
         *
         * Historical dates and today's date are valid.
         * Future reporting dates are rejected.
         */

        if (asOfDate == null) {

            throw new IllegalArgumentException(
                "Trial Balance reporting date is required"
            );
        }

        LocalDate today = LocalDate.now();

        if (asOfDate.isAfter(today)) {

            throw new IllegalArgumentException(
                "Trial Balance reporting date cannot be in the future"
            );
        }

        // ====================================================
        // STEP 4 — SELECT THE REPORTING DATABASE
        // ====================================================

        /**
         * REPLICA MODE:
         *
         * Execute the Trial Balance SQL using the
         * dedicated read-only replica connection.
         *
         * DEFAULT MODE:
         *
         * Execute the same SQL using the existing
         * primary-backed MyBatis mapper.
         *
         * This is explicit reporting query routing.
         *
         * We do not automatically route every
         * @Transactional(readOnly = true) operation
         * to the replica because some reads require
         * strong consistency.
         */

        List<TrialBalanceRow> rows;

        if (replicaTrialBalanceReader != null) {

            log.info(
                "Trial Balance routing: REPLICA "
                    + "(PostgreSQL read-only standby)"
            );

            rows =
                replicaTrialBalanceReader
                    .findByBusinessIdAsOfDate(
                        businessId,
                        asOfDate
                    );

        } else {

            log.info(
                "Trial Balance routing: PRIMARY "
                    + "(default MyBatis datasource)"
            );

            rows =
                trialBalanceMapper.findByBusinessIdAsOfDate(
                    businessId,
                    asOfDate
                );
        }

        // ====================================================
        // STEP 5 — INITIALIZE MONETARY TOTALS
        // ====================================================

        /**
         * All monetary arithmetic uses BigDecimal
         * to preserve exact decimal calculations.
         *
         * Never use double or float for financial
         * accounting balances.
         */
        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;

        // ====================================================
        // STEP 6 — CALCULATE REPORT TOTALS
        // ====================================================

        /**
         * Iterate through the account balances
         * returned by PostgreSQL.
         *
         * The mapper already separates each
         * account's net amount into its Debit
         * and Credit balance columns.
         *
         * This service calculates the complete
         * report's debit and credit totals.
         */
        for (TrialBalanceRow row : rows) {

            // ================================================
            // REQUIRE NON-NULL FINANCIAL VALUES
            // ================================================

            /**
             * SQL should always return non-null
             * amounts using COALESCE and GREATEST.
             *
             * Unexpected null values should fail
             * explicitly rather than being silently
             * converted to zero.
             */
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

            // ================================================
            // AGGREGATE ACCOUNT BALANCES
            // ================================================

            totalDebits =
                totalDebits.add(debitBalance);

            totalCredits =
                totalCredits.add(creditBalance);
        }

        // ====================================================
        // STEP 7 — LOG REPORT COMPLETION
        // ====================================================

        /**
         * Log accounting totals and account count
         * for local verification.
         *
         * Do not log passwords, tokens,
         * or sensitive customer information.
         */
        log.info(
            "Trial Balance completed: accountCount={}, "
                + "totalDebits={}, totalCredits={}",
            rows.size(),
            totalDebits,
            totalCredits
        );

        // ====================================================
        // STEP 8 — CONSTRUCT COMPLETE REPORT
        // ====================================================

        /**
         * TrialBalanceReport stores:
         *
         * - Business ID
         * - Reporting date
         * - Account balances
         * - Total debit balances
         * - Total credit balances
         *
         * Its existing constructor calculates
         * the difference and balanced status.
         *
         * An unbalanced result must remain visible
         * instead of being falsely marked balanced.
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
