package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.AccountingPeriodActionRequest;
import com.ledgerbase.backend.model.AccountingPeriod;
import com.ledgerbase.backend.service.AccountingPeriodService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.server.ResponseStatusException;

import java.util.List;

// ============================================================
// LEDGERBASE — ACCOUNTING PERIOD REST CONTROLLER
// ============================================================

/**
 * REST API for accounting-period management.
 *
 * BASE URL:
 *
 * /api/v1/businesses/{businessId}
 *
 * RESPONSIBILITIES:
 *
 * - Retrieve accounting periods for a fiscal year.
 * - Retrieve one accounting period.
 * - Close an OPEN accounting period.
 * - Reopen a CLOSED accounting period.
 *
 * Each fiscal year normally contains 12 monthly
 * accounting periods.
 *
 * AUTHENTICATION:
 *
 * Spring Security validates incoming JWT access tokens.
 *
 * AUTHORIZATION:
 *
 * BusinessAuthorizationManager checks the user's
 * business membership and role before allowing
 * access to business-scoped endpoints.
 *
 * READ OPERATIONS:
 *
 * Authorized business members can retrieve
 * accounting-period information.
 *
 * WRITE OPERATIONS:
 *
 * Closing and reopening require business write
 * permission under the current RBAC configuration.
 *
 * ACTOR IDENTITY:
 *
 * The actual user performing a state change is
 * derived from the verified JWT subject.
 *
 * Client-supplied actorId values are never trusted.
 *
 * ACCOUNTING SAFEGUARDS:
 *
 * AccountingPeriodService remains responsible for:
 *
 * - Validating business and period ownership.
 * - Checking valid OPEN/CLOSED transitions.
 * - Coordinating PostgreSQL row-level locks.
 * - Preventing conflicts with journal posting.
 * - Updating status and closing timestamps.
 * - Creating transactional accounting audit events.
 *
 * The controller handles HTTP input and output,
 * while the service enforces accounting rules.
 */
@RestController
@RequestMapping("/api/v1/businesses/{businessId}")
public class AccountingPeriodController {

    // ========================================================
    // ACCOUNTING PERIOD SERVICE
    // ========================================================

    /**
     * Existing accounting-period business logic.
     *
     * We preserve the service so authentication
     * changes do not disturb transaction management
     * or the PostgreSQL accounting safeguards.
     */
    private final AccountingPeriodService accountingPeriodService;


    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    /**
     * Spring injects AccountingPeriodService.
     *
     * Constructor injection keeps dependencies
     * explicit and supports unit testing.
     */
    public AccountingPeriodController(
            AccountingPeriodService accountingPeriodService) {

        this.accountingPeriodService = accountingPeriodService;
    }


    // ========================================================
    // GET ALL PERIODS FOR A FISCAL YEAR
    // ========================================================

    /**
     * GET
     * /api/v1/businesses/{businessId}/fiscal-years/{fiscalYearId}/periods
     *
     * Retrieve the monthly accounting periods
     * belonging to a specific fiscal year.
     *
     * Results are ordered by period number.
     *
     * Example response:
     *
     * [
     *   {
     *     "id": 1,
     *     "businessId": 1,
     *     "fiscalYearId": 5,
     *     "periodNumber": 1,
     *     "startDate": "2026-01-01",
     *     "endDate": "2026-01-31",
     *     "status": "OPEN",
     *     "closedAt": null
     *   }
     * ]
     *
     * SECURITY:
     *
     * The existing Spring Security configuration
     * verifies the authenticated user's business
     * read permission before invoking this method.
     *
     * Returns:
     *
     * HTTP 200 — Fiscal year found; periods returned.
     * HTTP 401 — Authentication required.
     * HTTP 403 — Business access denied.
     * HTTP 404 — Fiscal year does not exist in business.
     */
    @GetMapping("/fiscal-years/{fiscalYearId}/periods")
    public ResponseEntity<List<AccountingPeriod>> getPeriodsForFiscalYear(

        @PathVariable("businessId")
        Long businessId,

        @PathVariable("fiscalYearId")
        Long fiscalYearId

    ) {

        // ----------------------------------------------------
        // LOAD BUSINESS-SCOPED ACCOUNTING PERIODS
        // ----------------------------------------------------

        List<AccountingPeriod> periods =
            accountingPeriodService.getPeriodsForFiscalYear(
                businessId,
                fiscalYearId
            );


        // ----------------------------------------------------
        // HANDLE MISSING FISCAL YEAR
        // ----------------------------------------------------

        /**
         * A null result indicates that no fiscal
         * year matches both supplied identifiers.
         *
         * An existing fiscal year with no periods
         * may instead return an empty list.
         */
        if (periods == null) {

            return ResponseEntity
                .notFound()
                .build();
        }


        // ----------------------------------------------------
        // RETURN ACCOUNTING PERIODS
        // ----------------------------------------------------

        return ResponseEntity.ok(periods);
    }


