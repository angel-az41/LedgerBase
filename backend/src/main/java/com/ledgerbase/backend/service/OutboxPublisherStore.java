package com.ledgerbase.backend.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL persistence for asynchronous outbox publishing.
 *
 * The financial posting transaction inserts PENDING events.
 * This repository handles their publication lifecycle.
 *
 * Claiming uses PostgreSQL FOR UPDATE SKIP LOCKED so
 * multiple publisher instances cannot claim the same
 * available event simultaneously.
 *
 * Database claims are committed before network calls
 * are made to Kafka. No row locks are held while waiting
 * for Kafka acknowledgments.
 */
@Repository
public class OutboxPublisherStore {

    private final JdbcTemplate jdbcTemplate;

    /**
     * A claimed event contains everything needed to
     * publish one financial event to Apache Kafka.
     *
     * The claim token identifies one publication attempt.
     * It prevents a delayed publisher from modifying a row
     * that another publisher has subsequently reclaimed.
     */
    public record ClaimedEvent(
        long id,
        UUID eventId,
        String kafkaTopic,
        String eventKey,
        String payload,
        int attemptCount,
        String claimToken
    ) {}

    public OutboxPublisherStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Atomically claim one eligible outbox event.
     *
     * Eligible events include:
     *
     * - PENDING events whose retry time has arrived.
     * - PROCESSING events with expired publisher claims.
     *
     * SKIP LOCKED allows another publisher to continue
     * without waiting for a row already being claimed.
     *
     * Every claim has a new UUID token.
     *
     * The 90-second lease exceeds the configured Kafka
     * send timeout for this one-event-at-a-time publisher.
     */
    @Transactional
    public Optional<ClaimedEvent> claimNextEvent() {

        String claimToken = UUID.randomUUID().toString();

        List<ClaimedEvent> events = jdbcTemplate.query(
            """
            WITH eligible AS (
                SELECT id
                FROM outbox_events
                WHERE (
                    status = 'PENDING'
                    AND next_attempt_at <= NOW()
                )
                OR (
                    status = 'PROCESSING'
                    AND claimed_until <= NOW()
                )
                ORDER BY id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE outbox_events AS event
            SET status = 'PROCESSING',
                claimed_by = ?,
                claimed_until = NOW() + INTERVAL '90 seconds',
                attempt_count = event.attempt_count + 1,
                last_error = NULL
            FROM eligible
            WHERE event.id = eligible.id
            RETURNING
                event.id,
                event.event_id,
                event.kafka_topic,
                event.event_key,
                event.payload::text AS payload,
                event.attempt_count
            """,
            (resultSet, rowNumber) -> new ClaimedEvent(
                resultSet.getLong("id"),
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("kafka_topic"),
                resultSet.getString("event_key"),
                resultSet.getString("payload"),
                resultSet.getInt("attempt_count"),
                claimToken
            ),
            claimToken
        );

        return events.stream().findFirst();
    }

    /**
     * Mark an event as published only after Kafka
     * acknowledges successful delivery.
     *
     * The claim token acts as a fencing condition.
     * An expired or replaced claim cannot update the row.
     *
     * @return Number of rows updated, normally one.
     */
    @Transactional
    public int markPublished(ClaimedEvent event) {

        return jdbcTemplate.update(
            """
            UPDATE outbox_events
            SET status = 'PUBLISHED',
                published_at = NOW(),
                claimed_by = NULL,
                claimed_until = NULL,
                last_error = NULL
            WHERE id = ?
              AND status = 'PROCESSING'
              AND claimed_by = ?
            """,
            event.id(),
            event.claimToken()
        );
    }

    /**
     * Schedule another publication attempt after failure.
     *
     * A failed event returns to PENDING.
     *
     * Retry delay is calculated by the publisher using
     * exponential backoff.
     *
     * We retain failed events for later recovery instead
     * of silently discarding financial events.
     *
     * The claim token prevents stale publishers from
     * overwriting a newer claim.
     */
    @Transactional
    public int scheduleRetry(
            ClaimedEvent event,
            long retryDelaySeconds,
            String errorMessage) {

        return jdbcTemplate.update(
            """
            UPDATE outbox_events
            SET status = 'PENDING',
                next_attempt_at =
                    NOW() + (? * INTERVAL '1 second'),
                claimed_by = NULL,
                claimed_until = NULL,
                last_error = LEFT(?, 1500)
            WHERE id = ?
              AND status = 'PROCESSING'
              AND claimed_by = ?
            """,
            retryDelaySeconds,
            errorMessage,
            event.id(),
            event.claimToken()
        );
    }
}
