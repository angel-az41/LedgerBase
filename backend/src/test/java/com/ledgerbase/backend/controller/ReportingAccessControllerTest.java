package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.service.BusinessReportingRevisionService;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — REPORTING ACCESS CONTROLLER TEST
// ============================================================

/**
 * Unit tests for the reporting revision HTTP contract.
 *
 * These tests verify the controller's response after
 * authorization has succeeded.
 *
 * JWT authentication and business-scoped RBAC are
 * independently enforced by Spring Security and are
 * covered by the application's security tests.
 *
 * No real database or HTTP server is required here.
 */
class ReportingAccessControllerTest {

    // ========================================================
    // TEST — RETURN AUTHORITATIVE REVISION
    // ========================================================

    @Test
    void returnsReportingRevisionInNoContentResponse() {

        // Create a mocked reporting revision service.
        BusinessReportingRevisionService revisionService =
            mock(BusinessReportingRevisionService.class);

        // Simulate PostgreSQL returning revision 12.
        when(revisionService.getCurrentRevision(42L))
            .thenReturn(12L);

        ReportingAccessController controller =
            new ReportingAccessController(revisionService);

        // Execute the authorized controller method.
        ResponseEntity<Void> response =
            controller.verifyReportingAccess(42L);

        // Preserve the existing authorization response.
        assertEquals(
            204,
            response.getStatusCode().value()
        );

        // Verify the authoritative revision header.
        assertEquals(
            "12",
            response.getHeaders().getFirst(
                ReportingAccessController.REPORTING_REVISION_HEADER
            )
        );

        // Authorization responses must not be HTTP-cached.
        assertEquals(
            "no-store",
            response.getHeaders().getCacheControl()
        );

        // HTTP 204 must not contain a response body.
        assertNull(response.getBody());

        // Read only the requested business revision.
        verify(revisionService, times(1))
            .getCurrentRevision(42L);

        verifyNoMoreInteractions(revisionService);
    }

    // ========================================================
    // TEST — ZERO IS A VALID REVISION
    // ========================================================

    @Test
    void returnsZeroForNewBusiness() {

        BusinessReportingRevisionService revisionService =
            mock(BusinessReportingRevisionService.class);

        when(revisionService.getCurrentRevision(7L))
            .thenReturn(0L);

        ReportingAccessController controller =
            new ReportingAccessController(revisionService);

        ResponseEntity<Void> response =
            controller.verifyReportingAccess(7L);

        assertEquals(
            204,
            response.getStatusCode().value()
        );

        assertEquals(
            "0",
            response.getHeaders().getFirst(
                ReportingAccessController.REPORTING_REVISION_HEADER
            )
        );
    }

    // ========================================================
    // TEST — DO NOT FABRICATE MISSING REVISIONS
    // ========================================================

    @Test
    void propagatesRevisionLookupFailure() {

        BusinessReportingRevisionService revisionService =
            mock(BusinessReportingRevisionService.class);

        when(revisionService.getCurrentRevision(99L))
            .thenThrow(
                new IllegalStateException(
                    "Reporting revision not found"
                )
            );

        ReportingAccessController controller =
            new ReportingAccessController(revisionService);

        // A database lookup failure must not be converted
        // into an apparently successful revision-zero result.
        assertThrows(
            IllegalStateException.class,
            () -> controller.verifyReportingAccess(99L)
        );
    }
}
