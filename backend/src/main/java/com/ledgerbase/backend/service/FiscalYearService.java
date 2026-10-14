package com.ledgerbase.backend.service;

import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.mapper.AccountingPeriodMapper;
import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.mapper.FiscalYearMapper;
import com.ledgerbase.backend.model.AccountingPeriod;
import com.ledgerbase.backend.model.FiscalYear;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

// ============================================================
// LEDGERBASE — FISCAL YEAR SERVICE
// ============================================================

/**
 * Business logic for fiscal-year management.
 *
 * Responsibilities:
 *
 * 1. Retrieve fiscal years belonging to a business.
 * 2. Retrieve an individual fiscal year.
 * 3. Read the business's fiscal-year starting month.
 * 4. Calculate fiscal-year start and end dates.
 * 5. Prevent overlapping fiscal years.
 * 6. Create 12 monthly accounting periods automatically.
 *
 * IMPORTANT ACCOUNTING RULES:
 *
 * A fiscal year contains exactly 12 calendar months.
 *
 * The fiscal-year label uses the calendar year
 * in which the fiscal year ends.
 *
 * Examples:
 *
 * January starting month:
 *
 * FY2026 = January 1, 2026 - December 31, 2026
 *
 * April starting month:
 *
 * FY2027 = April 1, 2026 - March 31, 2027
 *
 * Creating the fiscal year and its 12 periods must
 * happen inside a single database transaction.
 */
@Service
public class FiscalYearService {

    // ========================================================
    // DATABASE MAPPERS
    // ========================================================

    /**
     * Reads business information and fiscal configuration.
     */
    private final BusinessMapper businessMapper;

    /**
     * Reads and inserts fiscal-year records.
     */
    private final FiscalYearMapper fiscalYearMapper;

    /**
     * Inserts the 12 monthly accounting periods.
     */
    private final AccountingPeriodMapper accountingPeriodMapper;


    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    /**
     * Spring automatically injects the three mappers.
     *
     * Constructor injection makes these dependencies
     * explicit and easier to test.
     */
    public FiscalYearService(
            BusinessMapper businessMapper,
            FiscalYearMapper fiscalYearMapper,
            AccountingPeriodMapper accountingPeriodMapper) {

        this.businessMapper = businessMapper;
        this.fiscalYearMapper = fiscalYearMapper;
        this.accountingPeriodMapper = accountingPeriodMapper;
    }


    // ========================================================
    // GET ALL FISCAL YEARS
    // ========================================================

    /**
     * Retrieve the fiscal years belonging to a business.
     *
     * Newest fiscal years appear first because the mapper
     * orders them by start_date DESC.
     *
     * A nonexistent business must return an error,
     * rather than an empty list.
     */
    @Transactional(readOnly = true)
    public List<FiscalYear> getFiscalYears(Long businessId) {

        validateBusinessExists(businessId);

        return fiscalYearMapper.findByBusinessId(businessId);
    }


    // ========================================================
    // GET ONE FISCAL YEAR
    // ========================================================

    /**
     * Retrieve a single fiscal year.
     *
     * The mapper checks both the business ID and
     * fiscal-year ID to maintain business isolation.
     *
     * Returns null if the fiscal year is not found.
     */
    @Transactional(readOnly = true)
    public FiscalYear getFiscalYear(
            Long businessId,
            Long fiscalYearId) {

        validateBusinessExists(businessId);

        if (fiscalYearId == null || fiscalYearId <= 0) {

            throw new IllegalArgumentException(
                "Fiscal year ID must be positive"
            );
        }

        return fiscalYearMapper.findById(
            businessId,
            fiscalYearId
        );
    }


    // ========================================================
    // CREATE FISCAL YEAR
    // ========================================================

