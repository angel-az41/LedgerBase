package com.ledgerbase.backend.mapper;

import com.ledgerbase.backend.model.AuditEvent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis mapper for the PostgreSQL audit_events table.
 *
 * This interface defines database operations for
 * recording and retrieving important application events.
 *
 * The actual SQL statements are implemented in
 * resources/mapper/AuditEventMapper.xml.
 *
 * MyBatis generates the Java implementation for us.
 */
@Mapper
public interface AuditEventMapper {

    /**
     * Insert a new audit event into PostgreSQL.
     *
     * PostgreSQL automatically generates:
     * - The primary key (id)
     * - The creation timestamp (created_at)
     *
     * MyBatis will populate the generated ID
     * on the AuditEvent Java object.
     *
     * Returns 1 when the insertion succeeds.
     */
    int insert(AuditEvent event);

    /**
     * Retrieve audit events for a specific entity
     * belonging to a particular business.
     *
     * Example:
     * businessId = 1
     * entityType = "TRANSACTION"
     * entityId = 10
     *
     * This could return:
     * - TRANSACTION_CREATED
     * - TRANSACTION_POSTED
     *
     * Results will be ordered chronologically.
     */
    List<AuditEvent> findByEntity(
        @Param("businessId") Long businessId,
        @Param("entityType") String entityType,
        @Param("entityId") Long entityId
    );
}
