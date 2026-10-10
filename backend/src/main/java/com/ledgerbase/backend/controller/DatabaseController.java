package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.mapper.DatabaseMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for database connectivity checks.
 *
 * Provides an HTTP endpoint that queries PostgreSQL
 * through the MyBatis mapper.
 */
@RestController
@RequestMapping("/api/v1/health")
public class DatabaseController {

    private final DatabaseMapper databaseMapper;

    // Spring injects the mapper using constructor injection.
    public DatabaseController(DatabaseMapper databaseMapper) {
        this.databaseMapper = databaseMapper;
    }

    /**
     * GET /api/v1/health/database
     *
     * Returns the connected PostgreSQL database name.
     */
    @GetMapping("/database")
    public Map<String, String> checkDatabase() {

        // Execute SQL through MyBatis.
        String databaseName = databaseMapper.getDatabaseName();

        // Spring Boot automatically converts this Map to JSON.
        return Map.of(
                "status", "connected",
                "database", databaseName
        );
    }
}