    // ========================================================
    // GET ONE ACCOUNTING PERIOD
    // ========================================================

    /**
     * GET
     * /api/v1/businesses/{businessId}/accounting-periods/{periodId}
     *
     * Retrieve one accounting period.
     *
     * The service and MyBatis mapper scope the
     * query to the requested business.
     *
     * SECURITY:
     *
     * Spring Security checks the authenticated
     * user's membership before invoking the method.
     *
     * Returns:
     *
     * HTTP 200 — Period found.
     * HTTP 401 — Authentication required.
     * HTTP 403 — Business access denied.
     * HTTP 404 — Period not found in business.
     */
    @GetMapping("/accounting-periods/{periodId}")
    public ResponseEntity<AccountingPeriod> getAccountingPeriod(

        @PathVariable("businessId")
        Long businessId,

        @PathVariable("periodId")
        Long periodId

    ) {

        // ----------------------------------------------------
        // RETRIEVE PERIOD
        // ----------------------------------------------------

        AccountingPeriod period =
            accountingPeriodService.getAccountingPeriod(
                businessId,
                periodId
            );


        // ----------------------------------------------------
        // HANDLE MISSING PERIOD
        // ----------------------------------------------------

        if (period == null) {

            return ResponseEntity
                .notFound()
                .build();
        }


        // ----------------------------------------------------
        // RETURN DATABASE RECORD
        // ----------------------------------------------------

        return ResponseEntity.ok(period);
    }


    // ========================================================
    // CLOSE ACCOUNTING PERIOD
    // ========================================================

    /**
     * POST
     * /api/v1/businesses/{businessId}/accounting-periods/{periodId}/close
     *
     * Change an accounting period from OPEN to CLOSED.
     *
     * Example new request body:
     *
     * {}
     *
     * A request body is also optional.
     *
     * BACKWARD COMPATIBILITY:
     *
     * Older clients may still send:
     *
     * {
     *     "actorId": 1
     * }
     *
     * The actorId in that body is ignored.
     *
     * SECURITY:
     *
     * Spring Security validates the JWT.
     *
     * BusinessAuthorizationManager checks the
     * user's business write permission.
     *
     * This controller determines the acting user
     * from the verified JWT subject.
     *
     * AccountingPeriodService then:
     *
     * - Verifies that the period belongs to businessId.
     * - Acquires the required PostgreSQL row lock.
     * - Verifies that the period is OPEN.
     * - Changes the period status to CLOSED.
     * - Records the closing timestamp.
     * - Records the authenticated actor in audit_events.
     *
     * A CLOSED accounting period cannot accept
     * new journal postings.
     *
     * Returns:
     *
     * HTTP 200 — Period successfully closed.
     * HTTP 401 — Authentication required.
     * HTTP 403 — Insufficient business permission.
     * HTTP 404 — Period not found.
     * HTTP 409 — Invalid transition or posting conflict.
     *
     * @param businessId owning business
     * @param periodId period to close
     * @param ignoredLegacyRequest optional legacy body
     * @param jwt verified authentication principal
     * @return updated CLOSED period
     */
    @PostMapping("/accounting-periods/{periodId}/close")
    public ResponseEntity<AccountingPeriod> closeAccountingPeriod(

        @PathVariable("businessId")
        Long businessId,

        @PathVariable("periodId")
        Long periodId,

        @RequestBody(required = false)
        AccountingPeriodActionRequest ignoredLegacyRequest,

        @AuthenticationPrincipal
        Jwt jwt

    ) {

        // ----------------------------------------------------
        // RESOLVE AUTHENTICATED ACTOR
        // ----------------------------------------------------

        /**
         * The acting user must come from the JWT.
         *
         * Never call ignoredLegacyRequest.actorId().
         *
         * This prevents a user from closing a period
         * while impersonating another user in the
         * financial audit history.
         */
        Long authenticatedActorId =
            getAuthenticatedUserId(jwt);


        // ----------------------------------------------------
        // DELEGATE TO ACCOUNTING PERIOD SERVICE
        // ----------------------------------------------------

        /**
         * The existing service receives the same
         * three arguments as before.
         *
         * The difference is that actorId is now
         * trusted authentication data, not browser input.
         *
         * Database locking, status transitions,
         * and audit logging remain unchanged.
         */
        AccountingPeriod closedPeriod =
            accountingPeriodService.closeAccountingPeriod(
                businessId,
                periodId,
                authenticatedActorId
            );


        // ----------------------------------------------------
        // HANDLE MISSING OR CROSS-BUSINESS PERIOD
        // ----------------------------------------------------

        /**
         * The service returns null when the period
         * cannot be found in the specified business.
         */
        if (closedPeriod == null) {

            return ResponseEntity
                .notFound()
                .build();
        }


        // ----------------------------------------------------
        // RETURN UPDATED PERIOD
        // ----------------------------------------------------

        return ResponseEntity.ok(closedPeriod);
    }


