package com.ledgerbase.backend.service;

import com.ledgerbase.backend.exception.BusinessNotFoundException;

import com.ledgerbase.backend.exception.AccountingPeriodConflictException;
import com.ledgerbase.backend.mapper.AccountingPeriodMapper;
import com.ledgerbase.backend.mapper.AuditEventMapper;
import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.mapper.FiscalYearMapper;
import com.ledgerbase.backend.model.AuditEvent;

import com.ledgerbase.backend.model.AccountingPeriod;
import com.ledgerbase.backend.model.FiscalYear;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// ============================================================
// LEDGERBASE — ACCOUNTING PERIOD SERVICE
// ============================================================

/**
 * Business logic for accounting-period management.
 *
 * Each fiscal year contains 12 monthly accounting periods.
 *
 * Example:
 *
 * Business: LedgerBase Demo Business
 * Fiscal Year: FY2026
 *
 * Period 1  = January 2026
 * Period 2  = February 2026
 * ...
 * Period 10 = October 2026
 * ...
 * Period 12 = December 2026
 *
 * For a business using an April-starting fiscal year:
 *
 * Period 1  = April
 * Period 2  = May
 * ...
 * Period 12 = March of the following calendar year
 *
 * This initial service provides READ-ONLY operations.
 *
 * Closing and reopening will be added after journal
 * posting checks have been implemented and tested.
 *
 * IMPORTANT:
 *
 * All operations must remain scoped to one business.
 */
@Service
public class AccountingPeriodService {

    // ========================================================
    // DATABASE MAPPERS
    // ========================================================

    /**
     * Checks whether a business exists.
     */
    private final BusinessMapper businessMapper;

    /**
     * Retrieves fiscal-year information.
     */
    private final FiscalYearMapper fiscalYearMapper;

    /**
     * Retrieves accounting periods.
     */
    private final AccountingPeriodMapper accountingPeriodMapper;

    /**
     * Records changes to accounting-period status.
     */
    private final AuditEventMapper auditEventMapper;

    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    /**
     * Spring injects all three MyBatis mappers
     * through constructor injection.
     */
    public AccountingPeriodService(
            BusinessMapper businessMapper,
            FiscalYearMapper fiscalYearMapper,
            AccountingPeriodMapper accountingPeriodMapper,
            AuditEventMapper auditEventMapper) {

        this.businessMapper = businessMapper;
        this.fiscalYearMapper = fiscalYearMapper;
        this.accountingPeriodMapper = accountingPeriodMapper;
        this.auditEventMapper = auditEventMapper;
    }


    // ========================================================
    // GET PERIODS FOR A FISCAL YEAR
    // ========================================================

    /**
     * Retrieve all accounting periods belonging
     * to a specific fiscal year.
     *
     * Example:
     *
     * getPeriodsForFiscalYear(1L, 5L)
     *
     * Returns Periods 1 through 12 for fiscal year 5,
     * provided that fiscal year belongs to Business 1.
     *
     * The mapper orders periods by period_number ASC.
     *
     * Returns:
     *
     * - A list of periods when the fiscal year exists.
     * - null when the fiscal year does not exist
     *   or belongs to another business.
     *
     * Returning null for a missing fiscal year lets
     * our future REST controller return HTTP 404.
     *
     * An existing fiscal year with no periods would
     * produce an empty list, which is distinct
     * from a missing fiscal year.
     */
    @Transactional(readOnly = true)
    public List<AccountingPeriod> getPeriodsForFiscalYear(
            Long businessId,
            Long fiscalYearId) {

        // ====================================================
        // VALIDATE BUSINESS
        // ====================================================

        validateBusinessExists(businessId);


        // ====================================================
        // VALIDATE FISCAL YEAR ID
        // ====================================================

        if (fiscalYearId == null || fiscalYearId <= 0) {

            throw new IllegalArgumentException(
                "Fiscal year ID must be positive"
            );
        }


        // ====================================================
        // VERIFY FISCAL YEAR OWNERSHIP
        // ====================================================

        /**
         * This query includes both business_id and id.
         *
         * It prevents one business from retrieving
         * another business's accounting periods.
         */
        FiscalYear fiscalYear = fiscalYearMapper.findById(
            businessId,
            fiscalYearId
        );

        if (fiscalYear == null) {

            return null;
        }


        // ====================================================
        // RETRIEVE ACCOUNTING PERIODS
        // ====================================================

        /**
         * MyBatis maps each database row into an
         * AccountingPeriod Java object.
         *
         * Results include:
         *
         * - Period number
         * - Start and end dates
         * - OPEN or CLOSED status
         * - Closing timestamp, when applicable
         */
        return accountingPeriodMapper.findByFiscalYearId(
            businessId,
            fiscalYearId
        );
    }


