package com.ledgerbase.backend.service;

import com.ledgerbase.backend.dto.BalanceSheetResponse;
import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.mapper.BalanceSheetMapper;
import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.model.BalanceSheetRow;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ============================================================
// LEDGERBASE — BALANCE SHEET SERVICE
// ============================================================

/**
 * Business logic for Balance Sheet financial reporting.
 *
 * REPORTING RULES:
 *
 * 1. Read cumulative account balances as of one date.
 *
 * 2. Include only POSTED transactions.
 *
 * 3. Include historical balances from inactive accounts.
 *
 * 4. Exclude non-postable grouping accounts.
 *
 * 5. Use V7 reporting groups for financial sections.
 *
 * 6. Include unclassified account balances in totals.
 *
 * 7. Calculate unclosed earnings from remaining
 *    Revenue and Expense account balances.
 *
 * 8. Verify the accounting equation:
 *
 *    Assets = Liabilities + Equity
 *
 * 9. Preserve monetary precision using BigDecimal.
 *
 * IMPORTANT:
 *
 * This service performs read-only financial reporting.
 *
 * It does not:
 *
 * - Modify journal entries
 * - Post transactions
 * - Close fiscal years
 * - Transfer income into retained earnings
 * - Create artificial balancing entries
 */
@Service
public class BalanceSheetService {

    // ========================================================
    // DATABASE DEPENDENCIES
    // ========================================================

    /**
     * Retrieves account-level balances from PostgreSQL.
     */
    private final BalanceSheetMapper balanceSheetMapper;

    /**
     * Validates that the requested business exists.
     */
    private final BusinessMapper businessMapper;


    // ========================================================
    // BALANCE SHEET REPORTING GROUP DEFINITIONS
    // ========================================================

    /**
     * Each group corresponds to an existing V7
     * reporting_group database classification.
     *
     * Order matters because the frontend will display
     * sections in the same order returned by the API.
     *
     * We do not introduce any new database values.
     */
    private static final List<ReportingGroup> REPORTING_GROUPS =
        List.of(

            // ================================================
            // ASSETS
            // ================================================

            new ReportingGroup(
                "CURRENT_ASSET",
                "Current Assets",
                "ASSET"
            ),

            new ReportingGroup(
                "NON_CURRENT_ASSET",
                "Non-current Assets",
                "ASSET"
            ),


            // ================================================
            // LIABILITIES
            // ================================================

            new ReportingGroup(
                "CURRENT_LIABILITY",
                "Current Liabilities",
                "LIABILITY"
            ),

            new ReportingGroup(
                "NON_CURRENT_LIABILITY",
                "Non-current Liabilities",
                "LIABILITY"
            ),


            // ================================================
            // EQUITY
            // ================================================

            new ReportingGroup(
                "CONTRIBUTED_CAPITAL",
                "Contributed Capital",
                "EQUITY"
            ),

            new ReportingGroup(
                "RETAINED_EARNINGS",
                "Retained Earnings",
                "EQUITY"
            ),

            new ReportingGroup(
                "OTHER_EQUITY",
                "Other Equity",
                "EQUITY"
            )
        );


    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    /**
     * Spring injects both mapper dependencies.
     *
     * Constructor injection makes the service's
     * database dependencies explicit and testable.
     */
    public BalanceSheetService(
        BalanceSheetMapper balanceSheetMapper,
        BusinessMapper businessMapper
    ) {

        this.balanceSheetMapper = balanceSheetMapper;
        this.businessMapper = businessMapper;
    }


    // ========================================================
    // GENERATE BALANCE SHEET
    // ========================================================

