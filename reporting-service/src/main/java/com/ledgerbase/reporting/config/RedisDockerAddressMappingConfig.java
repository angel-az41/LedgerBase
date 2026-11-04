package com.ledgerbase.reporting.config;

import io.lettuce.core.internal.HostAndPort;
import io.lettuce.core.resource.MappingSocketAddressResolver;

import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adapts the existing local Redis Cluster topology
 * for Dockerized Reporting Service instances.
 *
 * The development Redis nodes advertise localhost
 * addresses because the original cluster was
 * configured for applications running on macOS.
 *
 * A Docker container cannot directly use those
 * addresses because 127.0.0.1 refers to the
 * container's own network namespace.
 *
 * This configuration translates Redis-advertised
 * localhost endpoints into the Docker Compose
 * Redis service hostname.
 *
 * The mapping is enabled explicitly through an
 * environment property.
 *
 * It does not modify Redis server configuration,
 * cluster membership, hash slots, or persisted data.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "ledgerbase.redis",
    name = "docker-address-mapping-enabled",
    havingValue = "true"
)
public class RedisDockerAddressMappingConfig {

    private static final String REDIS_DOCKER_HOST =
        "redis-cluster";

    private static final int FIRST_REDIS_PORT = 7000;

    private static final int LAST_REDIS_PORT = 7005;

    /**
     * Customize Lettuce networking resources before
     * the Redis connection factory is initialized.
     *
     * Spring Boot retains responsibility for normal
     * Redis configuration, credentials, and timeouts.
     */
    @Bean
    public ClientResourcesBuilderCustomizer
            redisDockerAddressMappingCustomizer() {

        return builder -> builder.socketAddressResolver(
            MappingSocketAddressResolver.create(
                RedisDockerAddressMappingConfig::mapAddress
            )
        );
    }

    /**
     * Translate only the six known local Redis
     * Cluster endpoints.
     *
     * All unrelated addresses remain unchanged.
     */
    static HostAndPort mapAddress(
            HostAndPort originalAddress) {

        String host = originalAddress.getHostText();

        int port = originalAddress.getPort();

        boolean isLocalRedisNode =
            ("127.0.0.1".equals(host)
                || "localhost".equalsIgnoreCase(host))
            && port >= FIRST_REDIS_PORT
            && port <= LAST_REDIS_PORT;

        if (isLocalRedisNode) {

            return HostAndPort.of(
                REDIS_DOCKER_HOST,
                port
            );
        }

        return originalAddress;
    }
}
