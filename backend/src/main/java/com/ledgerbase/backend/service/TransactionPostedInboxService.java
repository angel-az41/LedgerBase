package com.ledgerbase.backend.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Idempotent processing of TransactionPosted Kafka events.
 *
 * The inbox records which events have been successfully
 * processed by this logical consumer.
 *
 * PostgreSQL enforces uniqueness through:
 *
 * PRIMARY KEY (consumer_name, event_id)
 *
 * The processing receipt is inserted transactionally.
 * Any future downstream database changes should execute
 * in this same transaction.
 *
 * This service never posts financial transactions or
 * modifies authoritative ledger entries.
 */
@Service
public class TransactionPostedInboxService {

    private static final String CONSUMER_NAME =
        "transaction-posted-inbox-v1";

    private final JdbcTemplate jdbcTemplate;

    private final JsonMapper jsonMapper;

    public TransactionPostedInboxService(
            JdbcTemplate jdbcTemplate,
            JsonMapper jsonMapper) {

        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Process one Kafka record through the durable inbox.
     *
     * @return true if this is a newly processed event;
     *         false if the same event was processed before.
     *
     * Any failure propagates to the Kafka listener.
     * Its offset must not be acknowledged on failure.
     */
    @Transactional
    public boolean process(
            ConsumerRecord<String, String> record) {

        // ====================================================
        // PARSE THE ORIGINAL FINANCIAL EVENT
        // ====================================================

        JsonNode payload;

        try {
            payload = jsonMapper.readTree(record.value());
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                "Invalid TransactionPosted JSON payload",
                exception
            );
        }

        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException(
                "TransactionPosted payload must be a JSON object"
            );
        }

        UUID eventId = UUID.fromString(
            requiredText(payload, "eventId")
        );

        String eventType =
            requiredText(payload, "eventType");

        long eventVersion =
            requiredPositiveLong(payload, "eventVersion");

        long businessId =
            requiredPositiveLong(payload, "businessId");

        long transactionId =
            requiredPositiveLong(payload, "transactionId");

        // ====================================================
        // VALIDATE THE EVENT CONTRACT
        // ====================================================

        if (!"TransactionPosted".equals(eventType)) {
            throw new IllegalArgumentException(
                "Unsupported financial event type: " + eventType
            );
        }

        if (eventVersion != 1L) {
            throw new IllegalArgumentException(
                "Unsupported TransactionPosted event version: "
                    + eventVersion
            );
        }

        String expectedKey =
            "business:" + businessId
                + ":transaction:" + transactionId;

        if (!expectedKey.equals(record.key())) {
            throw new IllegalArgumentException(
                "Kafka message key does not match "
                    + "the financial transaction identifiers"
            );
        }

        // The earlier development event may contain
        // postedAt=null. This is intentionally accepted
        // for backward compatibility with event version 1.

        // ====================================================
        // ATOMICALLY INSERT THE PROCESSING RECEIPT
        // ====================================================

        /*
         * INSERT ... ON CONFLICT DO NOTHING is the
         * PostgreSQL idempotency boundary.
         *
         * The SELECT verifies that the financial
         * transaction exists and is already POSTED.
         *
         * If an event was processed previously,
         * the unique key prevents a second receipt.
         */
        int insertedRows = jdbcTemplate.update(
            """
            INSERT INTO kafka_consumer_inbox (
                consumer_name,
                event_id,
                business_id,
                transaction_id,
                event_type,
                event_version,
                kafka_topic,
                kafka_partition,
                kafka_offset,
                payload
            )
            SELECT
                ?,
                CAST(? AS UUID),
                t.business_id,
                t.id,
                ?,
                ?,
                ?,
                ?,
                ?,
                CAST(? AS JSONB)
            FROM transactions t
            WHERE t.business_id = ?
              AND t.id = ?
              AND t.status = 'POSTED'
            ON CONFLICT (consumer_name, event_id)
            DO NOTHING
            """,
            CONSUMER_NAME,
            eventId.toString(),
            eventType,
            (int) eventVersion,
            record.topic(),
            record.partition(),
            record.offset(),
            record.value(),
            businessId,
            transactionId
        );

        if (insertedRows == 1) {

            /*
             * A new event was durably processed.
             *
             * Future business projections or notifications
             * that require PostgreSQL changes belong here,
             * inside this same database transaction.
             */
            return true;
        }

        // ====================================================
        // DISTINGUISH DUPLICATES FROM INVALID EVENTS
        // ====================================================

        /*
         * Zero inserted rows can mean:
         *
         * - The event was already processed.
         * - The referenced POSTED transaction is missing.
         *
         * Only an exact match to an existing receipt
         * counts as an acceptable duplicate.
         */
        Integer duplicateCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM kafka_consumer_inbox
            WHERE consumer_name = ?
              AND event_id = CAST(? AS UUID)
              AND business_id = ?
              AND transaction_id = ?
              AND event_type = ?
              AND event_version = ?
              AND kafka_topic = ?
              AND payload = CAST(? AS JSONB)
            """,
            Integer.class,
            CONSUMER_NAME,
            eventId.toString(),
            businessId,
            transactionId,
            eventType,
            (int) eventVersion,
            record.topic(),
            record.value()
        );

        if (duplicateCount != null && duplicateCount == 1) {
            return false;
        }

        throw new IllegalStateException(
            "Cannot process TransactionPosted eventId="
                + eventId
                + ": missing POSTED transaction or "
                + "conflicting event identity"
        );
    }

    /**
     * Read a required nonblank JSON string.
     */
    private String requiredText(
            JsonNode payload,
            String fieldName) {

        JsonNode value = payload.get(fieldName);

        if (value == null
                || !value.isTextual()
                || value.asText().isBlank()) {

            throw new IllegalArgumentException(
                "Missing or invalid event field: " + fieldName
            );
        }

        return value.asText();
    }

    /**
     * Read a strictly positive integral JSON number.
     */
    private long requiredPositiveLong(
            JsonNode payload,
            String fieldName) {

        JsonNode value = payload.get(fieldName);

        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() <= 0) {

            throw new IllegalArgumentException(
                "Missing or invalid numeric event field: "
                    + fieldName
            );
        }

        return value.longValue();
    }
}
