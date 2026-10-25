package com.ledgerbase.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous publisher for committed financial events.
 *
 * This component is enabled only when:
 *
 * ledgerbase.kafka.outbox.publisher.enabled=true
 *
 * It polls PostgreSQL for committed outbox events,
 * publishes them to Kafka, and records acknowledgment.
 *
 * IMPORTANT:
 *
 * Financial posting never waits for this publisher.
 *
 * If Kafka is unavailable, posting continues normally
 * and the durable outbox event remains available.
 *
 * DELIVERY GUARANTEE:
 *
 * At least once.
 *
 * If Kafka acknowledges an event but PostgreSQL cannot
 * record PUBLISHED, the event can be retried.
 *
 * Consumers must therefore deduplicate using eventId.
 */
@Component
@ConditionalOnProperty(
    prefix = "ledgerbase.kafka.outbox.publisher",
    name = "enabled",
    havingValue = "true"
)
public class OutboxKafkaPublisher {

    private static final Logger logger =
        LoggerFactory.getLogger(OutboxKafkaPublisher.class);

    private final OutboxPublisherStore outboxStore;

    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxKafkaPublisher(
            OutboxPublisherStore outboxStore,
            KafkaTemplate<String, String> kafkaTemplate) {

        this.outboxStore = outboxStore;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Run sequentially with a fixed delay.
     *
     * One event is claimed per poll.
     *
     * This is intentionally simple for LedgerBase's
     * initial reliable asynchronous event pipeline.
     *
     * Concurrent publisher instances remain safe
     * because PostgreSQL controls row claims.
     */
    @Scheduled(
        fixedDelayString =
            "${ledgerbase.kafka.outbox.publisher.poll-ms:1000}"
    )
    public void publishNextEvent() {

        Optional<OutboxPublisherStore.ClaimedEvent> claimed;

        try {
            // Claiming is a short database transaction.
            // It commits before we contact Kafka.
            claimed = outboxStore.claimNextEvent();

        } catch (Exception exception) {
            logger.error(
                "Unable to claim the next outbox event",
                exception
            );
            return;
        }

        if (claimed.isEmpty()) {
            return;
        }

        OutboxPublisherStore.ClaimedEvent event =
            claimed.get();

        SendResult<String, String> result;

        try {
            // Publish the original stored event payload.
            //
            // Waiting for Kafka acknowledgment is safe
            // because no PostgreSQL row locks are held.
            result = kafkaTemplate.send(
                event.kafkaTopic(),
                event.eventKey(),
                event.payload()
            ).get(10, TimeUnit.SECONDS);

        } catch (InterruptedException exception) {

            Thread.currentThread().interrupt();

            scheduleRetry(event, exception);

            return;

        } catch (Exception exception) {

            scheduleRetry(event, exception);

            return;
        }

        // Kafka has acknowledged the record.
        //
        // Persist that acknowledgment in PostgreSQL.
        //
        // If this update fails, the claim eventually
        // expires and the event becomes eligible again.
        //
        // This is why consumers must be idempotent.
        try {
            int updated = outboxStore.markPublished(event);

            if (updated == 1) {
                logger.info(
                    "Published eventId={} topic={} partition={} offset={}",
                    event.eventId(),
                    event.kafkaTopic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset()
                );

            } else {
                logger.warn(
                    "Kafka acknowledged eventId={}, but its "
                    + "outbox claim is no longer current",
                    event.eventId()
                );
            }

        } catch (Exception exception) {
            logger.error(
                "Kafka acknowledged eventId={}, but PostgreSQL "
                + "could not record publication; retry may occur",
                event.eventId(),
                exception
            );
        }
    }

    /**
     * Schedule exponential backoff after a failed delivery.
     *
     * Example delays:
     *
     * Attempt 1: 2 seconds
     * Attempt 2: 4 seconds
     * Attempt 3: 8 seconds
     *
     * The delay is capped at five minutes.
     *
     * Failed events remain durable in PostgreSQL.
     */
    private void scheduleRetry(
            OutboxPublisherStore.ClaimedEvent event,
            Exception exception) {

        long delaySeconds = Math.min(
            300L,
            1L << Math.min(event.attemptCount(), 8)
        );

        logger.warn(
            "Kafka publication failed for eventId={}; "
            + "retry scheduled in {} seconds",
            event.eventId(),
            delaySeconds,
            exception
        );

        try {
            int updated = outboxStore.scheduleRetry(
                event,
                delaySeconds,
                exception.toString()
            );

            if (updated != 1) {
                logger.warn(
                    "Retry was not scheduled because the claim "
                    + "is no longer current for eventId={}",
                    event.eventId()
                );
            }

        } catch (Exception databaseException) {
            // The PROCESSING claim will eventually expire.
            // Another publisher can then recover this event.
            logger.error(
                "Unable to schedule retry for eventId={}; "
                + "expired-claim recovery will be required",
                event.eventId(),
                databaseException
            );
        }
    }
}
