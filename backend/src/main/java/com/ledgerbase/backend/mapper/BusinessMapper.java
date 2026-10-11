package com.ledgerbase.backend.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Database operations for LedgerBase businesses.
 *
 * For now, we only need to check whether a business exists.
 * More business management operations will come later.
 */
@Mapper
public interface BusinessMapper {

    /**
     * Returns true when a business exists with the given ID.
     *
     * COUNT(*) always returns a number, even when
     * no matching business is found.
     */
    @Select("""
        SELECT COUNT(*) > 0
        FROM businesses
        WHERE id = #{businessId}
        """)
    boolean existsById(@Param("businessId") Long businessId);
}
