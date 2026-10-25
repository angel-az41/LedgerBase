package com.ledgerbase.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enable Spring's scheduled task infrastructure.
 *
 * The Kafka outbox publisher itself is conditional:
 * it only exists when explicitly enabled through
 * the Kafka outbox configuration profile.
 *
 * Scheduling infrastructure does not automatically
 * activate Kafka publishing in normal test runs.
 */
@Configuration
@EnableScheduling
public class OutboxSchedulingConfig {
}
