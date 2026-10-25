package com.ledgerbase.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

import org.springframework.util.backoff.FixedBackOff;

/**
 * Error handling for the durable financial event inbox.
 *
 * Events that cannot be processed must not be silently
 * treated as successfully consumed.
 *
 * This initial implementation retries failed records
 * with a fixed backoff instead of skipping them.
 *
 * A permanently invalid record may block its Kafka
 * partition until the issue is resolved.
 *
 * Dead-letter topic handling and operational alerts
 * can be added when expanding the event pipeline.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "ledgerbase.kafka.inbox.consumer",
    name = "enabled",
    havingValue = "true"
)
public class KafkaInboxErrorHandlerConfig {

    @Bean
    public CommonErrorHandler kafkaInboxErrorHandler() {

        return new DefaultErrorHandler(
            new FixedBackOff(
                1000L,
                FixedBackOff.UNLIMITED_ATTEMPTS
            )
        );
    }
}
