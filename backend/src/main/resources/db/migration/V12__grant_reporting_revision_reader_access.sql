-- ============================================================
-- LEDGERBASE — REPORTING REVISION READER PERMISSIONS
-- ============================================================

-- PURPOSE:
--
-- Allow the independent Reporting Service to verify
-- PostgreSQL read-replica freshness using the business's
-- reporting_revision.
--
-- Reporting Service connects using ledgerbase_reader.
--
-- SECURITY:
--
-- Grant SELECT only on the two required columns:
--
-- id
-- reporting_revision
--
-- Do not grant INSERT, UPDATE, DELETE, or unrestricted
-- access to business records.
--
-- PostgreSQL WAL replication propagates these catalog
-- permission changes from primary to standby.
--
-- Some isolated test environments may not provision the
-- ledgerbase_reader role. Skip this grant in those
-- environments without breaking Flyway initialization.

DO $$
BEGIN

    IF EXISTS (
        SELECT 1
        FROM pg_roles
        WHERE rolname = 'ledgerbase_reader'
    ) THEN

        GRANT SELECT (
            id,
            reporting_revision
        )
        ON TABLE public.businesses
        TO ledgerbase_reader;

    END IF;

END
$$;
