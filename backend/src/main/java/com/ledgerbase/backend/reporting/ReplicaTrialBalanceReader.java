package com.ledgerbase.backend.reporting;

import com.ledgerbase.backend.mapper.TrialBalanceMapper;
import com.ledgerbase.backend.model.TrialBalanceRow;

import org.apache.ibatis.session.SqlSessionFactory;

import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.boot.autoconfigure.SpringBootVFS;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

// ============================================================
// LEDGERBASE — REPLICA-BACKED TRIAL BALANCE READER
// ============================================================

/**
 * Execute Trial Balance SQL against the PostgreSQL
 * read replica using an independent MyBatis session.
 *
 * ARCHITECTURE:
 *
 * - Primary PostgreSQL: localhost:5434
 * - Replica PostgreSQL: localhost:5435
 *
 * The primary datasource continues serving:
 *
 * - Financial transaction posting
 * - Account modifications
 * - Authentication and authorization queries
 * - Flyway migrations
 * - Other consistency-sensitive reads
 *
 * This component serves explicitly selected
 * read-only Trial Balance reporting queries.
 *
 * IMPORTANT:
 *
 * The PostgreSQL replica is asynchronous.
 *
 * Recently committed primary transactions
 * may not be immediately visible on the replica.
 *
 * The component is enabled only when:
 *
 * ledgerbase.read-replica.enabled=true
 */
@Component
@ConditionalOnProperty(
    prefix = "ledgerbase.read-replica",
    name = "enabled",
    havingValue = "true"
)
public class ReplicaTrialBalanceReader {

    // ========================================================
    // LOGGING
    // ========================================================

    private static final Logger log =
        LoggerFactory.getLogger(
            ReplicaTrialBalanceReader.class
        );

    // ========================================================
    // REPLICA MYBATIS SESSION
    // ========================================================

    /**
     * Dedicated SqlSessionTemplate connected to
     * the PostgreSQL standby datasource.
     *
     * This does not replace the application's
     * existing primary MyBatis session.
     */
    private final SqlSessionTemplate replicaSession;

    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    /**
     * Configure the replica-specific MyBatis session.
     *
     * STEPS:
     *
     * 1. Receive the dedicated replica datasource.
     * 2. Verify PostgreSQL is running as a standby.
     * 3. Build an isolated MyBatis SqlSessionFactory.
     * 4. Load the existing Trial Balance SQL mapper.
     * 5. Create the replica SqlSessionTemplate.
     *
     * @param replicaDataSource
     *        Dedicated read-only PostgreSQL datasource.
     */
    public ReplicaTrialBalanceReader(
        @Qualifier("readReplicaDataSource")
        DataSource replicaDataSource
    ) throws Exception {

        // ====================================================
        // STEP 1 — VERIFY REPLICA DATABASE IDENTITY
        // ====================================================

        /**
         * pg_is_in_recovery() returns:
         *
         * true:
         *   PostgreSQL is operating as a standby.
         *
         * false:
         *   PostgreSQL is operating as a primary.
         *
         * Fail startup if the configured replica
         * connection accidentally points at a primary.
         */
        JdbcTemplate jdbcTemplate =
            new JdbcTemplate(
                replicaDataSource
            );

        Boolean isStandby =
            jdbcTemplate.queryForObject(
                "SELECT pg_is_in_recovery()",
                Boolean.class
            );

        if (!Boolean.TRUE.equals(isStandby)) {

            throw new IllegalStateException(
                "Trial Balance replica datasource "
                    + "must connect to a PostgreSQL standby"
            );
        }

        // ====================================================
        // STEP 2 — CREATE REPLICA MYBATIS FACTORY
        // ====================================================

        /**
         * Use the replica datasource rather than
         * Spring Boot's primary datasource.
         */
        SqlSessionFactoryBean factoryBean =
            new SqlSessionFactoryBean();

        factoryBean.setDataSource(
            replicaDataSource
        );

        /**
         * Support classpath mapper resource loading
         * for packaged Spring Boot applications.
         */
        factoryBean.setVfs(
            SpringBootVFS.class
        );

        // ====================================================
        // STEP 3 — CONFIGURE MYBATIS
        // ====================================================

        /**
         * Preserve camelCase mapping for Java models.
         *
         * Example:
         *
         * account_id -> accountId
         *
         * The XML mapper also defines explicit result
         * mappings for TrialBalanceRow.
         */
        org.apache.ibatis.session.Configuration
            mybatisConfiguration =
                new org.apache.ibatis.session.Configuration();

        mybatisConfiguration.setMapUnderscoreToCamelCase(
            true
        );

        factoryBean.setConfiguration(
            mybatisConfiguration
        );

        // ====================================================
        // STEP 4 — REUSE EXISTING REPORTING SQL
        // ====================================================

        /**
         * Keep the existing TrialBalanceMapper.xml.
         *
         * Reuse exactly the same SQL for primary
         * and replica reporting.
         *
         * This avoids duplicating financial
         * calculations in different mappers.
         */
        factoryBean.setMapperLocations(
            new ClassPathResource(
                "mapper/TrialBalanceMapper.xml"
            )
        );

        // ====================================================
        // STEP 5 — BUILD REPLICA SQL SESSION
        // ====================================================

        SqlSessionFactory sqlSessionFactory =
            Objects.requireNonNull(
                factoryBean.getObject(),
                "Failed to initialize replica MyBatis session"
            );

        this.replicaSession =
            new SqlSessionTemplate(
                sqlSessionFactory
            );

        // ====================================================
        // STEP 6 — CONFIRM INITIALIZATION
        // ====================================================

        log.info(
            "Trial Balance replica reader initialized "
                + "against a PostgreSQL standby"
        );
    }

    // ========================================================
    // EXECUTE TRIAL BALANCE QUERY ON REPLICA
    // ========================================================

    /**
     * Retrieve Trial Balance rows using the
     * dedicated replica-backed MyBatis session.
     *
     * @param businessId requested business ID
     * @param asOfDate inclusive reporting cutoff date
     * @return Trial Balance account rows
     */
    public List<TrialBalanceRow> findByBusinessIdAsOfDate(
            Long businessId,
            LocalDate asOfDate) {

        // ====================================================
        // STEP 1 — LOG QUERY EXECUTION
        // ====================================================

        /**
         * INFO logging makes the routing visible
         * without requiring DEBUG configuration.
         */
        log.info(
            "Executing Trial Balance query on "
                + "PostgreSQL replica: businessId={}, asOfDate={}",
            businessId,
            asOfDate
        );

        // ====================================================
        // STEP 2 — OBTAIN REPLICA-BACKED MAPPER
        // ====================================================

        /**
         * Retrieve the mapper from the replica
         * session rather than the primary session.
         */
        TrialBalanceMapper mapper =
            replicaSession.getMapper(
                TrialBalanceMapper.class
            );

        // ====================================================
        // STEP 3 — EXECUTE EXISTING REPORTING SQL
        // ====================================================

        List<TrialBalanceRow> rows =
            mapper.findByBusinessIdAsOfDate(
                businessId,
                asOfDate
            );

        // ====================================================
        // STEP 4 — CONFIRM QUERY COMPLETION
        // ====================================================

        log.info(
            "PostgreSQL replica Trial Balance query "
                + "completed: {} account rows returned",
            rows.size()
        );

        return rows;
    }
}