    /**
     * Create a fiscal year and all 12 monthly periods.
     *
     * fiscalYearEndYear determines the FY label.
     *
     * For example:
     *
     * createFiscalYear(1L, 2027)
     *
     * If the business starts in January:
     *
     * FY2027 = Jan 1, 2027 - Dec 31, 2027
     *
     * If the business starts in April:
     *
     * FY2027 = Apr 1, 2026 - Mar 31, 2027
     *
     * @Transactional is essential:
     *
     * If any period INSERT fails, Spring rolls back
     * the entire operation, including the fiscal year.
     *
     * This prevents partially created fiscal years.
     */
    @Transactional
    public FiscalYear createFiscalYear(
            Long businessId,
            int fiscalYearEndYear) {

        // ====================================================
        // VALIDATE BUSINESS
        // ====================================================

        validateBusinessExists(businessId);


        // ====================================================
        // VALIDATE FISCAL YEAR
        // ====================================================

        /**
         * Restrict new fiscal years to a practical range
         * supported by this initial LedgerBase API.
         *
         * This is a product validation choice,
         * not a US accounting or tax requirement.
         */
        if (fiscalYearEndYear < 1900 ||
                fiscalYearEndYear > 9999) {

            throw new IllegalArgumentException(
                "Fiscal year must be between 1900 and 9999"
            );
        }


        // ====================================================
        // GET BUSINESS FISCAL-YEAR CONFIGURATION
        // ====================================================

        /**
         * The database defaults this setting to January.
         *
         * We read the actual business configuration
         * instead of assuming every business uses
         * a January-December fiscal year.
         */
        Integer startMonth =
            businessMapper.findFiscalYearStartMonth(businessId);

        if (startMonth == null ||
                startMonth < 1 ||
                startMonth > 12) {

            throw new IllegalStateException(
                "Business has an invalid fiscal-year starting month"
            );
        }


        // ====================================================
        // CALCULATE FISCAL YEAR DATES
        // ====================================================

        /**
         * A January-starting fiscal year begins in its
         * label year.
         *
         * Any other starting month begins in the
         * previous calendar year.
         *
         * Example:
         *
         * FY2027 with April start:
         *
         * startYear = 2026
         * startMonth = 4
         */
        int startYear = startMonth == 1
            ? fiscalYearEndYear
            : fiscalYearEndYear - 1;

        LocalDate startDate = LocalDate.of(
            startYear,
            startMonth,
            1
        );

        /**
         * Calculate the final inclusive date.
         *
         * Adding 12 months gives the first day of the
         * next fiscal year.
         *
         * Subtracting one day gives the final date
         * belonging to the current fiscal year.
         */
        LocalDate endDate = startDate
            .plusMonths(12)
            .minusDays(1);

        String label = "FY" + fiscalYearEndYear;


        // ====================================================
        // VALIDATE AGAINST EXISTING FISCAL YEARS
        // ====================================================

        /**
         * Reject duplicate labels and overlapping years.
         *
         * This gives callers a clear validation error.
         *
         * PostgreSQL also enforces uniqueness and
         * non-overlapping ranges.
         *
         * Those database constraints remain necessary
         * for protection against simultaneous requests.
         */
        List<FiscalYear> existingYears =
            fiscalYearMapper.findByBusinessId(businessId);

        for (FiscalYear existing : existingYears) {

            if (label.equals(existing.getLabel())) {

                throw new IllegalArgumentException(
                    "Fiscal year " + label + " already exists"
                );
            }

            boolean datesOverlap =
                !startDate.isAfter(existing.getEndDate())
                && !endDate.isBefore(existing.getStartDate());

            if (datesOverlap) {

                throw new IllegalArgumentException(
                    "Fiscal year overlaps an existing fiscal year"
                );
            }
        }


        // ====================================================
        // CREATE FISCAL YEAR MODEL
        // ====================================================

        FiscalYear fiscalYear = new FiscalYear();

        fiscalYear.setBusinessId(businessId);
        fiscalYear.setLabel(label);
        fiscalYear.setStartDate(startDate);
        fiscalYear.setEndDate(endDate);


        // ====================================================
        // INSERT FISCAL YEAR
        // ====================================================

        /**
         * PostgreSQL generates the fiscal-year ID.
         *
         * MyBatis places that generated ID into
         * fiscalYear.getId().
         */
        int insertedYears = fiscalYearMapper.insert(fiscalYear);

        if (insertedYears != 1 || fiscalYear.getId() == null) {

            throw new IllegalStateException(
                "Failed to create fiscal year"
            );
        }


        // ====================================================
        // GENERATE 12 ACCOUNTING PERIODS
        // ====================================================

        /**
         * Periods are numbered relative to the
         * fiscal-year starting month.
         *
         * For an April-starting year:
         *
         * Period 1  = April
         * Period 2  = May
         * Period 3  = June
         * ...
         * Period 12 = March
         *
         * Every period initially has status OPEN.
         */
        for (int periodNumber = 1;
                periodNumber <= 12;
                periodNumber++) {

            // =================================================
            // CALCULATE MONTHLY PERIOD DATES
            // =================================================

            LocalDate periodStart = startDate.plusMonths(
                periodNumber - 1
            );

            LocalDate periodEnd = periodStart
                .plusMonths(1)
                .minusDays(1);


            // =================================================
            // CREATE ACCOUNTING PERIOD MODEL
            // =================================================

            AccountingPeriod period = new AccountingPeriod();

            period.setBusinessId(businessId);

            period.setFiscalYearId(
                fiscalYear.getId()
            );

            period.setPeriodNumber(periodNumber);

            period.setStartDate(periodStart);
            period.setEndDate(periodEnd);

            period.setStatus(
                AccountingPeriod.Status.OPEN
            );


            // =================================================
            // INSERT ACCOUNTING PERIOD
            // =================================================

            /**
             * The database enforces valid period numbers,
             * date ranges and business ownership.
             */
            int insertedPeriods =
                accountingPeriodMapper.insert(period);

            if (insertedPeriods != 1) {

                throw new IllegalStateException(
                    "Failed to create accounting period "
                    + periodNumber
                );
            }
        }


        // ====================================================
        // RETURN CREATED FISCAL YEAR
        // ====================================================

        /**
         * Reload the saved fiscal year so its response
         * includes database-generated fields such as:
         *
         * - id
         * - createdAt
         *
         * This follows our existing AccountService pattern.
         */
        return fiscalYearMapper.findById(
            businessId,
            fiscalYear.getId()
        );
    }


    // ========================================================
    // VALIDATE BUSINESS EXISTENCE
    // ========================================================

    /**
     * Shared validation used by service methods.
     *
     * A business must exist before its fiscal years
     * can be retrieved or created.
     *
     * Our existing exception handler can convert
     * BusinessNotFoundException into HTTP 404.
     */
    private void validateBusinessExists(Long businessId) {

        if (businessId == null ||
                !businessMapper.existsById(businessId)) {

            throw new BusinessNotFoundException(businessId);
        }
    }
}
