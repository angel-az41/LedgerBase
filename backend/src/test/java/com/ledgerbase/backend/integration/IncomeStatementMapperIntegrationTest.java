package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.mapper.IncomeStatementMapper;
import com.ledgerbase.backend.model.IncomeStatementRow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — INCOME STATEMENT MAPPER INTEGRATION TESTS
// ============================================================

/**
 * Test IncomeStatementMapper against real PostgreSQL.
 *
 * These tests verify:
 *
 * - MyBatis XML/result mapping
 * - Revenue and expense account selection
 * - Zero-activity financial amounts
 * - Inactive and unclassified accounts
 * - Exclusion of non-postable grouping accounts
 * - Business-level data isolation
 *
 * Each test creates its own business and uses
 * TransactionTemplate to roll back all test data.
 *
 * IMPORTANT:
 *
 * These initial tests use accounts with no journal
 * activity. Posted transactions and reporting-date
 * boundaries will be tested separately.
 */
@SpringBootTest
@ActiveProfiles("test")
class IncomeStatementMapperIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    @Autowired
    private IncomeStatementMapper incomeStatementMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;


    // ========================================================
    // TEST 1 — RETURN VALID INCOME STATEMENT ACCOUNTS
    // ========================================================

    @Test
    void returnsRevenueAndExpenseAccountsWithoutActivity() {

        withRollbackFixture((userId, businessId) -> {

            // ====================================================
            // CREATE A REVENUE POSTING ACCOUNT
            // ====================================================

            Long revenueAccountId = createAccount(
                businessId,
                "4000",
                "Sales Revenue",
                "REVENUE",
                "OPERATING_REVENUE",
                true,
                true
            );

            // ====================================================
            // CREATE AN INACTIVE, UNCLASSIFIED EXPENSE ACCOUNT
            // ====================================================

            Long expenseAccountId = createAccount(
                businessId,
                "5000",
                "Historical Expense",
                "EXPENSE",
                null,
                true,
                false
            );

            // ====================================================
            // CREATE A NON-POSTABLE REVENUE GROUPING ACCOUNT
            // ====================================================

            createAccount(
                businessId,
                "4010",
                "Revenue Group",
                "REVENUE",
                "OPERATING_REVENUE",
                false,
                true
            );

            // ====================================================
            // CREATE AN ASSET ACCOUNT
            // ====================================================

            createAccount(
                businessId,
                "1000",
                "Cash",
                "ASSET",
                "CURRENT_ASSET",
                true,
                true
            );

            // ====================================================
            // QUERY THE INCOME STATEMENT MAPPER
            // ====================================================

            List<IncomeStatementRow> rows =
                incomeStatementMapper.findByBusinessIdAndDateRange(
                    businessId,
                    LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 10, 31)
                );

            // ====================================================
            // VERIFY ONLY REVENUE AND EXPENSE POSTING ACCOUNTS
            // ====================================================

            assertEquals(2, rows.size());

            IncomeStatementRow revenueRow = rows.get(0);
            IncomeStatementRow expenseRow = rows.get(1);

            assertEquals(
                revenueAccountId,
                revenueRow.getAccountId()
            );

            assertEquals(
                expenseAccountId,
                expenseRow.getAccountId()
            );

            // ====================================================
            // VERIFY REVENUE CLASSIFICATION
            // ====================================================

            assertEquals(
                "REVENUE",
                revenueRow.getAccountType()
            );

            assertEquals(
                "OPERATING_REVENUE",
                revenueRow.getReportingGroup()
            );

            // ====================================================
            // VERIFY ZERO REVENUE ACTIVITY
            // ====================================================

            assertNotNull(revenueRow.getAmount());

            assertEquals(
                0,
                revenueRow.getAmount().compareTo(
                    BigDecimal.ZERO
                )
            );

            assertEquals(
                Long.valueOf(0),
                revenueRow.getPostedEntryCount()
            );

            // ====================================================
            // VERIFY INACTIVE EXPENSE ACCOUNT IS INCLUDED
            // ====================================================

            assertEquals(
                "EXPENSE",
                expenseRow.getAccountType()
            );

            assertEquals(
                Boolean.FALSE,
                expenseRow.getActive()
            );

            // ====================================================
            // VERIFY UNCLASSIFIED ACCOUNT IS RETAINED
            // ====================================================

            assertNull(
                expenseRow.getReportingGroup()
            );

            // ====================================================
            // VERIFY ZERO EXPENSE ACTIVITY
            // ====================================================

            assertNotNull(expenseRow.getAmount());

            assertEquals(
                0,
                expenseRow.getAmount().compareTo(
                    BigDecimal.ZERO
                )
            );

            assertEquals(
                Long.valueOf(0),
                expenseRow.getPostedEntryCount()
            );
        });
    }


    // ========================================================
    // TEST 2 — ENFORCE BUSINESS ISOLATION
    // ========================================================

    @Test
    void returnsAccountsOnlyForRequestedBusiness() {

        withRollbackFixture((userId, businessAId) -> {

            // ====================================================
            // CREATE A SECOND BUSINESS
            // ====================================================

            Long businessBId = createBusiness(
                userId,
                "Income Statement Business B"
            );

            // ====================================================
            // CREATE ACCOUNT IN BUSINESS A
            // ====================================================

            Long accountAId = createAccount(
                businessAId,
                "4000",
                "Business A Revenue",
                "REVENUE",
                "OPERATING_REVENUE",
                true,
                true
            );

            // ====================================================
            // CREATE ACCOUNT IN BUSINESS B
            // ====================================================

            Long accountBId = createAccount(
                businessBId,
                "4000",
                "Business B Revenue",
                "REVENUE",
                "OPERATING_REVENUE",
                true,
                true
            );

            // ====================================================
            // REQUEST BUSINESS A'S INCOME STATEMENT
            // ====================================================

            List<IncomeStatementRow> rows =
                incomeStatementMapper.findByBusinessIdAndDateRange(
                    businessAId,
                    LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 10, 31)
                );

            // ====================================================
            // VERIFY BUSINESS B'S ACCOUNT IS NOT INCLUDED
            // ====================================================

            assertEquals(1, rows.size());

            assertEquals(
                accountAId,
                rows.get(0).getAccountId()
            );

            assertNotEquals(
                accountBId,
                rows.get(0).getAccountId()
            );
        });
    }


    // ========================================================
    // HELPER — CREATE ROLLBACK TEST FIXTURE
    // ========================================================

    /**
     * Create an isolated test user and business.
     *
     * All changes are rolled back after execution.
     */
    private void withRollbackFixture(
        BiConsumer<Long, Long> testAction
    ) {

        TransactionTemplate template =
            new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status -> {

            try {

                // ====================================================
                // CREATE TEST USER
                // ====================================================

                Long userId = jdbcTemplate.queryForObject(
                    """
                    INSERT INTO users (
                        email,
                        password_hash,
                        full_name
                    )
                    VALUES (?, ?, ?)
                    RETURNING id
                    """,
                    Long.class,
                    "income-statement-"
                        + UUID.randomUUID()
                        + "@ledgerbase.local",
                    "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
                    "Income Statement Test User"
                );

                // ====================================================
                // CREATE TEST BUSINESS
                // ====================================================

                Long businessId = createBusiness(
                    userId,
                    "Income Statement Test Business"
                );

                // ====================================================
                // EXECUTE TEST LOGIC
                // ====================================================

                testAction.accept(userId, businessId);

            } finally {

                // ====================================================
                // PREVENT PERSISTENT TEST DATA
                // ====================================================

                status.setRollbackOnly();
            }
        });
    }


    // ========================================================
    // HELPER — CREATE BUSINESS
    // ========================================================

    private Long createBusiness(
        Long userId,
        String businessName
    ) {

        return jdbcTemplate.queryForObject(
            """
            INSERT INTO businesses (
                owner_id,
                name,
                currency
            )
            VALUES (?, ?, ?)
            RETURNING id
            """,
            Long.class,
            userId,
            businessName,
            "USD"
        );
    }


    // ========================================================
    // HELPER — CREATE ACCOUNT
    // ========================================================

    /**
     * Insert an account using the V7 schema.
     *
     * The helper supports:
     *
     * - Different account types
     * - Optional reporting groups
     * - Posting and grouping accounts
     * - Active and inactive accounts
     */
    private Long createAccount(
        Long businessId,
        String code,
        String name,
        String accountType,
        String reportingGroup,
        boolean postable,
        boolean active
    ) {

        return jdbcTemplate.queryForObject(
            """
            INSERT INTO accounts (
                business_id,
                code,
                name,
                account_type,
                reporting_group,
                is_postable,
                is_active
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            businessId,
            code,
            name,
            accountType,
            reportingGroup,
            postable,
            active
        );
    }
}
