package com.ledgerbase.backend.service;

import com.ledgerbase.backend.exception.BusinessNotFoundException;

import com.ledgerbase.backend.mapper.AccountingPeriodMapper;
import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.mapper.FiscalYearMapper;

import com.ledgerbase.backend.model.AccountingPeriod;
import com.ledgerbase.backend.model.FiscalYear;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// ============================================================
// LEDGERBASE — ACCOUNTING PERIOD SERVICE
// ============================================================

/**
 * Business logic for accounting-period management.
 *
 * Each fiscal year contains 12 monthly accounting periods.
 *
 * Example:
 *
 * Business: LedgerBase Demo Business
 * Fiscal Year: FY2026
 *
 * Period 1  = January 2026
 * Period 2  = February 2026
 * ...
 * Period 10 = October 2026
 * ...
 * Period 12 = December 2026
 *
 * For a business using an April-starting fiscal year:
 *
 * Period 1  = April
 * Period 2  = May
 * ...
 * Period 12 = March of the following calendar year
 *
 * This initial service provides READ-ONLY operations.
 *
 * Closing and reopening will be added after journal
 * posting checks have been implemented and tested.
 *
 * IMPORTANT:
 *
 * All operations must remain scoped to one business.
 */
@Service
public class AccountingPeriodService {

    // ========================================================
    // DATABASE MAPPERS
    // ========================================================

    /**
     * Checks whether a business exists.
     */
    private final BusinessMapper businessMapper;

    /**
     * Retrieves fiscal-year information.
     */
    private final FiscalYearMapper fiscalYearMapper;

    /**
     * Retrieves accounting periods.
     */
    private final AccountingPeriodMapper accountingPeriodMapper;


    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    /**
     * Spring injects all three MyBatis mappers
     * through constructor injection.
     */
    public AccountingPeriodService(
            BusinessMapper businessMapper,
            FiscalYearMapper fiscalYearMapper,
            AccountingPeriodMapper accountingPeriodMapper) {

        this.businessMapper = businessMapper;
        this.fiscalYearMapper = fiscalYearMapper;
        this.accountingPeriodMapper = accountingPeriodMapper;
    }


    // ========================================================
    // GET PERIODS FOR A FISCAL YEAR
    // ========================================================

    /**
     * Retrieve all accounting periods belonging
     * to a specific fiscal year.
     *
     * Example:
     *
     * getPeriodsForFiscalYear(1L, 5L)
     *
     * Returns Periods 1 through 12 for fiscal year 5,
     * provided that fiscal year belongs to Business 1.
     *
     * The mapper orders periods by period_number ASC.
     *
     * Returns:
     *
     * - A list of periods when the fiscal year exists.
     * - null when the fiscal year does not exist
     *   or belongs to another business.
     *
     * Returning null for a missing fiscal year lets
     * our future REST controller return HTTP 404.
     *
     * An existing fiscal year with no periods would
     * produce an empty list, which is distinct
     * from a missing fiscal year.
     */
    @Transactional(readOnly = true)
    public List<AccountingPeriod> getPeriodsForFiscalYear(
            Long businessId,
            Long fiscalYearId) {

        // ====================================================
        // VALIDATE BUSINESS
        // ====================================================

        validateBusinessExists(businessId);


        // ====================================================
        // VALIDATE FISCAL YEAR ID
        // ====================================================

        if (fiscalYearId == null || fiscalYearId <= 0) {

            throw new IllegalArgumentException(
                "Fiscal year ID must be positive"
            );
        }


        // ====================================================
        // VERIFY FISCAL YEAR OWNERSHIP
        // ====================================================

        /**
         * This query includes both business_id and id.
         *
         * It prevents one business from retrieving
         * another business's accounting periods.
         */
        FiscalYear fiscalYear = fiscalYearMapper.findById(
            businessId,
            fiscalYearId
        );

        if (fiscalYear == null) {

            return null;
        }


        // ====================================================
        // RETRIEVE ACCOUNTING PERIODS
        // ====================================================

        /**
         * MyBatis maps each database row into an
         * AccountingPeriod Java object.
         *
         * Results include:
         *
         * - Period number
         * - Start and end dates
         * - OPEN or CLOSED status
         * - Closing timestamp, when applicable
         */
        return accountingPeriodMapper.findByFiscalYearId(
            businessId,
            fiscalYearId
        );
    }


    // ========================================================
    // GET ONE ACCOUNTING PERIOD
    // ========================================================

    /**
     * Retrieve an individual accounting period
     * belonging to a business.
     *
     * Returns null when the period does not exist
     * or belongs to another business.
     *
     * A REST controller can translate null into
     * HTTP 404 Not Found.
     */
    @Transactional(readOnly = true)
    public AccountingPeriod getAccountingPeriod(
            Long businessId,
            Long periodId) {

        // ====================================================
        // VALIDATE BUSINESS
        // ====================================================

        validateBusinessExists(businessId);


        // ====================================================
        // VALIDATE PERIOD ID
        // ====================================================

        if (periodId == null || periodId <= 0) {

            throw new IllegalArgumentException(
                "Accounting period ID must be positive"
            );
        }


        // ====================================================
        // RETRIEVE BUSINESS-SCOPED PERIOD
        // ====================================================

        return accountingPeriodMapper.findById(
            businessId,
            periodId
        );
    }


    // ========================================================
    // VALIDATE BUSINESS EXISTENCE
    // ========================================================

    /**
     * Reject requests for nonexistent businesses.
     *
     * Our existing BusinessNotFoundException
     * is handled by the global API exception handler
     * and produces HTTP 404.
     */
    private void validateBusinessExists(Long businessId) {

        if (businessId == null ||
                !businessMapper.existsById(businessId)) {

            throw new BusinessNotFoundException(businessId);
        }
    }
}