    // ========================================================
    // REOPEN ACCOUNTING PERIOD
    // ========================================================

    /**
     * POST
     * /api/v1/businesses/{businessId}/accounting-periods/{periodId}/reopen
     *
     * Change an accounting period from CLOSED to OPEN.
     *
     * The frontend may submit an empty JSON object:
     *
     * {}
     *
     * A request body is also optional.
     *
     * Legacy actorId values are ignored.
     *
     * SECURITY:
     *
     * Spring Security validates the user's JWT
     * and applies business write authorization.
     *
     * The verified JWT subject determines the
     * actor recorded in audit_events.
     *
     * AccountingPeriodService:
     *
     * - Checks business and period ownership.
     * - Locks the relevant database rows.
     * - Verifies that the period is CLOSED.
     * - Changes the period status to OPEN.
     * - Clears the closing timestamp.
     * - Records an ACCOUNTING_PERIOD_REOPENED event.
     *
     * Reopening permits eligible journal postings
     * into the period again.
     *
     * Returns:
     *
     * HTTP 200 — Period successfully reopened.
     * HTTP 401 — Authentication required.
     * HTTP 403 — Insufficient business permission.
     * HTTP 404 — Period not found.
     * HTTP 409 — Invalid state transition.
     *
     * @param businessId owning business
     * @param periodId period to reopen
     * @param ignoredLegacyRequest optional legacy body
     * @param jwt verified authentication principal
     * @return updated OPEN period
     */
    @PostMapping("/accounting-periods/{periodId}/reopen")
    public ResponseEntity<AccountingPeriod> reopenAccountingPeriod(

        @PathVariable("businessId")
        Long businessId,

        @PathVariable("periodId")
        Long periodId,

        @RequestBody(required = false)
        AccountingPeriodActionRequest ignoredLegacyRequest,

        @AuthenticationPrincipal
        Jwt jwt

    ) {

        // ----------------------------------------------------
        // RESOLVE AUTHENTICATED ACTOR
        // ----------------------------------------------------

        /**
         * The legacy request body is deliberately ignored.
         *
         * This guarantees that a client-supplied actorId
         * cannot determine who reopened the period.
         */
        Long authenticatedActorId =
            getAuthenticatedUserId(jwt);


        // ----------------------------------------------------
        // DELEGATE TO ACCOUNTING PERIOD SERVICE
        // ----------------------------------------------------

        AccountingPeriod reopenedPeriod =
            accountingPeriodService.reopenAccountingPeriod(
                businessId,
                periodId,
                authenticatedActorId
            );


        // ----------------------------------------------------
        // HANDLE MISSING OR CROSS-BUSINESS PERIOD
        // ----------------------------------------------------

        if (reopenedPeriod == null) {

            return ResponseEntity
                .notFound()
                .build();
        }


        // ----------------------------------------------------
        // RETURN UPDATED PERIOD
        // ----------------------------------------------------

        return ResponseEntity.ok(reopenedPeriod);
    }


    // ========================================================
    // RESOLVE AUTHENTICATED USER ID
    // ========================================================

    /**
     * Extract the authenticated user's PostgreSQL ID
     * from Spring Security's verified JWT principal.
     *
     * LedgerBase uses the numeric database user ID
     * as the JWT subject.
     *
     * Example:
     *
     * JWT subject: "42"
     *
     * Result:
     *
     * Long userId = 42L
     *
     * We reject:
     *
     * - Missing JWT principals.
     * - Missing JWT subjects.
     * - Non-numeric subjects.
     * - Zero or negative user IDs.
     *
     * This method only resolves identity.
     *
     * Authorization to modify a specific business
     * is enforced independently by the Spring
     * Security authorization manager.
     *
     * @param jwt validated JWT principal
     * @return authenticated database user ID
     */
    private Long getAuthenticatedUserId(
        Jwt jwt
    ) {

        // ----------------------------------------------------
        // REQUIRE AUTHENTICATED PRINCIPAL
        // ----------------------------------------------------

        if (jwt == null) {

            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Authentication required"
            );
        }


        // ----------------------------------------------------
        // PARSE NUMERIC JWT SUBJECT
        // ----------------------------------------------------

        try {

            Long userId =
                Long.parseLong(jwt.getSubject());


            // ------------------------------------------------
            // REQUIRE POSITIVE USER ID
            // ------------------------------------------------

            if (userId <= 0) {

                throw new NumberFormatException(
                    "Authenticated user ID must be positive"
                );
            }


            // ------------------------------------------------
            // RETURN TRUSTED ACTOR
            // ------------------------------------------------

            return userId;

        } catch (
            NumberFormatException
            | NullPointerException exception
        ) {

            // ------------------------------------------------
            // FAIL CLOSED ON INVALID JWT IDENTITY
            // ------------------------------------------------

            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Invalid authenticated identity"
            );
        }
    }
}
