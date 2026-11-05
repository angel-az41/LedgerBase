-- ============================================================
-- LEDGERBASE — CASE-INSENSITIVE USER EMAIL UNIQUENESS
-- ============================================================
--
-- PURPOSE:
--
-- Prevent multiple user accounts from using email
-- addresses that differ only in capitalization or
-- surrounding whitespace.
--
-- EXAMPLES:
--
-- angel@example.com
-- Angel@Example.com
--  angel@example.com
--
-- These are treated as the same account identity.
--
-- Existing user records are preserved.
-- Existing business memberships are unchanged.
--
-- IMPORTANT:
--
-- Run the duplicate-email diagnostic before applying
-- this migration. A conflicting existing user group
-- will cause index creation to fail rather than
-- silently merge or delete user accounts.
--
-- The original users.email UNIQUE constraint is retained.

CREATE UNIQUE INDEX uq_users_email_normalized
ON users (
    LOWER(BTRIM(email))
);

-- ============================================================
-- END OF MIGRATION
-- ============================================================
