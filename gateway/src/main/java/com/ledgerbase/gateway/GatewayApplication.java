package com.ledgerbase.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// ============================================================
// LEDGERBASE — API GATEWAY APPLICATION
// ============================================================

/**
 * Entry point for the standalone LedgerBase API Gateway.
 *
 * RESPONSIBILITIES:
 *
 * - Receive HTTP requests from the React frontend.
 * - Route API requests to downstream services.
 * - Provide independent gateway health monitoring.
 * - Support future microservices integration.
 *
 * ARCHITECTURE:
 *
 * React frontend (5173)
 *       |
 *       v
 * Spring Cloud Gateway (8081)
 *       |
 *       v
 * LedgerBase backend (8080)
 *
 * IMPORTANT:
 *
 * The gateway does not execute financial transactions
 * or connect directly to PostgreSQL.
 *
 * The existing accounting backend continues handling
 * JWT authentication, RBAC, and financial operations.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {

        SpringApplication.run(
            GatewayApplication.class,
            args
        );
    }
}