    /**
     * Generate a Balance Sheet for one business.
     *
     * @param businessId Business being reported.
     * @param asOfDate Inclusive reporting cutoff date.
     *
     * @return Complete Balance Sheet API response.
     *
     * All calculations operate on BigDecimal.
     *
     * Only the final API response converts monetary
     * amounts into two-decimal-place strings.
     */
    @Transactional(readOnly = true)
    public BalanceSheetResponse getBalanceSheet(
        Long businessId,
        LocalDate asOfDate
    ) {

        // ====================================================
        // STEP 1 — VALIDATE REQUEST
        // ====================================================

        validateBusinessExists(businessId);

        if (asOfDate == null) {

            throw new IllegalArgumentException(
                "Balance Sheet reporting date is required"
            );
        }


        // ====================================================
        // STEP 2 — RETRIEVE POSTED ACCOUNT BALANCES
        // ====================================================

        /**
         * The mapper performs the PostgreSQL aggregation.
         *
         * Its SQL:
         *
         * - Filters by business_id.
         * - Includes only POSTED transactions.
         * - Applies transaction_date <= asOfDate.
         * - Excludes non-postable accounts.
         * - Preserves inactive accounts.
         * - Returns all five accounting types.
         */
        List<BalanceSheetRow> accountRows =
            balanceSheetMapper.findByBusinessIdAsOfDate(
                businessId,
                asOfDate
            );


        // ====================================================
        // STEP 3 — INITIALIZE REPORTING GROUP CONTAINERS
        // ====================================================

        /**
         * LinkedHashMap preserves the display order
         * defined in REPORTING_GROUPS.
         *
         * Every reporting group will appear in the
         * response even when it contains no accounts.
         */
        Map<String, List<BalanceSheetResponse.AccountLine>>
            groupedAccounts = new LinkedHashMap<>();

        Map<String, BigDecimal> groupSubtotals =
            new LinkedHashMap<>();

        Map<String, ReportingGroup> groupDefinitions =
            new LinkedHashMap<>();


        for (ReportingGroup group : REPORTING_GROUPS) {

            groupedAccounts.put(
                group.key(),
                new ArrayList<>()
            );

            groupSubtotals.put(
                group.key(),
                BigDecimal.ZERO
            );

            groupDefinitions.put(
                group.key(),
                group
            );
        }


        // ====================================================
        // STEP 4 — INITIALIZE UNCLASSIFIED ACCOUNT LIST
        // ====================================================

        /**
         * Unclassified Asset, Liability, and Equity
         * accounts with posted activity are recorded
         * separately.
         *
         * Their balances still contribute to totals.
         */
        List<BalanceSheetResponse.AccountLine>
            unclassifiedAccounts = new ArrayList<>();


        // ====================================================
        // STEP 5 — INITIALIZE FINANCIAL TOTALS
        // ====================================================

        /**
         * These are Java BigDecimal accumulators.
         *
         * Never use double or float for accounting
         * calculations.
         */
        BigDecimal totalAssets = BigDecimal.ZERO;

        BigDecimal totalLiabilities = BigDecimal.ZERO;

        BigDecimal postedEquity = BigDecimal.ZERO;

        BigDecimal totalRevenue = BigDecimal.ZERO;

        BigDecimal totalExpenses = BigDecimal.ZERO;


        // ====================================================
        // STEP 6 — PROCESS EACH GENERAL LEDGER ACCOUNT
        // ====================================================

        for (BalanceSheetRow row : accountRows) {

            // ================================================
            // READ ACCOUNT TYPE AND BALANCE
            // ================================================

            String accountType = row.getAccountType();

            BigDecimal balance = row.getBalance();

            if (balance == null) {

                throw new IllegalStateException(
                    "Balance Sheet mapper returned a null balance"
                );
            }


            // ================================================
            // READ POSTED ACTIVITY COUNT
            // ================================================

            /**
             * The mapper returns zero for accounts
             * without posted activity.
             */
            Long entryCount = row.getPostedEntryCount();

            if (entryCount == null) {

                throw new IllegalStateException(
                    "Balance Sheet mapper returned a null entry count"
                );
            }


            // ================================================
            // AGGREGATE FUNDAMENTAL ACCOUNT TYPES
            // ================================================

            /**
             * The mapper has already normalized balances
             * according to the account's normal direction.
             *
             * Asset and Expense:
             *     Debit - Credit
             *
             * Liability, Equity, and Revenue:
             *     Credit - Debit
             */
            switch (accountType) {

                case "ASSET" -> {

                    totalAssets = totalAssets.add(balance);
                }

                case "LIABILITY" -> {

                    totalLiabilities =
                        totalLiabilities.add(balance);
                }

                case "EQUITY" -> {

                    postedEquity = postedEquity.add(balance);
                }

                case "REVENUE" -> {

                    totalRevenue = totalRevenue.add(balance);
                }

                case "EXPENSE" -> {

                    totalExpenses = totalExpenses.add(balance);
                }

                default -> throw new IllegalStateException(
                    "Unsupported account type in Balance Sheet: "
                    + accountType
                );
            }


            // ================================================
            // REVENUE AND EXPENSE HANDLING
            // ================================================

            /**
             * Revenue and Expense accounts do not appear
             * as individual Balance Sheet section lines.
             *
             * Their balances contribute to unclosed
             * earnings later in the calculation.
             *
             * We therefore skip the classification
             * logic below for these two account types.
             */
            if (
                "REVENUE".equals(accountType)
                || "EXPENSE".equals(accountType)
            ) {

                continue;
            }


            // ================================================
            // BUILD ACCOUNT RESPONSE LINE
            // ================================================

            BalanceSheetResponse.AccountLine accountLine =
                new BalanceSheetResponse.AccountLine(

                    row.getAccountId(),

                    row.getCode(),

                    row.getName(),

                    accountType,

                    Boolean.TRUE.equals(row.getActive()),

                    formatMoney(balance),

                    entryCount
                );


            // ================================================
            // HANDLE UNCLASSIFIED ACCOUNTS
            // ================================================

            String reportingGroup = row.getReportingGroup();

            if (reportingGroup == null) {

                /**
                 * An account without posted activity does
                 * not make the report classification-
                 * incomplete.
                 *
                 * An account with offsetting entries,
                 * however, must still be classified.
                 *
                 * Its balance may be zero even though
                 * postedEntryCount is positive.
                 *
                 * A nonzero balance is included as an
                 * additional defensive condition.
                 */
                if (
                    entryCount > 0
                    || balance.compareTo(BigDecimal.ZERO) != 0
                ) {

                    unclassifiedAccounts.add(accountLine);
                }

                /**
                 * The balance has already been added
                 * to the overall account-type total.
                 *
                 * Therefore, no financial amount is lost.
                 */
                continue;
            }


            // ================================================
            // VALIDATE CLASSIFICATION
            // ================================================

            /**
             * PostgreSQL V7 enforces reporting-group
             * compatibility.
             *
             * We also validate the group in Java
             * to prevent unexpected data from being
             * silently ignored.
             */
            ReportingGroup definition =
                groupDefinitions.get(reportingGroup);

            if (
                definition == null
                || !definition.accountType().equals(accountType)
            ) {

                throw new IllegalStateException(
                    "Invalid Balance Sheet reporting group "
                    + reportingGroup
                    + " for account "
                    + row.getAccountId()
                );
            }


            // ================================================
            // ADD ACCOUNT TO ITS REPORTING SECTION
            // ================================================

            groupedAccounts
                .get(reportingGroup)
                .add(accountLine);


            // ================================================
            // UPDATE SECTION SUBTOTAL
            // ================================================

            BigDecimal currentSubtotal =
                groupSubtotals.get(reportingGroup);

            groupSubtotals.put(
                reportingGroup,
                currentSubtotal.add(balance)
            );
        }


        // ====================================================
        // STEP 7 — BUILD ORDERED REPORT SECTIONS
        // ====================================================

        List<BalanceSheetResponse.Section> sections =
            new ArrayList<>();

        for (ReportingGroup group : REPORTING_GROUPS) {

            sections.add(
                new BalanceSheetResponse.Section(

                    group.key(),

                    group.label(),

                    group.accountType(),

                    List.copyOf(
                        groupedAccounts.get(group.key())
                    ),

                    formatMoney(
                        groupSubtotals.get(group.key())
                    )
                )
            );
        }


        // ====================================================
        // STEP 8 — CALCULATE UNCLOSED EARNINGS
        // ====================================================

        /**
         * Unclosed Earnings =
         *
         *     Total Revenue - Total Expenses
         *
         * This includes the remaining cumulative balances
         * of all Revenue and Expense accounts through the
         * selected reporting date.
         *
         * It is not restricted to the current fiscal year.
         *
         * A proper future year-end closing journal would:
         *
         * - Clear the relevant Revenue balances.
         * - Clear the relevant Expense balances.
         * - Transfer the net amount to Equity.
         *
         * Once those entries are posted, those amounts
         * are represented in postedEquity rather than
         * unclosedEarnings.
         *
         * This prevents double-counting.
         */
        BigDecimal unclosedEarnings =
            totalRevenue.subtract(totalExpenses);


        // ====================================================
        // STEP 9 — CALCULATE TOTAL EQUITY
        // ====================================================

        /**
         * Total Equity =
         *
         * Posted Equity + Unclosed Earnings
         */
        BigDecimal totalEquity =
            postedEquity.add(unclosedEarnings);


        // ====================================================
        // STEP 10 — CALCULATE LIABILITIES AND EQUITY
        // ====================================================

        BigDecimal totalLiabilitiesAndEquity =
            totalLiabilities.add(totalEquity);


        // ====================================================
        // STEP 11 — RECONCILE ACCOUNTING EQUATION
        // ====================================================

        /**
         * Difference =
         *
         * Assets - (Liabilities + Equity)
         *
         * We calculate the difference directly.
         *
         * We must not:
         *
         * - Use an artificial plug balance.
         * - Remove an account to force equality.
         * - Round away a genuine discrepancy.
         */
        BigDecimal difference =
            totalAssets.subtract(
                totalLiabilitiesAndEquity
            );


        // ====================================================
        // STEP 12 — DETERMINE BALANCED STATUS
        // ====================================================

        /**
         * compareTo() compares numeric BigDecimal values.
         *
         * It correctly treats:
         *
         * 0
         * 0.0
         * 0.00
         *
         * as equivalent monetary amounts.
         */
        boolean balanced =
            difference.compareTo(BigDecimal.ZERO) == 0;


        // ====================================================
        // STEP 13 — DETERMINE CLASSIFICATION COMPLETENESS
        // ====================================================

        /**
         * Classification completeness is independent
         * from the accounting equation.
         *
         * A report can:
         *
         * - Balance mathematically.
         * - Still contain unclassified accounts.
         *
         * Both statuses are important.
         */
        boolean classificationComplete =
            unclassifiedAccounts.isEmpty();


        // ====================================================
        // STEP 14 — RETURN COMPLETE API RESPONSE
        // ====================================================

        /**
         * All monetary values are converted to decimal
         * strings only after calculations finish.
         *
         * This prevents JavaScript floating-point
         * precision errors in the React frontend.
         */
        return new BalanceSheetResponse(

            businessId,

            asOfDate,

            classificationComplete,

            List.copyOf(sections),

            List.copyOf(unclassifiedAccounts),

            formatMoney(totalAssets),

            formatMoney(totalLiabilities),

            formatMoney(postedEquity),

            formatMoney(unclosedEarnings),

            formatMoney(totalEquity),

            formatMoney(totalLiabilitiesAndEquity),

            formatMoney(difference),

            balanced
        );
    }


