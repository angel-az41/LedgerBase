package com.ledgerbase.backend.service;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Unit tests for asynchronous Kafka outbox publishing.
 *
 * These tests verify:
 *
 * - Empty outbox polls do not publish messages.
 * - Kafka acknowledgment marks an event PUBLISHED.
 * - Kafka delivery failures schedule database retries.
 * - Database acknowledgment failures remain recoverable.
 *
 * KafkaTemplate and PostgreSQL persistence are mocked.
 * No real broker or database connection is required.
 *
 * Separate integration tests verify actual delivery.
 */
class OutboxKafkaPublisherTest {

    private static final String TOPIC =
        "ledgerbase.transaction-posted.v1";

    private static final String EVENT_KEY =
        "business:1:transaction:12";

    private static final String PAYLOAD =
        """
        {
          "eventType": "TransactionPosted",
          "businessId": 1,
          "transactionId": 12
        }
        """;

    private OutboxPublisherStore outboxStore;

    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxKafkaPublisher publisher;

    /**
     * Create fresh mock dependencies before every test.
     *
     * Each test independently controls Kafka responses
     * and PostgreSQL state transition results.
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {

        outboxStore = mock(OutboxPublisherStore.class);

        kafkaTemplate = mock(KafkaTemplate.class);

        publisher = new OutboxKafkaPublisher(
            outboxStore,
            kafkaTemplate
        );
    }

    /**
     * Construct a representative claimed outbox event.
     *
     * Attempt count starts at one to match the
     * publisher's first database claim.
     */
    private OutboxPublisherStore.ClaimedEvent claimedEvent() {

        return new OutboxPublisherStore.ClaimedEvent(
            2L,
            UUID.fromString(
                "92bb8447-7c62-48f7-b3e3-f219793924d4"
            ),
            TOPIC,
            EVENT_KEY,
            PAYLOAD,
            1,
            UUID.randomUUID().toString()
        );
    }

    /**
     * No Kafka message should be published when
     * PostgreSQL contains no eligible outbox event.
     */
    @Test
    void doesNothingWhenNoEventIsAvailable() {

        when(outboxStore.claimNextEvent())
            .thenReturn(Optional.empty());

        publisher.publishNextEvent();

        verifyNoInteractions(kafkaTemplate);

        verify(outboxStore, never())
            .markPublished(any());
    }

    /**
     * Successful Kafka acknowledgment must advance
     * the matching PostgreSQL event to PUBLISHED.
     */
    @Test
    void marksEventPublishedAfterKafkaAcknowledgment() {

        var event = claimedEvent();

        when(outboxStore.claimNextEvent())
            .thenReturn(Optional.of(event));

        SendResult<String, String> result =
            new SendResult<>(
                new ProducerRecord<>(
                    TOPIC,
                    EVENT_KEY,
                    PAYLOAD
                ),
                mock(RecordMetadata.class)
            );

        when(kafkaTemplate.send(
            TOPIC,
            EVENT_KEY,
            PAYLOAD
        )).thenReturn(
            CompletableFuture.completedFuture(result)
        );

        when(outboxStore.markPublished(event))
            .thenReturn(1);

        publisher.publishNextEvent();

        verify(kafkaTemplate).send(
            TOPIC,
            EVENT_KEY,
            PAYLOAD
        );

        verify(outboxStore).markPublished(event);

        verify(outboxStore, never())
            .scheduleRetry(any(), anyLong(), anyString());
    }

    /**
     * Failed Kafka delivery must schedule another attempt.
     *
     * On the first attempt, exponential backoff
     * should produce a two-second delay.
     */
    @Test
    void schedulesRetryWhenKafkaDeliveryFails() {

        var event = claimedEvent();

        when(outboxStore.claimNextEvent())
            .thenReturn(Optional.of(event));

        when(kafkaTemplate.send(
            TOPIC,
            EVENT_KEY,
            PAYLOAD
        )).thenReturn(
            CompletableFuture.failedFuture(
                new IllegalStateException("Kafka offline")
            )
        );

        when(outboxStore.scheduleRetry(
            eq(event),
            eq(2L),
            anyString()
        )).thenReturn(1);

        publisher.publishNextEvent();

        verify(outboxStore).scheduleRetry(
            eq(event),
            eq(2L),
            contains("Kafka offline")
        );

        verify(outboxStore, never())
            .markPublished(any());
    }

    /**
     * Kafka can acknowledge a message even when
     * PostgreSQL subsequently fails to record
     * the successful publication.
     *
     * The publisher must not claim that the database
     * was updated, and must not immediately overwrite
     * the state using the normal delivery-failure path.
     *
     * The existing claim lease allows later recovery.
     * This scenario demonstrates why consumers must
     * tolerate duplicate Kafka deliveries.
     */
    @Test
    void retainsRecoverableClaimWhenPublicationUpdateFails() {

        var event = claimedEvent();

        when(outboxStore.claimNextEvent())
            .thenReturn(Optional.of(event));

        SendResult<String, String> result =
            new SendResult<>(
                new ProducerRecord<>(
                    TOPIC,
                    EVENT_KEY,
                    PAYLOAD
                ),
                mock(RecordMetadata.class)
            );

        when(kafkaTemplate.send(
            TOPIC,
            EVENT_KEY,
            PAYLOAD
        )).thenReturn(
            CompletableFuture.completedFuture(result)
        );

        when(outboxStore.markPublished(event))
            .thenThrow(
                new IllegalStateException(
                    "PostgreSQL temporarily unavailable"
                )
            );

        publisher.publishNextEvent();

        verify(outboxStore).markPublished(event);

        verify(outboxStore, never())
            .scheduleRetry(any(), anyLong(), anyString());
    }
}
