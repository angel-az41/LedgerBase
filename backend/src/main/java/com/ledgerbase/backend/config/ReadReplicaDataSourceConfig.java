package com.ledgerbase.backend.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// ============================================================
// LEDGERBASE — READ REPLICA DATASOURCE CONFIGURATION
// ============================================================

/**
 * Configure a dedicated PostgreSQL read-replica
 * connection pool.
 *
 * PRIMARY DATABASE:
 *
 * Spring Boot continues to auto-configure the
 * existing spring.datasource connection.
 *
 * MyBatis, transaction management, and Flyway
 * continue using that primary connection.
 *
 * READ REPLICA:
 *
 * This class adds an independent HikariCP pool
 * connected to the PostgreSQL standby.
 *
 * The replica is available only when the
 * ledgerbase.read-replica.enabled property is true.
 *
 * IMPORTANT:
 *
 * This configuration does not automatically route
 * queries to the replica. Reporting operations
 * must explicitly select the replica datasource.
 */
@Configuration(proxyBeanMethods = false)
public class ReadReplicaDataSourceConfig {

    // ========================================================
    // READ REPLICA CONNECTION POOL
    // ========================================================

    /**
     * Register an additional datasource without replacing
     * Spring Boot's primary auto-configured datasource.
     *
     * defaultCandidate = false:
     *   Preserves normal datasource auto-configuration.
     *
     * ConditionalOnProperty:
     *   Keeps the replica disabled for ordinary startup
     *   and existing test profiles.
     *
     * @param jdbcUrl PostgreSQL replica connection URL
     * @param username read-only PostgreSQL username
     * @param password read-only PostgreSQL password
     * @param maximumPoolSize maximum replica connections
     * @return configured HikariCP datasource
     */
    @Bean(
        name = "readReplicaDataSource",
        defaultCandidate = false
    )
    @ConditionalOnProperty(
        prefix = "ledgerbase.read-replica",
        name = "enabled",
        havingValue = "true"
    )
    public HikariDataSource readReplicaDataSource(
        @Value("${ledgerbase.read-replica.jdbc-url}")
        String jdbcUrl,

        @Value("${ledgerbase.read-replica.username}")
        String username,

        @Value("${ledgerbase.read-replica.password}")
        String password,

        @Value("${ledgerbase.read-replica.maximum-pool-size:4}")
        int maximumPoolSize
    ) {

        // ====================================================
        // REQUIRE VALID CONNECTION CREDENTIALS
        // ====================================================

        if (
            password == null ||
            password.isBlank()
        ) {
            throw new IllegalStateException(
                "PostgreSQL read replica password is missing"
            );
        }

        // ====================================================
        // CONFIGURE HIKARICP
        // ====================================================

        HikariConfig config = new HikariConfig();

        config.setPoolName(
            "ledgerbase-read-replica-pool"
        );

        config.setJdbcUrl(jdbcUrl);

        config.setUsername(username);

        config.setPassword(password);

        // Replica queries must not perform writes.
        config.setReadOnly(true);

        // Keep the replica pool independent and small.
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(0);

        // Fail promptly when connections cannot be made.
        config.setConnectionTimeout(5000);
        config.setValidationTimeout(2000);

        // ====================================================
        // CREATE THE REPLICA CONNECTION POOL
        // ====================================================

        return new HikariDataSource(config);
    }
}
