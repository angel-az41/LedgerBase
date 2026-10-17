package com.ledgerbase.backend.service;

import com.ledgerbase.backend.dto.IncomeStatementResponse;
import com.ledgerbase.backend.mapper.IncomeStatementMapper;
import com.ledgerbase.backend.model.IncomeStatementRow;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ============================================================
// LEDGERBASE — INCOME STATEMENT SERVICE
// ============================================================

/**
 * Generates Income Statements using actual posted
 * ledger activity stored in PostgreSQL.
 *
 * Responsibilities:
 *
 * 1. Validate the requested business and date range.
 * 2. Retrieve account-level financial activity.
 * 3. Organize accounts into reporting groups.
 * 4. Calculate financial subtotals.
 * 5. Calculate total revenue and total expenses.
 * 6. Calculate net income.
 * 7. Detect unclassified accounts with posted activity.
 * 8. Return a structured response to the frontend.
 *
 * IMPORTANT ACCOUNTING RULES:
 *
 * - Only POSTED transactions affect financial amounts.
 * - DRAFT transactions are excluded.
 * - Revenue = Credits - Debits.
 * - Expenses = Debits - Credits.
 * - Net Income = Revenue - Expenses.
 * - Inactive accounts remain included.
 * - Grouping accounts are excluded from direct activity.
 * - Negative financial amounts remain valid.
 * - BigDecimal preserves monetary precision.
 *
 * The mapper performs database aggregation.
 *
 * This service performs reporting-group aggregation.
 *
 * The React frontend displays the results without
 * recalculating financial statement totals.
 */
@Service
public class IncomeStatementService {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    private final IncomeStatementMapper incomeStatementMapper;

    private final JdbcTemplate jdbcTemplate;


    // ========================================================
    // REPORTING GROUP DEFINITIONS
    // ========================================================

    /**
     * Defines one financial statement section.
     *
     * accountType determines whether a reporting group
     * belongs to REVENUE or EXPENSE.
     */
    private record ReportingGroupDefinition(
        String code,
        String label,
        String accountType
    ) {
    }

    /**
     * Define a stable order for Income Statement sections.
     *
     * Display revenue categories first, followed by
     * expense categories.
     *
     * All codes must match the V7 PostgreSQL schema.
     */
    private static final List<ReportingGroupDefinition>
        REPORTING_GROUPS = List.of(

            new ReportingGroupDefinition(
                "OPERATING_REVENUE",
                "Operating Revenue",
                "REVENUE"
            ),

            new ReportingGroupDefinition(
                "OTHER_INCOME",
                "Other Income",
                "REVENUE"
            ),

            new ReportingGroupDefinition(
                "COST_OF_GOODS_SOLD",
                "Cost of Goods Sold",
                "EXPENSE"
            ),

            new ReportingGroupDefinition(
                "OPERATING_EXPENSE",
                "Operating Expenses",
                "EXPENSE"
            ),

            new ReportingGroupDefinition(
                "OTHER_EXPENSE",
                "Other Expenses",
                "EXPENSE"
            )
        );


    // ========================================================
    // CONSTRUCTOR DEPENDENCY INJECTION
    // ========================================================

    /**
     * Spring Boot provides the mapper and JDBC template.
     *
     * IncomeStatementMapper retrieves the actual
     * financial activity.
     *
     * JdbcTemplate verifies that the requested
     * business exists.
     *
     * The business check does not authorize access.
     * Business-membership authorization will be added
     * with the portfolio authentication system.
     */
    public IncomeStatementService(
        IncomeStatementMapper incomeStatementMapper,
        JdbcTemplate jdbcTemplate
    ) {

        this.incomeStatementMapper =
            incomeStatementMapper;

        this.jdbcTemplate =
            jdbcTemplate;
    }


    // ========================================================
    // GENERATE INCOME STATEMENT
    // ========================================================

