package com.ledgerbase.backend.mapper;

import com.ledgerbase.backend.model.AccountingPeriod;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

// ============================================================
// LEDGERBASE — ACCOUNTING PERIOD MYBATIS MAPPER
// ============================================================

/**
 * Database operations for accounting periods.
 *
 * Database table:
 *
 * accounting_periods
 *
 * Responsibilities:
 *
 * 1. Retrieve periods belonging to a fiscal year.
 * 2. Retrieve a specific accounting period.
 * 3. Find and lock the period containing a transaction date.
 * 4. Insert newly generated accounting periods.
 * 5. Close and reopen accounting periods.
 *
 * IMPORTANT:
 *
 * Every query must be scoped to the correct business.
 *
 * The mapper handles database operations.
 * Business rules and transaction management belong
 * in the service layer.
 */
@Mapper
public interface AccountingPeriodMapper {

    // ========================================================
    // GET PERIODS FOR A FISCAL YEAR
    // ========================================================

    /**
     * Retrieve all accounting periods belonging to
     * a particular fiscal year and business.
     *
     * Results are ordered from Period 1 to Period 12.
     *
     * The @Results mapping converts PostgreSQL column
     * names into the corresponding Java properties.
     *
     * The named result map can be reused by other
     * SELECT methods in this interface.
     */
    @Select("""
        SELECT
            id,
            business_id,
            fiscal_year_id,
            period_number,
            start_date,
            end_date,
            status,
            closed_at,
            created_at
        FROM accounting_periods
        WHERE business_id = #{businessId}
          AND fiscal_year_id = #{fiscalYearId}
        ORDER BY period_number ASC
        """)
    @Results(id = "accountingPeriodResultMap", value = {
        @Result(
            property = "id",
            column = "id",
            id = true
        ),
        @Result(
            property = "businessId",
            column = "business_id"
        ),
        @Result(
            property = "fiscalYearId",
            column = "fiscal_year_id"
        ),
        @Result(
            property = "periodNumber",
            column = "period_number"
        ),
        @Result(
            property = "startDate",
            column = "start_date"
        ),
        @Result(
            property = "endDate",
            column = "end_date"
        ),
        @Result(
            property = "status",
            column = "status"
        ),
        @Result(
            property = "closedAt",
            column = "closed_at"
        ),
        @Result(
            property = "createdAt",
            column = "created_at"
        )
    })
    List<AccountingPeriod> findByFiscalYearId(
        @Param("businessId") Long businessId,
        @Param("fiscalYearId") Long fiscalYearId
    );


    // ========================================================
    // GET ONE ACCOUNTING PERIOD
    // ========================================================

    /**
     * Retrieve one accounting period belonging
     * to the specified business.
     *
     * Returns null if the record does not exist
     * or belongs to another business.
     */
    @Select("""
        SELECT
            id,
            business_id,
            fiscal_year_id,
            period_number,
            start_date,
            end_date,
            status,
            closed_at,
            created_at
        FROM accounting_periods
        WHERE business_id = #{businessId}
          AND id = #{periodId}
        """)
    @ResultMap("accountingPeriodResultMap")
    AccountingPeriod findById(
        @Param("businessId") Long businessId,
        @Param("periodId") Long periodId
    );


    // ========================================================
    // FIND AND LOCK PERIOD FOR TRANSACTION DATE
    // ========================================================

    /**
     * Find the accounting period that contains
     * a transaction's accounting date.
     *
     * Example:
     *
     * Transaction date: October 13, 2026
     *
     * Matching period:
     * October 1 - October 31, 2026
     *
     * FOR UPDATE obtains a PostgreSQL row lock.
     *
     * This will coordinate journal posting with
     * period closing:
     *
     * - If a period is being closed, posting waits.
     * - If posting holds the lock, closing waits.
     * - After waiting, the operation sees the
     *   latest committed period status.
     *
     * IMPORTANT:
     *
     * The calling service must run inside a
     * Spring-managed database transaction
     * so the lock is held until commit or rollback.
     *
     * Returns null when no period covers the date.
     */
    @Select("""
        SELECT
            id,
            business_id,
            fiscal_year_id,
            period_number,
            start_date,
            end_date,
            status,
            closed_at,
            created_at
        FROM accounting_periods
        WHERE business_id = #{businessId}
          AND #{transactionDate}
              BETWEEN start_date AND end_date
        FOR UPDATE
        """)
    @ResultMap("accountingPeriodResultMap")
    AccountingPeriod findByDateForUpdate(
        @Param("businessId") Long businessId,
        @Param("transactionDate") LocalDate transactionDate
    );


    // ========================================================
    // INSERT ACCOUNTING PERIOD
    // ========================================================

    /**
     * Insert a new monthly accounting period.
     *
     * PostgreSQL automatically generates the ID.
     *
     * MyBatis assigns the generated ID back to
     * the AccountingPeriod Java object.
     *
     * The service layer will calculate each period's
     * dates and insert all 12 periods as part of
     * one database transaction.
     *
     * The database constraints prevent overlapping
     * period dates and duplicate period numbers.
     *
     * Returns the number of inserted rows.
     */
    @Insert("""
        INSERT INTO accounting_periods (
            business_id,
            fiscal_year_id,
            period_number,
            start_date,
            end_date,
            status
        )
        VALUES (
            #{businessId},
            #{fiscalYearId},
            #{periodNumber},
            #{startDate},
            #{endDate},
            #{status}
        )
        """)
    @Options(
        useGeneratedKeys = true,
        keyProperty = "id",
        keyColumn = "id"
    )
    int insert(AccountingPeriod accountingPeriod);


    // ========================================================
    // CLOSE ACCOUNTING PERIOD
    // ========================================================

    /**
     * Change an OPEN accounting period to CLOSED.
     *
     * The WHERE clause only allows the transition
     * when the current status is OPEN.
     *
     * closed_at records when the closing occurred.
     *
     * Returns:
     *
     * 1 = Period was successfully closed.
     * 0 = Period was not found or was not OPEN.
     *
     * The service layer will distinguish these cases
     * and enforce additional closing rules.
     */
    @Update("""
        UPDATE accounting_periods
        SET
            status = 'CLOSED',
            closed_at = NOW()
        WHERE business_id = #{businessId}
          AND id = #{periodId}
          AND status = 'OPEN'
        """)
    int closePeriod(
        @Param("businessId") Long businessId,
        @Param("periodId") Long periodId
    );


    // ========================================================
    // REOPEN ACCOUNTING PERIOD
    // ========================================================

    /**
     * Change a CLOSED accounting period back to OPEN.
     *
     * Reopening clears the closed_at timestamp
     * because the period is no longer closed.
     *
     * The service layer will validate whether
     * reopening is permitted.
     *
     * Returns:
     *
     * 1 = Period was successfully reopened.
     * 0 = Period was not found or was not CLOSED.
     */
    @Update("""
        UPDATE accounting_periods
        SET
            status = 'OPEN',
            closed_at = NULL
        WHERE business_id = #{businessId}
          AND id = #{periodId}
          AND status = 'CLOSED'
        """)
    int reopenPeriod(
        @Param("businessId") Long businessId,
        @Param("periodId") Long periodId
    );
}
