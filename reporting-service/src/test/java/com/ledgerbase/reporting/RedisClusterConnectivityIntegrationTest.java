package com.ledgerbase.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — REDIS CLUSTER CONNECTIVITY INTEGRATION TESTS
// ============================================================

/**
 * Verify that the independent Reporting Service can
 * connect to the local six-node Redis Cluster.
 *
 * TEST ARCHITECTURE:
 *
 * Spring Boot Test
 *       |
 *       v
 * Spring Data Redis + Lettuce
 *       |
 *       v
 * Redis Cluster (ports 7000-7005)
 *
 * TEST COVERAGE:
 *
 * - Redis connection factory initialization.
 * - Cluster topology discovery.
 * - Six Redis nodes discovered.
 * - All 16,384 hash slots assigned.
 * - Redis SET and GET through Lettuce.
 * - Key expiration and cleanup.
 *
 * These tests require:
 *
 * - A running local Redis Cluster.
 * - A correctly configured REDIS_PASSWORD.
 * - The Reporting Service's normal Spring context
 *   dependencies and required environment variables.
 *
 * They are disabled during ordinary Maven test runs.
 *
 * To enable:
 *
 * LEDGERBASE_REDIS_IT=true
 *
 * SECURITY:
 *
 * - Uses temporary synthetic test keys.
 * - Does not store JWTs or real financial records.
 * - Does not modify PostgreSQL data.
 *
 * Redis will only be used for actual financial report
 * caching after a separate correctness checkpoint.
 */
@EnabledIfEnvironmentVariable(
    named = "LEDGERBASE_REDIS_IT",
    matches = "true"
)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class RedisClusterConnectivityIntegrationTest {

    // ========================================================
    // REDIS CONNECTION FACTORY
    // ========================================================

    /**
     * Spring Boot automatically creates a cluster-aware
     * RedisConnectionFactory using Lettuce.
     *
     * The factory reads configuration from:
     *
     * spring.data.redis.cluster.nodes
     * spring.data.redis.password
     */
    @Autowired
    private RedisConnectionFactory connectionFactory;

    // ========================================================
    // REDIS STRING TEMPLATE
    // ========================================================

    /**
     * Spring Boot automatically provides a
     * StringRedisTemplate for string keys and values.
     *
     * Lettuce selects the correct Redis primary node
     * using the cluster's hash slot assignments.
     */
    @Autowired
    private StringRedisTemplate redisTemplate;

    // ========================================================
    // TEST 1 — CLUSTER TOPOLOGY
    // ========================================================

    /**
     * Verify that Spring Data Redis connects to
     * the actual Redis Cluster, not just one
     * standalone Redis instance.
     *
     * The local topology has:
     *
     * - Six Redis nodes
     * - Three primaries
     * - Three replicas
     * - 16,384 assigned hash slots
     *
     * This test checks node discovery and slot coverage.
     * Detailed node role checks are also available
     * through redis-cli CLUSTER NODES.
     */
    @Test
    void redisClientDiscoversClusterTopology() {

        assertNotNull(connectionFactory);

        try (
            RedisClusterConnection connection =
                connectionFactory.getClusterConnection()
        ) {

            // --------------------------------------------
            // VERIFY SIX CLUSTER NODES
            // --------------------------------------------

            long discoveredNodes =
                StreamSupport.stream(
                    connection.clusterGetNodes()
                        .spliterator(),
                    false
                ).count();

            assertEquals(
                6L,
                discoveredNodes,
                "Redis Cluster should contain six nodes"
            );

            // --------------------------------------------
            // VERIFY HASH SLOT COVERAGE
            // --------------------------------------------

            var clusterInfo =
                connection.clusterGetClusterInfo();

            assertNotNull(clusterInfo);

            assertTrue(
                clusterInfo.getSlotsAssigned() == 16384,
                "All 16,384 hash slots must be assigned"
            );
        }
    }

    // ========================================================
    // TEST 2 — CLUSTER READ AND WRITE
    // ========================================================

    /**
     * Verify that Redis operations work through the
     * cluster-aware StringRedisTemplate.
     *
     * Two different hash tags are used to exercise
     * independent cluster-key routing.
     *
     * Both keys receive a short expiration.
     *
     * Test cleanup removes them even if an
     * assertion fails after the keys are created.
     */
    @Test
    void redisClusterSupportsStringReadWrite()
            throws Exception {

        assertNotNull(redisTemplate);

        // ----------------------------------------------------
        // CREATE UNIQUE TEMPORARY TEST KEYS
        // ----------------------------------------------------

        String uniqueId =
            UUID.randomUUID().toString();

        String firstKey =
            "ledgerbase:redis:test:{business-2}:"
                + uniqueId;

        String secondKey =
            "ledgerbase:redis:test:{business-999}:"
                + uniqueId;

        Duration expiration =
            Duration.ofSeconds(60);

        try {

            // ------------------------------------------------
            // WRITE TO REDIS CLUSTER
            // ------------------------------------------------

            redisTemplate.opsForValue().set(
                firstKey,
                "reporting-connected",
                expiration
            );

            redisTemplate.opsForValue().set(
                secondKey,
                "cluster-routing-working",
                expiration
            );

            // ------------------------------------------------
            // READ BACK THE STORED VALUES
            // ------------------------------------------------

            assertEquals(
                "reporting-connected",
                redisTemplate.opsForValue().get(
                    firstKey
                )
            );

            assertEquals(
                "cluster-routing-working",
                redisTemplate.opsForValue().get(
                    secondKey
                )
            );

            // ------------------------------------------------
            // VERIFY KEY EXPIRATION
            // ------------------------------------------------

            Long remainingTtl =
                redisTemplate.getExpire(firstKey);

            assertNotNull(
                remainingTtl,
                "Redis must return the key TTL"
            );

            assertTrue(
                remainingTtl > 0,
                "The temporary key must expire"
            );

        } finally {

            // ------------------------------------------------
            // REMOVE TEMPORARY TEST DATA
            // ------------------------------------------------

            redisTemplate.delete(firstKey);
            redisTemplate.delete(secondKey);
        }
    }
}
