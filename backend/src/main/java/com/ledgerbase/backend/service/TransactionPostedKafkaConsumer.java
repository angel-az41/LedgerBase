package com.ledgerbase.backend.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka listener for committed financial transaction events.
 *
 * This listener is enabled only through the explicit
 * Kafka inbox consumer configuration property.
 *
 * The processing service has its own PostgreSQL
 * transaction and commits before returning.
 *
 * Kafka acknowledgment happens only after that
 * transaction has committed successfully.
 *
 * If processing fails, the exception propagates
 * to the Kafka container's configured error handler.
 *
 * The listener never updates ledger balances.
 */
@Component
@ConditionalOnProperty(
    prefix = "ledgerbase.kafka.inbox.consumer",
    name = "enabled",
    havingValue = "true"
)
public class TransactionPostedKafkaConsumer {

    private static final Logger logger =
        LoggerFactory.getLogger(
            TransactionPostedKafkaConsumer.class
        );

    private final TransactionPostedInboxService inboxService;

    public TransactionPostedKafkaConsumer(
            TransactionPostedInboxService inboxService) {

        this.inboxService = inboxService;
    }

    /**
     * Consume one TransactionPosted record.
     *
     * Returning from inboxService.process() means
     * Spring has completed its database transaction.
     *
     * A Kafka offset is acknowledged only afterward.
     */
    @KafkaListener(
        topics =
            "${ledgerbase.kafka.inbox.consumer.topic:ledgerbase.transaction-posted.v1}"
    )
    public void onTransactionPosted(
            ConsumerRecord<String, String> record,
            Acknowledgment acknowledgment) {

        // Process and commit the PostgreSQL inbox receipt.
        // Exceptions propagate without acknowledging Kafka.
        boolean newlyProcessed = inboxService.process(record);

        // Acknowledging after the service returns ensures
        // PostgreSQL has already committed the receipt.
        acknowledgment.acknowledge();

        if (newlyProcessed) {
            logger.info(
                "Processed TransactionPosted event "
                    + "topic={} partition={} offset={}",
                record.topic(),
                record.partition(),
                record.offset()
            );
        } else {
            logger.info(
                "Skipped duplicate TransactionPosted event "
                    + "topic={} partition={} offset={}",
                record.topic(),
                record.partition(),
                record.offset()
            );
        }
    }
}
