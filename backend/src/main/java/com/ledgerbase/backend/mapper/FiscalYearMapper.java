package com.ledgerbase.backend.mapper;

import com.ledgerbase.backend.model.FiscalYear;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

// ============================================================
// LEDGERBASE — FISCAL YEAR MYBATIS MAPPER
// ============================================================

/**
 * Database operations for fiscal years.
 *
 * Database table:
 *
 * fiscal_years
 *
 * Responsibilities:
 *
 * 1. Retrieve fiscal years belonging to a business.
 * 2. Retrieve an individual fiscal year.
 * 3. Insert a new fiscal year.
 *
 * IMPORTANT:
 *
 * All lookup queries must include business_id.
 *
 * This prevents one business from retrieving
 * another business's accounting information.
 *
 * Fiscal-year validation and period generation
 * belong in the service layer, not this mapper.
 */
@Mapper
public interface FiscalYearMapper {

    // ========================================================
    // GET ALL FISCAL YEARS FOR A BUSINESS
    // ========================================================

    /**
     * Retrieve all fiscal years belonging to one business.
     *
     * The newest fiscal year appears first.
     *
     * Example:
     *
     * Business 1:
     * - FY2027
     * - FY2026
     * - FY2025
     *
     * @Results explicitly maps PostgreSQL snake_case
     * columns to Java camelCase properties.
     *
     * The mapping is named so other SELECT methods
     * can reuse it through @ResultMap.
     */
    @Select("""
        SELECT
            id,
            business_id,
            label,
            start_date,
            end_date,
            created_at
        FROM fiscal_years
        WHERE business_id = #{businessId}
        ORDER BY start_date DESC, id DESC
        """)
    @Results(id = "fiscalYearResultMap", value = {
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
            property = "label",
            column = "label"
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
            property = "createdAt",
            column = "created_at"
        )
    })
    List<FiscalYear> findByBusinessId(
        @Param("businessId") Long businessId
    );


    // ========================================================
    // GET ONE FISCAL YEAR
    // ========================================================

    /**
     * Retrieve one fiscal year by its ID.
     *
     * The business_id condition enforces isolation.
     *
     * Returns null when no matching fiscal year exists.
     *
     * Reuses the mapping defined above.
     */
    @Select("""
        SELECT
            id,
            business_id,
            label,
            start_date,
            end_date,
            created_at
        FROM fiscal_years
        WHERE business_id = #{businessId}
          AND id = #{fiscalYearId}
        """)
    @ResultMap("fiscalYearResultMap")
    FiscalYear findById(
        @Param("businessId") Long businessId,
        @Param("fiscalYearId") Long fiscalYearId
    );


    // ========================================================
    // INSERT FISCAL YEAR
    // ========================================================

    /**
     * Insert a new fiscal year.
     *
     * PostgreSQL generates the primary key automatically.
     *
     * @Options(useGeneratedKeys = true) instructs MyBatis
     * to retrieve that generated ID and assign it to
     * the FiscalYear object's id property.
     *
     * The database already prevents overlapping
     * fiscal-year date ranges and duplicate labels.
     *
     * The service layer will calculate start/end dates
     * and create the 12 corresponding periods within
     * the same database transaction.
     *
     * Returns the number of inserted rows.
     */
    @Insert("""
        INSERT INTO fiscal_years (
            business_id,
            label,
            start_date,
            end_date
        )
        VALUES (
            #{businessId},
            #{label},
            #{startDate},
            #{endDate}
        )
        """)
    @Options(
        useGeneratedKeys = true,
        keyProperty = "id",
        keyColumn = "id"
    )
    int insert(FiscalYear fiscalYear);
}
