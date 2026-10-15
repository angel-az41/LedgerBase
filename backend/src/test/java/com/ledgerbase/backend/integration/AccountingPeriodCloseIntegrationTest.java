package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.exception.AccountingPeriodConflictException;
import com.ledgerbase.backend.mapper.AuditEventMapper;
import com.ledgerbase.backend.model.AccountingPeriod;
import com.ledgerbase.backend.model.AuditEvent;
import com.ledgerbase.backend.model.FiscalYear;
import com.ledgerbase.backend.service.AccountingPeriodService;
import com.ledgerbase.backend.service.FiscalYearService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — ACCOUNTING PERIOD CLOSING INTEGRATION TESTS
// ============================================================

/**
 * Verify accounting-period closing against real PostgreSQL.
 *
 * These tests exercise:
 *
 * AccountingPeriodService
 *         |
 * AccountingPeriodMapper / AuditEventMapper
 *         |
 * PostgreSQL
 *
 * Each test creates its own user, business, fiscal year,
 * and 12 accounting periods.
 *
 * @Transactional rolls back test records afterward.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AccountingPeriodCloseIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FiscalYearService fiscalYearService;

    @Autowired
    private AccountingPeriodService accountingPeriodService;

    @Autowired
    private AuditEventMapper auditEventMapper;


    // ========================================================
    // TEST FIXTURE IDENTIFIERS
    // ========================================================

    private Long testUserId;
    private Long testBusinessId;
    private Long octoberPeriodId;


    // ========================================================
    // SETUP — CREATE BUSINESS AND FY2026
    // ========================================================

    @BeforeEach
    void setUp() {

        // Create the user who will close the period.
        testUserId = jdbcTemplate.queryForObject(
            """
            INSERT INTO users (
                email,
                password_hash,
                full_name
            )
            VALUES (?, ?, ?)
            RETURNING id
            """,
            Long.class,
            "period-close-" + UUID.randomUUID()
                + "@ledgerbase.local",
            "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
            "Accounting Period Close Test"
        );

        // Create an independent test business.
        testBusinessId = jdbcTemplate.queryForObject(
            """
            INSERT INTO businesses (
                owner_id,
                name,
                currency
            )
            VALUES (?, ?, ?)
            RETURNING id
            """,
            Long.class,
            testUserId,
            "Accounting Period Close Test Business",
            "USD"
        );

        // Create FY2026 and its 12 OPEN monthly periods.
        FiscalYear fiscalYear =
            fiscalYearService.createFiscalYear(
                testBusinessId,
                2026
            );

        // Retrieve October 2026 (Period 10).
        List<AccountingPeriod> periods =
            accountingPeriodService.getPeriodsForFiscalYear(
                testBusinessId,
                fiscalYear.getId()
            );

        assertEquals(12, periods.size());

        AccountingPeriod october = periods.get(9);

        assertEquals(10, october.getPeriodNumber());
        assertEquals(
            LocalDate.of(2026, 10, 1),
            october.getStartDate()
        );
        assertEquals(
            AccountingPeriod.Status.OPEN,
            october.getStatus()
        );

        octoberPeriodId = october.getId();
    }


    // ========================================================
    // TEST 1 — CLOSE AN OPEN ACCOUNTING PERIOD
    // ========================================================

    @Test
    void closesOpenAccountingPeriod() {

        AccountingPeriod closed =
            accountingPeriodService.closeAccountingPeriod(
                testBusinessId,
                octoberPeriodId,
                testUserId
            );

        // Verify the returned status.
        assertNotNull(closed);
        assertEquals(
            AccountingPeriod.Status.CLOSED,
            closed.getStatus()
        );

        // PostgreSQL records when the period was closed.
        assertNotNull(closed.getClosedAt());

        // Verify the change was persisted.
        AccountingPeriod saved =
            accountingPeriodService.getAccountingPeriod(
                testBusinessId,
                octoberPeriodId
            );

        assertEquals(
            AccountingPeriod.Status.CLOSED,
            saved.getStatus()
        );
        assertNotNull(saved.getClosedAt());
    }


    // ========================================================
    // TEST 2 — RECORD THE CLOSING AUDIT EVENT
    // ========================================================

    @Test
    void recordsAccountingPeriodClosingAuditEvent() {

        accountingPeriodService.closeAccountingPeriod(
            testBusinessId,
            octoberPeriodId,
            testUserId
        );

        // Retrieve the period's audit history.
        List<AuditEvent> events =
            auditEventMapper.findByEntity(
                testBusinessId,
                "ACCOUNTING_PERIOD",
                octoberPeriodId
            );

        // Exactly one successful closing event.
        assertEquals(1, events.size());

        AuditEvent event = events.get(0);

        assertEquals(
            "ACCOUNTING_PERIOD_CLOSED",
            event.getAction()
        );

        assertEquals(
            "ACCOUNTING_PERIOD",
            event.getEntityType()
        );

        assertEquals(
            testBusinessId,
            event.getBusinessId()
        );

        assertEquals(
            testUserId,
            event.getActorId()
        );

        assertEquals(
            octoberPeriodId,
            event.getEntityId()
        );

        // Verify the JSONB status transition details.
        assertNotNull(event.getDetails());
        assertTrue(event.getDetails().contains("OPEN"));
        assertTrue(event.getDetails().contains("CLOSED"));
    }


    // ========================================================
    // TEST 3 — REJECT CLOSING AN ALREADY CLOSED PERIOD
    // ========================================================

    @Test
    void rejectsClosingAlreadyClosedPeriod() {

        // First closing succeeds.
        accountingPeriodService.closeAccountingPeriod(
            testBusinessId,
            octoberPeriodId,
            testUserId
        );

        // Second closing must fail.
        AccountingPeriodConflictException exception =
            assertThrows(
                AccountingPeriodConflictException.class,
                () -> accountingPeriodService
                    .closeAccountingPeriod(
                        testBusinessId,
                        octoberPeriodId,
                        testUserId
                    )
            );

        assertEquals(
            "Accounting period is already closed: "
                + octoberPeriodId,
            exception.getMessage()
        );

        // Rejection must not create another audit event.
        List<AuditEvent> events =
            auditEventMapper.findByEntity(
                testBusinessId,
                "ACCOUNTING_PERIOD",
                octoberPeriodId
            );

        assertEquals(1, events.size());
    }


    // ========================================================
    // TEST 4 — ENFORCE BUSINESS DATA ISOLATION
    // ========================================================

    @Test
    void cannotCloseAnotherBusinessAccountingPeriod() {

        // Create another business owned by our test user.
        Long otherBusinessId = jdbcTemplate.queryForObject(
            """
            INSERT INTO businesses (
                owner_id,
                name,
                currency
            )
            VALUES (?, ?, ?)
            RETURNING id
            """,
            Long.class,
            testUserId,
            "Other Accounting Period Test Business",
            "USD"
        );

        // Try to close Business A's period using Business B.
        AccountingPeriod result =
            accountingPeriodService.closeAccountingPeriod(
                otherBusinessId,
                octoberPeriodId,
                testUserId
            );

        // Period is not visible through another business.
        assertNull(result);

        // Original business's period must remain OPEN.
        AccountingPeriod original =
            accountingPeriodService.getAccountingPeriod(
                testBusinessId,
                octoberPeriodId
            );

        assertEquals(
            AccountingPeriod.Status.OPEN,
            original.getStatus()
        );

        // No closing audit event should exist.
        List<AuditEvent> events =
            auditEventMapper.findByEntity(
                testBusinessId,
                "ACCOUNTING_PERIOD",
                octoberPeriodId
            );

        assertTrue(events.isEmpty());
    }


    // ============================================================
    // TEST 5 — REOPEN A CLOSED ACCOUNTING PERIOD
    // ============================================================

    /**
     * Verify that reopening reverses a successful close.
     *
     * Expected:
     *
     * - Status returns to OPEN.
     * - closed_at becomes NULL.
     * - An ACCOUNTING_PERIOD_REOPENED audit event is created.
     */
    @Test
    void reopensClosedAccountingPeriod() {

        // ========================================================
        // ARRANGE — CLOSE OCTOBER
        // ========================================================

        AccountingPeriod closed =
            accountingPeriodService.closeAccountingPeriod(
                testBusinessId,
                octoberPeriodId,
                testUserId
            );

        assertEquals(
            AccountingPeriod.Status.CLOSED,
            closed.getStatus()
        );

        assertNotNull(closed.getClosedAt());


        // ========================================================
        // ACT — REOPEN OCTOBER
        // ========================================================

        AccountingPeriod reopened =
            accountingPeriodService.reopenAccountingPeriod(
                testBusinessId,
                octoberPeriodId,
                testUserId
            );


        // ========================================================
        // ASSERT — PERIOD IS OPEN AGAIN
        // ========================================================

        assertNotNull(reopened);

        assertEquals(
            AccountingPeriod.Status.OPEN,
            reopened.getStatus()
        );

        assertNull(reopened.getClosedAt());


        // ========================================================
        // ASSERT — DATABASE STATE MATCHES
        // ========================================================

        AccountingPeriod saved =
            accountingPeriodService.getAccountingPeriod(
                testBusinessId,
                octoberPeriodId
            );

        assertEquals(
            AccountingPeriod.Status.OPEN,
            saved.getStatus()
        );

        assertNull(saved.getClosedAt());


        // ========================================================
        // ASSERT — AUDIT HISTORY
        // ========================================================

        List<AuditEvent> events =
            auditEventMapper.findByEntity(
                testBusinessId,
                "ACCOUNTING_PERIOD",
                octoberPeriodId
            );

        assertEquals(2, events.size());

        assertEquals(
            "ACCOUNTING_PERIOD_CLOSED",
            events.get(0).getAction()
        );

        AuditEvent reopeningEvent = events.get(1);

        assertEquals(
            "ACCOUNTING_PERIOD_REOPENED",
            reopeningEvent.getAction()
        );

        assertEquals(
            testUserId,
            reopeningEvent.getActorId()
        );

        assertTrue(
            reopeningEvent.getDetails()
                .contains("\"CLOSED\"")
        );

        assertTrue(
            reopeningEvent.getDetails()
                .contains("\"OPEN\"")
        );
    }


    // ============================================================
    // TEST 6 — REJECT REOPENING AN ALREADY OPEN PERIOD
    // ============================================================

    /**
     * An OPEN period cannot be reopened again.
     *
     * The attempted operation must fail without:
     *
     * - Changing the accounting period.
     * - Creating an audit event.
     */
    @Test
    void rejectsReopeningAlreadyOpenPeriod() {

        // October starts in OPEN status.
        AccountingPeriod original =
            accountingPeriodService.getAccountingPeriod(
                testBusinessId,
                octoberPeriodId
            );

        assertEquals(
            AccountingPeriod.Status.OPEN,
            original.getStatus()
        );


        // ========================================================
        // ACT — ATTEMPT INVALID REOPENING
        // ========================================================

        AccountingPeriodConflictException exception =
            assertThrows(
                AccountingPeriodConflictException.class,
                () -> accountingPeriodService
                    .reopenAccountingPeriod(
                        testBusinessId,
                        octoberPeriodId,
                        testUserId
                    )
            );


        // ========================================================
        // ASSERT — CORRECT CONFLICT
        // ========================================================

        assertEquals(
            "Accounting period is already open: "
                + octoberPeriodId,
            exception.getMessage()
        );


        // ========================================================
        // ASSERT — NO AUDIT EVENT CREATED
        // ========================================================

        List<AuditEvent> events =
            auditEventMapper.findByEntity(
                testBusinessId,
                "ACCOUNTING_PERIOD",
                octoberPeriodId
            );

        assertTrue(events.isEmpty());
    }


    // ============================================================
    // TEST 7 — COMPLETE ACCOUNTING PERIOD LIFECYCLE
    // ============================================================

    /**
     * Verify a complete accounting-period lifecycle:
     *
     * OPEN -> CLOSED -> OPEN -> CLOSED
     *
     * Every successful transition must generate
     * exactly one corresponding audit event.
     *
     * This models an accountant:
     *
     * 1. Closing the October books.
     * 2. Reopening October for an adjustment.
     * 3. Closing October again after the adjustment.
     */
    @Test
    void supportsCompleteAccountingPeriodLifecycle() {

        // ========================================================
        // FIRST TRANSITION — OPEN TO CLOSED
        // ========================================================

        AccountingPeriod firstClose =
            accountingPeriodService.closeAccountingPeriod(
                testBusinessId,
                octoberPeriodId,
                testUserId
            );

        assertEquals(
            AccountingPeriod.Status.CLOSED,
            firstClose.getStatus()
        );

        assertNotNull(firstClose.getClosedAt());


        // ========================================================
        // SECOND TRANSITION — CLOSED TO OPEN
        // ========================================================

        AccountingPeriod reopen =
            accountingPeriodService.reopenAccountingPeriod(
                testBusinessId,
                octoberPeriodId,
                testUserId
            );

        assertEquals(
            AccountingPeriod.Status.OPEN,
            reopen.getStatus()
        );

        assertNull(reopen.getClosedAt());


        // ========================================================
        // THIRD TRANSITION — OPEN TO CLOSED AGAIN
        // ========================================================

        AccountingPeriod secondClose =
            accountingPeriodService.closeAccountingPeriod(
                testBusinessId,
                octoberPeriodId,
                testUserId
            );

        assertEquals(
            AccountingPeriod.Status.CLOSED,
            secondClose.getStatus()
        );

        assertNotNull(secondClose.getClosedAt());


        // ========================================================
        // VERIFY THREE AUDIT EVENTS
        // ========================================================

        List<AuditEvent> events =
            auditEventMapper.findByEntity(
                testBusinessId,
                "ACCOUNTING_PERIOD",
                octoberPeriodId
            );

        assertEquals(3, events.size());

        assertEquals(
            "ACCOUNTING_PERIOD_CLOSED",
            events.get(0).getAction()
        );

        assertEquals(
            "ACCOUNTING_PERIOD_REOPENED",
            events.get(1).getAction()
        );

        assertEquals(
            "ACCOUNTING_PERIOD_CLOSED",
            events.get(2).getAction()
        );


        // ========================================================
        // VERIFY ALL EVENTS IDENTIFY THE SAME ACTOR
        // ========================================================

        for (AuditEvent event : events) {

            assertEquals(
                testBusinessId,
                event.getBusinessId()
            );

            assertEquals(
                testUserId,
                event.getActorId()
            );

            assertEquals(
                octoberPeriodId,
                event.getEntityId()
            );

            assertNotNull(event.getCreatedAt());
        }
    }

    // ============================================================
    // TEST 8 — PREVENT CROSS-BUSINESS REOPENING
    // ============================================================

    /**
     * A business cannot reopen another business's period.
     *
     * We first close Business A's period, then attempt
     * to reopen it using Business B's ID.
     *
     * The original period must remain CLOSED.
     */
    @Test
    void cannotReopenAnotherBusinessAccountingPeriod() {

        // ========================================================
        // ARRANGE — CLOSE BUSINESS A'S PERIOD
        // ========================================================

        accountingPeriodService.closeAccountingPeriod(
            testBusinessId,
            octoberPeriodId,
            testUserId
        );


        // ========================================================
        // ARRANGE — CREATE BUSINESS B
        // ========================================================

        Long otherBusinessId = jdbcTemplate.queryForObject(
            """
            INSERT INTO businesses (
                owner_id,
                name,
                currency
            )
            VALUES (?, ?, ?)
            RETURNING id
            """,
            Long.class,
            testUserId,
            "Other Reopening Test Business",
            "USD"
        );


        // ========================================================
        // ACT — ATTEMPT CROSS-BUSINESS REOPENING
        // ========================================================

        AccountingPeriod result =
            accountingPeriodService.reopenAccountingPeriod(
                otherBusinessId,
                octoberPeriodId,
                testUserId
            );

        assertNull(result);


        // ========================================================
        // ASSERT — ORIGINAL PERIOD REMAINS CLOSED
        // ========================================================

        AccountingPeriod original =
            accountingPeriodService.getAccountingPeriod(
                testBusinessId,
                octoberPeriodId
            );

        assertEquals(
            AccountingPeriod.Status.CLOSED,
            original.getStatus()
        );

        assertNotNull(original.getClosedAt());


        // ========================================================
        // ASSERT — ONLY ORIGINAL CLOSING WAS AUDITED
        // ========================================================

        List<AuditEvent> events =
            auditEventMapper.findByEntity(
                testBusinessId,
                "ACCOUNTING_PERIOD",
                octoberPeriodId
            );

        assertEquals(1, events.size());

        assertEquals(
            "ACCOUNTING_PERIOD_CLOSED",
            events.get(0).getAction()
        );
    }
}