    /**
     * Generate an Income Statement for one business.
     *
     * Example:
     *
     * businessId = 1
     * startDate = 2026-10-01
     * endDate = 2026-10-31
     *
     * Both reporting dates are inclusive.
     *
     * This operation is read-only.
     *
     * It does not:
     *
     * - Create journal entries
     * - Post transactions
     * - Modify accounts
     * - Close accounting periods
     * - Update account balances
     *
     * @return Structured Income Statement response.
     */
    @Transactional(readOnly = true)
    public IncomeStatementResponse getIncomeStatement(
        Long businessId,
        LocalDate startDate,
        LocalDate endDate
    ) {

        // ====================================================
        // STEP 1 — VALIDATE INPUTS
        // ====================================================

        validateRequest(
            businessId,
            startDate,
            endDate
        );


        // ====================================================
        // STEP 2 — VERIFY BUSINESS EXISTS
        // ====================================================

        validateBusinessExists(businessId);


        // ====================================================
        // STEP 3 — RETRIEVE POSTED ACCOUNT ACTIVITY
        // ====================================================

        /**
         * MyBatis returns:
         *
         * - Revenue and Expense accounts
         * - Posted period activity
         * - Reporting classifications
         * - Posted entry counts
         *
         * All monetary amounts are BigDecimal.
         */
        List<IncomeStatementRow> rows =
            incomeStatementMapper.findByBusinessIdAndDateRange(
                businessId,
                startDate,
                endDate
            );


        // ====================================================
        // STEP 4 — PREPARE REPORTING GROUP COLLECTIONS
        // ====================================================

        /**
         * Each reporting group has:
         *
         * - A collection of account lines
         * - A calculated subtotal
         *
         * LinkedHashMap preserves the predefined
         * financial statement section order.
         */
        Map<String, List<IncomeStatementResponse.AccountLine>>
            accountsByGroup = new LinkedHashMap<>();

        Map<String, BigDecimal>
            subtotalsByGroup = new LinkedHashMap<>();

        for (ReportingGroupDefinition group : REPORTING_GROUPS) {

            accountsByGroup.put(
                group.code(),
                new ArrayList<>()
            );

            subtotalsByGroup.put(
                group.code(),
                BigDecimal.ZERO
            );
        }


        // ====================================================
        // STEP 5 — PREPARE FINANCIAL TOTALS
        // ====================================================

        /**
         * All totals include classified and unclassified
         * posting accounts.
         *
         * We do not silently discard financial activity
         * when an account lacks a reporting group.
         */
        BigDecimal totalRevenue = BigDecimal.ZERO;

        BigDecimal totalExpenses = BigDecimal.ZERO;


        // ====================================================
        // STEP 6 — PREPARE UNCLASSIFIED ACCOUNT LIST
        // ====================================================

        /**
         * Accounts with no reporting group but with
         * actual posted ledger activity appear here.
         *
         * They must be highlighted to the user.
         */
        List<IncomeStatementResponse.AccountLine>
            unclassifiedAccounts = new ArrayList<>();


        // ====================================================
        // STEP 7 — PROCESS EACH ACCOUNT
        // ====================================================

        for (IncomeStatementRow row : rows) {

            // ==================================================
            // VALIDATE MAPPER RESULTS
            // ==================================================

            /**
             * Do not silently substitute zero for a
             * missing amount or missing entry count.
             *
             * Such a result indicates an unexpected
             * database or mapping problem.
             */
            if (
                row.getAmount() == null ||
                row.getPostedEntryCount() == null
            ) {

                throw new IllegalStateException(
                    "Income Statement mapper returned "
                    + "incomplete financial data"
                );
            }

            BigDecimal amount = row.getAmount();

            long postedEntryCount =
                row.getPostedEntryCount();


            // ==================================================
            // VERIFY FUNDAMENTAL ACCOUNT TYPE
            // ==================================================

            /**
             * The mapper should return only REVENUE
             * and EXPENSE posting accounts.
             *
             * Reject unexpected account types
             * rather than producing incorrect totals.
             */
            String accountType = row.getAccountType();

            if (
                !"REVENUE".equals(accountType) &&
                !"EXPENSE".equals(accountType)
            ) {

                throw new IllegalStateException(
                    "Unexpected account type in "
                    + "Income Statement: " + accountType
                );
            }


            // ==================================================
            // BUILD FRONTEND ACCOUNT LINE
            // ==================================================

            /**
             * Convert BigDecimal to a decimal string.
             *
             * A negative amount remains negative.
             *
             * Active and inactive accounts are both
             * represented in financial reports.
             */
            IncomeStatementResponse.AccountLine accountLine =
                new IncomeStatementResponse.AccountLine(

                    row.getAccountId(),

                    row.getCode(),

                    row.getName(),

                    accountType,

                    Boolean.TRUE.equals(row.getActive()),

                    formatMoney(amount),

                    postedEntryCount
                );


            // ==================================================
            // ACCUMULATE TOTAL REVENUE AND EXPENSES
            // ==================================================

            /**
             * Monetary amounts already follow the
             * correct normal-balance conventions
             * from IncomeStatementMapper.xml.
             *
             * Revenue:
             *   Credits - Debits
             *
             * Expenses:
             *   Debits - Credits
             */
            if ("REVENUE".equals(accountType)) {

                totalRevenue = totalRevenue.add(amount);

            } else {

                totalExpenses = totalExpenses.add(amount);
            }


            // ==================================================
            // CHECK FINANCIAL REPORTING CLASSIFICATION
            // ==================================================

            String reportingGroup =
                row.getReportingGroup();

            if (reportingGroup == null) {

                // ==============================================
                // IDENTIFY UNCLASSIFIED FINANCIAL ACTIVITY
                // ==============================================

                /**
                 * An account with zero net activity
                 * might still contain posted entries.
                 *
                 * For example:
                 *
                 * Credit 500
                 * Debit 500
                 * Net activity 0
                 *
                 * postedEntryCount > 0 tells us that
                 * the account requires classification.
                 *
                 * An unused account with no posted
                 * entries does not block this report.
                 */
                if (postedEntryCount > 0) {

                    unclassifiedAccounts.add(accountLine);
                }

                continue;
            }


            // ==================================================
            // VALIDATE REPORTING GROUP COMPATIBILITY
            // ==================================================

            /**
             * PostgreSQL V7 already enforces reporting
             * group and account-type compatibility.
             *
             * This check protects the reporting layer
             * against unexpected mapping or data errors.
             */
            ReportingGroupDefinition matchingGroup = null;

            for (
                ReportingGroupDefinition group
                : REPORTING_GROUPS
            ) {

                if (group.code().equals(reportingGroup)) {

                    matchingGroup = group;
                    break;
                }
            }

            if (
                matchingGroup == null ||
                !matchingGroup.accountType().equals(accountType)
            ) {

                throw new IllegalStateException(
                    "Invalid reporting group "
                    + reportingGroup
                    + " for account "
                    + row.getCode()
                );
            }


            // ==================================================
            // ADD ACCOUNT TO ITS REPORTING SECTION
            // ==================================================

            accountsByGroup.get(reportingGroup)
                .add(accountLine);


            // ==================================================
            // UPDATE REPORTING GROUP SUBTOTAL
            // ==================================================

            /**
             * Sum the account's net activity using
             * exact BigDecimal arithmetic.
             *
             * Negative amounts are preserved.
             */
            BigDecimal existingSubtotal =
                subtotalsByGroup.get(reportingGroup);

            subtotalsByGroup.put(
                reportingGroup,
                existingSubtotal.add(amount)
            );
        }


        // ====================================================
        // STEP 8 — BUILD REPORTING SECTIONS
        // ====================================================

        List<IncomeStatementResponse.Section>
            sections = new ArrayList<>();

        for (ReportingGroupDefinition group : REPORTING_GROUPS) {

            sections.add(
                new IncomeStatementResponse.Section(

                    group.code(),

                    group.label(),

                    List.copyOf(
                        accountsByGroup.get(group.code())
                    ),

                    formatMoney(
                        subtotalsByGroup.get(group.code())
                    )
                )
            );
        }


        // ====================================================
        // STEP 9 — CALCULATE NET INCOME
        // ====================================================

        /**
         * Net Income = Total Revenue - Total Expenses.
         *
         * Negative net income represents a loss.
         */
        BigDecimal netIncome =
            totalRevenue.subtract(totalExpenses);


        // ====================================================
        // STEP 10 — VERIFY CLASSIFICATION COMPLETENESS
        // ====================================================

        /**
         * classificationComplete is false whenever
         * a revenue or expense account with posted
         * activity lacks a reporting group.
         *
         * Total revenue, expenses, and net income
         * still include that unclassified activity.
         *
         * The frontend must display an explicit
         * warning when classification is incomplete.
         */
        boolean classificationComplete =
            unclassifiedAccounts.isEmpty();


        // ====================================================
        // STEP 11 — RETURN THE COMPLETE FINANCIAL STATEMENT
        // ====================================================

        return new IncomeStatementResponse(

            businessId,

            startDate,

            endDate,

            classificationComplete,

            List.copyOf(sections),

            List.copyOf(unclassifiedAccounts),

            formatMoney(totalRevenue),

            formatMoney(totalExpenses),

            formatMoney(netIncome)
        );
    }


