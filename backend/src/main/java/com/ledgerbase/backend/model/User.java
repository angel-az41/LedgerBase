package com.ledgerbase.backend.model;

import java.time.OffsetDateTime;

// ============================================================
// LEDGERBASE — USER MODEL
// ============================================================

/**
 * Represents a LedgerBase user stored in PostgreSQL.
 *
 * DATABASE TABLE:
 *
 * users
 *
 * PURPOSE:
 *
 * Store the user's persistent identity and authentication
 * information.
 *
 * A user may belong to multiple businesses through the
 * business_memberships table.
 *
 * SECURITY:
 *
 * passwordHash stores a password hash, never plaintext.
 *
 * This model is intended for internal backend operations.
 * It must not be returned directly from REST controllers
 * because doing so could expose passwordHash.
 *
 * Authentication responses will use separate DTOs that
 * contain only explicitly approved fields.
 */
public class User {

    // ========================================================
    // DATABASE FIELDS
    // ========================================================

    private Long id;

    private String email;

    private String passwordHash;

    private String fullName;

    private OffsetDateTime createdAt;


    // ========================================================
    // DEFAULT CONSTRUCTOR
    // ========================================================

    /**
     * Required for standard Java bean mapping.
     *
     * MyBatis creates the object and populates its fields
     * using the configured SQL result mapping.
     */
    public User() {
    }


    // ========================================================
    // GETTERS AND SETTERS
    // ========================================================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }


    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }


    /**
     * Internal authentication credential.
     *
     * Never include this value in API response DTOs,
     * application logs, or frontend state.
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }


    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }


    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
