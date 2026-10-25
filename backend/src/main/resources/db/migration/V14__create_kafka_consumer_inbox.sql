-- ============================================================
-- LEDGERBASE — KAFKA CONSUMER INBOX
-- ============================================================
--
-- Store durable receipts for Kafka events successfully
-- processed by a specific logical consumer.
--
-- Kafka supports at-least-once message delivery.
-- The same event may therefore arrive multiple times.
--
-- The combination of consumer_name and event_id
-- uniquely identifies an event processed by a consumer.
--
-- A consumer inserts its receipt in the same PostgreSQL
-- transaction as its downstream database changes.
--
-- If processing fails, that transaction must roll back
-- so that Kafka can redeliver the event safely.
--
-- Financial transactions remain authoritative in
-- the existing transactions and ledger_entries tables.
-- This inbox never reposts financial transactions.

CREATE TABLE kafka_consumer_inbox (

    -- Logical consumer identifier.
    -- Multiple independent consumers can process the
    -- same event without conflicting with one another.
    consumer_name VARCHAR(120) NOT NULL,

    -- Stable UUID supplied by the event producer.
    -- Redelivery of the same event retains this ID.
    event_id UUID NOT NULL,

    -- Business and transaction associated with
    -- the original financial event.
    business_id BIGINT NOT NULL,
    transaction_id BIGINT NOT NULL,

    -- Domain event metadata.
    event_type VARCHAR(80) NOT NULL,
    event_version INTEGER NOT NULL,

    -- Kafka source information for diagnostics.
    kafka_topic VARCHAR(249) NOT NULL,
    kafka_partition INTEGER NOT NULL,
    kafka_offset BIGINT NOT NULL,

    -- Preserve the original event for auditing.
    payload JSONB NOT NULL,

    -- Timestamp when processing successfully recorded
    -- the durable event receipt.
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Deduplication is scoped to a logical consumer.
    CONSTRAINT pk_kafka_consumer_inbox
        PRIMARY KEY (consumer_name, event_id),

    -- Ensure referenced financial transactions exist
    -- within the appropriate business.
    CONSTRAINT fk_kafka_consumer_inbox_transaction
        FOREIGN KEY (business_id, transaction_id)
        REFERENCES transactions (business_id, id)
        ON DELETE RESTRICT,

    -- Basic field validation.
    CONSTRAINT chk_kafka_consumer_name
        CHECK (LENGTH(TRIM(consumer_name)) > 0),

    CONSTRAINT chk_kafka_consumer_event_type
        CHECK (LENGTH(TRIM(event_type)) > 0),

    CONSTRAINT chk_kafka_consumer_topic
        CHECK (LENGTH(TRIM(kafka_topic)) > 0),

    CONSTRAINT chk_kafka_consumer_event_version
        CHECK (event_version >= 1),

    CONSTRAINT chk_kafka_consumer_partition
        CHECK (kafka_partition >= 0),

    CONSTRAINT chk_kafka_consumer_offset
        CHECK (kafka_offset >= 0),

    CONSTRAINT chk_kafka_consumer_payload
        CHECK (jsonb_typeof(payload) = 'object')
);

-- ============================================================
-- BUSINESS-SCOPED PROCESSING HISTORY
-- ============================================================
--
-- Support efficient inspection of recently processed
-- events for an individual business.
--
-- The primary key already handles event-ID deduplication.

CREATE INDEX idx_kafka_consumer_inbox_business_processed
    ON kafka_consumer_inbox (
        business_id,
        processed_at DESC
    );