    // ========================================================
    // HELPER — VALIDATE REPORT REQUEST
    // ========================================================

    /**
     * Validate business identification and reporting dates.
     *
     * A financial statement requires both dates.
     *
     * startDate must not be after endDate.
     */
    private void validateRequest(
        Long businessId,
        LocalDate startDate,
        LocalDate endDate
    ) {

        if (
            businessId == null ||
            businessId <= 0
        ) {

            throw new IllegalArgumentException(
                "A valid business ID is required"
            );
        }

        if (
            startDate == null ||
            endDate == null
        ) {

            throw new IllegalArgumentException(
                "Income Statement start and end dates are required"
            );
        }

        if (startDate.isAfter(endDate)) {

            throw new IllegalArgumentException(
                "Start date cannot be after end date"
            );
        }
    }


    // ========================================================
    // HELPER — VERIFY BUSINESS EXISTS
    // ========================================================

    /**
     * Verify the reporting business exists.
     *
     * An unknown business must not receive an
     * apparently valid statement containing zeroes.
     *
     * IMPORTANT:
     *
     * This is an existence check, not authentication
     * or membership authorization.
     */
    private void validateBusinessExists(
        Long businessId
    ) {

        Integer businessCount =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM businesses
                WHERE id = ?
                """,
                Integer.class,
                businessId
            );

        if (
            businessCount == null ||
            businessCount == 0
        ) {

            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Business not found"
            );
        }
    }


    // ========================================================
    // HELPER — FORMAT EXACT MONETARY VALUES
    // ========================================================

    /**
     * Convert a BigDecimal into a decimal string
     * with two fractional digits.
     *
     * Examples:
     *
     * 1000.00 -> "1000.00"
     * 500      -> "500.00"
     * -125.50  -> "-125.50"
     * 0        -> "0.00"
     *
     * RoundingMode.UNNECESSARY prevents silently
     * rounding an unexpected high-precision value.
     *
     * The accounting database is expected to
     * store monetary amounts at cent precision.
     */
    private String formatMoney(
        BigDecimal amount
    ) {

        return amount
            .setScale(2, RoundingMode.UNNECESSARY)
            .toPlainString();
    }
}
