package com.ledgerbase.backend.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// ============================================================
// LEDGERBASE — BUSINESS REPORTING REVISION MAPPER
// ============================================================

/**
 * Provides PostgreSQL operations for tracking
 * business-scoped financial reporting revisions.
 *
 * A reporting revision identifies the committed
 * version of financial data for a business.
 *
 * Revisions are stored in PostgreSQL, not Redis.
 */
@Mapper
public interface BusinessReportingRevisionMapper {

    // ========================================================
    // INCREMENT REPORTING REVISION
    // ========================================================

    /**
     * Atomically increase one business's revision.
     *
     * This operation must participate in the same
     * database transaction as the accounting change.
     *
     * @param businessId affected business
     * @return number of updated database rows
     */
    int incrementRevision(
        @Param("businessId") Long businessId
    );

    // ========================================================
    // READ CURRENT REPORTING REVISION
    // ========================================================

    /**
     * Retrieve the current revision for a business.
     *
     * A null result indicates that the business
     * does not exist.
     *
     * @param businessId requested business
     * @return current reporting revision
     */
    Long findRevision(
        @Param("businessId") Long businessId
    );
}
