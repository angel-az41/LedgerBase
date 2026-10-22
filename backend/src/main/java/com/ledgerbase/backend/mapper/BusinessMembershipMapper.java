package com.ledgerbase.backend.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

// ============================================================
// LEDGERBASE — BUSINESS MEMBERSHIP MAPPER
// ============================================================

/**
 * Database access for business-level authorization.
 *
 * SOURCE OF TRUTH:
 *
 * business_memberships
 *
 * The composite primary key (business_id, user_id)
 * guarantees at most one role per user per business.
 *
 * SECURITY:
 *
 * - Never infer permissions from business ownership
 *   displayed in the frontend.
 * - Never accept a membership role from the browser.
 * - Read the current role from PostgreSQL.
 *
 * A missing membership returns null.
 */
@Mapper
public interface BusinessMembershipMapper {

    // ========================================================
    // LOOK UP BUSINESS MEMBERSHIP ROLE
    // ========================================================

    /**
     * Retrieve the current role assigned to a user.
     *
     * Supported database values:
     *
     * OWNER
     * ADMIN
     * ACCOUNTANT
     * VIEWER
     *
     * Returns null when the user has no membership.
     *
     * @param businessId business requiring authorization
     * @param userId authenticated PostgreSQL user ID
     * @return role or null
     */
    @Select("""
        SELECT role
        FROM business_memberships
        WHERE business_id = #{businessId}
          AND user_id = #{userId}
        """)
    String findRole(
        @Param("businessId") Long businessId,
        @Param("userId") Long userId
    );
}
