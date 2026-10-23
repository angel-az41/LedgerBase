package com.ledgerbase.reporting.service;

import com.ledgerbase.reporting.mapper.TrialBalanceMapper;
import com.ledgerbase.reporting.model.TrialBalanceRow;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * ============================================================
 * LEDGERBASE — TRIAL BALANCE REPORTING SERVICE
 * ============================================================
 *
 * Application service responsible for retrieving Trial Balance
 * reporting data.
 *
 * Responsibilities:
 *
 * - Accept reporting parameters from the application layer.
 * - Delegate financial reporting queries to MyBatis.
 * - Use the Reporting Service's replica-backed datasource.
 * - Prevent application-level transaction writes.
 *
 * Accounting journal posting and financial data mutations
 * remain the responsibility of Accounting Core.
 *
 * Business authorization must be enforced before requests
 * reach this service through the reporting API.
 */
@Service
public class TrialBalanceService {

    private final TrialBalanceMapper trialBalanceMapper;

    /**
     * Constructor injection for the reporting mapper.
     */
    public TrialBalanceService(
            TrialBalanceMapper trialBalanceMapper) {

        this.trialBalanceMapper = trialBalanceMapper;
    }

    /**
     * Retrieve Trial Balance rows for a specific business
     * as of a given reporting date.
     *
     * The underlying SQL is executed through the Reporting
     * Service's configured PostgreSQL read replica.
     *
     * @param businessId business whose accounts are reported
     * @param asOfDate reporting cutoff date
     * @return account-level Trial Balance rows
     */
    @Transactional(readOnly = true)
    public List<TrialBalanceRow> getTrialBalance(
            Long businessId,
            LocalDate asOfDate) {

        if (businessId == null || businessId <= 0) {
            throw new IllegalArgumentException(
                    "A valid business ID is required.");
        }

        if (asOfDate == null) {
            throw new IllegalArgumentException(
                    "A reporting date is required.");
        }

        return trialBalanceMapper.findByBusinessIdAsOfDate(
                businessId,
                asOfDate
        );
    }
}