    // ========================================================
    // GET ONE ACCOUNTING PERIOD
    // ========================================================

    /**
     * Retrieve an individual accounting period
     * belonging to a business.
     *
     * Returns null when the period does not exist
     * or belongs to another business.
     *
     * A REST controller can translate null into
     * HTTP 404 Not Found.
     */
    @Transactional(readOnly = true)
    public AccountingPeriod getAccountingPeriod(
            Long businessId,
            Long periodId) {

        // ====================================================
        // VALIDATE BUSINESS
        // ====================================================

        validateBusinessExists(businessId);


        // ====================================================
        // VALIDATE PERIOD ID
        // ====================================================

        if (periodId == null || periodId <= 0) {

            throw new IllegalArgumentException(
                "Accounting period ID must be positive"
            );
        }


        // ====================================================
        // RETRIEVE BUSINESS-SCOPED PERIOD
        // ====================================================

        return accountingPeriodMapper.findById(
            businessId,
            periodId
        );
    }


    // ============================================================
    // CLOSE ACCOUNTING PERIOD
    // ============================================================

    /**
     * Close an OPEN accounting period.
     *
     * Parameters:
     *
     * businessId:
     *     Business that owns the accounting period.
     *
     * periodId:
     *     Accounting period to close.
     *
     * actorId:
     *     ID of the user performing the operation.
     *
     * Business rules:
     *
     * 1. The business and period must be valid.
     * 2. The period must currently be OPEN.
     * 3. Closing changes its status to CLOSED.
     * 4. An audit event records the successful change.
     *
     * Returns:
     *
     * - Updated CLOSED accounting period on success.
     * - null if the period does not belong to the business
     *   or does not exist.
     *
     * Throws:
     *
     * - AccountingPeriodConflictException when the period
     *   is already CLOSED or a concurrent update prevents
     *   the requested transition.
     *
     * IMPORTANT:
     *
     * @Transactional ensures the status update and audit
     * event are committed or rolled back together.
     *
     * The mapper's conditional UPDATE also obtains the
     * accounting-period row lock, coordinating with
     * journal posting and the V6 database trigger.
     */
    @Transactional
    public AccountingPeriod closeAccountingPeriod(
            Long businessId,
            Long periodId,
            Long actorId) {

        // ========================================================
        // VALIDATE ACTOR ID
        // ========================================================

        /**
         * Every closing action must identify the user
         * responsible for the accounting change.
         *
         * Actual authorization will be enforced by the
         * authenticated API layer when that is implemented.
         */
        if (actorId == null || actorId <= 0) {

            throw new IllegalArgumentException(
                "Actor ID must be positive"
            );
        }


        // ========================================================
        // RETRIEVE BUSINESS-SCOPED ACCOUNTING PERIOD
        // ========================================================

        /**
         * Reuse the existing method to validate:
         *
         * - Business existence.
         * - Positive period ID.
         * - Business ownership of the period.
         *
         * This call participates in the outer transaction.
         */
        AccountingPeriod accountingPeriod =
            getAccountingPeriod(
                businessId,
                periodId
            );


        // ========================================================
        // HANDLE MISSING ACCOUNTING PERIOD
        // ========================================================

        /**
         * Returning null is consistent with the existing
         * getAccountingPeriod() behavior.
         *
         * Our future controller will translate this
         * result into HTTP 404 Not Found.
         */
        if (accountingPeriod == null) {

            return null;
        }


        // ========================================================
        // REJECT ALREADY CLOSED PERIOD
        // ========================================================

        /**
         * A CLOSED period cannot be closed again.
         *
         * The user must explicitly reopen it before
         * a subsequent closing operation.
         */
        if (accountingPeriod.getStatus()
                != AccountingPeriod.Status.OPEN) {

            throw new AccountingPeriodConflictException(
                "Accounting period is already closed: "
                + periodId
            );
        }


        // ========================================================
        // UPDATE ACCOUNTING PERIOD STATUS
        // ========================================================

        /**
         * The mapper executes:
         *
         * UPDATE accounting_periods
         * SET status = 'CLOSED',
         *     closed_at = NOW()
         * WHERE business_id = ?
         *   AND id = ?
         *   AND status = 'OPEN'
         *
         * PostgreSQL locks the updated row.
         *
         * If posting holds the period lock, closing waits
         * until that posting operation finishes.
         *
         * The OPEN condition also prevents two concurrent
         * closing requests from both succeeding.
         */
        int updatedRows =
            accountingPeriodMapper.closePeriod(
                businessId,
                periodId
            );


        // ========================================================
        // HANDLE CONCURRENT STATUS CHANGES
        // ========================================================

        /**
         * If another request closed the period before us,
         * its status is no longer OPEN.
         *
         * PostgreSQL then updates zero rows.
         *
         * Return a conflict rather than pretending the
         * closing operation succeeded.
         */
        if (updatedRows != 1) {

            throw new AccountingPeriodConflictException(
                "Accounting period could not be closed: "
                + periodId
            );
        }


        // ========================================================
        // RECORD ACCOUNTING PERIOD AUDIT EVENT
        // ========================================================

        /**
         * Record:
         *
         * - The affected business.
         * - The acting user.
         * - The closing action.
         * - The accounting-period entity and ID.
         * - Previous and new statuses.
         *
         * The PostgreSQL audit_events table generates
         * the audit ID and creation timestamp.
         */
        AuditEvent auditEvent = new AuditEvent();

        auditEvent.setBusinessId(businessId);
        auditEvent.setActorId(actorId);

        auditEvent.setAction(
            "ACCOUNTING_PERIOD_CLOSED"
        );

        auditEvent.setEntityType(
            "ACCOUNTING_PERIOD"
        );

        auditEvent.setEntityId(periodId);

        auditEvent.setDetails(
            "{\"previousStatus\":\"OPEN\",\"newStatus\":\"CLOSED\"}"
        );


        // ========================================================
        // INSERT AUDIT EVENT
        // ========================================================

        /**
         * A successful close must have exactly one audit
         * event.
         *
         * If insertion fails, the surrounding transaction
         * rolls back the accounting-period status change.
         */
        int insertedAuditEvents =
            auditEventMapper.insert(auditEvent);

        if (insertedAuditEvents != 1) {

            throw new IllegalStateException(
                "Failed to record accounting period closing audit event"
            );
        }


        // ========================================================
        // RETURN UPDATED ACCOUNTING PERIOD
        // ========================================================

        /**
         * Retrieve the period again so the caller receives
         * its latest status and database-generated closed_at.
         *
         * Expected status: CLOSED.
         */
        return accountingPeriodMapper.findById(
            businessId,
            periodId
        );
    }


