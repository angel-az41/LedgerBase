package com.ledgerbase.backend.service;

import com.ledgerbase.backend.mapper.OutboxEventMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Records financial domain events in PostgreSQL.
 *
 * This service implements the database-writing portion
 * of the transactional outbox pattern.
 *
 * FINANCIAL CONSISTENCY:
 *
 * A posting operation must create its outbox event
 * inside the same PostgreSQL transaction.
 *
 * Propagation.MANDATORY prevents this service from
 * accidentally starting an independent transaction.
 *
 * If the caller has no active transaction, Spring
 * rejects the invocation.
 *
 * If event insertion fails, the exception propagates
 * to the financial posting operation and triggers
 * transaction rollback.
 *
 * This service does not send Kafka messages.
 * Kafka publishing is handled asynchronously by
 * a separate component.
 */
@Service
public class OutboxEventService {

    // MyBatis persistence for financial outbox records.
    private final OutboxEventMapper outboxEventMapper;

    /**
     * Constructor injection makes the persistence
     * dependency explicit and testable.
     */
    public OutboxEventService(
            OutboxEventMapper outboxEventMapper) {

        this.outboxEventMapper = outboxEventMapper;
    }

    /**
     * Record a successfully posted financial transaction.
     *
     * The event is persisted within the caller's
     * existing PostgreSQL transaction.
     *
     * Every event receives a unique UUID.
     *
     * The database additionally prevents duplicate
     * TransactionPosted events for the same transaction.
     *
     * @param businessId Business owning the transaction.
     * @param transactionId Posted transaction identifier.
     * @param actorId User who posted the transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordTransactionPosted(
            Long businessId,
            Long transactionId,
            Long actorId) {

        // Validate all identifiers before recording
        // a financial domain event.

        if (businessId == null || businessId <= 0) {
            throw new IllegalArgumentException(
                "A valid business ID is required"
            );
        }

        if (transactionId == null || transactionId <= 0) {
            throw new IllegalArgumentException(
                "A valid transaction ID is required"
            );
        }

        if (actorId == null || actorId <= 0) {
            throw new IllegalArgumentException(
                "A valid posting actor ID is required"
            );
        }

        // Generate the event identifier once.
        //
        // The identifier is persisted in PostgreSQL
        // and later reused during Kafka publication.

        String eventId = UUID.randomUUID().toString();

        // Insert the event using the existing
        // PostgreSQL transaction.
        //
        // No Kafka connection is required here.

        int insertedRows =
            outboxEventMapper.insertTransactionPosted(
                eventId,
                businessId,
                transactionId,
                actorId
            );

        // An outbox event must be created for every
        // successful financial posting.
        //
        // A missing row indicates an invalid posting
        // state or an unexpected persistence failure.
        //
        // Throwing an unchecked exception causes the
        // outer financial transaction to roll back.

        if (insertedRows != 1) {
            throw new IllegalStateException(
                "Failed to record TransactionPosted outbox event "
                + "for transaction " + transactionId
            );
        }
    }
}
