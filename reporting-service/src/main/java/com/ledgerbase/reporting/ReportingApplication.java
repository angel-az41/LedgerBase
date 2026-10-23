package com.ledgerbase.reporting;

import org.mybatis.spring.annotation.MapperScan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// ============================================================
// LEDGERBASE — FINANCIAL REPORTING SERVICE
// ============================================================

/**
 * Independent Spring Boot application responsible
 * for financial reporting workloads.
 *
 * ARCHITECTURE:
 *
 * React Frontend
 *       |
 *       v
 * Spring Cloud Gateway
 *       |
 *       v
 * Financial Reporting Service
 *       |
 *       v
 * PostgreSQL Read Replica
 *
 * PLANNED RESPONSIBILITIES:
 *
 * - Generate Trial Balance reports.
 * - Generate Income Statements.
 * - Generate Balance Sheets.
 * - Perform read-only financial aggregation.
 * - Enforce reporting authorization.
 *
 * ACCOUNTING SAFETY:
 *
 * - This service will not post journal entries.
 * - It will not modify general ledger balances.
 * - Financial writes remain in Accounting Core.
 *
 * Initially, the application exposes only
 * its own Actuator health endpoint.
 */
@SpringBootApplication
@MapperScan("com.ledgerbase.reporting.mapper")
public class ReportingApplication {

    public static void main(String[] args) {

        SpringApplication.run(
            ReportingApplication.class,
            args
        );
    }
}
