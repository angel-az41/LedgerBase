package com.ledgerbase.backend.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for durable financial outbox events.
 *
 * The mapper writes to the same primary PostgreSQL
 * database used by the accounting transaction engine.
 *
 * It does not communicate with Apache Kafka.
 *
 * The corresponding SQL is defined in
 * OutboxEventMapper.xml.
 */
@Mapper
public interface OutboxEventMapper {

    /**
     * Insert a TransactionPosted domain event.
     *
     * The SQL only inserts an event when the referenced
     * transaction belongs to the specified business
     * and its status is POSTED.
     *
     * The caller must already have an active
     * PostgreSQL transaction.
     *
     * @param eventId Stable UUID expressed as a string.
     * @param businessId Business owning the transaction.
     * @param transactionId Posted transaction identifier.
     * @param actorId User responsible for posting.
     * @return Number of inserted outbox rows.
     */
    int insertTransactionPosted(
        @Param("eventId") String eventId,
        @Param("businessId") Long businessId,
        @Param("transactionId") Long transactionId,
        @Param("actorId") Long actorId
    );
}
