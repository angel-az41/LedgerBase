package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.service.AccountExportService;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

// ============================================================
// LEDGERBASE — ACCOUNT EXPORT CONTROLLER
// ============================================================

/**
 * REST API for downloading the Chart of Accounts.
 *
 * MILESTONE 20 — IMPORT AND EXPORT
 *
 * Supported export formats:
 *
 * 1. CSV  — Comma-separated values
 * 2. XLSX — Native Microsoft Excel workbook
 *
 * BASE URL:
 *
 * /api/v1/businesses/{businessId}/accounts
 *
 * EXAMPLE REQUESTS:
 *
 * GET /api/v1/businesses/2/accounts/export?format=csv
 *
 * GET /api/v1/businesses/2/accounts/export?format=xlsx
 *
 * IMPORTANT:
 *
 * - Export operations are read-only.
 * - Business ID comes from the URL.
 * - AccountExportService validates business existence.
 * - Existing financial records are never modified.
 *
 * Authentication and business-level authorization
 * must be implemented before public deployment.
 */
@RestController
@RequestMapping(
    "/api/v1/businesses/{businessId}/accounts"
)
public class AccountExportController {

    // ========================================================
    // ACCOUNT EXPORT SERVICE
    // ========================================================

    /**
     * Generates CSV and Excel file contents.
     *
     * The service reuses the existing AccountService
     * and business-scoped MyBatis queries.
     */
    private final AccountExportService accountExportService;


    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    public AccountExportController(
            AccountExportService accountExportService) {

        this.accountExportService = accountExportService;
    }


    // ========================================================
    // EXPORT CHART OF ACCOUNTS — CSV OR EXCEL
    // ========================================================

    /**
     * Download the Chart of Accounts for one business.
     *
     * ENDPOINT:
     *
     * GET /api/v1/businesses/{businessId}/accounts/export
     *
     * QUERY PARAMETER:
     *
     * format=csv
     * format=xlsx
     *
     * DEFAULT:
     *
     * When format is omitted, CSV is used.
     *
     * RESPONSES:
     *
     * 200 — File generated successfully.
     *
     * 400 — Unsupported export format.
     *
     * 404 — Business does not exist.
     *
     * The response includes Content-Disposition:
     * attachment so the browser downloads the file.
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportAccounts(

            @PathVariable
            Long businessId,

            @RequestParam(defaultValue = "csv")
            String format) {

        // ====================================================
        // STEP 1 — NORMALIZE THE REQUESTED FORMAT
        // ====================================================

        /**
         * Accept case-insensitive format parameters.
         *
         * Examples:
         *
         * csv  -> csv
         * CSV  -> csv
         * XLSX -> xlsx
         *
         * Locale.ROOT avoids locale-specific
         * letter conversion.
         */
        String normalizedFormat =
            format.trim().toLowerCase(Locale.ROOT);


        // ====================================================
        // STEP 2 — PREPARE FILE CONTENT AND MEDIA TYPE
        // ====================================================

        /**
         * CSV and Excel have different:
         *
         * - File contents
         * - File extensions
         * - HTTP Content-Type values
         *
         * Both reuse AccountExportService.
         */
        byte[] fileData;

        MediaType contentType;


        // ====================================================
        // CSV EXPORT
        // ====================================================

        if ("csv".equals(normalizedFormat)) {

            /**
             * Generate UTF-8 CSV content.
             *
             * text/csv tells the browser this is
             * a comma-separated values file.
             */
            fileData =
                accountExportService.exportAccountsCsv(
                    businessId
                );

            contentType = new MediaType(
                "text",
                "csv",
                StandardCharsets.UTF_8
            );


        // ====================================================
        // EXCEL EXPORT
        // ====================================================

        } else if ("xlsx".equals(normalizedFormat)) {

            /**
             * Generate a native Excel workbook.
             *
             * Apache POI creates the XLSX file
             * inside AccountExportService.
             *
             * Use the official Open XML spreadsheet
             * MIME type for .xlsx downloads.
             */
            fileData =
                accountExportService.exportAccountsExcel(
                    businessId
                );

            contentType = MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument."
                + "spreadsheetml.sheet"
            );


        // ====================================================
        // UNSUPPORTED FORMAT
        // ====================================================

        } else {

            /**
             * Reject unsupported formats explicitly.
             *
             * Do not generate a misleading file.
             *
             * Example:
             *
             * format=pdf -> HTTP 400
             */
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unsupported export format. "
                + "Supported formats: csv, xlsx"
            );
        }


        // ====================================================
        // STEP 3 — CREATE DOWNLOAD FILENAME
        // ====================================================

        /**
         * Include the business ID so downloads from
         * different businesses are distinguishable.
         *
         * EXAMPLES:
         *
         * ledgerbase-accounts-business-2.csv
         *
         * ledgerbase-accounts-business-2.xlsx
         */
        String filename =
            "ledgerbase-accounts-business-"
            + businessId
            + "."
            + normalizedFormat;


        // ====================================================
        // STEP 4 — SET CONTENT-DISPOSITION HEADER
        // ====================================================

        /**
         * Content-Disposition: attachment
         *
         * Instructs the browser to download the
         * file rather than render it as a web page.
         */
        ContentDisposition disposition =
            ContentDisposition
                .attachment()
                .filename(filename)
                .build();


        // ====================================================
        // STEP 5 — RETURN FILE DOWNLOAD
        // ====================================================

        /**
         * Return:
         *
         * HTTP 200
         * Correct Content-Type
         * Download filename
         * Content-Length
         * Cache-Control: no-store
         *
         * no-store discourages browsers and
         * intermediaries from caching exported
         * financial information.
         *
         * nosniff prevents MIME type guessing.
         */
        return ResponseEntity
            .ok()
            .contentType(contentType)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                disposition.toString()
            )
            .header(
                HttpHeaders.CACHE_CONTROL,
                "no-store"
            )
            .header(
                "X-Content-Type-Options",
                "nosniff"
            )
            .contentLength(fileData.length)
            .body(fileData);
    }
}
