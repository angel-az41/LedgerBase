-- =====================================================
-- LedgerBase - Transactional Outbox
-- =====================================================
--
-- PURPOSE:
--
-- Reliably record financial domain events in PostgreSQL
-- before publishing them asynchronously to Apache Kafka.
--
-- A successful journal posting will insert an outbox
-- event within the same database transaction as:
--
-- - The DRAFT -> POSTED status transition.
-- - The posting audit event.
-- - The business reporting revision increment.
--
-- If the posting transaction rolls back, the outbox
-- event rolls back automatically.
--
-- Kafka is not contacted inside the financial posting
-- transaction.
--
-- DELIVERY MODEL:
--
-- PENDING:
--   Event is available for publication.
--
-- PROCESSING:
--   A publisher temporarily owns the event.
--
-- PUBLISHED:
--   Kafka acknowledged the event.
--
-- DEAD:
--   Event requires investigation after repeated failures.
--
-- Publisher retries and consumer deduplication will
-- provide at-least-once event delivery.
--
-- =====================================================


-- =====================================================
-- TRANSACTIONAL OUTBOX TABLE
-- =====================================================

CREATE TABLE outbox_events (

    -- Internal PostgreSQL row identifier.
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- Stable event identifier used for deduplication.
    -- PostgreSQL generates a UUID for each event.
    event_id UUID NOT NULL DEFAULT gen_random_uuid(),

    -- The business that owns this financial event.
    business_id BIGINT NOT NULL,

    -- The accounting transaction that produced the event.
    transaction_id BIGINT NOT NULL,

    -- Financial domain event name.
    -- Initial supported value: TRANSACTION_POSTED.
    event_type VARCHAR(80) NOT NULL,

    -- Kafka topic receiving this event.
    kafka_topic VARCHAR(249) NOT NULL,

    -- Kafka message key used for partition assignment.
    --
    -- We will use a deterministic business/transaction
    -- identifier so events for the same aggregate are
    -- assigned consistently to a partition.
    event_key VARCHAR(128) NOT NULL,

    -- Financial event payload stored as structured JSON.
    --
    -- Payloads must not contain JWTs, passwords,
    -- credentials, or other authentication secrets.
    payload JSONB NOT NULL,

    -- Current publication lifecycle state.
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    -- Number of publication attempts.
    attempt_count INTEGER NOT NULL DEFAULT 0,

    -- Earliest time at which another attempt is eligible.
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Publisher instance currently processing this event.
    --
    -- These values support recovery if a publisher
    -- crashes before acknowledging publication.
    claimed_by VARCHAR(120),

    claimed_until TIMESTAMPTZ,

    -- Database insertion timestamp.
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Timestamp of successful Kafka acknowledgment.
    published_at TIMESTAMPTZ,

    -- Most recent publication failure, if any.
    last_error TEXT,

    -- =================================================
    -- TENANT-SAFE FINANCIAL REFERENCE
    -- =================================================

    -- The transaction and event must belong to
    -- the same business.
    CONSTRAINT fk_outbox_transaction
        FOREIGN KEY (business_id, transaction_id)
        REFERENCES transactions(business_id, id)
        ON DELETE RESTRICT,

    -- =================================================
    -- EVENT UNIQUENESS
    -- =================================================

    -- Every event has a globally unique event UUID.
    CONSTRAINT uq_outbox_event_id
        UNIQUE (event_id),

    -- Prevent the same event type from being created
    -- twice for one accounting transaction.
    --
    -- This is appropriate for the one-time posting
    -- event. Future repeatable events will need a
    -- distinct event identity or sequence.
    CONSTRAINT uq_outbox_transaction_event
        UNIQUE (business_id, transaction_id, event_type),

    -- =================================================
    -- DATA VALIDATION
    -- =================================================

    CONSTRAINT chk_outbox_event_type
        CHECK (LENGTH(TRIM(event_type)) > 0),

    CONSTRAINT chk_outbox_kafka_topic
        CHECK (LENGTH(TRIM(kafka_topic)) > 0),

    CONSTRAINT chk_outbox_event_key
        CHECK (LENGTH(TRIM(event_key)) > 0),

    -- Only JSON objects are accepted as event payloads.
    CONSTRAINT chk_outbox_payload_object
        CHECK (jsonb_typeof(payload) = 'object'),

    CONSTRAINT chk_outbox_status
        CHECK (
            status IN (
                'PENDING',
                'PROCESSING',
                'PUBLISHED',
                'DEAD'
            )
        ),

    CONSTRAINT chk_outbox_attempt_count
        CHECK (attempt_count >= 0),

    -- Claim owner and expiration must be set together,
    -- or both be absent.
    CONSTRAINT chk_outbox_claim_pair
        CHECK (
            (claimed_by IS NULL AND claimed_until IS NULL)
            OR
            (claimed_by IS NOT NULL AND claimed_until IS NOT NULL)
        )
);


-- =====================================================
-- PUBLISHER POLLING INDEX
-- =====================================================
--
-- The publisher will retrieve eligible PENDING rows
-- ordered by retry eligibility and event ID.
--
-- The partial index excludes already published events.

CREATE INDEX idx_outbox_pending
ON outbox_events (next_attempt_at, id)
WHERE status = 'PENDING';


-- =====================================================
-- EXPIRED CLAIM RECOVERY INDEX
-- =====================================================
--
-- A publisher may crash after claiming an event.
--
-- Another worker can later recover the event once
-- claimed_until has expired.
--
-- The future publisher implementation will coordinate
-- claims using PostgreSQL row-level locking.

CREATE INDEX idx_outbox_expired_claims
ON outbox_events (claimed_until, id)
WHERE status = 'PROCESSING';


-- =====================================================
-- BUSINESS EVENT LOOKUP INDEX
-- =====================================================
--
-- Supports troubleshooting and querying event history
-- for one business.

CREATE INDEX idx_outbox_business_created
ON outbox_events (business_id, created_at DESC, id DESC);


-- =====================================================
-- END OF TRANSACTIONAL OUTBOX MIGRATION
-- =====================================================
