package com.ledgerbase.backend.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.kafka.support.Acknowledgment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for Kafka consumer acknowledgment behavior.
 *
 * The inbox service and Kafka acknowledgment are mocked.
 * No PostgreSQL or Kafka broker is required.
 *
 * These tests verify that:
 *
 * - Successfully processed events are acknowledged.
 * - Duplicate events are safely acknowledged.
 * - Failed processing does not acknowledge the record.
 *
 * Kafka acknowledgement occurs only after the inbox
 * service has returned from its transaction boundary.
 */
class TransactionPostedKafkaConsumerTest {

    private TransactionPostedInboxService inboxService;

    private TransactionPostedKafkaConsumer consumer;

    private Acknowledgment acknowledgment;

    /**
     * Create fresh mock dependencies for each test.
     */
    @BeforeEach
    void setUp() {

        inboxService =
            mock(TransactionPostedInboxService.class);

        acknowledgment =
            mock(Acknowledgment.class);

        consumer = new TransactionPostedKafkaConsumer(
            inboxService
        );
    }

    /**
     * Create a representative Kafka record.
     *
     * The inbox service is mocked, so these tests
     * focus on listener behavior rather than JSON parsing.
     */
    private ConsumerRecord<String, String> createRecord() {

        return new ConsumerRecord<>(
            "ledgerbase.transaction-posted.v1",
            2,
            100L,
            "business:1:transaction:12",
            "{\"eventType\":\"TransactionPosted\"}"
        );
    }

    /**
     * A newly processed event must be acknowledged
     * after the inbox service reports success.
     */
    @Test
    void acknowledgesSuccessfullyProcessedEvent() {

        ConsumerRecord<String, String> record =
            createRecord();

        when(inboxService.process(record))
            .thenReturn(true);

        consumer.onTransactionPosted(
            record,
            acknowledgment
        );

        // Verify that database processing occurs
        // before Kafka acknowledgment.
        var order = inOrder(
            inboxService,
            acknowledgment
        );

        order.verify(inboxService).process(record);
        order.verify(acknowledgment).acknowledge();
    }

    /**
     * A previously processed event is a successful
     * idempotent outcome.
     *
     * The listener must acknowledge it to avoid
     * unnecessary repeated Kafka deliveries.
     */
    @Test
    void acknowledgesDuplicateEvent() {

        ConsumerRecord<String, String> record =
            createRecord();

        when(inboxService.process(record))
            .thenReturn(false);

        consumer.onTransactionPosted(
            record,
            acknowledgment
        );

        verify(inboxService).process(record);

        verify(acknowledgment).acknowledge();
    }

    /**
     * A database or validation failure must not
     * acknowledge the Kafka record.
     *
     * The exception propagates to Spring Kafka's
     * configured error handler for retry handling.
     */
    @Test
    void doesNotAcknowledgeWhenProcessingFails() {

        ConsumerRecord<String, String> record =
            createRecord();

        when(inboxService.process(record))
            .thenThrow(
                new IllegalStateException(
                    "Simulated PostgreSQL processing failure"
                )
            );

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> consumer.onTransactionPosted(
                record,
                acknowledgment
            )
        );

        assertEquals(
            "Simulated PostgreSQL processing failure",
            exception.getMessage()
        );

        verify(inboxService).process(record);

        // A failed event must remain unacknowledged.
        verifyNoInteractions(acknowledgment);
    }
}
