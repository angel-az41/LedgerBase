-- ============================================================
-- LEDGERBASE — BUSINESS REPORTING REVISIONS
-- ============================================================
--
-- Introduce a durable reporting revision for each business.
--
-- PURPOSE:
--
-- - Track changes that affect financial reports.
-- - Support version-aware Redis cache keys.
-- - Prevent previously cached financial reports from
--   being selected after committed accounting changes.
--
-- CONSISTENCY:
--
-- PostgreSQL is the authoritative source of revisions.
--
-- Reporting revisions will be incremented inside the
-- same database transaction as the accounting mutation.
--
-- PostgreSQL streaming replication propagates the
-- committed accounting changes and their revisions
-- to the read replica.
--
-- Redis does not manage authoritative revisions.

-- ============================================================
-- ADD REPORTING REVISION TO BUSINESSES
-- ============================================================

-- Existing businesses start with revision zero.
--
-- BIGINT supports a large number of future changes.
-- NOT NULL ensures every business has a revision.

ALTER TABLE businesses
ADD COLUMN reporting_revision BIGINT NOT NULL DEFAULT 0;

-- ============================================================
-- ENFORCE NONNEGATIVE REVISIONS
-- ============================================================

-- Reporting revisions must never become negative.

ALTER TABLE businesses
ADD CONSTRAINT businesses_reporting_revision_nonnegative
CHECK (reporting_revision >= 0);
