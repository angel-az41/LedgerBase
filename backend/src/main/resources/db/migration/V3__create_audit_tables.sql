-- =====================================================
-- LedgerBase - Database Migration V3
-- Audit Trail
-- =====================================================
--
-- Stores an append-only history of important actions.
-- Examples: transaction creation, posting, reversal.
--
-- Audit events are separate from ledger entries:
-- ledger entries represent financial movements,
-- while audit events describe actions performed.
-- =====================================================

CREATE TABLE audit_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- Business affected by the recorded action.
    business_id BIGINT NOT NULL
        REFERENCES businesses(id) ON DELETE RESTRICT,

    -- User who performed the action.
    actor_id BIGINT NOT NULL
        REFERENCES users(id) ON DELETE RESTRICT,

    -- Type of action, such as TRANSACTION_POSTED.
    action VARCHAR(100) NOT NULL,

    -- Name and ID of the affected entity.
    entity_type VARCHAR(100) NOT NULL,
    entity_id BIGINT NOT NULL,

    -- Optional structured details of the action.
    -- Never store passwords or other secrets here.
    details JSONB,

    -- Timestamp of the recorded event.
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Prevent blank action and entity type values.
    CONSTRAINT chk_audit_action
        CHECK (LENGTH(TRIM(action)) > 0),

    CONSTRAINT chk_audit_entity_type
        CHECK (LENGTH(TRIM(entity_type)) > 0)
);


-- =====================================================
-- Audit Query Indexes
-- =====================================================

-- Efficiently retrieve recent audit events for a business.
CREATE INDEX idx_audit_business_time
ON audit_events(business_id, created_at DESC);

-- Find the audit history of a particular entity.
CREATE INDEX idx_audit_entity
ON audit_events(entity_type, entity_id);
