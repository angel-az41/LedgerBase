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
 * DATABASE TABLES:
 *
 * - businesses
 * - business_memberships
 *
 * RESPONSIBILITIES:
 *
 * - Check whether a business exists.
 * - Retrieve fiscal-year configuration.
 * - Preserve existing owner-scoped queries.
 * - Retrieve businesses through membership records.
 * - Insert new businesses.
 * - Assign OWNER membership to new business owners.
 *
 * SECURITY:
 *
 * Membership-scoped SELECT queries filter results
 * using the authenticated user's database ID.
 *
 * The HTTP controller must obtain that ID from
 * Spring Security's validated JWT.
 *
 * The database mapper does not independently
 * authenticate the caller.
 */
@Mapper
public interface BusinessMapper {

    // ========================================================
    // BUSINESS EXISTENCE
    // ========================================================

    /**
     * Check whether a business ID exists.
     *
     * Preserved for existing accounting services.
     *
     * IMPORTANT:
     *
     * Existence alone does not authorize access.
     *
     * Business-scoped controllers must perform
     * membership authorization separately.
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
    // FISCAL-YEAR CONFIGURATION
    // ========================================================

    /**
     * Return the fiscal-year starting month.
     *
     * Preserved for existing fiscal-year services.
     *
     * This query does not enforce business membership.
     * Authorization belongs at the protected
     * accounting operation boundary.
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
    // EXISTING OWNER-SCOPED BUSINESS LIST
    // ========================================================

    /**
     * Retrieve businesses whose canonical owner_id
     * matches the requested owner.
     *
     * This method is preserved for compatibility with
     * existing tests and internal business operations.
     *
     * User-facing business listing will instead use
     * findAllByMemberUserId().
     *
     * @Results defines the reusable Business mapping.
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
    // EXISTING OWNER-SCOPED BUSINESS LOOKUP
    // ========================================================

    /**
     * Retrieve a business matching both:
     *
     * - business ID
     * - canonical owner ID
     *
     * Preserved for existing service operations.
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
    // LIST BUSINESSES ACCESSIBLE TO A USER
    // ========================================================

    /**
     * Retrieve all businesses that an authenticated
     * user belongs to.
     *
     * Unlike owner-scoped queries, this supports:
     *
     * - OWNER
     * - ADMIN
     * - ACCOUNTANT
     * - VIEWER
     *
     * SQL SECURITY RULE:
     *
     * The membership must match the requested user ID.
     *
     * The composite membership primary key prevents
     * duplicate rows for the same user and business.
     *
     * The V9 migration provides an index on:
     *
     * (user_id, business_id)
     *
     * This makes user-first membership lookups efficient.
     *
     * @param userId authenticated user's database ID
     * @return businesses accessible to the user
     */
    @Select("""
        SELECT
            b.id,
            b.owner_id,
            b.name,
            TRIM(b.currency) AS currency,
            b.fiscal_year_start_month,
            b.created_at
        FROM businesses AS b
        INNER JOIN business_memberships AS m
            ON m.business_id = b.id
        WHERE m.user_id = #{userId}
        ORDER BY b.id ASC
        """)
    @ResultMap("businessResultMap")
    List<Business> findAllByMemberUserId(
        @Param("userId") Long userId
    );


    // ========================================================
    // RETRIEVE A BUSINESS THROUGH MEMBERSHIP
    // ========================================================

    /**
     * Retrieve a specific business only if the
     * authenticated user has a membership.
     *
     * Both conditions are enforced by PostgreSQL:
     *
     * - b.id = requested business ID
     * - m.user_id = authenticated user ID
     *
     * An unauthorized user receives null.
     *
     * The service converts null into the existing
     * BusinessNotFoundException.
     *
     * This deliberately avoids revealing whether
     * another user's business exists.
     *
     * @param businessId requested business ID
     * @param userId authenticated user's database ID
     * @return accessible business or null
     */
    @Select("""
        SELECT
            b.id,
            b.owner_id,
            b.name,
            TRIM(b.currency) AS currency,
            b.fiscal_year_start_month,
            b.created_at
        FROM businesses AS b
        INNER JOIN business_memberships AS m
            ON m.business_id = b.id
        WHERE b.id = #{businessId}
          AND m.user_id = #{userId}
        """)
    @ResultMap("businessResultMap")
    Business findByIdAndMemberUserId(
        @Param("businessId") Long businessId,
        @Param("userId") Long userId
    );


    // ========================================================
    // INSERT A NEW BUSINESS
    // ========================================================

    /**
     * Create a business belonging to the supplied owner.
     *
     * PostgreSQL generates:
     *
     * - id
     * - created_at
     *
     * MyBatis stores the generated ID in business.id.
     *
     * BusinessService validates inputs and manages
     * the transaction.
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


    // ========================================================
    // INSERT OWNER MEMBERSHIP
    // ========================================================

    /**
     * Assign OWNER membership to the newly created
     * business's canonical owner.
     *
     * The role is a fixed SQL literal.
     *
     * The caller cannot choose a different role
     * when creating the business.
     *
     * This statement must execute in the same
     * Spring transaction as the business INSERT.
     *
     * If either INSERT fails, the complete business
     * creation transaction must roll back.
     *
     * DATABASE SAFEGUARDS:
     *
     * - Composite membership primary key.
     * - Foreign keys to businesses and users.
     * - Valid-role CHECK constraint.
     * - Partial unique index for OWNER memberships.
     *
     * @param businessId newly created business ID
     * @param ownerId authenticated creator's user ID
     * @return number of inserted membership rows
     */
    @Insert("""
        INSERT INTO business_memberships (
            business_id,
            user_id,
            role
        )
        VALUES (
            #{businessId},
            #{ownerId},
            'OWNER'
        )
        """)
    int insertOwnerMembership(
        @Param("businessId") Long businessId,
        @Param("ownerId") Long ownerId
    );
}
