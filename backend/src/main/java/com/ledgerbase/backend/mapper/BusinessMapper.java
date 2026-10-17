package com.ledgerbase.backend.mapper;

import com.ledgerbase.backend.model.Business;

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
// LEDGERBASE — BUSINESS DATABASE MAPPER
// ============================================================

/**
 * MyBatis database operations for LedgerBase businesses.
 *
 * DATABASE TABLE:
 *
 * businesses
 *
 * This mapper supports:
 *
 * 1. Checking whether a business exists.
 * 2. Reading a business's fiscal-year starting month.
 * 3. Listing businesses belonging to an owner.
 * 4. Retrieving a business by its ID and owner ID.
 * 5. Creating a new business.
 *
 * IMPORTANT:
 *
 * This mapper only handles database access.
 *
 * Business validation belongs in BusinessService.
 *
 * HTTP request handling belongs in BusinessController.
 *
 * Authentication and authorization will be integrated
 * separately. For now, ownerId is explicitly provided
 * by the calling service.
 */
@Mapper
public interface BusinessMapper {

    // ========================================================
    // 1. CHECK WHETHER A BUSINESS EXISTS
    // ========================================================

    /**
     * Returns true when a business exists with the given ID.
     *
     * COUNT(*) always returns a result, even when
     * no matching business exists.
     *
     * IMPORTANT:
     *
     * This existing method is preserved because other
     * LedgerBase services already depend on it.
     */
    @Select("""
        SELECT COUNT(*) > 0
        FROM businesses
        WHERE id = #{businessId}
        """)
    boolean existsById(
        @Param("businessId") Long businessId
    );

    // ========================================================
    // 2. RETRIEVE FISCAL-YEAR STARTING MONTH
    // ========================================================

    /**
     * Retrieve the fiscal-year starting month configured
     * for a particular business.
     *
     * Valid values: 1 through 12.
     *
     * Examples:
     *
     * 1  = January
     * 4  = April
     * 7  = July
     * 10 = October
     *
     * Returns null when the business does not exist.
     *
     * This existing method is preserved because
     * FiscalYearService already uses it.
     */
    @Select("""
        SELECT fiscal_year_start_month
        FROM businesses
        WHERE id = #{businessId}
        """)
    Integer findFiscalYearStartMonth(
        @Param("businessId") Long businessId
    );

    // ========================================================
    // 3. LIST BUSINESSES BELONGING TO AN OWNER
    // ========================================================

    /**
     * Retrieve businesses owned by a specific user.
     *
     * Example:
     *
     * ownerId = 1
     *
     * Returns:
     *
     * - LedgerBase Demo Business
     * - ABC Consulting LLC
     *
     * An owner with no businesses receives an empty list.
     *
     * OWNERSHIP FILTER:
     *
     * We explicitly filter by owner_id so the mapper
     * does not retrieve every business in the database.
     *
     * Later, BusinessService must obtain the owner
     * identity from the authenticated security context.
     */
    @Select("""
        SELECT
            id,
            owner_id,
            name,
            TRIM(currency) AS currency,
            fiscal_year_start_month,
            created_at
        FROM businesses
        WHERE owner_id = #{ownerId}
        ORDER BY id ASC
        """)
    @Results(
        id = "businessResultMap",
        value = {
            @Result(
                property = "id",
                column = "id",
                id = true
            ),
            @Result(
                property = "ownerId",
                column = "owner_id"
            ),
            @Result(
                property = "name",
                column = "name"
            ),
            @Result(
                property = "currency",
                column = "currency"
            ),
            @Result(
                property = "fiscalYearStartMonth",
                column = "fiscal_year_start_month"
            ),
            @Result(
                property = "createdAt",
                column = "created_at"
            )
        }
    )
    List<Business> findAllByOwnerId(
        @Param("ownerId") Long ownerId
    );

    // ========================================================
    // 4. RETRIEVE ONE BUSINESS BELONGING TO AN OWNER
    // ========================================================

    /**
     * Retrieve one business by its ID and owner ID.
     *
     * Both conditions must match.
     *
     * Example:
     *
     * businessId = 2
     * ownerId    = 1
     *
     * Returns the business if it belongs to owner 1.
     *
     * Otherwise, MyBatis returns null.
     *
     * This owner-scoped query provides a useful
     * foundation for future authorization checks.
     *
     * It is NOT a substitute for authentication:
     * the calling service must supply a trusted owner ID.
     *
     * @ResultMap reuses the mapping defined above,
     * so we don't need to repeat every @Result.
     */
    @Select("""
        SELECT
            id,
            owner_id,
            name,
            TRIM(currency) AS currency,
            fiscal_year_start_month,
            created_at
        FROM businesses
        WHERE id = #{businessId}
          AND owner_id = #{ownerId}
        """)
    @ResultMap("businessResultMap")
    Business findByIdAndOwnerId(
        @Param("businessId") Long businessId,
        @Param("ownerId") Long ownerId
    );

    // ========================================================
    // 5. CREATE A NEW BUSINESS
    // ========================================================

    /**
     * Insert a business into PostgreSQL.
     *
     * DATABASE COLUMNS:
     *
     * owner_id                NOT NULL
     * name                    VARCHAR(200) NOT NULL
     * currency                CHAR(3) NOT NULL
     * fiscal_year_start_month SMALLINT NOT NULL
     *
     * DATABASE-GENERATED VALUES:
     *
     * id:
     *   GENERATED ALWAYS AS IDENTITY
     *
     * created_at:
     *   Defaults to NOW()
     *
     * Therefore, neither column needs to be supplied
     * explicitly in our INSERT statement.
     *
     * The BusinessService will validate input before
     * calling this mapper.
     *
     * PostgreSQL also enforces the database constraints.
     */
    @Insert("""
        INSERT INTO businesses (
            owner_id,
            name,
            currency,
            fiscal_year_start_month
        )
        VALUES (
            #{ownerId},
            #{name},
            #{currency},
            #{fiscalYearStartMonth}
        )
        """)
    @Options(
        useGeneratedKeys = true,
        keyProperty = "id",
        keyColumn = "id"
    )
    int insert(Business business);

}
