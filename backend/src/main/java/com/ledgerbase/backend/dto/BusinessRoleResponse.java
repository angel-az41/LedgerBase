package com.ledgerbase.backend.dto;

// ============================================================
// LEDGERBASE — BUSINESS ROLE RESPONSE
// ============================================================

/**
 * Return the authenticated user's membership role
 * for one accessible business.
 *
 * This response is used by React to determine which
 * accounting controls should be visible.
 *
 * SUPPORTED ROLES:
 *
 * OWNER
 * ADMIN
 * ACCOUNTANT
 * VIEWER
 *
 * SECURITY:
 *
 * The role is retrieved from PostgreSQL using
 * the user ID derived from a verified JWT.
 *
 * React must never submit or select its own role.
 *
 * Backend authorization remains authoritative.
 * Hiding frontend controls does not replace
 * Spring Security permission checks.
 *
 * Example JSON:
 *
 * {
 *     "role": "VIEWER"
 * }
 *
 * @param role authenticated user's membership role
 */
public record BusinessRoleResponse(
    String role
) {
}
