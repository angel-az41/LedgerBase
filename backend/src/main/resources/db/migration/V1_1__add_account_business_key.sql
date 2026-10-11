-- =====================================================
-- LedgerBase - Migration V1.1
-- Business-Scoped Account References
-- =====================================================
--
-- Adds a composite unique key to accounts so future
-- foreign keys can enforce business ownership.
-- =====================================================

ALTER TABLE accounts
ADD CONSTRAINT uq_account_business_id
UNIQUE (business_id, id);
