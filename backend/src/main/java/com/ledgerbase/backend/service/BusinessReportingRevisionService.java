package com.ledgerbase.backend.service;

import com.ledgerbase.backend.mapper.BusinessReportingRevisionMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// ============================================================
// LEDGERBASE — BUSINESS REPORTING REVISION SERVICE
// ============================================================

/**
 * Coordinates durable financial-report revision tracking.
 *
 * Every successful accounting mutation that affects
 * financial reporting must advance the business revision.
 *
 * PostgreSQL remains authoritative.
 *
 * Redis cache keys will reference committed revisions
 * during a later development checkpoint.
 */
@Service
public class BusinessReportingRevisionService {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    private final BusinessReportingRevisionMapper revisionMapper;

    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    public BusinessReportingRevisionService(
        BusinessReportingRevisionMapper revisionMapper
    ) {

        this.revisionMapper = revisionMapper;
    }

    // ========================================================
    // ADVANCE REPORTING REVISION
    // ========================================================

    /**
     * Increment the reporting revision inside an
     * existing Spring-managed database transaction.
     *
     * Propagation.MANDATORY prevents this method from
     * independently committing a revision without
     * the corresponding accounting change.
     *
     * The method must be invoked through this Spring
     * service bean so the transactional interceptor runs.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void advanceRevision(Long businessId) {

        // ----------------------------------------------------
        // VALIDATE BUSINESS ID
        // ----------------------------------------------------

        if (businessId == null || businessId <= 0) {

            throw new IllegalArgumentException(
                "A valid business ID is required"
            );
        }

        // ----------------------------------------------------
        // UPDATE POSTGRESQL
        // ----------------------------------------------------

        int updatedRows =
            revisionMapper.incrementRevision(businessId);

        // ----------------------------------------------------
        // VERIFY EXACTLY ONE BUSINESS WAS UPDATED
        // ----------------------------------------------------

        if (updatedRows != 1) {

            throw new IllegalStateException(
                "Failed to advance reporting revision "
                    + "for business " + businessId
            );
        }
    }

    // ========================================================
    // READ REPORTING REVISION
    // ========================================================

    /**
     * Retrieve the committed reporting revision from
     * Accounting Core's PostgreSQL primary.
     *
     * The Reporting Service will later obtain the
     * authorized revision through Accounting Core.
     */
    @Transactional(readOnly = true)
    public long getCurrentRevision(Long businessId) {

        if (businessId == null || businessId <= 0) {

            throw new IllegalArgumentException(
                "A valid business ID is required"
            );
        }

        Long revision =
            revisionMapper.findRevision(businessId);

        if (revision == null) {

            throw new IllegalStateException(
                "Reporting revision not found for business "
                    + businessId
            );
        }

        return revision;
    }
}