    // ========================================================
    // VALIDATE BUSINESS EXISTENCE
    // ========================================================

    /**
     * Every Balance Sheet belongs to one business.
     *
     * Invalid or nonexistent business identifiers
     * must not produce misleading financial reports.
     *
     * BusinessNotFoundException is handled by
     * LedgerBase's existing exception handler.
     */
    private void validateBusinessExists(Long businessId) {

        if (
            businessId == null
            || businessId <= 0
            || !businessMapper.existsById(businessId)
        ) {

            throw new BusinessNotFoundException(businessId);
        }
    }


    // ========================================================
    // FORMAT EXACT MONETARY AMOUNTS
    // ========================================================

    /**
     * Convert a BigDecimal into a two-decimal-place
     * string for API serialization.
     *
     * Examples:
     *
     * BigDecimal("1250") -> "1250.00"
     *
     * BigDecimal("42.5") -> "42.50"
     *
     * BigDecimal("-15") -> "-15.00"
     *
     * UNNECESSARY means Java must not round away
     * fractional cents.
     *
     * LedgerBase's current financial reporting
     * uses two-decimal-place monetary amounts.
     */
    private String formatMoney(BigDecimal amount) {

        return amount
            .setScale(2, RoundingMode.UNNECESSARY)
            .toPlainString();
    }


    // ========================================================
    // INTERNAL REPORTING GROUP DEFINITION
    // ========================================================

    /**
     * Small internal record describing one of the
     * seven existing Balance Sheet reporting groups.
     *
     * This is not a database entity or API DTO.
     *
     * It keeps group identifiers, display labels,
     * and account types together.
     */
    private record ReportingGroup(

        String key,

        String label,

        String accountType

    ) {
    }
}