    // ============================================================
    // REOPEN ACCOUNTING PERIOD
    // ============================================================

    /**
     * Reopen a previously CLOSED accounting period.
     *
     * Parameters:
     *
     * businessId:
     *     Business that owns the accounting period.
     *
     * periodId:
     *     Accounting period to reopen.
     *
     * actorId:
     *     ID of the user performing the operation.
     *
     * Business rules:
     *
     * 1. The business and accounting period must exist.
     * 2. The period must currently be CLOSED.
     * 3. Reopening changes the status to OPEN.
     * 4. Reopening clears the closed_at timestamp.
     * 5. An audit event records the successful reopening.
     *
     * Returns:
     *
     * - Updated OPEN accounting period on success.
     * - null when the period does not exist within
     *   the specified business.
     *
     * Throws:
     *
     * - AccountingPeriodConflictException when the
     *   period is already OPEN or its status changes
     *   concurrently.
     *
     * IMPORTANT:
     *
     * @Transactional ensures that reopening the period
     * and recording its audit event are atomic.
     *
     * PostgreSQL locks the updated accounting-period row.
     * This coordinates with concurrent journal posting.
     */
    @Transactional
    public AccountingPeriod reopenAccountingPeriod(
            Long businessId,
            Long periodId,
            Long actorId) {

        // ========================================================
        // VALIDATE ACTOR ID
        // ========================================================

        /**
         * Every accounting-period status change must
         * identify the user who initiated it.
         *
         * The authenticated API layer will eventually
         * provide a trusted actor identity.
         */
        if (actorId == null || actorId <= 0) {

            throw new IllegalArgumentException(
                "Actor ID must be positive"
            );
        }


        // ========================================================
        // RETRIEVE BUSINESS-SCOPED ACCOUNTING PERIOD
        // ========================================================

        /**
         * Reuse our existing validation for:
         *
         * - Business existence.
         * - Positive accounting-period ID.
         * - Business ownership.
         */
        AccountingPeriod accountingPeriod =
            getAccountingPeriod(
                businessId,
                periodId
            );


        // ========================================================
        // HANDLE MISSING ACCOUNTING PERIOD
        // ========================================================

        /**
         * A period belonging to another business
         * is treated as not found.
         *
         * This prevents modifications across businesses.
         */
        if (accountingPeriod == null) {

            return null;
        }


        // ========================================================
        // REJECT ALREADY OPEN PERIOD
        // ========================================================

        /**
         * Only CLOSED periods can be reopened.
         *
         * Reopening an OPEN period again would be
         * an invalid status transition.
         */
        if (accountingPeriod.getStatus()
                != AccountingPeriod.Status.CLOSED) {

            throw new AccountingPeriodConflictException(
                "Accounting period is already open: "
                + periodId
            );
        }


        // ========================================================
        // UPDATE ACCOUNTING PERIOD STATUS
        // ========================================================

        /**
         * Our existing MyBatis mapper executes:
         *
         * UPDATE accounting_periods
         * SET status = 'OPEN',
         *     closed_at = NULL
         * WHERE business_id = ?
         *   AND id = ?
         *   AND status = 'CLOSED'
         *
         * The conditional status check protects against
         * conflicting concurrent reopening requests.
         *
         * PostgreSQL obtains a row lock for the update.
         */
        int updatedRows =
            accountingPeriodMapper.reopenPeriod(
                businessId,
                periodId
            );


        // ========================================================
        // HANDLE CONCURRENT STATUS CHANGES
        // ========================================================

        /**
         * Exactly one row must have been reopened.
         *
         * If another request changed the period status
         * before this update, return a conflict.
         */
        if (updatedRows != 1) {

            throw new AccountingPeriodConflictException(
                "Accounting period could not be reopened: "
                + periodId
            );
        }


        // ========================================================
        // RECORD REOPENING AUDIT EVENT
        // ========================================================

        /**
         * Reopening must leave an audit trail.
         *
         * This allows us to identify:
         *
         * - Which accounting period was reopened.
         * - Which business it belongs to.
         * - Which user performed the operation.
         * - What status transition occurred.
         *
         * The database generates the audit timestamp.
         */
        AuditEvent auditEvent = new AuditEvent();

        auditEvent.setBusinessId(businessId);
        auditEvent.setActorId(actorId);

        auditEvent.setAction(
            "ACCOUNTING_PERIOD_REOPENED"
        );

        auditEvent.setEntityType(
            "ACCOUNTING_PERIOD"
        );

        auditEvent.setEntityId(periodId);

        auditEvent.setDetails(
            "{\"previousStatus\":\"CLOSED\",\"newStatus\":\"OPEN\"}"
        );


        // ========================================================
        // INSERT AUDIT EVENT
        // ========================================================

        /**
         * The audit record must be inserted in the
         * same transaction as the reopening update.
         *
         * If the insert fails, the reopening is rolled back.
         */
        int insertedAuditEvents =
            auditEventMapper.insert(auditEvent);

        if (insertedAuditEvents != 1) {

            throw new IllegalStateException(
                "Failed to record accounting period reopening audit event"
            );
        }


        // ========================================================
        // RETURN UPDATED ACCOUNTING PERIOD
        // ========================================================

        /**
         * Reload the period from PostgreSQL.
         *
         * Expected:
         *
         * status    = OPEN
         * closed_at = NULL
         */
        return accountingPeriodMapper.findById(
            businessId,
            periodId
        );
    }


    // ========================================================
    // VALIDATE BUSINESS EXISTENCE
    // ========================================================

    /**
     * Reject requests for nonexistent businesses.
     *
     * Our existing BusinessNotFoundException
     * is handled by the global API exception handler
     * and produces HTTP 404.
     */
    private void validateBusinessExists(Long businessId) {

        if (businessId == null ||
                !businessMapper.existsById(businessId)) {

            throw new BusinessNotFoundException(businessId);
        }
    }
}
